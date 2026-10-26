<p align="center">
  <img src="branding/png/lockup-light-2000.png" alt="Bulwark" width="420">
</p>

# Bulwark: Encrypted Storage

Bulwark is an Android vault for any file: documents, photos, videos and notes, encrypted on your
phone. It has **no internet permission**, no accounts, no ads, no tracking and no subscriptions:
one purchase, and nobody (including us) can open your files.

> **Status: foundation build (0.1).** The encryption core and project are in place; the app does
> not store files yet. Do not use pre-release builds for real data.

## What's in this repository

| Path | What it is |
| --- | --- |
| `core-crypto/` | The encryption core: keyslots, master key, streaming file format, recovery words, locked capture. Pure Kotlin over libsodium, fully unit-tested on a computer. |
| `app/` | The Android app (Kotlin, Jetpack Compose). Currently a foundation screen that runs an on-device encryption self-test. |
| `docs/file-format.md` | The open specification of every format Bulwark writes. |
| `branding/` | Logo, icons, brand fonts and the scripts that generate them. |
| `scripts/check_apk.py` | The security gate every APK must pass: permission allowlist, no exported components, no backups. |

## Security principles

- **Offline by construction.** No `INTERNET` permission; the build fails if one ever appears.
- **Zero knowledge.** One random master key encrypts everything; each unlock method is a separate
  keyslot around it. We never hold a key, password or file.
- **Everything encrypted, including what files are.** Names, folders, sizes (padded), dates and
  thumbnails live only inside ciphertext.
- **Recovery is yours.** 24-word recovery key, printable sheet, split shares or hardware security
  key, each optional and offline.
- **Verifiable.** Open source (GPL-3.0), open file format, pinned and checksum-verified
  dependencies, and an independent audit before 1.0.

See [SECURITY.md](SECURITY.md) for how to report a vulnerability.

## Building

Requirements: JDK 21, Android SDK with platform 37.0 and build-tools 36.1.0.

```sh
./gradlew :core-crypto:test            # crypto tests (run on your computer)
./gradlew :app:assembleDebug           # debug APK in app/build/outputs/apk/debug/
python3 scripts/check_apk.py app/build/outputs/apk/debug/app-debug.apk
```

Every push and pull request is built by GitHub Actions, which uploads the debug APK as an artifact.

Dependencies are verified against `gradle/verification-metadata.xml`. After an intentional
dependency change, regenerate it with
`./gradlew --write-verification-metadata sha256 :core-crypto:test :app:lintDebug :app:assembleDebug :app:assembleRelease`
and review the diff.

## Licence

Bulwark is free software under the [GNU General Public License v3.0](LICENSE). Third-party
components and their licences are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
