#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to Android NDK r28c}"
compiler_root="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
for abi in arm64-v8a x86_64; do
  case "$abi" in arm64-v8a) triple=aarch64-linux-android;; *) triple=x86_64-linux-android;; esac
  mkdir -p "app/src/main/jniLibs/$abi"
  "$compiler_root/${triple}35-clang++" -std=c++17 -O2 -fPIC -shared -static-libstdc++ -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-z,relro,-z,now app/src/main/cpp/loop_rime.cpp -ldl -o "app/src/main/jniLibs/$abi/libloop_rime.so"
done
