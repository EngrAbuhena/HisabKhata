-- ============================================================================
-- Acceptance tests for notes and the public-note feed.
--
-- The security questions: can a stranger read a private note, can a reader edit
-- somebody else's public note, and can anybody learn who wrote a note when the
-- author asked to stay hidden (and always when the author is a SuperAdmin)?
--
-- Runs as the real `authenticated` role with JWT claims. Rolled back.
-- ============================================================================

\set ON_ERROR_STOP 1
\pset format unaligned
\pset tuples_only on
\pset pager off

begin;

create schema tests;

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
    ('bbbbbbbb-0000-4000-8000-000000000001', 'author@example.test',  now()),
    ('bbbbbbbb-0000-4000-8000-000000000002', 'reader@example.test',  now()),
    ('bbbbbbbb-0000-4000-8000-000000000003', 'super@example.test',   now()),
    ('bbbbbbbb-0000-4000-8000-000000000004', 'disabled@example.test', now());

select private.set_account_role('bbbbbbbb-0000-4000-8000-000000000003', 'superadmin');
update public.profiles
   set display_name = 'Author Name'
 where user_id = 'bbbbbbbb-0000-4000-8000-000000000001';
update public.profiles
   set display_name = 'Super Person'
 where user_id = 'bbbbbbbb-0000-4000-8000-000000000003';
update public.profiles set status = 'disabled', disabled_at = now()
 where user_id = 'bbbbbbbb-0000-4000-8000-000000000004';

-- ==================================================== 1. authoring notes =====
set local role authenticated;
select tests.login('bbbbbbbb-0000-4000-8000-000000000001');

insert into public.notes (id, client_id, title, body, pinned)
values ('11111111-1111-4111-8111-111111111101',
        'c0000000-0000-4000-8000-000000000101',
        'Shop list', 'Rice, oil, salt', true);

select tests.assert(
    (select is_public = false and show_author = false from public.notes
      where id = '11111111-1111-4111-8111-111111111101'),
    'a new note is private and hides its author by default');
select tests.assert(
    (select title = 'Shop list' and body = 'Rice, oil, salt' and pinned
       from public.notes where id = '11111111-1111-4111-8111-111111111101'),
    'title, body and pin round-trip');

-- Author identity is not readable even by the author through the table.
select tests.expect('select owner_id from public.notes', '42501',
    'author identity is not a selectable column');

-- An author cannot mark a private note as showing them.
select tests.expect(
    'update public.notes set show_author = true where id = ''11111111-1111-4111-8111-111111111101''',
    '23514', 'a private note cannot opt in to showing its author');

-- Empty notes and oversized text are refused.
select tests.expect(
    'insert into public.notes (title, body) values (''   '', ''   '')',
    '23514', 'a completely empty note is refused');
select tests.expect(
    'insert into public.notes (title) values (repeat(''x'', 201))',
    '23514', 'an over-long title is refused');

-- ================================================= 2. private stays private ==
select tests.login('bbbbbbbb-0000-4000-8000-000000000002');
select tests.assert((select count(*) = 0 from (select id from public.notes) s),
    'a private note is invisible to another account');
select tests.assert(
    (select count(*) = 0 from (select id from public.notes
                                where id = '11111111-1111-4111-8111-111111111101') s),
    'a known private note id is invisible too');
select tests.assert((select count(*) = 0 from (select id from public.notes_feed) s),
    'the feed hides private notes');

-- ============================================ 3. public notes are readable ==
select tests.login('bbbbbbbb-0000-4000-8000-000000000001');
insert into public.notes (id, client_id, title, body, is_public, show_author)
values ('11111111-1111-4111-8111-111111111102',
        'c0000000-0000-4000-8000-000000000102',
        'Public tip', 'Count the change twice', true, true);

select tests.login('bbbbbbbb-0000-4000-8000-000000000002');
select tests.assert(
    (select count(*) = 1 from (select id from public.notes
                                where id = '11111111-1111-4111-8111-111111111102') s),
    'a public note is readable by another signed-in account');
select tests.assert(
    (select is_mine = false and author_display_name = 'Author Name'
       from public.notes_feed
      where id = '11111111-1111-4111-8111-111111111102'),
    'a public note with the author shown carries the display name');

-- Read-only: a reader cannot change or delete somebody else's public note.
do $$
declare
    v_rows integer;
begin
    update public.notes set body = 'defaced'
     where id = '11111111-1111-4111-8111-111111111102';
    get diagnostics v_rows = row_count;
    if v_rows <> 0 then
        raise exception 'ASSERTION FAILED: a reader edited a public note';
    end if;
    raise notice 'ok  a reader cannot edit a public note';

    delete from public.notes
     where id = '11111111-1111-4111-8111-111111111102';
    get diagnostics v_rows = row_count;
    if v_rows <> 0 then
        raise exception 'ASSERTION FAILED: a reader deleted a public note';
    end if;
    raise notice 'ok  a reader cannot delete a public note';
end;
$$;

select tests.assert(
    (select body = 'Count the change twice' from public.notes
      where id = '11111111-1111-4111-8111-111111111102'),
    'the public note is untouched');

-- A reader cannot publish somebody else's private note, but may write their own.
do $$
declare
    v_rows integer;
begin
    update public.notes set is_public = true
     where id = '11111111-1111-4111-8111-111111111101';
    get diagnostics v_rows = row_count;
    if v_rows <> 0 then
        raise exception 'ASSERTION FAILED: another account published a private note';
    end if;
    raise notice 'ok  nobody else can publish a private note';
