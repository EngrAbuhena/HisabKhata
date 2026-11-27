-- ============================================================================
-- Acceptance tests for public.link_ledger_entries.
--
-- Why: the app is offline-first and cannot rely on two REST requests sharing a
-- transaction, so linking a loan to its cash row must be one atomic server call.
-- This suite proves the call links, unlinks, is idempotent, keeps amounts, and
-- refuses rows that belong to another account (and anonymous callers).
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
        if sqlstate = p_state then
            raise notice 'ok  %', p_label;
            return;
        end if;
        raise exception 'ASSERTION FAILED: % (expected %, got % — %)',
            p_label, p_state, sqlstate, sqlerrm;
    end;
    raise exception 'ASSERTION FAILED: % (expected %, but the statement succeeded)',
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
    ('aaaaaaaa-0000-4000-8000-000000000001', 'fixture-a@example.test', now()),
    ('aaaaaaaa-0000-4000-8000-000000000002', 'fixture-b@example.test', now());

-- ===================================================== 1. owner A links ====
set local role authenticated;
select tests.login('aaaaaaaa-0000-4000-8000-000000000001');

insert into public.people (id, client_id, name, type)
values ('d0000000-0000-4000-8000-0000000000a1', 'a0000000-0000-4000-8000-0000000000a1',
        'Fixture Ann', 'lent');

insert into public.loan_entries
    (id, client_id, person_id, entry_date, loan, paid, description, created_at_ms)
values ('e0000000-0000-4000-8000-0000000000a1', 'a0000000-0000-4000-8000-0000000000b1',
        'd0000000-0000-4000-8000-0000000000a1', 20350, 123456789, 0, 'fixture loan',
        1794370000000);

insert into public.cash_entries
    (id, client_id, entry_date, deposit, cost, description, created_at_ms)
values ('f0000000-0000-4000-8000-0000000000a1', 'a0000000-0000-4000-8000-0000000000c1',
        20350, 0, 123456789, 'Fixture Ann - fixture loan', 1794370000000);

select tests.assert(
    (select linked_cash_entry_id is null and linked_loan_entry_id is null
       from public.loan_entries l, public.cash_entries c
      where l.id = 'e0000000-0000-4000-8000-0000000000a1'
        and c.id = 'f0000000-0000-4000-8000-0000000000a1'),
    'the pair starts unlinked when written as two separate rows');

select tests.assert(
    (select count(*) = 1
       from public.link_ledger_entries('a0000000-0000-4000-8000-0000000000b1',
                                       'a0000000-0000-4000-8000-0000000000c1')),
    'the link call succeeds in one statement');

select tests.assert(
    (select l.linked_cash_entry_id = c.id and c.linked_loan_entry_id = l.id
       from public.loan_entries l join public.cash_entries c
         on c.linked_loan_entry_id = l.id
      where l.id = 'e0000000-0000-4000-8000-0000000000a1'),
    'both directions of the link are stored');

select tests.assert(
    (select l.loan = 123456789 and l.paid = 0 and c.cost = 123456789 and c.deposit = 0
       from public.loan_entries l join public.cash_entries c
         on c.id = l.linked_cash_entry_id
      where l.id = 'e0000000-0000-4000-8000-0000000000a1'),
    'linking never changes an amount');

-- ============================================ 2. calling it again is safe ====
select public.link_ledger_entries('a0000000-0000-4000-8000-0000000000b1',
                                  'a0000000-0000-4000-8000-0000000000c1');
select tests.assert(
    (select count(*) = 1
       from public.loan_entries l join public.cash_entries c
         on c.linked_loan_entry_id = l.id
      where l.id = 'e0000000-0000-4000-8000-0000000000a1'
        and l.linked_cash_entry_id = c.id),
    'a retried link is idempotent');

-- =========================================================== 3. unlinking ====
select public.link_ledger_entries('a0000000-0000-4000-8000-0000000000b1',
                                  'a0000000-0000-4000-8000-0000000000c1', false);
select tests.assert(
    (select l.linked_cash_entry_id is null and c.linked_loan_entry_id is null
       from public.loan_entries l, public.cash_entries c
      where l.id = 'e0000000-0000-4000-8000-0000000000a1'
        and c.id = 'f0000000-0000-4000-8000-0000000000a1'),
    'unlinking clears both pointers');

-- An unlinked cash row can be deleted independently, as the editor expects.
delete from public.cash_entries where id = 'f0000000-0000-4000-8000-0000000000a1';
select tests.assert(
    (select count(*) = 0 from public.cash_entries
      where id = 'f0000000-0000-4000-8000-0000000000a1'),
    'an unlinked cash row can be removed on its own');

-- ====================================== 4. cross-account rows are refused ====
reset role;

insert into auth.users (id, email, email_confirmed_at)
values ('aaaaaaaa-0000-4000-8000-000000000003', 'fixture-c@example.test', now())
on conflict do nothing;

set local role authenticated;
select tests.login('aaaaaaaa-0000-4000-8000-000000000001');
insert into public.cash_entries
    (id, client_id, entry_date, deposit, cost, description, created_at_ms)
values ('f0000000-0000-4000-8000-0000000000a2', 'a0000000-0000-4000-8000-0000000000c2',
        20351, 500, 0, 'owner A cash', 1794370000001);
reset role;

set local role authenticated;
select tests.login('aaaaaaaa-0000-4000-8000-000000000002');
-- Owner B tries to link its own loan to owner A's cash row.
select tests.expect(
    'select public.link_ledger_entries(''a0000000-0000-4000-8000-0000000000b2'',
                                        ''a0000000-0000-4000-8000-0000000000c2'')',
    'P0002',
    'a link into another account''s ledger is refused');

select tests.expect(
    'select public.link_ledger_entries(''a0000000-0000-4000-8000-0000000000b1'',
                                        ''a0000000-0000-4000-8000-0000000000c1'')',
    'P0002',
    'another account''s loan is invisible to the caller');

reset role;
select tests.assert(
    (select linked_loan_entry_id is null from public.cash_entries
      where id = 'f0000000-0000-4000-8000-0000000000a2'),
    'the other account''s cash row was not re-pointed');

-- =============================================== 5. no anonymous access =====
-- Clear the authenticated claims first: `set local role anon` alone would still
-- expose the previous login through the session GUCs.
set local role anon;
select set_config('request.jwt.claim.sub', '', true);
select set_config('request.jwt.claim.role', 'anon', true);
select set_config('request.jwt.claims', '{"role":"anon"}', true);
select tests.expect(
    'select public.link_ledger_entries(''a0000000-0000-4000-8000-0000000000b1'',
                                        ''a0000000-0000-4000-8000-0000000000c1'')',
    '42501',
    'an anonymous caller cannot call the link function');

\echo ''
\echo 'ALL ATOMIC LINK TESTS PASSED'

rollback;
