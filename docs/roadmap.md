# Roadmap

M0–M2 are complete. The scope is fixed: Minecraft Java Edition 26.2, Fabric, Windows x64, and
Minecraft's own Vulkan renderer. No legacy Minecraft, no OpenGL path, and no self-written
AntiLag, frame scheduler, GPU-timing estimator, or non-NVIDIA upscaler fallback.

## M0 — scaffold and safety gate

- [x] GitHub repository with `main` as the default branch.
- [x] Minecraft `26.2` Fabric Gradle module.
- [x] Java API and Windows x64 JNI boundary.
- [x] No self-written AntiLag or Super Resolution fallback.
- [x] Build verification with JDK 25 and the Gradle wrapper.
- [x] Read-only Fabric 26.2 Vulkan context probe validated against Minecraft-owned device, queue, and swapchain.
- [x] Validate windowed, borderless-fullscreen, and exclusive-fullscreen Vulkan presentation modes.

## M1 — Vulkan context verification

- [x] Map the exact Minecraft 26.2 Vulkan renderer classes and lifecycle.
- [x] Implement a read-only resolver for the Minecraft-owned Vulkan handles.
- [x] Add strict validation, version fingerprinting, and disabled-by-default diagnostics.
- [x] Verify initial creation in windowed, borderless-fullscreen, and exclusive-fullscreen presentation modes.
- [x] Verify runtime windowed ↔ borderless fullscreen ↔ windowed transitions and refresh the borrowed swapchain handle.
- [x] Trace the actual simulation, Vulkan submit, and Present boundaries without changing their behavior.
- [ ] Verify the runtime exclusive-fullscreen transition through Minecraft's public settings screen, world switch, and shutdown.

## M2 — official NVIDIA Reflex

- [x] Integrate an approved NVIDIA Streamline SDK distribution through `nvidia-sdk-native`.
- [x] Align the Java marker contract with Streamline 2.14.1 PCL markers and add a native SDK-readiness probe.
- [x] Initialize against Minecraft's existing Vulkan device and graphics queue.
- [x] Map actual input, simulation, queue-submit, and present boundaries to official Reflex markers.
- [x] Call only NVIDIA SDK sleep/marker functions; implement no custom timing algorithm.
- [x] Validate marker order and failure handling on supported NVIDIA hardware.
- [x] Answer the driver's out-of-band latency ping from the window procedure.
- [x] Verify end-to-end against NVIDIA's Reflex Test Utility (`PC Latency` non-zero, `I>S` measured).
- [x] Expose mode, frame limit, and live telemetry through an in-game settings screen and a `/reflex` command, both of which re-apply through the existing per-tick path.
- [x] Trace why `VK_NV_low_latency_2` did not initialize on Minecraft's device. Root cause confirmed — the documented fix works but measurably regresses Reflex, so it stays off by default and the cause of the regression is still unknown. See [reflex-verification.md](reflex-verification.md) §7.3.

## M3 — DLSS Super Resolution

- [ ] Provide a valid `applicationId` to `slInit` so `sl.common` creates an NGX context.
- [ ] Establish Minecraft-native color, depth, motion-vector, jitter, exposure, and history-reset paths.
- [ ] Tag Vulkan resources and command buffers for Streamline.
- [ ] Run official DLSS Super Resolution only after M2 is stable.
- [ ] Keep HUD and GUI outside the DLSS input path.

## M4 — Frame Generation evaluation

- [ ] Evaluate only after Reflex and DLSS SR are stable under resizing, world changes, recording, and compatibility tests.
- [ ] Remain experimental and disabled by default.
