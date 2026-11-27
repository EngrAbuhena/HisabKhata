-- ============================================================================
-- Staging verification of the accounts foundation.
--
-- Exercises the deployed functions and policies as the real `authenticated`
-- role inside ONE transaction that is rolled back on purpose: the final
-- statement raises an exception carrying the pass count, which proves success
-- and guarantees staging is left exactly as it was.
--
-- Fixtures (auth users, invitations) therefore never persist.
-- ============================================================================

begin;

create schema tests;

create table tests.results (label text);

create function tests.ok(c boolean, l text) returns void
language plpgsql as $f$
begin
    if c is distinct from true then
        raise exception 'SELF-TEST FAILED: %', l;
    end if;
    insert into tests.results values (l);
end;
$f$;

create function tests.expect(q text, st text, l text) returns void
language plpgsql as $f$
begin
    begin
        execute q;
    exception when others then
        if SQLSTATE = st then
            insert into tests.results values (l);
            return;
        end if;
        raise exception 'SELF-TEST FAILED: % (got % / %, expected %)', l, SQLSTATE, SQLERRM, st;
    end;
    raise exception 'SELF-TEST FAILED: % (no error raised, expected %)', l, st;
end;
$f$;

create function tests.login(u uuid) returns void
language plpgsql as $f$
begin
    perform set_config('request.jwt.claim.sub', u::text, true);
    perform set_config('request.jwt.claim.role', 'authenticated', true);
    perform set_config('request.jwt.claims',
        json_build_object('sub', u, 'role', 'authenticated')::text, true);
end;
$f$;

grant usage on schema tests to authenticated;
grant execute on all functions in schema tests to authenticated;
grant select, insert on tests.results to authenticated;

-- --------------------------------------------------------------- fixtures --
insert into auth.users (id, aud, role, email, email_confirmed_at,
                        raw_app_meta_data, created_at, updated_at)
values
 ('a0000000-0000-4000-8000-000000000001', 'authenticated', 'authenticated', 'kh-super@example.test',  now(), '{}', now(), now()),
 ('a0000000-0000-4000-8000-000000000002', 'authenticated', 'authenticated', 'kh-admin@example.test',  now(), '{}', now(), now()),
 ('a0000000-0000-4000-8000-000000000003', 'authenticated', 'authenticated', 'kh-carol@example.test',  now(), '{}', now(), now()),
 ('a0000000-0000-4000-8000-000000000004', 'authenticated', 'authenticated', 'kh-dave@example.test',   now(), '{}', now(), now()),
 ('a0000000-0000-4000-8000-000000000005', 'authenticated', 'authenticated', 'kh-erin@example.test',   null, '{}', now(), now()),
 ('a0000000-0000-4000-8000-000000000007', 'authenticated', 'authenticated', 'kh-admin2@example.test', now(), '{}', now(), now());

select tests.ok((select count(*) = 6 from public.profiles
                  where user_id::text like 'a0000000-%'),
                'the auth trigger creates one profile per new account');
select tests.ok((select bool_and(role = 'user' and status = 'active') from public.profiles
                  where user_id::text like 'a0000000-%'),
                'new accounts default to an active user role');

update public.profiles set role = 'superadmin'
 where user_id = 'a0000000-0000-4000-8000-000000000001';
update public.profiles set role = 'admin'
 where user_id in ('a0000000-0000-4000-8000-000000000002',
                   'a0000000-0000-4000-8000-000000000007');

-- ------------------------------------------------------------ as a client --
set local role authenticated;

select tests.login('a0000000-0000-4000-8000-000000000003');
select tests.ok((select count(*) = 1 from (select user_id from public.profiles) s),
                'a user sees only its own profile');
select tests.ok((select account_role = 'user' and account_status = 'active' and email_verified
                   from public.my_account()),
                'a verified user reads its own account state');
select tests.expect('update public.profiles set role = ''superadmin'' where user_id = auth.uid()',
                    '42501', 'a client cannot change its own role');
select tests.expect('select public.create_invitation(''user'')', '42501',
                    'a user cannot invite anybody');
select tests.expect('select public.set_account_status(''a0000000-0000-4000-8000-000000000002'', ''disabled'')',
                    '42501', 'a user cannot disable accounts');
select tests.expect('select private.require_active()', '42501',
                    'internal helpers are not callable by clients');

select tests.login('a0000000-0000-4000-8000-000000000002');
select tests.expect('select public.create_invitation(''admin'')', '42501',
                    'an admin cannot invite an admin');
