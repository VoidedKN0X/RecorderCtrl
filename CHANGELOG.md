# Changelog

What changed in each RecorderCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/RecorderCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

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
