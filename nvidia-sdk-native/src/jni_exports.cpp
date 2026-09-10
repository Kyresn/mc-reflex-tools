#include <jni.h>
#include <cstdlib>
#include <string>

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
// Holds the last non-OK Streamline result for diagnostics.
int g_lastSdkError = 0;

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

// Plugin DLL search path (the Streamline SDK bin/x64 directory), populated from
// the NVIDIA_STREAMLINE_ROOT environment variable.
std::wstring g_pluginPath;

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
    // marker this frame.
    if (slGetNewFrameToken(g_currentFrameToken) != sl::Result::eOk || g_currentFrameToken == nullptr) {
        return;
    }
    g_renderSubmitState = 0;
    slReflexSleep(*g_currentFrameToken);
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeMarker(JNIEnv*, jclass, jint marker) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    if (!g_initialized || g_currentFrameToken == nullptr) return;

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

    slPCLSetMarker(toPclMarker(marker), *g_currentFrameToken);
#else
    (void)marker;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeShutdown(JNIEnv*, jclass) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    if (g_sdkInitialized) {
        slShutdown();
        g_sdkInitialized = false;
        g_initialized = false;
        g_dlssInitialized = false;
        g_dlssGInitialized = false;
        g_currentFrameToken = nullptr;
        g_renderSubmitState = 0;
        g_suppressedRenderSubmitCount = 0;
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
