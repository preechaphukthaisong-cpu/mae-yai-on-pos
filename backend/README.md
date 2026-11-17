# Backend scope

No remote backend is part of the approved version 1. The application runs on one Android device and uses its app-private SQLite database as the sole source of truth.

This folder is retained as a future integration boundary only. Do not add a server, API runtime, cloud persistence, payment-provider credentials or synchronization behavior to phases 0–8.

All current business rules must be enforced in Android domain services and SQLite transactions, not in an absent remote backend.
