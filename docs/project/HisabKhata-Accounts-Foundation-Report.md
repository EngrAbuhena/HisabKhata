# Accounts foundation: current state in HisabKhata-Staging

**Project:** `pghgdvqadmrdbqkewllv` (HisabKhata-Staging, Tokyo `ap-northeast-1`)
**Migrations applied:** `accounts_roles_invitations` → `invitation_only_activation` → `manual_account_creation` → `private_ledgers` → `notes` (+ `notes_reconcile`) → `atomic_link`
**Verification:** 145 local assertions across four suites + rolled-back staging proofs and live API checks
**Model:** accounts are created **manually by the owner in the Supabase dashboard**. Invitation links and the activation gate were cancelled on request.

---

## 1. What exists now

### Tables (RLS enabled)

| Table | Purpose | Client access |
|---|---|---|
| `public.profiles` | one row per account: display name, role, status, disable trail | column-level `SELECT` for `authenticated`; no INSERT/UPDATE/DELETE |
| `public.account_events` | append-only audit trail | visible to the active SuperAdmin only |

### Types
`public.account_role` = `user | admin | superadmin`; `public.account_status` = `active | disabled`.

### Functions

| Function | Callable by | Purpose |
|---|---|---|
| `my_account()` | any signed-in account | own role, status, whether the email is verified |
| `update_display_name(text)` | active accounts | trim + validate + store the name |
| `set_account_status(uuid, account_status)` | SuperAdmin only | disable / re-enable; records who and when; never deletes data |
| `private.set_account_role(uuid, role)` | **owner only** (dashboard) | assigns `user`/`admin`/`superadmin`, writes an audit row |
| `private.bootstrap_superadmin(uuid)` | **owner only** (dashboard) | grants `superadmin` to the first account |

Owner-only functions are revoked from `public`, `anon` and `authenticated`, so no
app client or leaked publishable key can reach them.

### What was removed

`account_invitations` table, `create_invitation()`, `claim_invitation()`,
`revoke_invitation()`, the `activated_*` columns and the "claim an invitation to
activate" gate. Creating an account is now the authorisation step.

---

## 1d. Private ledgers (migration 4)

| Table | Holds | Owner |
|---|---|---|
| `public.people` | name, phone, note, `lent`/`borrowed` | `owner_id = auth.uid()` set by the database |
| `public.loan_entries` | `loan`, `paid` (integer paisa), epoch-day date, description, optional linked cash row | same |
| `public.cash_entries` | `deposit`, `cost` (integer paisa), epoch-day date, description, optional linked loan row | same |

Design points that matter for the app:

- **Money is `bigint` paisa**, exactly as Room stores it — no float, no rounding.
- **`entry_date` is epoch days** and **`created_at_ms` is epoch milliseconds**, so ordering and calculations match the current app.
- **`client_id`** is a stable app-generated UUID, unique per owner, so a retried upload after a lost response cannot duplicate a row.
- **Soft delete** via `deleted_at` for undo/sync; hard `DELETE` is also permitted.
- **Cash/loan links are symmetric and same-owner**, enforced by a deferred constraint trigger, matching `BackupCodec.validate`.
- **Linking is one atomic call**: `public.link_ledger_entries(loan_client_id, cash_client_id, linked)` (migration 7, owner-scoped, invoker rights, idempotent). Needed because each PostgREST request is its own transaction, so two requests can never satisfy the deferred symmetry check — verified live on staging (`23514` before the fix, both directions stored after it).
- **One RLS policy set per table, owner-only.** There is deliberately no admin or SuperAdmin read policy.

**Staging proof (rolled back):** owner A wrote 1 person and 1 loan of
123,456,789 paisa. Another user saw `people 0, loans 0`. The **SuperAdmin** also
saw `people 0, loans 0`.

## 1e. Notes (migrations 5–6)

| Column | Meaning |
|---|---|
| `title`, `body` | content; at least one must be non-blank |
| `is_public` | default **false** — visible only to the author |
| `show_author` | per-note; default **false**; only allowed on a public note |
| `pinned` | pins to the top of the list |
| `deleted_at` | soft delete, so undo and sync work |
| `client_id` | app-generated, unique per author, so retries cannot duplicate |

Read path: `public.notes` (raw rows, author identity not granted) and
`public.notes_feed` (the only view that can carry a display name).

