# Daily Full Backup Credential Lifecycle — Security Decision Proposal

**Status: PENDING OWNER APPROVAL — NOT IMPLEMENTED**  
**Scope:** G3, unattended daily encrypted full snapshots.

## Proposed design

1. Daily full-backup scheduling is explicitly opt-in. The UI must explain that unattended backup requires the app to access the owner's recovery credentials while the device is unattended.
2. On explicit opt-in, store the Recovery Password and Recovery Key only as an AES-256-GCM authenticated ciphertext file in app-private storage. Protect the vault encryption key with a dedicated non-exportable Android Keystore AES key. Never put credentials in SharedPreferences, WorkManager input/output, intents, logs, crash reports, analytics, or cloud metadata.
3. WorkManager receives only the selected Google account email (non-secret routing metadata). OAuth access tokens are acquired just-in-time by the worker and are not persisted by application code.
4. A unique 24-hour periodic worker runs without a network constraint, loads/decrypts the credential vault only in memory, creates the encrypted full snapshot, wipes mutable credential buffers, and queues the already-encrypted file for upload. The upload worker retains its connected-network constraint. A missing network therefore delays upload, not the local snapshot.
5. If the vault is absent, corrupted, or the Keystore key is unavailable/invalidated, the worker must fail closed, create no backup, expose a recoverable status, and require the owner to re-enter credentials. Never fall back to plaintext.
6. Account changes cancel/replace the unique schedule and require the owner to confirm the new account. Disabling unattended backup cancels periodic work and deletes the local credential vault; already uploaded encrypted backups remain subject to the owner's retention policy.
7. The owner must keep an independent offline copy of the Recovery Password and Recovery Key. The Keystore-protected vault is device-bound and is not a substitute for portable recovery credentials. Reinstall, device loss, or Keystore invalidation may require re-entry.

## Security properties and trade-offs

- AES-GCM protects the stored credential file against offline disclosure and tampering; the Keystore key is non-exportable.
- Unattended operation necessarily permits the app process to decrypt credentials without interactive biometric approval. This increases exposure if an attacker can execute code inside the app or compromises the unlocked device.
- No credential is sent to WorkManager or included in a backup payload. The backup envelope continues to use the existing independent Recovery Password and Recovery Key wrappers.
- The implementation must use a separate Keystore alias and authenticated metadata/AAD for the credential vault; it must not reuse the backup DEK alias.
- Credential arrays and decrypted byte buffers must be wiped on every exit path. The existing recovery-key APIs currently accept immutable Java String values; a security review must account for this limitation or change those APIs before claiming complete in-memory zeroization.

## Required owner decision

Approve or reject all of the following:
- [ ] Permit unattended backup to use an encrypted, device-bound credential vault.
- [ ] Accept that no biometric prompt can be required for each scheduled backup.
- [ ] Require the owner to keep the Recovery Password and Recovery Key separately offline.
- [ ] Accept that reinstall/device loss/Keystore invalidation requires re-entering credentials.

Until approval, G3 remains NOT_STARTED and no background credential persistence is implemented.
