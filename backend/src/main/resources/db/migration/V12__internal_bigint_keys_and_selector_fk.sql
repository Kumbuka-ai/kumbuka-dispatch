-- ===========================================================================
-- V12: internal identity is BIGINT, and exchange references selector by it.
--
-- V1..V11 keyed every relation of this schema with a UUID and referenced the
-- selector from `exchange` and `number_circle` by its name as text. Both
-- choices are revised here for one rule taken as normative:
--
--   Internal identity is BIGINT, allocated by the database. UUIDs are for
--   identifiers that leave the service — for the tenant axis, for the
--   platform scope reference, for anything that faces outward. `selector.id`
--   and `exchange.id` never leave: the address grammar names an exchange as
--   `<scope>/<selector-name>/<number>.<sub>[<suffix>]`, and the row's UUID
--   never appears in it. So the row does not need one.
--
-- WHAT THIS MIGRATION CHANGES
--
--   1. `selector.id`      UUID → BIGINT (GENERATED ALWAYS AS IDENTITY).
--   2. `exchange.id`      UUID → BIGINT (GENERATED ALWAYS AS IDENTITY).
--   3. `exchange.selector` TEXT → `exchange.selector_id` BIGINT REFERENCES
--      `dispatch.selector(id)`. The selector's name is reached by join. It
--      was already unique per `(tenant_id, scope_id, name)` — the reference
--      is now on that identity by primary key rather than by name.
--
-- The address itself does not change. `<scope>/<selector-name>/<n>.<m>` is
-- the identity outward, and it remains a projection over `(tenant_id,
-- scope_id, selector.name, number, sub, addendum_suffix)`. What changes is
-- the join column, not the join.
--
-- WHY A FOREIGN KEY EXISTS AT ALL
--
-- V1's `no cross-schema foreign key` rule stated one thing, and only one
-- thing: this service does not reach into another service's schema. Within
-- `dispatch`, the rule was silent. It was never a licence for the domain to
-- reference its own selector by loose text. The absence of the reference
-- here was a gap rather than a doctrine — one that V4 itself pointed at in
-- its own comment on `selector` ("a typo must not silently open a
-- namespace") without closing.
--
-- WHY IDENTITY RATHER THAN A NAMED SEQUENCE
--
-- `GENERATED ALWAYS AS IDENTITY` is a PostgreSQL 10+ column-level counter.
-- Its sequence is created and dropped with the column, has no grantable ACL
-- of its own (the table's grants cover writes to it), and cannot be advanced
-- by anything other than the column's INSERT — which is what closes the
-- class "burned identifier by manual SEQUENCE call". V8's note that this
-- schema has no sequence needs updating in prose; nothing in its grant model
-- moves, because there is no separate sequence privilege to grant.
--
-- WHY THIS IS ONE MIGRATION AND NOT A CAREFUL TWO-STEP
--
-- The additive/N-1 principle would have said: add the new columns, dual-
-- write, sunset the old columns in a later release. V6 named the exception
-- and its shape — a drop is deliberate, its probes are rewritten onto the
-- replacement FIRST, and the drop and the rewrites travel together. This
-- change fits the same shape. The Java entities, the JPQL and native
-- queries, and the fixtures and probes are all rewritten onto the new
-- columns in the same commit that carries this migration. Nothing writes
-- against `exchange.id UUID` or `exchange.selector TEXT` after the code in
-- this release starts.
--
-- ORDER
--
-- Selector first, exchange second. Exchange's new `selector_id` needs the
-- selector's `id` in BIGINT form to reference, and PostgreSQL will not
-- accept a FOREIGN KEY between columns whose types do not agree.
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- 1. Selector's id: UUID → BIGINT.
--
-- Adding an IDENTITY column populates every existing row atomically. The
-- old UUID column is retained until step 3, so that `exchange.selector`
-- can be resolved to a new BIGINT via the current join key
-- (`tenant_id, scope_id, name`), which is not touched.
-- ---------------------------------------------------------------------------

ALTER TABLE dispatch.selector
    ADD COLUMN id_new BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL;

-- The old primary key is on `id`. Drop it before renaming so PostgreSQL's
-- implicit `<table>_pkey` name is free for the new column.
ALTER TABLE dispatch.selector DROP CONSTRAINT selector_pkey;
ALTER TABLE dispatch.selector DROP COLUMN id;
ALTER TABLE dispatch.selector RENAME COLUMN id_new TO id;
ALTER TABLE dispatch.selector ADD PRIMARY KEY (id);


-- ---------------------------------------------------------------------------
-- 2. Exchange's identity: UUID → BIGINT. Same shape as selector's.
-- ---------------------------------------------------------------------------

ALTER TABLE dispatch.exchange
    ADD COLUMN id_new BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL;

ALTER TABLE dispatch.exchange DROP CONSTRAINT exchange_pkey;
ALTER TABLE dispatch.exchange DROP COLUMN id;
ALTER TABLE dispatch.exchange RENAME COLUMN id_new TO id;
ALTER TABLE dispatch.exchange ADD PRIMARY KEY (id);


-- ---------------------------------------------------------------------------
-- 3. Exchange's selector reference: TEXT name → BIGINT foreign key.
--
-- The values in `selector` (name) are matched against the selector table's
-- current row to derive the new `selector_id`. The join is on the identity
-- the two tables have carried in parallel (`tenant_id, scope_id, name`),
-- and after the copy `exchange.selector` (TEXT) is dropped in favour of
-- `exchange.selector_id` (BIGINT).
-- ---------------------------------------------------------------------------

ALTER TABLE dispatch.exchange
    ADD COLUMN selector_id BIGINT;

UPDATE dispatch.exchange e
   SET selector_id = s.id
  FROM dispatch.selector s
 WHERE s.tenant_id = e.tenant_id
   AND s.scope_id  = e.scope_id
   AND s.name      = e.selector;

ALTER TABLE dispatch.exchange
    ALTER COLUMN selector_id SET NOT NULL;

-- The foreign key. RESTRICT is the correct action: a selector with
-- exchanges cannot be deleted because it cannot be deleted at all — its
-- withdrawal is a status, not a deletion (V4). CASCADE would license a
-- deletion the domain refuses to have. NO ACTION would defer the check
-- and is indistinguishable from RESTRICT here; RESTRICT states the
-- refusal at the row.
ALTER TABLE dispatch.exchange
    ADD CONSTRAINT fk_exchange_selector
        FOREIGN KEY (selector_id)
        REFERENCES dispatch.selector(id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT;

-- The indexes and the composite uniqueness that carried the TEXT
-- selector are dropped and rebuilt on the new column. Each new form
-- has the same rows in the same order under it — column order in an
-- index and in the composite UNIQUE is the query's, and the query's
-- order carries over.
DROP INDEX  dispatch.idx_exchange_bracket;
DROP INDEX  dispatch.idx_exchange_claimable;
ALTER TABLE dispatch.exchange DROP CONSTRAINT uq_exchange_address;

ALTER TABLE dispatch.exchange DROP COLUMN selector;

ALTER TABLE dispatch.exchange
    ADD CONSTRAINT uq_exchange_address
        UNIQUE (tenant_id, scope_id, selector_id, number, sub, addendum_suffix);

CREATE INDEX idx_exchange_bracket
    ON dispatch.exchange (tenant_id, scope_id, selector_id, number);

CREATE INDEX idx_exchange_claimable
    ON dispatch.exchange (tenant_id, scope_id, selector_id, number, sub)
 WHERE addendum_suffix IS NULL
   AND status IN ('open', 'active');


-- ---------------------------------------------------------------------------
-- 4. The freeze trigger references OLD.selector in its error messages.
--
-- The function body is source text, and PostgreSQL does not rewrite
-- references inside it when a referenced column is dropped. So the
-- function must be replaced whole, and the selector's name is now looked
-- up through the foreign key.
--
-- Behaviour unchanged: every guard the earlier version raised, this one
-- raises; every guard it passed, this one passes.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION dispatch.refuse_frozen_writes() RETURNS TRIGGER AS $$
DECLARE
    selector_name TEXT;
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

    IF NEW.title <> OLD.title
       OR NEW.dispatch_body <> OLD.dispatch_body
       OR NEW.apparatus <> OLD.apparatus
       OR NEW.dispatch_date <> OLD.dispatch_date
       OR NEW.sent_at <> OLD.sent_at THEN
        RAISE EXCEPTION
            'exchange %.%.% is frozen: title, dispatch_body, apparatus, date and sent_at '
            'cannot change after send. Corrections attach as an addendum.',
            selector_name, OLD.number, OLD.sub
            USING ERRCODE = 'raise_exception';
    END IF;

    IF NEW.dispatch_metadata IS DISTINCT FROM OLD.dispatch_metadata THEN
        RAISE EXCEPTION
            'the metadata of exchange %.%.% were written with the dispatch and frozen at '
            'send. They are write-once: a pointer that changes is a pointer whose readers '
            'cannot tell which one they followed.',
            selector_name, OLD.number, OLD.sub
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
            selector_name, OLD.number, OLD.sub
            USING ERRCODE = 'raise_exception';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
