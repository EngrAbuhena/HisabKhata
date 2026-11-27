# Hisab Khata: Android, Accounts, Supabase and Notepad Plan

**Status: Steps 2–3 complete on the server; Step 4 (Android client) is in progress.** The owner cancelled invitation links and self-service sign-up; accounts are added by hand in the Supabase dashboard. Seven migrations are live (accounts, manual creation, private ledgers, notes, notes reconcile, atomic link). The server is verified with 145 local assertions and rolled-back or deleted staging proofs. Step 4 now has: a sign-in gate at launch, a Room v1→v2 migration that only adds tables, a durable per-account upload queue, retry-safe `client_id` upserts, conditional updates that report conflicts, atomic cash/loan linking, and a pull that fills a fresh install from the cloud. 82 JVM tests pass and a debug v1.3.0 APK assembles. Nothing is pushed to GitHub, signed for release, or released, and no real user data has been touched. The website and Vercel remain paused.

## 1. Goal and current baseline

Add accounts created by the owner, individually owned ledgers, cloud storage and sync, and a Notepad to the existing Android app while preserving existing data and offline use.

The public GitHub repository was checked read-only for this plan: `main` is the v1.2.0 source commit `4eeeb64`; the v1.1.0 and v1.2.0 tags and releases exist, with matching APKs and verification reports. The signed-release workflow is currently disabled. Recheck the repository before starting, because it can change.

The current Android app uses a local Room database, supports Android 7/API 24 and later, and stores money as integer paisa. Its current JSON backup format is version 1. Optional Google Drive backups are separate from cloud sync and have not yet been tested with a live Google account in this project.

**Out of scope for now:** a website, web app, and deployment to Vercel. Existing app features should be retained unless a change is explicitly approved.

## 2. Agreed access rules

A ledger means the owner's Cash Book, Loan Ledger, people and their linked records. Every account type has its own personal ledger.

| Action | SuperAdmin | Admin | User |
|---|---|---|---|
| See account directory | All accounts | All accounts except SuperAdmin accounts | Own account; a wider directory has not been requested |
| View, create, edit and delete own ledger entries | Yes | Yes | Yes |
| View or change another person's ledger | No | No | No |
| View another person's private notes | No | No | No |
| Read public notes | Yes | Yes | Yes |
| Edit another person's public note | No | No | No |
| Create accounts | **Owner, manually in Supabase** | No | No |
| Assign roles | **Owner, manually in Supabase** | No | No |
| Disable or re-enable a User or Admin account | Yes, without deleting their data | No | No |

**Important correction:** the latest instruction overrides the earlier idea that SuperAdmin could view everyone else's ledger. In the app, SuperAdmin sees accounts but not the contents of other people's ledgers or private notes.

For public notes, SuperAdmin-authored notes never expose the author's name to app users. Admin and User authors can choose whether to show their names **for each note**, with the author hidden by default. Public means visible to signed-in, permitted app users, not to the unauthenticated internet. Only a note's creator can edit or delete it; safe deletion was included in the original feature list.

For other accounts, an Admin may view the non-SuperAdmin account list. An Admin cannot create accounts, change roles, disable or delete users. Accounts and roles are created only by the owner in the Supabase dashboard. SuperAdmin can disable and re-enable User and Admin accounts; disabling an account does not delete its data or grant SuperAdmin access to its private ledger or notes. Disabling a SuperAdmin account has not been requested.

### Chosen privacy level: Option A

