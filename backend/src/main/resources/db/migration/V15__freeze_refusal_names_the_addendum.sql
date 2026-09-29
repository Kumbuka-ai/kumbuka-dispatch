-- ---------------------------------------------------------------------------
-- THE FREEZE REFUSAL NAMES THE ROW IT REFUSED
--
-- `refuse_frozen_writes()` composed its message from selector, number and
-- sub, and left `addendum_suffix` out. So a refused write to the addendum
-- `satellite/29.17a` announced itself as:
--
--     exchange satellite.29.17 is frozen: title, dispatch_body, apparatus,
--     date and sent_at cannot change after send.
--
-- The address in the message belongs to a DIFFERENT row — the exchange the
-- addendum corrects — and that row is frozen too, so the sentence reads
-- true and sends the reader to the wrong object. Measured 2026-09-29 while
-- tracing why an append had produced an empty, unfillable addendum: the
-- trigger was doing its job correctly and blaming the parent for it.
--
-- Nothing about WHAT the trigger refuses changes here. Every guard it
-- raised, this one raises; every guard it passed, this one passes. The one
-- change is that each address in a message is now the complete address,
-- with the addendum's letter where the row has one.
--
-- The function is replaced whole rather than patched, for the reason V12
-- states: a function body is source text, and there is no way to edit one
-- guard of it in place.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION dispatch.refuse_frozen_writes() RETURNS TRIGGER AS $$
DECLARE
    selector_name TEXT;
    at            TEXT;
BEGIN
    IF OLD.sent_at IS NULL THEN
        -- Still a draft: fully mutable, by design.
        RETURN NEW;
    END IF;

    -- The name is looked up once here rather than in each RAISE, so a
    -- trigger call that raises no error carries no join at all. The
    -- lookup is by primary key and is O(1) — indexed by definition.
    SELECT s.name INTO selector_name
      FROM dispatch.selector s
     WHERE s.id = OLD.selector_id;

    -- The complete address, assembled once beside the name and for the same
    -- reason. COALESCE and not a CASE: the suffix is null on every ordinary
    -- exchange, which is the overwhelming majority of the rows this trigger
    -- ever sees, and their message is then byte-identical to the old one.
    at := selector_name || '.' || OLD.number || '.' || OLD.sub
          || COALESCE(OLD.addendum_suffix, '');

    IF NEW.title <> OLD.title
       OR NEW.dispatch_body <> OLD.dispatch_body
       OR NEW.apparatus <> OLD.apparatus
       OR NEW.dispatch_date <> OLD.dispatch_date
       OR NEW.sent_at <> OLD.sent_at THEN
        RAISE EXCEPTION
            'exchange % is frozen: title, dispatch_body, apparatus, date and sent_at '
            'cannot change after send. Corrections attach as an addendum.',
            at
            USING ERRCODE = 'raise_exception';
    END IF;

    IF NEW.dispatch_metadata IS DISTINCT FROM OLD.dispatch_metadata THEN
        RAISE EXCEPTION
            'the metadata of exchange % were written with the dispatch and frozen at '
            'send. They are write-once: a pointer that changes is a pointer whose readers '
            'cannot tell which one they followed.',
            at
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
            'the return of exchange % is ratified and frozen',
            at
            USING ERRCODE = 'raise_exception';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
