-- ============================================================================
-- Acceptance tests for the private ledger tables (people, loan_entries,
-- cash_entries).
--
-- The point of this suite: prove that a ledger is private to its owner, that
-- even the SuperAdmin cannot read another account's rows, and that money, dates
-- and cash/loan links survive as the app stores them.
--
-- Runs as the real `authenticated` role with JWT claims. Rolled back.
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

grant usage on schema tests to public;
grant execute on all functions in schema tests to public;

-- ------------------------------------------------------------- accounts -----
insert into auth.users (id, email, email_confirmed_at) values
    ('aaaaaaaa-0000-4000-8000-000000000001', 'owner-a@example.test', now()),
    ('aaaaaaaa-0000-4000-8000-000000000002', 'owner-b@example.test', now()),
    ('aaaaaaaa-0000-4000-8000-000000000003', 'super@example.test',   now()),
    ('aaaaaaaa-0000-4000-8000-000000000004', 'disabled@example.test', now());

select private.set_account_role('aaaaaaaa-0000-4000-8000-000000000003', 'superadmin');
update public.profiles set status = 'disabled', disabled_at = now()
 where user_id = 'aaaaaaaa-0000-4000-8000-000000000004';

-- ================================================= 1. owner writes a ledger ==
set local role authenticated;
select tests.login('aaaaaaaa-0000-4000-8000-000000000001');

-- Person, with an app-supplied stable client id (retry-safe upsert key).
insert into public.people (id, client_id, name, phone, note, type)
values ('11111111-1111-4111-8111-111111111111',
        'c0000000-0000-4000-8000-000000000001',
        'Karim Mia', '01700000000', 'shopkeeper', 'lent');

select tests.assert(
    (select owner_id = 'aaaaaaaa-0000-4000-8000-000000000001' from public.people
      where id = '11111111-1111-4111-8111-111111111111'),
    'the owner is taken from the token, not the request');
select tests.assert(
    (select name = 'Karim Mia' and phone = '01700000000' and type = 'lent'
       from public.people where id = '11111111-1111-4111-8111-111111111111'),
    'a person round-trips with name, phone and type');

-- Money stays exact integer paisa.
insert into public.loan_entries
    (id, client_id, person_id, entry_date, loan, paid, description, created_at_ms)
values ('22222222-2222-4222-8222-222222222222',
        'c0000000-0000-4000-8000-000000000002',
        '11111111-1111-4111-8111-111111111111',
        20321, 123456789, 0, 'lent for stock', 1700000000000);

select tests.assert(
    (select loan = 123456789 and paid = 0 from public.loan_entries
      where id = '22222222-2222-4222-8222-222222222222'),
    'a loan amount is stored as an exact bigint paisa value');
select tests.assert(
    (select entry_date = 20321 and created_at_ms = 1700000000000
       from public.loan_entries where id = '22222222-2222-4222-8222-222222222222'),
    'epoch-day date and epoch-millisecond created time are preserved');

-- ============================================ 2. cross-account invisibility =
select tests.login('aaaaaaaa-0000-4000-8000-000000000002');
select tests.assert((select count(*) = 0 from (select id from public.people) s),
    'another account sees none of the first account''s people');
select tests.assert((select count(*) = 0 from (select id from public.loan_entries) s),
    'another account sees none of the first account''s loans');
select tests.assert((select count(*) = 0 from (select id from public.cash_entries) s),
    'another account sees none of the first account''s cash rows');

-- Even the SuperAdmin cannot read another account's ledger.
select tests.login('aaaaaaaa-0000-4000-8000-000000000003');
select tests.assert((select count(*) = 0 from (select id from public.people) s),
    'the SuperAdmin cannot read another account''s people');
select tests.assert((select count(*) = 0 from (select id from public.loan_entries) s),
    'the SuperAdmin cannot read another account''s loans');
select tests.assert((select count(*) = 0 from (select id from public.cash_entries) s),
    'the SuperAdmin cannot read another account''s cash rows');

-- A direct query for a known id returns nothing at all.
select tests.assert(
    (select count(*) = 0 from (select id from public.loan_entries
                                where id = '22222222-2222-4222-8222-222222222222') s),
    'a known ledger id is invisible to the SuperAdmin');

-- ============================================ 3. cannot write into others ===
select tests.expect(
    format('insert into public.people (name, type, owner_id) values (%L, %L, %L)',
        'Plant', 'lent', 'aaaaaaaa-0000-4000-8000-000000000001'),
    '42501', 'a client cannot create a person owned by somebody else');

