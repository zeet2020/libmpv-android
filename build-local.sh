#!/bin/bash
set -e

usage() {
    echo "Usage: build-local.sh [options] [-- build.sh args...]"
    echo "  -s, --skip-native  Skip native compilation, only build the AAR"
    echo "  -h, --help         Show this help"
    echo ""
    echo "Examples:"
    echo "  ./build-local.sh                        # full build"
    echo "  ./build-local.sh -s                     # AAR only (native libs must exist)"
    echo "  ./build-local.sh -- --arch arm64 mpv    # build only arm64 mpv"
    echo "  ./build-local.sh -- --clean             # clean build"
    exit 0
}

skip_native=0
while [ $# -gt 0 ]; do
    case "$1" in
        -s|--skip-native) skip_native=1; shift ;;
        -h|--help) usage ;;
        --) shift; break ;;
        *) break ;;
    esac
done

cd "$(dirname "$0")/buildscripts"

# Install macOS deps if needed
if [[ "$OSTYPE" == "darwin"* ]]; then
    if ! command -v brew &>/dev/null; then
        echo "Error: Homebrew is required. Install from https://brew.sh"
        exit 1
    fi
    for tool in automake autoconf libtool pkg-config nasm meson ninja wget; do
        command -v "$tool" &>/dev/null || brew install "$tool"
    done
fi

if [ $skip_native -eq 1 ]; then
    ./build.sh -n "$@"
else
    ./download.sh
    ./patch.sh
    ./build.sh "$@"
fi

echo ""
echo "Build output: libmpv/build/outputs/aar/libmpv-release.aar"
