# Database Write-Path and Maintenance Serialization Audit

**Baseline audited:** `main` at `cb7111926fb28d80d164dffadaad706dc32fefec`  
**Audit branch:** `phase-3-release-gates`  
**Scope:** Production Java source under `app/src/main/java`; this is a source audit, not physical-device qualification.

## Inventory

| Path | Mutation / maintenance | Boundary | Finding |
|---|---|---|---|
| `core/barcode/ProductRegistrationService` | Creates product, barcode registry/counter, op log, backup journal | Injected `TransactionRunner`; operation is atomic | Compliant when constructed with `RoomTransactionRunner`. Verify production dependency wiring before acceptance. |
| `core/barcode/BarcodeAllocator` | Manufacturer barcode registration and system counter/registry allocation | Public methods use injected `TransactionRunner`; `*InTransaction` methods are package-private | Compliant when public entry points are used; internal methods are intentionally transaction-scoped. |
| `core/backup/GoogleDriveBackupUploadWorker` | Marks journal rows uploaded after Drive confirms/reconciles remote file | Baseline used direct `RoomDatabase.runInTransaction` under a manually acquired `DatabaseMaintenanceGate` | **Violation of the single approved write API.** Fixed on this branch to use `RoomTransactionRunner`; its shared gate is reentrant, so acknowledgement remains serialized. |
| `core/backup/RoomDatabaseSnapshotter` | WAL checkpoint and SQLite file snapshot | `DatabaseMaintenanceGate.withExclusiveAccess` | Uses the shared maintenance gate. Checkpoint refuses busy/incomplete results and validates integrity, schema version, and foreign keys. |
| `core/backup/BackupRestoreCoordinator` | Safety snapshot, staging validation, activation/rollback orchestration | Holds `DatabaseMaintenanceGate` across restore | Uses the shared gate for the complete maintenance operation. |
| `core/backup/RoomDatabaseRestorer` | Closes Room singleton, replaces database file, restores rollback on failure | Holds shared gate, then synchronizes on `AppDatabase.class` | Uses shared gate. Lock order is gate → AppDatabase.class in restore code; no reverse acquisition was found in the reviewed production paths. |
| `core/backup/DeltaBackupCoordinator` | Reads pending journal batch and packages encrypted delta | Read-only DAO query; no acknowledgement/mutation | Read does not mutate the DB. A concurrent upload acknowledgement may cause redundant packaging; server reconciliation and idempotent acknowledgement must remain safe. |
| `core/db/DatabaseGuards` | SQLite triggers enforce invariants | Database-level trigger execution | Guards are not an application write API; they validate writes performed within the caller's transaction. |

## Approved application write contract

1. Every application mutation must be exposed through a domain/service method and execute through `RoomTransactionRunner` (or an explicitly approved `TransactionRunner` implementation in tests).
2. DAOs remain persistence primitives. A feature or worker must not call DAO mutation methods or `RoomDatabase.runInTransaction` directly as its application write boundary.
3. Snapshot, checkpoint, restore, and database-file replacement must acquire `DatabaseMaintenanceGate`. Lock ordering is gate first, then `AppDatabase.class` when singleton replacement is required.
4. Multi-row business operations must remain atomic, including operation log and backup journal evidence.
5. New production DAO mutation call sites require updating this inventory and adding a concurrency/failure test.

## Findings and disposition

- [x] Identified one production write path bypassing `RoomTransactionRunner`: Drive upload journal acknowledgement.
- [x] Changed that path to use `RoomTransactionRunner` on branch `phase-3-release-gates`.
- [ ] Run unit and emulator instrumentation CI against the branch.
- [ ] Verify production dependency wiring always injects `RoomTransactionRunner` into product/barcode services.
- [ ] Add/verify a deterministic concurrency test proving a writer cannot overlap snapshot/maintenance access.
- [ ] Re-run source audit after implementation changes and record no remaining bypasses.

## Acceptance status

**G5 remains IN_PROGRESS.** The identified bypass is fixed in source, but CI, wiring verification, and deterministic concurrency evidence are still required. This document does not certify physical-device behavior.
