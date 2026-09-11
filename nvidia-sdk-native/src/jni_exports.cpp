#include <jni.h>
#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

#ifdef _WIN32
#include <direct.h>
#include <windows.h>
#endif

#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
#include <sl.h>
#include <sl_reflex.h>
#include <sl_pcl.h>
#include <sl_dlss.h>
#include <sl_dlss_g.h>
#include <sl_helpers.h>

// sl_helpers_vk.h pulls in inline helpers that require the full Vulkan headers,
// which this project does not vendor. slSetVulkanInfo only needs the three
// handle types and the VulkanInfo struct below, so declare those directly.
struct VkInstance_T;
struct VkPhysicalDevice_T;
struct VkDevice_T;
using VkInstance = VkInstance_T*;
using VkPhysicalDevice = VkPhysicalDevice_T*;
using VkDevice = VkDevice_T*;

namespace sl {
// Mirrors sl_helpers_vk.h VulkanInfo exactly (GUID 0x0EED6FD5..., kStructVersion3).
SL_STRUCT_BEGIN(VulkanInfo, StructType({ 0xeed6fd5, 0x82cd, 0x43a9, { 0xbd, 0xb5, 0x47, 0xa5, 0xba, 0x2f, 0x45, 0xd6 } }), kStructVersion3)
    VkDevice device {};
    VkInstance instance{};
    VkPhysicalDevice physicalDevice{};
    uint32_t computeQueueIndex{};
    uint32_t computeQueueFamily{};
    uint32_t graphicsQueueIndex{};
    uint32_t graphicsQueueFamily{};
    uint32_t opticalFlowQueueIndex{};
    uint32_t opticalFlowQueueFamily{};
    bool useNativeOpticalFlowMode = false;
    uint32_t computeQueueCreateFlags{};
    uint32_t graphicsQueueCreateFlags{};
    uint32_t opticalFlowQueueCreateFlags{};
SL_STRUCT_END()
}

extern "C" sl::Result slSetVulkanInfo(const sl::VulkanInfo& info);
#endif

namespace {
constexpr jint kSuccess = 0;
constexpr jint kUnavailable = 1;

#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
// slInit() has completed. Set as early as possible (before the game creates its
// graphics device) so Streamline can install its hooks in time.
bool g_sdkInitialized = false;
// slSetVulkanInfo() has completed and the SDK can resolve feature functions.
bool g_initialized = false;
bool g_dlssInitialized = false;
bool g_dlssGInitialized = false;
// The single frame token for the current in-flight frame. It is fetched once at
// frame start (nativeSleep) and reused by every marker for that frame, per the
// Streamline Reflex/PCL contract: exactly one token per frame.
sl::FrameToken* g_currentFrameToken = nullptr;
// Render submission can be recorded more than once per frame, but the Reflex
// marker contract expects exactly one RenderSubmit pair per presented frame.
// 0 = idle, 1 = start emitted, 2 = end emitted.
int g_renderSubmitState = 0;
// Number of extra RenderSubmit markers dropped by the per-frame dedupe, for
// diagnostics. Reset only on shutdown.
int g_suppressedRenderSubmitCount = 0;
// Marker id (1..6) of the last marker accepted for the current frame, used to
// detect out-of-order or duplicated markers. Reset at every frame start.
int g_lastAcceptedMarker = 0;
// Number of markers that arrived out of the expected SimulationStart ->
// SimulationEnd -> RenderSubmitStart -> RenderSubmitEnd -> PresentStart ->
// PresentEnd order.
int g_markerOrderViolationCount = 0;
// Markers dropped because no frame token was available (either slReflexSleep has
// not run yet or slGetNewFrameToken failed).
int g_staleMarkerCount = 0;
// Number of times slReflexSleep has been called, and the last result it returned.
int g_sleepCount = 0;
int g_lastSleepResult = 0;
// Holds the last non-OK Streamline result for diagnostics.
int g_lastSdkError = 0;

#ifdef _WIN32
// The driver measures input sampling latency by posting a periodic message to
// the game window; the application must answer it with ePCLatencyPing. Minecraft
// hands its window entirely to GLFW, so the window procedure has to be
// subclassed to observe that message at all.
HWND g_hookedWindow = nullptr;
WNDPROC g_previousWndProc = nullptr;
uint32_t g_pclPingMessageId = 0;
LONG g_pclPingCount = 0;
LONG g_pclPingMissedCount = 0;

// Window procedure installed over GLFW's own. Runs on the thread that pumps
// messages, which is the game thread inside RenderSystem.pollEvents().
LRESULT CALLBACK mcReflexToolsWindowProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    if (g_pclPingMessageId != 0 && msg == static_cast<UINT>(g_pclPingMessageId)) {
        // slReflexSleep has already claimed the frame token for the frame in
        // flight, so the ping belongs to that frame and needs no index bump.
        if (g_currentFrameToken != nullptr) {
            slPCLSetMarker(sl::PCLMarker::ePCLatencyPing, *g_currentFrameToken);
            InterlockedIncrement(&g_pclPingCount);
        } else {
            InterlockedIncrement(&g_pclPingMissedCount);
        }
    }

