> **LEGACY / NON-NORMATIVE REFERENCE** — This document predates the approved POS v1 specification. Do not implement requirements from this file when they conflict with `docs/SPEC.md` or `docs/DECISIONS.md`. The phase-2 design documents `docs/ARCHITECTURE.md`, `docs/SCHEMA.md`, and `docs/MENU_LOCK.json` supersede this reference after user approval.

# Security and operational safeguards

## Authentication
- Store password/PIN hashes only; use a modern password hashing algorithm with per-user salts and an appropriate work factor.
- Never store raw credentials or refresh tokens. Store token hashes and revoke sessions on logout, staff termination or suspected compromise.
- Apply login throttling and temporary lockout; avoid revealing whether a staff code exists.
- Enforce role/permission checks in backend/domain services, not only by hiding UI controls.

## Payments
- Never store PAN, CVV, bank login credentials or provider secrets.
- Keep gateway secrets on the trusted backend.
- Verify callback signatures and provider transaction status.
- Deduplicate webhooks by provider and provider event ID.
- A QR display, client callback or screenshot is not proof of settlement.
- Use idempotency keys for payment creation and refunds.

## Local data and privacy
- Keep SQLite in application-private storage.
- Use platform-backed encryption/key storage where the threat model requires it.
- Minimize personal data in logs and sync payloads.
- Apply least privilege to staff and device access.
- Provide retention/export policies appropriate to applicable privacy and tax requirements.

## Recovery
- Back up the database consistently while WAL is enabled; do not copy only the main database file.
- Test restore, integrity_check and foreign_key_check regularly.
- Keep unsynced outbox records until a durable server acknowledgement is received.
- Monitor failed sync, webhook, receipt and print jobs.
