# ร้านแม่ใหญ่อ่อน POS

Android POS แบบ Offline-first สำหรับมือถือเครื่องเดียว ใช้ SQLite ฐานข้อมูลกลางชุดเดียว

- Android source: app/
- Existing SQL foundation and historical architecture: database/, api/, backend/, docs/
- Implementation scope and risk invariants: docs/implementation-status.md
- GitHub Actions: Android CI

Build: workflow Android CI builds a debug APK and publishes it as an artifact.

The existing SQL files are retained for audit and must be reconciled through tested Android migrations before being used by the runtime database. CI build is not a substitute for real-device acceptance.