    if (g_previousWndProc != nullptr) {
        return CallWindowProcW(g_previousWndProc, hwnd, msg, wParam, lParam);
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}
#endif

// Most recent messages reported by the Streamline plugins, oldest first. The
// SDK invokes logMessageCallback from its own threads, so this ring is guarded
// and drained from the game thread; the callback never touches the JVM.
constexpr size_t kMaxSdkLogMessages = 64;
std::deque<std::string> g_sdkLogMessages;
std::mutex g_sdkLogMutex;

// Receives every Streamline plugin log message. Runs on SDK threads, so it must
// stay allocation-light and must not call into the JVM. The message pointer is
// only valid for the duration of the call, hence the copy.
void streamlineLogCallback(sl::LogType type, const char* msg) {
    if (msg == nullptr) {
        return;
    }

    const char* prefix = "INFO";
    if (type == sl::LogType::eWarn) {
        prefix = "WARN";
    } else if (type == sl::LogType::eError) {
        prefix = "ERROR";
    }

    std::lock_guard<std::mutex> lock(g_sdkLogMutex);
    if (g_sdkLogMessages.size() >= kMaxSdkLogMessages) {
        g_sdkLogMessages.pop_front();
    }
    g_sdkLogMessages.emplace_back(std::string("[SL ") + prefix + "] " + msg);
}

// Features must be explicitly requested in sl::Preferences::featuresToLoad;
// otherwise no plugin is loaded and every feature call reports eErrorFeatureMissing.
const sl::Feature g_featuresToLoad[] = {
    sl::kFeatureReflex,
    sl::kFeaturePCL,
    sl::kFeatureDLSS,
    sl::kFeatureDLSS_G,
};
constexpr uint32_t g_numFeaturesToLoad =
    static_cast<uint32_t>(sizeof(g_featuresToLoad) / sizeof(g_featuresToLoad[0]));

// Features whose Vulkan device requirements the host application has to satisfy
// itself. Streamline adds these when the host creates its device through the
// sl.interposer proxies; Minecraft creates its own VkDevice directly, so the host
// is responsible for them. See the SDK's ProgrammingGuideManualHooking.md,
// "Instance and device additions".
//
// DLSS and DLSS-G are deliberately excluded: their requirements also include extra
// optical-flow queues, which cannot be added to a device Minecraft has already
// created and sized.
const sl::Feature g_featuresRequiringDeviceExtensions[] = {
    sl::kFeatureReflex,
    sl::kFeaturePCL,
};
constexpr uint32_t g_numFeaturesRequiringDeviceExtensions =
    static_cast<uint32_t>(sizeof(g_featuresRequiringDeviceExtensions) /
                          sizeof(g_featuresRequiringDeviceExtensions[0]));

// Plugin DLL search path (the Streamline SDK bin/x64 directory), populated from
// the NVIDIA_STREAMLINE_ROOT environment variable.
std::wstring g_pluginPath;
// Directory Streamline writes its own log files to.
std::wstring g_logPath;

// Absolute directory where Streamline should drop its log files: the process
// working directory (the Minecraft run directory) plus a dedicated subfolder.
// Streamline opens its log with _wfsopen and gives up permanently if the open
// fails, so the directory has to exist before slInit runs.
std::wstring resolveLogPath() {
#ifdef _WIN32
    wchar_t buffer[4096] = {};
    if (_wgetcwd(buffer, 4096) != nullptr) {
        std::wstring path(buffer);
        path += L"\\mc_reflex_tools_logs";
        if (_wmkdir(path.c_str()) == 0 || errno == EEXIST) {
            return path;
        }
        // Could not create the subfolder; the working directory itself still
        // works and is writable.
        return std::wstring(buffer);
    }
#endif
    return L"";
}

// Initializes the Streamline SDK. Must run before the game creates its graphics
// device; the device is registered afterwards by nativeInitialize.
bool initStreamlineSdk() {
    if (g_sdkInitialized) {
        return true;
    }

    sl::Preferences pref{};
    pref.showConsole = false;
    pref.logLevel = sl::LogLevel::eDefault;
    pref.featuresToLoad = g_featuresToLoad;
    pref.numFeaturesToLoad = g_numFeaturesToLoad;
    // Surface plugin warnings/errors in the game log and let the SDK write its
    // own files. Without these the integration is blind when a plugin silently
    // declines to engage, which is exactly what a PCL of 0.0 looks like.
    pref.logMessageCallback = &streamlineLogCallback;
    g_logPath = resolveLogPath();
    pref.pathToLogsAndData = g_logPath.c_str();

    // Tell Streamline where its plugin DLLs (sl.reflex.dll, sl.pcl.dll, ...)
    // live. Without this it searches next to the executable and finds nothing.
    const char* sdkRoot = std::getenv("NVIDIA_STREAMLINE_ROOT");
    if (sdkRoot != nullptr) {
        g_pluginPath = std::wstring(sdkRoot, sdkRoot + std::strlen(sdkRoot));
    } else {
        g_pluginPath = L"<path-to-streamline-sdk-v2.14.1>";
    }
    g_pluginPath += L"\\bin\\x64";
    const wchar_t* pluginPaths[] = { g_pluginPath.c_str() };
    pref.pathsToPlugins = pluginPaths;
    pref.numPathsToPlugins = 1;

    sl::Result res = slInit(pref, sl::kSDKVersion);
    if (res != sl::Result::eOk) {
        g_lastSdkError = static_cast<int>(res);
        return false;
    }

    g_sdkInitialized = true;
    return true;
}

sl::PCLMarker toPclMarker(jint marker) {
    switch (marker) {
        case 1: return sl::PCLMarker::eSimulationStart;
        case 2: return sl::PCLMarker::eSimulationEnd;
        case 3: return sl::PCLMarker::eRenderSubmitStart;
        case 4: return sl::PCLMarker::eRenderSubmitEnd;
        case 5: return sl::PCLMarker::ePresentStart;
        case 6: return sl::PCLMarker::ePresentEnd;
        default: return sl::PCLMarker::eSimulationStart;
    }
}

sl::ReflexMode toReflexMode(jint mode) {
    switch (mode) {
        case 0: return sl::ReflexMode::eOff;
        case 1: return sl::ReflexMode::eLowLatency;
        case 2: return sl::ReflexMode::eLowLatencyWithBoost;
        default: return sl::ReflexMode::eLowLatency;
    }
}

sl::DLSSMode toDlssMode(jint mode) {
    switch (mode) {
        case 0: return sl::DLSSMode::eOff;
        case 1: return sl::DLSSMode::eMaxPerformance;
        case 2: return sl::DLSSMode::eBalanced;
        case 3: return sl::DLSSMode::eMaxQuality;
        case 4: return sl::DLSSMode::eUltraPerformance;
        case 5: return sl::DLSSMode::eUltraQuality;
        case 6: return sl::DLSSMode::eDLAA;
        default: return sl::DLSSMode::eOff;
    }
}

sl::DLSSGMode toDlssGMode(jint mode) {
    switch (mode) {
        case 0: return sl::DLSSGMode::eOff;
        case 1: return sl::DLSSGMode::eOn;
        case 2: return sl::DLSSGMode::eAuto;
        case 3: return sl::DLSSGMode::eDynamic;
        default: return sl::DLSSGMode::eOff;
    }
}
#endif

jobject newBridgeStatus(JNIEnv* env, jboolean libraryLoaded, jboolean streamlineSdkDetected, const char* detail) {
    jclass statusClass = env->FindClass("dev/kyresn/mcreflex/api/NativeBridgeStatus");
    if (statusClass == nullptr) {
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(statusClass, "<init>", "(ZZLjava/lang/String;)V");
    if (constructor == nullptr) {
        return nullptr;
    }

    jstring message = env->NewStringUTF(detail);
    if (message == nullptr) {
        return nullptr;
    }

    jobject status = env->NewObject(statusClass, constructor, libraryLoaded, streamlineSdkDetected, message);
    env->DeleteLocalRef(message);
    return status;
}
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeBridgeStatus(JNIEnv* env, jclass) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    return newBridgeStatus(env, JNI_TRUE, JNI_TRUE, "NVIDIA Streamline SDK integrated with Reflex, DLSS & DLSS-G");
#else
    return newBridgeStatus(env, JNI_TRUE, JNI_FALSE, "NVIDIA_STREAMLINE_ROOT is not configured at native build time");
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeInitSdk(JNIEnv*, jclass) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    return initStreamlineSdk() ? kSuccess : kUnavailable;
#else
    return kUnavailable;
#endif
}

// Vulkan device extensions the loaded Streamline features need on the host device.
//
// Minecraft creates its own VkDevice through the loader rather than through
// Streamline's vkCreateDevice proxy, so the extensions Streamline would normally
// inject are never added. Without VK_NV_low_latency2 the LL2 backend fails with
// eNoImplementation (vkLatencySleepNV resolves to null), the swapchain is created
// without VkSwapchainLatencyCreateInfoNV, and Reflex silently degrades.
//
// Queried before Minecraft calls vkCreateDevice; see VulkanBackendDeviceExtensionsMixin.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeGetRequiredDeviceExtensions(JNIEnv* env, jclass) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    if (!initStreamlineSdk()) {
        return nullptr;
    }

    std::vector<std::string> names;
    for (uint32_t i = 0; i < g_numFeaturesRequiringDeviceExtensions; ++i) {
        sl::FeatureRequirements requirements{};
        if (slGetFeatureRequirements(g_featuresRequiringDeviceExtensions[i], requirements) != sl::Result::eOk) {
            // Feature is not loaded or not present on this adapter; its
            // requirements simply contribute nothing.
            continue;
        }
        for (uint32_t e = 0; e < requirements.vkNumDeviceExtensions; ++e) {
            const char* name = requirements.vkDeviceExtensions[e];
            if (name == nullptr) {
                continue;
            }
            if (std::find(names.begin(), names.end(), name) == names.end()) {
                names.emplace_back(name);
            }
        }
    }

    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) {
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(names.size()), stringClass, nullptr);
    if (result == nullptr) {
        env->DeleteLocalRef(stringClass);
        return nullptr;
    }
    for (size_t i = 0; i < names.size(); ++i) {
        jstring name = env->NewStringUTF(names[i].c_str());
        if (name == nullptr) {
            env->DeleteLocalRef(result);
            env->DeleteLocalRef(stringClass);
            return nullptr;
        }
        env->SetObjectArrayElement(result, static_cast<jsize>(i), name);
        env->DeleteLocalRef(name);
    }
    env->DeleteLocalRef(stringClass);
    return result;
