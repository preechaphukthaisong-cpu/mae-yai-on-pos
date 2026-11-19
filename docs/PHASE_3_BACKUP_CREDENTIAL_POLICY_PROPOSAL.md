# Daily Full Backup Credential Lifecycle — Approved Implementation Specification

**Status: OWNER APPROVED — IMPLEMENTATION IN PROGRESS**  
**Decision date:** 2026-11-19  
**Scope:** G3, opt-in unattended daily full snapshots.

## Approved policy

The owner approves unattended daily full backups subject to every control below. Approval does not mean the feature is implemented, tested, or enabled in production.

1. Scheduling is opt-in. The setup screen must explain the unattended-access trade-off and require explicit confirmation.
2. Recovery Password and Recovery Key are entered by the owner. The Recovery Key must be displayed/exported through a deliberate one-time setup flow, and the owner must confirm that an independent offline copy is stored before enabling unattended backup.
3. Store credentials only as AES-256-GCM authenticated ciphertext in app-private storage. Protect the vault key with a dedicated non-exportable Android Keystore AES key. Use a dedicated alias and versioned AAD; never reuse the backup DEK alias.
4. Never place Recovery Password, Recovery Key, DEK plaintext, or OAuth access tokens in WorkManager input/output, intents, logs, crash reports, analytics, notifications, or cloud metadata. WorkManager may receive only non-secret routing metadata such as the selected account name.
5. A unique 24-hour periodic worker creates a local full snapshot without requiring network connectivity. The snapshot/upload stages must use appropriate charging and battery-not-low constraints where supported; upload remains network-constrained and uses retry with exponential backoff. Constraints must not prevent a user-triggered backup.
6. A missing, corrupted, or invalidated vault/Keystore key fails closed. Do not create an incomplete backup or fall back to plaintext. Expose a clear recoverable status and require owner re-entry.
7. Notify the owner when no successful backup exists for more than 24 hours or repeated backup attempts fail. Do not silently report success before upload confirmation.
8. Verify the encrypted envelope and SHA-256 digest after creation and after upload/download. A successful upload must be reconciled by deterministic remote filename and confirmed Drive file metadata; an ambiguous response is retried without acknowledging the journal.
9. Provide a user-triggered restore verification flow and an automated monthly restore-integrity check against an isolated staging database. Never replace the live database during a verification-only test.
10. Keep local full-backup files for a rolling 7–14 days, but never delete the last known-good locally recoverable copy or any copy required by an unfinished upload/restore. Cloud archives containing accounting evidence follow G6 retention, not this short local cache policy.
11. Account changes cancel/replace the unique schedule only after explicit confirmation. Disabling unattended backup cancels periodic work and securely removes the local credential vault; it does not delete previously uploaded archives.
12. Wipe mutable credential and decrypted byte arrays in finally blocks. The existing Recovery Key APIs accept immutable Java String values; implementation must minimize their lifetime and document this JVM limitation. Do not claim complete zeroization of immutable strings.

## Security properties and trade-offs

- AES-GCM authenticates the vault; Android Keystore protects a non-exportable device-bound key.
- Unattended operation necessarily permits the app process to decrypt credentials without interactive biometric approval during a scheduled run.
- The vault is device-bound and is not a substitute for portable Recovery Password/Key copies.
- Reinstall, device loss, or Keystore invalidation may require credential re-entry. Without the independent Recovery Key, older portable backups may be unrecoverable.
- The local backup cache retention does not override G6 accounting-evidence retention.

## Required implementation evidence

- Opt-in/consent UI and offline Recovery Key acknowledgement.
- Keystore alias separation, AAD/version validation, authenticated encryption, corruption and invalidation tests.
- WorkManager input inspection proving no recovery secret/token is passed.
- Scheduler constraints, retry/backoff, account-change and disable tests.
- Process-death and fail-closed credential lifecycle tests.
- Backup creation, Drive confirmation, checksum verification, notification and monthly staging-restore tests.
- Local rolling retention tests proving last-known-good and pending-upload files are preserved.

**Owner decision:** APPROVED with the requirements above. G3 remains IN_PROGRESS until implementation and CI evidence pass; real-account/device qualification remains part of G1/G4.
