# Lecture Recorder

An unofficial Android app that wraps [KIT's live-transcription site](https://lt2srv.iar.kit.edu/)
in a `WebView` and keeps it recording reliably in the background.

**Not affiliated with or endorsed by KIT.** This is a personal project, built to fix
problems the plain mobile browser has with that site during a live lecture:

- **Keeps the microphone alive in the background.** Since Android 9, a backgrounded app
  with no microphone-type foreground service is fed digital silence instead of real audio
  or an error — silently. This app runs a foreground service while a recording is live so
  the mic keeps working when you switch apps or lock the screen.
- **Survives rotation and day/night switches.** The site ends the recording session when
  the page unloads, so anything that reloads the `WebView` (a rotation, a system theme
  change) would silently kill a live recording. The activity is configured to survive
  those instead of recreating the page.
- **Remembers session short links.** Every `/webapi/shorten/<name>` short link the page
  loads (via tap, redirect, initial load, or a shared QR code) is collected into a start
  screen, so you can jump back into a session without hunting for the link again.
- Dark theme in night mode (via [Dark Reader](https://github.com/darkreader/darkreader)),
  an in-app error page with auto-retry, and the page console forwarded to `logcat` for
  debugging.

## Building

Requires JDK 17 and the Android SDK (`compileSdk`/`targetSdk` 35, `minSdk` 26).

```sh
./gradlew testReleaseUnitTest
./gradlew assembleRelease
```

CI (`.github/workflows/build.yml`) runs the same two steps on every push. If the repo has
`KEYSTORE_BASE64` and `KEYSTORE_PASSWORD` configured as secrets, it uploads a signed
release APK as the `app-release-signed` build artifact; otherwise (e.g. on a fork without
those secrets) it falls back to `app-release-unsigned`. The signing key alias defaults to
`my-key` and the key password to the store password (a local Gradle build can override
either with the `KEY_ALIAS`/`KEY_PASSWORD` environment variables).

Releases happen by version bump: raise `versionName` and `versionCode` in
`app/build.gradle.kts` and push to `master`. `.github/workflows/release.yml` then tags
`v<versionName>` and publishes a GitHub release with the signed
`lecture-recorder-v<versionName>.apk`. No tags are pushed by hand.

## Signing a release build

If you don't have access to the CI-signed `app-release-signed` artifact — e.g. building
from your own fork — sign a build with your own key instead:

```sh
scripts/release.sh <keystore-password>
```

By default this looks for a keystore two directories up (`../../my-debug.jks`, alias
`my-key`); point it at your own instead with the `KEYSTORE` and `KEY_ALIAS` environment
variables:

```sh
KEYSTORE=/path/to/your.jks KEY_ALIAS=your-alias scripts/release.sh <keystore-password>
```

Or sign `app/build/outputs/apk/debug/app-debug.apk` (from `./gradlew assembleDebug`)
however you prefer — `apksigner`, Android Studio, etc.

## License

MIT — see [LICENSE](LICENSE). `Dark Reader` code vendored under `app/src/main/assets/`
ships under its own MIT license.
