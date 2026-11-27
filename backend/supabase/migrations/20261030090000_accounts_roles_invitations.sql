-- ============================================================================
-- HisabKhata accounts foundation: server-controlled roles, invitations and
-- account lifecycle.
--
-- Design rules enforced here:
--   * Role and status live in public.profiles, written only by SECURITY DEFINER
--     functions that re-check the caller. They are never taken from client
--     supplied metadata.
--   * A new auth user gets a profile through a trigger on auth.users. The role
--     may only come from raw_app_meta_data, which clients cannot write.
--   * Invitations are unbound, single use and time limited (24h for users,
--     1h for administrators). Only a hash of the token is stored.
--   * A disabled account keeps its data but loses every application read and
--     write path.
--   * Clients get column level SELECT on profiles/invitations and EXECUTE on
--     the RPCs below. There is no direct INSERT/UPDATE/DELETE for clients.
-- ============================================================================

create schema if not exists extensions;
create extension if not exists pgcrypto with schema extensions;

create schema if not exists private;
revoke all on schema private from public;
grant usage on schema private to authenticated;

create type public.account_role as enum ('user', 'admin', 'superadmin');
create type public.account_status as enum ('active', 'disabled');

comment on type public.account_role is
  'Account role. Server controlled; clients cannot assign it directly.';

-- ---------------------------------------------------------------- profiles --
create table public.profiles (
    user_id uuid primary key references auth.users (id) on delete cascade,
    display_name text,
    role public.account_role not null default 'user',
    status public.account_status not null default 'active',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    disabled_at timestamptz,
    disabled_by uuid references auth.users (id) on delete set null,
    constraint profiles_display_name_check check (
        display_name is null
        or (
            char_length(display_name) between 1 and 80
            and display_name = btrim(display_name)
            and display_name !~ '[[:cntrl:]]'
        )
    ),
    constraint profiles_disabled_consistency check (
        (status = 'active' and disabled_at is null and disabled_by is null)
        or (status = 'disabled' and disabled_at is not null)
    )
);

comment on table public.profiles is
  'One row per auth user. Role and status are server controlled.';

create function private.touch_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

create trigger profiles_touch_updated_at
    before update on public.profiles
    for each row execute function private.touch_updated_at();

-- Defence in depth: even if a future migration accidentally grants UPDATE on
-- the role/status columns, an ordinary client role still cannot change them.
-- Authority is defined structurally (table owner, superuser, hosted service
-- roles) rather than by hard coding the hosted owner name, so the local test
-- database and the hosted project behave identically.
create function private.guard_profile_columns()
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

    if not v_authority
       and (new.role is distinct from old.role or new.status is distinct from old.status) then
        raise exception 'account role and status are server controlled'
            using errcode = '42501';
    end if;

    return new;
end;
$$;

create trigger profiles_guard_columns
    before update on public.profiles
    for each row execute function private.guard_profile_columns();

