-- ============================================================================
-- Local-only shim that lets the real Supabase migration run on a plain
-- PostgreSQL 17 server. It reproduces the hosted topology the migration
-- depends on:
--   * extensions schema with pgcrypto (hosted installs it there),
--   * auth schema owned by supabase_auth_admin with a users table shaped like
--     the hosted one (only the columns the foundation touches),
--   * auth.uid() implemented from the request.jwt.claims GUC exactly as the
--     hosted function does,
--   * anon / authenticated / service_role client roles.
-- This file is never applied to staging.
-- ============================================================================

create schema if not exists auth;
create schema if not exists extensions;

create extension if not exists pgcrypto with schema extensions;

-- Roles are cluster wide, so create them idempotently.
do $$
declare
    r record;
begin
    for r in
        select * from (values
            ('anon', false, false),
            ('authenticated', false, false),
            ('service_role', false, true),
            ('supabase_auth_admin', false, false)
        ) as v(name, login, bypass)
    loop
        if not exists (select 1 from pg_roles where rolname = r.name) then
            if r.bypass then
                execute format('create role %I nologin bypassrls', r.name);
            else
                execute format('create role %I nologin', r.name);
            end if;
        end if;
    end loop;
end;
$$;

grant usage on schema auth to anon, authenticated, service_role;
grant usage on schema extensions to anon, authenticated, service_role, supabase_auth_admin;

-- Hosted Supabase ships default privileges that hand every new object in the
-- public schema to the API roles, which is exactly why the migration has to
-- revoke them explicitly. Reproducing that here makes the local privilege
-- model identical to staging instead of accidentally stricter.
alter default privileges for role postgres in schema public
    grant all on tables to anon, authenticated, service_role;
alter default privileges for role postgres in schema public
    grant all on sequences to anon, authenticated, service_role;
alter default privileges for role postgres in schema public
    grant all on functions to anon, authenticated, service_role;

create table auth.users (
    instance_id uuid,
    id uuid primary key,
    aud varchar(255),
    role varchar(255),
    email varchar(255),
    encrypted_password varchar(255),
    email_confirmed_at timestamptz,
    invited_at timestamptz,
    confirmation_token varchar(255),
    confirmation_sent_at timestamptz,
    recovery_token varchar(255),
    recovery_sent_at timestamptz,
    email_change_token_new varchar(255),
    email_change varchar(255),
    email_change_sent_at timestamptz,
    last_sign_in_at timestamptz,
    raw_app_meta_data jsonb,
    raw_user_meta_data jsonb,
    is_super_admin boolean,
    created_at timestamptz,
    updated_at timestamptz,
    phone text unique default null,
    phone_confirmed_at timestamptz,
    banned_until timestamptz
);

create function auth.uid()
returns uuid
language sql
stable
as $$
    select coalesce(
        nullif(current_setting('request.jwt.claim.sub', true), ''),
        (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'sub')
    )::uuid
$$;

create function auth.role()
returns text
language sql
stable
as $$
    select coalesce(
        nullif(current_setting('request.jwt.claim.role', true), ''),
        (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'role')
    )
$$;

create function auth.email()
returns text
language sql
stable
as $$
    select coalesce(
        nullif(current_setting('request.jwt.claim.email', true), ''),
        (nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'email')
    )
$$;

grant execute on function auth.uid() to anon, authenticated, service_role;
grant execute on function auth.role() to anon, authenticated, service_role;
grant execute on function auth.email() to anon, authenticated, service_role;
