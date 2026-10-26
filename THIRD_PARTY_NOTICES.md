# Third-party components

| Component | Used for | Licence |
| --- | --- | --- |
| [libsodium](https://libsodium.org) | All cryptography | ISC |
| [lazysodium-android / lazysodium-java](https://github.com/terl/lazysodium-java) 5.2.0 | Java bindings for libsodium | MPL-2.0 |
| [JNA](https://github.com/java-native-access/jna) 5.19.1 | Native calls for the bindings | Apache-2.0 (dual-licensed with LGPL-2.1) |
| AndroidX, Jetpack Compose | App framework and UI | Apache-2.0 |
| Kotlin standard library | Language runtime | Apache-2.0 |
| [Archivo](https://github.com/Omnibus-Type/Archivo) | Display and wordmark font | SIL Open Font License 1.1 (`branding/fonts/OFL-Archivo.txt`) |
| [Source Sans 3](https://github.com/adobe-fonts/source-sans) | Body and UI font | SIL Open Font License 1.1 (`branding/fonts/OFL-SourceSans3.txt`) |
| [Source Code Pro](https://github.com/adobe-fonts/source-code-pro) | Recovery words | SIL Open Font License 1.1 (`branding/fonts/OFL-SourceCodePro.txt`) |
| [BIP-39 English word list](https://github.com/bitcoin/bips/blob/master/bip-0039/english.txt) | Recovery key words | MIT (BIP-39) |

## Planned before 1.0

The libsodium binaries currently come prebuilt inside lazysodium. Before 1.0 they will be replaced
by our own reproducible build of a pinned, signature-checked libsodium release, so that every byte
of native crypto code in the APK can be traced to audited source.
