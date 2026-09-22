# kali_GUI — X11 launcher

Grafická vrstva ("obličej") systému kali_core_emulator. Aplikace se chová jako
**X11 server + klient na Androidu**: spustí vestavěný X.org server, připojí se
k němu a vykresluje okna GUI aplikací běžících v proot Linuxu (Kali/Parrot) z
hostitelské aplikace `kali_core_emulator`.

- **applicationId:** `com.linux_core.xlauncher`
- **minSdk / targetSdk:** 28 · **compileSdk:** 36
- **Podpis:** sdílený debug keystore (`release.jks`) s ostatními aplikacemi
  balíku → stejný podpis = sdílené UID = vzájemný přístup k datům/rootfs.

## Struktura

```
app/src/main/java/com/linux_core/xlauncher/   ← VLASTNÍ kód (4 soubory)
  LauncherActivity.kt   – vstupní Activity, UI a životní cyklus
  X11Client.kt          – X11 protokol: připojení, čtení/zápis requestů
  X11Renderer.kt        – vykreslení framebufferu (GetImage → Canvas)
  ConnectionConfig.kt   – konfigurace připojení (display, loopback)

app/src/main/linux-x11/src/main/cpp/          ← VENDOROVANÝ X.org build strom
  xserver/ libx11/ pixman/ xkbcomp/ libxfont/ libepoxy/ lorie/ ...
  patches/   – lokální patche na upstream X.org zdrojáky
  recipes/   – build recepty jednotlivých knihoven
  CMakeLists.txt – sestavení celého X11 stacku přes NDK/CMake
```

> **Pozn. ke struktuře:** `linux-x11/` je záměrně vendorovaný a patchovaný build
> strom X.org (~4500 souborů, ~20 knihoven), ne jeden upstream projekt. Proto
> **není** git submodule — jednotlivé knihovny jsou upravené lokálními patchi v
> `patches/`. Vlastní kód aplikace jsou pouze 4 soubory v `xlauncher/`.

## Build

Standardní Gradle (Groovy DSL). Nativní X11 stack staví CMake přes Android NDK
jako součást `assembleDebug`. Kvůli velikosti a NDK závislostem se plný build
dělá lokálně / přes `mbuild` pipeline v `kali_core_emulator`, ne na GitHub CI.

```
./gradlew assembleDebug
```

## Vztah k ostatním repo

| Repo | Role |
|------|------|
| `kali_core_emulator` | motor — proot Linux, terminál, VPN/MITM, USB |
| `kali_ai_assistant`  | AI vrstva — asistent, pi-bridge |
| **`kali_GUI`**       | **obličej — X11 launcher (toto repo)** |
