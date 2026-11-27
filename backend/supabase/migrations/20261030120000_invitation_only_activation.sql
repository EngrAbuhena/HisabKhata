-- ============================================================================
-- Invitation-only activation.
--
-- Migration 1 made accounts safe *once they exist*, but a profile was 'active'
-- the moment the Auth server created it. With public sign-up open that would
-- mean anybody could register and immediately use the app. This migration adds
-- an explicit activation step that only a verified email plus a valid
-- invitation can satisfy.
--
--   * profiles.activated_at  NULL  => the account exists but has no app access
--   * status = 'disabled'          => access revoked by the SuperAdmin
--   * both                         => normal use
--
-- Order of operations on a real deployment would be: backfill activated_at for
-- existing rows, then deploy. Staging holds no accounts, so nothing to backfill.
-- ============================================================================

alter table public.profiles
    add column if not exists activated_at timestamptz,
    add column if not exists activated_by uuid references auth.users (id) on delete set null,
    add column if not exists activated_via uuid references public.account_invitations (id) on delete set null;

comment on column public.profiles.activated_at is
  'When the account first completed invitation acceptance. NULL means no app access yet.';
comment on column public.profiles.activated_by is
  'The account that issued the invitation used for activation.';
comment on column public.profiles.activated_via is
  'The invitation used for activation. Kept for audit, not exposed to clients.';

create index if not exists profiles_activated_at_idx on public.profiles (activated_at);

-- ---------------------------------------------------------------------------
-- Role, status and activation are all server controlled. Extend the guard so a
-- future accidental grant still cannot activate an account on its own.
-- ---------------------------------------------------------------------------
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
        or new.activated_at is distinct from old.activated_at
        or new.activated_by is distinct from old.activated_by
        or new.activated_via is distinct from old.activated_via
    ) then
        raise exception 'account role, status and activation are server controlled'
            using errcode = '42501';
    end if;

    return new;
end;
$$;

-- ---------------------------------------------------------------------------
-- Activation-aware helpers. is_active() is what RLS policies call, so every
-- policy in the schema becomes invitation-gated at once.
-- ---------------------------------------------------------------------------
create or replace function private.is_active()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select coalesce(
        (select p.status = 'active' and p.activated_at is not null
           from public.profiles p where p.user_id = auth.uid()),
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
    v_activated timestamptz;
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;

    select p.status, p.activated_at into v_status, v_activated
    from public.profiles p where p.user_id = auth.uid();

    if v_status is null then
        raise exception 'account is not active' using errcode = '42501';
    end if;
    if v_status <> 'active' then
        raise exception 'account is not active' using errcode = '42501';
    end if;
    if v_activated is null then
        raise exception 'account has not been activated with an invitation'
            using errcode = '42501';
    end if;
end;
$$;

-- Used by the invitation claim path: a disabled account may not activate, but
-- an not-yet-activated one obviously must be allowed to.
create function private.require_not_disabled()
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

revoke all on function private.require_not_disabled() from public, anon, authenticated;

-- ---------------------------------------------------------------------------
-- my_account(): now reports activation separately so the app can tell
-- "never activated" (show the invitation screen) from "disabled" (show support).
-- ---------------------------------------------------------------------------
drop function if exists public.my_account();

create function public.my_account()
returns table (
    user_id uuid,
    display_name text,
    account_role public.account_role,
    account_status public.account_status,
    email_verified boolean,
    account_activated boolean
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
               coalesce(u.email_confirmed_at is not null, false),
               p.activated_at is not null
        from public.profiles p
        join auth.users u on u.id = p.user_id
        where p.user_id = auth.uid();
end;
$$;

revoke all on function public.my_account() from public, anon;
grant execute on function public.my_account() to authenticated;

-- ---------------------------------------------------------------------------
-- claim_invitation(): the only route to activation. Requires a verified email
-- and a live invitation, consumes the link, and records the activation.
-- ---------------------------------------------------------------------------
drop function if exists public.claim_invitation(text);

create function public.claim_invitation(p_token text)
returns table (
    account_role public.account_role,
    display_name text,
    account_activated boolean
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_uid uuid := auth.uid();
    v_inv public.account_invitations;
    v_current public.account_role;
    v_name text;
    v_was_activated timestamptz;
begin
    perform private.require_not_disabled();
    perform private.require_verified_email();

    if p_token is null or p_token !~ '^[0-9a-f]{64}$' then
        raise exception 'invalid invitation' using errcode = '22023';
    end if;

    select i.* into v_inv
    from public.account_invitations i
    where i.token_hash = extensions.digest(p_token, 'sha256')
    for update;

    if not found then
        raise exception 'invalid invitation' using errcode = '22023';
    end if;
    if v_inv.revoked_at is not null then
        raise exception 'invitation was cancelled' using errcode = '22023';
    end if;
    if v_inv.claimed_at is not null then
        raise exception 'invitation was already used' using errcode = '22023';
    end if;
    if v_inv.expires_at <= now() then
        raise exception 'invitation has expired' using errcode = '22023';
    end if;

    select p.role, p.display_name, p.activated_at
      into v_current, v_name, v_was_activated
    from public.profiles p where p.user_id = v_uid;

    if v_current is null then
        raise exception 'account profile is missing' using errcode = '42501';
    end if;

    update public.account_invitations i
       set claimed_by = v_uid, claimed_at = now()
     where i.id = v_inv.id;

    update public.profiles p
       set activated_at = coalesce(p.activated_at, now()),
           activated_by = coalesce(p.activated_by, v_inv.created_by),
           activated_via = coalesce(p.activated_via, v_inv.id)
     where p.user_id = v_uid;

    if v_was_activated is null then
        perform private.record_event(
            'account_activated',
            v_uid,
            jsonb_build_object('invitation_id', v_inv.id, 'role', v_inv.role)
        );
    end if;

    if v_current = 'superadmin' or (v_current = 'admin' and v_inv.role = 'user') then
        perform private.record_event(
            'invitation_claimed_without_change',
            v_uid,
            jsonb_build_object('invitation_id', v_inv.id, 'role', v_current)
        );
        return query select v_current, v_name, true;
        return;
    end if;

    update public.profiles p set role = v_inv.role where p.user_id = v_uid;

    perform private.record_event(
        'invitation_claimed',
        v_uid,
        jsonb_build_object('invitation_id', v_inv.id, 'role', v_inv.role)
    );

    return query select v_inv.role, v_name, true;
end;
$$;

revoke all on function public.claim_invitation(text) from public, anon;
grant execute on function public.claim_invitation(text) to authenticated;

-- ---------------------------------------------------------------------------
-- Owner-only bootstrap for the very first SuperAdmin. Deliberately NOT
-- callable by anon/authenticated: it exists for one run by the project owner
-- after they create their own account through the Auth server.
-- ---------------------------------------------------------------------------
create function private.bootstrap_superadmin(p_user_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    if p_user_id is null then
        raise exception 'account id is required' using errcode = '22023';
    end if;

    update public.profiles p
       set role = 'superadmin',
           activated_at = coalesce(p.activated_at, now())
     where p.user_id = p_user_id;

    if not found then
        raise exception 'account not found' using errcode = 'P0002';
    end if;

    insert into public.account_events (actor_id, subject_id, action, details)
    values (null, p_user_id, 'superadmin_bootstrapped', '{}'::jsonb);
end;
$$;

comment on function private.bootstrap_superadmin(uuid) is
  'Owner-only: grants the first SuperAdmin role and activates the account. Not exposed to API clients.';

revoke all on function private.bootstrap_superadmin(uuid) from public, anon, authenticated;