select tests.expect(
    format('insert into public.loan_entries (person_id, entry_date, loan, created_at_ms, owner_id) values (%L, 20321, 500, 1700000000000, %L)',
        '11111111-1111-4111-8111-111111111111',
        'aaaaaaaa-0000-4000-8000-000000000001'),
    '42501', 'a client cannot insert a loan into another account''s ledger');

-- RLS filters silently on UPDATE/DELETE: the statement succeeds but touches
-- zero rows. Assert that nothing changed rather than expecting an error.
do $$
declare
    v_rows integer;
begin
    update public.people set name = 'Hijacked'
     where id = '11111111-1111-4111-8111-111111111111';
    get diagnostics v_rows = row_count;
    if v_rows <> 0 then
        raise exception 'ASSERTION FAILED: another account''s person was updated';
    end if;
    raise notice 'ok  a client cannot update another account''s person';

    delete from public.loan_entries
     where id = '22222222-2222-4222-8222-222222222222';
    get diagnostics v_rows = row_count;
    if v_rows <> 0 then
        raise exception 'ASSERTION FAILED: another account''s loan was deleted';
    end if;
    raise notice 'ok  a client cannot delete another account''s loan';
end;
$$;
reset role;
select tests.assert(
    (select name = 'Karim Mia' from public.people
      where id = '11111111-1111-4111-8111-111111111111'),
    'the target row is untouched after the blocked attempts');
select tests.assert(
    (select count(*) = 1 from public.loan_entries
      where id = '22222222-2222-4222-8222-222222222222'),
    'the loan still exists after the blocked delete');
set local role authenticated;
select tests.login('aaaaaaaa-0000-4000-8000-000000000002');

-- ================================================ 4. ownership is immutable =
select tests.login('aaaaaaaa-0000-4000-8000-000000000001');
select tests.expect(
    format('update public.people set owner_id = %L where id = %L',
        'aaaaaaaa-0000-4000-8000-000000000002',
        '11111111-1111-4111-8111-111111111111'),
    '42501', 'a row cannot be re-pointed at another account');

select tests.expect(
    format('update public.loan_entries set owner_id = %L where id = %L',
        'aaaaaaaa-0000-4000-8000-000000000002',
        '22222222-2222-4222-8222-222222222222'),
    '42501', 'a loan cannot be re-pointed at another account');

-- ================================================== 5. cash/loan linking ====
insert into public.cash_entries
    (id, client_id, entry_date, deposit, cost, description, created_at_ms,
     linked_loan_entry_id)
values ('33333333-3333-4333-8333-333333333333',
        'c0000000-0000-4000-8000-000000000003',
        20321, 123456789, 0, 'linked cash for loan', 1700000000001,
        '22222222-2222-4222-8222-222222222222');

-- The loan side must now point back, and the pair is accepted at commit.
update public.loan_entries
   set linked_cash_entry_id = '33333333-3333-4333-8333-333333333333'
 where id = '22222222-2222-4222-8222-222222222222';

select tests.assert(
    (select count(*) = 1 from (select l.id from public.loan_entries l
                                join public.cash_entries c
                                  on c.linked_loan_entry_id = l.id
                               where l.linked_cash_entry_id = c.id) s),
    'a symmetric cash/loan link is stored and readable');

-- Removing one side nulls the other (ON DELETE SET NULL).
delete from public.cash_entries where id = '33333333-3333-4333-8333-333333333333';
select tests.assert(
    (select linked_cash_entry_id is null from public.loan_entries
      where id = '22222222-2222-4222-8222-222222222222'),
    'deleting the cash row clears the loan''s pointer');

-- ============================================== 6. value and shape checks ====
select tests.expect(
    'insert into public.people (name, type) values (''   '', ''lent'')',
    '23514', 'a blank person name is rejected');
select tests.expect(
    'insert into public.people (name, type) values (''Ayesha '', ''lent'')',
    '23514', 'an untrimmed person name is rejected');
select tests.expect(
    'insert into public.people (name, type) select repeat(''x'', 121), ''lent''',
    '23514', 'an over-long person name is rejected');

select tests.expect(
    format('insert into public.loan_entries (person_id, entry_date, loan, created_at_ms) values (%L, 20321, -1, 1700000000000)',
        '11111111-1111-4111-8111-111111111111'),
    '23514', 'a negative loan amount is rejected');
