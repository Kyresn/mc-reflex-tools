# Building and verifying the native bridge

`nvidia-sdk-java/src/main/resources/natives/windows-x64/mc_reflex_tools_native.dll` is a
checked-in build artifact. It is compiled from
[`nvidia-sdk-native/src/jni_exports.cpp`](../nvidia-sdk-native/src/jni_exports.cpp) and is a
build *input* for the mod jar, not a build output — the `fabric` module nests it into the
distributed jar, and a clone without a C++ toolchain must still produce a working jar.

## Toolchain

The checked-in binary was produced with:

| | |
| --- | --- |
| Generator | Visual Studio 18 2026, `-A x64` |
| Windows SDK | 10.0.28000.0 |
| Platform toolset | v145 |
| Language standard | C++20 |
| Streamline SDK | v2.14.1 (`v2.14.1.98614dad6`) |

The build is deterministic for a fixed toolchain: `nvidia-sdk-native/CMakeLists.txt` passes
`/Brepro` to the compiler and linker and `/pathmap` to normalize source paths, so the PE
timestamp and debug paths do not vary between machines. It is **not** deterministic across
toolchain versions — a different MSVC build produces different bytes for the same source.

## Build

```bash
export NVIDIA_STREAMLINE_ROOT='<path-to-streamline-sdk-v2.14.1>'
cmake -S nvidia-sdk-native -B nvidia-sdk-native/build -G "Visual Studio 18 2026" -A x64
cmake --build nvidia-sdk-native/build --config Release
```

When `NVIDIA_STREAMLINE_ROOT` is unset the bridge still compiles, but as a readiness stub:
`MC_REFLEX_TOOLS_HAS_STREAMLINE` is undefined, every entry point reports unavailability, and
the resulting DLL is **not** a substitute for the checked-in one.

Copy the result into place and rebuild the jar:

```bash
cp nvidia-sdk-native/build/Release/mc_reflex_tools_native.dll \
   nvidia-sdk-java/src/main/resources/natives/windows-x64/
./gradlew :fabric:build
```

## What CI does, and what it cannot do

`native-bridge` compiles the bridge in both configurations. That catches a source that no
longer builds, which is the failure that matters most.

CI cannot rebuild the real DLL, because it links against `sl.interposer.lib` and includes the
Streamline headers — neither of which may be redistributed, so neither can be checked in or
placed on a runner. A byte-for-byte provenance check therefore has to be run by someone who
has the SDK.

`native-dll-freshness` covers the gap that CI *can* see without the SDK: it fails when
`jni_exports.cpp` has been committed more recently than the checked-in DLL, which is exactly
the "edited the bridge, forgot to rebuild" mistake.

## Verifying the binary locally

If you have the SDK, rebuild as above and compare:

```bash
scripts/verify-native-dll.sh
```

It rebuilds and diffs against the checked-in file, and prints both hashes. On the recorded
toolchain it reports identical; on any other MSVC version it will report a mismatch, which
means the toolchain differs, not that the binary is wrong.
