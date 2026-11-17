# POS system overview — approved single-device scope

## Runtime architecture

    Android screens
       |
    Feature services (sales, catalog, inventory, credit, history, settings, reports)
       |
    Shared repositories and transaction manager
       |
    One app-private SQLite database

Every feature uses the same database provider. Screens must not run independent SQL, keep separate copies of financial data, or calculate authoritative totals.

## Confirmed sale flow

1. Search or scan a product and load its current price and stock from SQLite.
2. Validate the cart and recompute amounts with integer satang arithmetic.
3. Confirm with a unique operation ID.
4. In one SQLite transaction write the sale, line snapshots, payment/debt, stock ledger and audit record.
5. Show success only after commit. On any failure roll back the whole operation.
6. Reports read committed records from the same database.

## Scope exclusions for version 1

No cloud database, remote backend, REST API runtime, payment gateway, multi-device sync, Wi-Fi replication, email/LINE receipt delivery or online account system. Cash and credit records are stored locally. A transfer recorded by an operator is not treated as independently verified provider settlement.

## Reliability

Use short transactions, foreign keys, WAL, busy timeout, synchronous durability, integer satang, UUID identifiers, parameterized SQL, immutable financial/stock evidence, verified backup/restore and migration tests. Physical-device recovery and parallel shop testing remain mandatory before live use.
