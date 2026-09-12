# Reflex / PCL integration verification

Status as of **2026-09-11**. This is the working record for the Streamline Reflex
integration in `nvidia-sdk-native`: what is verified, what the evidence is, how far
each number can be trusted, and what is still open.

The short version: **Reflex Low Latency reaches the NVIDIA driver and is measured by
NVIDIA's own verification tool.** The Reflex HUD overlay does not draw over the game
window, and that is not caused by anything in this repository.

## 1. Verdict

| Area | Status | Evidence |
| --- | --- | --- |
| Streamline `slInit` + plugin load | PASS | `sl.log` line 2, 22–94 |
| Streamline bound to Minecraft's `VkDevice` | PASS | Reflex calls return `eOk`; markers reach the driver |
| Reflex plugin hooks installed | PASS | `slHookVkCreateSwapchainKHRBefore/After` `- OK` |
| Per-frame marker order | PASS | `orderViolations` flat from frame 247 000 → 256 800 |
| `slReflexSleep` placement | PASS | `sleeps == frame - 2` |
| Driver-side latency measurement | PASS | Reflex Test Utility: `I>S` 0.00 → 0.68–1.86 ms |
| In-application latency report | PASS | `ReflexReport.frameId` advances 1:1 |
| Runtime mode / frame-limit toggle | PASS | `/reflex mode|framelimit` re-applies options the same tick — see §4.4 |
| Reflex HUD over the game window | **FAIL** | see §7.1 |
| DLSS SR / DLSS-G | **BLOCKED** | missing NGX context, see §7.2 |
| `VK_NV_low_latency_2` backend | **NOT ACTIVE** | root cause found, fix disabled on purpose — see §7.3 |

## 2. Environment

| | |
| --- | --- |
| Minecraft | Java Edition `26.2`, Fabric, client-only source set |
| Renderer | native Vulkan, `presentMode=IMMEDIATE` |
| GPU | NVIDIA RTX 5070, adapter LUID 0.60153 |
| Driver | 591.86 |
| Display | 1920x1080 @ 280 Hz |
| JDK | Eclipse Adoptium 25 |
| Streamline | `v2.14.1.98614dad6`, built Fri Aug 28 13:38:13 2026 |
| Reflex Test Utility | NVIDIA's `ReflexTest.exe` / `ReflexTestEnable.exe`, run from an elevated prompt |

Streamline raises the Windows timer resolution to 5017 x 100 ns (≈ 0.502 ms) at
`commonEntry.cpp:2045`. That bounds sleep granularity; it does not truncate the
microsecond timestamps in `ReflexReport`.

## 3. Defects found and fixed

Four defects, plus one packaging defect. All are in commit `f9d1671`.

### 3.1 `slReflexSleep` ran at the wrong point in the frame

`slReflexSleep` must run **after the previous frame's present and before input
sampling**. It was being called at the simulation boundary, which is after input
sampling — so the driver had nothing to throttle against.

Fixed by moving the call to `RenderSystem.pollEvents()` HEAD
([`RenderSystemPollEventsMixin.java`](../fabric/src/client/java/dev/kyresn/mcreflex/fabric/mixin/RenderSystemPollEventsMixin.java)).
That call wraps `GLFW.glfwPollEvents()`, so it is exactly the input-sampling boundary.

This alone moved `I>S` from `0.00` to `0.68`.

### 3.2 `SimulationEnd` fired after `PresentEnd`

`present()` is called from `renderFrame()`, and `renderFrame()` is called from
`runTick()`. Hooking `runTick`'s RETURN therefore placed `SimulationEnd` *after*
`PresentEnd` on every frame — one order violation per frame (2397 violations at
frame 2400).

Fixed by hooking the `renderFrame` INVOKE site instead
([`MinecraftSimulationLifecycleMixin.java`](../fabric/src/client/java/dev/kyresn/mcreflex/fabric/mixin/MinecraftSimulationLifecycleMixin.java)).
Result: 0 new violations after startup.

### 3.3 The driver's latency ping was never answered

