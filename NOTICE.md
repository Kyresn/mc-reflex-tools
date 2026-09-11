# Notices

## What the license covers

The MIT license in [`LICENSE`](LICENSE) covers the source code in this repository.

## What this repository does not include

**The NVIDIA Streamline SDK is not part of this repository.** No NVIDIA headers, import
libraries, or runtime DLLs (`sl.interposer.dll`, `sl.common.dll`, `sl.reflex.dll`, …) are
distributed here. You obtain the SDK from NVIDIA yourself and accept NVIDIA's license terms
for it. The build locates it through the `NVIDIA_STREAMLINE_ROOT` environment variable.

**No Minecraft code is part of this repository.** The mod is compiled against Minecraft
through the Fabric Loom toolchain, which fetches it separately on each machine.

## The checked-in native binary

`nvidia-sdk-java/src/main/resources/natives/windows-x64/mc_reflex_tools_native.dll` is a
build artifact compiled from [`nvidia-sdk-native/src/jni_exports.cpp`](../nvidia-sdk-native/src/jni_exports.cpp)
in this repository. It is checked in so that a clone produces a usable mod jar without a C++
toolchain. It links against `sl.interposer.lib` at build time and loads NVIDIA's
`sl.interposer.dll` at runtime; no NVIDIA code is statically embedded in it.

The CI job `native-bridge` proves the bridge still compiles from source, in both the
with-SDK and without-SDK configurations, and `native-dll-freshness` fails if
`jni_exports.cpp` has been edited more recently than the checked-in binary. CI cannot
rebuild the real DLL itself, because the Streamline SDK it links against is not
redistributable and therefore cannot be present on a build runner. See
[`native-build.md`](native-build.md) for the toolchain and how to verify the binary locally.

## Trademarks

NVIDIA, GeForce, Reflex, DLSS, G-SYNC and Streamline are trademarks and/or registered
trademarks of NVIDIA Corporation. Minecraft is a trademark of Mojang Synergies AB. Microsoft
and Windows are trademarks of Microsoft Corporation. Fabric is a project of the FabricMC
community.

This project is not affiliated with, endorsed by, or sponsored by NVIDIA, Mojang, Microsoft,
or FabricMC.

## No warranty

Reflex, DLSS and DLSS-G are NVIDIA technologies. This project only calls NVIDIA's own SDK —
it does not implement, emulate, or approximate any of them. Whether they engage depends on
your GPU, your driver, and the SDK version you supply.
