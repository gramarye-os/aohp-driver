#!/usr/bin/env bash
# Copyright (C) 2026 Chris Anderson
# SPDX-License-Identifier: Apache-2.0
# Build + platform-sign + install + launch on the test phone.
# usage: scripts/install.sh [adb-serial]   (default e6d11a7c)
set -euo pipefail
SERIAL=${1:-${ADB_SERIAL:-e6d11a7c}}
cd "$(dirname "$0")/.."
export ANDROID_HOME=${ANDROID_HOME:-/aosp/android-sdk}
export JAVA_HOME=${JAVA_HOME:-$(dirname $(dirname $(readlink -f $(command -v java))))}
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew --no-daemon -q assembleDebug
OUT=/tmp/aohp-driver.apk
./scripts/sign-platform.sh app/build/outputs/apk/debug/app-debug.apk "$OUT"
adb -s "$SERIAL" install -r -g "$OUT"
adb -s "$SERIAL" shell am start -n org.aohp.driver/.MainActivity
