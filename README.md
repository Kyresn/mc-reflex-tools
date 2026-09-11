# MC Reflex Tools

Minecraft Java Edition `26.2` client mod scaffold for **official NVIDIA SDK integration only**.

## Scope

- Minecraft Java Edition `26.2` only.
- Fabric client artifact.
- Windows x64 and Minecraft's native Vulkan renderer.
- NVIDIA Streamline integration for Reflex first, then DLSS Super Resolution.
- No legacy Minecraft support.
- No OpenGL path.
- No custom AntiLag, frame scheduler, GPU-timing estimator, shader upscaler, or non-NVIDIA fallback.

## Current state: M2 — Reflex Low Latency verified

The Fabric client resolves and verifies Minecraft's Vulkan context, loads the official
NVIDIA Streamline SDK, and drives Reflex Low Latency through it. Reflex is verified
end-to-end against NVIDIA's own Reflex Test Utility — `PC Latency` moves from `0.000`
to `8.3–22.3 ms` depending on the scene, with a non-zero input-to-simulation component.

DLSS Super Resolution and DLSS-G are **not** functional yet. They are blocked on an NGX
context (see below) and on resource tagging that is not implemented.

The full record — evidence, measurement precision, disproven conclusions, and open
issues — is in [`docs/reflex-verification.md`](docs/reflex-verification.md). Read it
before re-investigating Reflex behavior.

A feature becomes eligible only when all of these conditions hold:

1. Minecraft `26.2` runs with its native Vulkan renderer.
2. The Mod can safely access Minecraft's active `VkInstance`, `VkPhysicalDevice`, `VkDevice`, graphics `VkQueue`, queue-family index, and `VkSwapchainKHR`.
3. The handles are verified to belong to Minecraft's actual submit/present path.
4. An NVIDIA-approved Streamline SDK package and redistribution plan are available.
5. The NVIDIA runtime initializes successfully on a supported GPU and driver.

Anything else remains unavailable. The Mod must never create a second Vulkan device, queue, swapchain, or Present loop.

Known blockers outside the Mod:

- `dlssGMode` must stay `OFF` in `config/mc_reflex_tools.json`. Frame Generation cannot
  run without an NGX context, and enabling it makes the Reflex Test Utility's Frame Gen
  cycles fail.
- The NVIDIA Reflex HUD does not composite over the game window. It is drawn by the
  driver and is not affected by anything in this repository.
- `VK_NV_low_latency2` is deliberately **not** enabled on Minecraft's device. The fix
  exists (`VulkanBackendDeviceExtensionsMixin`) but enabling it makes Reflex strictly
  worse, so it is gated behind `-Dmc_reflex_tools.vulkanLowLatency2=true` and off by
  default. See [`docs/reflex-verification.md`](docs/reflex-verification.md) §7.3.

## Modules

| Module | Responsibility |
| --- | --- |
| `common-api` | Feature status, Vulkan context contract, and lifecycle interfaces. |
| `vulkan-context` | Version-independent contract for Minecraft-owned Vulkan context discovery. |
| `nvidia-sdk-java` | JNI boundary and non-emulating unavailable provider. |
| `nvidia-sdk-native` | CMake native bridge. NVIDIA SDK binaries are not vendored. |
| `fabric` | Fabric 26.2 client artifact and sole active loader target. |

## Planned delivery

### M0 — scaffold and safety gate

- [x] Private GitHub repository with `main` as the default branch.
- [x] Minecraft `26.2` Fabric Gradle module.
- [x] Java API and Windows x64 JNI boundary.
- [x] No self-written AntiLag or Super Resolution fallback.
- [x] Build verification with JDK 25 and Gradle wrapper.
- [x] Read-only Fabric 26.2 Vulkan context probe validated against Minecraft-owned device, queue, and swapchain.
- [x] Validate windowed, borderless-fullscreen, and exclusive-fullscreen Vulkan presentation modes.

### M1 — Vulkan context verification

- [x] Map the exact Minecraft 26.2 Vulkan renderer classes and lifecycle.
- [x] Implement a read-only resolver for the Minecraft-owned Vulkan handles.
- [x] Add strict validation, version fingerprinting, and disabled-by-default diagnostics.
- [x] Verify initial creation in windowed, borderless-fullscreen, and exclusive-fullscreen presentation modes.
- [x] Verify runtime windowed ↔ borderless fullscreen ↔ windowed transitions and refresh the borrowed swapchain handle.
- [x] Trace the actual simulation, Vulkan submit, and Present boundaries without changing their behavior.
- [ ] Verify runtime exclusive-fullscreen transition through Minecraft's public settings screen, world switch, and shutdown.

### M2 — official NVIDIA Reflex

- [x] Integrate an approved NVIDIA Streamline SDK distribution through `nvidia-sdk-native`.
- [x] Align the Java marker contract with Streamline 2.14.1 PCL markers and add a native SDK-readiness probe.
- [x] Initialize against Minecraft's existing Vulkan device and graphics queue.
- [x] Map actual input, simulation, queue-submit, and present boundaries to official Reflex markers.
- [x] Call only NVIDIA SDK sleep/marker functions; implement no custom timing algorithm.
- [x] Validate marker order and failure handling on supported NVIDIA hardware.
- [x] Answer the driver's out-of-band latency ping from the window procedure.
- [x] Verify end-to-end against NVIDIA's Reflex Test Utility (`PC Latency` non-zero, `I>S` measured).
- [ ] Trace why `VK_NV_low_latency_2` did not initialize on Minecraft's device.

### M3 — DLSS Super Resolution

- [ ] Provide a valid `applicationId` to `slInit` so `sl.common` creates an NGX context.
- [ ] Establish Minecraft-native color, depth, motion-vector, jitter, exposure, and history-reset paths.
- [ ] Tag Vulkan resources and command buffers for Streamline.
- [ ] Run official DLSS Super Resolution only after M2 is stable.
- [ ] Keep HUD and GUI outside the DLSS input path.

### M4 — Frame Generation evaluation

- [ ] Evaluate only after Reflex and DLSS SR are stable under resizing, world changes, recording, and compatibility tests.
- [ ] Remain experimental and disabled by default.

## Build prerequisites

- JDK 25 for Minecraft 26.2.
- CMake and a C++20 Windows toolchain for `nvidia-sdk-native`.

## Build

```bash
./gradlew build
cmake -S nvidia-sdk-native -B nvidia-sdk-native/build
cmake --build nvidia-sdk-native/build --config Release
```

## Non-goals

- Minecraft 1.8.9, 1.12.2, or any legacy version.
- OpenGL integration.
- G-SYNC enablement; this is driver/display controlled.
- DLSS/Reflex behavior emulation or general-GPU substitutes.
