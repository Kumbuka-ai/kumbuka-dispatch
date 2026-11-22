-- ===========================================================================
-- V17: the store of the rebuilt lifecycle, beside the one that runs.
--
-- Three tables: `task` carries the process of a commission, `task_text` its
-- texts, one row per text, and `task_idempotency_key` the idempotency records
-- of the new tasks. The stock of `exchange` is copied into them. Nothing reads
-- or writes the new tables yet; the running code keeps reading and writing
-- `exchange`, and its behaviour does not change.
--
-- WHY THE SPLIT
--
-- Today the state and both texts of an exchange stand in one row. Every
-- transition writes that row, and the freeze trigger therefore has to list
-- columns; it was rewritten in V4, V7, V10, V12 and V15. With the texts in a
-- table of their own the table boundary IS the boundary of the freeze: a text
-- row is never changed once its task is sent, and a transition writes `task`
-- and at most inserts one text row.
--
-- ADDITIVE, AND COMPATIBLE WITH THE RELEASE BEFORE IT (DEC-0018)
--
-- New relations and nothing else. No column of `exchange` or `idempotency_key`
-- is added, changed or dropped, no trigger, index, policy or grant on them is
-- touched, and no row of them is written. The image before this one does not
-- know the new tables and runs unchanged against this schema.
--
-- COLUMN ORDER
--
-- Surrogate; identity; tenant and scope; references to parents; own address
-- and kind; state and attributes; payload; creation stamps, then change
-- stamps. PostgreSQL appends a column added later at the end, so the order
-- holds only while a test reads it from the catalogue (TaskStoreShapeIT).
--
-- WHAT IS DELIBERATELY NOT HERE
--
-- No freeze on `title`, `apparatus` and `dispatch_metadata` of a sent task:
-- whether that protection returns to the database is open. No history table
-- and no history trigger. No table for scheduled tasks. No index beyond the
-- one the text key needs; which indexes the kernel needs is measured with the
-- kernel.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. task — the process of a commission.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.task (
    -- surrogate: target of every foreign key, never leaves the service
    id                   BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- durable identity: random (v4), deliberately not time-ordered
    uuid                 UUID         NOT NULL DEFAULT gen_random_uuid(),

    tenant_id            UUID         NOT NULL,
    scope_id             UUID         NOT NULL,

    selector_id          BIGINT       NOT NULL,

    number               INTEGER      NOT NULL,
    sub                  INTEGER      NOT NULL,

    state                TEXT         NOT NULL DEFAULT 'draft',
    hold_reason          TEXT,
    outcome              TEXT,
    state_changed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    state_changed_by     TEXT,
    question_options     JSONB,
    not_before           TIMESTAMPTZ,
    lapse_count          SMALLINT     NOT NULL DEFAULT 0,
    holder_subject       TEXT,
    holder_receipt_hash  TEXT,
    lease_expires_at     TIMESTAMPTZ,
    curated_in_id        BIGINT,

    title                TEXT         NOT NULL,
    apparatus            TEXT         NOT NULL,
    dispatch_metadata    JSONB,
    return_metadata      JSONB,

    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by           TEXT,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by           TEXT,

    CONSTRAINT uq_task_uuid    UNIQUE (uuid),
    CONSTRAINT uq_task_address UNIQUE (tenant_id, scope_id, selector_id, number, sub),
    -- The targets of the composite keys below (ADR-0042): tenant with id for
    -- the references within a tenant, tenant and scope with id for the text
    -- rows, which belong to their task's scope as well.
    CONSTRAINT uq_task_tenant_id       UNIQUE (tenant_id, id),
    CONSTRAINT uq_task_tenant_scope_id UNIQUE (tenant_id, scope_id, id),

    CONSTRAINT fk_task_selector_tenant
        FOREIGN KEY (tenant_id, selector_id) REFERENCES dispatch.selector (tenant_id, id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_task_curated_in_tenant
        FOREIGN KEY (tenant_id, curated_in_id) REFERENCES dispatch.task (tenant_id, id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,

    -- value sets
    CONSTRAINT ck_task_state CHECK (state IN (
        'draft', 'open', 'active', 'on_hold', 'delivered', 'closed')),
    CONSTRAINT ck_task_hold_reason CHECK (hold_reason IN ('question', 'dependency', 'external')),
    CONSTRAINT ck_task_outcome CHECK (outcome IN ('accepted', 'rejected', 'failed', 'withdrawn')),
    CONSTRAINT ck_task_number      CHECK (number >= 1),
    CONSTRAINT ck_task_sub         CHECK (sub >= 0),
    CONSTRAINT ck_task_lapse_count CHECK (lapse_count >= 0),

    -- the shape of a row
    CONSTRAINT ck_task_hold_reason_exactly_on_hold
        CHECK ((state = 'on_hold') = (hold_reason IS NOT NULL)),
    CONSTRAINT ck_task_outcome_exactly_closed
        CHECK ((state = 'closed') = (outcome IS NOT NULL)),
    CONSTRAINT ck_task_holder_whole
        CHECK ((holder_subject IS NULL) = (holder_receipt_hash IS NULL)),
    -- Unlike `exchange.ck_claim_whole`, the holder stays without a lease in
    -- `on_hold` and `delivered`: the lease runs only while the work does.
    CONSTRAINT ck_task_lease_only_active_and_held
        CHECK (lease_expires_at IS NULL OR (state = 'active' AND holder_subject IS NOT NULL)),
    CONSTRAINT ck_task_question_options_only_on_question
        CHECK (question_options IS NULL OR hold_reason = 'question'),
    CONSTRAINT ck_task_not_before_only_open
        CHECK (not_before IS NULL OR state = 'open'),
    CONSTRAINT ck_task_curated_in_only_closed_and_not_self
        CHECK (curated_in_id IS NULL OR (state = 'closed' AND curated_in_id <> id))
);

-- ---------------------------------------------------------------------------
-- 2. task_text — the texts of a task, one row per text.
--
-- A second delivery after a rework is a second `return` row; the youngest is
-- the valid one. An addendum is a row with a letter suffix on a text of the
-- same type, and has no title.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.task_text (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    tenant_id        UUID         NOT NULL,
    scope_id         UUID         NOT NULL,

    task_id          BIGINT       NOT NULL,

    text_type        TEXT         NOT NULL,
    addendum_suffix  TEXT,

    text             TEXT         NOT NULL,

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       TEXT,

    CONSTRAINT fk_task_text_task_tenant
        FOREIGN KEY (tenant_id, scope_id, task_id)
        REFERENCES dispatch.task (tenant_id, scope_id, id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,

    CONSTRAINT ck_task_text_type CHECK (text_type IN (
        'dispatch', 'return', 'question', 'answer', 'remark')),
    -- The suffix grammar of `exchange.ck_exchange_suffix`.
    CONSTRAINT ck_task_text_suffix CHECK (addendum_suffix IS NULL
                                          OR addendum_suffix ~ '^[a-z]$')
);

-- Carries the key above: the guard in section 4 and every RESTRICT check on a
-- deleted draft look a task's texts up by it.
CREATE INDEX idx_task_text_task ON dispatch.task_text (tenant_id, scope_id, task_id, text_type);

-- ---------------------------------------------------------------------------
-- 3. task_idempotency_key — the idempotency records of the new tasks.
--
-- What `dispatch.idempotency_key` (V14) holds, pointing at `task` instead of
-- `exchange`, in the column order of section 1.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.task_idempotency_key (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    tenant_id        UUID         NOT NULL,
    scope_id         UUID         NOT NULL,

    task_id          BIGINT       NOT NULL,

    caller_subject   TEXT         NOT NULL,
    idempotency_key  TEXT         NOT NULL,
    call_name        TEXT         NOT NULL,
    argument_digest  TEXT         NOT NULL,

    first_seen_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT task_idempotency_key_once
        UNIQUE (scope_id, caller_subject, idempotency_key),

    CONSTRAINT fk_task_idempotency_key_task_tenant
        FOREIGN KEY (tenant_id, task_id) REFERENCES dispatch.task (tenant_id, id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
);

-- ---------------------------------------------------------------------------
-- 4. The one guard after the send (DEC-0014, DEC-0015).
--
-- A draft is mutable and hard-deleted; after the send its texts are frozen
-- and the task stays. The service role holds DELETE, and this guard confines
-- it to drafts. No key deletes along: whoever deletes a draft deletes its
-- `task_text` and `task_idempotency_key` rows first, then the task. A
-- cascading delete would reach this guard while the parent row is already
-- being deleted.
--
-- A text whose task cannot be found is refused too. Under row-level security
-- that is a text read under the wrong tenant, and refusing is the side to fail
-- on.
-- ---------------------------------------------------------------------------
CREATE FUNCTION dispatch.refuse_text_writes_after_send() RETURNS TRIGGER AS $$
DECLARE
    task_state TEXT;
BEGIN
    SELECT t.state INTO task_state
      FROM dispatch.task t
     WHERE t.tenant_id = OLD.tenant_id AND t.id = OLD.task_id;

    IF task_state IS DISTINCT FROM 'draft' THEN
        RAISE EXCEPTION
            'text % of task % is frozen: its task is %, and a text is changed or deleted '
            'only while its task is a draft. Corrections attach as an addendum.',
            OLD.id, OLD.task_id, coalesce(task_state, 'not visible')
            USING ERRCODE = 'raise_exception';
    END IF;

    IF TG_OP = 'UPDATE' AND (NEW.tenant_id, NEW.task_id) IS DISTINCT FROM (OLD.tenant_id, OLD.task_id) THEN
        SELECT t.state INTO task_state
          FROM dispatch.task t
         WHERE t.tenant_id = NEW.tenant_id AND t.id = NEW.task_id;
        IF task_state IS DISTINCT FROM 'draft' THEN
            RAISE EXCEPTION
                'text % cannot move to task %: that task is %, not a draft',
                OLD.id, NEW.task_id, coalesce(task_state, 'not visible')
                USING ERRCODE = 'raise_exception';
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER task_text_frozen_after_send
    BEFORE UPDATE OR DELETE ON dispatch.task_text
    FOR EACH ROW EXECUTE FUNCTION dispatch.refuse_text_writes_after_send();

CREATE FUNCTION dispatch.refuse_task_delete_after_send() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.state <> 'draft' THEN
        RAISE EXCEPTION
            'task % is %: only a draft is deleted, a sent task stays',
            OLD.id, OLD.state
            USING ERRCODE = 'raise_exception';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER task_deleted_only_as_draft
    BEFORE DELETE ON dispatch.task
    FOR EACH ROW EXECUTE FUNCTION dispatch.refuse_task_delete_after_send();

-- updated_at is the database's, as on `exchange` (V4), with the same function.
CREATE TRIGGER task_stamp_updated_at
    BEFORE UPDATE ON dispatch.task
    FOR EACH ROW EXECUTE FUNCTION dispatch.stamp_updated_at();

-- ---------------------------------------------------------------------------
-- 5. The copy of the stock.
--
-- READING ACROSS EVERY TENANT
--
-- TenantMigrationCallback binds `app.tenant_id` to the configured tenant
-- before every migration, and `exchange` is under FORCE ROW LEVEL SECURITY.
-- A plain INSERT ... SELECT therefore copies the bound tenant's rows and
-- silently nothing of any other. The migrator carries neither SUPERUSER nor
-- BYPASSRLS (V8 refuses both), so the one way it reads every tenant is as
-- the owner of a table WITHOUT forced security: FORCE is lifted from the two
-- source tables for the copy and put back before this migration ends. Both
-- statements run inside this migration's transaction, under the ACCESS
-- EXCLUSIVE lock the first one takes, so no other session ever sees the
-- tables without it; the catalogue after the commit is the one before it.
-- The policies themselves are not touched.
--
-- The target tables get their row-level security after the copy (section 6),
-- for the same reason: under it, the WITH CHECK clause would refuse every row
-- of a tenant other than the bound one.
--
-- WHAT THE STOCK MUST SATISFY, CHECKED BEFORE A ROW IS WRITTEN
--
-- A row that breaks a form rule of `task` is a finding, not something to
-- copy around: the migration stops and names the count per case. No rule is
-- loosened and no row is left out.
-- ---------------------------------------------------------------------------
ALTER TABLE dispatch.exchange        NO FORCE ROW LEVEL SECURITY;
ALTER TABLE dispatch.idempotency_key NO FORCE ROW LEVEL SECURITY;

DO $do$
DECLARE
    n BIGINT;
BEGIN
    -- An addendum hangs on the task of the same address without a suffix.
    SELECT count(*) INTO n
      FROM dispatch.exchange a
     WHERE a.addendum_suffix IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM dispatch.exchange b
                        WHERE b.tenant_id = a.tenant_id AND b.scope_id = a.scope_id
                          AND b.selector_id = a.selector_id AND b.number = a.number
                          AND b.sub = a.sub AND b.addendum_suffix IS NULL);
    IF n > 0 THEN
        RAISE EXCEPTION 'V17 stops: % addendum row(s) of exchange have no base row at their address', n
            USING ERRCODE = 'KD003';
    END IF;

    -- An addendum carries a title and a text and nothing else. Any other text
    -- on it would have no place in task_text and would be lost.
    SELECT count(*) INTO n
      FROM dispatch.exchange
     WHERE addendum_suffix IS NOT NULL
       AND (return_body IS NOT NULL OR executor_question IS NOT NULL
            OR commissioner_message IS NOT NULL OR termination_reason IS NOT NULL);
    IF n > 0 THEN
        RAISE EXCEPTION 'V17 stops: % addendum row(s) of exchange carry a text besides their own', n
            USING ERRCODE = 'KD003';
    END IF;

    -- curated_in_id only in closed: of the source statuses only `consumed`,
    -- `returned`, `closed`, `rejected` and `failed` become closed.
    SELECT count(*) INTO n
      FROM dispatch.exchange
     WHERE addendum_suffix IS NULL AND curated_into_id IS NOT NULL
       AND status IN ('draft', 'open', 'active', 'needs_input');
    IF n > 0 THEN
        RAISE EXCEPTION 'V17 stops: % exchange row(s) carry a curation target in a status that '
            'does not become closed', n
            USING ERRCODE = 'KD003';
    END IF;

    -- A curation target must become a task: an addendum does not.
    SELECT count(*) INTO n
      FROM dispatch.exchange e
      JOIN dispatch.exchange t ON t.id = e.curated_into_id
     WHERE e.addendum_suffix IS NULL AND t.addendum_suffix IS NOT NULL;
    IF n > 0 THEN
        RAISE EXCEPTION 'V17 stops: % exchange row(s) are curated into an addendum, which '
            'becomes no task', n
            USING ERRCODE = 'KD003';
    END IF;

    -- An idempotency record must point at a row that becomes a task.
    SELECT count(*) INTO n
      FROM dispatch.idempotency_key k
      JOIN dispatch.exchange e ON e.id = k.exchange_id
     WHERE e.addendum_suffix IS NOT NULL;
    IF n > 0 THEN
        RAISE EXCEPTION 'V17 stops: % idempotency record(s) point at an addendum, which '
            'becomes no task', n
            USING ERRCODE = 'KD003';
    END IF;

    -- The dispatch date becomes the key `date` of the dispatch metadata. A
    -- caller's own `date` with another value would be overwritten.
    SELECT count(*) INTO n
      FROM dispatch.exchange
     WHERE addendum_suffix IS NULL
       AND dispatch_metadata ? 'date'
       AND dispatch_metadata -> 'date' IS DISTINCT FROM to_jsonb(dispatch_date::text);
    IF n > 0 THEN
        RAISE EXCEPTION 'V17 stops: % exchange row(s) carry a metadata key date that differs '
            'from their dispatch date', n
            USING ERRCODE = 'KD003';
    END IF;
END
$do$;

-- The process: one task per row without a suffix, with the same id.
INSERT INTO dispatch.task (
    id, uuid, tenant_id, scope_id, selector_id, number, sub,
    state, hold_reason, outcome, state_changed_at, state_changed_by,
    question_options, not_before, lapse_count,
    holder_subject, holder_receipt_hash, lease_expires_at, curated_in_id,
    title, apparatus, dispatch_metadata, return_metadata,
    created_at, created_by, updated_at, updated_by)
OVERRIDING SYSTEM VALUE
SELECT e.id, gen_random_uuid(), e.tenant_id, e.scope_id, e.selector_id, e.number, e.sub,
       m.state, m.hold_reason, m.outcome, e.updated_at, e.updated_by,
       CASE WHEN m.hold_reason = 'question'
            THEN jsonb_build_object('options', '[]'::jsonb, 'free_text', true) END,
       NULL, 0,
       CASE WHEN m.state IN ('active', 'on_hold', 'delivered') THEN e.holder_subject END,
       CASE WHEN m.state IN ('active', 'on_hold', 'delivered') THEN e.holder_receipt_hash END,
       -- a lease already lapsed is copied as it stands
       CASE WHEN m.state = 'active' THEN e.claim_expires_at END,
       e.curated_into_id,
       e.title, e.apparatus,
       coalesce(e.dispatch_metadata, '{}'::jsonb)
           || jsonb_build_object('date', e.dispatch_date::text),
       e.return_metadata,
       e.created_at, e.created_by, e.updated_at, e.updated_by
  FROM dispatch.exchange e
  CROSS JOIN LATERAL (
      SELECT CASE
                 WHEN e.status IN ('draft', 'open', 'active') THEN e.status
                 WHEN e.status = 'needs_input' AND e.return_body IS NOT NULL THEN 'delivered'
                 WHEN e.status = 'needs_input' THEN 'on_hold'
                 ELSE 'closed'
             END AS state,
             CASE WHEN e.status = 'needs_input' AND e.return_body IS NULL
                  THEN 'question' END AS hold_reason,
             CASE
                 WHEN e.status IN ('returned', 'consumed') THEN 'accepted'
                 WHEN e.status IN ('rejected', 'failed') THEN e.status
                 -- TAR-0004 section 9 maps closed to withdrawn throughout; the
                 -- running service also ends accepted work in closed
                 -- (accept_return), and ratified_at tells the two apart.
                 WHEN e.status = 'closed' AND e.ratified_at IS NOT NULL THEN 'accepted'
                 WHEN e.status = 'closed' THEN 'withdrawn'
             END AS outcome
  ) m
 WHERE e.addendum_suffix IS NULL;

-- The texts. A return body is copied in every source status, also where the
-- task is open or active; its state is not adjusted. The dispatch text keeps
-- the source's creation stamp; every other text takes the source's change
-- stamp, the closest the source holds.
INSERT INTO dispatch.task_text (tenant_id, scope_id, task_id, text_type, addendum_suffix,
                                text, created_at, created_by)
SELECT tenant_id, scope_id, task_id, text_type, addendum_suffix, text, created_at, created_by
  FROM (
      SELECT e.tenant_id, e.scope_id, e.id AS task_id, 'dispatch' AS text_type,
             NULL::text AS addendum_suffix, e.dispatch_body AS text,
             e.created_at, e.created_by, 1 AS ordinal, e.id AS source_id
        FROM dispatch.exchange e
       WHERE e.addendum_suffix IS NULL
      UNION ALL
      SELECT e.tenant_id, e.scope_id, e.id, 'return', NULL, e.return_body,
             e.updated_at, e.updated_by, 2, e.id
        FROM dispatch.exchange e
       WHERE e.addendum_suffix IS NULL AND e.return_body IS NOT NULL
      UNION ALL
      SELECT e.tenant_id, e.scope_id, e.id, 'question', NULL, e.executor_question,
             e.updated_at, e.updated_by, 3, e.id
        FROM dispatch.exchange e
       WHERE e.addendum_suffix IS NULL AND e.executor_question IS NOT NULL
      UNION ALL
      SELECT e.tenant_id, e.scope_id, e.id,
             CASE WHEN e.executor_question IS NOT NULL THEN 'answer' ELSE 'remark' END,
             NULL, e.commissioner_message, e.updated_at, e.updated_by, 4, e.id
        FROM dispatch.exchange e
       WHERE e.addendum_suffix IS NULL AND e.commissioner_message IS NOT NULL
      UNION ALL
      SELECT e.tenant_id, e.scope_id, e.id, 'remark', NULL, e.termination_reason,
             e.updated_at, e.updated_by, 5, e.id
        FROM dispatch.exchange e
       WHERE e.addendum_suffix IS NULL AND e.termination_reason IS NOT NULL
      UNION ALL
      -- An addendum: a dispatch text with its suffix on the task of its
      -- address; title, a blank line, then its text; its own stamps.
      SELECT a.tenant_id, a.scope_id, b.id, 'dispatch', a.addendum_suffix,
             a.title || E'\n\n' || a.dispatch_body,
             a.created_at, a.created_by, 6, a.id
        FROM dispatch.exchange a
        JOIN dispatch.exchange b
          ON b.tenant_id = a.tenant_id AND b.scope_id = a.scope_id
         AND b.selector_id = a.selector_id AND b.number = a.number
         AND b.sub = a.sub AND b.addendum_suffix IS NULL
       WHERE a.addendum_suffix IS NOT NULL
  ) texts
 ORDER BY task_id, ordinal, addendum_suffix, source_id;

-- The idempotency records, with their ids.
INSERT INTO dispatch.task_idempotency_key (
    id, tenant_id, scope_id, task_id, caller_subject, idempotency_key,
    call_name, argument_digest, first_seen_at)
OVERRIDING SYSTEM VALUE
SELECT k.id, k.tenant_id, k.scope_id, k.exchange_id, k.caller_subject, k.idempotency_key,
       k.call_name, k.argument_digest, k.first_seen_at
  FROM dispatch.idempotency_key k;

-- The identity counters stand above the highest copied id, so the first
-- insert without an id collides with no copied row.
SELECT setval(pg_get_serial_sequence('dispatch.task', 'id'),
              coalesce((SELECT max(id) FROM dispatch.task), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('dispatch.task_idempotency_key', 'id'),
              coalesce((SELECT max(id) FROM dispatch.task_idempotency_key), 0) + 1, false);

-- The copy, counted against its source while every tenant is still visible.
DO $do$
DECLARE
    source_rows BIGINT;
    task_rows   BIGINT;
BEGIN
    SELECT count(*) INTO source_rows FROM dispatch.exchange WHERE addendum_suffix IS NULL;
    SELECT count(*) INTO task_rows   FROM dispatch.task;
    IF source_rows <> task_rows THEN
        RAISE EXCEPTION 'V17 copied % task(s) from % exchange row(s) without a suffix',
            task_rows, source_rows
            USING ERRCODE = 'KD003';
    END IF;
END
$do$;

ALTER TABLE dispatch.exchange        FORCE ROW LEVEL SECURITY;
ALTER TABLE dispatch.idempotency_key FORCE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- 6. Row-level security, in the form V3 established, on all three tables.
-- ---------------------------------------------------------------------------
ALTER TABLE dispatch.task ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.task FORCE  ROW LEVEL SECURITY;
CREATE POLICY task_tenant_isolation ON dispatch.task
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE dispatch.task_text ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.task_text FORCE  ROW LEVEL SECURITY;
CREATE POLICY task_text_tenant_isolation ON dispatch.task_text
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE dispatch.task_idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.task_idempotency_key FORCE  ROW LEVEL SECURITY;
CREATE POLICY task_idempotency_key_tenant_isolation ON dispatch.task_idempotency_key
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ---------------------------------------------------------------------------
-- 7. The entitlement, written out.
--
-- DELETE is new in this schema and is the point: a draft is hard-deleted
-- (DEC-0015), and the guard in section 4 confines the privilege to drafts.
-- No TRUNCATE, no REFERENCES, no TRIGGER. The tables belong to the migrator.
-- No sequence privilege: an identity column draws its value without one, as
-- `exchange` and `selector` have done since V12.
-- ---------------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE, DELETE ON dispatch.task                 TO kumbuka_dispatch;
GRANT SELECT, INSERT, UPDATE, DELETE ON dispatch.task_text            TO kumbuka_dispatch;
GRANT SELECT, INSERT, UPDATE, DELETE ON dispatch.task_idempotency_key TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.task IS
    'The process of a commission: address, state, holding, relations and the '
    'commission''s attributes. Its texts are in task_text.';
COMMENT ON TABLE dispatch.task_text IS
    'The texts of a task, one row per text. Frozen once the task is sent.';
COMMENT ON TABLE dispatch.task_idempotency_key IS
    'The 24-hour memory behind the idempotency_key argument for tasks.';
