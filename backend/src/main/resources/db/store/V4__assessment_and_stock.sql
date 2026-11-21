-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V4 of the store line: what the assessment of V1 to V3 and the copy of the
-- stock require.
--
-- It changes tables of V1 to V3 in place, drops three and adds one. The line
-- is not deployed, no service reads or writes it, no configuration points
-- here and it holds no row, so this one migration may drop, rename and add
-- NOT NULL columns. After it the line takes changes additively only.
--
-- The rules of V1 to V3 hold unchanged: every table carries tenant_id and
-- scope_id and UNIQUE (tenant_id, scope_id, id); every foreign key carries
-- the tenant and the scope and none deletes or updates along; ENABLE and
-- FORCE ROW LEVEL SECURITY with one policy <table>_tenant_isolation; the
-- privileges of `kumbuka_dispatch` written out per table, never TRUNCATE,
-- REFERENCES or TRIGGER; no trigger, no JSON. Like V1 it writes no row.
--
-- A column added to a table keeps the table's SELECT and INSERT; it carries
-- UPDATE only where a GRANT below names it.
--
-- What the store still does not check, and the service does: that ask_act_id
-- names an act of the kind ask; that the value of a row of
-- instance_field_value belongs to the field of the row and matches the
-- values written by the acts; that the claim of a hold acts on its task.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. definition_version: a version the copy of the stock creates is marked
-- seed; no call writes the column. UNIQUE (…, selector_id, id) is the target
-- by which an instance is bound only to a version of its own selector.
-- ---------------------------------------------------------------------------
ALTER TABLE dispatch.definition_version
    ADD COLUMN seed BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT uq_definition_version_selector_id
        UNIQUE (tenant_id, scope_id, selector_id, id);

-- ---------------------------------------------------------------------------
-- 2. instance: a title, set by start and never changed; the version it is
-- bound to belongs to its own selector. definition_version_id keeps its
-- UPDATE privilege, and the key holds the selector on every change.
-- ---------------------------------------------------------------------------
ALTER TABLE dispatch.instance
    DROP CONSTRAINT fk_instance_definition_version;

ALTER TABLE dispatch.instance
    ADD COLUMN title TEXT,
    ADD CONSTRAINT fk_instance_definition_version
        FOREIGN KEY (tenant_id, scope_id, selector_id, definition_version_id)
        REFERENCES dispatch.definition_version (tenant_id, scope_id, selector_id, id);

-- ---------------------------------------------------------------------------
-- 3. task: the copy of the stock places the root task of an existing bracket
-- under <number>.0, so sub admits 0. The counter of an instance still starts
-- at 1 (ck_instance_next_sub), so no new task receives the 0. The role a
-- task is addressed to is a projection, like its title, and the index serves
-- the list of open tasks by role.
-- ---------------------------------------------------------------------------
ALTER TABLE dispatch.task
    DROP CONSTRAINT ck_task_sub;

