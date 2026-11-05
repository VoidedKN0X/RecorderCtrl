# RecorderCtrl

Voice recorder in the VKNXWorks Ctrl series. Kotlin, Jetpack Compose, Material 3, manual DI (`AppContainer`), DataStore for settings. Records with MediaRecorder in a microphone foreground service; recordings live in the app's private storage.

## Layout

- `record/RecorderService.kt`: MediaRecorder in a foreground service (type microphone), state as a StateFlow, the notification with Pause/Stop.
- `record/Player.kt`: MediaPlayer with audio focus and speed.
- `data/Recordings.kt`: files in `filesDir/recordings` (written as `.part`, renamed when saved), the JSON index, recovery of cut-off recordings, Save a copy to MediaStore.
- `RecordActivity.kt`: RECORD_SOUND for other apps; returns a FileProvider uri with a read grant for that one file.
- `ui/record`, `ui/list`: the two tabs.

## Hard rules

- **No `INTERNET` permission, ever** (the manifest removes it; CI fails the build if the APK has any network permission).
- **Google-free:** no Google libraries, services or SDKs, including transitive ones (CI checks `releaseRuntimeClasspath`). No WebView. Fonts are bundled.
- **Telemetry-free:** no analytics or crash reporting.
- arm64-v8a only. Every user-facing string goes in `strings.xml` (shared Ctrl strings in `strings_ctrl.xml`).
- Settings open only from the profile picture. No settings tab, menu or gear icon, no You tab.
- Must work as a normal user app on any ROM (Android 13+), not only when built into the system.
- Recordings stay in private storage; other apps only get a file through Share (one FileProvider grant) or Save a copy.
- The microphone is used only while a recording is running, through the non-exported `RecorderService`.
- Never lose a recording: write to `.part` and rename on save; recover readable `.part` files at start.

## Releasing

- A release is published only when `versionName` in `app/build.gradle.kts` is bumped. Bump `versionCode` by 1 at the same time.
- Tags are `vX.Y.Z` and releases are named "RecorderCtrl X.Y.Z". Never add a build number (`-build.N`, "(build N)").
- Use the same version for every change that ships together; README, licence and CI changes don't need a bump.
- Push straight to `main`; no branches or pull requests.

## Changelog

Every new version needs a detailed entry at the top of `CHANGELOG.md`, under a heading for that version (match the existing headings). CI refuses to release without one, and the entry becomes the release notes on GitHub, in Obtainium and in the CtrlF-Droid repo.

- Write for people using the app, not for developers: what's new, what changed, what was fixed.
- Cover every user-visible change in the version, one bullet each, with enough detail to understand it without opening the app.
- Group with `### New`, `### Changed`, `### Fixed` and `### Security` when there's more than a couple of items.