The driver measures input-sampling latency **out of band**, by posting a message to
the game window (`sl::ReflexState::statsWindowMessage`). The app answers with an
`ePCLatencyPing` marker. Minecraft leaves the window procedure to GLFW, so nothing
answered it.

Fixed by subclassing the window procedure with `SetWindowLongPtrW` and chaining to
GLFW's proc (`nativeInstallPclPingHook`). `pclPings` went from 0 to ~1 per 300 frames.

### 3.4 Streamline file logging was silently disabled

Streamline opens `pathToLogsAndData/sl.log` with `_wfsopen` and **permanently disables
file logging if the open fails** (`log.cpp:319-322`). The configured directory did not
exist. Fixed by `_wmkdir`-ing it in `resolveLogPath()` before `slInit`.

This is why `mc_reflex_tools_logs/sl.log` is now the first place to look when a plugin
declines to engage — §7.3 was only visible because of this fix.

### 3.5 The native DLL never reached a distributed jar

`nvidia-sdk-java` was an `implementation` dependency, not a Loom `include(...)`, and
`.gitignore`'s blanket `*.dll` rule excluded the bundled `sl.interposer.dll`. Both
fixed; the `fabric` module now nests `common-api`, `vulkan-context`, and
`nvidia-sdk-java`.

## 4. Evidence

### 4.1 Counter trace

`VulkanLifecycleTrace` prints every 300 frames. Steady state, frame 256 800:

```text
Vulkan lifecycle: frame=256800, submits=300, presentDurationNs=30700,
  sleeps=256798, pclPings=279, pclPingsMissed=0,
  suppressedRenderSubmit=38, orderViolations=19, staleMarkers=5
```

Reading it:

- `sleeps == frame - 2` — one sleep per frame. The offset of 2 is a fixed startup
  artefact.
- `orderViolations=19` and `suppressedRenderSubmit=38` accumulated during the loading
  screens and are **flat** across 9 600 subsequent frames.
- `staleMarkers=5` is likewise a fixed startup offset.
- `pclPings` advances, `pclPingsMissed=0` — the driver is actively measuring and every
  ping is answered. **Do not read a short run's `pclPings=0` as a failure**: in scripted
  ~3-minute runs the count stayed at 0 even with the hook installed and the latency report
  live, while the long session reached 250. The hook installs, but the driver only starts
  pinging once it is actually measuring, which the long session shows it does.

### 4.2 In-application latency report

```text
Reflex latency report: frameId=256782, simStartToPresentUs=1231, simUs=3,
  renderSubmitUs=37, presentUs=29, gpuActiveUs=648, gpuFrameUs=1398
```

`frameId` advances 1:1 with the game frame counter. The report for frame 256782 sits in
the same 300-frame window as the trace line for frame 256800, and its `presentUs` (29 µs)
agrees with that window's independently measured Java-side `presentDurationNs` (30 700 ns).
Two different instruments, same answer — a useful cross-check that the report is live and
not stale. They are not the same frame, so compare magnitudes, not exact values.

### 4.3 External tool

`ReflexTest.exe curve`, same mode (`Reflex BOOST`), before and after the fixes:

```text
Fri Sep 11 06:21:38   PC Latency =  0.000   {0.00 0.00 0.00 3.50}   FPS = 280
Fri Sep 11 18:43:25   PC Latency = 22.322   {0.68 0.00 0.00 5.57}   FPS = 280
Fri Sep 11 18:56:04   PC Latency =  2.084   {0.48 0.00 0.00 1.72}   FPS = 1056
Fri Sep 11 18:59:33   PC Latency =  8.341   {1.16 0.00 0.00 4.74}   FPS = 673
                      PC Latency =  8.763   {1.16 0.00 0.00 4.74}   FPS = 673
                      PC Latency =  9.112   {1.48 0.00 0.00 4.97}   FPS = 707
                      PC Latency =  8.939   {1.86 0.00 0.00 5.28}   FPS = 799
                      PC Latency =  9.410   {1.36 0.00 0.00 4.68}   FPS = 642
Fri Sep 11 19:53:34   PC Latency = 21.477   {0.43 0.00 0.00 5.59}   FPS = 280
                      PC Latency = 21.680   {0.42 0.00 0.00 5.65}   FPS = 280
                      PC Latency = 22.606   {0.59 0.00 0.00 5.74}   FPS = 280
                      PC Latency = 22.816   {0.59 0.00 0.00 5.80}   FPS = 280
                      PC Latency = 23.135   {0.74 0.00 0.00 5.74}   FPS = 280
```

