#include <jni.h>

namespace {
constexpr jint kUnavailable = 1;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeInitialize(
        JNIEnv*, jclass, jlong, jlong, jlong, jlong, jint, jlong) {
    // M0 intentionally does not load Streamline or emulate Reflex. Replace this
    // only after an approved SDK package and a verified Minecraft Vulkan context
    // integration exist.
    return kUnavailable;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeSleep(JNIEnv*, jclass) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeMarker(JNIEnv*, jclass, jint) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_kyresn_mcreflex_nvidia_NativeReflexProvider_nativeShutdown(JNIEnv*, jclass) {
}
