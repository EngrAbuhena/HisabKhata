# Hisab Khata

Hisab Khata is an Android Cash Book and Loan Ledger for recording deposits,
costs, money lent and money borrowed. Ledger work is local and works offline.

Version 1.3.0 adds accounts: the app asks you to sign in at launch, keeps each
account's ledger private to that account in the cloud, and syncs changes made
elsewhere. Google Drive backup remains optional and requires a Google account,
internet access and Google Play services.

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
- English and Bengali app languages, with a phone-language option; numbers are
  displayed with 0–9 digits in both languages. System, light and dark themes.
- **Accounts (1.3.0):** sign in with an account created for you by the owner,
  with no self sign-up. Each account has its own private ledger; private data is
  never shared with other accounts, admins or the SuperAdmin.
- **Cloud sync (1.3.0):** changes are saved on the device first and uploaded
  when a connection is available. Settings shows *Saved locally, waiting to
  sync*, *All changes are synced* or *Action needed* when a change conflicts with
  another device. A conflict is never resolved by silently overwriting data.

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
- Package: `com.hisabkhata`; version: `1.3.0` (`versionCode` 8).
- A Supabase project for accounts and sync. The client URL and **publishable**
  key are compiled into `BuildConfig`; the secret/service-role key must never be
  added to the app or this repository.

## Build and install

From the project root:

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest lint
# Optional: run Android tests on a connected device/emulator
./gradlew connectedDebugAndroidTest
```

The Supabase endpoint defaults to the project configured in `app/build.gradle.kts`.
Override it without editing files (useful for a separate test project):

```sh
./gradlew assembleDebug -PsupabaseUrl=https://YOUR-PROJECT.supabase.co \
                        -PsupabasePublishableKey=sb_publishable_...
# or: SUPABASE_URL=... SUPABASE_PUBLISHABLE_KEY=... ./gradlew assembleDebug
```

The publishable key is a client key and is safe to ship: it maps to the `anon`
and `authenticated` roles, and every table is protected by owner-only Row Level
Security. Never put the `service_role`/secret key in the app, this repository or
a workflow file.

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

The supplied v1.0.0, v1.1.0 and v1.2.0 releases reuse the previous installed debug build's signing
**key** so it can update that app without an uninstall. That key originated as an
Android debug key; keep its private keystore secure. A separately supplied copy is
protected with a new password, but that does not undo exposure of any earlier copy.
**These artifacts are not Google Play-ready:** [Android's signing guidance](https://developer.android.com/studio/publish/app-signing)
says Google Play does not accept apps signed with a debug certificate. A different
release key cannot update this installed app without a supported migration. Back up
user data and plan a secure signing/installation transition before publishing.

## Accounts and cloud sync (1.3.0)

Accounts are created by the owner in the Supabase dashboard; self sign-up is
disabled on the server and the app has no registration screen. Sign-in is
required at launch, and the session is deliberately not stored on disk, so a
shared phone does not reopen the previous account's ledger.

How sync behaves:

- **Local first.** Every change is written to the on-device database and queued in
  the same transaction, so a saved row is never left without its outgoing change.
- **Retry safe.** Each row carries a stable `client_id`, and uploads are
  `(owner_id, client_id)` upserts, so a retry after a lost response cannot create a
  duplicate. Repeated edits collapse into one queued change.
- **Account scoped.** A queued change records the account that created it and is
  only ever sent for that account. Signing out warns about unsynced work and never
  discards it; it uploads the next time that account signs in.
- **Conflicts.** Updates are conditional on the server version last seen. If
  another device changed the row, nothing is overwritten: the change stays queued
  and Settings shows **Action needed**.
- **Linked loan/cash pairs** are joined by one atomic server call
  (`link_ledger_entries`), because each Supabase REST request is its own
  transaction and a linked pair cannot be written in two requests.
- **Pull.** After uploading, the app downloads this account's rows and matches
  them by `client_id`, so a fresh install or a second device shows the same
  ledger. Rows with unsent local changes are never replaced by older cloud copies.

The ledger tables in the on-device database are unchanged by this update: the
database upgrade only adds two sync tables, so existing records, amounts and
linked entries keep their values. Take a local JSON backup before upgrading.

Sync is not encryption: whoever controls the Supabase project can read the
database. It is also not a backup plan — see **Backup warning**.

## Backup warning

**Uninstalling the app or clearing its data deletes the on-device ledger.** Before
changing phones, uninstalling, restoring over data, or changing signing identity,
create and test a local JSON backup and keep it somewhere safe. Android automatic
app backup is disabled. JSON, CSV, Excel and PDF files are not encrypted by the
app; protect your files and Google account. See [PRIVACY.md](PRIVACY.md).

## Language / ভাষা

Open **Settings → Language** and choose **English**, **বাংলা**, or **Follow phone
language**. The choice is saved and works offline. English remains the default for
existing installations. The phone-language option uses Bengali when the phone's
language is Bengali and English otherwise. System-owned screens such as the file
picker and Google consent may use the phone's own language.

Numbers and dates are displayed with 0–9 digits in both app languages. Amount fields
also accept Bengali digits and normalize them to 0–9; amounts remain exact integer
paisa. Existing names and descriptions are not translated. English and Bengali
CSV/Excel import headings are recognized, while JSON backup keys and ledger data
stay language-independent. Reports generated in Bengali use Bengali headings;
existing English imports remain supported. When exporting CSV/Excel for an older
app version, choose English first: older versions may not recognize Bengali headings.
On Android 7 with very large system text, calendar weekday abbreviations may be cut
off; full date accessibility labels remain available. On short landscape screens,
scroll inside the calendar to reach all dates.

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

## Backend (accounts and sync schema)

`backend/` holds the Supabase schema, policies and tests for accounts, private
ledgers, notes and atomic linking, plus a README with the exact client contract.
Run its suites with:

```bash
cd backend && ./scripts/run_local_accounts_tests.sh   # needs PostgreSQL 17
```

## Project structure

```text
app/src/main/java/com/hisabkhata/
  data/                 Room, repositories, cloud/sync, transfer, preferences, Drive REST
  domain/               Ledger calculations, sync engine and backup/import codecs
  ui/                   Compose screens, components, navigation and theme
app/src/main/assets/samples/  Fictional CSV and XLSX import examples
app/src/main/res/             Strings and Android resources
app/src/test/                 JVM tests
app/src/androidTest/          Android/Room and UI tests for debug builds
app/schemas/                  Exported Room schemas (1 and 2)
scripts/release-smoke/        Standalone minified-release device probe
.github/workflows/            CI and signed tag-release workflows
backend/                      Supabase migrations, policies and tests (see backend/README.md)
docs/                         Release and Google OAuth checklists
docs/project/                 Implementation plan, foundation report and account runbook
```

Very large monetary values may be horizontally compressed on narrow screens at
large font scales; the full value remains available to accessibility services.
Excel itself has about 15 significant digits of numeric precision, so the export
writes larger exact values as text. PDF/CSV/Excel exports are reports, not full
backups. Imports are bounded and validated before saving.

## Rights

All rights reserved. No open-source license has been granted with this project.