ALTER TABLE dispatch.task
    ADD CONSTRAINT ck_task_sub CHECK (sub >= 0),
    ADD COLUMN addressed_role TEXT,
    ADD CONSTRAINT ck_task_addressed_role
        CHECK (addressed_role ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$');

CREATE INDEX idx_task_state_addressed_role
    ON dispatch.task (tenant_id, scope_id, state, addressed_role, instance_id, sub);

GRANT UPDATE (addressed_role) ON dispatch.task TO kumbuka_dispatch;

-- ---------------------------------------------------------------------------
-- 4. commission: the role it is addressed to, frozen when it is sent; and its
-- instance, so that its act and the tasks it concerns or binds lie in that
-- one instance.
-- ---------------------------------------------------------------------------
ALTER TABLE dispatch.commission
    DROP CONSTRAINT fk_commission_act,
    DROP CONSTRAINT fk_commission_task_concerns,
    DROP CONSTRAINT fk_commission_task_bound;

ALTER TABLE dispatch.commission
    ADD COLUMN addressed_role TEXT,
    ADD COLUMN instance_id BIGINT NOT NULL,
    ADD CONSTRAINT ck_commission_addressed_role
        CHECK (addressed_role ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'),
    ADD CONSTRAINT fk_commission_instance
        FOREIGN KEY (tenant_id, scope_id, instance_id)
        REFERENCES dispatch.instance (tenant_id, scope_id, id),
    ADD CONSTRAINT fk_commission_act
        FOREIGN KEY (tenant_id, scope_id, instance_id, act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, instance_id, id),
    ADD CONSTRAINT fk_commission_task_concerns
        FOREIGN KEY (tenant_id, scope_id, instance_id, concerns_task_id)
        REFERENCES dispatch.task (tenant_id, scope_id, instance_id, id),
    ADD CONSTRAINT fk_commission_task_bound
        FOREIGN KEY (tenant_id, scope_id, instance_id, bound_task_id)
        REFERENCES dispatch.task (tenant_id, scope_id, instance_id, id);

-- ---------------------------------------------------------------------------
-- 5. draft: the role the draft is addressed to, changeable until it is sent.
-- ---------------------------------------------------------------------------
ALTER TABLE dispatch.draft
    ADD COLUMN addressed_role TEXT,
    ADD CONSTRAINT ck_draft_addressed_role
        CHECK (addressed_role ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$');

GRANT UPDATE (addressed_role) ON dispatch.draft TO kumbuka_dispatch;

-- ---------------------------------------------------------------------------
-- 6. external_reference: the kind item names the worklist item an instance
-- serves, and hangs on the instance.
-- ---------------------------------------------------------------------------
ALTER TABLE dispatch.external_reference
    DROP CONSTRAINT ck_external_reference_kind,
    DROP CONSTRAINT ck_external_reference_instance_kinds;

ALTER TABLE dispatch.external_reference
    ADD CONSTRAINT ck_external_reference_kind
        CHECK (kind IN ('trigger', 'tag', 'reference', 'curation', 'item')),
    ADD CONSTRAINT ck_external_reference_instance_kinds
        CHECK (kind NOT IN ('trigger', 'reference', 'item') OR instance_id IS NOT NULL);

-- ---------------------------------------------------------------------------
-- 7. content: the input text of an instance, written at its start act. It
-- carries neither a criterion nor a supplement: ck_content_criterion and
-- ck_content_criterion_evidence hold the criterion columns empty for every
-- kind but their own, and ck_content_supplements holds both supplement
-- columns empty for every kind but addendum.
-- ---------------------------------------------------------------------------
ALTER TABLE dispatch.content
    DROP CONSTRAINT ck_content_kind;

ALTER TABLE dispatch.content
    ADD CONSTRAINT ck_content_kind CHECK (kind IN (
        'commission', 'criterion', 'criterion_evidence', 'decision_basis', 'question',
        'answer', 'return', 'translation_basis', 'remark', 'addendum', 'instance_return',
        'instance_translation_basis', 'instance_input'));

-- ---------------------------------------------------------------------------
-- 8. act: the payloads of hold, defer and ask, each a column bound to the
-- kind of its act by a check, which the tables they replace could not do.
-- ---------------------------------------------------------------------------
ALTER TABLE dispatch.act
    ADD COLUMN hold_reason TEXT,
    ADD COLUMN not_before TIMESTAMPTZ,
    ADD COLUMN free_text_admitted BOOLEAN,
    ADD CONSTRAINT ck_act_hold_reason
        CHECK (hold_reason IS NULL OR hold_reason IN ('dependency', 'external')),
    ADD CONSTRAINT ck_act_hold_kind CHECK ((kind = 'hold') = (hold_reason IS NOT NULL)),
    ADD CONSTRAINT ck_act_not_before_kind CHECK (not_before IS NULL OR kind = 'defer'),
    ADD CONSTRAINT ck_act_free_text_kind
        CHECK ((kind = 'ask') = (free_text_admitted IS NOT NULL));

-- ---------------------------------------------------------------------------
-- 9. and 10. question_option and answer hang on the ask act instead of the
-- question. The keys that point to question go first; the option an answer
-- chooses stays bound to its question by the key over ask_act_id.
-- ---------------------------------------------------------------------------
ALTER TABLE dispatch.answer
    DROP CONSTRAINT fk_answer_question_option,
    DROP CONSTRAINT fk_answer_question,
    DROP CONSTRAINT uq_answer_question;

ALTER TABLE dispatch.question_option
    DROP CONSTRAINT fk_question_option_question,
    DROP CONSTRAINT uq_question_option_question_id;

ALTER TABLE dispatch.question_option RENAME COLUMN question_id TO ask_act_id;

ALTER TABLE dispatch.question_option
    ADD CONSTRAINT uq_question_option_ask_act_id UNIQUE (tenant_id, scope_id, ask_act_id, id),
    ADD CONSTRAINT fk_question_option_act_ask
        FOREIGN KEY (tenant_id, scope_id, ask_act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, id);

ALTER TABLE dispatch.answer RENAME COLUMN question_id TO ask_act_id;

ALTER TABLE dispatch.answer
    ADD CONSTRAINT uq_answer_ask_act UNIQUE (tenant_id, scope_id, ask_act_id),
    ADD CONSTRAINT fk_answer_act_ask
        FOREIGN KEY (tenant_id, scope_id, ask_act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, id),
    ADD CONSTRAINT fk_answer_question_option
        FOREIGN KEY (tenant_id, scope_id, ask_act_id, option_id)
        REFERENCES dispatch.question_option (tenant_id, scope_id, ask_act_id, id);

-- ---------------------------------------------------------------------------
-- 11. hold, deferral and question are gone, with their policies and
-- privileges. No key points to any of them any more; without CASCADE a key
-- that still did would stop the migration.
-- ---------------------------------------------------------------------------
DROP TABLE dispatch.hold;
DROP TABLE dispatch.deferral;
DROP TABLE dispatch.question;

-- ---------------------------------------------------------------------------
-- 12. instance_field_value: the value of a control field that holds for an
-- instance, one row per instance and field. It names the field value written
-- and the act that accepted it, empty for a value written by start, and
-- repeats the value so that a list can filter by it. No index on text_value,
-- since 4096 characters can exceed the row of an index, and none on
-- boolean_value.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.instance_field_value (
    id                  BIGINT   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           UUID     NOT NULL,
    scope_id            UUID     NOT NULL,

    instance_id         BIGINT   NOT NULL,
    control_field_id    BIGINT   NOT NULL,

    -- Projection.
    field_value_id      BIGINT   NOT NULL,
    accepted_by_act_id  BIGINT,
    boolean_value       BOOLEAN,
    number_value        NUMERIC,
    date_value          DATE,
    text_value          TEXT,
    choice_value_id     BIGINT,

    CONSTRAINT uq_instance_field_value_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_instance_field_value_instance_control_field
        UNIQUE (tenant_id, scope_id, instance_id, control_field_id),
    CONSTRAINT ck_instance_field_value_text_value CHECK (char_length(text_value) <= 4096),
    CONSTRAINT fk_instance_field_value_instance
        FOREIGN KEY (tenant_id, scope_id, instance_id)
        REFERENCES dispatch.instance (tenant_id, scope_id, id),
    CONSTRAINT fk_instance_field_value_control_field
        FOREIGN KEY (tenant_id, scope_id, control_field_id)
        REFERENCES dispatch.control_field (tenant_id, scope_id, id),
    CONSTRAINT fk_instance_field_value_field_value
        FOREIGN KEY (tenant_id, scope_id, field_value_id)
        REFERENCES dispatch.field_value (tenant_id, scope_id, id),
    -- The accepting act belongs to the same instance.
    CONSTRAINT fk_instance_field_value_act_accepted_by
        FOREIGN KEY (tenant_id, scope_id, instance_id, accepted_by_act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, instance_id, id),
    CONSTRAINT fk_instance_field_value_enumeration_value
        FOREIGN KEY (tenant_id, scope_id, choice_value_id)
        REFERENCES dispatch.enumeration_value (tenant_id, scope_id, id)
);

CREATE INDEX idx_instance_field_value_number
    ON dispatch.instance_field_value (tenant_id, scope_id, control_field_id, number_value,
                                      instance_id);
CREATE INDEX idx_instance_field_value_date
    ON dispatch.instance_field_value (tenant_id, scope_id, control_field_id, date_value,
                                      instance_id);
CREATE INDEX idx_instance_field_value_choice
    ON dispatch.instance_field_value (tenant_id, scope_id, control_field_id, choice_value_id,
                                      instance_id);

ALTER TABLE dispatch.instance_field_value ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.instance_field_value FORCE  ROW LEVEL SECURITY;
CREATE POLICY instance_field_value_tenant_isolation ON dispatch.instance_field_value
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.instance_field_value TO kumbuka_dispatch;
GRANT UPDATE (field_value_id, accepted_by_act_id, boolean_value, number_value, date_value,
              text_value, choice_value_id)
    ON dispatch.instance_field_value TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.instance_field_value IS
    'The value of a control field that holds for an instance, with the field value and the act that accepted it.';
