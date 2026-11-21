# Changelog

What changed in each RecorderCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/RecorderCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

## Unreleased

### New

- **RecorderCtrl in French and German, following the phone's language.** Every screen, message and setting now also comes in French (polite "vous" form) and German (informal "du"), next to English and Dutch. Android's per-app language setting (Settings > Apps > RecorderCtrl > Language) now offers all four. Translated by Claude and checked by script for missing strings and placeholders; not yet read through by a native speaker or run on a phone.

## v0.5.0 (2026-11-21)

Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### New

- **Bookmarks while recording.** While recording, tap **Add bookmark** on the Record screen or **Add bookmark** in the recording notification to mark that moment (at most one a second). The Record screen counts them, they're saved with the recording, and the recording's player in the Recordings tab lists them: tap one to play from there. A recording recovered after a crash has no bookmarks. Bookmarks are stored in the encrypted list of recordings.
- **Pause during calls** (Settings → Recording, on by default). While the phone rings or a phone or internet call is going on, a recording pauses by itself ("Paused for a call") and carries on when the call is over, unless you pressed Pause or Resume yourself in the meantime. RecorderCtrl only looks at Android's audio mode for this; it gets no phone permission and learns nothing about the call.
- **Microphone choice** (Settings → Recording → Microphone): Automatic (as before), Phone, Wired or USB, or Bluetooth. A Bluetooth headset's microphone is recorded in mono with voice processing, because that's the only way Android offers it. If the microphone you chose isn't connected when you start, the recording uses the phone's own choice and the Record screen says so. Needs confirming on your phone, especially Bluetooth.
- **Lossless quality.** A new **Lossless** option records 16-bit PCM WAV at 48 kHz (about 330 MB an hour, 660 MB in stereo), with nothing thrown away. A lossless recording that's cut off by a crash or power loss is repaired and recovered like the others. WAV files stop just before their 4 GB limit (about 6 hours in stereo) and are saved.
- **Trim and cut.** The scissors in a recording's player open **Trim or cut**: pick a part with the slider (and nudge the start and end a second at a time), play it, then **Keep only the selection** or **Remove the selection**. The result is saved as a new recording ("… (trimmed)" or "… (cut)") with the bookmarks moved along; the original stays as it is, so delete it yourself if you don't need it. Nothing is re-encoded: WAV is cut to the sample, .m4a and .ogg to the nearest audio packet (about 20 ms).
- **Playback controls in the notification.** While a recording is loaded in the player, a notification (and Android's media controls) has play/pause and 10 seconds back or forward; swipe it away to stop. On the lock screen it only says "Playing a recording". While the app lock is locked it can pause and stop but not start playback.
- **Transcribe while charging** (Settings → Speech to text, off by default). Recordings that have no transcript yet are transcribed by themselves, oldest first and one at a time, while the phone is charging, with the usual progress notification. Unplugging stops it and it carries on the next time; one that fails or that you cancel is left alone until RecorderCtrl restarts. It needs an imported model and **Unrestricted** battery use (Android only lets an app start this in the background then; Settings links to App info). After a phone restart it's set up again the next time RecorderCtrl runs.
- **Export and import of settings** (Settings → Export and import). Saves your recording and speech-to-text settings (quality, stereo, microphone mode and choice, pause during calls, keep screen on, language, transcribe while charging) to a JSON file you choose, and imports such a file after showing what you'll get and asking first. Recordings, transcripts, the speech model, the PIN, the app lock and the privacy settings are never in the file and an import never changes them.

- **RecorderCtrl in Dutch.** When your phone is set to Dutch (Nederlands), RecorderCtrl now shows every screen and notification in Dutch (Belgian Dutch, informal "je"); in any other language it stays in English. It follows the phone's language setting (also Android's per-app language), with no language choice inside the app. Technical names (whisper.cpp, ggml, AAC, Opus, WAV), units and the app name stay as they are. New recordings get a Dutch default name ("Opname …") when the phone is in Dutch; existing names don't change.

### Changed

- The recording notification has a third button, **Add bookmark**.

## v0.4.0 (2026-11-21)

Built and checked by CI only: nothing in this release had been run on a phone when it was published. It is the first release built with the new build tools (Android Gradle Plugin 9, stricter code shrinking), so update over your installed version and check that your data and settings are still there.

