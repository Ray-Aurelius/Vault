# Security policy

Bulwark exists to protect people's private files, so we treat every report seriously.

## Reporting a vulnerability

Please **do not open a public issue** for a security problem. Instead use GitHub's private
vulnerability reporting for this repository (Security tab → "Report a vulnerability").

Include what you found, how to reproduce it, and the version or commit you tested. We aim to
acknowledge reports within 3 working days and to agree a disclosure date with you.

## Scope

In scope: anything that lets someone read, alter, detect or delete vault contents without the
user's credentials; leaks of plaintext, keys or metadata (to storage, logs, the clipboard,
screenshots, other apps or backups); weaknesses in the file format or key handling; build or
supply-chain issues.

Out of scope, by design (see the threat model): an attacker with root or kernel control of an
unlocked, running phone; someone watching the screen while the vault is open; files the user
exports or opens in another app.

## Status

Bulwark is pre-release and **has not been audited yet**. An independent audit of the crypto core
and Android layer is required before 1.0; its report will be published here.
