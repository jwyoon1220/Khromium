#!/usr/bin/env bash
# Cross-platform native build script for Linux/macOS.
# Builds KhromiumCore shared library using CMake + QuickJS-ng + TLSF.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CPP_DIR="$SCRIPT_DIR/src/main/cpp"
OUT_DIR="$SCRIPT_DIR/build/native"
BUILD_DIR="$SCRIPT_DIR/build/native_cmake"

# Detect Java home (for JNI includes)
if [ -z "$JAVA_HOME" ]; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(which java)")")")"
fi
echo "Using JAVA_HOME: $JAVA_HOME"

JNI_INCLUDE="$JAVA_HOME/include"
if [ "$(uname)" = "Darwin" ]; then
    JNI_PLATFORM_INCLUDE="$JNI_INCLUDE/darwin"
else
    JNI_PLATFORM_INCLUDE="$JNI_INCLUDE/linux"
fi

# Check cmake
if ! command -v cmake &> /dev/null; then
    echo "WARNING: cmake not found. Skipping native build. JS engine will fall back to Nashorn."
    mkdir -p "$OUT_DIR"
    exit 0
fi

# Check git (needed by FetchContent)
if ! command -v git &> /dev/null; then
    echo "WARNING: git not found. Skipping native build. JS engine will fall back to Nashorn."
    mkdir -p "$OUT_DIR"
    exit 0
fi

mkdir -p "$BUILD_DIR"
mkdir -p "$OUT_DIR"

echo "Configuring CMake..."
cmake -S "$CPP_DIR" -B "$BUILD_DIR" \
    -DCMAKE_BUILD_TYPE=Release \
    -DJAVA_HOME="$JAVA_HOME" \
    -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$OUT_DIR" \
    2>&1 | tail -20

echo "Building native library..."
if ! cmake --build "$BUILD_DIR" --config Release --parallel "$(nproc 2>/dev/null || echo 2)" 2>&1 | tail -20; then
    echo "WARNING: Native build failed. JS engine will fall back to Nashorn."
    mkdir -p "$OUT_DIR"
    exit 0
fi

echo "--------------------------------------------"
echo "Build successfully finished!"
echo "Library location: $OUT_DIR/libKhromiumCore.so (or .dylib on macOS)"
