-- ============================================================================
-- Acceptance tests for the accounts foundation.
--
-- Accounts are created manually in the Supabase dashboard (no invitations,
-- no self-service sign-up). These tests mirror that: auth users are inserted
-- with the privileges of the project owner, then every client assertion runs
-- as the real `authenticated` role with JWT claims, exactly as PostgREST would.
--
-- Run with: scripts/run_local_accounts_tests.sh
-- Everything happens in one transaction and is rolled back.
-- ============================================================================

\set ON_ERROR_STOP 1
\pset format unaligned
\pset tuples_only on
\pset pager off

begin;

create schema tests;

create function tests.ok(label text) returns void
language plpgsql as $$
begin
    raise notice 'ok  %', label;
end;
$$;

create function tests.assert(condition boolean, label text) returns void
language plpgsql as $$
begin
    if condition is distinct from true then
        raise exception 'ASSERTION FAILED: %', label;
    end if;
    raise notice 'ok  %', label;
end;
$$;

-- Runs p_sql as the caller (SECURITY INVOKER) and requires the given SQLSTATE.
create function tests.expect(p_sql text, p_state text, p_label text) returns void
language plpgsql as $$
begin
    begin
        execute p_sql;
    exception when others then
        if SQLSTATE = p_state then
            raise notice 'ok  %', p_label;
            return;
        end if;
        raise exception 'ASSERTION FAILED: % (got SQLSTATE % / %, expected %)',
            p_label, SQLSTATE, SQLERRM, p_state;
    end;
    raise exception 'ASSERTION FAILED: % (statement succeeded, expected SQLSTATE %)',
        p_label, p_state;
end;
$$;

create function tests.login(p_user uuid) returns void
language plpgsql as $$
begin
    perform set_config('request.jwt.claim.sub', p_user::text, true);
    perform set_config('request.jwt.claim.role', 'authenticated', true);
    perform set_config('request.jwt.claims',
        json_build_object('sub', p_user, 'role', 'authenticated')::text, true);
end;
$$;

create function tests.logout() returns void
language plpgsql as $$
begin
    perform set_config('request.jwt.claim.sub', '', true);
    perform set_config('request.jwt.claim.role', 'anon', true);
    perform set_config('request.jwt.claims', '{"role":"anon"}', true);
end;
$$;

grant usage on schema tests to public;
grant execute on all functions in schema tests to public;

-- ============================================================ 1. fixtures ===
-- Mirrors the owner adding users in the dashboard: rows in auth.users only.
insert into auth.users (id, email, email_confirmed_at, raw_app_meta_data) values
    ('00000000-0000-4000-8000-000000000001', 'super@example.test',  now(), '{}'),
    ('00000000-0000-4000-8000-000000000008', 'super2@example.test', now(), '{}'),
    ('00000000-0000-4000-8000-000000000002', 'admin@example.test',  now(), '{}'),
    ('00000000-0000-4000-8000-000000000007', 'admin2@example.test', now(), '{}'),
    ('00000000-0000-4000-8000-000000000003', 'carol@example.test',  now(), '{}'),
    ('00000000-0000-4000-8000-000000000004', 'dave@example.test',   now(), '{}'),
    ('00000000-0000-4000-8000-000000000005', 'erin@example.test',   null, '{}'),
    ('00000000-0000-4000-8000-000000000009', 'heidi@example.test',  now(), '{}');

select tests.assert((select count(*) = 8 from (select user_id from public.profiles) s),
    'the auth trigger creates one profile per manually created account');
select tests.assert(
    (select bool_and(role = 'user' and status = 'active') from public.profiles),
    'a manually created account starts as an active user');
select tests.assert(
    (select bool_and(display_name is null) from public.profiles),
    'a manually created account starts with no display name');

-- A role in app metadata (server written) is honoured; user metadata is ignored.
insert into auth.users (id, email, email_confirmed_at, raw_app_meta_data, raw_user_meta_data)
values ('00000000-0000-4000-8000-00000000000a', 'meta@example.test', now(),
        '{"role":"admin"}', '{"role":"superadmin"}');
