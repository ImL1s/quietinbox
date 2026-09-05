# ADR-0003: Key handling and lock modes

Date: 2026-09-06 · Status: accepted

## Decision (plan §9, v1 continuous-capture mode)

- One AES-256-GCM key-encryption key in AndroidKeyStore (`dev.quietinbox.kek.v1`) with
  `setUserAuthenticationRequired(false)`, so the notification listener can write while the screen is
  locked. It only exists after the device's first unlock (credential-encrypted storage).
- Three per-installation random 32-byte secrets — database key, media key, recovery key — are stored
  only wrapped by the KEK, with the purpose bound as associated data so files cannot be swapped.
- The database key array handed to `SupportOpenHelperFactory` is **not** zeroed after opening:
  SQLCipher reuses it for every pooled WAL connection (zeroing it produced "file is not a database"
  on the second connection during device testing).
- Key failures surface as `VaultState.Locked(KeyFailure)`; the UI offers retry or an explicit reset.
  The app never deletes a vault it cannot open on its own.
- The app lock (BiometricPrompt, `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`) is a UI gate only and is
  described as such in Settings. No claim is made that decryption requires biometrics.

## Not decided here

High-security lock-vault mode (auth-bound key, capture paused while locked) and password-based
backups (Argon2id) stay P2 and need their own ADR and review.
