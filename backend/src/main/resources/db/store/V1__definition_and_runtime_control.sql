-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V1 of the store line: definition and runtime control.
--
-- A second, fresh migration line beside db/migration. It builds the schema
-- `dispatch` on an empty database and writes no row. The service neither
-- reads nor writes it yet, and no configuration of the service points here:
-- the lines change places when the service is rebuilt against this store.
--
-- THE MODEL
--
-- Every instance is a run of a declared process, and every change of its
-- control is an act: a row, appended, never changed. The state of an instance
-- or a task is a projection of its acts, kept in the transaction of the act
-- that changes it. Hence the rules every table below follows:
--
--   - acts and their payloads are insert-only; the only columns that change
--     are the named projection columns and the counters. Insert-only is held
--     by the missing privilege, not by a trigger;
--   - the control carries no JSON and no text of the work;
--   - every reference the domain names is a key, and every key carries the
--     tenant and the scope: FOREIGN KEY (tenant_id, scope_id, <column>), so the
--     store itself refuses a pairing across tenants or scopes, which a key
--     checked with row-level security bypassed would otherwise allow;
--   - every reference outside (a tag, a trigger, an object of another service)
--     is a value without a foreign key, stored and never resolved;
--   - there is no table `scope`: `scope_id` carries no foreign key;
--   - times are TIMESTAMPTZ without a default: the service sets the time.
--
-- TENANCY in the form of V3 and V24 of the line db/migration: ENABLE and
-- FORCE ROW LEVEL SECURITY and a policy <table>_tenant_isolation with USING
-- and WITH CHECK on the bound session setting, for every table.
--
-- PRIVILEGES of the runtime role `kumbuka_dispatch`, written out per table.
-- Never TRUNCATE, REFERENCES or TRIGGER. An insert-only table gets SELECT and
-- INSERT. A table with a projection gets SELECT, INSERT and UPDATE on its
-- projection columns only. DELETE is held by `task` alone, for a draft never
-- sent: a task with an act cannot be deleted, the key of `act` refuses it.
--
-- An identity column draws its next value without a privilege check on its
-- sequence, so no sequence is granted here.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. The runtime role, as V2 of the line db/migration checks it.
--
-- Created when it is missing; refused when it carries SUPERUSER or BYPASSRLS,
-- either of which makes every policy below inert. The membership of the
-- migrator that V2 took is not taken: it served an ownership callback that V8
-- removed, and the runtime role owns nothing here.
-- ---------------------------------------------------------------------------
DO $do$
DECLARE
    is_super   boolean;
    is_bypass  boolean;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'kumbuka_dispatch') THEN
        -- A CREATEROLE migrator cannot confer SUPERUSER or BYPASSRLS, so a
        -- role created here structurally cannot carry either. The check below
        -- is for the other path: a role an operator created beforehand.
        CREATE ROLE kumbuka_dispatch LOGIN PASSWORD 'change-me-kumbuka-dispatch';
        RAISE NOTICE 'created role kumbuka_dispatch with the placeholder password — rotate it';
    END IF;

    SELECT rolsuper, rolbypassrls INTO is_super, is_bypass
    FROM pg_catalog.pg_roles WHERE rolname = 'kumbuka_dispatch';

    IF is_super OR is_bypass THEN
        RAISE EXCEPTION
            'kumbuka_dispatch carries superuser=% bypassrls=% — either one makes the '
            'row-level-security policies of this schema inert, and this migration will not '
            'create a schema whose isolation cannot hold. Recreate the role without '
            'them.', is_super, is_bypass;
    END IF;

    -- Raw SQL and psql sessions land in the service's own schema rather than
    -- in `public`. Hibernate and Flyway are pinned by configuration and do
    -- not depend on this; it is here so an unqualified statement typed by a
    -- human fails in the right place.
    EXECUTE 'ALTER ROLE kumbuka_dispatch SET search_path = dispatch';
END
$do$;

-- ---------------------------------------------------------------------------
-- 2. The migrator carries no policy exemption, as V8 of the line
-- db/migration checks it.
--
-- The migrator owns every relation here and every view this schema will
-- gain; a view reads its base tables with its owner's privileges, and a
-- superuser or BYPASSRLS owner is exempt from FORCE ROW LEVEL SECURITY. The
-- block refuses rather than repairs. ERRCODE KD001 is application-defined; a
-- probe matches on it rather than on the message text.
-- ---------------------------------------------------------------------------
DO $do$
DECLARE
    is_super   boolean;
    is_bypass  boolean;
BEGIN
    SELECT rolsuper, rolbypassrls INTO is_super, is_bypass
    FROM pg_catalog.pg_roles WHERE rolname = current_user;

    IF is_super OR is_bypass THEN
        RAISE EXCEPTION
            'the migrating role % carries superuser=% bypassrls=% and this schema will '
            'not be created under it. The migrator owns every relation here, including '
            'every future view, and a view reads its base tables with its owner''s '
            'privileges — either attribute makes that owner exempt from the policies, '
            'silently. Migrate as a role that carries neither (CREATEROLE is all this '
            'chain needs).',
            current_user, is_super::text, is_bypass::text
            USING ERRCODE = 'KD001';
    END IF;
END
$do$;

