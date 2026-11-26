#!/usr/bin/env bash
set -euo pipefail

case "${1:-}" in
    iphoneos|iphonesimulator) sdk=$1 ;;
    *) echo "Usage: $0 {iphoneos|iphonesimulator} [build-directory]" >&2; exit 2 ;;
esac
root=$(cd "$(dirname "$0")/.." && pwd)
out=${2:-"$root/nativelibs/build/native/$sdk"}
mkdir -p "$out"
python3 -B "$root/tools/ios-native-manifest.py" "$root/app/src/main/cpp" "$out"
cmake -S "$root/nativelibs/src/native" -B "$out" -G Ninja \
    -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_OSX_SYSROOT="$(xcrun --sdk "$sdk" --show-sdk-path)" \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=18.0 -DCMAKE_BUILD_TYPE=MinSizeRel
cmake --build "$out" --parallel "${CMAKE_BUILD_PARALLEL_LEVEL:-6}"