select tests.assert(
    (select role = 'admin' from public.profiles
      where user_id = '00000000-0000-4000-8000-00000000000a'),
    'role comes from app metadata and ignores user-controlled metadata');
delete from auth.users where id = '00000000-0000-4000-8000-00000000000a';

-- An unrecognised app-metadata role aborts provisioning instead of guessing.
do $$
begin
    begin
        insert into auth.users (id, email, email_confirmed_at, raw_app_meta_data)
        values ('00000000-0000-4000-8000-00000000000b', 'bogus@example.test', now(),
                '{"role":"root"}');
        raise exception 'ASSERTION FAILED: unknown app metadata role accepted';
    exception when others then
        if SQLERRM like 'ASSERTION FAILED%' then
            raise;
        end if;
        raise notice 'ok  unknown app metadata role is rejected';
    end;
end;
$$;
select tests.assert(
    (select count(*) = 0 from (select user_id from public.profiles
                                where user_id = '00000000-0000-4000-8000-00000000000b') s),
    'a rejected account leaves no profile behind');

-- Owner assigns roles (dashboard) the only way they are ever assigned.
-- The very first SuperAdmin uses the dedicated bootstrap path.
select private.bootstrap_superadmin('00000000-0000-4000-8000-000000000001');
select private.set_account_role('00000000-0000-4000-8000-000000000008', 'superadmin');
select private.set_account_role('00000000-0000-4000-8000-000000000002', 'admin');
select private.set_account_role('00000000-0000-4000-8000-000000000007', 'admin');

select tests.assert(
    (select count(*) = 2 from public.profiles where role = 'superadmin'),
    'the owner can grant SuperAdmin');
select tests.assert(
    (select count(*) = 2 from public.profiles where role = 'admin'),
    'the owner can grant Admin');

-- ======================================================= 2. privilege surface =
set local role authenticated;
select tests.login('00000000-0000-4000-8000-000000000003');

select tests.expect(
    'insert into public.profiles (user_id, role) values (gen_random_uuid(), ''superadmin'')',
    '42501', 'a client cannot insert profiles');
select tests.expect(
    'update public.profiles set role = ''superadmin'' where user_id = auth.uid()',
    '42501', 'a client cannot update its own role');
select tests.expect(
    'delete from public.profiles where user_id = auth.uid()',
    '42501', 'a client cannot delete profiles');
select tests.assert(
    (select count(*) = 0 from (select id from public.account_events) s),
    'an ordinary client sees no audit rows');
select tests.expect('select private.require_active()',
    '42501', 'internal helpers are not callable by clients');
select tests.expect('select private.set_account_role(auth.uid(), ''superadmin'')',
    '42501', 'the owner-only role function is not callable by clients');
select tests.expect('select private.bootstrap_superadmin(auth.uid())',
    '42501', 'the owner-only bootstrap is not callable by clients');
select tests.expect('create table public.leak (id int)',
    '42501', 'clients cannot create objects in the public schema');
select tests.expect('select * from public.account_invitations', '42P01',
    'the cancelled invitation table no longer exists');

-- The invitation RPCs are gone, not merely restricted.
select tests.expect('select public.create_invitation(''user'')', '42883',
    'invitation creation no longer exists');
select tests.expect('select * from public.claim_invitation(''x'')', '42883',
    'invitation claiming no longer exists');

select tests.assert((select count(*) = 1 from (select user_id from public.profiles) s),
    'a user sees exactly one profile row');
reset role;

-- Defence in depth: even with UPDATE granted (and RLS bypassed) the guard
-- trigger refuses role and status changes, while ordinary edits still work.
create role app_writer nologin bypassrls;
grant usage on schema public to app_writer;
grant select, update on public.profiles to app_writer;
set local role app_writer;
update public.profiles set display_name = 'via app_writer'
 where user_id = '00000000-0000-4000-8000-000000000003';
select tests.assert(
    (select display_name = 'via app_writer' from public.profiles
      where user_id = '00000000-0000-4000-8000-000000000003'),
    'an ordinary profile edit still works, proving the trigger is the blocker');