#else
    return nullptr;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeInitialize(
        JNIEnv*, jclass, jlong instance, jlong physicalDevice, jlong device,
        jlong graphicsQueue, jint queueFamilyIndex, jlong swapchain) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    if (g_initialized) {
        return kSuccess;
    }

    // Fallback for callers that skipped the early SDK init.
    if (!initStreamlineSdk()) {
        return kUnavailable;
    }

    // Streamline feature functions (slReflexSetOptions, slReflexSleep,
    // slPCLSetMarker, ...) are resolved lazily via slGetFeatureFunction, which
    // requires the device to be set first. Minecraft creates its own Vulkan
    // device, so Streamline's vkCreateDevice proxy never intercepts it; the
    // device must be provided explicitly via slSetVulkanInfo.
    sl::VulkanInfo vkInfo{};
    vkInfo.device = reinterpret_cast<VkDevice>(device);
    vkInfo.instance = reinterpret_cast<VkInstance>(instance);
    vkInfo.physicalDevice = reinterpret_cast<VkPhysicalDevice>(physicalDevice);
    vkInfo.graphicsQueueFamily = static_cast<uint32_t>(queueFamilyIndex);
    vkInfo.graphicsQueueIndex = 0;

    sl::Result res = slSetVulkanInfo(vkInfo);
    if (res != sl::Result::eOk) {
        g_lastSdkError = static_cast<int>(res);
        return kUnavailable;
    }

    sl::ReflexOptions reflexOptions{};
    reflexOptions.mode = sl::ReflexMode::eLowLatency;
    res = slReflexSetOptions(reflexOptions);
    if (res != sl::Result::eOk) {
        g_lastSdkError = static_cast<int>(res);
        return kUnavailable;
    }

    g_initialized = true;
    return kSuccess;
