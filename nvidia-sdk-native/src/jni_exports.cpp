#include <jni.h>

#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
#include <sl.h>
#include <sl_reflex.h>
#include <sl_pcl.h>
#include <sl_dlss.h>
#endif

namespace {
constexpr jint kSuccess = 0;
constexpr jint kUnavailable = 1;

#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
bool g_initialized = false;
uint64_t g_currentFrameIndex = 0;
bool g_dlssInitialized = false;

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
    return newBridgeStatus(env, JNI_TRUE, JNI_TRUE, "NVIDIA Streamline SDK integrated with Reflex & PCL support");
#else
    return newBridgeStatus(env, JNI_TRUE, JNI_FALSE, "NVIDIA_STREAMLINE_ROOT is not configured at native build time");
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

    sl::Preferences pref{};
    pref.showConsole = false;
    pref.logLevel = sl::LogLevel::eDefault;

    sl::Result res = slInit(pref, sl::kSDKVersion);
    if (res != sl::Result::eOk) {
        return kUnavailable;
    }

    sl::ReflexOptions reflexOptions{};
    reflexOptions.mode = sl::ReflexMode::eLowLatency;
    slReflexSetOptions(reflexOptions);

    g_initialized = true;
    g_currentFrameIndex = 0;
    return kSuccess;
#else
    (void)instance; (void)physicalDevice; (void)device;
    (void)graphicsQueue; (void)queueFamilyIndex; (void)swapchain;
    return kUnavailable;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeSleep(JNIEnv*, jclass) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    if (!g_initialized) return;

    sl::FrameToken* currentFrame = nullptr;
    uint32_t frameIdx = static_cast<uint32_t>(g_currentFrameIndex);
    if (slGetNewFrameToken(currentFrame, &frameIdx) == sl::Result::eOk && currentFrame) {
        slReflexSleep(*currentFrame);
    }
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeMarker(JNIEnv*, jclass, jint marker) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    if (!g_initialized) return;

    if (marker == 1) { // SIMULATION_START
        g_currentFrameIndex++;
    }

    sl::FrameToken* currentFrame = nullptr;
    uint32_t frameIdx = static_cast<uint32_t>(g_currentFrameIndex);
    if (slGetNewFrameToken(currentFrame, &frameIdx) == sl::Result::eOk && currentFrame) {
        slPCLSetMarker(toPclMarker(marker), *currentFrame);
    }
#else
    (void)marker;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeShutdown(JNIEnv*, jclass) {
#ifdef MC_REFLEX_TOOLS_HAS_STREAMLINE
    if (g_initialized) {
        slShutdown();
        g_initialized = false;
        g_dlssInitialized = false;
    }
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