select tests.expect(
    'update public.profiles set role = ''superadmin'' where user_id = ''00000000-0000-4000-8000-000000000003''',
    '42501', 'a role change is refused by the guard trigger even with UPDATE granted');
select tests.expect(
    'update public.profiles set status = ''disabled'' where user_id = ''00000000-0000-4000-8000-000000000003''',
    '42501', 'a status change is refused by the guard trigger even with UPDATE granted');
reset role;
revoke all on public.profiles from app_writer;
revoke all on schema public from app_writer;
drop role app_writer;

-- ========================================================= 3. account state ===
set local role anon;
select tests.expect('select * from public.my_account()', '42501',
    'anonymous callers cannot reach the account RPCs');
select tests.expect('select * from (select user_id from public.profiles) s', '42501',
    'anonymous callers cannot read profiles');
reset role;

select tests.logout();
select tests.expect('select * from public.my_account()', '28000',
    'the account RPC rejects callers without a token');

set local role authenticated;
select tests.login('00000000-0000-4000-8000-000000000003');
select tests.assert(
    (select account_role = 'user' and account_status = 'active' and email_verified
       from public.my_account()),
    'a manually created, verified account is immediately usable');
select tests.login('00000000-0000-4000-8000-000000000005');
select tests.assert(
    (select not email_verified from public.my_account()),
    'an unverified account is reported as unverified');

select tests.login('00000000-0000-4000-8000-000000000003');
select public.update_display_name('  Carol Test  ');
select tests.assert((select display_name = 'Carol Test' from public.my_account()),
    'a display name is trimmed and stored');
select tests.expect('select public.update_display_name(''   '')', '22023',
    'a blank display name is rejected');
select tests.expect('select public.update_display_name(repeat(''x'', 81))', '22023',
    'an over-long display name is rejected');
select tests.expect('select public.update_display_name(''Carol'' || chr(7) || ''Test'')',
    '22023', 'a display name with control characters is rejected');
reset role;

-- ===================================================== 4. account status =====
set local role authenticated;

select tests.login('00000000-0000-4000-8000-000000000002');
select tests.expect(
    format('select public.set_account_status(%L, %L)',
        '00000000-0000-4000-8000-000000000004', 'disabled'),
    '42501', 'an admin cannot disable accounts');

select tests.login('00000000-0000-4000-8000-000000000003');
select tests.expect(
    format('select public.set_account_status(%L, %L)',
        '00000000-0000-4000-8000-000000000002', 'disabled'),
    '42501', 'a user cannot disable accounts');

select tests.login('00000000-0000-4000-8000-000000000001');
select tests.expect(
    format('select public.set_account_status(%L, %L)',
        '00000000-0000-4000-8000-000000000001', 'disabled'),
    '42501', 'the super administrator cannot disable itself');
select tests.expect(
    format('select public.set_account_status(%L, %L)',
        '00000000-0000-4000-8000-000000000008', 'disabled'),
    '42501', 'a super administrator account cannot be disabled');
select tests.expect(
    format('select public.set_account_status(%L, %L)',
        '00000000-0000-4000-8000-00000000ffff', 'disabled'),
    'P0002', 'an unknown account reports not found');
select tests.expect(
    format('select public.set_account_status(%L, null)',
        '00000000-0000-4000-8000-000000000004'),
    '22023', 'a null status is rejected');

select public.set_account_status('00000000-0000-4000-8000-000000000004', 'disabled');
reset role;
select tests.assert(
    (select status = 'disabled' and disabled_at is not null
            and disabled_by = '00000000-0000-4000-8000-000000000001'
       from public.profiles where user_id = '00000000-0000-4000-8000-000000000004'),
    'disabling records who did it and when');
select tests.assert(
    (select count(*) = 1 from public.profiles
      where user_id = '00000000-0000-4000-8000-000000000004'),
    'a disabled account keeps its profile and data');

-- A disabled account loses every write path.
set local role authenticated;
select tests.login('00000000-0000-4000-8000-000000000004');
select tests.assert((select account_status = 'disabled' from public.my_account()),
    'a disabled account can still learn that it is disabled');
