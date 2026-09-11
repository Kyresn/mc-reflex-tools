#!/usr/bin/env bash
#
# Rebuilds the native bridge and compares the result against the binary that is
# checked into the repository.
#
# This is the only check that actually ties the checked-in DLL to its source. CI cannot
# run it, because the Streamline SDK it links against is not redistributable and so can
# never be present on a runner.
#
# The build is deterministic for a fixed toolchain (see docs/native-build.md), so on the
# recorded toolchain this reports "match". A mismatch on any other MSVC or Windows SDK
# version means the toolchain differs, not that the checked-in binary is wrong.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

checked_in="nvidia-sdk-java/src/main/resources/natives/windows-x64/mc_reflex_tools_native.dll"
built="nvidia-sdk-native/build/Release/mc_reflex_tools_native.dll"

if [ -z "${NVIDIA_STREAMLINE_ROOT:-}" ]; then
    echo "NVIDIA_STREAMLINE_ROOT is unset." >&2
    echo "The bridge would build as a readiness stub, so there is nothing to compare." >&2
    exit 2
fi

if [ ! -f "$checked_in" ]; then
    echo "No checked-in binary at $checked_in" >&2
    exit 2
fi

echo "Checking the checked-in binary against the recorded checksum..."
sha256sum -c nvidia-sdk-native/native-artifact.sha256

echo "Rebuilding..."
cmake -S nvidia-sdk-native -B nvidia-sdk-native/build >/dev/null
cmake --build nvidia-sdk-native/build --config Release >/dev/null

checked_sum="$(sha256sum "$checked_in" | cut -d' ' -f1)"
built_sum="$(sha256sum "$built" | cut -d' ' -f1)"

echo "checked in: $checked_sum"
echo "rebuilt:    $built_sum"

if [ "$checked_sum" = "$built_sum" ]; then
    echo "match"
    exit 0
fi

echo "MISMATCH" >&2
echo "Either jni_exports.cpp changed without rebuilding the checked-in binary, or this" >&2
echo "machine's MSVC / Windows SDK differs from the recorded toolchain." >&2
exit 1