#else
    (void)instance; (void)physicalDevice; (void)device;
    (void)graphicsQueue; (void)queueFamilyIndex; (void)swapchain;
    return kUnavailable;
#endif
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeGetReflexState(JNIEnv* env, jclass) {
    jclass stateClass = env->FindClass("dev/kyresn/mcreflex/api/ReflexState");
    if (stateClass == nullptr) return nullptr;

    jmethodID constructor = env->GetMethodID(stateClass, "<init>", "(ZZ)V");
    if (constructor == nullptr) return nullptr;

#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    if (g_initialized) {
        sl::ReflexState state{};
        if (slReflexGetState(state) == sl::Result::eOk) {
            return env->NewObject(stateClass, constructor,
                                  state.lowLatencyAvailable ? JNI_TRUE : JNI_FALSE,
                                  state.flashIndicatorDriverControlled ? JNI_TRUE : JNI_FALSE);
        }
    }
#endif

    return env->NewObject(stateClass, constructor, JNI_FALSE, JNI_FALSE);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeLastSdkError(JNIEnv* env, jclass) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    const char* name = sl::getResultAsStr(static_cast<sl::Result>(g_lastSdkError));
    return env->NewStringUTF(name != nullptr ? name : "unknown");
#else
    return env->NewStringUTF("streamline not compiled");
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeSetReflexOptions(
        JNIEnv*, jclass, jint mode, jint frameLimitFps) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    if (!g_initialized) return;

    sl::ReflexOptions reflexOptions{};
    reflexOptions.mode = toReflexMode(mode);
    if (frameLimitFps > 0) {
        reflexOptions.frameLimitUs = static_cast<uint32_t>(1000000 / frameLimitFps);
    } else {
        reflexOptions.frameLimitUs = 0;
    }
    slReflexSetOptions(reflexOptions);