-- Reaching the schema at all. CREATE is deliberately not granted: a runtime
-- role that can create a table in its own schema owns that table.
GRANT USAGE ON SCHEMA dispatch TO kumbuka_dispatch;

-- ===========================================================================
-- DEFINITION
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- selector: the name under which instances are numbered.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.selector (
    id           BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    UUID    NOT NULL,
    scope_id     UUID    NOT NULL,

    name         TEXT    NOT NULL,
    next_number  BIGINT  NOT NULL DEFAULT 1,

    CONSTRAINT uq_selector_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_selector_name UNIQUE (tenant_id, scope_id, name),
    CONSTRAINT ck_selector_next_number CHECK (next_number >= 1)
);

ALTER TABLE dispatch.selector ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.selector FORCE  ROW LEVEL SECURITY;
CREATE POLICY selector_tenant_isolation ON dispatch.selector
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.selector TO kumbuka_dispatch;
GRANT UPDATE (next_number) ON dispatch.selector TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.selector IS
    'A selector of a scope, under which its instances are numbered from next_number.';

-- ---------------------------------------------------------------------------
-- definition_version: one version of the process a selector declares.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.definition_version (
    id                BIGINT   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         UUID     NOT NULL,
    scope_id          UUID     NOT NULL,

    selector_id       BIGINT   NOT NULL,
    version           INTEGER  NOT NULL,
    verdict_required  BOOLEAN  NOT NULL,
    outcome_required  BOOLEAN  NOT NULL,
    state             TEXT     NOT NULL,

    CONSTRAINT uq_definition_version_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_definition_version_selector_version
        UNIQUE (tenant_id, scope_id, selector_id, version),
    CONSTRAINT ck_definition_version_version CHECK (version >= 1),
    CONSTRAINT ck_definition_version_state CHECK (state IN ('draft', 'active', 'retired')),
    CONSTRAINT fk_definition_version_selector
        FOREIGN KEY (tenant_id, scope_id, selector_id)
        REFERENCES dispatch.selector (tenant_id, scope_id, id)
);

-- At most one active version per selector.
CREATE UNIQUE INDEX uq_definition_version_active
    ON dispatch.definition_version (tenant_id, scope_id, selector_id)
    WHERE state = 'active';

ALTER TABLE dispatch.definition_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.definition_version FORCE  ROW LEVEL SECURITY;
CREATE POLICY definition_version_tenant_isolation ON dispatch.definition_version
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.definition_version TO kumbuka_dispatch;
GRANT UPDATE (state) ON dispatch.definition_version TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.definition_version IS
    'A numbered version of the process a selector declares, of which at most one is active.';

-- ---------------------------------------------------------------------------
-- definition_act: the acts that define and activate a version.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.definition_act (
    id                     BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id              UUID         NOT NULL,
    scope_id               UUID         NOT NULL,

    definition_version_id  BIGINT       NOT NULL,
    sequence               INTEGER      NOT NULL,
    kind                   TEXT         NOT NULL,
    actor_subject          TEXT         NOT NULL,
    actor_owner_subject    TEXT,
    permission             TEXT,
    path                   TEXT,
    authentication_level   TEXT,
    at                     TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uq_definition_act_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_definition_act_version_sequence
        UNIQUE (tenant_id, scope_id, definition_version_id, sequence),
    CONSTRAINT ck_definition_act_sequence CHECK (sequence >= 1),
    CONSTRAINT ck_definition_act_kind CHECK (kind IN ('define', 'activate')),
    CONSTRAINT ck_definition_act_path CHECK (path IN ('interface', 'console')),
    CONSTRAINT ck_definition_act_authentication_level
        CHECK (authentication_level IS NULL OR path = 'console'),
    CONSTRAINT fk_definition_act_definition_version
        FOREIGN KEY (tenant_id, scope_id, definition_version_id)
        REFERENCES dispatch.definition_version (tenant_id, scope_id, id)
);

ALTER TABLE dispatch.definition_act ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.definition_act FORCE  ROW LEVEL SECURITY;
CREATE POLICY definition_act_tenant_isolation ON dispatch.definition_act
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.definition_act TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.definition_act IS
    'An act that defines or activates a definition version, appended and never changed.';

-- ---------------------------------------------------------------------------
-- definition_tag: the tags a version carries, as values of the platform.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.definition_tag (
    id                     BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id              UUID    NOT NULL,
    scope_id               UUID    NOT NULL,

    definition_version_id  BIGINT  NOT NULL,
    tag_id                 UUID    NOT NULL,

    CONSTRAINT uq_definition_tag_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_definition_tag_version_tag
        UNIQUE (tenant_id, scope_id, definition_version_id, tag_id),
    CONSTRAINT fk_definition_tag_definition_version
        FOREIGN KEY (tenant_id, scope_id, definition_version_id)
        REFERENCES dispatch.definition_version (tenant_id, scope_id, id)
);

ALTER TABLE dispatch.definition_tag ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.definition_tag FORCE  ROW LEVEL SECURITY;
CREATE POLICY definition_tag_tenant_isolation ON dispatch.definition_tag
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.definition_tag TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.definition_tag IS
    'A tag of the platform a definition version carries, held as a value without a key.';