end;
$$;
select tests.expect(
    format('insert into public.notes (owner_id, title, body) values (%L, %L, %L)',
        'bbbbbbbb-0000-4000-8000-000000000001', 'Impersonated', 'x'),
    '42501', 'a note cannot be created in another account''s name');

-- ================================= 4. author visibility choices are honoured =
-- The author hides their name on a public note.
select tests.login('bbbbbbbb-0000-4000-8000-000000000001');
insert into public.notes (id, client_id, title, body, is_public, show_author)
values ('11111111-1111-4111-8111-111111111103',
        'c0000000-0000-4000-8000-000000000103',
        'Anonymous tip', 'Check the ledger weekly', true, false);

select tests.login('bbbbbbbb-0000-4000-8000-000000000002');
select tests.assert(
    (select author_display_name is null and is_public
       from public.notes_feed
      where id = '11111111-1111-4111-8111-111111111103'),
    'a hidden author is not named in the feed');

-- Nothing in the feed leaks an author id.
select tests.assert(
    (select count(*) = 0 from (
        select column_name from information_schema.columns
         where table_schema = 'public' and table_name = 'notes_feed'
           and column_name in ('owner_id', 'author_id', 'user_id')
     ) s),
    'the feed exposes no author identifier column');

-- ================================= 5. SuperAdmin authors are always hidden ==
select tests.login('bbbbbbbb-0000-4000-8000-000000000003');
insert into public.notes (id, client_id, title, body, is_public, show_author)
values ('11111111-1111-4111-8111-111111111104',
        'c0000000-0000-4000-8000-000000000104',
        'Notice from the owner', 'Back up before upgrading', true, true);

select tests.assert(
    (select show_author from public.notes
      where id = '11111111-1111-4111-8111-111111111104'),
    'the SuperAdmin note did record the show-author flag');

select tests.login('bbbbbbbb-0000-4000-8000-000000000002');
select tests.assert(
    (select author_display_name is null
       from public.notes_feed
      where id = '11111111-1111-4111-8111-111111111104'),
    'a SuperAdmin author is never named, even with show_author set');

select tests.login('bbbbbbbb-0000-4000-8000-000000000003');
select tests.assert(
    (select author_display_name is null
       from public.notes_feed
      where id = '11111111-1111-4111-8111-111111111104'),
    'the SuperAdmin author is hidden from itself in the feed as well');

-- ========================================== 6. search, pin and feed shape ===
select tests.login('bbbbbbbb-0000-4000-8000-000000000001');
insert into public.notes (client_id, title, body) values
    ('c0000000-0000-4000-8000-000000000105', 'Rice price', 'Ask Karim for the rate'),
    ('c0000000-0000-4000-8000-000000000106', 'Note', 'unrelated text');

select tests.assert(
    (select count(*) = 1 from (select id from public.notes
                                where title ilike '%rice%') s),
    'a title search finds the matching note only');
select tests.assert(
    (select count(*) = 1 from (select id from public.notes
                                where body ilike '%karim%') s),
    'a body search finds the matching note only');

-- The author sees its own notes plus every public note, and nothing else.
select tests.assert(
    (select count(*) = 6 from (select id from public.notes) s),
    'the author sees its own notes plus other authors'' public notes');
select tests.assert(
    (select count(*) = 3 from (select id from public.notes
                                where is_public and deleted_at is null) s),
    'public notes from all authors are visible');

-- Ordering used by the feed: pinned first, then newest.
select tests.assert(
    (select bool_and(ok) from (
        select (row_number() over (order by pinned desc, created_at desc)) is not null as ok
          from public.notes_feed
     ) s),
    'the feed can be ordered pinned-first then newest');

-- ================================================= 7. soft delete + undo ====
update public.notes set deleted_at = now()
 where id = '11111111-1111-4111-8111-111111111101';
select tests.assert(
    (select deleted_at is not null from public.notes
      where id = '11111111-1111-4111-8111-111111111101'),
    'a deleted note is kept with a deletion timestamp (undo possible)');
update public.notes set deleted_at = null
 where id = '11111111-1111-4111-8111-111111111101';
select tests.assert(
    (select deleted_at is null from public.notes
      where id = '11111111-1111-4111-8111-111111111101'),
    'undo restores the note');

-- A deleted public note disappears from the feed.
select tests.login('bbbbbbbb-0000-4000-8000-000000000003');
update public.notes set deleted_at = now()
 where id = '11111111-1111-4111-8111-111111111104';
select tests.login('bbbbbbbb-0000-4000-8000-000000000002');
select tests.assert(
    (select count(*) = 0 from (select id from public.notes_feed
                                where id = '11111111-1111-4111-8111-111111111104') s),
    'a deleted public note leaves the feed');

-- ================================================= 8. disabled + anonymous ===
reset role;
set local role authenticated;
select tests.login('bbbbbbbb-0000-4000-8000-000000000004');
select tests.assert((select count(*) = 0 from (select id from public.notes) s),
    'a disabled account reads no notes');
select tests.assert((select count(*) = 0 from (select id from public.notes_feed) s),
    'a disabled account reads no feed');
select tests.expect(
    'insert into public.notes (title) values (''blocked'')',
    '42501', 'a disabled account cannot write a note');

set local role anon;
select tests.expect('select * from public.notes', '42501',
    'an anonymous caller cannot read notes');
select tests.expect('select * from public.notes_feed', '42501',
    'an anonymous caller cannot read the feed');
reset role;

\echo ''
\echo 'ALL NOTES TESTS PASSED'

rollback;

