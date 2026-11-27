# Changed files

## v1.3.0: accounts and cloud sync

Adds account sign-in at launch and private, per-account cloud sync on top of the
existing offline ledger. The ledger tables in the on-device database are
unchanged; the schema upgrade only adds two sync tables.

### Safety summary

- No secret or service-role key is added. The compiled-in Supabase **publishable**
  key maps to the `anon`/`authenticated` roles only, and every table is protected
  by owner-only Row Level Security.
- Existing records, integer-paisa amounts, epoch-day dates, cross-ledger links and
  the JSON backup format are unchanged. The database upgrade is additive
  (`MIGRATION_1_2`), with no destructive fallback.
- Application ID and signing identity are unchanged. No release artifact was
  produced from this branch.

### Behaviour

- Sign-in is required at launch; accounts are created by the owner in Supabase and
  self sign-up remains disabled. The session is not persisted to disk.
- Every local write queues its upload in the same Room transaction. Queued work is
  account-scoped, retries are idempotent by `client_id`, repeated edits collapse,
  and a conflicted row is retained and reported instead of being overwritten.
- Linked loan/cash pairs are joined through the atomic `link_ledger_entries` RPC,
  because a Supabase REST request cannot write a symmetric pair on its own.
- After uploading, the app pulls the account's rows and matches them by
  `client_id`; rows with unsent local changes are never replaced.

### Added

- `app/src/main/java/com/hisabkhata/data/cloud/CloudAccount.kt`
- `app/src/main/java/com/hisabkhata/data/cloud/CloudGatewayImpl.kt`
- `app/src/main/java/com/hisabkhata/data/cloud/CloudPull.kt`
- `app/src/main/java/com/hisabkhata/data/cloud/SupabaseModule.kt`
- `app/src/main/java/com/hisabkhata/data/local/DatabaseMigrations.kt`
- `app/src/main/java/com/hisabkhata/data/local/dao/SyncDao.kt`
- `app/src/main/java/com/hisabkhata/data/local/entity/SyncEntities.kt`
- `app/src/main/java/com/hisabkhata/data/sync/RoomSyncRecords.kt`
- `app/src/main/java/com/hisabkhata/data/sync/RoomSyncStore.kt`
- `app/src/main/java/com/hisabkhata/data/sync/SyncCoordinator.kt`
- `app/src/main/java/com/hisabkhata/data/sync/SyncModule.kt`
- `app/src/main/java/com/hisabkhata/domain/sync/SyncEngine.kt`
- `app/src/main/java/com/hisabkhata/domain/sync/SyncModels.kt`
- `app/src/main/java/com/hisabkhata/ui/auth/SignInScreen.kt`
- `app/src/main/java/com/hisabkhata/ui/auth/SignInViewModel.kt`

### Added (tests)

- `app/src/androidTest/java/com/hisabkhata/data/cloud/TestCloudSession.kt`
- `app/src/androidTest/java/com/hisabkhata/data/local/RoomMigrationTest.kt`
- `app/src/test/java/com/hisabkhata/domain/sync/OutboxMergeRulesTest.kt`
- `app/src/test/java/com/hisabkhata/domain/sync/SyncEngineTest.kt`

### Added (schema and tooling)

- `app/schemas/com.hisabkhata.data.local.HisabDatabase/2.json`
- `scripts/bootstrap_android_sdk.sh`
- `scripts/gradle_low_mem.sh`

### Modified

- `CHANGELOG.md`
- `README.md`
- `app/build.gradle.kts`
- `app/src/androidTest/java/com/hisabkhata/data/RoomLoanSynchronizationTest.kt`
- `app/src/androidTest/java/com/hisabkhata/ui/BengaliAmountInputUiTest.kt`
- `app/src/androidTest/java/com/hisabkhata/ui/CompactSearchUiTest.kt`
- `app/src/androidTest/java/com/hisabkhata/ui/DeleteConfirmationTest.kt`
- `app/src/androidTest/java/com/hisabkhata/ui/MobileLedgerUiTest.kt`
- `app/src/main/java/com/hisabkhata/MainActivity.kt`
- `app/src/main/java/com/hisabkhata/data/local/HisabDatabase.kt`
- `app/src/main/java/com/hisabkhata/data/local/dao/CashEntryDao.kt`
- `app/src/main/java/com/hisabkhata/data/repository/CashRepository.kt`
- `app/src/main/java/com/hisabkhata/data/repository/LoanRepository.kt`
- `app/src/main/java/com/hisabkhata/di/DatabaseModule.kt`
- `app/src/main/java/com/hisabkhata/ui/settings/SettingsScreen.kt`
- `app/src/main/res/values-bn/strings.xml`
- `app/src/main/res/values/strings.xml`
- `build.gradle.kts`

