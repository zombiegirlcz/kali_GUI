# Project Agent Documentation: X11 Launcher Implementation

## Project Overview

**Goal**: Replace the current VNC-based remote framebuffer with a native X11 client/server architecture using the official Termux-X11 repository as the X server. This eliminates VNC tunneling, improves performance, and provides direct X11 access.

**Key Benefits**:
- Near-zero latency (direct X11)
- Hardware-accelerated OpenGL ES rendering
- Native Android → X11 event translation
- Full X11 feature set (clipboard, drag-and-drop, system tray)
- Uses proven, well-maintained Termux-X11 codebase

## Architecture Overview

```
+--------------------+          +--------------------+          +--------------------+
|   kali_GUI (Host) | <--- X11 --- |  termux-x11 (Container) | <--- PRoot Kali/Parrot |
|   (X11 Client)   |             |   (X Server)       |             | (Desktop Apps)     |
+--------------------+          +--------------------+          +--------------------+
```

### Components

1. **termux-x11 (X Server)** – Android X server from Termux-X11 repository
2. **kali_GUI (X11 Client)** – New X11 client/renderer (replaces VNC)
3. **Core (`com.linux_core`) – Host App** – Manages container, X server startup, adb reverse tunnels

## Current Status

**Repository Location**: `/root/core/kali_GUI`
**Primary Branch**: `master` (production)
**Target Branch**: Development branch for X11 implementation

## Implementation Plan

Based on the design in `/tmp/termux-x11-real/README_IMPLEMENTATION.md`, here are the main phases:

### Phase 1: Core Integration
1. Modify `assets/nh/desktop_start` to launch termux-x11 instead of vncserver
2. Add adb reverse tunneling support in core
3. Package termux-x11 binary into core APK
4. Update `TerminalActivity.ensureDesktopStarted()` to start X server

### Phase 2: termux-x11 Module Setup
1. Import Termux-X11 repository as Gradle module
2. Build termux-x11 binary
3. Configure Gradle dependencies
4. Test X server startup inside PRoot container

### Phase 3: X11 Client Implementation (kali_GUI)
1. Create `X11Client.kt` (X11 protocol client)
2. Create `X11Renderer.kt` (OpenGL ES renderer)
3. Update `LauncherActivity.kt` to use X11 client
4. Implement MIT‑SHM support for efficient framebuffer sharing
5. Add touch and key event translation

### Phase 4: Integration & Testing
1. Setup complete adb reverse tunnel
2. Test X11 client connection
3. Verify framebuffer rendering
4. Test input events (touch, keyboard)
5. End-to-end desktop testing

## Task Breakdown

| Task ID | Task Name | Status | Priority | Dependencies |
|---------|-----------|--------|----------|--------------|
| T1 | Core Integration | **BLOCKED** | High | None |
| T2 | termux-x11 Module Setup | **NOT STARTED** | High | T1 |
| T3 | X11 Client Implementation | **NOT STARTED** | High | T2 |
| T4 | Integration & Testing | **NOT STARTED** | Medium | T3 |

## Current Challenges

1. **Termux-X11 Integration**: Need to successfully build and package the termux-x11 binary
2. **X11 Protocol**: Implement X11 client protocol (MIT‑SHM, PutImage, events)
3. **OpenGL ES Renderer**: Create efficient framebuffer rendering
4. **Adb Reverse Tunneling**: Setup proper adb reverse for X server
5. **Input Translation**: Map Android touch/key events to X11 events

## Execution Strategy

Following the **executing-plans skill** guidelines:

### Planning Approach
- **Batch Execution**: Execute tasks in batches with architect review checkpoints
- **TDD First**: Failing tests first for production code
- **Critical Review**: Before starting each batch, review plan critically for gaps
- **Isolation**: Use isolated workspace (git worktrees) when needed
- **Verification**: Run verifications as specified before each checkpoint

### Review Checkpoints

**Batch 1**: Core Integration
- Review `assets/nh/desktop_start` changes
- Verify adb reverse setup
- Test X server startup in container

**Batch 2**: termux-x11 Module
- Verify termux-x11 binary build
- Test Gradle integration
- Check packaging into core APK

**Batch 3**: X11 Client Implementation
- Implement basic X11 client connection
- Add framebuffer rendering
- Test MIT‑SHM support

**Batch 4**: Integration & Testing
- End-to-end testing
- Performance verification
- User acceptance testing

## Tools & Technologies

