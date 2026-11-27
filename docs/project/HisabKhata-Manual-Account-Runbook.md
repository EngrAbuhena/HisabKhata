# Creating accounts manually (owner runbook)

Invitation links and self-service sign-up were **cancelled**. Every account is now
created by you in the Supabase dashboard. This replaces
`HisabKhata-Bootstrap-SuperAdmin-Runbook.md`.

**Who does this:** you, the project owner, in the Supabase dashboard.

---

## What changed in the database

| Removed | Added / kept |
|---|---|
| `account_invitations` table | `profiles` (role + status, server controlled) |
| `create_invitation()`, `claim_invitation()`, `revoke_invitation()` | `private.set_account_role()` — owner only |
| `activated_at`, `activated_by`, `activated_via` columns | `private.bootstrap_superadmin()` — owner only |
| the "must claim an invitation to use the app" gate | `my_account()`, `update_display_name()`, `set_account_status()` |

A new auth user gets its `profiles` row automatically (role `user`, status
`active`) from a trigger on `auth.users`. There is no activation step: the act of
creating the account *is* the authorisation.

---

## Step 1 — Create the first SuperAdmin

1. **Authentication → Users → Add user → Create new user.**
2. Enter your email and a strong password. Tick **Auto Confirm User** so the
   account does not wait on an email (the default mailer is rate limited).
3. Copy the user's **UID**.
4. **SQL editor** → run:

```sql
select private.bootstrap_superadmin('PASTE-YOUR-USER-UID-HERE');
```

5. Verify:

```sql
select p.user_id, p.role, p.status, u.email_confirmed_at is not null as verified
  from public.profiles p join auth.users u on u.id = p.user_id
 where p.user_id = 'PASTE-YOUR-USER-UID-HERE';
```

Expected: `superadmin` / `active` / `verified = true`. A
`superadmin_bootstrapped` row is written to `public.account_events`.

6. Change that bootstrap password from the app on first sign-in.

---

## Step 2 — Create every other account

Repeat for each person:

1. **Authentication → Users → Add user → Create new user.**
   - Email + password, **Auto Confirm** ticked.
   - Do **not** use "Send invite" — that sends an email through the test
     mailer and the person would still have to confirm before signing in.
2. Set the role (defaults to `user`; skip for normal users):

```sql
select private.set_account_role('THEIR-USER-UID', 'admin');   -- or 'user'
```

3. Tell the person their email and temporary password. They sign in and change
   the password.

There is currently **no in-app password reset working** until a real SMTP sender
is configured — until then, reset a password from the dashboard
(Authentication → Users → user → ⋯ → Reset password or update it directly).

---

## Step 3 — Verify the gate

| Check | Expected |
|---|---|
| Sign in as a normal user | Lands in the app with an empty ledger |
| Try to see another account in the directory | Users see only themselves |
| Sign in as Admin | Sees the non-SuperAdmin account list |
| Sign in as SuperAdmin | Sees all accounts and the audit trail |
| Disable an account (SuperAdmin) | The person is refused on their very next request; data intact |
| Re-enable | They can work again immediately |

---

## Important correction: sign-up does NOT need to stay on

Earlier I said dashboard user creation required public sign-up to be enabled.
**That was wrong.** The Supabase dashboard's *Add user* and the admin API use an
independently authenticated admin path (service role). The `disable_signup`
toggle only blocks the public `signUp()` endpoint — the one an app or stranger
would call. Admin creation keeps working either way.

So the safe setup is:

- **`disable_signup = true`** — no stranger can register.
- **Add users from the dashboard** whenever you want — unaffected.
- The app has no sign-up screen, so nothing breaks.

Recommendation: **keep `disable_signup = true` permanently** and add accounts as
needed. There is no reason to open sign-up at all.

### If you ever do open it temporarily

Some teams switch it on briefly, then forget to switch it back. Then this is the
routine:

```sql
-- who exists, newest first
select id, email, created_at, email_confirmed_at, last_sign_in_at
  from auth.users
 order by created_at desc;

-- who looks like a stranger (never signed in, created recently)
select id, email, created_at
  from auth.users
 where last_sign_in_at is null
   and created_at > now() - interval '30 days';

-- remove one (only safe while it owns no data: the profile cascades away)
delete from auth.users where id = 'SUSPECT-UID';
```

**Never delete an account that owns ledger or note data.** Disable it instead:

```sql
select public.set_account_status('THEIR-UID', 'disabled');
```

That blocks every write immediately, keeps the data, and is reversible.

---

## Still open

- **SMTP** — needed for password reset. Without it, resets must be done from the
  dashboard.
- **Password policy** — minimum length is 6 with no other rules; raise before
  real users arrive.
- **Backups** — Free plan has no automatic database backups.
- **MFA** — TOTP is enabled project-wide; enrol it on the SuperAdmin account.
- **Ledger tables** — Step 3 of the plan; nothing ledger-related exists yet, so
  an account today can sign in but has no data tables to use.