select tests.expect(
    format('insert into public.loan_entries (person_id, entry_date, loan, paid, created_at_ms) values (%L, 20321, 0, 0, 1700000000000)',
        '11111111-1111-4111-8111-111111111111'),
    '23514', 'a loan row with no money at all is rejected');
select tests.expect(
    format('insert into public.loan_entries (person_id, entry_date, loan, created_at_ms) values (%L, 4000000, 500, 1700000000000)',
        '11111111-1111-4111-8111-111111111111'),
    '23514', 'an out-of-range epoch day is rejected');
select tests.expect(
    format('insert into public.cash_entries (entry_date, deposit, cost, created_at_ms) values (20321, -5, 0, 1700000000000)'),
    '23514', 'a negative cash amount is rejected');

-- Retried uploads cannot duplicate a row: client_id is unique per account.
select tests.expect(
    format('insert into public.people (client_id, name, type) values (%L, %L, %L)',
        'c0000000-0000-4000-8000-000000000001', 'Karim Mia again', 'lent'),
    '23505', 'a replayed client id cannot create a duplicate person');
select tests.assert(
    (select count(*) = 1 from (select id from public.people
                                where client_id = 'c0000000-0000-4000-8000-000000000001') s),
    'exactly one person exists for that client id');

-- ============================== 7. a disabled account writes nothing ========
reset role;
set local role authenticated;
select tests.login('aaaaaaaa-0000-4000-8000-000000000004');
select tests.assert((select count(*) = 0 from (select id from public.people) s),
    'a disabled account reads no ledger rows');
select tests.expect(
    format('insert into public.people (name, type) values (%L, %L)', 'Blocked', 'lent'),
    '42501', 'a disabled account cannot insert a person');
select tests.expect(
    format('insert into public.cash_entries (entry_date, deposit, created_at_ms) values (20321, 100, 1700000000000)'),
    '42501', 'a disabled account cannot insert a cash row');

-- ============================== 8. SuperAdmin keeps a private ledger ========
select tests.login('aaaaaaaa-0000-4000-8000-000000000003');
insert into public.people (name, type) values ('Super own person', 'borrowed');
select tests.assert(
    (select count(*) = 1 from (select id from public.people where name = 'Super own person') s),
    'the SuperAdmin can keep its own ledger');
select tests.assert(
    (select count(*) = 1 from (select id from public.people) s),
    'the SuperAdmin sees only its own ledger, never the other accounts''');

-- ============================== 9. links cannot cross accounts ==============
reset role;
select tests.assert(
    (select count(*) = 0 from public.cash_entries c
      join public.loan_entries l on l.id = c.linked_loan_entry_id
     where c.owner_id <> l.owner_id),
    'no stored link crosses two accounts');

-- Owner A keeps a cash row of its own for the next check.
set local role authenticated;
select tests.login('aaaaaaaa-0000-4000-8000-000000000001');
insert into public.cash_entries
    (id, entry_date, deposit, cost, description, created_at_ms)
values ('44444444-4444-4444-8444-444444444444', 20322, 900, 0, 'own cash', 1700000000002);

-- Owner B cannot link to a row it does not own: the symmetry/same-owner check
-- refuses it (the write is also invisible to B either way).
select tests.login('aaaaaaaa-0000-4000-8000-000000000002');
insert into public.people (id, name, type)
values ('55555555-5555-4555-8555-555555555555', 'Second owner person', 'borrowed');
insert into public.loan_entries
    (id, person_id, entry_date, loan, created_at_ms, linked_cash_entry_id)
values ('66666666-6666-4666-8666-666666666666',
        '55555555-5555-4555-8555-555555555555', 20322, 900, 1700000000003,
        '44444444-4444-4444-8444-444444444444');
select tests.expect('set constraints all immediate', '23514',
    'a link into another account''s ledger is refused');
reset role;
select tests.assert(
    (select linked_loan_entry_id is null from public.cash_entries
      where id = '44444444-4444-4444-8444-444444444444'),
    'the other account''s cash row was not re-pointed');
set local role authenticated;
select tests.login('aaaaaaaa-0000-4000-8000-000000000002');


-- ================================================ 10. anon sees nothing =====
set local role anon;
select tests.expect('select * from public.people', '42501',
    'an anonymous caller cannot read ledgers');
select tests.expect('select * from public.loan_entries', '42501',
    'an anonymous caller cannot read loans');
select tests.expect('select * from public.cash_entries', '42501',
    'an anonymous caller cannot read cash rows');
reset role;

\echo ''
\echo 'ALL PRIVATE LEDGER TESTS PASSED'

rollback;
