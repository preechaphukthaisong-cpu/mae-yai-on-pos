# Code reset record — 17 November 2026

## Decision

The owner selected: **remove all executable application code while retaining documentation and Git history**.

## Safety and history preservation

- Original main-branch head before reset: `93aafea7f03e9b548965a94ef07be21c0874a537`.
- A preservation branch was created before modifying `main`: `archive/pre-code-reset-2026-11-17`.
- The reset is recorded as a new commit on `main`; no previous commits were rewritten or deleted.
- This operation does not delete repository issues, pull requests, releases, tags, or other Git references.

## Removed from the current main branch

- `.github/workflows/android.yml`
- `app/build.gradle`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/th/co/maeyai/onpos/DatabaseBackup.java`
- `app/src/main/java/th/co/maeyai/onpos/MainActivity.java`
- `app/src/main/java/th/co/maeyai/onpos/PosDatabase.java`
- `build.gradle`
- `gradle.properties`
- `settings.gradle`
- `database/schema.sql`
- `database/migrations/002_pos_operations.sql`
- `database/migrations/003_platform_foundation.sql`
- `database/seeds/001_default_roles.sql`

## Retained

- Root README and all architecture/product/security documentation in `docs/`.
- API scope README and OpenAPI contract as historical planning material.
- Database design rules and seed-data policy READMEs.
- Frontend and backend scope READMEs.
- Database validation checklist.
- Complete Git history, including the previous implementation on the archive branch.

## Resulting state

The current `main` branch is a documentation/design baseline, not a buildable Android project. GitHub Actions Android build was removed because its Gradle project and application source no longer exist. A new Android project and CI workflow must be created during the rebuild.

## Rebuild constraints

Use the retained requirements as design inputs, not as proof of implementation. Re-audit any historical source or SQL before reuse. Build modules separately, add automated tests, and verify financial transactions, inventory integrity, backup/restore, and real-device behavior before production use.
