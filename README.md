# Mae Yai On POS

A retail Point of Sale project organized into frontend, backend, and shared data layers.

## Architecture
- frontend/: cashier and owner application modules.
- backend/: business services, API and integration boundaries.
- database/: SQLite baseline schema, ordered migrations and data rules.
- api/: API contract conventions and OpenAPI specification.
- database/seeds/: idempotent built-in role/permission catalogue.
- docs/architecture/: system architecture and offline-sync contracts.
- tests/: database integrity and acceptance checklist.

## Current foundation
- SQLite local-first database schema and migrations.
- Product/catalog, sales, payments, stock ledger, purchasing, suppliers, expenses and cash sessions.
- Staff, roles, permissions, authentication-session metadata and attendance.
- Product barcodes, images, variants/modifiers, promotions, coupons and loyalty.
- Payment gateway transaction/webhook records, print jobs and receipt delivery jobs.
- Offline sync inbox/outbox, cursor state and idempotency records.
- Audit history, architecture documentation and validation checklist.

## Important status
This repository currently contains framework-neutral architecture and database foundations. It does not yet contain runnable frontend/backend application code. The app framework and cloud hosting target must be selected before generating platform-specific source code or payment-provider integrations.

## Database setup order
1. Execute database/schema.sql as the baseline (version 1).
2. Record migration version 1 only after the baseline schema succeeds.
3. Apply database/migrations/002_pos_operations.sql as version 2.
4. Apply database/migrations/003_platform_foundation.sql as version 3.
5. Record each migration version in schema_migrations in the same transaction as its successful application.

Do not apply a migration twice manually. Use a migration runner that checks schema_migrations and wraps each migration in a transaction.

## Build policy
No build workflow or automatic build command is part of this foundation.
