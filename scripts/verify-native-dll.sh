#!/usr/bin/env bash
#
# Rebuilds the native bridge and compares the result against the binary that is
# checked into the repository.
#
# The build is deterministic for a fixed toolchain (see docs/native-build.md), so on
# the recorded toolchain this reports "match". A mismatch on any other MSVC or Windows
# SDK version means the toolchain differs, not that the checked-in binary is wrong.
#
# Requires NVIDIA_STREAMLINE_ROOT to point at a Streamline SDK distribution, which is
# why this cannot run in CI.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
checked_in="$root/nvidia-sdk-java/src/main/resources/natives/windows-x64/mc_reflex_tools_native.dll"
built="$root/nvidia-sdk-native/build/Release/mc_reflex_tools_native.dll"

if [ -z "${NVIDIA_STREAMLINE_ROOT:-}" ]; then
    echo "NVIDIA_STREAMLINE_ROOT is unset." >&2
    echo "The bridge would build as a readiness stub, so there is nothing to compare." >&2
    exit 2
fi

if [ ! -f "$checked_in" ]; then
    echo "No checked-in binary at $checked_in" >&2
    exit 2
fi

echo "Rebuilding..."
cmake -S "$root/nvidia-sdk-native" -B "$root/nvidia-sdk-native/build" >/dev/null
cmake --build "$root/nvidia-sdk-native/build" --config Release >/dev/null

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
