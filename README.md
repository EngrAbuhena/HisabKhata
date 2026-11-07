# Hisab Khata

Hisab Khata is an Android Cash Book and Loan Ledger for recording deposits,
costs, money lent and money borrowed. Ledger work is local and works offline;
Google Drive backup is optional and requires a Google account, internet access
and Google Play services.

## Features

- Cash Book with running balance, search, month/date filters and a compact summary.
- Loan Ledger for lent and borrowed money, with optional linked Cash Book entries.
- Mobile-friendly tables and confirmations before destructive operations.
- CSV, Excel and PDF export; CSV and Excel import with bundled **fictional** samples.
- Local JSON backup and restore, including merge or confirmed replacement.
- Google Drive backups in the selected account's private app-data folder: manual
  backup plus an opt-in daily backup around 11:45 p.m. device-local time.
- Manual restore of a selected local JSON or Drive backup, with a backup list,
  last-backup times, merge, or explicitly confirmed replacement.
- System, light and dark themes.

Automatic backup needs internet and may be delayed by Android; it is not an exact
alarm or a substitute for testing a restore. A dedicated backup log and an app
lock are not included. The app does not encrypt backup or exported files.

## Screenshots

Screenshots are not yet included. Add only fictional, sanitized images here before
publishing; do not commit screenshots of a real ledger or account.

## Requirements

- Android 7.0 or later (API 24+); Google Drive requires Google Play services.
- JDK 17, Android SDK Platform 35 and Build-Tools 35.0.0.
- Android Studio with Android Gradle Plugin 8.7.3 support, or the included Gradle
  8.9 wrapper. Dependency downloads require network access.
- Package: `com.hisabkhata`; version: `1.1.0` (`versionCode` 6).

## Build and install

From the project root:

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest lint
# Optional: run Android tests on a connected device/emulator
./gradlew connectedDebugAndroidTest
```

On Windows use `gradlew.bat`. The debug APK is at
`app/build/outputs/apk/debug/app-debug.apk`. Install an APK from a trusted source
using `adb install -r path/to/app.apk` or your device's package installer.
Android accepts an install-over update only if its application ID and signing
certificate match and its version code is not lower.

To build a signed release, copy `keystore.properties.example` to the ignored
`keystore.properties` and supply your private keystore path, alias and passwords;
or use `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` in the
build environment. Never commit signing material or print passwords. Then run:

```sh
./gradlew testDebugUnitTest lint assembleRelease bundleRelease
```

The signed APK is `app/build/outputs/apk/release/app-release.apk`; the AAB is
`app/build/outputs/bundle/release/app-release.aab`. If signing is not configured,
the build prints an explicit warning and produces an **unsigned** release; it is
not installable or publishable until signed. Release builds enable R8 and resource
shrinking. See [docs/RELEASING.md](docs/RELEASING.md) for secure signing, tests,
GitHub secrets and tagging.

The supplied v1.0.0 and v1.1.0 releases reuse the previous installed debug build's signing
**key** so it can update that app without an uninstall. That key originated as an
Android debug key; keep its private keystore secure. A separately supplied copy is
protected with a new password, but that does not undo exposure of any earlier copy.
**These artifacts are not Google Play-ready:** [Android's signing guidance](https://developer.android.com/studio/publish/app-signing)
says Google Play does not accept apps signed with a debug certificate. A different
release key cannot update this installed app without a supported migration. Back up
user data and plan a secure signing/installation transition before publishing.

## Backup warning

**Uninstalling the app or clearing its data deletes the on-device ledger.** Before
changing phones, uninstalling, restoring over data, or changing signing identity,
create and test a local JSON backup and keep it somewhere safe. Android automatic
app backup is disabled. JSON, CSV, Excel and PDF files are not encrypted by the
app; protect your files and Google account. See [PRIVACY.md](PRIVACY.md).

## Back up and restore

1. Open **Settings → Backup and restore** and choose **Sign in with Google**.
   Select the account and grant the private Drive app-data permission. Upload a
   manual backup first and verify that it appears after **Refresh backups**.
2. Turn on **Automatic daily backup**. It is off by default and aims for 11:45 p.m.
   in the device's local time. On Android 13+, you can allow optional notifications
   for backup problems. The backup screen shows the last automatic success and
   whether a retry or renewed authorization is needed.
3. To restore, select a listed Drive backup and tap **Restore**, or choose
   **Restore from JSON file** for a local backup. Review the record counts. **Merge**
   appends records (including duplicates); **Replace** overwrites the current
   database and requires a separate confirmation. Make a local backup first.

Automatic backups are new snapshots, not sync, and are not deleted automatically;
use the backup list to remove old ones if needed. Android can delay background
work for connectivity, power, device-off and other system restrictions. A force-
stopped app will not run background work until reopened. Turning the toggle off or
signing out cancels scheduled work; an already-started upload may still finish.
Restore is never automatic. Backups are limited to 32 MiB and are not encrypted
by this app. **Test backup and restore with your own account before relying on it.**

## Google Drive setup

Enable the Google Drive API, configure the OAuth consent screen and test users, and
register Android OAuth credentials for `com.hisabkhata` with the SHA-1 of each
certificate used to sign installed builds. The app requests only the
`drive.appdata` scope and uses the account you select. See the complete checklist
in [docs/GOOGLE_SETUP.md](docs/GOOGLE_SETUP.md). Live Google authorization and Drive
operations require your own Google Cloud setup and have not been verified in this
workspace.

## Project structure

```text
app/src/main/java/com/hisabkhata/
  data/                 Room, repositories, transfer, preferences, Drive REST
  domain/               Ledger calculations and backup/import codecs
  ui/                   Compose screens, components, navigation and theme
app/src/main/assets/samples/  Fictional CSV and XLSX import examples
app/src/main/res/             Strings and Android resources
app/src/test/                 JVM tests
app/src/androidTest/          Android/Room and UI tests for debug builds
app/schemas/                  Exported Room schema
scripts/release-smoke/        Standalone minified-release device probe
.github/workflows/            CI and signed tag-release workflows
docs/                         Release and Google OAuth checklists
```

Very large monetary values may be horizontally compressed on narrow screens at
large font scales; the full value remains available to accessibility services.
Excel itself has about 15 significant digits of numeric precision, so the export
writes larger exact values as text. PDF/CSV/Excel exports are reports, not full
backups. Imports are bounded and validated before saving.

## Rights

All rights reserved. No open-source license has been granted with this project.