-- ---------------------------------------------------------------------------
-- activity: a step a definition version declares.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.activity (
    id                     BIGINT   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id              UUID     NOT NULL,
    scope_id               UUID     NOT NULL,

    definition_version_id  BIGINT   NOT NULL,
    name                   TEXT     NOT NULL,
    verdict_required       BOOLEAN  NOT NULL,
    outcome_required       BOOLEAN  NOT NULL,
    criteria_required      BOOLEAN  NOT NULL,
    input_kind             TEXT     NOT NULL,
    bound_activity_id      BIGINT,
    may_ask                BOOLEAN  NOT NULL,
    starts_excluded        BOOLEAN  NOT NULL,
    handbook_address       TEXT,

    CONSTRAINT uq_activity_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_activity_version_name
        UNIQUE (tenant_id, scope_id, definition_version_id, name),
    CONSTRAINT uq_activity_version_id
        UNIQUE (tenant_id, scope_id, definition_version_id, id),
    CONSTRAINT ck_activity_input_kind
        CHECK (input_kind IN ('text', 'bound', 'parameters', 'none')),
    CONSTRAINT ck_activity_bound
        CHECK ((input_kind = 'bound') = (bound_activity_id IS NOT NULL)),
    CONSTRAINT fk_activity_definition_version
        FOREIGN KEY (tenant_id, scope_id, definition_version_id)
        REFERENCES dispatch.definition_version (tenant_id, scope_id, id),
    -- The bound activity belongs to the same version.
    CONSTRAINT fk_activity_activity_bound
        FOREIGN KEY (tenant_id, scope_id, definition_version_id, bound_activity_id)
        REFERENCES dispatch.activity (tenant_id, scope_id, definition_version_id, id)
);

ALTER TABLE dispatch.activity ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.activity FORCE  ROW LEVEL SECURITY;
CREATE POLICY activity_tenant_isolation ON dispatch.activity
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.activity TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.activity IS
    'An activity a definition version declares, with what its tasks require and take as input.';

-- ---------------------------------------------------------------------------
-- outcome_label: a label an outcome may carry, of an activity or a version.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.outcome_label (
    id                     BIGINT   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id              UUID     NOT NULL,
    scope_id               UUID     NOT NULL,

    activity_id            BIGINT,
    definition_version_id  BIGINT,
    label                  TEXT     NOT NULL,
    ordinal                INTEGER  NOT NULL,

    CONSTRAINT uq_outcome_label_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT ck_outcome_label_owner
        CHECK (num_nonnulls(activity_id, definition_version_id) = 1),
    CONSTRAINT fk_outcome_label_activity
        FOREIGN KEY (tenant_id, scope_id, activity_id)
        REFERENCES dispatch.activity (tenant_id, scope_id, id),
    CONSTRAINT fk_outcome_label_definition_version
        FOREIGN KEY (tenant_id, scope_id, definition_version_id)
        REFERENCES dispatch.definition_version (tenant_id, scope_id, id)
);

CREATE UNIQUE INDEX uq_outcome_label_activity_label
    ON dispatch.outcome_label (tenant_id, scope_id, activity_id, label)
    WHERE activity_id IS NOT NULL;
CREATE UNIQUE INDEX uq_outcome_label_definition_version_label
    ON dispatch.outcome_label (tenant_id, scope_id, definition_version_id, label)
    WHERE definition_version_id IS NOT NULL;

ALTER TABLE dispatch.outcome_label ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.outcome_label FORCE  ROW LEVEL SECURITY;
CREATE POLICY outcome_label_tenant_isolation ON dispatch.outcome_label
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.outcome_label TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.outcome_label IS
    'An outcome label declared either for one activity or for a whole definition version.';

-- ===========================================================================
-- INSTANCE, TASK, ACT
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- instance: one run of a declared process. The key of trigger_task_id is
-- added once `task` exists, below it.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.instance (
    id                     BIGINT   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id              UUID     NOT NULL,
    scope_id               UUID     NOT NULL,

    selector_id            BIGINT   NOT NULL,
    number                 BIGINT   NOT NULL,
    trigger_task_id        BIGINT,

    -- Projection.
    next_sub               INTEGER  NOT NULL DEFAULT 1,
    definition_version_id  BIGINT   NOT NULL,
    state                  TEXT     NOT NULL DEFAULT 'running',
    resolution             TEXT,

    CONSTRAINT uq_instance_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_instance_selector_number UNIQUE (tenant_id, scope_id, selector_id, number),
    CONSTRAINT ck_instance_number CHECK (number >= 1),
    CONSTRAINT ck_instance_next_sub CHECK (next_sub >= 1),
    CONSTRAINT ck_instance_state CHECK (state IN ('running', 'closed')),
    CONSTRAINT ck_instance_resolution CHECK (resolution IN ('concluded', 'withdrawn')),
    CONSTRAINT ck_instance_closed_resolved
        CHECK ((state = 'closed') = (resolution IS NOT NULL)),
    CONSTRAINT fk_instance_selector
        FOREIGN KEY (tenant_id, scope_id, selector_id)
        REFERENCES dispatch.selector (tenant_id, scope_id, id),
    CONSTRAINT fk_instance_definition_version
        FOREIGN KEY (tenant_id, scope_id, definition_version_id)
        REFERENCES dispatch.definition_version (tenant_id, scope_id, id)
);

