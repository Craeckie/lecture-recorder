#!/usr/bin/env bash
#
# Builds a debug APK and signs it with your own keystore.
#
# Usage: scripts/release.sh <keystore-password>
#
# Override KEYSTORE / KEY_ALIAS to point at your own key; by default this
# looks for a keystore two directories up (the layout used when this repo is
# checked out as a submodule alongside a shared keystore).
set -euo pipefail
cd "$(dirname "$0")/.."
KEYSTORE="${KEYSTORE:-../../my-debug.jks}"
KEY_ALIAS="${KEY_ALIAS:-my-key}"
./gradlew --stop
./gradlew assembleDebug --no-daemon
apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --ks-pass "pass:$1" --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled false --out "$(pwd)/my-app-signed.apk" app/build/outputs/apk/debug/app-debug.apk