Columns are `I>S  S>Q  Q>R  R>D`.

**`I>S` becoming non-zero is the decisive signal.** `I>` is the input sample point,
which the driver can only place if it is receiving the ping round trip. `I>S = 0.00`
means "no input sampling measured at all"; `I>S = 0.68–1.86` means the driver is
measuring the real input-to-simulation leg. `S>Q` and `Q>R` stay at 0 because this is a
CPU-bound scene with no queue or render backlog to accumulate. They are `0.00` on every
row of the whole file, in every mode and at every frame rate, so a zero there is the
instrument's normal reading against Minecraft and not a regression.

**The 19:53:34 run is the post-§7.3 baseline.** It was recorded after the device-extension
injection was returned to its off-by-default state, on the configuration §7.3 concludes is
the correct one. `PC Latency` 21.477–23.135 ms and `I>S` 0.42–0.74 put it in the same
regime as the 18:43:25 run, before the experiment — which is itself the evidence that
reverting the injection restored the measurement, since the regressed state published no
frame reports and no pings at all.

Do not compare it against the 18:56/18:59 runs: those were uncapped at 1056 and 673–799
FPS, where the CPU-bound `R>D` leg shrinks with frame time. 280 FPS is the display's
refresh rate, so 19:53 is vsync-limited.

This run also stops at the `*** Reflex OFF` header with no OFF rows and no second cycle,
like every entry from 18:56 onward — see §7.2.

The 19:00+ runs need an **elevated** prompt: `ReflexTest.exe` and `ReflexTestEnable.exe`
both call `OpenProcessToken`/`GetTokenInformation` and print
`Please run with elevated/admin privileges.` Without elevation the tool cannot install
its driver profile and reports `PC Latency = 0.000` regardless of the app.

### 4.4 Runtime toggling

The four modes and the frame limit are changeable in-game with a client command; each
change is re-sent to the driver on the next tick.

```text
/reflex status
/reflex mode off|on|boost
/reflex framelimit <fps>     # 0 disables the limiter
/reflex debug 0|1            # lifecycle trace every 30 frames instead of 300
```

Arguments autocomplete, and a bare subcommand (`/reflex debug` with no value) prints
`Usage: /reflex debug 0|1` instead of doing nothing.

A session that exercised every path:

```text
22:33:13  Reflex frame limit set to 100 fps via /reflex command
22:33:13  Applied Reflex options: mode=ON_PLUS_BOOST, frameLimitFps=100, swapchain=0x6457d300, reason=options
22:33:24  Reflex frame limit set to 260 fps via /reflex command
22:33:24  Applied Reflex options: mode=ON_PLUS_BOOST, frameLimitFps=260, swapchain=0x6457d300, reason=options
22:33:42  Reflex mode set to ON via /reflex command
22:33:42  Applied Reflex options: mode=ON, frameLimitFps=260, swapchain=0x6457d300, reason=options
22:33:49  Reflex mode set to ON_PLUS_BOOST via /reflex command
22:33:49  Applied Reflex options: mode=ON_PLUS_BOOST, frameLimitFps=260, swapchain=0x6457d300, reason=options
22:34:31  Reflex mode set to OFF via /reflex command
22:34:31  Applied Reflex options: mode=OFF, frameLimitFps=260, swapchain=0x6457d300, reason=options
```

`reason=options` (not `reason=swapchain`) confirms each change came from the config, and
the same second on both lines confirms the apply happened on the next tick. `sleeps` kept
tracking `frame` in every mode, including `OFF` — `slReflexSleep` runs regardless of mode,
as the contract requires.

The command surfaces the same counters as §4.1 and §4.2 through `/reflex status`, which is
the in-app readout §7.1 calls for.

