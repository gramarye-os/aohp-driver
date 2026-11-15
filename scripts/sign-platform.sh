#!/usr/bin/env bash
# Copyright (C) 2026 Chris Anderson
# SPDX-License-Identifier: Apache-2.0
# Sign an APK with the AOSP tree's platform key (never copied into this repo).
# usage: scripts/sign-platform.sh <in.apk> <out.apk>
set -euo pipefail
IN=${1:?in.apk}; OUT=${2:?out.apk}
KEYDIR=${AOHP_PLATFORM_KEYDIR:-/aosp/aohp/AOSP/build/make/target/product/security}
APKSIGNER=${APKSIGNER:-${ANDROID_HOME:-/aosp/android-sdk}/build-tools/36.0.0/apksigner}
"$APKSIGNER" sign --key "$KEYDIR/platform.pk8" --cert "$KEYDIR/platform.x509.pem" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT" "$IN"
"$APKSIGNER" verify --print-certs "$OUT" | head -3
echo "signed: $OUT"