### Changed

- **Renaming a recording no longer makes the screen wait for storage.** The list of recordings (which is encrypted and written safely to disk on every change) was saved on the screen's own thread when you renamed a recording, and had to wait its turn if the app was still reading the recordings just after opening. It is now saved in the background, like adding and deleting a recording already were. The new name shows a moment after you confirm it instead of in the very same instant; nothing else changes.
- **Newer build tools and libraries; nothing changes in how RecorderCtrl looks or works.** RecorderCtrl is now built with the current stable toolchain: Android Gradle Plugin 8.11.1 → 9.5.1, Gradle 8.14.3 → 9.9.2, Kotlin 2.2.0 → 2.4.21 (with its Compose and serialization compiler plugins) and Compose BOM 2025.07.00 → 2026.09.00 (Compose 1.12.1, Material 3 1.4.0). The libraries move to the versions the newest Ctrl apps use: AndroidX Activity 1.10.1 → 1.13.0, Core 1.16.0 → 1.18.0, Lifecycle 2.9.1 → 2.10.0, Navigation 2.9.1 → 2.9.8, DataStore 1.1.7 → 1.3.0, kotlinx.serialization 1.9.0 → 1.13.0 and kotlinx.coroutines 1.10.2 → 1.11.0. No library was added, and the app is still Google-free and offline (CI checks both).
- **Compiled against the Android 17 SDK.** Compose 1.12 needs `compileSdk` 37, so that goes from 36 to 37. The Android versions RecorderCtrl runs on and targets are unchanged (`minSdk` 35, `targetSdk` 36), so Android treats the app exactly as before.
- **The app is shrunk by a newer R8.** Android Gradle Plugin 9 compiles Kotlin itself (the separate Kotlin Android plugin is gone from the build files) and brings the R8 version that understands Kotlin 2.4. RecorderCtrl's own keep rules are unchanged.
- The checksums Gradle verifies every download against (`gradle/verification-metadata.xml`) were regenerated on CI for the new versions. Built and checked by CI only; not yet run on a phone.

### Security

- **The signing key is no longer available to the build.** Until now the release key and its passwords were present in the same GitHub Actions job that runs Gradle, so any build plugin or library could in principle have read them. The build job now has no secrets at all and produces an unsigned APK. A separate job, which checks out no code and runs no Gradle, signs it with `apksigner`, and then refuses to hand it on unless it is signed by exactly the certificate published in SECURITY.md and README.md and its contents are identical to what the build job made. Builds are signed with the same key and the same signature scheme (v2) as before, so updates install as usual. Nothing in the app itself changed for this.
- **The build checks that the published fingerprint and the enforced one are the same.** If `build.yml`, SECURITY.md and README.md ever disagree about the signing certificate, the build fails.

### Project

- **Builds no longer use a saved Gradle cache.** Ctrl apps had builds fail with "Dependency verification failed ... from repository Gradle Central Plugin Repository", and keep failing until the caches were deleted by hand. The cause, reproduced on DateCtrl: with a restored cache Gradle still fetches the plugin files from Maven Central on every build; when Maven Central can't be reached for a moment, Gradle takes the Kotlin plugin "marker" file from the Gradle plugin portal instead, the portal serves a different file under the same name, verification rightly refuses it, and the failed run then saved that state as the cache for every later run. Now every build and the code scan start from a clean Gradle home, and the plugin portal is no longer a plugin source at all (`settings.gradle.kts`): everything the build needs is on Maven Central and Google's Maven. Dependency verification itself is unchanged and as strict as before.
- The new build-and-sign path has run for ordinary builds only. RecorderCtrl's release job (publishing) has not run with it yet; the next real release is its first use here.

## v0.3.0 (2026-11-20)

**This release encrypts some of what the app stores on the phone. An older version cannot read it afterwards, so export or back up anything you need before you update (details below).** Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### Security

