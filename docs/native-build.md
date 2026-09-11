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

Copy the result into place, update the recorded checksum, and rebuild the jar:

```bash
cp nvidia-sdk-native/build/Release/mc_reflex_tools_native.dll \
   nvidia-sdk-java/src/main/resources/natives/windows-x64/
sha256sum nvidia-sdk-java/src/main/resources/natives/windows-x64/mc_reflex_tools_native.dll \
  > /tmp/new.sha256   # then paste the digest into nvidia-sdk-native/native-artifact.sha256
./gradlew :fabric:build
```

## What CI checks, and what it cannot

**CI provides no guarantee that the checked-in binary was built from the checked-in source.**
This cannot be fixed in a workflow: the DLL links against `sl.interposer.lib` and includes
Streamline headers, neither of which may be redistributed, so neither can be committed or
placed on a runner. A job that claims to verify binary provenance here would be lying.

What is checked instead:

- **`native-bridge`** compiles the source in both configurations, with and without the SDK.
  This catches a bridge that no longer builds, which is the failure that matters most.
- **`native-artifact-integrity`** verifies the DLL against the SHA-256 recorded in
  [`nvidia-sdk-native/native-artifact.sha256`](../nvidia-sdk-native/native-artifact.sha256),
  and checks that the last commit touching `jni_exports.cpp` or `CMakeLists.txt` is an
  ancestor of the last commit touching the binary. Both are consistency checks: they catch a
  truncated or swapped file, and the "edited the bridge and forgot to rebuild" mistake. They
  do not establish that the binary corresponds to the source, and neither timestamp nor
  ancestry could — a single commit can change both, and anyone able to commit the DLL can
  commit a matching checksum.

## Verifying the binary properly

Provenance requires rebuilding with the SDK present. That is what this script does:

```bash
scripts/verify-native-dll.sh
```

It checks the recorded checksum, rebuilds, and diffs the result against the checked-in file.
On the recorded toolchain it reports `match`; on any other MSVC or Windows SDK version it
reports a mismatch, which means the toolchain differs rather than that the binary is wrong.

Anyone reviewing a change to `nvidia-sdk-java/src/main/resources/natives/` should run it, or
ask for its output, rather than treating a green CI run as evidence about the binary.
