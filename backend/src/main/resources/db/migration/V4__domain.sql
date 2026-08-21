-- ===========================================================================
-- V4: the object model of the exchange.
--
-- An exchange has two roles — the dispatch (the commission) and the handover
-- (the answer) — and they are two roles of ONE identity, not two objects that
-- happen to reference each other. That sentence is the reason for the shape
-- below, and it is worth spending a paragraph on because the obvious
-- alternative is wrong in a way that only shows up later.
--
-- The obvious alternative is two tables joined one-to-one. It reads well and
-- it quietly reintroduces everything this design removes: two rows can carry
-- two statuses, so there is a second place for the state to live and a second
-- thing to keep in step; one row can exist without the other, so "an exchange
-- with no answer" and "an answer with no exchange" become distinguishable
-- states nobody designed; and the freeze then needs a rule per table instead
-- of a gate per object. The predecessor of this service had exactly that
-- shape, and the pair invariant it needed was checked beside the transition
-- rather than at it — which made a mismatched pair permanently unclosable.
--
-- So: ONE row per exchange, with two field groups. The handover fields are
-- null until there is an answer. There is one status column and there is
-- nowhere else for a status to be.
--
-- WHAT IS DELIBERATELY NOT HERE
--
-- No audit table and no audit entry — that is a separate line of work. No
-- write-permission column. No return-obligation flag on the selector: the
-- question it depends on is unanswered, and a switch that must not be thrown
-- is a gate with no observable red state. The global obligation holds in the
-- meantime.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- selector — a bracket name, declared before use.
--
-- A selector is a declared, immutable object per scope. It is never created
-- by first use and never by a verb: a typo must not silently open a
-- namespace, and later an aspect or a piece of agent-influencing
-- configuration attaches to a selector — which a string that came into
-- existence by being typed cannot carry.
--
-- It can never be renamed, because every address ever issued depends on it.
-- Withdrawal is a status rather than a deletion, and only a never-used
-- selector may be withdrawn; that check lives in the domain, where "never
-- used" is a question about exchanges.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.selector (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID         NOT NULL,
    scope_id    UUID         NOT NULL,
    name        TEXT         NOT NULL,
    withdrawn   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_selector_name UNIQUE (tenant_id, scope_id, name),
    -- The address grammar admits no upper case and no separators beyond the
    -- hyphen. Enforced here rather than in the domain alone, because an
    -- address that was issued cannot be withdrawn by fixing a validator.
    CONSTRAINT ck_selector_name CHECK (name ~ '^[a-z][a-z0-9-]{0,62}$')
);

-- ---------------------------------------------------------------------------
-- number_circle — one counter per scope and selector.
--
-- Allocation is transactional with the creation of the object: the row is
-- locked, incremented and consumed inside the same transaction that inserts
-- the exchange, so a rolled-back creation returns its number. No verb
-- allocates a number on its own, which removes the class "burned number"
-- structurally rather than by convention.
--
-- Only the bracket level has a circle. The children number WITHIN the bracket
-- instance and the letter suffix numbers within the exchange it corrects;
-- neither is a declared circle, and giving either one a counter row here
-- would make it look like one.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.number_circle (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL,
    scope_id     UUID         NOT NULL,
    selector     TEXT         NOT NULL,
    next_number  INTEGER      NOT NULL DEFAULT 1,
    CONSTRAINT uq_circle UNIQUE (tenant_id, scope_id, selector),
    CONSTRAINT ck_circle_next CHECK (next_number >= 1)
);

