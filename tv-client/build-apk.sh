#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

echo "=========================================="
echo " Building TV Diagnostics Android TV APK   "
echo "=========================================="

mkdir -p dist

if command -v docker &> /dev/null; then
    echo "[Build] Using Dockerized Android SDK build pipeline..."
    docker build -f Dockerfile.build -t tv-apk-builder .
    docker run --rm -v "$DIR/dist:/dist" tv-apk-builder
    echo "=========================================="
    echo " Build Complete!"
    echo " APK output: $DIR/dist/tv-diagnostics.apk"
    echo "=========================================="
else
    echo "[Build] Running local Gradle build..."
    ./gradlew assembleDebug --no-daemon
    cp app/build/outputs/apk/debug/*.apk dist/tv-diagnostics.apk
    echo "=========================================="
    echo " Build Complete!"
    echo " APK output: $DIR/dist/tv-diagnostics.apk"
    echo "=========================================="
fi