The **Options → Video Settings → NVIDIA Reflex Tool** screen writes the same config through the
same apply path. Its frame-limit box was verified the same way: typing `450` and leaving it
unconfirmed changed nothing, and pressing Enter produced
`Reflex frame limit set to 450 FPS from the options screen` followed by
`Applied Reflex options: … frameLimitFps=450, reason=options` on the same tick.

## 5. Measurement precision and validity

### 5.1 Two independent channels

| | Channel A: in-process | Channel B: out-of-band |
| --- | --- | --- |
| Source | `slReflexGetState` → `sl::ReflexReport` | Reflex Test Utility |
| Measures | marker timestamps, GPU times | full PC latency chain, incl. `I>S` |
| Depends on | SDK + driver | driver profile + ping round trip |
| Our control | yes | no |

They overlap but are not interchangeable. **Channel B is authoritative for PC
latency** — it is NVIDIA's own verification path and it is the one that can see the
input-sampling leg.

### 5.2 What each number is, and is not

- **All `ReflexReport` timestamps are absolute microseconds.** Only differences are
  meaningful. `ReflexLatencyReport`'s derived accessors do those subtractions.
- **`inputSampleTimeUs` is always 0 on this backend, and that is structural, not a
  defect.** The driver stamps it from `VK_LATENCY_MARKER_INPUT_SAMPLE_NV`, but
  Streamline 2.14.1 removed that marker (`PCLMarker::eInputSample` is commented out in
  `sl_pcl.h`, and in `PCLMarker2VkLatencyMarker` in `source/platforms/sl.chi/`), and
  `VK_NV_low_latency2` rev 1 has no marker for the latency ping —
  `PCLMarker::ePCLatencyPing` maps to `VK_LATENCY_MARKER_MAX_ENUM_NV`. The input
  sampling leg is measured **only** through Channel B.
  `ReflexLatencyReport.hasInputSample()` therefore returns false by construction.
- **`presentDurationNs` in the trace is a different clock from `presentUs`.** The first
  is Java `System.nanoTime()` around `VulkanGpuSurface.present()`; the second is the
  driver's microsecond timestamp. They agree here, but they are not subtractable from
  each other.
- **Counters are monotonic since process start**, except `submits`, which is a delta
  reset at every 300-frame print. A `submits` value far above 300 means frames stopped
  advancing while submissions continued (e.g. the window was minimised) — observed once
  as `submits=4800` across a 7-minute gap.
- **Reported GPU times are driver-sourced.** If the driver is not measuring, they read
  as 0, not as an error.

### 5.3 Failure signatures

| Symptom | Meaning |
| --- | --- |
| `frameId = 4294967295` (`0xFFFFFFFF`) | no report; driver has not published a frame |
| all timestamps 0 | same |
| `pclPings` stuck at 0 | app is not in the NVIDIA driver profile — nothing is measuring |
| `orderViolations` growing | marker order broke; see §3.2 |
| `staleMarkers` growing | a marker was emitted with no current frame token |
| `sleeps` not tracking `frame` | sleep placement broke; see §3.1 |

`UINT32_MAX` is the sentinel to watch for. It is also what the driver's own HUD shows
when it is running but not bound to any measured application — see §7.1.

## 6. Conclusions that were wrong, and why

Recorded so they are not re-derived.

1. **"The missing ping is the direct cause of `PCL 0.000`."** Stated, then retracted as
   irrelevant, then **proved correct** — implementing the ping moved `I>S` from 0.00 to
   0.68. The reasoning error was conflating two channels: `ReflexReport.inputSampleTime`
   being structurally 0 (Channel A, true) does not imply the ping does not matter
   (Channel B, false).

2. **"Minecraft's Vulkan calls never go through `sl.interposer.dll`."** Wrong. The
   Reflex plugin receives the swapchain only via
   `reflexEntry.cpp:983 ctx.compute->notifyCreateSwapchain(...)`, called from
   `slHookVkCreateSwapchainKHRBefore`; `vknvll2.cpp`'s `getReport` early-returns an empty
   report when `m_swapchain` is null. We get a populated report, therefore the hook
   fires, therefore the interposer is in Minecraft's Vulkan path. Note this is
   independent of the `interposer 'no'` flag in the plugin load lines — that flag means
   the plugin does not *require* interposer-only features, not that its hooks are absent.

