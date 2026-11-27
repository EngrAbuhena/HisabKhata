# Hisab Khata: accounts, ledgers and notes (Supabase backend)

Server-side schema, policies and tests for the private, per-account ledgers used by
the Hisab Khata Android app (v1.3.0 and later).

In the app repository this folder is `backend/`; it is also self-contained, so it can
be split into its own repository without changes. The Android client contract below
is what `app/src/main/java/com/hisabkhata/data/cloud/` implements.

**Status:** applied to the staging project `pghgdvqadmrdbqkewllv`
(HisabKhata-Staging, Tokyo). Verified with **145 local assertions** across four
suites plus staging proofs; staging holds no user rows.

## What this provides

| Area | Detail |
|---|---|
| Accounts | `profiles` + `account_events`; roles `superadmin`/`admin`/`user`, status `active`/`disabled` |
| Account creation | **Manual, by the owner in the Supabase dashboard.** Self sign-up is disabled server-side |
| Private ledgers | `people`, `loan_entries`, `cash_entries` — owner-only Row Level Security, no admin or SuperAdmin read policy |
| Notes | `notes` + masked `notes_feed` view; public notes readable by signed-in users, SuperAdmin authors always hidden |
| Linking | `public.link_ledger_entries(...)` joins a loan and its cash row atomically |
| Audit | Role and status changes recorded in `account_events` |

Money is stored as `bigint` **paisa**; dates as epoch days (`entry_date`) and
epoch milliseconds (`created_at_ms`), matching the Android Room schema exactly, so
the app never reformats amounts or dates during sync.

## Migrations (apply in order)

```
supabase/migrations/
  20261030090000_accounts_roles_invitations.sql
  20261030120000_invitation_only_activation.sql
  20261030150000_manual_account_creation.sql
  20261030170000_private_ledgers.sql
  20261030190000_notes.sql
  20261030200000_notes_reconcile.sql
  20261030210000_atomic_link.sql
```

`20261030200000_notes_reconcile.sql` is idempotent and exists because an earlier
out-of-band draft of `notes` was found on staging; on a fresh database it is a
no-op re-create.

## Running the tests locally

Requires PostgreSQL 17 (`postgresql-17`, `postgresql-client-17`). Nothing here
connects to a hosted project.

```bash
./scripts/run_local_accounts_tests.sh          # scratch DB created, then dropped
./scripts/run_local_accounts_tests.sh --keep   # keep the scratch DB to inspect
```

The script starts a Unix-socket-only PostgreSQL, installs a Supabase shim
(`auth` schema, `auth.uid()`, client roles, hosted default privileges), applies
every migration, runs each `tests/local/*_test.sql` suite as the real
`authenticated`/`anon` roles with JWT claims, and rolls everything back.

Expected final line:

```
ACCOUNTS TESTS PASSED (145 assertions)
```

| Suite | Covers |
|---|---|
| `tests/local/10_accounts_test.sql` | roles, status, self-service denial, audit rows |
| `tests/local/20_private_ledgers_test.sql` | owner-only reads/writes, cross-account denial, amounts, links, anon denial |
| `tests/local/30_notes_test.sql` | private/public notes, read-only public notes, author masking, soft delete |
| `tests/local/40_atomic_link_test.sql` | `link_ledger_entries`: link, unlink, idempotency, cross-account and anon denial |

`artifacts/local-accounts-tests.log` is the recorded output of a full run.

## Client contract used by the Android app

Only the **publishable** key ships in the app; it maps to the `anon` and
`authenticated` roles. The `service_role`/secret key must never leave the owner's
environment. Every table is owner-scoped by RLS, so no client can read another
account's ledger.

| Operation | Call |
|---|---|
| Sign in | `POST /auth/v1/token?grant_type=password` |
| Upsert a row (retry safe) | `POST /rest/v1/<table>?on_conflict=owner_id,client_id` with `Prefer: resolution=merge-duplicates,return=representation` |
| Conditional update | `PATCH /rest/v1/<table>?id=eq.<uuid>&updated_at=eq.<seen>`; an empty array means another device changed the row |
| Soft delete | `PATCH` setting `deleted_at` |
| Link a loan and cash row | `POST /rest/v1/rpc/link_ledger_entries` |

`owner_id` is never sent by the client: it defaults to `auth.uid()`.

**Why the link RPC exists:** the cash/loan symmetry check is a *deferred*
constraint trigger. Each PostgREST request is its own transaction, so a client
cannot create one side of a pair and then the other — the second write fails with
`23514`. `link_ledger_entries` resolves both rows by `client_id` for the calling
owner and writes both pointers in one transaction.

## Security notes

- `disable_signup` stays `true`. Accounts are created in the dashboard, so no
  public registration path exists.
- Roles and status are server-controlled; clients cannot insert, update or delete
  `profiles` and cannot change their own role.
- A disabled account loses every application read and write path, while its data is
  kept and remains invisible to the SuperAdmin.
- This is Row Level Security, not end-to-end encryption: whoever controls the
  project (or its secret key) can read the database.

## Rights

All rights reserved. No open-source license is granted with this project.