#else
    (void)mode; (void)frameLimitFps;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeSleep(JNIEnv*, jclass) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    if (!g_initialized) return;

    // Start a new frame: fetch exactly one fresh token and reuse it for every
    // marker this frame. The caller places this call immediately before the
    // frame's input is sampled, which is where Reflex expects to sleep.
    g_currentFrameToken = nullptr;
    g_renderSubmitState = 0;
    g_lastAcceptedMarker = 0;

    sl::Result res = slGetNewFrameToken(g_currentFrameToken);
    if (res != sl::Result::eOk || g_currentFrameToken == nullptr) {
        g_lastSdkError = static_cast<int>(res);
        return;
    }

    g_lastSleepResult = static_cast<int>(slReflexSleep(*g_currentFrameToken));
    g_sleepCount++;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeMarker(JNIEnv*, jclass, jint marker) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    if (!g_initialized) return;

    if (g_currentFrameToken == nullptr) {
        // No token for this frame: either slReflexSleep has not run yet or
        // slGetNewFrameToken failed. The marker cannot be attributed to a frame.
        g_staleMarkerCount++;
        return;
    }

    // Minecraft can record more than one queue submission per frame, but the
    // Reflex marker contract wants exactly one RenderSubmit pair per presented
    // frame. Keep the first pair and ignore the rest until the next frame.
    constexpr jint kRenderSubmitStart = 3;
    constexpr jint kRenderSubmitEnd = 4;
    if (marker == kRenderSubmitStart) {
        if (g_renderSubmitState != 0) {
            g_suppressedRenderSubmitCount++;
            return;
        }
        g_renderSubmitState = 1;
    } else if (marker == kRenderSubmitEnd) {
        if (g_renderSubmitState != 1) {
            g_suppressedRenderSubmitCount++;
            return;
        }
        g_renderSubmitState = 2;
    }

    // Within one frame the accepted markers must be strictly increasing:
    // SimulationStart -> SimulationEnd -> RenderSubmitStart -> RenderSubmitEnd
    // -> PresentStart -> PresentEnd.
    if (marker <= g_lastAcceptedMarker) {
        g_markerOrderViolationCount++;
    }
    g_lastAcceptedMarker = marker;

    slPCLSetMarker(toPclMarker(marker), *g_currentFrameToken);
#else
    (void)marker;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeShutdown(JNIEnv*, jclass) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    if (g_sdkInitialized) {
#ifdef _WIN32
        if (g_hookedWindow != nullptr && g_previousWndProc != nullptr) {
            SetWindowLongPtrW(g_hookedWindow, GWLP_WNDPROC,
                              reinterpret_cast<LONG_PTR>(g_previousWndProc));
        }
        g_hookedWindow = nullptr;
        g_previousWndProc = nullptr;
        g_pclPingMessageId = 0;
        g_pclPingCount = 0;
        g_pclPingMissedCount = 0;
#endif
        slShutdown();
        g_sdkInitialized = false;
        g_initialized = false;
        g_dlssInitialized = false;
        g_dlssGInitialized = false;
        g_currentFrameToken = nullptr;
        g_renderSubmitState = 0;
        g_suppressedRenderSubmitCount = 0;
        g_lastAcceptedMarker = 0;
        g_markerOrderViolationCount = 0;
        g_staleMarkerCount = 0;
        g_sleepCount = 0;
        g_lastSleepResult = 0;
    }
