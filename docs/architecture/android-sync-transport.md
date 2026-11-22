# Android sync transport and deployment decisions

## Deployment decisions approved by the system owner

- Reuse the existing Google Cloud project: `Maeyai-On-POS`.
- Use the HTTPS service URL provisioned by Google Cloud Run; do not purchase or configure a custom domain in the initial deployment.
- Optimize the initial deployment for low cost and small-store usage.
- Use Cloud Run for the API and Cloud SQL PostgreSQL for the shared event store when production provisioning is approved.
- Keep Google Drive as an encrypted backup mirror, not as the primary database.
- Do not create billable Cloud resources until the estimated recurring and usage-based costs are presented and explicitly approved.
- Do not place OAuth client secrets, database credentials, encryption keys, or service-account keys in source control or chat.

These are deployment decisions, not evidence that Cloud Run, Cloud SQL, OAuth, or Drive server authorization have been provisioned or tested.

## Current Android implementation

- HTTPS-only `SyncApiClient` with bounded request/response sizes, timeouts, no redirects, and no HTTP cache.
- Google ID-token provider and sign-in configuration for ID token, server authorization code, and Drive app-data scope.
- Device identity and idempotent store/device bootstrap. The database-aware bootstrap binds pre-cloud outbox rows to one store and refuses cross-store rebinding.
- Room v6 durable outbox, inbox, per-store cursor, and per-entity version tables with a non-destructive 5-to-6 migration.
- Versioned event envelopes with canonical JSON SHA-256 validation.
- Transactional outbox writes for supported product, inventory, sale, debt-payment, return, cancellation, and sale-price operations.
- Push/pull coordinators with durable acknowledgements, retry accounting, strict server response validation, conflict quarantine, and acknowledged-local-echo suppression.
- Remote replay runs under `SyncReplayContext`; the outbox writer suppresses local echo events while a domain replay handler is applying remote state.

## CI evidence

Latest commit at the time of this document: `1b0cb0f04bc69617e5cb0af4ff47d1992a0d4a8c`.

- Android unit tests and debug APK build: passed.
- Android database instrumentation tests: passed.
- Backend checks, migrations, and tests: passed.

CI success does not establish physical-device, production-cloud, or multi-device correctness.

## Production blockers — keep sync disabled

1. Typed domain replay handlers are not yet implemented and wired for every supported aggregate. Do not use generic JSON-to-SQL mapping.
2. Customer data and credit-sale dependencies are not synchronized. Customer identity data must not be sent until an end-to-end encrypted payload design is implemented and reviewed.
3. Event payloads are currently sent as JSON and stored in backend PostgreSQL JSONB. End-to-end encryption, per-store key provisioning, device key wrapping, recovery, rotation, and revocation are required before real business data is transmitted.
4. Conflict resolution workflow and user interface are incomplete. Financial and stock conflicts must never be auto-merged.
5. Unique WorkManager push/pull orchestration, network constraints, retry/backoff, and explicit opt-in/activation gates are not wired.
6. Production OAuth configuration, Cloud Run deployment, Cloud SQL provisioning, Secret Manager configuration, and Drive server authorization-code flow are not completed or verified.
7. Physical qualification is still required for multiple devices, offline/reconnect, process death, interrupted upload/download, conflict recovery, backup restore, and data preservation.

## Required implementation order

1. Implement and test typed, idempotent domain replay handlers using the same Room transaction and business invariants as local operations.
2. Design and implement end-to-end encryption before transmitting any real store event payload.
3. Implement conflict inspection and explicit resolution without overwriting financial history.
4. Add unique WorkManager orchestration with network constraints, exponential backoff, bounded retries, and a default-disabled feature gate.
5. Prepare a costed Cloud Run/Cloud SQL deployment plan. Provision only after owner approval; keep secrets in Secret Manager.
6. Complete physical multi-device and restore qualification before enabling live sync.

The existing offline POS behavior, application ID, Room data, and release-signing identity must remain unchanged. Do not merge this draft, publish a release, provision billable cloud resources, or enable live sync without the required approvals and acceptance evidence.
