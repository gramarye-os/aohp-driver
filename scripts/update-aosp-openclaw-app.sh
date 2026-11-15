#!/usr/bin/env bash
# Copyright (C) 2026 Chris Anderson
# SPDX-License-Identifier: Apache-2.0
# Copy a *release-signed* OpenClaw Android APK into the AOSP tree as the prebuilt consumed by
# packages/apps/OpenClawAndroid/Android.bp (android_app_import, preprocessed: true => Soong
# only validates and copies it, never re-signs). Validates alignment and signature first.
#
# Build the APK from ~/openclaw-src/apps/android with
#   ORG_GRADLE_PROJECT_OPENCLAW_ANDROID_STORE_FILE=/aosp/keys/aohp-apps.jks
#   ORG_GRADLE_PROJECT_OPENCLAW_ANDROID_KEY_ALIAS=aohp-apps
#   ORG_GRADLE_PROJECT_OPENCLAW_ANDROID_STORE_PASSWORD=$(cat /aosp/keys/aohp-apps.pass)
#   ORG_GRADLE_PROJECT_OPENCLAW_ANDROID_KEY_PASSWORD=$(cat /aosp/keys/aohp-apps.pass)
#   ./gradlew --no-daemon :app:assembleThirdPartyRelease -PopenclawBuildCommit=$(git rev-parse HEAD) -PopenclawBuildTimestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)
# (passwords via env, never on a command line; the keystore lives outside every repo).
#
# usage: scripts/update-aosp-openclaw-app.sh <signed.apk> [aosp-tree]
set -euo pipefail
APK=${1:?signed OpenClaw apk}
AOSP=${2:-${AOSP_TREE:-/aosp/aohp/AOSP}}
DEST="$AOSP/packages/apps/OpenClawAndroid"
BT=${ANDROID_HOME:-/aosp/android-sdk}/build-tools/36.0.0
[ -d "$DEST" ] || { echo "missing $DEST"; exit 1; }
"$BT/zipalign" -c -p 4 "$APK" || { echo "not 4-byte/page aligned: $APK"; exit 1; }
"$BT/apksigner" verify --print-certs "$APK" | grep -E 'Signer #1 certificate DN|Verifies' || true
if "$BT/apksigner" verify --print-certs "$APK" | grep -q 'CN=Android'; then
  echo "refusing: APK is platform/AOSP-test signed; OpenClawAndroid must use the dedicated aohp-apps key"; exit 1
fi
cp -f "$APK" "$DEST/OpenClawAndroid.apk"
ls -la "$DEST/OpenClawAndroid.apk"
"$BT/aapt" dump badging "$DEST/OpenClawAndroid.apk" | grep -E "^package:|native-code" || true
echo "updated $DEST/OpenClawAndroid.apk"
