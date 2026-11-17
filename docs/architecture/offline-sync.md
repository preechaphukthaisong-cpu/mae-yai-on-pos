> **LEGACY / NON-NORMATIVE REFERENCE** — This document predates the approved POS v1 specification. Do not implement requirements from this file when they conflict with `docs/SPEC.md` or `docs/DECISIONS.md`. The phase-2 design documents `docs/ARCHITECTURE.md`, `docs/SCHEMA.md`, and `docs/MENU_LOCK.json` supersede this reference after user approval.

# Offline and synchronization scope

## Version 1: one Android device only

The approved first release is an offline-first Android application on one shop device. SQLite on that device is the only source of truth. No server, cloud database, account login, device identity, sync outbox/inbox, cursor, remote conflict resolution, Wi-Fi/LAN replication, or background upload is part of version 1.

The existing sync tables and this document are retained as historical/future design material only. They must not be called by the Android runtime and must not block sales when there is no network.

## Future multi-device phase

A future, separately approved multi-device release may define event delivery, server-side idempotency, durable acknowledgements, conflict resolution and device revocation. Financial events must remain immutable and be corrected through reversal entries. This future design must not be implemented in the single-device phases 0–8.