- **Transcripts and the list of recordings are now encrypted on the phone.** Every transcript (`<recording>.transcript.json`) and the index that holds your recordings' names, dates and lengths (`recordings.json`) are sealed with AES-256-GCM under a random 256-bit data key. That key is stored in `data.key` in RecorderCtrl's private storage, itself encrypted by a key in the phone's secure hardware (Android Keystore, StrongBox when the phone has it) that can't be exported. This protects those files when they are copied off the phone (a backup image, a forensic copy, a rooted file manager); it does not protect against something already running inside RecorderCtrl while the app is open. The key needs no fingerprint or PIN, so a transcript can still be saved with the screen off, and it is independent of the app lock.
- **The audio itself is deliberately not encrypted by this change.** Recordings stay ordinary audio files in RecorderCtrl's private storage (still protected by the phone's own storage encryption and invisible to other apps). The reasons: the recorder writes them while recording and that path must not change; Android's player, the speech-to-text decoder and the length check open them by file path; Share and "record a sound for another app" hand another app a link straight to the file; and long recordings are far larger than can be decrypted in memory in one piece. The imported speech-to-text model (hundreds of megabytes, opened by whisper.cpp by path, not personal) and the settings are not encrypted either. The app lock's own storage is unchanged.
- **The move is automatic and can't lose a transcript.** When this version starts, each existing transcript and the index are read as before. An encrypted copy is then written to a temporary file, synced to disk, read back, decrypted and compared byte for byte; only when it matches does it take the old file's place, in one step. If anything goes wrong the old file stays in use and RecorderCtrl tries again at the next start. Both kinds of file are read from now on. All of this runs in the background, not on the screen's thread.
- **If an encrypted transcript can't be opened** (the key is missing from the phone's secure storage, or the file is damaged), it is not deleted or overwritten. The transcript screen says "Can't open this transcript" with **Try again**, and the recording itself still plays, shares and exports normally. You can make a new transcript with **Transcribe** in the Recordings list; the one that can't be opened is then kept aside as `<recording>.transcript.json.unreadable`. Deleting the recording or the transcript deletes it as well.
- **If the encrypted list of recordings can't be opened**, it is kept aside as `recordings.json.unreadable` and the list is built again from the audio files, as RecorderCtrl already did for a missing index: every recording is still there, but shows its file name (`rec_...`) instead of the name you gave it. If the key works again at a later start, the names come back by themselves. "Forgot PIN?" deletes a list that was kept aside along with everything else.
- **Without a working key** (the phone's secure hardware can't provide one) RecorderCtrl keeps reading and writing ordinary files exactly as before.
- **Exports are unchanged.** Exporting a transcript as .txt or .srt, sharing its text, sharing a recording and Save a copy produce the same ordinary files as before.
- **No going back to an older version with your transcripts.** RecorderCtrl 0.2.1 and older can't read the encrypted files: they show no transcripts and file names instead of your recording names, and rewrite the index. Export the transcripts you need (.txt or .srt) before installing an older version. The recordings themselves are not affected.

## v0.2.1 (2026-11-20)

Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### Changed

- **Much less battery use on long recordings.** The level meter behind the waveform used to wake the phone 20 times a second for the whole recording, also with the screen off, in another app and while paused: about 72,000 times an hour for a waveform nobody was looking at. It now runs only while you are recording and the Record screen is actually showing. The recording itself is not affected in any way. When you come back to the Record screen the waveform carries on from where it was, and the few seconds from before scroll away.
- **Smoother lists while recording.** The Recordings list and an open transcript no longer redraw 20 times a second while a recording is running.
- **Opens with your settings already in place.** RecorderCtrl now waits a few milliseconds (half a second at most) for your saved settings before it draws its first screen, so the Record screen no longer shows the default quality for a moment and then changes.

### New

- **A warning when storage is almost full.** With less than 100 MB free on the phone, the Record screen shows a note that a long recording may not fit. It is only a warning: you can still record.

### Privacy

- **"Hide in screenshots" is on from the very first frame.** The app's window now starts hidden from screenshots and screen recordings, and that is lifted only once your saved settings say the switch is off. Before, the protection was switched on a few frames after the app opened.

## v0.2.0 (2026-11-13)

Speech to text, on your phone. RecorderCtrl can now turn a recording into text with timestamps, without going online.

### New

