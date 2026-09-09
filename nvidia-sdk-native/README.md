# NVIDIA SDK distribution

This repository does not vendor NVIDIA Streamline, DLSS, Reflex, or related binary components.

## M2 readiness probe

The native bridge can compile without Streamline. In that mode it reports:

```text
libraryLoaded=true
streamlineSdkDetected=false
```

It never initializes or emulates Reflex.

To enable SDK-header detection, obtain the official NVIDIA Streamline SDK and confirm its license and redistribution terms. Then set the environment variable before configuring CMake:

```powershell
$env:NVIDIA_STREAMLINE_ROOT = 'C:\SDKs\Streamline'
cmake -S nvidia-sdk-native -B nvidia-sdk-native/build
cmake --build nvidia-sdk-native/build --config Release
```

The root must contain:

```text
include/sl.h
include/sl_helpers_vk.h
```

Header detection is not feature activation. Streamline initialization remains disabled until this project satisfies the required Vulkan device-creation proxy/queue requirements and receives the approved runtime binaries.
