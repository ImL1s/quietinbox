> 繁體中文：[docs/zh-Hant/adr/0005-backup-container.md](../zh-Hant/adr/0005-backup-container.md)

# ADR-0005: Backup container

Date: 2026-09-06 · Status: accepted

## Format

```
"QIBK" | version(1) | salt(16)              plaintext header, bound as associated data
Tink AES-256-GCM-HKDF streaming AEAD (1 MiB segments) over UTF-8 JSON lines:
  {"type":"manifest", formatVersion, schemaVersion, appVersion, createdAtEpochMs, expected counts}
  {"type":"source"|"conversation"|"message"|"revision"|"media"...}
  {"type":"end", actual counts}
```

Key: HKDF-SHA256(ikm = 256-bit recovery key, salt, info = "quietinbox-backup-v1"). The recovery key
is shown once as Crockford base32 (13 × 4 chars + 4-char checksum) and is the only cross-device
secret; Keystore keys never leave the device.

## Import rules (plan §11)

Everything is staged in memory (bounded: 16 MiB per line, 2 M records, 256 MiB media), the `end`
record and both count sets are verified, Tink authenticates every segment and the EOF, and only then
one Room transaction applies the merge (existing conversations matched by scope + identity key,
duplicate fingerprints skipped, counters recomputed). Any failure leaves the vault untouched and
removes media files written during staging.

## Deliberately not done

No compression (avoids zip-bomb handling), no password KDF (P2), no partial/streamed apply.