ALTER TABLE dispatch.instance ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.instance FORCE  ROW LEVEL SECURITY;
CREATE POLICY instance_tenant_isolation ON dispatch.instance
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.instance TO kumbuka_dispatch;
GRANT UPDATE (next_sub, definition_version_id, state, resolution)
    ON dispatch.instance TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.instance IS
    'An instance, one numbered run of a declared process, whose state projects its acts.';

-- ---------------------------------------------------------------------------
-- task: a task of an instance. Every column after `sub` is projection.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.task (
    id                BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         UUID         NOT NULL,
    scope_id          UUID         NOT NULL,

    instance_id       BIGINT       NOT NULL,
    sub               INTEGER      NOT NULL,

    -- Projection.
    title             TEXT,
    activity_id       BIGINT,
    concerns_task_id  BIGINT,
    state             TEXT         NOT NULL DEFAULT 'draft',
    hold_reason       TEXT,
    resolution        TEXT,
    not_before        TIMESTAMPTZ,
    parked            BOOLEAN      NOT NULL DEFAULT false,

    CONSTRAINT uq_task_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_task_instance_sub UNIQUE (tenant_id, scope_id, instance_id, sub),
    CONSTRAINT uq_task_instance_id UNIQUE (tenant_id, scope_id, instance_id, id),
    CONSTRAINT ck_task_sub CHECK (sub >= 1),
    CONSTRAINT ck_task_state CHECK (state IN ('draft', 'recalled', 'open', 'active',
                                              'on_hold', 'delivered', 'closed')),
    CONSTRAINT ck_task_hold_reason CHECK (hold_reason IN ('question', 'dependency', 'external')),
    CONSTRAINT ck_task_resolution
        CHECK (resolution IN ('accepted', 'rejected', 'failed', 'withdrawn')),
    CONSTRAINT ck_task_on_hold_reason CHECK ((state = 'on_hold') = (hold_reason IS NOT NULL)),
    CONSTRAINT ck_task_closed_resolved CHECK ((state = 'closed') = (resolution IS NOT NULL)),
    CONSTRAINT ck_task_not_before_open CHECK (not_before IS NULL OR state = 'open'),
    CONSTRAINT ck_task_parked
        CHECK (NOT parked OR (state = 'on_hold' AND hold_reason = 'external')),
    CONSTRAINT ck_task_concerns_other CHECK (concerns_task_id IS NULL OR concerns_task_id <> id),
    CONSTRAINT fk_task_instance
        FOREIGN KEY (tenant_id, scope_id, instance_id)
        REFERENCES dispatch.instance (tenant_id, scope_id, id),
    CONSTRAINT fk_task_activity
        FOREIGN KEY (tenant_id, scope_id, activity_id)
        REFERENCES dispatch.activity (tenant_id, scope_id, id),
    -- The task concerned belongs to the same instance.
    CONSTRAINT fk_task_task_concerns
        FOREIGN KEY (tenant_id, scope_id, instance_id, concerns_task_id)
        REFERENCES dispatch.task (tenant_id, scope_id, instance_id, id)
);

ALTER TABLE dispatch.task ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.task FORCE  ROW LEVEL SECURITY;
CREATE POLICY task_tenant_isolation ON dispatch.task
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- DELETE is held by this table alone: a draft never sent may be deleted, and
-- the key of `act` refuses the delete of any task that has an act.
GRANT SELECT, INSERT, DELETE ON dispatch.task TO kumbuka_dispatch;
GRANT UPDATE (title, activity_id, concerns_task_id, state, hold_reason, resolution,
              not_before, parked)
    ON dispatch.task TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.task IS
    'A task of an instance, numbered within it, whose state projects the acts on it.';

ALTER TABLE dispatch.instance
    ADD CONSTRAINT fk_instance_task_trigger
        FOREIGN KEY (tenant_id, scope_id, trigger_task_id)
        REFERENCES dispatch.task (tenant_id, scope_id, id);