select tests.expect('select public.update_display_name(''Dave'')', '42501',
    'a disabled account cannot change its profile');
reset role;

set local role authenticated;
select tests.login('00000000-0000-4000-8000-000000000001');
select public.set_account_status('00000000-0000-4000-8000-000000000004', 'active');
reset role;
select tests.assert(
    (select status = 'active' and disabled_at is null and disabled_by is null
       from public.profiles where user_id = '00000000-0000-4000-8000-000000000004'),
    're-enabling restores active status and clears the disable trail');

-- A disabled admin loses administrative rights immediately.
set local role authenticated;
select tests.login('00000000-0000-4000-8000-000000000001');
select public.set_account_status('00000000-0000-4000-8000-000000000007', 'disabled');
reset role;
set local role authenticated;
select tests.login('00000000-0000-4000-8000-000000000007');
select tests.expect('select public.update_display_name(''Admin Two'')', '42501',
    'a disabled admin can no longer write');
select tests.assert((select account_status = 'disabled' from public.my_account()),
    'a disabled admin sees its own disabled status');
reset role;
set local role authenticated;
select tests.login('00000000-0000-4000-8000-000000000001');
select public.set_account_status('00000000-0000-4000-8000-000000000007', 'active');
reset role;

-- =============================================== 5. directory and privacy ===
set local role anon;
select tests.expect('select * from (select id from public.account_events) s', '42501',
    'anonymous callers cannot read the audit trail');
reset role;

set local role authenticated;
select tests.login('00000000-0000-4000-8000-000000000003');
select tests.assert(
    (select count(*) = 0 from (select user_id from public.profiles
                                where user_id <> '00000000-0000-4000-8000-000000000003') s),
    'a user cannot see another account');
select tests.assert((select count(*) = 0 from (select id from public.account_events) s),
    'a user sees no audit trail');

select tests.login('00000000-0000-4000-8000-000000000002');
select tests.assert(
    (select count(*) = 0 from (select user_id from public.profiles
                                where user_id = '00000000-0000-4000-8000-000000000001') s),
    'an admin cannot see the super administrator');
select tests.assert(
    (select count(*) = 1 from (select user_id from public.profiles
                                where user_id = '00000000-0000-4000-8000-000000000003') s),
    'an admin can see ordinary accounts');
select tests.assert((select count(*) = 0 from (select id from public.account_events) s),
    'an admin sees no audit trail');

select tests.login('00000000-0000-4000-8000-000000000001');
select tests.assert((select count(*) = 8 from (select user_id from public.profiles) s),
    'the super administrator sees every account');
select tests.assert((select count(*) > 0 from (select id from public.account_events) s),
    'the super administrator sees the audit trail');
select tests.assert(
    (select count(*) >= 4 from public.account_events where action = 'role_changed'),
    'owner role changes are audited');
select tests.assert(
    (select count(*) = 1 from public.account_events
      where action = 'superadmin_bootstrapped'
        and subject_id = '00000000-0000-4000-8000-000000000001'),
    'the first SuperAdmin bootstrap is audited');
reset role;

-- ================================================ 6. stable between calls ===
set local role authenticated;
select tests.login('00000000-0000-4000-8000-000000000002');
select tests.assert((select account_role = 'admin' from public.my_account()),
    'a role assigned by the owner persists across calls');
reset role;

-- ================================================== 7. no invite remnants ===
select tests.assert(
    (select count(*) = 0 from information_schema.tables
      where table_schema = 'public' and table_name = 'account_invitations'),
    'the invitation table is gone');
select tests.assert(
    (select count(*) = 0 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
      where p.proname in ('create_invitation', 'claim_invitation', 'revoke_invitation')),
    'the invitation functions are gone');
select tests.assert(
    (select count(*) = 0 from information_schema.columns
      where table_schema = 'public' and table_name = 'profiles'
        and column_name like 'activated%'),
    'the activation columns are gone');

\echo ''
\echo 'ALL ACCOUNTS FOUNDATION TESTS PASSED'

rollback;