#endif
}

// Number of extra RenderSubmit markers dropped by the per-frame dedupe.
extern "C" JNIEXPORT jint JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeGetSuppressedMarkerCount(JNIEnv*, jclass) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    return static_cast<jint>(g_suppressedRenderSubmitCount);
#else
    return 0;
#endif
}

// Number of markers that arrived out of the expected per-frame order.
extern "C" JNIEXPORT jint JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeGetMarkerOrderViolationCount(JNIEnv*, jclass) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    return static_cast<jint>(g_markerOrderViolationCount);
#else
    return 0;
#endif
}

// Number of markers dropped because no frame token was available for the frame.
extern "C" JNIEXPORT jint JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeGetStaleMarkerCount(JNIEnv*, jclass) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    return static_cast<jint>(g_staleMarkerCount);
#else
    return 0;
#endif
}

// Number of slReflexSleep calls, so the caller can prove it happens once per frame.
extern "C" JNIEXPORT jint JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeGetSleepCount(JNIEnv*, jclass) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    return static_cast<jint>(g_sleepCount);
#else
    return 0;
#endif
}

// Installs the PCL latency-ping handler over the game window. `windowHandle` is
// the GLFW window handle; the Win32 HWND and the driver's ping message id are
// resolved here so the Java side does not need either.
extern "C" JNIEXPORT jint JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeInstallPclPingHook(
        JNIEnv*, jclass, jlong windowHandle) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    if (!g_initialized || windowHandle == 0) {
        return kUnavailable;
    }

#ifdef _WIN32
    HWND hwnd = reinterpret_cast<HWND>(windowHandle);
    if (g_hookedWindow == hwnd) {
        return kSuccess;
    }

    sl::ReflexState state{};
    if (slReflexGetState(state) != sl::Result::eOk) {
        return kUnavailable;
    }
    if (state.statsWindowMessage == 0) {
        // The driver is not publishing a ping message, so there is nothing to
        // listen for. Not an error: this is the case outside Reflex measurement.
        return kUnavailable;
    }

    if (g_hookedWindow != nullptr && g_previousWndProc != nullptr) {
        SetWindowLongPtrW(g_hookedWindow, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(g_previousWndProc));
        g_hookedWindow = nullptr;
        g_previousWndProc = nullptr;
    }

    g_pclPingMessageId = state.statsWindowMessage;
    SetLastError(0);
    LONG_PTR previous = SetWindowLongPtrW(hwnd, GWLP_WNDPROC,
                                          reinterpret_cast<LONG_PTR>(&mcReflexToolsWindowProc));
    if (previous == 0 && GetLastError() != 0) {
        g_pclPingMessageId = 0;
        return kUnavailable;
    }

    g_previousWndProc = reinterpret_cast<WNDPROC>(previous);
    g_hookedWindow = hwnd;
    return kSuccess;
#else
    return kUnavailable;
#endif
#else
    (void)windowHandle;
    return kUnavailable;
#endif
}

// Number of ePCLatencyPing markers emitted in response to the driver's message.
extern "C" JNIEXPORT jint JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeGetPclPingCount(JNIEnv*, jclass) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
#ifdef _WIN32
    return static_cast<jint>(InterlockedCompareExchange(&g_pclPingCount, 0, 0));
#else
    return 0;
#endif
#else
    return 0;
#endif
}

// Number of pings seen before a frame token existed, which means the marker
// could not be attributed and the input sampling latency went unmeasured.
extern "C" JNIEXPORT jint JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeGetPclPingMissedCount(JNIEnv*, jclass) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
#ifdef _WIN32
    return static_cast<jint>(InterlockedCompareExchange(&g_pclPingMissedCount, 0, 0));
#else
    return 0;
#endif
#else
    return 0;
#endif
}

// Drains the queued Streamline plugin log messages, oldest first, and clears the
// queue. Returns an empty string when nothing new arrived since the last drain.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeDrainSdkMessages(JNIEnv* env, jclass) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    std::string joined;
    {
        std::lock_guard<std::mutex> lock(g_sdkLogMutex);
        for (const std::string& message : g_sdkLogMessages) {
            if (!joined.empty()) {
                joined += '\n';
            }
            joined += message;
        }
        g_sdkLogMessages.clear();
    }
    return env->NewStringUTF(joined.c_str());
#else
    return env->NewStringUTF("");
#endif
}