-- ---------------------------------------------------------------------------
-- exchange — the commission, its answer, and the state of that exchange.
--
-- IDENTITY. (scope, selector, number, sub, addendum_suffix). The bracket is
-- the exchange numbered sub = 0; it is not a row of its own and has no
-- separate state. Its status IS the status of its `.0`, and its metadata ARE
-- the metadata of its `.0` — there is no second place for either.
--
-- ADDENDA. An addendum carries a letter suffix on the exchange it corrects —
-- 149.0a, 149.0b — and never a regular sub-number. The distinction is
-- load-bearing rather than cosmetic: a regular number would make the
-- addendum an ordinary child of the bracket, and an ordinary child carries
-- the handover expectation and counts in the terminality check that governs
-- whether the bracket may close. The suffix keeps a correction attached to
-- what it corrects without manufacturing a second exchange.
--
-- STATUS. Nine values, one column. Six are the ordinary path; three exist for
-- the exchanges that never reach an answer, because without them an executor
-- who cannot deliver has only the choice between leaving the exchange lying
-- and terminating it — and an exchange left lying is indistinguishable in the
-- store from one being worked.
--
-- THE FREEZE. Before `send` the dispatch fields are fully mutable and the row
-- is hard-deletable. `send` sets sent_at and from then on the dispatch fields
-- are immutable while the status goes on moving. Enforced by trigger below,
-- not only in the application: a freeze that lives in one layer is a freeze
-- that raw SQL walks past.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.exchange (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID         NOT NULL,
    scope_id          UUID         NOT NULL,

    -- identity
    selector          TEXT         NOT NULL,
    number            INTEGER      NOT NULL,
    sub               INTEGER      NOT NULL,
    addendum_suffix   TEXT,

    -- the one status field
    status            TEXT         NOT NULL DEFAULT 'draft',

    -- the dispatch role: the commission
    title             TEXT         NOT NULL,
    body              TEXT         NOT NULL DEFAULT '',
    apparatus         TEXT         NOT NULL,
    dispatch_date     DATE         NOT NULL,
    sent_at           TIMESTAMPTZ,

    -- the handover role: the answer. Null until there is one.
    handover_body     TEXT,
    ratified_at       TIMESTAMPTZ,

    -- technical fields, server-derived and unwritable through any path
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        TEXT,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by        TEXT,

    CONSTRAINT uq_exchange_address
        UNIQUE (tenant_id, scope_id, selector, number, sub, addendum_suffix),

    CONSTRAINT ck_exchange_status CHECK (status IN (
        'draft', 'open', 'active', 'returned', 'closed', 'consumed',
        'rejected', 'failed', 'needs_input')),

    CONSTRAINT ck_exchange_number CHECK (number >= 1),
    CONSTRAINT ck_exchange_sub    CHECK (sub >= 0),

    -- A single lower-case letter, or nothing. Overflow beyond `z` is deferred
    -- as hypothetical and is therefore refused rather than silently wrapped:
    -- a wrapped suffix would collide with an address already issued.
    CONSTRAINT ck_exchange_suffix CHECK (addendum_suffix IS NULL
                                         OR addendum_suffix ~ '^[a-z]$'),

    -- An addendum hangs from an object that was already frozen, so it cannot
    -- itself be a draft: there is nothing to correct until a commitment was
    -- acquired.
    CONSTRAINT ck_addendum_not_draft CHECK (addendum_suffix IS NULL
                                            OR status <> 'draft'),

    -- The freeze, as a data-level statement: an exchange past `draft` has been
    -- sent. Without this the status could advance while sent_at stayed null,
    -- and the trigger below would then let the dispatch fields keep moving.
    CONSTRAINT ck_sent_when_past_draft CHECK (status = 'draft' OR sent_at IS NOT NULL),

    -- `returned` means the handover is ratified and frozen. A returned
    -- exchange with no answer would be the state the whole record exists to
    -- rule out.
    CONSTRAINT ck_returned_has_handover CHECK (
        status <> 'returned' OR (handover_body IS NOT NULL AND ratified_at IS NOT NULL))
);

CREATE INDEX idx_exchange_tenant   ON dispatch.exchange (tenant_id);
CREATE INDEX idx_exchange_bracket  ON dispatch.exchange (tenant_id, scope_id, selector, number);
CREATE INDEX idx_exchange_status   ON dispatch.exchange (tenant_id, status);

-- ---------------------------------------------------------------------------
-- The freeze, enforced at the table.
--
-- The application refuses a write to a frozen field with a typed error, which
-- is what a caller should see. This trigger is the other half: raw SQL, a
-- native query, a migration written in a hurry — none of them pass through
-- the application, and the freeze is the one property of this record that
-- everything else rests on. A guarantee enforced in exactly one layer is a
-- guarantee with a known way around it.
--
-- The technical fields are covered by the same rule and for the same reason:
-- they are derived server-side and unwritable through any path, so a client
-- that supplies one is rejected rather than quietly ignored.
-- ---------------------------------------------------------------------------
CREATE FUNCTION dispatch.refuse_frozen_writes() RETURNS TRIGGER AS $$
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

    IF NEW.created_at <> OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'created_at and created_by are server-derived and unwritable'
            USING ERRCODE = 'raise_exception';
    END IF;

    -- A ratified handover is frozen at the same gate as the dispatch.
    IF OLD.ratified_at IS NOT NULL
       AND (NEW.handover_body IS DISTINCT FROM OLD.handover_body
            OR NEW.ratified_at <> OLD.ratified_at) THEN
        RAISE EXCEPTION
            'the handover of exchange %.%.% is ratified and frozen',
            OLD.selector, OLD.number, OLD.sub
            USING ERRCODE = 'raise_exception';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER exchange_freeze
    BEFORE UPDATE ON dispatch.exchange
    FOR EACH ROW EXECUTE FUNCTION dispatch.refuse_frozen_writes();

-- ---------------------------------------------------------------------------
-- updated_at is derived here and nowhere else.
--
-- The technical fields are server-derived and unwritable through any path, and
-- "server" means this server: a value the application computes is a value a
-- client can influence by choosing when to call, and a value the application
-- FORGETS to compute is a row whose age silently stops moving. The database
-- knows when it wrote, so the database says so.
-- ---------------------------------------------------------------------------
CREATE FUNCTION dispatch.stamp_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER exchange_stamp_updated_at
    BEFORE UPDATE ON dispatch.exchange
    FOR EACH ROW EXECUTE FUNCTION dispatch.stamp_updated_at();

-- ---------------------------------------------------------------------------
-- Row-level security, in the form V3 established: ENABLE and FORCE, and a
-- policy with both USING and WITH CHECK on every tenant-scoped table. The
-- completeness probe reads the catalog and fails on any table here that
-- carries tenant_id without all three.
-- ---------------------------------------------------------------------------
ALTER TABLE dispatch.selector ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.selector FORCE  ROW LEVEL SECURITY;
CREATE POLICY selector_tenant_isolation ON dispatch.selector
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE dispatch.number_circle ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.number_circle FORCE  ROW LEVEL SECURITY;
CREATE POLICY number_circle_tenant_isolation ON dispatch.number_circle
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE dispatch.exchange ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.exchange FORCE  ROW LEVEL SECURITY;
CREATE POLICY exchange_tenant_isolation ON dispatch.exchange
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