## Removed

None.
## v1.2.0: English and Bengali

Added Bengali strings and a remembered language choice, with Latin digits in both
languages. Amount input accepts Bengali digits. Bengali import headers and report
headings are supported alongside the existing English ones. Calendar, notifications,
PDF line wrapping and report contexts use the selected language. Added unit, Android
and UI coverage for localization, exact numbers and interchange.

Ledger records, database schema, JSON backup format, application ID and signing
identity are unchanged. Google Drive remains opt-in and its live OAuth flow still
requires separate owner testing. No key, password or user ledger was added.

## v1.1.0: automatic Drive backup

Compared with v1.0.0, this update adds opt-in daily Google Drive backups and
keeps the existing manual Drive/local restore with merge or confirmed replace.
The ledger logic, database schema, application ID and signing identity are unchanged.

### Added

- `app/src/main/java/com/hisabkhata/domain/backup/AutomaticBackup.kt`
- `app/src/main/java/com/hisabkhata/data/drive/AutomaticDriveBackupCoordinator.kt`
- `app/src/main/java/com/hisabkhata/data/drive/AutomaticDriveBackupWorker.kt`
- `app/src/test/java/com/hisabkhata/domain/backup/AutomaticBackupTest.kt`
- `app/src/androidTest/java/com/hisabkhata/data/drive/AutomaticDriveBackupIntegrationTest.kt`

### Modified

- App version and WorkManager dependency; manifest and application initialization.
- Drive error reporting, preferences, backup screen/view model and strings.
- R8 keep rules, Android and minified-release probes.
- README, Google setup/release/privacy documentation and changelog.

No files were removed. No key, password, user ledger or export was added.

## v1.0.0 changed files

Compared with the prior Hisab Khata Compact Search (versionCode 4) source package.
Release work changes build/signing configuration, documentation, CI, fictional
fixtures/samples and an IDE-only preview; it does not add runtime UI or ledger
features or change the database schema.

## Added

- `.github/workflows/ci.yml`
- `.github/workflows/release.yml`
- `CHANGELOG.md`
- `PRIVACY.md`
- `docs/GOOGLE_SETUP.md`
- `docs/RELEASING.md`
- `keystore.properties.example`
- `scripts/release-smoke/AndroidManifest.xml`
- `scripts/release-smoke/com/hisabkhata/releaseprobe/ReleaseProbe.java`
- `scripts/release_notes.py`
- `scripts/run_release_probe.sh`

## Modified

- `.gitignore`
- `CHANGED_FILES.md`
- `README.md`
- `app/build.gradle.kts`
- `app/proguard-rules.pro`
- `app/src/androidTest/java/com/hisabkhata/data/RoomLoanSynchronizationTest.kt`
- `app/src/androidTest/java/com/hisabkhata/data/TransferIntegrationTest.kt`
- `app/src/androidTest/java/com/hisabkhata/ui/CompactSearchUiTest.kt`
- `app/src/androidTest/java/com/hisabkhata/ui/DeleteConfirmationTest.kt`
- `app/src/androidTest/java/com/hisabkhata/ui/MobileLedgerUiTest.kt`
- `app/src/main/assets/samples/cash_sample.csv`
- `app/src/main/assets/samples/cash_sample.xlsx`
- `app/src/main/assets/samples/loan_sample.csv`
- `app/src/main/assets/samples/loan_sample.xlsx`
- `app/src/main/java/com/hisabkhata/ui/components/MobileLedgerTable.kt`
- `app/src/test/java/com/hisabkhata/domain/LoanCashSynchronizerTest.kt`
- `app/src/test/java/com/hisabkhata/domain/backup/BackupCodecTest.kt`
- `app/src/test/java/com/hisabkhata/domain/interchange/ImportParserTest.kt`

## Removed

None.
