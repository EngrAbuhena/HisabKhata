# Changed files

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
