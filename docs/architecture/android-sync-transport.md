# Android sync transport foundation

This branch adds a strict HTTPS transport boundary only. It does not enable synchronization in the POS runtime.

## Current components

- `DeviceIdentityStore` persists a stable installation UUID in app-private preferences. A reinstall or cleared app data is a new installation.
- `SyncAuthTokenProvider` is the narrow boundary for obtaining a Google ID token. A Google access token or one-time server authorization code is not accepted by this contract.
- `SyncApiClient` implements the existing v1 backend routes for store lookup/creation, device registration, event push/pull, and opaque encrypted snapshot upload/download.
- Requests have bounded timeouts and response sizes, do not follow redirects, disable caching, and require HTTPS.

## Not implemented yet — do not activate

1. There is no production base URL or Google ID-token provider configured.
2. The current Room `backup_journal` row contains an opaque `op` and `payload`; it does not provide a validated entity type, entity ID, or base entity version matching the server event contract. It cannot safely be translated into a server event by guessing.
3. Push acknowledgements are not yet persisted as a durable per-event server cursor/ack state.
4. Pull cursors are not persisted, and remote events are not validated and applied transactionally into Room.
5. Financial entities require immutable, domain-specific event application and reversal semantics. Generic JSON-to-SQL writes are prohibited.
6. Conflict review/resolution UI, durable WorkManager orchestration, Google Drive server authorization-code flow, and full snapshot restore integration remain outstanding.
7. No deployed HTTPS backend or physical multi-device qualification exists.

## Required next implementation slices

- Define a versioned domain event envelope for each supported aggregate and record it in the same Room transaction as the business write.
- Add Room schema migration for durable outbox, inbox cursor, server entity versions, and conflict records without dropping or rebuilding existing tables.
- Implement idempotent upload/ack and ordered pull/apply using one transaction per page, with crash-recovery tests.
- Implement explicit conflict handling; never silently overwrite sales, payments, stock movements, returns, or cancellations.
- Wire Google sign-in ID-token refresh, device registration/revocation, and unique WorkManager jobs.
- Deploy the backend behind HTTPS and run two-or-more-device offline/reconnect/restore tests.

Until all slices pass, keep the feature disabled and the existing offline POS behavior unchanged.