Supabase Row Level Security (RLS), server-side authorization, and restricted responses will enforce the rules for ordinary app and API users. The person controlling the Supabase project or its secret/service-role key can technically access database records and ownership metadata, even if that person also has a SuperAdmin app account. This is the accepted Option A trade-off; it is not end-to-end encryption. A secret key must never be placed in the APK, public GitHub repository, or a public client. [2](https://supabase.com/docs/guides/auth/users)

## 3. Step-by-step implementation

### Step 0 — Confirm the decisions and protect the baseline

**Progress**

- [x] Checked the public GitHub branch and tags read-only: `main` is v1.2.0 commit `4eeeb64`, with v1.1.0 and v1.2.0 tags present.
- [x] Created a separate clean local clone at `/home/user/HisabKhata-cloud-work`, on branch `feature/android-accounts-notepad`. Nothing was pushed and the existing source folder was not modified.
- [x] Ran the existing translation/number-policy checker: 313 resources passed.
- [x] Baseline verification passed using Java 21 (the app targets Java 17) and Android SDK 35: 63/63 JVM tests passed and a debug APK assembled. The first attempt's Gradle daemon disappeared; retrying with one worker, a smaller memory limit and separate phases succeeded. Instrumentation tests were not rerun in this step. The temporary debug build is not a release artifact and must not be installed over the existing release.
- [x] Connected Supabase and confirmed the owner-selected `HisabKhata-Staging` project in Tokyo for testing. Auth, database and REST health checks passed; no public application tables, migrations or Edge Functions were present at the initial read-only check. The other project must not be touched.
- [ ] Before testing an upgrade on any real installation, create and verify that user's local JSON backup. A source-code copy is not a ledger backup.
- [ ] Agree on a safe signing and release path. The previously distributed APKs use an Android debug certificate; an install-over update needs a compatible signing identity, and Google Play publication needs a separately planned production-signing transition. Never publish the keystore or passwords.

**Checkpoint:** source baseline and user-data backup are verified; product, staging and signing decisions are understood. Do not overwrite `main`, old tags, or existing releases.

### Step 1 — Prepare a safe Supabase environment

1. The owner creates or designates a Supabase project. Use a separate test/staging project before any production data is moved.
2. Configure the project's region, access, quotas, database backups and recovery plan. Verify the plan's actual backup features and costs; syncing to Supabase is not itself a backup.
3. Set up Supabase Auth with email + password. Accounts are created by the owner in the dashboard (invitation links were cancelled on 30 Oct 2026). Password recovery still needs email delivery, so configure a production sender; Supabase's default email service is restricted and intended for testing. [2](https://supabase.com/docs/guides/auth/testing-auth-flows)
4. Keep server secrets in the provider's secret settings. The Android app may use a publishable client key with properly tested RLS, never the secret/service-role key. Limit project-administrator access and recommend MFA for privileged accounts.
5. Bootstrap the first SuperAdmin through a controlled project-owner process; do not hard-code a password or allow users to select their own privileged role.

**Checkpoint:** staging is isolated, secrets are protected, and database recovery can be tested without touching real user data.

**Done so far (backend foundation, 30 Oct 2026):**

- Repository files in `/home/user/HisabKhata-backend`: `supabase/migrations/` (three migrations), `tests/local/`, `tests/staging/`, `scripts/run_local_accounts_tests.sh`.
- Migration 1 `accounts_roles_invitations`: `profiles`, `account_events`, enum types, ~10 functions, triggers, RLS policies, column-level grants only. Clients cannot INSERT/UPDATE/DELETE these tables or create objects in `public`.
- Migration 2 `invitation_only_activation`: added an activation gate (later removed by migration 3).
- Migration 3 `manual_account_creation`: **dropped** `account_invitations`, the three invitation RPCs and the activation columns; reverted `is_active()`/`require_active()` to status-only; added owner-only `private.set_account_role()` and kept owner-only `private.bootstrap_superadmin()`.
- Roles and status are server-controlled. A new auth user gets a profile from the insert trigger (role from server-written app metadata only, never user metadata). A guard trigger refuses any client role/status change.
- Local harness: `./scripts/run_local_accounts_tests.sh` builds a scratch PostgreSQL 17 database with a Supabase shim and runs **59 allow/deny/privacy assertions**.
- Staging verification: 7-check manual-account run, all rolled back. Staging afterwards: 0 users, 0 profiles, 0 audit rows, no leftover `tests` schema.

**Checkpoint:** staging is isolated, secrets are protected, and database recovery can be tested without touching real user data.

**Done so far (backend foundation, 30 Oct 2026):**

- Repository files live in `/home/user/HisabKhata-backend` (outside the Android clone): three migrations under `supabase/migrations/`, `tests/local/`, `tests/staging/`, `scripts/run_local_accounts_tests.sh`.
- Migration `accounts_roles_invitations` → `invitation_only_activation` → `manual_account_creation`, applied in order to `pghgdvqadmrdbqkewllv` (Tokyo).
- Current schema: enum types `account_role`, `account_status`; tables `profiles` and `account_events`; RLS policies on both; column-level SELECT grants only; clients cannot INSERT/UPDATE/DELETE either table or create objects in `public`.
- Client-callable functions: `my_account()`, `update_display_name(text)`, `set_account_status(uuid, account_status)`. Owner-only: `private.set_account_role()`, `private.bootstrap_superadmin()` — both revoked from `anon`/`authenticated`.
- A new auth user gets a profile from the insert trigger (role from server-written app metadata only, never user metadata). A guard trigger refuses any client role/status change even if a future grant appears.
- Disabling an account preserves every row but blocks all writes immediately; re-enabling clears the trail. SuperAdmin cannot disable itself or another SuperAdmin.
- Audit trail (`account_events`) is readable only by the active SuperAdmin.
- Local harness: `./scripts/run_local_accounts_tests.sh` builds a scratch PostgreSQL 17 database with a Supabase shim and runs **59 allow/deny/privacy assertions**, then rolls back.
- Staging verification: 7-check manual-account run (create → assign role → sign in → write → disable), rolled back. Staging afterwards: 0 users, 0 profiles, 0 audit rows, no leftover `tests` schema.

**Checkpoint:** staging is isolated, secrets are protected, and database recovery can be tested without touching real user data.

**Done so far (backend foundation, 30 Oct 2026):**

- Repository files in `/home/user/HisabKhata-backend`: `supabase/migrations/` (three migrations), `tests/local/`, `tests/staging/`, `scripts/run_local_accounts_tests.sh`.
- Migration 1 `accounts_roles_invitations`: `profiles`, `account_events`, enum types, ~10 functions, triggers, RLS policies, column-level grants only. Clients cannot INSERT/UPDATE/DELETE these tables or create objects in `public`.
- Migration 2 `invitation_only_activation`: added an activation gate (later removed by migration 3).
- Migration 3 `manual_account_creation`: **dropped** `account_invitations`, the three invitation RPCs and the activation columns; reverted `is_active()`/`require_active()` to status-only; added owner-only `private.set_account_role()` and kept owner-only `private.bootstrap_superadmin()`.
- Roles and status are server-controlled. A new auth user gets a profile from the insert trigger (role from server-written app metadata only, never user metadata). A guard trigger refuses any client role/status change.
- Local harness: `./scripts/run_local_accounts_tests.sh` builds a scratch PostgreSQL 17 database with a Supabase shim and runs **59 allow/deny/privacy assertions**.
- Staging verification: 7-check manual-account run, all rolled back. Staging afterwards: 0 users, 0 profiles, 0 audit rows, no leftover `tests` schema.

**Checkpoint:** staging is isolated, secrets are protected, and database recovery can be tested without touching real user data.

**Done so far (backend foundation, 30 Oct 2026):**

- Repository files live in `/home/user/HisabKhata-backend` (kept outside the Android clone): `supabase/migrations/20261030090000_accounts_roles_invitations.sql`, `tests/local/`, `tests/staging/`, `scripts/run_local_accounts_tests.sh`.
- Migration `accounts_roles_invitations` is applied to `pghgdvqadmrdbqkewllv` (Tokyo). Objects created: enum types `account_role`, `account_status`; tables `profiles`, `account_invitations`, `account_events`; 19 functions; 5 triggers (including the `auth.users` insert trigger); 6 RLS policies; column-level SELECT grants only. Client roles cannot INSERT/UPDATE/DELETE any of these tables directly and cannot create objects in `public`.
- Roles and status are server-controlled. A new auth user gets a profile from raw **app** metadata only; `raw_user_meta_data` is ignored. A guard trigger refuses any role/status change made by a non-owner, so a future accidental grant still cannot escalate privileges.
- Disabling an account preserves every row but blocks all writes and invitation claims immediately; re-enabling clears the trail. SuperAdmin cannot disable itself or another SuperAdmin.
- Audit trail (`account_events`) is readable only by the active SuperAdmin.
- Local harness: `./scripts/run_local_accounts_tests.sh` recreates a scratch PostgreSQL 17 database, applies a local Supabase shim (auth schema, `auth.uid()`, `anon`/`authenticated`/`service_role`, hosted default privileges), runs 87 allow/deny/privacy assertions and rolls back.
- Parity: a schema fingerprint (function definitions, policies, triggers, columns, ACLs) matches staging object-for-object after applying the same fix locally.
- Staging self-test: 33 checks passed inside a rolled-back transaction. Staging afterwards: 0 auth users, 0 profiles, 0 invitations, 0 events, no leftover `tests` schema.


### Auth settings changed on staging

| Setting | Value |
|---|---|
| Public sign-up | **disabled** — dashboard admin user creation is unaffected |
| Email autoconfirm | off (email must be verified) |
| Email provider / phone | enabled / disabled |
| Redirect allowlist | `hisabkhata://auth/confirm`, `hisabkhata://auth/recovery`, localhost equivalents |
| Refresh token rotation | on |
| JWT expiry | 3600 s |

**Owner action required next:** follow `HisabKhata-Manual-Account-Runbook.md` to create the first SuperAdmin, then change its password from the app.

**Still open in Step 1:** production email sender for password recovery (default mailer allows ~2 messages/hour to project team only) or dashboard-based password resets, password minimum length (still 6), project backup/recovery verification (Free plan has no automatic backups), MFA enrolment for privileged accounts, and a cleanup routine for self-registered accounts.

### Step 2 — Build accounts and roles (invitations cancelled)

**Decision (latest): there are no invitations and no self-service sign-up. The owner creates every account manually in the Supabase dashboard.** This supersedes the earlier invitation design in this document.

1. The owner adds each account: Supabase dashboard → Authentication → Users → Add user (email + password, with Auto Confirm, or send an invite email from the dashboard). The `hisabkhata_account_profile` trigger creates the matching `public.profiles` row automatically as an `active` `user`.
2. The owner sets roles from the dashboard with `select private.set_account_role('USER-UUID', 'admin');` — or the first one with `select private.bootstrap_superadmin('USER-UUID');`. Both are owner-only and revoked from `anon`/`authenticated`; every change is written to `public.account_events`.
3. The app has **no sign-up screen and no invitation screen**. It has sign-in, sign-out, and password recovery only. Account creation is an owner/administrative action outside the app.
4. Roles and status stay server-controlled: profile rows are not writable by clients, and a guard trigger refuses role/status changes even if a future grant is added by mistake.
5. SuperAdmin may disable and re-enable User and Admin accounts from the app; disabling keeps all data, blocks every write immediately (checked server-side on each request) and never exposes the account's ledger to SuperAdmin. SuperAdmin cannot disable itself or another SuperAdmin.
6. Because the app can no longer create accounts, Android App Links / deep-link handling for invitations is not needed. Only the email confirmation and password-recovery redirects remain (`hisabkhata://auth/confirm`, `hisabkhata://auth/recovery`), which work without a website.
7. Password recovery still needs email delivery even though creation is manual — either configure SMTP, or let the owner reset passwords from the dashboard.

**Sign-up stays disabled.** The dashboard's *Add user* and the admin API authenticate separately (service role) and keep working with `disable_signup = true`; the toggle only closes the public `signUp()` endpoint. The app therefore has sign-in only, and no stranger can register. If sign-up is ever re-opened temporarily, review `auth.users` afterwards and delete unknown accounts (disabled accounts are never deleted while they own data).

**Checkpoint:** a manually created account signs in, sees only its own (empty) ledger, and cannot escalate its role; a disabled account is refused immediately; a deleted account's data is not orphaned.

### Step 3 — Create private cloud ledgers and enforce permissions

1. Design database records for profiles/roles, Cash Book, people, Loan Ledger entries, and notes (no invitations: accounts are created manually). Associate each private record with its owner; do not trust an owner ID supplied by the client.
2. Enable and test database-level RLS and grants for every exposed table and operation. All app roles—including SuperAdmin—can access only their own ledger and private notes. User-directory rules are separate from data-access rules.
3. Keep roles and activation status in server-controlled data, not user-editable profile metadata. Privileged server functions must validate authorization themselves because privileged keys can bypass RLS.
4. Preserve money as exact integer paisa, existing ordering and calculations, and the relationships between loan and cash entries. Linked changes must be applied atomically.
5. Use stable IDs and protected ownership so a user cannot change a record to another person's account or link to another person's private records.

**Done (migration `private_ledgers`):** `people`, `loan_entries` and `cash_entries` with owner-only RLS, `bigint` paisa, epoch-day dates, epoch-ms timestamps, per-owner unique `client_id` for retry-safe uploads, soft delete, and a deferred constraint trigger enforcing symmetric same-owner cash/loan links. There is no admin or SuperAdmin read policy by design.

**Checkpoint:** direct API tests prove that changing a user ID or role in a request does not expose or alter someone else's data. ✅ Proven locally (100 assertions) and on staging (owner A's ledger invisible to another user and to the SuperAdmin).

