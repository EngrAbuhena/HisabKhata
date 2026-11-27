-- ============================================================================
-- Reconcile the notes objects with the tested definition.
--
-- Staging held an earlier draft of the notes table:
--   * SELECT policy allowed only the author, so public notes were unreadable
--   * owner_id was granted to clients, exposing author identity
--   * the notes_feed view was missing
--
-- The table held zero rows, so this replaces the objects rather than migrating
-- data. Everything here is idempotent and safe to run on a fresh database,
-- which keeps the local test harness and staging on one definition.
-- ============================================================================

drop view if exists public.notes_feed;
drop table if exists public.notes cascade;

create table public.notes (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null default auth.uid()
        references auth.users (id) on delete cascade,
    client_id uuid not null default gen_random_uuid(),
    title text not null default '',
    body text not null default '',
    is_public boolean not null default false,
    show_author boolean not null default false,
    pinned boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    constraint notes_title_check check (
        char_length(title) <= 200
        and title = btrim(title)
        and title !~ '[[:cntrl:]]'
    ),
    constraint notes_body_check check (
        char_length(body) <= 100000
        and body !~ '[[:cntrl:]]'
    ),
    constraint notes_not_empty_check check (
        btrim(title) <> '' or btrim(body) <> ''
    ),
    constraint notes_client_id_unique unique (owner_id, client_id),
    -- A private note can never be marked as showing its author.
    constraint notes_show_author_requires_public check (not show_author or is_public)
);

comment on table public.notes is
  'Notepad entries. Private unless is_public; author identity is never exposed.';
comment on column public.notes.owner_id is
  'Author. Never granted to clients; used by RLS and by the feed view only.';
comment on column public.notes.show_author is
  'Per-note author visibility. Ignored (always hidden) for SuperAdmin authors.';

create index notes_owner_idx on public.notes (owner_id, deleted_at, updated_at desc);
create index notes_public_feed_idx on public.notes (created_at desc)
    where is_public and deleted_at is null;

create trigger notes_touch_updated_at
    before update on public.notes
    for each row execute function private.touch_row_updated_at();

create trigger notes_guard_owner
    before update on public.notes
    for each row execute function private.guard_ledger_owner();

alter table public.notes enable row level security;

-- Read: your own notes, plus public notes written by anybody.
create policy notes_select_own_or_public on public.notes
    for select to authenticated
    using (
        private.is_active()
        and (owner_id = auth.uid() or (is_public and deleted_at is null))
    );

create policy notes_insert_own on public.notes
    for insert to authenticated
    with check (owner_id = auth.uid() and private.is_active());

-- Write: the author only. There is no policy letting a reader modify a public
-- note, so such writes match zero rows.
create policy notes_update_own on public.notes
    for update to authenticated
    using (owner_id = auth.uid() and private.is_active())
    with check (owner_id = auth.uid() and private.is_active());

create policy notes_delete_own on public.notes
    for delete to authenticated
    using (owner_id = auth.uid() and private.is_active());

-- Column grants deliberately omit owner_id so no client can read or write it.
revoke all on public.notes from anon, authenticated;
grant select (id, client_id, title, body, is_public, show_author, pinned,
              created_at, updated_at, deleted_at)
    on public.notes to authenticated;
grant insert (id, client_id, owner_id, title, body, is_public, show_author, pinned)
    on public.notes to authenticated;
grant update (title, body, is_public, show_author, pinned, deleted_at)
    on public.notes to authenticated;
grant delete on public.notes to authenticated;

-- ------------------------------------------------------------- feed view ----
-- The only place author information can appear. This is a definer view, so the
-- WHERE clause below IS the security boundary and repeats every rule instead of
-- inheriting the table's policies.
create view public.notes_feed
with (security_barrier = true)
as
select
    n.id,
    n.client_id,
    n.title,
    n.body,
    n.is_public,
    n.show_author,
    n.pinned,
    n.created_at,
    n.updated_at,
    (n.owner_id = auth.uid()) as is_mine,
    case
        -- Hidden for every SuperAdmin-authored note, whatever the flag says.
        when p.role = 'superadmin' then null
        -- Shown only for a public note whose author opted in.
        when n.is_public and n.show_author then coalesce(p.display_name, 'User')
        else null
    end as author_display_name
from public.notes n
join public.profiles p on p.user_id = n.owner_id
where private.is_active()
  and n.deleted_at is null
  and (n.owner_id = auth.uid() or n.is_public);

comment on view public.notes_feed is
  'Readable feed of your own notes plus public notes, with author identity masked.';

revoke all on public.notes_feed from anon, authenticated;
grant select on public.notes_feed to authenticated;

notify pgrst, 'reload schema';