-- ---------------------------------------------------------------------------
-- act: one change of the control of an instance, appended and never changed.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.act (
    id                    BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id             UUID         NOT NULL,
    scope_id              UUID         NOT NULL,

    instance_id           BIGINT       NOT NULL,
    sequence              INTEGER      NOT NULL,
    task_id               BIGINT,
    kind                  TEXT         NOT NULL,
    actor_subject         TEXT         NOT NULL,
    actor_owner_subject   TEXT,
    permission            TEXT,
    path                  TEXT,
    authentication_level  TEXT,
    at                    TIMESTAMPTZ  NOT NULL,
    to_state              TEXT         NOT NULL,
    refers_to_act_id      BIGINT,

    CONSTRAINT uq_act_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_act_instance_sequence UNIQUE (tenant_id, scope_id, instance_id, sequence),
    CONSTRAINT uq_act_instance_id UNIQUE (tenant_id, scope_id, instance_id, id),
    CONSTRAINT ck_act_sequence CHECK (sequence >= 1),
    CONSTRAINT ck_act_kind CHECK (kind IN (
        'start', 'send', 'recall', 'claim', 'renew', 'release', 'defer', 'ask', 'answer',
        'hold', 'resume', 'deliver', 'rework', 'accept', 'reject', 'fail', 'withdraw',
        'conclude', 'annotate', 'tag', 'untag', 'refer', 'unrefer', 'relate', 'unrelate',
        'import')),
    -- The object of an act: the instance alone, either, or a task.
    CONSTRAINT ck_act_object CHECK (CASE
        WHEN kind IN ('start', 'conclude', 'refer', 'unrefer', 'import') THEN task_id IS NULL
        WHEN kind IN ('withdraw', 'tag', 'untag') THEN true
        ELSE task_id IS NOT NULL
    END),
    CONSTRAINT ck_act_path CHECK (path IN ('interface', 'console')),
    CONSTRAINT ck_act_authentication_level
        CHECK (authentication_level IS NULL OR path = 'console'),
    CONSTRAINT ck_act_to_state CHECK (to_state IN (
        'running', 'closed', 'draft', 'recalled', 'open', 'active', 'on_hold', 'delivered')),
    CONSTRAINT ck_act_refers_other CHECK (refers_to_act_id IS NULL OR refers_to_act_id <> id),
    CONSTRAINT fk_act_instance
        FOREIGN KEY (tenant_id, scope_id, instance_id)
        REFERENCES dispatch.instance (tenant_id, scope_id, id),
    -- The task belongs to the same instance.
    CONSTRAINT fk_act_task
        FOREIGN KEY (tenant_id, scope_id, instance_id, task_id)
        REFERENCES dispatch.task (tenant_id, scope_id, instance_id, id),
    -- The act referred to belongs to the same instance.
    CONSTRAINT fk_act_act_refers
        FOREIGN KEY (tenant_id, scope_id, instance_id, refers_to_act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, instance_id, id)
);

CREATE INDEX idx_act_task_sequence ON dispatch.act (tenant_id, scope_id, task_id, sequence);

ALTER TABLE dispatch.act ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.act FORCE  ROW LEVEL SECURITY;
CREATE POLICY act_tenant_isolation ON dispatch.act
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.act TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.act IS
    'An act on an instance or one of its tasks, with who did it, by what right and by which path.';

-- ===========================================================================
-- THE PAYLOADS OF THE ACTS AND WHAT HANGS ON THEM
--
-- A row carrying act_id hangs on exactly one act. Every table of this part is
-- insert-only, except holding and external_reference.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- commission
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.commission (
    id                BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         UUID    NOT NULL,
    scope_id          UUID    NOT NULL,

    act_id            BIGINT  NOT NULL,
    title             TEXT    NOT NULL,
    activity_id       BIGINT  NOT NULL,
    concerns_task_id  BIGINT,
    bound_task_id     BIGINT,

    CONSTRAINT uq_commission_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_commission_act UNIQUE (tenant_id, scope_id, act_id),
    CONSTRAINT fk_commission_act
        FOREIGN KEY (tenant_id, scope_id, act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, id),
    CONSTRAINT fk_commission_activity
        FOREIGN KEY (tenant_id, scope_id, activity_id)
        REFERENCES dispatch.activity (tenant_id, scope_id, id),
    CONSTRAINT fk_commission_task_concerns
        FOREIGN KEY (tenant_id, scope_id, concerns_task_id)
        REFERENCES dispatch.task (tenant_id, scope_id, id),
    CONSTRAINT fk_commission_task_bound
        FOREIGN KEY (tenant_id, scope_id, bound_task_id)
        REFERENCES dispatch.task (tenant_id, scope_id, id)
);

ALTER TABLE dispatch.commission ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.commission FORCE  ROW LEVEL SECURITY;
CREATE POLICY commission_tenant_isolation ON dispatch.commission
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.commission TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.commission IS
    'The commission an act gives a task: its title, its activity and the tasks it concerns or binds.';

-- ---------------------------------------------------------------------------
-- commission_criterion: the identifier of a criterion; its text is content
-- and does not stand here.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.commission_criterion (
    id             BIGINT   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      UUID     NOT NULL,
    scope_id       UUID     NOT NULL,

    commission_id  BIGINT   NOT NULL,
    identifier     TEXT     NOT NULL,
    ordinal        INTEGER  NOT NULL,

    CONSTRAINT uq_commission_criterion_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_commission_criterion_identifier
        UNIQUE (tenant_id, scope_id, commission_id, identifier),
    CONSTRAINT fk_commission_criterion_commission
        FOREIGN KEY (tenant_id, scope_id, commission_id)
        REFERENCES dispatch.commission (tenant_id, scope_id, id)
);

ALTER TABLE dispatch.commission_criterion ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.commission_criterion FORCE  ROW LEVEL SECURITY;
CREATE POLICY commission_criterion_tenant_isolation ON dispatch.commission_criterion
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.commission_criterion TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.commission_criterion IS
    'A criterion of a commission by its identifier and place, without its text.';

