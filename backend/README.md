# Maeyai-On-POS Sync Backend

This is the first executable backend implementation for the multi-device scope. It is an event-log service and encrypted backup coordinator. It is not yet deployed and the Android application is not yet wired to these endpoints.

## Architecture and guarantees

- Google ID tokens identify the store owner. A store is isolated by the immutable Google subject (sub), not by a mutable email string.
- Each Android installation registers a UUID device ID. Revoked devices cannot push or pull.
- PostgreSQL assigns one monotonically increasing sequence per store. Push operations lock the store row so concurrent devices receive a single durable order.
- Every event has a client-generated UUID idempotency key, entity version, canonical JSON SHA-256, and an explicit upsert/delete operation. Replays with identical content are acknowledged without duplicate writes; reuse of an event ID with different content is rejected.
- Optimistic entity versions reject stale writes into a durable conflict queue. The server never silently overwrites a newer entity version. The client must fetch current events, resolve the business-level conflict, then submit a new event based on the latest version.
- Delete operations are retained as events (tombstones); there is no device-originated hard-delete endpoint.
- Full backup uploads are opaque encrypted bytes. The server verifies the checksum and sequence, stores the snapshot, then asynchronously mirrors it to the Google Drive appDataFolder.
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
- POST /v1/devices — register a device with { "deviceId": UUID, "label": "..." }.
- DELETE /v1/devices/{deviceId} — revoke another device.
- POST /v1/sync/push — submit 1–100 events. Event payload SHA-256 is calculated over canonical JSON.
- GET /v1/sync/pull?after=<sequence>&limit=200 — fetch ordered events.
- GET /v1/conflicts — list unresolved optimistic-concurrency conflicts.
- POST /v1/drive/connect — exchange a one-time Google server authorization code and encrypt the returned refresh token.
- POST /v1/backups — upload a device-encrypted full snapshot as raw application/octet-stream; provide X-Through-Seq and X-Backup-SHA256.
- GET /v1/backups — list available encrypted snapshots.
- GET /v1/backups/{snapshotId} — download an encrypted snapshot for cross-device restore.

## Deployment is not activation

The backend requires a real PostgreSQL instance, HTTPS hosting, OAuth client configuration, and secret provisioning. This repository change does not create cloud infrastructure, does not contain credentials, and does not activate multi-device sync in the Android UI. The Android client must still implement durable local outbox/inbox processing, event-to-Room mapping, conflict resolution UX, device registration, Drive connection, snapshot upload/download, and restore validation before this service can be used by the shop.