-- ------------------------------------------------------------------ events --
create table public.account_events (
    id bigint generated always as identity primary key,
    actor_id uuid references auth.users (id) on delete set null,
    subject_id uuid references auth.users (id) on delete set null,
    action text not null,
    details jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

comment on table public.account_events is
  'Append-only account audit trail. Readable by the super administrator only.';

-- ----------------------------------------------------------------- helpers --
create function private.current_role()
returns public.account_role
language sql
stable
security definer
set search_path = ''
as $$
    select p.role from public.profiles p where p.user_id = auth.uid()
$$;

create function private.is_active()
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

create function private.is_superadmin()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select coalesce(
        (select p.role = 'superadmin' and p.status = 'active'
           from public.profiles p where p.user_id = auth.uid()),
        false
    )
$$;

create function private.has_admin_role()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select coalesce(
        (select p.role in ('admin', 'superadmin') and p.status = 'active'
           from public.profiles p where p.user_id = auth.uid()),
        false
    )
$$;

create function private.account_is_active(p_user_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select coalesce(
        (select p.status = 'active' from public.profiles p where p.user_id = p_user_id),
        false
    )
$$;

create function private.require_active()
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    if auth.uid() is null then
        raise exception 'authentication required' using errcode = '28000';
    end if;
    if not private.is_active() then
        raise exception 'account is not active' using errcode = '42501';
    end if;
end;
$$;

create function private.require_verified_email()
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    if not coalesce(
        (select u.email_confirmed_at is not null from auth.users u where u.id = auth.uid()),
        false
    ) then
        raise exception 'email must be verified' using errcode = '42501';
    end if;
end;
$$;

create function private.record_event(p_action text, p_subject uuid, p_details jsonb default '{}'::jsonb)
returns void
language sql
security definer
set search_path = ''
as $$
    insert into public.account_events (actor_id, subject_id, action, details)
    values (auth.uid(), p_subject, p_action, coalesce(p_details, '{}'::jsonb));
$$;

-- Only the three helpers referenced directly by RLS policies are executable by
-- clients. Everything else is reachable only from the SECURITY DEFINER RPCs.
revoke all on function private.current_role() from public, anon, authenticated;
revoke all on function private.is_active() from public, anon;
revoke all on function private.is_superadmin() from public, anon;
revoke all on function private.has_admin_role() from public, anon;
revoke all on function private.account_is_active(uuid) from public, anon, authenticated;
revoke all on function private.require_active() from public, anon, authenticated;
revoke all on function private.require_verified_email() from public, anon, authenticated;
revoke all on function private.record_event(text, uuid, jsonb) from public, anon, authenticated;
revoke all on function private.touch_updated_at() from public, anon, authenticated;
revoke all on function private.guard_profile_columns() from public, anon, authenticated;

grant execute on function private.is_active() to authenticated;
grant execute on function private.is_superadmin() to authenticated;
grant execute on function private.has_admin_role() to authenticated;

-- -------------------------------------------------------------- profiles RLS --
alter table public.profiles enable row level security;

create policy profiles_select_self on public.profiles
    for select to authenticated
    using (user_id = auth.uid());

create policy profiles_select_superadmin on public.profiles
    for select to authenticated
    using (private.is_superadmin());

create policy profiles_select_admin on public.profiles
    for select to authenticated
    using (private.has_admin_role() and role <> 'superadmin');

revoke all on public.profiles from anon, authenticated;
grant select (user_id, display_name, role, status, created_at, updated_at, disabled_at)
    on public.profiles to authenticated;

-- ------------------------------------------------------------------ invites --
create table public.account_invitations (
    id uuid primary key default gen_random_uuid(),
    role public.account_role not null,
    token_hash bytea not null unique,
    label text,
    created_by uuid not null references auth.users (id) on delete cascade,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    claimed_by uuid references auth.users (id) on delete set null,
    claimed_at timestamptz,
    revoked_by uuid references auth.users (id) on delete set null,
    revoked_at timestamptz,
    constraint account_invitations_role_check check (role in ('user', 'admin')),
    constraint account_invitations_expiry_check check (expires_at > created_at),
    constraint account_invitations_label_check check (
        label is null or (char_length(label) between 1 and 120 and label = btrim(label))
    ),
    constraint account_invitations_claim_check check ((claimed_by is null) = (claimed_at is null)),
    constraint account_invitations_revocation_check check ((revoked_by is null) = (revoked_at is null))
);

comment on table public.account_invitations is
  'Unbound single-use invitation links. Only the SHA-256 hash of the token is stored.';

create index account_invitations_created_by_idx on public.account_invitations (created_by);

create function private.enforce_invitation_lifetime()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    if new.role = 'user' then
        if new.expires_at > new.created_at + interval '24 hours' then
            raise exception 'user invitations are limited to 24 hours' using errcode = '22023';
        end if;
    elsif new.role = 'admin' then
        if new.expires_at > new.created_at + interval '1 hour' then
            raise exception 'administrator invitations are limited to 1 hour' using errcode = '22023';
        end if;
    end if;
    return new;
end;
$$;

create trigger account_invitations_lifetime
    before insert or update of role, expires_at on public.account_invitations
    for each row execute function private.enforce_invitation_lifetime();

create function private.guard_invitation_update()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    if new.token_hash is distinct from old.token_hash
       or new.created_by is distinct from old.created_by
       or new.created_at is distinct from old.created_at
       or new.role is distinct from old.role
       or new.label is distinct from old.label then
        raise exception 'invitation scope is immutable' using errcode = '42501';
    end if;
    if old.claimed_at is not null and new.claimed_at is distinct from old.claimed_at then
        raise exception 'a used invitation is immutable' using errcode = '42501';
    end if;
    if old.revoked_at is not null and new.revoked_at is distinct from old.revoked_at then
        raise exception 'a cancelled invitation is immutable' using errcode = '42501';
    end if;
    return new;
end;
$$;

create trigger account_invitations_guard
    before update on public.account_invitations
    for each row execute function private.guard_invitation_update();

alter table public.account_invitations enable row level security;

create policy invitations_select_issuer on public.account_invitations
    for select to authenticated
    using (created_by = auth.uid() and private.is_active());

create policy invitations_select_superadmin on public.account_invitations
    for select to authenticated
    using (private.is_superadmin());

revoke all on public.account_invitations from anon, authenticated;
grant select (id, role, label, created_by, created_at, expires_at,
              claimed_by, claimed_at, revoked_by, revoked_at)
    on public.account_invitations to authenticated;

-- --------------------------------------------------------------- events RLS --
alter table public.account_events enable row level security;

create policy account_events_select_superadmin on public.account_events
    for select to authenticated
    using (private.is_superadmin());

revoke all on public.account_events from anon, authenticated;
grant select (id, actor_id, subject_id, action, details, created_at)
    on public.account_events to authenticated;

-- ------------------------------------------------------- new auth user hook --
create function private.handle_new_auth_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_role public.account_role := 'user';
begin
    if new.raw_app_meta_data is not null and new.raw_app_meta_data ? 'role' then
        begin
            v_role := (new.raw_app_meta_data ->> 'role')::public.account_role;
        exception when invalid_text_representation then
            raise exception 'unrecognized account role in app metadata' using errcode = '22023';
        end;
    end if;

    insert into public.profiles (user_id, role, status)
    values (new.id, v_role, 'active')
    on conflict (user_id) do nothing;

    return new;
end;
$$;

revoke all on function private.handle_new_auth_user() from public, anon, authenticated;

create trigger hisabkhata_account_profile
    after insert on auth.users
    for each row execute function private.handle_new_auth_user();

-- ================================================================== RPCs ===

-- ------------------------------------------------------------- my_account ---
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

-- ------------------------------------------------------ update_display_name --
create function public.update_display_name(p_display_name text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_name text := nullif(btrim(coalesce(p_display_name, '')), '');
begin
    perform private.require_active();
    if v_name is null then
        raise exception 'display name is required' using errcode = '22023';
    end if;
    if char_length(v_name) > 80 then
        raise exception 'display name is too long' using errcode = '22023';
    end if;
    if v_name ~ '[[:cntrl:]]' then
        raise exception 'display name contains unsupported characters' using errcode = '22023';
    end if;

    update public.profiles p set display_name = v_name where p.user_id = auth.uid();
end;
$$;

revoke all on function public.update_display_name(text) from public, anon;
grant execute on function public.update_display_name(text) to authenticated;

-- --------------------------------------------------------- create_invitation --
create function public.create_invitation(
    p_role public.account_role,
    p_label text default null
)
returns table (
    invitation_id uuid,
    invitation_token text,
    expires_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_uid uuid := auth.uid();
    v_role public.account_role := private.current_role();
    v_lifetime interval;
    v_label text := nullif(btrim(coalesce(p_label, '')), '');
    v_token text;
    v_id uuid := gen_random_uuid();
    v_expires timestamptz;
begin
    perform private.require_active();
    perform private.require_verified_email();

    if p_role is null or p_role not in ('user', 'admin') then
        raise exception 'unsupported invitation role' using errcode = '22023';
    end if;

    if p_role = 'user' then
        if v_role not in ('admin', 'superadmin') then
            raise exception 'only administrators can invite users' using errcode = '42501';
        end if;
        v_lifetime := interval '24 hours';
    else
        if v_role <> 'superadmin' then
            raise exception 'only the super administrator can invite administrators'
                using errcode = '42501';
        end if;
        v_lifetime := interval '1 hour';
    end if;

    if p_label is not null and v_label is null then
        raise exception 'invitation label is blank' using errcode = '22023';
    end if;
    if v_label is not null and char_length(v_label) > 120 then
        raise exception 'invitation label is too long' using errcode = '22023';
    end if;

    v_token := encode(extensions.gen_random_bytes(32), 'hex');
    v_expires := now() + v_lifetime;

    insert into public.account_invitations
        (id, role, token_hash, label, created_by, created_at, expires_at)
    values
        (v_id, p_role, extensions.digest(v_token, 'sha256'), v_label, v_uid, now(), v_expires);

    perform private.record_event(
        'invitation_created',
        null,
        jsonb_build_object('invitation_id', v_id, 'role', p_role, 'expires_at', v_expires)
    );

    return query select v_id, v_token, v_expires;
end;
$$;

revoke all on function public.create_invitation(public.account_role, text) from public, anon;
grant execute on function public.create_invitation(public.account_role, text) to authenticated;

-- ---------------------------------------------------------- claim_invitation --
-- A successful claim always consumes the link, even when the holder already
-- has an equal or greater role, so a link can never be replayed by somebody
-- else afterwards.
create function public.claim_invitation(p_token text)
returns table (
    account_role public.account_role,
    display_name text
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
begin
    perform private.require_active();
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

    select p.role, p.display_name into v_current, v_name
    from public.profiles p where p.user_id = v_uid;

    update public.account_invitations i
       set claimed_by = v_uid, claimed_at = now()
     where i.id = v_inv.id;

    if v_current is null then
        raise exception 'account profile is missing' using errcode = '42501';
    end if;

    if v_current = 'superadmin' or (v_current = 'admin' and v_inv.role = 'user') then
        perform private.record_event(
            'invitation_claimed_without_change',
            v_uid,
            jsonb_build_object('invitation_id', v_inv.id, 'role', v_current)
        );
        return query select v_current, v_name;
        return;
    end if;

    update public.profiles p set role = v_inv.role where p.user_id = v_uid;

    perform private.record_event(
        'invitation_claimed',
        v_uid,
        jsonb_build_object('invitation_id', v_inv.id, 'role', v_inv.role)
    );

    return query select v_inv.role, v_name;
end;
$$;

revoke all on function public.claim_invitation(text) from public, anon;
grant execute on function public.claim_invitation(text) to authenticated;

-- --------------------------------------------------------- revoke_invitation --
create function public.revoke_invitation(p_invitation_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_uid uuid := auth.uid();
    v_inv public.account_invitations;
begin
    perform private.require_active();

    select i.* into v_inv
    from public.account_invitations i
    where i.id = p_invitation_id
    for update;

    if not found then
        raise exception 'invitation not found' using errcode = 'P0002';
    end if;

    if v_inv.created_by <> v_uid and not private.is_superadmin() then
        raise exception 'only the issuer or the super administrator can cancel an invitation'
            using errcode = '42501';
    end if;
    if v_inv.claimed_at is not null then
        raise exception 'a used invitation cannot be cancelled' using errcode = '22023';
    end if;
    if v_inv.revoked_at is not null then
        return;
    end if;

    update public.account_invitations i
       set revoked_by = v_uid, revoked_at = now()
     where i.id = v_inv.id;

    perform private.record_event(
        'invitation_revoked',
        null,
        jsonb_build_object('invitation_id', v_inv.id, 'role', v_inv.role)
    );
end;
$$;

revoke all on function public.revoke_invitation(uuid) from public, anon;
grant execute on function public.revoke_invitation(uuid) to authenticated;

-- --------------------------------------------------------- set_account_status --
create function public.set_account_status(p_user_id uuid, p_status public.account_status)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_uid uuid := auth.uid();
    v_role public.account_role;
begin
    perform private.require_active();

    if not private.is_superadmin() then
        raise exception 'only the super administrator can change account status'
            using errcode = '42501';
    end if;
    if p_user_id is null or p_user_id = v_uid then
        raise exception 'you cannot change your own account status' using errcode = '42501';
    end if;
    if p_status is null then
        raise exception 'account status is required' using errcode = '22023';
    end if;

    select p.role into v_role from public.profiles p where p.user_id = p_user_id for update;
    if not found then
        raise exception 'account not found' using errcode = 'P0002';
    end if;
    if v_role = 'superadmin' then
        raise exception 'super administrator accounts cannot be disabled' using errcode = '42501';
    end if;

    update public.profiles p
       set status = p_status,
           disabled_at = case when p_status = 'disabled' then now() else null end,
           disabled_by = case when p_status = 'disabled' then v_uid else null end
     where p.user_id = p_user_id;

    perform private.record_event(
        case when p_status = 'disabled' then 'account_disabled' else 'account_enabled' end,
        p_user_id,
        jsonb_build_object('role', v_role)
    );
end;
$$;

revoke all on function public.set_account_status(uuid, public.account_status) from public, anon;
grant execute on function public.set_account_status(uuid, public.account_status) to authenticated;

-- ------------------------------------------------------------ schema guards --
revoke create on schema public from anon, authenticated;
