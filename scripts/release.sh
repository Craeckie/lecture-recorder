#!/usr/bin/env bash
#
# The output filename `my-app-signed.apk` is a WORKSPACE-WIDE CONSTANT, not a
# placeholder. Do NOT rename it to match the project ("cupsprint-signed.apk",
# "wetter-signed.apk", ...) when adapting this template — every Android project
# in the workspace writes the same filename, and the user's install/sideload
# tooling looks for exactly that name. Same for `../my-debug.jks` and `my-key`.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew --stop
./gradlew assembleDebug --no-daemon
apksigner sign --ks ../my-debug.jks --ks-key-alias my-key --ks-pass "pass:$1" --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled false --out "$(pwd)/my-app-signed.apk" app/build/outputs/apk/debug/app-debug.apk