-- ---------------------------------------------------------------------------
-- holding: a claim on a task, from the act that claimed it to the act that
-- ended it. A lapsed holding keeps ended_by_act_id empty, so there is no
-- unique index on open holdings.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.holding (
    id               BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        UUID    NOT NULL,
    scope_id         UUID    NOT NULL,

    task_id          BIGINT  NOT NULL,
    claim_act_id     BIGINT  NOT NULL,

    -- Projection.
    ended_by_act_id  BIGINT,

    CONSTRAINT uq_holding_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_holding_claim_act UNIQUE (tenant_id, scope_id, claim_act_id),
    CONSTRAINT fk_holding_task
        FOREIGN KEY (tenant_id, scope_id, task_id)
        REFERENCES dispatch.task (tenant_id, scope_id, id),
    CONSTRAINT fk_holding_act_claim
        FOREIGN KEY (tenant_id, scope_id, claim_act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, id),
    CONSTRAINT fk_holding_act_ended_by
        FOREIGN KEY (tenant_id, scope_id, ended_by_act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, id)
);

CREATE INDEX idx_holding_task ON dispatch.holding (tenant_id, scope_id, task_id);

ALTER TABLE dispatch.holding ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.holding FORCE  ROW LEVEL SECURITY;
CREATE POLICY holding_tenant_isolation ON dispatch.holding
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.holding TO kumbuka_dispatch;
GRANT UPDATE (ended_by_act_id) ON dispatch.holding TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.holding IS
    'A holding of a task, from the claim that opened it to the act that ended it, if any.';

-- ---------------------------------------------------------------------------
-- receipt
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.receipt (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     UUID         NOT NULL,
    scope_id      UUID         NOT NULL,

    holding_id    BIGINT       NOT NULL,
    receipt_hash  TEXT         NOT NULL,
    issued_at     TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uq_receipt_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT fk_receipt_holding
        FOREIGN KEY (tenant_id, scope_id, holding_id)
        REFERENCES dispatch.holding (tenant_id, scope_id, id)
);

CREATE INDEX idx_receipt_holding ON dispatch.receipt (tenant_id, scope_id, holding_id);

ALTER TABLE dispatch.receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.receipt FORCE  ROW LEVEL SECURITY;
CREATE POLICY receipt_tenant_isolation ON dispatch.receipt
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.receipt TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.receipt IS
    'A receipt issued to the holder of a holding, kept as the hash of what was handed out.';

-- ---------------------------------------------------------------------------
-- lease
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.lease (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   UUID         NOT NULL,
    scope_id    UUID         NOT NULL,

    holding_id  BIGINT       NOT NULL,
    act_id      BIGINT       NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uq_lease_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_lease_act UNIQUE (tenant_id, scope_id, act_id),
    CONSTRAINT fk_lease_holding
        FOREIGN KEY (tenant_id, scope_id, holding_id)
        REFERENCES dispatch.holding (tenant_id, scope_id, id),
    CONSTRAINT fk_lease_act
        FOREIGN KEY (tenant_id, scope_id, act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, id)
);

CREATE INDEX idx_lease_holding ON dispatch.lease (tenant_id, scope_id, holding_id);

ALTER TABLE dispatch.lease ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.lease FORCE  ROW LEVEL SECURITY;
CREATE POLICY lease_tenant_isolation ON dispatch.lease
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.lease TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.lease IS
    'A lease an act grants or renews on a holding, until it expires.';

-- ---------------------------------------------------------------------------
-- deferral
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.deferral (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   UUID         NOT NULL,
    scope_id    UUID         NOT NULL,

    act_id      BIGINT       NOT NULL,
    not_before  TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uq_deferral_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_deferral_act UNIQUE (tenant_id, scope_id, act_id),
    CONSTRAINT fk_deferral_act
        FOREIGN KEY (tenant_id, scope_id, act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, id)
);

ALTER TABLE dispatch.deferral ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.deferral FORCE  ROW LEVEL SECURITY;
CREATE POLICY deferral_tenant_isolation ON dispatch.deferral
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.deferral TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.deferral IS
    'The time before which an act defers a task.';

-- ---------------------------------------------------------------------------
-- hold
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.hold (
    id         BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  UUID    NOT NULL,
    scope_id   UUID    NOT NULL,

    act_id     BIGINT  NOT NULL,
    reason     TEXT    NOT NULL,

    CONSTRAINT uq_hold_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_hold_act UNIQUE (tenant_id, scope_id, act_id),
    CONSTRAINT ck_hold_reason CHECK (reason IN ('dependency', 'external')),
    CONSTRAINT fk_hold_act
        FOREIGN KEY (tenant_id, scope_id, act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, id)
);

ALTER TABLE dispatch.hold ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.hold FORCE  ROW LEVEL SECURITY;
CREATE POLICY hold_tenant_isolation ON dispatch.hold
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.hold TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.hold IS
    'The reason for which an act puts a task on hold.';

-- ---------------------------------------------------------------------------
-- question
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.question (
    id                  BIGINT   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           UUID     NOT NULL,
    scope_id            UUID     NOT NULL,

    act_id              BIGINT   NOT NULL,
    free_text_admitted  BOOLEAN  NOT NULL,

    CONSTRAINT uq_question_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_question_act UNIQUE (tenant_id, scope_id, act_id),
    CONSTRAINT fk_question_act
        FOREIGN KEY (tenant_id, scope_id, act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, id)
);

ALTER TABLE dispatch.question ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.question FORCE  ROW LEVEL SECURITY;
CREATE POLICY question_tenant_isolation ON dispatch.question
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.question TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.question IS
    'A question an act asks, and whether an answer may be free text.';

