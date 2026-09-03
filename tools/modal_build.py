"""Modal build pipeline for kali_GUI.

Zdroj pravdy je GitHub (zombiegirlcz/kali_GUI), ne lokální disk.
Modal si zdrojový strom natahuje sám přes git clone/fetch přímo z GitHubu —
žádný upload z telefonu, žádný rsync přes mobilní síť.

Volume layout:
  /vol/keys/release.jks        – signing keystore (persistent)
  /vol/src/                    – project source tree (git clone z GitHubu)
  /vol/gradle-cache/           – gradle dependency cache (persistent)
  /vol/builds/kali-gui-debug.apk – latest built APK

Setup:
  1) modal secret create build-secrets RELEASE_JKS_BASE64=$(base64 -w0 app/release.jks)
  2) modal secret create github-token GITHUB_TOKEN=<personal access token>
  3) modal run modal_build.py init     # store keystore
  4) modal run modal_build.py sync     # git clone/pull zdroje z GitHubu
  5) modal run modal_build.py build    # Gradle assembleDebug

Sync (VŽDY samostatně — buildy ho nikdy nevolají):
  modal run modal_build.py sync   # git clone (poprvé) nebo fetch+reset --hard (dál)
  modal run modal_build.py clean  # smaže src + gradle-cache na Volume (keys/builds zůstanou)

Lokální git push na GitHub je jediný krok z telefonu. Modal si zbytek
(stažení, sestavení) dělá sám přes svou vlastní, mnohem stabilnější síť.
"""

import json
import modal
import os
import shutil
import subprocess
import sys

APP_NAME = "kali-gui-build"
VOLUME_NAME = "kali-gui-build-data"
APK_OUTPUT = "kali-gui-debug.apk"

app = modal.App(APP_NAME)

ANDROID_SDK_ROOT = "/opt/android-sdk"

build_vol = modal.Volume.from_name(VOLUME_NAME, create_if_missing=True)

GITHUB_REPO = "zombiegirlcz/kali_GUI"
GITHUB_BRANCH = "master"


# ── Image with Android SDK + JDK 21 ─────────────────────────────────────────
base_image = (
    modal.Image.from_registry("eclipse-temurin:21-jdk")
    .apt_install("unzip", "wget", "git", "file", "rsync", "python3", "python3-pip", "python-is-python3")
    .run_commands(
        "mkdir -p /opt/android-sdk/cmdline-tools",
        "wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
        " -O /tmp/cmdline-tools.zip",
        "unzip -q /tmp/cmdline-tools.zip -d /opt/android-sdk/cmdline-tools",
        "mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest",
        "rm /tmp/cmdline-tools.zip",
        "yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null 2>&1 || true",
        "/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --install"
        " 'platforms;android-36' 'build-tools;36.0.0'",
    )
    .env({
        "ANDROID_HOME": ANDROID_SDK_ROOT,
        "ANDROID_SDK_ROOT": ANDROID_SDK_ROOT,
        "JAVA_HOME": "/opt/java/openjdk",
        "GRADLE_USER_HOME": "/vol/gradle-cache",
    })
)


# ── Sync source from GitHub to Volume ────────────────────────────────────────
@app.function(
    image=base_image,
    volumes={"/vol": build_vol},
    secrets=[modal.Secret.from_name("github-token")],
    timeout=600,
    memory=1024,
)
def sync():
    """Git clone (poprvé) nebo fetch + reset --hard (dál) přímo z GitHubu."""
    token = os.environ.get("GITHUB_TOKEN", "")
    auth = f"{token}@" if token else ""
    repo_url = f"https://{auth}github.com/{GITHUB_REPO}.git"
    dest = "/vol/src"

    if os.path.isdir(os.path.join(dest, ".git")):
        print(f"[sync] Repo už existuje na Volume — fetch + reset --hard origin/{GITHUB_BRANCH}")
        subprocess.run(["git", "remote", "set-url", "origin", repo_url], cwd=dest, check=True)
        subprocess.run(["git", "fetch", "origin", GITHUB_BRANCH], cwd=dest, check=True)
        subprocess.run(["git", "reset", "--hard", f"origin/{GITHUB_BRANCH}"], cwd=dest, check=True)
    else:
        print(f"[sync] Klonuji {GITHUB_REPO}@{GITHUB_BRANCH} -> {dest}")
        if os.path.isdir(dest):
            shutil.rmtree(dest)
        subprocess.run(
            ["git", "clone", "--branch", GITHUB_BRANCH, repo_url, dest],
            check=True,
        )

    subprocess.run(
        ["git", "remote", "set-url", "origin", f"https://github.com/{GITHUB_REPO}.git"],
        cwd=dest, check=True,
    )
    build_vol.commit()
    print("[sync] Hotovo.")


@app.function(
    image=base_image,
    volumes={"/vol": build_vol},
    timeout=600,
    memory=1024,
)
def clean():
    """Smaže /vol/src a /vol/gradle-cache (keys/builds zůstanou)."""
    for p in ("/vol/src", "/vol/gradle-cache"):
        if os.path.isdir(p):
            shutil.rmtree(p, ignore_errors=True)
            print(f"[clean] removed {p}")
        else:
            print(f"[clean] {p} absent")
    build_vol.commit()
    print("[clean] Done.")