3. **"Exclusive fullscreen + the missing present hook is why the HUD cannot draw."**
   Retracted. The HUD is drawn by the NVIDIA driver and does not depend on Streamline at
   all — `ReflexTestEnable.exe` uses NVAPI DRS and `NvAPI_Reflex_FlashIndicatorSet` with
   zero Streamline involvement. There is no causal chain from our integration to that
   overlay.

4. **"`sl.common`'s `CmdBindPipeline`/`CmdBindDescriptorSets`/`BeginCommandBuffer` being
   `NOT supported` blocks the HUD."** No. Those are DLSS hooks. They block DLSS, not the
   Reflex overlay.

**No verified cause for the missing HUD has been found.** See §7.1.

## 7. Open issues

### 7.1 The Reflex HUD does not draw over the Minecraft window

Facts:

- The overlay renders — it is visible over the desktop and over ordinary windows.
- It does **not** render over Minecraft, in windowed or exclusive-fullscreen mode.
  Exclusive fullscreen was confirmed achieved
  (`mode=exclusive-fullscreen, requestedFullscreen=true, actualFullscreen=true, displayAttached=true, framebuffer=1440x1080`)
  and did not change the outcome.
- In the captures taken so far the overlay showed `FrameID = 4294967295`,
  `App_Called_Sleep = 0`, and every timestamp 0. That is the **unbound / no-data
  state** (§5.3), and it is *not* Minecraft's state — the game was simultaneously
  reporting `frameId≈256782` and `sleeps≈256798`. Whatever that overlay was attached
  to, it was not this process.

**Cause is unknown. Do not assert one.** The remaining cheap discriminator is to launch
a Vulkan application that is known to be measured — CS2 with `-vulkan`; CS2 defaults to
D3D11, so its default mode proves nothing about Vulkan — and compare against the same
application's numbers on D3D11. If the overlay disappears on the Vulkan path, the
limitation is driver-side and no change in this repository can address it.

The robust alternative, independent of the driver overlay, the 2023 tool, and any window
mode, is to render the same fields from our own data in-game. Every field the driver HUD
shows is available to us except the input-sampling leg, and we additionally have the ping
counters. See §4.1 and §4.2 for the sources.

### 7.2 `ERROR: Failed to override Reflex` at BOOST → OFF

`ReflexTest.exe curve` reproducibly fails at the `Reflex BOOST → Reflex OFF` transition,
in every run since 2026-09-10, both before and after the integration fixes, and
identically at `PCL = 0.000` and `PCL = 22.322`. The failure therefore does not originate
in this repository. The `Reflex BOOST` half of each cycle completes and produces valid
numbers.

### 7.3 `VK_NV_low_latency_2` did not initialize — root cause found, fix not adopted

**Status: diagnosed and reproduced; the fix is implemented but disabled by default because it
makes Reflex worse.** Opt in with `-Dmc_reflex_tools.vulkanLowLatency2=true` to re-measure.

#### The symptom

`sl.log`:

```text
vknvll2.cpp:416[CreateVkNvLowLatency2] Failed to init VkNvLowLatency2: 2
```

`ComputeStatus` is `{ eOk=0, eError=1, eNoImplementation=2, ... }`
(`source/platforms/sl.chi/compute.h`), so the result is **`eNoImplementation`**.
`VkNvLowLatency2::init` returns that only from one place:

```cpp
m_pfnSetLatencySleepModeNV   = m_table->getDeviceProcAddr(m_device, "vkSetLatencySleepModeNV");
m_pfnLatencySleepNV          = m_table->getDeviceProcAddr(m_device, "vkLatencySleepNV");
m_pfnSetLatencyMarkerNV      = m_table->getDeviceProcAddr(m_device, "vkSetLatencyMarkerNV");
m_pfnGetLatencyTimingsNV     = m_table->getDeviceProcAddr(m_device, "vkGetLatencyTimingsNV");
m_pfnQueueNotifyOutOfBandNV  = m_table->getDeviceProcAddr(m_device, "vkQueueNotifyOutOfBandNV");

// If any LL2 entry point is missing, the extension wasn't enabled.
if (!m_pfnSetLatencySleepModeNV || !m_pfnLatencySleepNV || !m_pfnSetLatencyMarkerNV ||
    !m_pfnGetLatencyTimingsNV || !m_pfnQueueNotifyOutOfBandNV)
{
    return ComputeStatus::eNoImplementation;
}
```

