# ร้านแม่ใหญ่อ่อน POS

Android native application written in Java. Single-device, offline-first POS.

## Current status
- Phase 2 design and business decisions O-001 through O-030 were approved and merged to `main` in PR #2 on 17 November 2026.
- Phase 3 Android foundation was delivered and merged in PR #3 on 19 November 2026 (D-065).
- This is a foundation build, not a finished shop-ready POS. The launcher remains a foundation/status screen.
- Production use is blocked until Google Drive round-trip, delta replay, daily backup credential lifecycle, ledger archive policy and physical-device qualification pass.

## Project rules
- One Room/SQLite database is the single source of truth for all screens.
- All financial values use integer satang (`long`); never use floating point for money.
- Product quantities use integer milli-units.
- Business writes go through use cases and atomic transactions.
- Financial and inventory history is auditable; no destructive migrations.
- Google Drive is backup storage only, never a live database or sync backend.
- v1 has no in-app printer implementation.
- Plaintext database or backup payloads must never be uploaded to Google Drive.
- **Mandatory update compatibility:** every release must update over the installed version without uninstalling or clearing app data; keep applicationId and the approved release signing certificate unchanged, strictly increase versionCode, and preserve all POS data through tested Room migrations. See `docs/UPDATE_COMPATIBILITY_POLICY.md`.

## Documents
- `docs/SPEC.md` — approved requirements and phase plan.
- `docs/DECISIONS.md` — approved business and technical decisions.
- `docs/ARCHITECTURE.md` — dependency boundaries and runtime architecture.
- `docs/SCHEMA.md` — authoritative database design.
- `docs/MENU_LOCK.json` — locked navigation contract.
- `docs/phase-3-progress.md` — implementation progress and security gates.
- `docs/UPDATE_COMPATIBILITY_POLICY.md` — mandatory rules for signed in-place updates, versioning, and data preservation.

## CI
Pull requests targeting `main` run unit tests, Android emulator instrumentation tests and a debug APK build. The emulator test script is a single command because android-emulator-runner executes script lines independently. CI verifies and uploads the generated Room v4 schema JSON and debug APK as artifacts. A green CI run does not replace physical-device acceptance.
