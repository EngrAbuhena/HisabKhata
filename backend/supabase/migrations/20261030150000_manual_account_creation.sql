-- ============================================================================
-- Accounts are created manually in the Supabase dashboard.
--
-- Decision: no invitation links, no self-service account creation. The owner
-- adds each account through the Supabase dashboard; the auth trigger creates its
-- profile. This migration removes the invitation layer added by migrations 1
-- and 2, and the activation gate that came with it (which would otherwise lock
-- out every manually created account).
--
-- What remains:
--   * profiles with a server-controlled role and status
--   * SuperAdmin can disable / re-enable accounts (data preserved)
--   * owner-only role assignment through private.set_account_role()
--   * append-only audit trail
-- ============================================================================

-- ------------------------------------------------------- drop invite surface --
drop function if exists public.claim_invitation(text);
drop function if exists public.create_invitation(public.account_role, text);
drop function if exists public.revoke_invitation(uuid);
drop function if exists private.require_not_disabled();

-- ------------------------------------------------- drop activation gate ------
-- The FK to account_invitations must go before the table itself.
alter table public.profiles
    drop column if exists activated_via,
    drop column if exists activated_by,
    drop column if exists activated_at;

drop index if exists public.profiles_activated_at_idx;

drop table if exists public.account_invitations;

-- ------------------------------------------------------------- helpers ------
-- An account is usable when it exists and its status is active. Creation by the
-- owner is the authorization step, so no separate activation is required.
create or replace function private.is_active()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select coalesce(
        (select p.status = 'active' from public.profiles p where p.user_id = auth.uid()),
        false
    )
$$;

create or replace function private.require_active()
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_status public.account_status;
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;

    select p.status into v_status from public.profiles p where p.user_id = auth.uid();
    if v_status is distinct from 'active' then
        raise exception 'account is not active' using errcode = '42501';
    end if;
end;
$$;

-- --------------------------------------------------- guard trigger (revert) ---
create or replace function private.guard_profile_columns()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
    v_authority boolean;
begin
    if new.user_id is distinct from old.user_id then
        raise exception 'profile ownership is immutable' using errcode = '42501';
    end if;

    v_authority := current_user = (
                       select pg_catalog.pg_get_userbyid(c.relowner)
                       from pg_catalog.pg_class c
                       where c.oid = 'public.profiles'::regclass
                   )
                   or coalesce((
                       select r.rolsuper from pg_catalog.pg_roles r
                       where r.rolname = current_user
                   ), false)
                   or current_user::text in ('service_role', 'supabase_admin', 'supabase_auth_admin');

    if not v_authority and (
           new.role is distinct from old.role
        or new.status is distinct from old.status
    ) then
        raise exception 'account role and status are server controlled'
            using errcode = '42501';
    end if;

    return new;
end;
$$;

-- ------------------------------------------------------------- my_account ----
drop function if exists public.my_account();

create function public.my_account()
returns table (
    user_id uuid,
    display_name text,
    account_role public.account_role,
    account_status public.account_status,
    email_verified boolean
)
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    return query
        select p.user_id, p.display_name, p.role, p.status,
               coalesce(u.email_confirmed_at is not null, false)
        from public.profiles p
        join auth.users u on u.id = p.user_id
        where p.user_id = auth.uid();
end;
$$;

revoke all on function public.my_account() from public, anon;
grant execute on function public.my_account() to authenticated;

-- ------------------------------------------------- owner-only role control ---
-- Accounts are created in the dashboard; their roles are set here (or with a
-- direct UPDATE from the dashboard). Never callable by an app client.
create function private.set_account_role(p_user_id uuid, p_role public.account_role)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_old public.account_role;
begin
    if p_user_id is null or p_role is null then
        raise exception 'account and role are required' using errcode = '22023';
    end if;

    select p.role into v_old
    from public.profiles p where p.user_id = p_user_id
    for update;

    if not found then
        raise exception 'account not found' using errcode = 'P0002';
    end if;

    if v_old = p_role then
        return;
    end if;

    update public.profiles p set role = p_role where p.user_id = p_user_id;

    -- auth.uid() is NULL when the owner runs this from the dashboard.
    insert into public.account_events (actor_id, subject_id, action, details)
    values (auth.uid(), p_user_id, 'role_changed',
            jsonb_build_object('from', v_old, 'to', coalesce(p_role::text, '')));
end;
$$;

revoke all on function private.set_account_role(uuid, public.account_role)
    from public, anon, authenticated;

comment on function private.set_account_role(uuid, public.account_role) is
  'Owner-only role assignment. Not exposed to API clients; run from the dashboard.';

-- --------------------------------------------- first SuperAdmin bootstrap ----
create or replace function private.bootstrap_superadmin(p_user_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    if p_user_id is null then
        raise exception 'account id is required' using errcode = '22023';
    end if;

    perform private.set_account_role(p_user_id, 'superadmin');

    insert into public.account_events (actor_id, subject_id, action, details)
    values (auth.uid(), p_user_id, 'superadmin_bootstrapped', '{}'::jsonb);
end;
$$;

comment on function private.bootstrap_superadmin(uuid) is
  'Owner-only: grants the first SuperAdmin role. Not exposed to API clients.';

revoke all on function private.bootstrap_superadmin(uuid) from public, anon, authenticated;

-- ------------------------------------------------------- publish changes -----
notify pgrst, 'reload schema';
