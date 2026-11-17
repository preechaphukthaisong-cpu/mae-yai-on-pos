# Offline and synchronization scope

## Version 1: one Android device only

The approved first release is an offline-first Android application on one shop device. SQLite on that device is the only source of truth. No server, cloud database, account login, device identity, sync outbox/inbox, cursor, remote conflict resolution, Wi-Fi/LAN replication, or background upload is part of version 1.

The existing sync tables and this document are retained as historical/future design material only. They must not be called by the Android runtime and must not block sales when there is no network.

## Future multi-device phase

A future, separately approved multi-device release may define event delivery, server-side idempotency, durable acknowledgements, conflict resolution and device revocation. Financial events must remain immutable and be corrected through reversal entries. This future design must not be implemented in the single-device phases 0–8.