### Step 4 — Add Android offline storage, sync and migration

1. Keep a local Room cache and a durable queue of unsynced changes. Supabase is the cloud record; the app shows clear states such as **Saved locally**, **Waiting to sync**, **Synced**, and **Action needed**.
2. Existing users must first have a safe local backup opportunity, then must sign in to continue using the upgraded app; do not offer an indefinite local-only mode. A fresh installation with no old data does not need a pre-login data backup. Initial authentication needs internet. After a successful sign-in, previously available data can be used offline under the chosen session policy. The owner's other devices cannot see changes that have not yet reached Supabase.
3. Scope caches and queued work to the correct account, including on shared phones. Never send one account's pending changes as another account. On sign-out, warn about unsynced work and do not silently discard it.
4. Give records stable IDs, use retry-safe operations, and detect conflicting edits from multiple devices. For financial conflicts, do not silently overwrite another edit: retain the pending change and ask the owner to resolve it.
5. Migrate existing v1.0–v1.2 local data without destructive database recreation. Provide a backup/export path before the required sign-in, show a preview, let the user confirm which account receives the data, and verify counts, totals and cash/loan links. If backup or sign-in fails, preserve local data and let the user retry; never silently erase it. Keep the original local data until migration is verified. Retrying must not duplicate entries.
6. If SuperAdmin disables a User or Admin account, server requests must be rejected using current server-side status, not just an out-of-date role in a cached token. SuperAdmin may later re-enable that account. Do not delete the disabled account's data or expose it to SuperAdmin. Previously cached data on an offline device cannot be remotely erased immediately; recheck access when the device reconnects.

