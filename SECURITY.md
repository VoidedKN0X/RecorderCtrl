# Security policy

Thank you for helping keep RecorderCtrl and its users safe.

## Reporting a problem

**Please don't report security problems in a public issue.** Report them privately instead:

1. Go to the [Security tab](https://github.com/VoidedKN0X/RecorderCtrl/security) of this repo.
2. Click **Report a vulnerability** and describe what you found, how to reproduce it, and which version of RecorderCtrl you used.

Only the maintainer can see the report. I'll reply as soon as I can, work on a fix, and credit you in the changelog if you'd like.

## Supported versions

Only the latest release gets security fixes. Please update before reporting, and check the [changelog](CHANGELOG.md) to see whether it's already fixed.

## What counts

RecorderCtrl has no server and no internet permission: everything stays on your phone. So these count:

- Any way for RecorderCtrl to send recordings off the phone
- Another app being able to start a recording, or read a recording you didn't share with it
- Recordings showing while the app lock is on
- Ways around the app lock, or protected screens showing up in screenshots when they shouldn't
- Weaknesses in the app lock PIN
- Anything that lets someone run code or read files they shouldn't

## How releases are protected

Every release is built from this repo by GitHub Actions and signed with the same release key. Its SHA-256 certificate fingerprint is:

```
5C:67:DD:AA:1C:0D:00:5A:76:30:83:6A:A1:F4:29:A2:D9:B4:9D:A2:5F:3D:FC:F5:30:71:EE:DA:54:CC:B2:4F
```

The [CtrlF-Droid repo](https://voidedkn0x.github.io/CtrlF-Droid/) only accepts APKs signed with this key.
