#!/usr/bin/env bash
# Build the AOHP Driver APK and copy it into the AOSP tree as the prebuilt consumed by
# packages/apps/AOHPDriver/Android.bp (android_app_import, certificate: "platform").
#
# Soong re-signs the imported APK with the tree's platform key, so the Gradle-side
# signature does not matter: we build the *release* build type (not debuggable, no
# minification — see app/build.gradle.kts) which AGP emits unsigned as
# app-release-unsigned.apk. Set BUILD_TYPE=debug to use the debug build instead.
#
# usage: scripts/update-aosp-prebuilt.sh [aosp-tree]      (default /aosp/aohp/AOSP)
set -euo pipefail
cd "$(dirname "$0")/.."
AOSP=${1:-${AOSP_TREE:-/aosp/aohp/AOSP}}
DEST="$AOSP/packages/apps/AOHPDriver"
BUILD_TYPE=${BUILD_TYPE:-release}
export ANDROID_HOME=${ANDROID_HOME:-/aosp/android-sdk}
export JAVA_HOME=${JAVA_HOME:-$(dirname $(dirname $(readlink -f $(command -v java))))}
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
[ -d "$DEST" ] || { echo "missing $DEST (AOSP tree without packages/apps/AOHPDriver)"; exit 1; }

case "$BUILD_TYPE" in
  release) ./gradlew --no-daemon -q assembleRelease; SRC=app/build/outputs/apk/release/app-release-unsigned.apk ;;
  debug)   ./gradlew --no-daemon -q assembleDebug;   SRC=app/build/outputs/apk/debug/app-debug.apk ;;
  *) echo "BUILD_TYPE must be release|debug"; exit 1 ;;
esac
[ -f "$SRC" ] || { echo "build produced no $SRC"; exit 1; }
cp -f "$SRC" "$DEST/AOHPDriver.apk"
# The llama-server runtime (jniLibs) also goes to the image as plain files: PackageManager does not
# extract a /system priv-app's native libs, and the daemon needs real files to stage from
# (/system/etc/aohp/llm via PRODUCT_COPY_FILES in vendor/aohp/aohp.mk; LlmManager falls back to it).
mkdir -p "$DEST/llm" && rm -f "$DEST/llm"/*.so && cp -f app/src/main/jniLibs/arm64-v8a/*.so "$DEST/llm/"
echo "runtime: $(ls "$DEST/llm" | wc -l) libs -> $DEST/llm"
ls -la "$DEST/AOHPDriver.apk"
"$ANDROID_HOME/build-tools/36.0.0/aapt" dump badging "$DEST/AOHPDriver.apk" | grep -E "^package:|^launchable-activity" || true
echo "updated $DEST/AOHPDriver.apk ($BUILD_TYPE). Rebuild the image: lunch aosp_arm64_aohp-trunk_staging-userdebug && m -j10 systemimage"