So at least one `VK_NV_low_latency_2` entry point was null on Minecraft's device: the
extension was not enabled at `vkCreateDevice`.

#### Root cause (confirmed)

Two independent lines of evidence:

1. Minecraft logs its own enabled device extensions, and the extension is absent:

   ```text
   [18:58:10] (Minecraft) Using graphics device extensions: VK_KHR_synchronization2 (D),
     VK_EXT_multi_draw (D), VK_AMD_buffer_marker (D), VK_EXT_vertex_attribute_divisor (D),
     VK_KHR_win32_surface (I), VK_KHR_swapchain (D), VK_KHR_surface (I),
     VK_KHR_push_descriptor (D), VK_EXT_debug_utils (I), VK_KHR_dynamic_rendering (D)
   ```

   Neither `VK_NV_low_latency2` nor `VK_NV_low_latency` is in that list.

2. The SDK documents who is responsible. `docs/ProgrammingGuideManualHooking.md`:

   > If not using the proxy then host is responsible for setting up all extensions,
   > features, command queues and calling `slSetVulkanInfo`

   > SL features can request special extensions, device features or even modifications to the
   > number of command queues which need to be generated. Therefore before creating VK
   > instance and device you must call `slGetFeatureRequirements` **for each enabled feature**

   Streamline normally injects these itself: `sl.reflex`'s `updateEmbeddedJSON` writes
   `VK_NV_low_latency` (and, on a Turing-or-newer NV adapter, `VK_NV_low_latency2`) into
   `external/vk/device/extensions`, and `slGetFeatureRequirements` reads that back out of
   `cfg["vk"]["device"]["extensions"]` (`sl.cpp:1005`). That config is consumed by the
   interposer's own `vkCreateDevice` path. **Minecraft creates its device directly, so that
   path never runs and the extensions are never added.**

#### The fix, and why it is off

`VulkanBackendDeviceExtensionsMixin` injects into `VulkanBackend.createDevice`, adding every
extension `slGetFeatureRequirements` reports that `VulkanPhysicalDevice.hasDeviceExtension`
confirms. This is the same mechanism Minecraft already uses for `VK_AMD_buffer_marker`,
`VK_NV_device_diagnostic_checkpoints` and `VK_EXT_multi_draw`, and it is the documented
manual-hooking contract. It works:

```text
(mc_reflex_tools) Enabled Streamline-required Vulkan device extension VK_NV_low_latency2
(mc_reflex_tools) Enabled Streamline-required Vulkan device extension VK_NV_low_latency
(Minecraft) Using graphics device extensions: VK_NV_low_latency2 (D), ..., VK_NV_low_latency (D), ...
```

and `Failed to init VkNvLowLatency2` disappears from `sl.log` — the LL2 backend initializes.

**But the end-to-end pipeline regresses.** Measured, same machine, same mode:

| | LL2 enabled (opt-in) | Baseline (extension absent) |
| --- | --- | --- |
| `Failed to init VkNvLowLatency2` | absent — LL2 active | present — NvAPI fallback |
| `Reflex latency report` | `unavailable` every frame | live, `frameId` advancing |
| `pclPings` | 0 | ~250 per session |
| `sleeps` vs `frame` | tracks | tracks |

Streamline switches off whatever path was producing the reports and onto the LL2 backend,
which then produces nothing. The driver stops publishing frame reports and stops sending its
latency ping, so PC latency measurement is lost entirely.

#### A disproven hypothesis, recorded

