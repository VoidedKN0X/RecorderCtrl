# RecorderCtrl

Voice recorder in the VKNXWorks Ctrl series. Kotlin, Jetpack Compose, Material 3, manual DI (`AppContainer`), DataStore for settings. Records with MediaRecorder in a microphone foreground service; recordings live in the app's private storage.

## Layout

- `record/RecorderService.kt`: MediaRecorder in a foreground service (type microphone), state as a StateFlow, the notification with Pause/Stop.
- `record/Player.kt`: MediaPlayer with audio focus and speed.
- `data/Recordings.kt`: files in `filesDir/recordings` (written as `.part`, renamed when saved), the JSON index, recovery of cut-off recordings, Save a copy to MediaStore.
- `RecordActivity.kt`: RECORD_SOUND for other apps; returns a FileProvider uri with a read grant for that one file.
- `ui/record`, `ui/list`: the two tabs.
- Speech to text: `app/src/main/cpp` builds whisper.cpp (vendored, trimmed, tag v1.9.5, commit d1be6fde) and `whisper_jni.cpp` into `librecorderctrl_whisper.so` (arm64-v8a, ARMv8.2-A+fp16+dotprod; `WhisperLib` checks /proc/cpuinfo before loading it). `transcribe/`: `ModelStore` (one imported ggml model in `filesDir/models`, header check in `ModelHeader.kt`), `AudioDecoder` (MediaCodec to 16 kHz mono via `Resampler`), `TranscribeService` (foreground service, type mediaProcessing, wake lock, 5-minute chunks), `Transcript` + `TranscriptWriter` (.txt/.srt). `data/Transcripts.kt` stores `<recording file>.transcript.json` next to the recording. `ui/transcript`: the transcript screen, the Transcribe dialog and Settings > Speech to text.
- Unit tests (`app/src/test`): resampler, transcript writers, model header check.

## Hard rules

- **No `INTERNET` permission, ever** (the manifest removes it; CI fails the build if the APK has any network permission).
- **Google-free:** no Google libraries, services or SDKs, including transitive ones (CI checks `releaseRuntimeClasspath`). No WebView. Fonts are bundled.
- **Telemetry-free:** no analytics or crash reporting.
- arm64-v8a only. Every user-facing string goes in `strings.xml` (shared Ctrl strings in `strings_ctrl.xml`).
- Settings open only from the profile picture. No settings tab, menu or gear icon, no You tab.
- Must work as a normal user app on any ROM (Android 15+), not only when built into the system.
- Recordings stay in private storage; other apps only get a file through Share (one FileProvider grant) or Save a copy.
- The microphone is used only while a recording is running, through the non-exported `RecorderService`.
- Speech to text stays on the phone: models are imported by the user through the system file picker, never downloaded by the app; the only link is the models page opened in the browser. Transcripts live next to their recordings and are deleted with them.
- Never lose a recording: write to `.part` and rename on save; recover readable `.part` files at start.

## Dependency verification

Gradle checks every library and plugin download against the sha256 checksums in `gradle/verification-metadata.xml` (checksums only, no PGP). When a dependency, plugin or the Gradle version changes, start the `verification-metadata.yml` workflow (`gh api -X POST repos/VoidedKN0X/RecorderCtrl/actions/workflows/verification-metadata.yml/dispatches -f ref=main`), take the file from its log (gzipped and base64-encoded between the BEGIN/END markers), check the diff (only Maven Central, Google's Maven and the Gradle plugin portal) and commit it in the same push. Never turn verification off or make it lenient.

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