-- ---------------------------------------------------------------------------
-- question_option
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.question_option (
    id           BIGINT   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    UUID     NOT NULL,
    scope_id     UUID     NOT NULL,

    question_id  BIGINT   NOT NULL,
    label        TEXT     NOT NULL,
    ordinal      INTEGER  NOT NULL,

    CONSTRAINT uq_question_option_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_question_option_label UNIQUE (tenant_id, scope_id, question_id, label),
    CONSTRAINT uq_question_option_ordinal UNIQUE (tenant_id, scope_id, question_id, ordinal),
    CONSTRAINT uq_question_option_question_id UNIQUE (tenant_id, scope_id, question_id, id),
    CONSTRAINT fk_question_option_question
        FOREIGN KEY (tenant_id, scope_id, question_id)
        REFERENCES dispatch.question (tenant_id, scope_id, id)
);

ALTER TABLE dispatch.question_option ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.question_option FORCE  ROW LEVEL SECURITY;
CREATE POLICY question_option_tenant_isolation ON dispatch.question_option
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.question_option TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.question_option IS
    'An option a question offers, by its label and place.';

-- ---------------------------------------------------------------------------
-- answer: at most one per question; the option chosen is one of that
-- question's own.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.answer (
    id           BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    UUID    NOT NULL,
    scope_id     UUID    NOT NULL,

    act_id       BIGINT  NOT NULL,
    question_id  BIGINT  NOT NULL,
    option_id    BIGINT,

    CONSTRAINT uq_answer_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_answer_act UNIQUE (tenant_id, scope_id, act_id),
    CONSTRAINT uq_answer_question UNIQUE (tenant_id, scope_id, question_id),
    CONSTRAINT fk_answer_act
        FOREIGN KEY (tenant_id, scope_id, act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, id),
    CONSTRAINT fk_answer_question
        FOREIGN KEY (tenant_id, scope_id, question_id)
        REFERENCES dispatch.question (tenant_id, scope_id, id),
    CONSTRAINT fk_answer_question_option
        FOREIGN KEY (tenant_id, scope_id, question_id, option_id)
        REFERENCES dispatch.question_option (tenant_id, scope_id, question_id, id)
);

ALTER TABLE dispatch.answer ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.answer FORCE  ROW LEVEL SECURITY;
CREATE POLICY answer_tenant_isolation ON dispatch.answer
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.answer TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.answer IS
    'The answer an act gives to a question, with the option chosen where one was.';

-- ---------------------------------------------------------------------------
-- delivery
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.delivery (
    id                BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         UUID    NOT NULL,
    scope_id          UUID    NOT NULL,

    act_id            BIGINT  NOT NULL,
    verdict           TEXT,
    outcome_label_id  BIGINT,

    CONSTRAINT uq_delivery_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_delivery_act UNIQUE (tenant_id, scope_id, act_id),
    CONSTRAINT ck_delivery_verdict CHECK (verdict IN ('green', 'red', 'question')),
    CONSTRAINT fk_delivery_act
        FOREIGN KEY (tenant_id, scope_id, act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, id),
    CONSTRAINT fk_delivery_outcome_label
        FOREIGN KEY (tenant_id, scope_id, outcome_label_id)
        REFERENCES dispatch.outcome_label (tenant_id, scope_id, id)
);

ALTER TABLE dispatch.delivery ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.delivery FORCE  ROW LEVEL SECURITY;
CREATE POLICY delivery_tenant_isolation ON dispatch.delivery
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.delivery TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.delivery IS
    'The delivery an act makes on a task, with its verdict and outcome label where given.';

-- ---------------------------------------------------------------------------
-- delivery_criterion
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.delivery_criterion (
    id                       BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                UUID    NOT NULL,
    scope_id                 UUID    NOT NULL,

    delivery_id              BIGINT  NOT NULL,
    commission_criterion_id  BIGINT  NOT NULL,
    status                   TEXT    NOT NULL,

    CONSTRAINT uq_delivery_criterion_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_delivery_criterion_criterion
        UNIQUE (tenant_id, scope_id, delivery_id, commission_criterion_id),
    CONSTRAINT ck_delivery_criterion_status
        CHECK (status IN ('met', 'not_met', 'not_applicable')),
    CONSTRAINT fk_delivery_criterion_delivery
        FOREIGN KEY (tenant_id, scope_id, delivery_id)
        REFERENCES dispatch.delivery (tenant_id, scope_id, id),
    CONSTRAINT fk_delivery_criterion_commission_criterion
        FOREIGN KEY (tenant_id, scope_id, commission_criterion_id)
        REFERENCES dispatch.commission_criterion (tenant_id, scope_id, id)
);

ALTER TABLE dispatch.delivery_criterion ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.delivery_criterion FORCE  ROW LEVEL SECURITY;
CREATE POLICY delivery_criterion_tenant_isolation ON dispatch.delivery_criterion
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.delivery_criterion TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.delivery_criterion IS
    'The status a delivery reports for one criterion of the commission.';