# ── Initialize signing key on Volume ─────────────────────────────────────────
@app.function(
    image=base_image,
    volumes={"/vol": build_vol},
    secrets=[modal.Secret.from_name("build-secrets")],
    timeout=600,
    memory=1024,
)
def init_keys():
    """Store the signing keystore on the persistent Volume."""
    keys_dir = "/vol/keys"
    os.makedirs(keys_dir, exist_ok=True)

    key_path = os.path.join(keys_dir, "release.jks")

    if os.path.exists(key_path):
        print(f"[init] Key already exists at {key_path} (skipping).")
        return

    secret_key = os.environ.get("RELEASE_JKS_BASE64")
    if secret_key:
        import base64
        with open(key_path, "wb") as f:
            f.write(base64.b64decode(secret_key))
    else:
        print(
            "[init] RELEASE_JKS_BASE64 not set.  Create the secret first:\n"
            "  modal secret create build-secrets \\\n"
            "    RELEASE_JKS_BASE64=$(base64 -w0 app/release.jks)"
        )
        return

    os.chmod(key_path, 0o600)
    build_vol.commit()
    print(f"[init] Key stored at {key_path}")


# ── Build APK ────────────────────────────────────────────────────────────────
@app.function(
    image=base_image,
    volumes={"/vol": build_vol},
    secrets=[modal.Secret.from_name("build-secrets")],
    timeout=3600,
    memory=8192,
    cpu=4,
)
def build():
    """Build the debug APK from the source tree on the Volume."""
    src_dir = "/vol/src"
    vol_keys = "/vol/keys"

    if not os.path.isdir(src_dir):
        print(
            "[build] Source directory not found on Volume.  "
            "Sync first:\n"
            "  modal run modal_build.py::sync",
            file=sys.stderr,
        )
        sys.exit(1)

    with open(os.path.join(src_dir, "local.properties"), "w") as f:
        f.write(f"sdk.dir={ANDROID_SDK_ROOT}\n")

    key_src = os.path.join(vol_keys, "release.jks")
    key_dst = os.path.join(src_dir, "app", "release.jks")
    if os.path.exists(key_src):
        shutil.copy2(key_src, key_dst)
        os.chmod(key_dst, 0o600)
        print(f"[build] Signing key copied from Volume: {key_dst}")
    elif not os.path.exists(key_dst):
        print(
            "[build] WARNING: No signing key found! "
            "Run init_keys first (or ensure app/release.jks is in source tree)."
        )

    gradlew = os.path.join(src_dir, "gradlew")
    os.chmod(gradlew, 0o755)

    os.makedirs("/vol/gradle-cache", exist_ok=True)

    print("[build] Running: ./gradlew assembleDebug")
    result = subprocess.run(
        ["./gradlew", "assembleDebug", "--no-daemon", "--stacktrace"],
        cwd=src_dir,
        capture_output=False,
        text=True,
    )

    if result.returncode != 0:
        print("[build] BUILD FAILED", file=sys.stderr)
        sys.exit(result.returncode)

    apk_path = os.path.join(src_dir, "app/build/outputs/apk/debug/app-debug.apk")
    if not os.path.exists(apk_path):
        print("[build] APK not found at expected path!", file=sys.stderr)
        sys.exit(1)

    size_mb = os.path.getsize(apk_path) / (1024 * 1024)
    print(f"[build] APK built: {apk_path} ({size_mb:.1f} MB)")

    out_dir = "/vol/builds"
    os.makedirs(out_dir, exist_ok=True)
    dest = os.path.join(out_dir, APK_OUTPUT)
    shutil.copy2(apk_path, dest)
    build_vol.commit()
    print(f"[build] APK copied to Volume: {dest}")


@app.function(
    image=base_image,
    volumes={"/vol": build_vol},
    timeout=300,
)
def publish_apk():
    """Copy the built APK into /vol/src so it is reachable via `modal volume get`."""
    src = "/vol/builds/kali-gui-debug.apk"
    if not os.path.exists(src):
        print("[publish] No built APK at /vol/builds — run `build` first.", file=sys.stderr)
        sys.exit(1)
    dst = "/vol/src/kali-gui-debug.apk"
    shutil.copy2(src, dst)
    build_vol.commit()
    print(f"[publish] APK published to {dst} ({os.path.getsize(dst) / (1024*1024):.1f} MB)")


# ── Verify APK signature ─────────────────────────────────────────────────────
@app.function(
    image=base_image,
    volumes={"/vol": build_vol},
    timeout=300,
)
def verify_apk():
    """Print the signer certificate of the built APK."""
    apk = "/vol/builds/app-debug.apk"
    if not os.path.exists(apk):
        print("[verify] APK not found on Volume!")
        sys.exit(1)
    apksigner = f"{ANDROID_SDK_ROOT}/build-tools/36.0.0/apksigner"
    print(f"[verify] {apk}")
    subprocess.run(
        [apksigner, "verify", "--print-certs", apk],
        check=False,
        text=True,
    )


# ── Utility ──────────────────────────────────────────────────────────────────
@app.function(
    image=base_image,
    volumes={"/vol": build_vol},
    timeout=300,
)
def list_volume():
    """Print files stored on the build Volume."""
    for root, dirs, files in os.walk("/vol"):
        for f in files:
            fp = os.path.join(root, f)
            try:
                size = os.path.getsize(fp)
                print(f"  {fp}  ({size:,} bytes)")
            except OSError:
                print(f"  {fp}  (unreadable)")


@app.local_entrypoint()
def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "build"
    if cmd == "init":
        init_keys.remote()
    elif cmd == "sync":
        sync.remote()
    elif cmd == "clean":
        clean.remote()
    elif cmd == "build":
        build.remote()
    elif cmd == "list":
        list_volume.remote()
    else:
        print("Usage: modal run modal_build.py [init|sync|clean|build|list]")