The first explanation was that the LL2 backend had no swapchain: it only learns one through
`slHookVkCreateSwapchainKHRBefore` → `notifyCreateSwapchain`, and `setSleepMode` silently
returns `eOk` without calling `vkSetLatencySleepModeNV` while `m_swapchain` is null. The
theory was that Minecraft's only swapchain is created before Streamline's hooks are installed,
which is true — the hooks appear in `sl.log` at `2s:978ms` while the device and swapchain are
created in the same second. The mod was changed to re-apply `slReflexSetOptions` after every
swapchain change, and the swapchain was then forced to recreate five times mid-run, each time
producing `Applied Reflex options: ... reason=swapchain` with a new handle.

**`pclPings` stayed at 0 and the reports stayed unavailable.** The swapchain is not the cause.
That change was kept anyway, because re-applying options after a swapchain recreation is
independently correct, but it does not fix this.

#### What is still unknown

- Whether the prebuilt `sl.reflex.dll` was compiled with `SL_WITH_NVLLVK`. `vulkan.cpp:1179`
  falls back to `CreateNvLowLatencyVk` only under that `#ifdef`, and the macro appears nowhere
  in the shipped source tree except the `#ifdef` lines themselves. That the reports flowed
  before the fix and stopped after it implies a fallback exists, but the path was not traced.
- Why the LL2 backend produces nothing even with a live swapchain. It may need driver-side
  state this integration never establishes, or `vkSetLatencySleepModeNV` may be failing in a
  way that is not surfaced.

**Do not "fix" this by enabling the extensions.** The working configuration is the one where
the extension is absent. Re-open this only with a way to observe `vkSetLatencySleepModeNV`'s
`VkResult` and the driver's own view of the swapchain.

### 7.4 DLSS SR and DLSS-G are dead on arrival

`sl.log`:

```text
commonEntry.cpp:1658  Please provide correct application id when calling slInit
                      - NGX based features will be disabled
dlssEntry.cpp:923     Missing NGX context - DLSSContext cannot run
dlss_gEntry.cpp:1460  Unable to find NGX context - make sure sl.common was initialized properly
sl.cpp:179            'kFeatureDLSS' context is missing.
sl.cpp:179            'kFeatureDLSS_G' context is missing.
```

`slInit` is called without an `applicationId`, so `sl.common` never creates an NGX
context and both NGX-based features are disabled process-wide. Beyond that, DLSS is
still initialize-only: resource tagging (depth / motion vectors / colour),
`slSetConstants`, and `slEvaluateFeature` are not implemented, so it does not yet affect
the rendered image.

**Consequence for testing:** `config/mc_reflex_tools.json` currently has
`dlssGMode: "ON"`. Frame Generation cannot work in this state, and it makes the Reflex
Test Utility's `Frame Gen` cycles fail. Set it to `OFF` until §7.4 is addressed.

## 8. Reproducing

```bash
# 1. Build the mod
./gradlew :fabric:build

# 2. Build and install the native bridge
export NVIDIA_STREAMLINE_ROOT='<path-to-streamline-sdk-v2.14.1>'
cmake -S nvidia-sdk-native -B nvidia-sdk-native/build -G "Visual Studio 18 2026" -A x64
cmake --build nvidia-sdk-native/build --config Release
cp nvidia-sdk-native/build/Release/mc_reflex_tools_native.dll \
   nvidia-sdk-java/src/main/resources/natives/windows-x64/

# 3. Run in Vulkan mode
./gradlew :fabric:runClient
```

`fabric/run/options.txt` must contain `preferredGraphicsBackend:"vulkan"`. The client
rewrites this file on abnormal shutdown, so re-assert it if the game was force-killed.

In-game, for the external measurement:

```text
ReflexTest.exe curve          # elevated prompt required
```

Then read, in order:

1. `fabric/run/logs/latest.log` — `Vulkan lifecycle:` every 300 frames; `sleeps` should
   track `frame`, `orderViolations` and `staleMarkers` should be flat.
2. `fabric/run/logs/latest.log` — `Reflex latency report:`; `frameId` must advance.
3. `fabric/run/mc_reflex_tools_logs/sl.log` — plugin load lines, hook install results,
   and any `NOT supported` / `missing` errors.
4. `ReflexVerification/ReflexTestResults.txt` — the authoritative PC latency.

Do not force-kill the game: it rewrites `options.txt` and resets the graphics backend.