**Staging proof (rolled back):** a reader saw `private_visible 0`, `public_visible 3`,
`named_author "Ann"`, `anon_author NULL`, `superadmin_author NULL`. So a private
note is invisible, an opted-in author is named, an opted-out author is not, and a
SuperAdmin author is never named even with the flag set.

Note: staging briefly held an earlier draft whose SELECT policy allowed only the
author and which granted `owner_id` to clients. It held zero rows, and
`notes_reconcile` replaced it with the tested definition.

## 2. Guaranteed behaviour (each proven by a test)

**Manual creation works immediately.** Adding a user in the dashboard creates
its profile by trigger as an `active` `user`; the person can sign in and use the
app at once — no activation step.

**Clients cannot create or escalate.** A client cannot INSERT/UPDATE/DELETE a
profile, cannot create objects in `public`, and cannot call the owner-only role
functions. A guard trigger refuses role/status changes even if a future grant is
added by mistake.

**Admin vs SuperAdmin.** An Admin sees non-SuperAdmin accounts and cannot change
roles, statuses or accounts. A SuperAdmin sees all accounts plus the audit trail
and can disable/re-enable User and Admin accounts — but not itself or another
SuperAdmin, and never gains access to another account's ledger.

**Disabling is immediate and non-destructive.** Every write path re-checks the
server-side status, so a disabled account fails on its next request even with a
valid token. Its rows are preserved and remain invisible to SuperAdmin.

---

## 1f. Atomic link (migration 7)

`public.link_ledger_entries(p_loan_client_id uuid, p_cash_client_id uuid, p_linked boolean default true)`
returns `(loan_id, cash_entry_id)`.

- Resolves both rows **by `client_id` and `owner_id = auth.uid()`**; another account's rows look exactly like missing rows (`P0002`).
- Runs as `security invoker`, so RLS still applies; revoked from `public`/`anon`, granted to `authenticated`.
- Writes both pointer columns in one transaction (the deferred checks pass at commit), or clears both when `p_linked = false`.
- Never writes an amount, and is idempotent, so a retried upload can call it again safely.

**Live staging proof (fictional test account, deleted afterwards):** loan and cash
rows created separately, RPC returned both ids, both directions readable, replay
returned the same pair; unlink cleared both; anonymous call refused with `42501`.
Soft-deleting both rows left the cloud rows in place with `deleted_at` set and the
active views empty.

## 3. Re-running the tests

```bash
cd /home/user/HisabKhata-backend
./scripts/run_local_accounts_tests.sh          # 145 assertions, scratch DB dropped after
./scripts/run_local_accounts_tests.sh --keep   # keep the scratch DB to inspect
```

Needs `postgresql-17` + `postgresql-client-17`; the script starts a Unix-socket
server, applies a Supabase shim (auth schema, `auth.uid()`, client roles, hosted
default privileges), runs all three migrations, then the suite, and rolls back.

---

## 4. Open decisions and work

| Item | State |
|---|---|
| First SuperAdmin | **waiting on you** — see `HisabKhata-Manual-Account-Runbook.md` |
| Public sign-up | **disabled**. Dashboard user creation still works (admin path is separate). No stranger can register |
| SMTP / password reset | not configured; default mailer is test-restricted. Resets must be done from the dashboard |
| Password policy | minimum 6, no character rules |
| Backups | Free plan has no automatic backups |
| MFA | TOTP enabled; not yet enrolled on any account |
| Ledger tables + RLS | **done** — `people`, `loan_entries`, `cash_entries` live with owner-only RLS |
| Notes table + public-note privacy | **done** — `notes` + `notes_feed` live with masked authors |
| Atomic link RPC | **done** — `link_ledger_entries`, tested locally and proven live |
| Android app | **Step 4 in progress** on `feature/android-accounts-notepad`: sign-in gate, Supabase client, durable queue, conflict detection, atomic linking, cloud pull; Room v1→v2 additive migration; 82 JVM tests pass; debug v1.3.0 (versionCode 8) assembles. Not released, not pushed. |
| Android still open | pre-sign-in backup + account-confirmation screen, per-account Room caches, emulator runs of `RoomMigrationTest` and UI tests |

---

## 5. Suggested next step

**Step 4 — Android: auth + offline cache.** Sign-in/sign-out screens, a
per-account Room cache and a durable upload queue with retry-safe `client_id`
upserts against the tables that now exist. This is the first change to the
Android source, so it will be a new version (v1.3.0) that installs over v1.2.0.