select tests.expect('select public.create_invitation(''superadmin'')', '22023',
                    'no super administrator invitation role exists');
select tests.expect('select public.set_account_status(''a0000000-0000-4000-8000-000000000004'', ''disabled'')',
                    '42501', 'an admin cannot disable accounts');

insert into tests.results
select 'an admin receives a one-use user link'
  from public.create_invitation('user', 'staging check');

select tests.ok((select expires_at between now() + interval '23 hours 59 minutes'
                                       and now() + interval '24 hours'
                   from public.account_invitations where label = 'staging check'),
                'user invitations expire after 24 hours');
select tests.ok((select claimed_at is null and revoked_at is null
                   from public.account_invitations where label = 'staging check'),
                'the invitation is unbound and unused');
select tests.expect('select token_hash from public.account_invitations', '42501',
                    'clients cannot read the stored token hash');
select tests.ok((select bool_and(created_by = 'a0000000-0000-4000-8000-000000000002')
                   from public.account_invitations),
                'an admin sees only its own invitations');

select tests.login('a0000000-0000-4000-8000-000000000001');
select tests.ok((select count(*) = 6 from (select user_id from public.profiles) s),
                'the super administrator sees every account');
insert into tests.results
select 'the super administrator issues a one-hour admin link'
  from public.create_invitation('admin');
select tests.ok((select expires_at between now() + interval '59 minutes'
                                       and now() + interval '60 minutes'
                   from public.account_invitations
                  where role = 'admin' and claimed_at is null),
                'administrator invitations expire after 1 hour');
select tests.ok((select count(*) > 0 from (select id from public.account_events) s),
                'the super administrator sees the audit trail');

select tests.login('a0000000-0000-4000-8000-000000000005');
select tests.expect('select public.claim_invitation(repeat(''ab'', 32))', '42501',
                    'an unverified account cannot claim an invitation');

select tests.login('a0000000-0000-4000-8000-000000000007');
select tests.ok((select count(*) = 0 from (select id from public.account_invitations) s),
                'another admin sees no foreign invitations');
select tests.expect('select public.revoke_invitation(''00000000-0000-4000-8000-00000000ffff'')',
                    'P0002', 'an unknown invitation reports not found');

select tests.login('a0000000-0000-4000-8000-000000000003');
select tests.ok((select count(*) = 0 from (select id from public.account_events) s),
                'a user sees no audit trail');

-- --------------------------------------------- account status lifecycle ----
select tests.login('a0000000-0000-4000-8000-000000000001');
select tests.expect('select public.set_account_status(''a0000000-0000-4000-8000-000000000001'', ''disabled'')',
                    '42501', 'the super administrator cannot disable itself');
select tests.expect('select public.set_account_status(''00000000-0000-4000-8000-00000000ffff'', ''disabled'')',
                    'P0002', 'an unknown account reports not found');
select public.set_account_status('a0000000-0000-4000-8000-000000000004', 'disabled');
reset role;

select tests.ok((select status = 'disabled' and disabled_at is not null
                        and disabled_by = 'a0000000-0000-4000-8000-000000000001'
                   from public.profiles where user_id = 'a0000000-0000-4000-8000-000000000004'),
                'disabling records who did it and when');
select tests.ok((select count(*) = 1 from public.profiles
                  where user_id = 'a0000000-0000-4000-8000-000000000004'),
                'a disabled account keeps its data and profile row');

set local role authenticated;
select tests.login('a0000000-0000-4000-8000-000000000004');
select tests.ok((select account_status = 'disabled' from public.my_account()),
                'a disabled account can still learn that it is disabled');
select tests.expect('select public.create_invitation(''user'')', '42501',
                    'a disabled account cannot invite');
select tests.expect('select public.update_display_name(''Dave'')', '42501',
                    'a disabled account cannot edit its profile');

select tests.login('a0000000-0000-4000-8000-000000000001');
select public.set_account_status('a0000000-0000-4000-8000-000000000004', 'active');
reset role;

select tests.ok((select status = 'active' and disabled_at is null and disabled_by is null
                   from public.profiles where user_id = 'a0000000-0000-4000-8000-000000000004'),
                're-enabling restores the account');
select tests.ok((select count(*) = 6 from public.profiles
                  where user_id::text like 'a0000000-%'),
                'enabling never deletes an account');

-- ---------------------------------------------------------------- summary --
do $f$
declare
    n integer;
begin
    select count(*) into n from tests.results;
    raise exception 'STAGING SELF-TEST PASSED: % checks, transaction rolled back', n;
end;
$f$;
