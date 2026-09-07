-- ===========================================================================
-- V10: the return-role rename.
--
-- The outward name of the return role is settled. `handover` is the name of
-- the OUTWARD leg — the dispatch from concept to code — and the return path
-- carried the same word by accident, from an earlier draft that did not yet
-- distinguish the two. This migration renames the columns, the constraint
-- and the freeze trigger's field references to say `return` where they used
-- to say `handover`. Not touched: the ratification verb (`accept`), the
-- ratification timestamp (`ratified_at`), and the ratified status name
-- (`returned`) — all of which name the ACT rather than the role.
--
-- Additive to the chain, and non-destructive. In PostgreSQL a RENAME COLUMN
-- is a catalog change: no table rewrite, no data movement, no lock beyond the
-- brief metadata lock the ALTER holds. An image running the previous version
-- reads the new columns as unknown and stops writing — which is what the
-- release protocol expects, since the new image is what carries the code
-- calling the new names.
--
-- The runtime role's grant is table-level (V8), so nothing under it needs
-- adjusting; there is no column-level grant on either handover column.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. The columns
-- ---------------------------------------------------------------------------
ALTER TABLE dispatch.exchange RENAME COLUMN handover_body     TO return_body;
ALTER TABLE dispatch.exchange RENAME COLUMN handover_metadata TO return_metadata;

-- ---------------------------------------------------------------------------
-- 2. The constraint that names the role
--
-- ck_returned_has_handover said "a returned exchange carries a handover".
-- It still says the same thing; only the wording changes to name the role by
-- its outward name. Renamed rather than dropped-and-recreated so no window
-- exists in which the invariant does not hold.
-- ---------------------------------------------------------------------------
ALTER TABLE dispatch.exchange
    RENAME CONSTRAINT ck_returned_has_handover TO ck_returned_has_return;

-- ---------------------------------------------------------------------------
-- 3. The freeze trigger
--
-- PostgreSQL rewrites column references inside function bodies neither on
-- RENAME COLUMN nor on CREATE OR REPLACE FUNCTION: the function body is
-- source text, and its identifiers are resolved when a call runs. So the
-- trigger function must be replaced whole, with the new field names in place
-- and the message rewritten to name the return role.
--
-- The behaviour is unchanged. Every guard the earlier version raised, this
-- one raises; every guard the earlier version passed, this one passes. The
-- rename is the only thing this rewrites.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION dispatch.refuse_frozen_writes() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.sent_at IS NULL THEN
        -- Still a draft: fully mutable, by design.
        RETURN NEW;
    END IF;

    IF NEW.title <> OLD.title
       OR NEW.body <> OLD.body
       OR NEW.apparatus <> OLD.apparatus
       OR NEW.dispatch_date <> OLD.dispatch_date
       OR NEW.sent_at <> OLD.sent_at THEN
        RAISE EXCEPTION
            'exchange %.%.% is frozen: title, body, apparatus, date and sent_at cannot '
            'change after send. Corrections attach as an addendum.',
            OLD.selector, OLD.number, OLD.sub
            USING ERRCODE = 'raise_exception';
    END IF;

    IF NEW.dispatch_metadata IS DISTINCT FROM OLD.dispatch_metadata THEN
        RAISE EXCEPTION
            'the metadata of exchange %.%.% were written with the dispatch and frozen at '
            'send. They are write-once: a pointer that changes is a pointer whose readers '
            'cannot tell which one they followed.',
            OLD.selector, OLD.number, OLD.sub
            USING ERRCODE = 'raise_exception';
    END IF;

    IF NEW.created_at <> OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'created_at and created_by are server-derived and unwritable'
            USING ERRCODE = 'raise_exception';
    END IF;

    -- A ratified return is frozen at the same gate as the dispatch. Before
    -- ratification the draft is deliberately NOT protected here: overwriting
    -- it wholesale is the normal way rework happens.
    IF OLD.ratified_at IS NOT NULL
       AND (NEW.return_body IS DISTINCT FROM OLD.return_body
            OR NEW.ratified_at <> OLD.ratified_at
            OR NEW.return_metadata IS DISTINCT FROM OLD.return_metadata) THEN
        RAISE EXCEPTION
            'the return of exchange %.%.% is ratified and frozen',
            OLD.selector, OLD.number, OLD.sub
            USING ERRCODE = 'raise_exception';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
