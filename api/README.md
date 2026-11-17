# API contract

Endpoints will be versioned under /api/v1 after the runtime framework and hosting target are selected.

Conventions:
- JSON bodies and stable machine-readable error codes.
- Bearer authentication for private endpoints.
- Permission checks for every mutation.
- Idempotency-Key for sale confirmation, payment creation, refunds and sync.
- Cursor pagination for large collections.
- UTC timestamps and integer-satang amounts.
- Never expose secrets, password hashes, stack traces or raw provider payloads.
- Verify and deduplicate payment provider callbacks.
