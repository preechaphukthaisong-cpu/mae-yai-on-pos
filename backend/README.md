# Maeyai-On-POS Sync Backend

This is the executable backend implementation for the multi-device scope. It is an event-log service and encrypted backup coordinator. It is not yet deployed; Android integration remains gated pending cross-device key enrollment, complete domain replay and qualification.

## Architecture and guarantees

- Google ID tokens identify the store owner. A store is isolated by the immutable Google subject (sub), not by a mutable email string. Per-store AES-256 content keys are distributed only as RSA-OAEP device wrappers or recovery-key wrappers; the backend cannot unwrap them.
- Each Android installation registers a UUID device ID and an RSA-3072 public wrapping key. Private keys remain non-exportable in Android Keystore. Revoked devices cannot push or pull.
- PostgreSQL assigns one monotonically increasing sequence per store. Push operations lock the store row so concurrent devices receive a single durable order.
- Every event has a client-generated UUID idempotency key, entity version, canonical JSON SHA-256, and an explicit upsert/delete operation. The payload must be an opaque AES-256-GCM envelope (`encryptionVersion`, `keyEpoch`, `algorithm`, `nonce`, `ciphertext`, `aadVersion`); plaintext domain JSON is rejected at the API boundary. Replays with identical content are acknowledged without duplicate writes; reuse of an event ID with different content is rejected.
- Optimistic entity versions reject stale writes into a durable conflict queue. The server never silently overwrites a newer entity version. The client must fetch current events, resolve the business-level conflict, then submit a new event based on the latest version.
- Delete operations are retained as events (tombstones); there is no device-originated hard-delete endpoint.
- Full backup uploads are opaque encrypted bytes. The server verifies the checksum and authoritative sequence, validates an optional versioned baseline manifest, stores the snapshot, then asynchronously mirrors it to the Google Drive appDataFolder.
- Drive refresh tokens are encrypted at rest with AES-256-GCM. The Drive account is verified against the store owner's Google subject. The backend never receives the backup plaintext or an encryption recovery key.
- Full-backup rotation defaults to three snapshots. Rotation runs only after a new Drive upload succeeds. The event log is not deleted by full-backup rotation.

## Run locally

Requires Node.js 22+, PostgreSQL 17+, and a Google OAuth client configured for the same Android/Google identity audience.

1. Copy .env.example to a private deployment environment; do not commit it.
2. Set DATABASE_URL, GOOGLE_OAUTH_CLIENT_ID, and a random 32-byte base64 TOKEN_ENCRYPTION_KEY_BASE64.
3. To enable Drive mirroring, also set GOOGLE_DRIVE_OAUTH_CLIENT_SECRET. The Android Drive authorization must request offline access and the drive.appdata scope.
4. Run npm install, then npm test, then npm run migrate, then npm start.
5. Expose the service only behind HTTPS. Restrict database access to the backend. Back up PostgreSQL independently.

For container deployment, configure the variables and run docker compose up --build -d from this directory.

## API

All authenticated requests use Authorization: Bearer <Google ID token>. Device-scoped requests also use X-Device-ID: <registered UUID>.

- POST /v1/stores — create or retrieve the one store associated with the Google account.
- GET /v1/stores/me — read store identity and authoritative sequence.
- POST /v1/devices — register a device with { "deviceId": UUID, "label": "...", "publicKey": "base64url SPKI RSA-3072" }.
- GET /v1/devices — list active devices and their public-key fingerprints for explicit approval.
- GET /v1/key-epochs/current — read the current store key epoch.
- POST /v1/key-epochs — atomically publish a new epoch with one RSA-OAEP wrapper for every active device and an opaque recovery wrapper.
- POST /v1/key-wrappers — an already trusted device explicitly approves and wraps any historical epoch it already holds for a newly registered device.
- GET /v1/key-wrappers — retrieve every historical epoch wrapped specifically for this device.
- GET /v1/key-wrappers/current — retrieve this device's current epoch wrapper.
- GET /v1/key-wrappers/recovery?epoch=N — retrieve the opaque recovery wrapper.
- POST /v1/devices/{deviceId}/revoke — rotate the store key and revoke a device in one transaction. DELETE is rejected after key enrollment unless the key-rotation path is used.
- DELETE /v1/devices/{deviceId} — revoke another device.
- POST /v1/sync/push — submit 1–100 events. Payloads must be AES-256-GCM envelopes; SHA-256 is calculated over canonical encrypted-envelope JSON.
- GET /v1/sync/pull?after=<sequence>&limit=200 — fetch ordered events.
- GET /v1/conflicts — list unresolved optimistic-concurrency conflicts.
- POST /v1/drive/connect — exchange a one-time Google server authorization code and encrypt the returned refresh token.
- POST /v1/backups — upload a device-encrypted full snapshot as raw application/octet-stream; provide X-Through-Seq and X-Backup-SHA256. Baselines also provide X-Baseline-Manifest as base64url JSON with store ID, sequence, Room schema version, plaintext snapshot digest and encrypted-file digest.
- GET /v1/backups — list available encrypted snapshots.
- GET /v1/backups/{snapshotId} — download an encrypted snapshot for cross-device restore.

## Deployment is not activation

The backend requires a real PostgreSQL instance, HTTPS hosting, OAuth client configuration, and secret provisioning. This repository change does not create cloud infrastructure, does not contain credentials, and does not activate multi-device sync in the Android UI. The Android client has durable local outbox/inbox processing, encrypted transport, typed product/inventory, sale, return, cancellation, debt, customer and allow-listed store-setting replay, per-device historical key enrollment/recovery, baseline export/restore orchestration, serialized WorkManager execution and durable conflict review. The conflict UI supports inspecting inbound replay quarantine and explicitly retrying the same blocked event after the operator manually repairs local data; it never skips a server sequence or auto-merges financial records. Outbound optimistic-concurrency conflicts remain quarantined for manual investigation. Live sync remains disabled.

## Security boundary before production

The backend stores sync events only as opaque AES-256-GCM envelopes, device key wrappers as RSA-OAEP ciphertext, and recovery wrappers as AES-GCM ciphertext derived from a high-entropy recovery key. It cannot decrypt store content. Android key enrollment and recovery orchestration are implemented in the feature branch but still require cross-device qualification and user-facing confirmation flows. Production must use HTTPS, a private database network, managed PostgreSQL disk encryption, strict database access, and encrypted database backups. The token-encryption master key is supplied through deployment configuration; a managed KMS/HSM integration and key rotation workflow are not yet implemented. Do not deploy with public database access or treat this draft as a completed security review.