// Latest per-frame latency report from the Reflex plugin. This is the in-app
// equivalent of the Reflex verification HUD: it is populated by the driver from
// the PCL markers and needs no driver app profile to read back.
extern "C" JNIEXPORT jobject JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeGetLatencyReport(JNIEnv* env, jclass) {
    jclass reportClass = env->FindClass("dev/kyresn/mcreflex/api/ReflexLatencyReport");
    if (reportClass == nullptr) return nullptr;

    jmethodID constructor = env->GetMethodID(reportClass, "<init>", "(ZJJJJJJJJJJJJJJJJ)V");
    if (constructor == nullptr) return nullptr;

#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    if (g_initialized) {
        sl::ReflexState state{};
        if (slReflexGetState(state) == sl::Result::eOk && state.latencyReportAvailable) {
            // Pick the newest entry that has been through a present. Entries the
            // driver has not filled in yet carry frameID 0 or a zero present time.
            const sl::ReflexReport* latest = nullptr;
            for (int i = 0; i < sl::kReflexFrameReportCount; i++) {
                const sl::ReflexReport& candidate = state.frameReport[i];
                if (candidate.frameID == 0 || candidate.presentEndTime == 0) {
                    continue;
                }
                if (latest == nullptr ||
                    static_cast<int64_t>(candidate.frameID - latest->frameID) > 0) {
                    latest = &candidate;
                }
            }

            if (latest != nullptr) {
                return env->NewObject(reportClass, constructor,
                                      JNI_TRUE,
                                      static_cast<jlong>(latest->frameID),
                                      static_cast<jlong>(latest->inputSampleTime),
                                      static_cast<jlong>(latest->simStartTime),
                                      static_cast<jlong>(latest->simEndTime),
                                      static_cast<jlong>(latest->renderSubmitStartTime),
                                      static_cast<jlong>(latest->renderSubmitEndTime),
                                      static_cast<jlong>(latest->presentStartTime),
                                      static_cast<jlong>(latest->presentEndTime),
                                      static_cast<jlong>(latest->driverStartTime),
                                      static_cast<jlong>(latest->driverEndTime),
                                      static_cast<jlong>(latest->osRenderQueueStartTime),
                                      static_cast<jlong>(latest->osRenderQueueEndTime),
                                      static_cast<jlong>(latest->gpuRenderStartTime),
                                      static_cast<jlong>(latest->gpuRenderEndTime),
                                      static_cast<jlong>(latest->gpuActiveRenderTimeUs),
                                      static_cast<jlong>(latest->gpuFrameTimeUs));
            }
        }
    }
#endif

    return env->NewObject(reportClass, constructor,
                          JNI_FALSE,
                          0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                          0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
}

// -----------------------------------------------------------------------------
// DLSS Super Resolution Native Implementations
// -----------------------------------------------------------------------------

extern "C" JNIEXPORT jint JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeDlssProvider_nativeDlssInitialize(
        JNIEnv* env, jclass, jlong instance, jlong physicalDevice, jlong device,
        jlong graphicsQueue, jint queueFamilyIndex, jlong swapchain) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    if (g_dlssInitialized) {
        return kSuccess;
    }

    if (!g_initialized) {
        jint reflexInit = Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeInitialize(
                env, nullptr, instance, physicalDevice, device, graphicsQueue, queueFamilyIndex, swapchain);
        if (reflexInit != kSuccess) {
            return reflexInit;
        }
    }

    g_dlssInitialized = true;
    return kSuccess;
#else
    (void)env; (void)instance; (void)physicalDevice; (void)device;
    (void)graphicsQueue; (void)queueFamilyIndex; (void)swapchain;
    return kUnavailable;
#endif
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeDlssProvider_nativeGetOptimalSettings(
        JNIEnv* env, jclass, jint mode, jint outputWidth, jint outputHeight) {
    jclass settingsClass = env->FindClass("dev/kyresn/mcreflex/api/DlssOptimalSettings");
    if (settingsClass == nullptr) return nullptr;

    jmethodID constructor = env->GetMethodID(settingsClass, "<init>", "(IIFIIII)V");
    if (constructor == nullptr) return nullptr;

#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    if (g_dlssInitialized) {
        sl::DLSSOptions options{};
        options.mode = toDlssMode(mode);
        options.outputWidth = static_cast<uint32_t>(outputWidth);
        options.outputHeight = static_cast<uint32_t>(outputHeight);

        sl::DLSSOptimalSettings settings{};
        if (slDLSSGetOptimalSettings(options, settings) == sl::Result::eOk) {
            return env->NewObject(settingsClass, constructor,
                                  static_cast<jint>(settings.optimalRenderWidth),
                                  static_cast<jint>(settings.optimalRenderHeight),
                                  static_cast<jfloat>(settings.optimalSharpness),
                                  static_cast<jint>(settings.renderWidthMin),
                                  static_cast<jint>(settings.renderHeightMin),
                                  static_cast<jint>(settings.renderWidthMax),
                                  static_cast<jint>(settings.renderHeightMax));
        }
    }
