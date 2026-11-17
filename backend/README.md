# Backend foundation

Framework-specific runtime code will be added after app framework and hosting target are confirmed.

## Services
Auth/RBAC; catalog; sales; payments; inventory; purchasing; cash control; CRM/loyalty; promotions; workforce; reports; sync; notifications; printing.

## API and security rules
- Version endpoints under /api/v1.
- Authenticate private endpoints and authorize every mutation at the service boundary.
- Use TLS, parameterized SQL, strict validation and bounded payload sizes.
- Require idempotency keys for sale confirmation, payment initiation, refunds and sync writes.
- Never store raw card numbers, CVV, bank passwords, raw PINs or raw refresh tokens.
- Payment success comes only from verified provider status/callbacks, never a client UI claim.
- Keep provider secrets server-side; verify webhook signatures and deduplicate provider event IDs.
- Return stable error codes; never expose stack traces or secrets.
