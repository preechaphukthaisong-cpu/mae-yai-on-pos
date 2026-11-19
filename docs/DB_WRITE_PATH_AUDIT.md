# Database Write-Path and Maintenance Serialization Audit

**Baseline audited:** `main` at `cb7111926fb28d80d164dffadaad706dc32fefec`  
**Audit branch:** `phase-3-release-gates`  
**Scope:** Production Java source under `app/src/main/java`; CI evidence is emulator instrumentation, not physical-device qualification.

## Inventory

| Path | Mutation / maintenance | Boundary | Finding |
|---|---|---|---|
| `core/barcode/ProductRegistrationService` | Creates product, barcode registry/counter, op log, backup journal | Public constructor requires `RoomTransactionRunner`; generic `TransactionRunner` constructor is package-private for unit tests | Compliant. All multi-row product creation and evidence writes execute atomically through the shared runner. |
| `core/barcode/BarcodeAllocator` | Manufacturer barcode registration and system counter/registry allocation | Public constructor requires `RoomTransactionRunner`; generic test seam is package-private | Compliant. Public mutations use the shared transaction/maintenance boundary; `*InTransaction` methods are package-private and called inside the service transaction. |
| `core/backup/GoogleDriveBackupUploadWorker` | Marks journal rows uploaded after Drive confirms/reconciles remote file | `RoomTransactionRunner` | Compliant. Removed the direct `RoomDatabase.runInTransaction` application write path. |
| `core/backup/RoomDatabaseSnapshotter` | WAL checkpoint and SQLite file snapshot | `DatabaseMaintenanceGate.withExclusiveAccess` | Uses the shared maintenance gate. Checkpoint refuses busy/incomplete results and validates integrity, schema version, and foreign keys. |
| `core/backup/BackupRestoreCoordinator` | Safety snapshot, staging validation, activation/rollback orchestration | Holds `DatabaseMaintenanceGate` across restore | Uses the shared gate for the complete maintenance operation. |
| `core/backup/RoomDatabaseRestorer` | Closes Room singleton, replaces database file, restores rollback on failure | Holds shared gate, then synchronizes on `AppDatabase.class` | Uses shared gate. Lock order is gate → AppDatabase.class; no reverse acquisition was found in reviewed production paths. |
| `core/backup/DeltaBackupCoordinator` | Reads pending journal batch and packages encrypted delta | Read-only DAO query; no acknowledgement/mutation | Read-only. A concurrent acknowledgement can produce redundant packaging but cannot mutate source rows; sequence validation and idempotent replay reject unsafe batches. |
| `core/db/DatabaseGuards` | SQLite triggers enforce invariants | Database-level trigger execution | Guards are not an application write API; they validate writes performed inside the caller's transaction. |

## Approved application write contract

1. Every production mutation is exposed through a domain/service method and executes through `RoomTransactionRunner`.
2. Generic `TransactionRunner` injection remains package-private as a deterministic unit-test seam only.
3. DAOs remain persistence primitives. Feature code and workers must not call DAO mutation methods or `RoomDatabase.runInTransaction` directly as their application write boundary.
4. Snapshot, checkpoint, restore, and database-file replacement acquire `DatabaseMaintenanceGate`. Lock ordering is gate first, then `AppDatabase.class` when singleton replacement is required.
5. Multi-row business operations remain atomic, including operation log and backup journal evidence.
6. New production DAO mutation call sites require updating this inventory and adding concurrency/failure tests.

## Findings and disposition

- [x] Identified and removed the Drive upload journal acknowledgement bypass of `RoomTransactionRunner`.
- [x] Restricted public product/barcode service construction to `RoomTransactionRunner`; generic transaction injection is package-private.
- [x] Audited production source call sites; no remaining direct application `RoomDatabase.runInTransaction` or raw DAO mutation boundary was found outside the approved runner/domain services.
- [x] Added writer-vs-snapshot instrumentation proving snapshot waits for the active transaction to release the shared gate.
- [x] CI run #335 passed the initial concurrency instrumentation test.
- [x] CI run #350 passed after constructor hardening: unit tests, APK build, Room schema verification, and database instrumentation.
- [x] Re-reviewed lock ordering and SQLite integrity/foreign-key checks in snapshot/restore paths.

## Acceptance status

<badge>G5 PASSED</badge>

Evidence: PR #4; CI runs [#335](https://github.com/preechaphukthaisong-cpu/mae-yai-on-pos/actions/runs/44446065844) and [#350](https://github.com/preechaphukthaisong-cpu/mae-yai-on-pos/actions/runs/44448431288). This is code/CI acceptance only; it does not certify physical-device behavior or authorize production use before the other release gates pass.