#endif

    return env->NewObject(settingsClass, constructor,
                          outputWidth, outputHeight, 0.0f,
                          outputWidth, outputHeight, outputWidth, outputHeight);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeDlssProvider_nativeSetOptions(
        JNIEnv*, jclass, jint mode, jint outputWidth, jint outputHeight) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    if (!g_dlssInitialized) return;

    sl::DLSSOptions options{};
    options.mode = toDlssMode(mode);
    options.outputWidth = static_cast<uint32_t>(outputWidth);
    options.outputHeight = static_cast<uint32_t>(outputHeight);
    options.colorBuffersHDR = sl::Boolean::eFalse;

    sl::ViewportHandle viewport{ 0 };
    slDLSSSetOptions(viewport, options);
#else
    (void)mode; (void)outputWidth; (void)outputHeight;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeDlssProvider_nativeDlssShutdown(JNIEnv*, jclass) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    g_dlssInitialized = false;
#endif
}

// -----------------------------------------------------------------------------
// DLSS Frame Generation (DLSS-G) Native Implementations
// -----------------------------------------------------------------------------

extern "C" JNIEXPORT jint JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeDlssGProvider_nativeDlssGInitialize(
        JNIEnv* env, jclass, jlong instance, jlong physicalDevice, jlong device,
        jlong graphicsQueue, jint queueFamilyIndex, jlong swapchain) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    if (g_dlssGInitialized) {
        return kSuccess;
    }

    if (!g_initialized) {
        jint reflexInit = Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeInitialize(
                env, nullptr, instance, physicalDevice, device, graphicsQueue, queueFamilyIndex, swapchain);
        if (reflexInit != kSuccess) {
            return reflexInit;
        }
    }

    g_dlssGInitialized = true;
    return kSuccess;
#else
    (void)env; (void)instance; (void)physicalDevice; (void)device;
    (void)graphicsQueue; (void)queueFamilyIndex; (void)swapchain;
    return kUnavailable;
#endif
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeDlssGProvider_nativeDlssGGetState(JNIEnv* env, jclass) {
    jclass stateClass = env->FindClass("dev/kyresn/mcreflex/api/DlssGState");
    if (stateClass == nullptr) return nullptr;

    jmethodID constructor = env->GetMethodID(stateClass, "<init>", "(ZIZJ)V");
    if (constructor == nullptr) return nullptr;

#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    if (g_dlssGInitialized) {
        sl::ViewportHandle viewport{ 0 };
        sl::DLSSGState state{};
        if (slDLSSGGetState(viewport, state, nullptr) == sl::Result::eOk) {
            bool supported = (state.status == sl::DLSSGStatus::eOk);
            int maxFrames = static_cast<int>(state.numFramesToGenerateMax);
            bool dynamicMfg = (state.bIsDynamicMFGSupported == sl::Boolean::eTrue);
            long estimatedVram = static_cast<long>(state.estimatedVRAMUsageInBytes);

            return env->NewObject(stateClass, constructor,
                                  supported ? JNI_TRUE : JNI_FALSE,
                                  maxFrames,
                                  dynamicMfg ? JNI_TRUE : JNI_FALSE,
                                  estimatedVram);
        }
    }
#endif

    return env->NewObject(stateClass, constructor, JNI_FALSE, 0, JNI_FALSE, 0L);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeDlssGProvider_nativeDlssGSetOptions(
        JNIEnv*, jclass, jint mode, jint numFramesToGenerate) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    if (!g_dlssGInitialized) return;

    sl::DLSSGOptions options{};
    options.mode = toDlssGMode(mode);
    options.numFramesToGenerate = static_cast<uint32_t>(numFramesToGenerate);
    options.flags = sl::DLSSGFlags::eRetainResourcesWhenOff;

    sl::ViewportHandle viewport{ 0 };
    slDLSSGSetOptions(viewport, options);
#else
    (void)mode; (void)numFramesToGenerate;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeDlssGProvider_nativeDlssGShutdown(JNIEnv*, jclass) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    g_dlssGInitialized = false;
#endif
}