**Done so far (Android client foundation, 30 Oct 2026 — branch `feature/android-accounts-notepad`, build v1.3.0 / versionCode 8):**

- **Sign-in is required at every launch.** Accounts are created by the owner in the dashboard with self sign-up disabled; the app has no registration or self-service recovery path. The auth session is deliberately not persisted to disk, so a shared phone never reopens the previous account's ledger. Sign-in failure explains credentials, disabled account, or no connection, and never touches local data.
- **Room 1 → 2 is additive.** The ledger tables (`people`, `cash_entries`, `loan_entries`) are byte-identical in the exported schemas; v2 adds only `sync_ids` (local id ↔ `client_id`/cloud uuid, unique indexes checked against `2.json`) and `sync_outbox`. `MIGRATION_1_2` is registered in one place and there is no destructive fallback.
- **Durable queue.** Each local write and its queue row commit in one Room transaction (`RoomSyncStore.track`), so "saved but never queued" cannot happen. Repeated edits collapse per row; a queued delete wins over later edits.
- **Account isolation.** A queue row records the account that created it and is only pushed for that account; account switching never sends another person's work.
- **Retry safety.** Every row gets an immutable `client_id`, and uploads are `(owner_id, client_id)` upserts, so replaying a pass cannot duplicate rows.
- **Conflicts.** Updates carry the last seen `updated_at`; zero matching rows means another device changed the row. The change is retained and shown as *Action needed* — never silently overwritten.
- **Order.** People upload before their loans; a child whose parent has no cloud id waits in the queue instead of being sent with a local id.
- **Atomic cash/loan linking.** Verified live that PostgREST cannot write a linked pair in two requests (`23514`, because the symmetry trigger is deferred), so migration 7 adds `public.link_ledger_entries`, an owner-scoped atomic invoker RPC. The app calls it after both rows exist and keeps the queue row until it succeeds.
- **Pull.** After a push, the app downloads the account's rows and matches them by `client_id`; rows with unsent local work are never overwritten. Soft-deleted rows are filtered.
- **Testing.** 82 JVM tests pass (63 existing + 19 new: 14 sync-engine, 5 queue-merge). `RoomMigrationTest` is written (schema-1 database with paisa amounts and a cross-ledger link, upgraded, then verified) but has not been run because no emulator is available in this environment. The migration SQL was also checked statement-by-statement against the exported schema with SQLite.