- **Transcribe a recording.** Open a recording and tap **Transcribe**. It runs on your phone with [whisper.cpp](https://github.com/ggml-org/whisper.cpp) and a model you import; the audio and the text never leave RecorderCtrl. It keeps going with the screen off or in another app, shows its progress in the recording and in a notification, and can be cancelled from both. You get a notification when the transcript is ready. Works with every recording format (Standard, High and Small, mono or stereo).
- **Language:** detect it automatically, or pick one of the nearly 100 languages whisper knows. The choice is remembered (Settings > Speech to text > Language) and can be changed each time you transcribe. English-only models (`.en`) always transcribe English.
- **Bring your own model.** RecorderCtrl has no internet access, so it never downloads a model itself. Download a whisper.cpp model on your phone or computer (for example `ggml-base.bin`, or `ggml-small.bin` for better accuracy), then import it under **Settings > Speech to text > Import a model** with the system file picker. **Where do I get a model?** opens the whisper.cpp models page in your browser. RecorderCtrl checks that the file really is a whisper model before it copies it, then shows its name, type and size, with **Replace** and **Delete**.
- **Read along.** The transcript shows every line with its time. Tap a line to play the recording from there; the line being played is highlighted and the list follows the playback.
- **Search transcripts.** The search in the Recordings tab now finds words in transcripts as well as in names, and shows where it found them. Recordings with a transcript have a small subtitles mark.
- **Export:** save a transcript as plain text (.txt) or subtitles (.srt) wherever you choose, or share the text with another app.
- **Transcripts are kept next to their recordings,** in RecorderCtrl's private storage. Deleting a recording deletes its transcript; you can also delete just the transcript, or transcribe again (for example with a bigger model).
- **Open-source parts** on the You page shows the licence of whisper.cpp and ggml (MIT).

### Changed

- **Two new install-time permissions, no prompts:** "run foreground service with the type media processing" lets a transcription keep going in the background (Android allows this for up to 6 hours a day), and "prevent phone from sleeping" keeps the processor awake while it runs. Both are used only while a transcription is running.
- **Bigger download:** the speech-to-text engine adds about 1.7 MB to the app. Models are separate files you import (from about 30 MB for tiny up to about 3 GB for large).
- Speech to text needs a phone processor with ARMv8.2 and dot product (nearly every phone from 2018 on). On older processors RecorderCtrl says so instead of transcribing.

## v0.1.6 (2026-11-13)

A small security and privacy update. Recording, playback and sharing work as before.

### Privacy

- **No debug logging in the app.** Release builds now strip debug, verbose and info log messages from the app and the libraries it uses, so nothing about your recordings can end up in the phone's system log.

### Security

- **Checked build tools.** The build now checks the Gradle download against its published checksum, so a tampered build tool can't slip into a release.

## v0.1.5 (2026-11-12)

### Changed

- **Slightly smaller download.** The bundled JetBrains Mono font (numbers, readouts and labels) no longer carries the Cyrillic, Vietnamese and maths-alphabet letters RecorderCtrl never shows itself, which takes about 0.1 MB off the download and 0.2 MB off the installed app. Everything looks the same; if a name or a log line shown in that font uses one of those scripts, those letters are now drawn with your phone's own font.

## v0.1.4 (2026-11-05)

### Changed

- **Needs Android 15 or newer.** Android 13 no longer gets security updates and Android 14's updates end in early 2027, so RecorderCtrl now supports Android 15, 16 and 17 only. On older phones the version you have keeps working, but it won't get updates.

## v0.1.3 (2026-11-05)

### Changed

- **Delete and discard confirmations work with accessibility apps again.** Since 0.1.2 these confirmations, like the lock and PIN screens, were hidden from apps that use accessibility access without being an accessibility tool (automation apps, for example). Now only the lock and PIN screens are hidden from them. Other apps' floating windows are still hidden over the confirmations, as in 0.1.2, and TalkBack works everywhere as before.

## v0.1.2 (2026-11-05)

### Security

- **No overlays over the lock screen.** While the lock screen, a PIN screen (turning the lock on or off, changing the PIN), "Forgot PIN?" or a delete or discard confirmation is showing, other apps' floating windows (chat heads, screen overlays) are hidden over it, so no other app can cover it or put a fake screen on top. They come back as soon as that screen closes, and work as usual everywhere else. This uses a new install-time permission, "Hide other apps' floating windows"; there's no prompt.
- **PIN screens hidden from accessibility misuse.** On Android 14 and newer, the lock and PIN screens and those confirmations are marked as sensitive, so apps that misuse accessibility access can't read them. Real accessibility tools such as TalkBack keep working.
- **Stricter intents from other apps.** On Android 16 and newer, other apps can only open RecorderCtrl's screens with an intent that really matches them (opening the app, and "Record audio"). Nothing changes for you: the launcher and apps that ask for a recording work as before.

### Project

- **Checked dependencies:** every library and build plugin download is now checked against sha256 checksums in `gradle/verification-metadata.xml`, and the build fails on any mismatch.
- **Verifiable releases:** this is the first release with a signed build provenance attestation and the APK's SHA-256 in the release notes. The README explains how to check a download with `gh attestation verify`.

## Project updates (2026-11-05, workflows)

No new app version: these changes are to the repo's build workflows only.

### Project

- **Signed build provenance:** every release from now on gets a GitHub build attestation, signed through Sigstore, which proves the APK was built by this repo's workflow from the tagged commit. You can check it with `gh attestation verify`.
- **Checksum in the release notes:** the release notes end with the APK's SHA-256, so you can check a download before installing it.
- **Least privilege:** the workflows get no permissions by default; each job gets only what it needs, and only the release job can write to the repo.
- **Workflow security check:** a new zizmor check runs on every change to the workflows, offline, with zizmor pinned by its checksum.
- **Code scanning:** CodeQL analyses the app's code every week and on every app change; results go to the repo's Security tab.
- **Dependency checksums:** a new manually started workflow writes `gradle/verification-metadata.xml`, the list of sha256 checksums every library and build plugin download is checked against.

## v0.1.1 (2026-11-05)

### Fixed

- **A running recording is no longer thrown away when another app asks for a recording.** If you were already recording in RecorderCtrl and another app (a messenger, a notes app) opened RecorderCtrl to record something for it, tapping the close button or Back on that screen discarded your running recording. Now that screen only discards a recording it started itself: the one you started in RecorderCtrl keeps recording, with Pause and Stop still in its notification. If you stop and save that recording from the other app's screen, it's saved in RecorderCtrl as usual but isn't handed to the other app.

## Project updates (2026-11-05)

No new app version: these changes are to the repo, its build checks and its documentation only.

### Project

- **README:** the permissions table also lists the plain foreground-service permission and the app-internal permission AndroidX adds (it only lets RecorderCtrl talk to itself and isn't something you grant), and the Ctrl series list now includes RecorderCtrl itself.
- **Build:** every build now also checks that the Gradle wrapper in the repo hasn't been tampered with, before anything is compiled.
- **Repo:** `.gitignore` also keeps `google-services.json` out of the repo, next to the signing files it already covered.

## v0.1.0 (2026-11-05)

The first release of RecorderCtrl: a private, offline voice recorder in the Ctrl series, made to replace your phone's recorder app on any Android 13+ phone.

### New

- **Record** with a large timer and a live level meter, Pause and Resume, Stop and save, and Discard (asks first).
- **Keeps recording** with the screen off or in another app, with a notification that has Pause, Resume and Stop and save.
- **Quality:** Standard (AAC, about 45 MB an hour), High (AAC, about 85 MB an hour) or Small (Opus, about 30 MB an hour), mono or stereo.
- **Microphone modes:** Standard, Voice (less background noise and echo) and Unprocessed (for music, where the phone supports it).
- **Recordings tab** with name, date, length and size, search when there are more than a few, and a player with seeking and speeds from 0.75× to 2×.
- **Share** a recording, **save a copy** to Recordings/RecorderCtrl, **rename** or **delete** it.
- **Private storage:** recordings stay inside RecorderCtrl until you share them or save a copy.
- **Recovery:** a recording cut off by a crash or the phone switching off is kept when it can still be played.
- **Works with other apps:** apps that ask for a voice recording can get one from RecorderCtrl.
- **Settings:** quality, stereo, microphone mode and keep the screen on while recording.
- **App lock** with its own PIN (at least 6 digits) and optional fingerprint unlock, **Hide in screenshots** and **Hide in recent apps**, both off by default.
- **Ctrl / Signal look** in dark and light, following your phone's theme, with layouts for phones, tablets and foldables.
