# Bulwark storage formats, version 1

Status: **draft, pre-audit**. These formats are implemented in `core-crypto` and covered by its
tests. They may still change before 1.0; from 1.0 on, every later release must keep reading them.

All integers are big-endian. All cryptography comes from libsodium.

## Primitives

| Purpose | Primitive |
| --- | --- |
| Password and PIN stretching | Argon2id (`crypto_pwhash`, ALG_ARGON2ID13), 16-byte salt, 32-byte output |
| Small secrets (key wrapping) | XChaCha20-Poly1305 IETF AEAD, random 24-byte nonce |
| File contents | `crypto_secretstream_xchacha20poly1305` |
| Subkeys | `crypto_kdf_derive_from_key` (BLAKE2b), fixed 8-byte contexts |
| Locked capture | `crypto_box_seal` (X25519 + XSalsa20-Poly1305) |
| Recovery phrase checksum | SHA-256 (BIP-39) |

Argon2id floor for real vaults: memory 64 MiB, 3 passes. Settings are calibrated per device to
about one second and stored in each keyslot. Readers reject settings outside 8 KiB–2 GiB memory
or 1–32 passes.

## Key hierarchy

```
master key (32 random bytes, never stored unwrapped)
├── subkey "BWindex1"  -> encrypts the index (names, folders, sizes, dates, file keys)
├── subkey "BWthumb1"  -> encrypts thumbnails
├── subkey "BWfkwrp1"  -> wraps each file's random 32-byte file key
└── subkey "BWseal01"  -> wraps the locked-capture secret key
```

Each unlock method is a keyslot holding the master key wrapped under its own key-encryption key
(KEK):

| Slot type | Code | KEK |
| --- | --- | --- |
| Password | 1 | Argon2id(NFC-normalized UTF-8 password, salt, settings) |
| PIN | 2 | same as password |
| Recovery key | 3 | kdf(recovery entropy, id 1, "BWrecov1") |
| Biometric | 4 | kdf(secret released by the Android Keystore, id 1, "BWbiom01") |
| Hardware key | 5 | kdf(FIDO2 hmac-secret output, id 1, "BWhwkey1") |
| Split recovery | 6 | kdf(secret rebuilt from SLIP-39 shares, id 1, "BWsplit1") |

## Keyslot

```
"BWKS" | version u8 = 1 | type u8 | slotId[16] | hasKdf u8
[ salt[16] | opsLimit u32 | memLimitKiB u32 ]        only for password and PIN slots
wrappedLength u16 | wrapped[wrappedLength]          wrapped = nonce[24] || AEAD(master key)
```

Everything before `wrappedLength` is the AEAD associated data, so a changed type, ID or cost
setting makes the slot fail to open. A wrong credential and a tampered slot are indistinguishable.

## Keyring

```
"BWKR" | version u8 = 1 | count u8 (<= 32) | ( length u16 | keyslot[length] ) * count
```

On the phone the keyring file is additionally sealed by a StrongBox/TEE Keystore key that requires
an unlocked screen (the device layer, implemented in the app). Backups carry it without that layer.

## Encrypted file

```
Header, 52 bytes:
  "BWFE" | version u8 = 1 | suite u8 = 1 | reserved u16 = 0 | fileId[16]
  chunkSize u32 (default 65536; 4 KiB–4 MiB) | secretstreamHeader[24]
Body:
  chunk*  each = secretstream_push(plaintext[chunkSize]), i.e. chunkSize + 17 bytes;
          the last chunk may be shorter and is the only one tagged FINAL.
          Associated data for every chunk = the 52-byte header.
Plaintext stream carried by the chunks:
  length u64 | data[length] | zeros up to Padmé(8 + length)
```

Readers must:

1. check the header's `fileId` equals the record that points at the file (stops swaps);
2. authenticate each chunk before releasing any of its bytes;
3. reject a stream that ends without a FINAL chunk (truncation) or has bytes after it (extension);
4. reject non-zero padding, or a total that is not exactly Padmé(8 + length).

Padmé ([Nikitin et al., PETS 2019](https://bford.info/pub/sec/purb.pdf)) limits what stored sizes
reveal to roughly the log-log of the true size, at no more than about 12% overhead.

File keys are wrapped as `AEAD(subkey "BWfkwrp1", fileKey, ad = "bulwark/file-key/v1" || fileId)`
and stored only in the encrypted index.

## Recovery phrase

32 random bytes encoded as 24 words of the unmodified BIP-39 English list (SHA-256
`2f5eed53…b24dbda`), the last 8 bits being the first byte of SHA-256(entropy). Readers accept any
case, numbering, and each word's first four letters.

## Locked capture

`"BWSK" | version u8 = 1 | publicKey[32] | length u16 | wrappedSecretKey`; the secret key is
`AEAD(subkey "BWseal01", sk, ad = "bulwark/seal-key/v1" || publicKey)`. New files' keys are sealed
to the public key while the vault is locked, and unsealed after unlock.
