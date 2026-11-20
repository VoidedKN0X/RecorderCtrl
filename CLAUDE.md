# RecorderCtrl

Voice recorder in the VKNXWorks Ctrl series. Kotlin, Jetpack Compose, Material 3, manual DI (`AppContainer`), DataStore for settings. Records with MediaRecorder in a microphone foreground service; recordings live in the app's private storage.

## Layout

- `record/RecorderService.kt`: MediaRecorder in a foreground service (type microphone), state as a StateFlow, the notification with Pause/Stop.
- `record/Player.kt`: MediaPlayer with audio focus and speed.
- `data/Recordings.kt`: files in `filesDir/recordings` (written as `.part`, renamed when saved), the JSON index, recovery of cut-off recordings, Save a copy to MediaStore.
- `RecordActivity.kt`: RECORD_SOUND for other apps; returns a FileProvider uri with a read grant for that one file.
- `ui/record`, `ui/list`: the two tabs.
- Speech to text: `app/src/main/cpp` builds whisper.cpp (vendored, trimmed, tag v1.9.5, commit d1be6fde) and `whisper_jni.cpp` into `librecorderctrl_whisper.so` (arm64-v8a, ARMv8.2-A+fp16+dotprod; `WhisperLib` checks /proc/cpuinfo before loading it). `transcribe/`: `ModelStore` (one imported ggml model in `filesDir/models`, header check in `ModelHeader.kt`), `AudioDecoder` (MediaCodec to 16 kHz mono via `Resampler`), `TranscribeService` (foreground service, type mediaProcessing, wake lock, 5-minute chunks), `Transcript` + `TranscriptWriter` (.txt/.srt). `data/Transcripts.kt` stores `<recording file>.transcript.json` next to the recording. `ui/transcript`: the transcript screen, the Transcribe dialog and Settings > Speech to text.
- Unit tests (`app/src/test`): resampler, transcript writers, model header check, the sealed file format.

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

## Encryption at rest

- `recordings.json` (the index) and `recordings/*.transcript.json` are sealed with AES-256-GCM (`data/crypto/SealedFile`, marker `RECORDERCTRL-REST1`) under the data key (`DataKeyStore`: 32 random bytes in `filesDir/data.key`, wrapped by the Keystore key `recorderctrl_data_at_rest` through `KeystoreWrapper`/`KeystoreAes`; no user authentication, so `TranscribeService` can save with the screen off; independent of the app lock).
- **Not sealed, on purpose:** the audio files (written by MediaRecorder during capture; opened by path by `Player`, `AudioDecoder` and `MediaMetadataRetriever`; handed to other apps as a FileProvider uri by Share and `RecordActivity`; can be far over the ~32 MB an in-memory AES-GCM pass allows), `.part` files, the model in `filesDir/models` (opened by whisper.cpp by path), DataStore settings and the app lock vault. Don't seal the audio without a streaming design for all of those paths.
- Readers accept both sealed and ordinary files (`SealedFile.isSealed`). Without a data key the app reads and writes ordinary files as before. The key is fetched in `Recordings.load`/`Transcripts.load` on `Dispatchers.IO`; never add Keystore work on the main thread.
- An ordinary file only becomes a sealed one through `SealedFile.sealInPlace` (temp file, sync, read back, compare, atomic rename), under `Recordings.lock` (index) or `Transcripts.fileLock` (transcripts; also held for saves and deletes). Never replace an existing `data.key`; never create a Keystore key on the decrypt path (`KeystoreAes.existing`).
- A sealed file that can't be opened is never overwritten: a transcript shows in `Transcripts.unreadable` (the transcript screen's "Can't open this transcript") and is renamed to `*.transcript.json.unreadable` before a new one is saved; the index is renamed to `recordings.json.unreadable`, rebuilt from the audio files, and its names are merged back when it opens at a later start.
- Transcript exports (.txt/.srt/share text) and recording share/Save a copy stay ordinary formats.
- `SealedFileTest` covers the format, the migration step and the key rules (`./gradlew :app:testDebugUnitTest`; CI only runs `assembleRelease`, so it isn't run there).

## Build toolchain

- AGP 9.5.1, Gradle 9.9.2, Kotlin 2.4.21, Compose BOM 2026.09.00, `compileSdk` 37 (Compose 1.12 needs it), `minSdk` 35, `targetSdk` 36. All versions are in `gradle/libs.versions.toml`.
- AGP 9 has built-in Kotlin: there is no `org.jetbrains.kotlin.android` plugin any more. The Kotlin version is pinned by the `kotlin-gradle-plugin` classpath entry in the root `build.gradle.kts`; the Compose and serialization compiler plugins use the same `kotlin` version. Don't re-add the Kotlin Android plugin.
- AGP 9's R8 is stricter (`-keep class X` without a member block no longer keeps the constructor; resources are shrunk together with code). Keep rules in `proguard-rules.pro` must name the members they need, and resources must be referenced through `R`, never by name.
- Any change here needs the dependency verification procedure below.

## Dependency verification

Gradle checks every library and plugin download against the sha256 checksums in `gradle/verification-metadata.xml` (checksums only, no PGP). When a dependency, plugin or the Gradle version changes, start the `verification-metadata.yml` workflow (`gh api -X POST repos/VoidedKN0X/RecorderCtrl/actions/workflows/verification-metadata.yml/dispatches -f ref=main`), take the file from its log (gzipped and base64-encoded between the BEGIN/END markers), check the diff (only Maven Central and Google's Maven) and commit it in the same push. Never turn verification off or make it lenient.

`settings.gradle.kts` lists only `google()` and `mavenCentral()` for plugins, not `gradlePluginPortal()`: the portal serves different files for the Kotlin plugin markers, which made verification fail whenever Maven Central had a hiccup. Don't add it back unfiltered (see the comment in that file).

## Build and signing (CI)

`.github/workflows/build.yml` has four jobs: `version` (is this a release?), `build` (Gradle, **no secrets**, `UNSIGNED_RELEASE=true` gives `app-release-unsigned.apk`; runs every check), `sign` (no checkout, no Gradle: `zipalign` + `apksigner` with the release key, then checks the signer against `EXPECTED_CERT_SHA256` and that the APK's contents didn't change) and `release` (attestation, release notes, publish).

- Never give the `build` job a secret, and never run Gradle or check out the repo in `sign`. New build checks (tests, lint, APK checks) go in `build`.
- `EXPECTED_CERT_SHA256` in `build.yml` must match the fingerprint in `SECURITY.md` and `README.md`; the build fails if it doesn't.
- No Gradle cache in any workflow (`cache-disabled: true`): a cache saved by a failed run used to break every later build.

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