**Still open in Step 4:** the pre-sign-in backup screen and the "which account receives this data" confirmation for the upgrade path; device/instrumentation runs of the migration and UI tests; account-scoped Room caches per account on a shared phone (queued work is already scoped, the cache itself is not yet partitioned); and password-recovery delivery.

**Checkpoint:** signed upgrade, offline edits, interrupted upload, duplicate retry, account switching and conflict tests preserve the correct user's data.

### Step 5 — Add Notepad and public-note privacy

Place **Notepad** between Loan Ledger and Settings:

**Cash Book | Loan Ledger | Notepad | Settings**

First-stage features:

- Create and edit notes with an optional title and multiline content; auto-save with a clear saved/sync status.
- List notes with a preview and last-edited date; search by title and content; pin important notes.
- Safe deletion with confirmation/undo; only the owner can edit or delete their own note.
- Work offline and support English and Bengali UI. Preserve user-entered text and digits; use the existing app policy for app-generated numbers.
- Start every note as **Private**. Its owner can choose to make it **Public to signed-in users** and later make it private again.
- A SuperAdmin's author identity is always hidden in public feeds. Admin and User authors can choose whether their name is shown **for each note**; the author's name is hidden by default.

Protect author identity in the API as well as the UI: anonymous public-note responses must not leak the author's name, role, profile, or owner ID. Restrict direct access to raw note records and review views/functions carefully; a database view can otherwise use its creator's elevated privileges. [3](https://supabase.com/docs/guides/database/views)

Changing a public note back to private blocks future authorized retrieval after the change is applied, but cannot take back screenshots or copies already seen or cached by others. Content or timing can also allow someone to guess an author. The trusted Supabase project operator may see underlying ownership under Option A.

Keep the previously suggested **later-phase** items on the roadmap: checklists, categories/colors, reminders, linking to the owner's loan/person, text export/share, and optional note lock. A public note must never automatically expose its linked private ledger record. A lock is not the same as encryption.

**Checkpoint:** private/public access, creator-only edits, author masking, opt-in author display, offline editing, Bengali text and API-level leakage tests pass.

### Step 6 — Update backups and recovery

1. Update versioned local JSON backup/restore to include the owner's notes and ledger. Do not include other users' cached public notes as if they were the owner's data. Restore cannot write into another user's ledger or change their role.
2. Continue accepting old version-1 backups. When an old backup contains no notes, preserve existing notes by default and explain this in the restore preview. New-format backups may not open in older app versions; any optional old-format export must be clearly labelled as excluding new features.
3. Preserve the existing optional Google Drive backup flow where practical, now scoped to the owner's data. Test real authorization, upload, list, download, restore and scheduled work separately; these have not yet been verified live.
4. Keep tested, protected Supabase database backups separately from app-level exports. Database-wide recovery is a privileged operational action and must not be exposed as an ordinary user's restore.
5. Explain that existing app JSON/Drive exports are not encrypted by the app; protect files and accounts accordingly.

**Checkpoint:** old and new backups restore correctly, notes are not accidentally erased, and restoring one account cannot overwrite another account's data.

### Step 7 — Test before release

Use fictional data and separate test accounts for at least one SuperAdmin, two Admins and two Users. Test:

- Every role and every read/create/edit/delete permission, both through Android and by attempting direct unauthorized API requests.
- Account list filtering, hidden SuperAdmin identities, public note author choices, and attempts to obtain hidden identity from API responses.
- Manual account creation and role assignment from the Supabase dashboard; sign-in and password reset. Verify that a client can never create an account, change a role or change a status; that only SuperAdmin can disable/re-enable accounts; that disabling does not expose or delete the target's private data; and that an Admin cannot disable accounts.
- Android 7/API 24 plus a newer Android device; small screens, large fonts, English/Bengali, and accessibility.
- Upgrade from v1.2.0 with fictional cash, people, loans, links, settings and backups. Verify the pre-sign-in backup, required sign-in, retry paths, totals and data integrity.
- Offline create/edit/delete, interrupted sync, retries, conflicts, sign-out with pending work and account switching.
- Old and new JSON restore; optional real Google Drive flows; Supabase backup and recovery rehearsal.
- CI/build, secret scanning, release signing and actual signed APK install-over testing.

**Checkpoint:** document what passed, what was not tested, and any remaining risks. Do not claim live email or Drive behavior works before it has been tested.

### Step 8 — Release safely

1. Review the feature branch and tests before merging. Select a version number and increase Android's version code above v1.2.0's code 7; a major version may be appropriate, but the owner decides.
2. Resolve the existing debug-signing issue and test the intended update path. Do not tell users to uninstall or clear data as a shortcut.
3. Prepare release notes explaining sign-in, offline sync, migration, privacy, backup and any remaining limitations.
4. Publish a tagged Android release and matching APK/report after approval. GitHub's signed-release workflow is currently disabled; do not re-enable it or add signing secrets without a separate decision.
5. Observe sync and backup behavior after release and retain a rollback/recovery plan.

## 4. Decisions still needed before implementation

The following choices are settled: Admin copies an unbound link to share and any holder may claim it; a User link is one-use and valid for 24 hours; an Admin link is one-use and valid for 1 hour. Users sign in with verified email and password. Admin can view non-SuperAdmin accounts, invite Users, and cancel their own unused invitations, but cannot disable accounts. Only SuperAdmin can invite Admins, can cancel any pending invitation, and can disable and re-enable User and Admin accounts. Existing users must sign in after backing up; Admin/User authors choose name visibility per note, hidden by default. The owner confirmed the Tokyo `HisabKhata-Staging` project for testing.

Settled on 30 Oct 2026 for Step 4: sign-in at first launch of v1.3.0, add a Supabase client dependency with the project URL and publishable key in `BuildConfig`, and keep a Room id-mapping table rather than rewriting existing rows.

Before a real release, confirm the setup and signing plan, secure the initial SuperAdmin bootstrap, configure and test verification email delivery and Android invitation links, and back up each existing user's data. Never send project passwords, access tokens or service-role keys in chat. The duration and revocation rules are app-level policies and must be enforced server-side.

## 5. What the owner will eventually need

- A Supabase project they control, with an appropriate plan and recovery strategy.
- An email delivery service for password recovery (or an agreed dashboard-based reset routine). Invitation links and therefore Android App Links are no longer needed.
- Access to the existing signing material or a separately approved, tested transition plan.
- Explicit permission before any GitHub push, Supabase setup, migration or release. Never send passwords, access tokens or signing keys in chat.