### Development Environment
- **Android Studio / Gradle** - Main build system
- **Kotlin** - Primary language
- **OpenGL ES** - Hardware-accelerated rendering
- **Termux-X11** - X server
- **ADB Reverse** - Port forwarding
- **Git** - Version control

### Key Components
- **termux-x11**: X server (from termux/termux-x11)
- **OpenGL ES**: Hardware-accelerated rendering
- **MIT‑SHM**: Shared memory for efficient framebuffer sharing
- **adb reverse**: Secure tunnel for X11
- **PRoot container**: Linux environment inside Android

## Verification Process

### Unit Testing
- X11 client protocol parsing
- OpenGL ES renderer functionality
- Event translation correctness
- Integration with core services

### Integration Testing
- adb reverse tunnel functionality
- X server startup and shutdown
- Client-server connection
- Framebuffer updates
- Input event forwarding

### Performance Testing
- Framebuffer update latency
- Rendering frame rate
- Input responsiveness
- Memory usage

### User Acceptance Testing
- Desktop launch workflow
- Basic GUI interaction
- Clipboard functionality (if implemented)
- Multi-touch support

## Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Termux-X11 build failure | Medium | High | Use official releases, test on multiple devices |
| X11 protocol implementation complexity | High | High | Start with minimal client, add features incrementally |
| Adb reverse compatibility issues | Medium | Medium | Test on target devices, fallback to alternative tunneling |
| Performance on low-end devices | High | Medium | Implement fallback to software rendering |
| Input event mapping issues | Medium | Medium | Use existing Android input frameworks |

## Project Timeline

**Week 1**: Core Integration + termux-x11 setup
**Week 2**: X11 client implementation
**Week 3**: Renderer and event translation
**Week 4**: Integration testing and optimization
**Week 5**: Final testing and deployment preparation

## Communication Plan

### Status Reporting
- **Daily**: Task progress updates
- **Batch Completion**: Implementation reports with verification output
- **Blocker Issues**: Immediate reporting with proposed solutions

### Decision Making
- **Technical Decisions**: Discuss with architect before implementation
- **Design Changes**: Review with project lead before execution
- **Scope Changes**: Formal change request process

## Quality Assurance

### Code Quality
- **TDD**: Unit tests before implementation
- **Code Review**: Peer review for critical changes
- **Documentation**: Update AGENT.md with changes
- **Testing**: Comprehensive test coverage

### Build Quality
- **Modular Design**: Separate concerns, clear dependencies
- **Error Handling**: Robust error handling and recovery
- **Performance**: Optimized for mobile constraints
- **Compatibility**: Tested on target devices

## Next Steps

1. **Immediate**: Review current VNC code and identify exact changes needed in `assets/nh/desktop_start`
2. **Short-term**: Setup isolated workspace for X11 implementation
3. **Medium-term**: Begin core integration (Batch 1)
4. **Long-term**: Complete full X11 integration and testing

## Usage Instructions

### To Execute Implementation

1. Navigate to project root: `cd /root/core/kali_GUI`
2. Use the plan execution tool:
   ```
   /skill:executing-plans
   ```
3. Follow the guided execution with checkpoints

### To Get Status Update

```
/plan status
```

### To Get Implementation Report

```
/plan report
```

### To Execute Specific Batch

```
/plan execute batch-1
```

### To Complete Development

After all tasks are complete and verified:
```
/skill:finishing-a-development-branch
```

## Version Control

### Branch Management
- **Main Branch**: Production releases
- **Development Branch**: Active development
- **Feature Branches**: Individual X11 implementation work

### Commit Conventions
- **Feature**: `feat: <description>`
- **Bug Fix**: `fix: <description>`
- **Documentation**: `docs: <description>`
- **Refactor**: `refactor: <description>`

## References

1. **Termux-X11 Repository**: https://github.com/termux/termux-x11
2. **Project Documentation**: Various `*.md` files in the repository
3. **Android Development**: Official Android documentation
4. **OpenGL ES**: Khronos OpenGL ES specification
5. **X11 Protocol**: MIT X11 protocol documentation

## Contact & Support

For questions or issues:
- **Project Lead**: Review team
- **Technical Support**: Development team
- **Emergency Issues**: Project lead immediate escalation

---

**Document Version**: 1.0
**Created**: $(date)
**Last Updated**: $(date)
**Status**: In Development

*This AGENT.md file documents the X11 launcher implementation project, following the executing-plans skill guidelines for structured development with review checkpoints.*