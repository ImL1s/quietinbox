# Security policy

## Reporting a vulnerability

Please do **not** open a public issue for security problems. Until the repository has a public
home, report privately to the maintainer listed in `CONTRIBUTING.md`; once published, use the
repository's private vulnerability reporting. Include reproduction steps and the QuietInbox commit.
Never include real private messages, notification dumps or recovery keys in a report.

## Threat model (summary of plan §9–§11)

In scope: an attacker with the device locked or with a backup file but without the recovery key;
malicious or malformed notification content; replay of active notifications after deletion;
tampered or truncated backups.

Out of scope: an attacker who fully controls the app process or has root; screen recording by the
platform; source apps that post misleading content; memory forensics on an unlocked device.

## Crypto choices

- Android Keystore AES-256-GCM key-encryption key (no user-auth binding in v1; see ADR-0003).
- Per-installation random database / media / recovery keys, Keystore-wrapped at rest.
- SQLCipher for Android 4.18 (community edition) for the vault.
- Tink AEAD (AES-256-GCM) for blobs; Tink Streaming AEAD (AES-256-GCM-HKDF-1MB) for backups.
- HKDF-SHA256 for the backup key (RFC 5869 vectors in tests).
- No home-grown ciphers; the only bespoke construction is the backup header + record framing, which is
  authenticated by the streaming AEAD.

## Hardening in place

`FLAG_SECURE` (default on; debug builds exempt), no plaintext image cache, bounded and allow-listed
notification input, journal-first commits, commit fence on revoke, body-free diagnostics, replay
suppression tokens.
