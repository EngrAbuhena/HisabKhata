-- ============================================================================
-- Atomic cash/loan linking.
--
-- Why this exists: `private.enforce_link_symmetry` is a DEFERRED constraint
-- trigger, which is correct for one transaction. PostgREST gives every HTTP
-- request its own transaction, so a client that creates a loan and its linked
-- cash row with two requests can never commit a symmetric pair: verified live
-- on staging, the second request fails with 23514 and the link is lost.
--
-- The Android app is offline-first and writes the loan, the cash row and the
-- link in one Room transaction, so it needs one atomic, idempotent server call.
-- `public.link_ledger_entries` does exactly that and nothing else:
--   * it never writes an amount the client did not send;
--   * it refuses rows the caller does not own (RLS plus explicit checks);
--   * RLS policies still apply, the function is security invoker;
--   * it can also clear a link (nulls on both sides).
-- ============================================================================

create function public.link_ledger_entries(
    p_loan_client_id uuid,
    p_cash_client_id uuid,
    p_linked boolean default true
)
returns table (loan_id uuid, cash_entry_id uuid)
language plpgsql
security invoker
set search_path = ''
as $$
declare
    v_loan public.loan_entries;
    v_cash public.cash_entries;
begin
    if not private.is_active() then
        raise exception 'account is not active' using errcode = '42501';
    end if;

    -- Reads go through RLS, so another account's rows behave exactly like
    -- missing rows here.
    select * into v_loan from public.loan_entries l
     where l.client_id = p_loan_client_id and l.owner_id = auth.uid();
    if not found then
        raise exception 'loan not found for this account' using errcode = 'P0002';
    end if;

    select * into v_cash from public.cash_entries c
     where c.client_id = p_cash_client_id and c.owner_id = auth.uid();
    if not found then
        raise exception 'cash entry not found for this account' using errcode = 'P0002';
    end if;

    if p_linked then
        -- Both statements run in this one transaction; the deferred symmetry
        -- triggers are satisfied at commit.
        update public.cash_entries c
           set linked_loan_entry_id = v_loan.id
         where c.id = v_cash.id;
        update public.loan_entries l
           set linked_cash_entry_id = v_cash.id
         where l.id = v_loan.id;
    else
        -- Unlink only this pair; a row already pointing elsewhere is left alone.
        update public.loan_entries l
           set linked_cash_entry_id = null
         where l.id = v_loan.id and l.linked_cash_entry_id = v_cash.id;
        update public.cash_entries c
           set linked_loan_entry_id = null
         where c.id = v_cash.id and c.linked_loan_entry_id = v_loan.id;
    end if;

    return query select v_loan.id, v_cash.id;
end;
$$;

comment on function public.link_ledger_entries(uuid, uuid, boolean) is
  'Atomically link (or unlink) one loan and one cash entry owned by the caller. '
  'Needed because PostgREST requests cannot share a transaction and the '
  'symmetry check is deferred.';

revoke all on function public.link_ledger_entries(uuid, uuid, boolean) from public, anon;
grant execute on function public.link_ledger_entries(uuid, uuid, boolean) to authenticated;

notify pgrst, 'reload schema';
