# Changelog

What changed in each RecorderCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/RecorderCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

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