-- ---------------------------------------------------------------------------
-- instance_return
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.instance_return (
    id                BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         UUID    NOT NULL,
    scope_id          UUID    NOT NULL,

    act_id            BIGINT  NOT NULL,
    verdict           TEXT,
    outcome_label_id  BIGINT,

    CONSTRAINT uq_instance_return_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_instance_return_act UNIQUE (tenant_id, scope_id, act_id),
    CONSTRAINT ck_instance_return_verdict CHECK (verdict IN ('green', 'red', 'question')),
    CONSTRAINT fk_instance_return_act
        FOREIGN KEY (tenant_id, scope_id, act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, id),
    CONSTRAINT fk_instance_return_outcome_label
        FOREIGN KEY (tenant_id, scope_id, outcome_label_id)
        REFERENCES dispatch.outcome_label (tenant_id, scope_id, id)
);

ALTER TABLE dispatch.instance_return ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.instance_return FORCE  ROW LEVEL SECURITY;
CREATE POLICY instance_return_tenant_isolation ON dispatch.instance_return
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.instance_return TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.instance_return IS
    'The return an act gives for a whole instance, with its verdict and outcome label where given.';

-- ---------------------------------------------------------------------------
-- external_reference: a value outside this service, held on an instance or a
-- task and never resolved. At most one current reference of a kind and value
-- per instance and per task.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.external_reference (
    id               BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        UUID    NOT NULL,
    scope_id         UUID    NOT NULL,

    instance_id      BIGINT,
    task_id          BIGINT,
    kind             TEXT    NOT NULL,
    value            TEXT    NOT NULL,
    set_by_act_id    BIGINT  NOT NULL,

    -- Projection.
    ended_by_act_id  BIGINT,

    CONSTRAINT uq_external_reference_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT ck_external_reference_owner CHECK (num_nonnulls(instance_id, task_id) = 1),
    CONSTRAINT ck_external_reference_kind
        CHECK (kind IN ('trigger', 'tag', 'reference', 'curation')),
    CONSTRAINT ck_external_reference_instance_kinds
        CHECK (kind NOT IN ('trigger', 'reference') OR instance_id IS NOT NULL),
    CONSTRAINT ck_external_reference_task_kinds
        CHECK (kind <> 'curation' OR task_id IS NOT NULL),
    CONSTRAINT ck_external_reference_trigger_not_ended
        CHECK (kind <> 'trigger' OR ended_by_act_id IS NULL),
    CONSTRAINT fk_external_reference_instance
        FOREIGN KEY (tenant_id, scope_id, instance_id)
        REFERENCES dispatch.instance (tenant_id, scope_id, id),
    CONSTRAINT fk_external_reference_task
        FOREIGN KEY (tenant_id, scope_id, task_id)
        REFERENCES dispatch.task (tenant_id, scope_id, id),
    CONSTRAINT fk_external_reference_act_set_by
        FOREIGN KEY (tenant_id, scope_id, set_by_act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, id),
    CONSTRAINT fk_external_reference_act_ended_by
        FOREIGN KEY (tenant_id, scope_id, ended_by_act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, id)
);

CREATE UNIQUE INDEX uq_external_reference_instance_current
    ON dispatch.external_reference (tenant_id, scope_id, instance_id, kind, value)
    WHERE instance_id IS NOT NULL AND ended_by_act_id IS NULL;
CREATE UNIQUE INDEX uq_external_reference_task_current
    ON dispatch.external_reference (tenant_id, scope_id, task_id, kind, value)
    WHERE task_id IS NOT NULL AND ended_by_act_id IS NULL;
CREATE INDEX idx_external_reference_kind_value
    ON dispatch.external_reference (tenant_id, scope_id, kind, value);

ALTER TABLE dispatch.external_reference ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.external_reference FORCE  ROW LEVEL SECURITY;
CREATE POLICY external_reference_tenant_isolation ON dispatch.external_reference
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.external_reference TO kumbuka_dispatch;
GRANT UPDATE (ended_by_act_id) ON dispatch.external_reference TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.external_reference IS
    'A reference to something outside this service, held as a value on an instance or a task.';

-- ---------------------------------------------------------------------------
-- idempotency_key: the first answer to a caller's key. The key outlives a
-- draft it created: the delete of that task sets task_id alone to NULL, and
-- NULL means the draft the first call created was deleted.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.idempotency_key (
    id               BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        UUID         NOT NULL,
    scope_id         UUID         NOT NULL,

    caller_subject   TEXT         NOT NULL,
    idempotency_key  TEXT         NOT NULL,
    task_id          BIGINT,
    act_id           BIGINT,
    created_at       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uq_idempotency_key_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_idempotency_key_caller_key
        UNIQUE (tenant_id, scope_id, caller_subject, idempotency_key),
    CONSTRAINT ck_idempotency_key_one_target CHECK (num_nonnulls(task_id, act_id) <= 1),
    CONSTRAINT fk_idempotency_key_task
        FOREIGN KEY (tenant_id, scope_id, task_id)
        REFERENCES dispatch.task (tenant_id, scope_id, id)
        ON DELETE SET NULL (task_id),
    CONSTRAINT fk_idempotency_key_act
        FOREIGN KEY (tenant_id, scope_id, act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, id)
);

ALTER TABLE dispatch.idempotency_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.idempotency_key FORCE  ROW LEVEL SECURITY;
CREATE POLICY idempotency_key_tenant_isolation ON dispatch.idempotency_key
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.idempotency_key TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.idempotency_key IS
    'The first answer to a caller''s idempotency key, a task or an act, kept after a draft is deleted.';
