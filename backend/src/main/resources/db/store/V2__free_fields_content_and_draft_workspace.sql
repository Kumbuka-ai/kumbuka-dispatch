-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V2 of the store line: free fields, content and the draft workspace.
--
-- Six tables after V1, on the same rules: an identity key, tenant_id and
-- scope_id, UNIQUE (tenant_id, scope_id, id), every foreign key carrying the
-- tenant and the scope and none deleting or updating along, ENABLE and FORCE
-- ROW LEVEL SECURITY with one policy <table>_tenant_isolation, the privileges
-- of `kumbuka_dispatch` written out per table and never TRUNCATE, REFERENCES
-- or TRIGGER, no trigger. The checks of the runtime role and of the migrator
-- stand in V1 and are not repeated. Like V1 it writes no row.
--
-- FREE FIELDS AND CONTENT hang on the act that wrote them and are never
-- changed: SELECT and INSERT only. Free fields are what a customer or their
-- agent attaches; the platform stores and returns them and never evaluates
-- them. The two `document` columns are the only JSON of the schema, JSONB so
-- that they can be searched; a search index is a later migration's.
--
-- THE DRAFT WORKSPACE is the only changeable place of the store. It holds
-- what a task in draft will carry when it is sent; sending writes it out to
-- the insert-only tables above and V1, and empties it. No key deletes along,
-- so the service deletes the rows of the workspace itself, children first.
-- That a draft row hangs only on a task in draft or recalled, and which kinds
-- of content an act of a kind carries, the service checks: without triggers
-- the store cannot.
-- ===========================================================================

-- ===========================================================================
-- FREE FIELDS AND CONTENT
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- free_fields: one document of free fields per act. Whether it belongs to an
-- instance, a commission or a return follows from the kind of its act.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.free_fields (
    id         BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  UUID    NOT NULL,
    scope_id   UUID    NOT NULL,

    act_id     BIGINT  NOT NULL,
    document   JSONB   NOT NULL,

    CONSTRAINT uq_free_fields_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_free_fields_act UNIQUE (tenant_id, scope_id, act_id),
    CONSTRAINT ck_free_fields_document CHECK (jsonb_typeof(document) = 'object'),
    CONSTRAINT fk_free_fields_act
        FOREIGN KEY (tenant_id, scope_id, act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, id)
);

ALTER TABLE dispatch.free_fields ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.free_fields FORCE  ROW LEVEL SECURITY;
CREATE POLICY free_fields_tenant_isolation ON dispatch.free_fields
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.free_fields TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.free_fields IS
    'The document of free fields an act attaches, stored and returned and never evaluated.';

-- ---------------------------------------------------------------------------
-- content: a text an act writes. An addendum is a row of its annotate act
-- that supplements either one content row or, for an answer given by an
-- option alone, the answer act. The text of a criterion hangs on the
-- criterion by its column, so that one text belongs to one identifier.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.content (
    id                       BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                UUID    NOT NULL,
    scope_id                 UUID    NOT NULL,

    act_id                   BIGINT  NOT NULL,
    kind                     TEXT    NOT NULL,
    supplements_content_id   BIGINT,
    supplements_act_id       BIGINT,
    commission_criterion_id  BIGINT,
    delivery_criterion_id    BIGINT,
    media_type               TEXT    NOT NULL,
    body                     TEXT    NOT NULL,

    CONSTRAINT uq_content_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT ck_content_kind CHECK (kind IN (
        'commission', 'criterion', 'criterion_evidence', 'decision_basis', 'question',
        'answer', 'return', 'translation_basis', 'remark', 'addendum', 'instance_return',
        'instance_translation_basis')),
    -- An addendum supplements exactly one thing; every other kind none.
    CONSTRAINT ck_content_supplements CHECK (
        num_nonnulls(supplements_content_id, supplements_act_id)
            = CASE WHEN kind = 'addendum' THEN 1 ELSE 0 END),
    CONSTRAINT ck_content_criterion
        CHECK ((kind = 'criterion') = (commission_criterion_id IS NOT NULL)),
    CONSTRAINT ck_content_criterion_evidence
        CHECK ((kind = 'criterion_evidence') = (delivery_criterion_id IS NOT NULL)),
    CONSTRAINT ck_content_supplements_other
        CHECK (supplements_content_id IS NULL OR supplements_content_id <> id),
    CONSTRAINT fk_content_act
        FOREIGN KEY (tenant_id, scope_id, act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, id),
    CONSTRAINT fk_content_content_supplements
        FOREIGN KEY (tenant_id, scope_id, supplements_content_id)
        REFERENCES dispatch.content (tenant_id, scope_id, id),
    CONSTRAINT fk_content_act_supplements
        FOREIGN KEY (tenant_id, scope_id, supplements_act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, id),
    CONSTRAINT fk_content_commission_criterion
        FOREIGN KEY (tenant_id, scope_id, commission_criterion_id)
        REFERENCES dispatch.commission_criterion (tenant_id, scope_id, id),
    CONSTRAINT fk_content_delivery_criterion
        FOREIGN KEY (tenant_id, scope_id, delivery_criterion_id)
        REFERENCES dispatch.delivery_criterion (tenant_id, scope_id, id)
);

-- One text per criterion of a commission and per criterion of a delivery.
CREATE UNIQUE INDEX uq_content_commission_criterion
    ON dispatch.content (tenant_id, scope_id, commission_criterion_id)
    WHERE commission_criterion_id IS NOT NULL;
CREATE UNIQUE INDEX uq_content_delivery_criterion
    ON dispatch.content (tenant_id, scope_id, delivery_criterion_id)
    WHERE delivery_criterion_id IS NOT NULL;
CREATE INDEX idx_content_act ON dispatch.content (tenant_id, scope_id, act_id);

ALTER TABLE dispatch.content ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.content FORCE  ROW LEVEL SECURITY;
CREATE POLICY content_tenant_isolation ON dispatch.content
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.content TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.content IS
    'A text of a kind an act writes, with the content, act or criterion it supplements or belongs to.';

-- ===========================================================================
-- THE DRAFT WORKSPACE
--
-- SELECT, INSERT and DELETE on every table, UPDATE only on the columns named.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- draft: the control a task in draft will carry, one per task. Its
-- UNIQUE (tenant_id, scope_id, id) is the target of the tables below.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.draft (
    id                BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         UUID    NOT NULL,
    scope_id          UUID    NOT NULL,

    task_id           BIGINT  NOT NULL,
    title             TEXT,
    activity_id       BIGINT,
    concerns_task_id  BIGINT,
    bound_task_id     BIGINT,

    CONSTRAINT uq_draft_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_draft_task UNIQUE (tenant_id, scope_id, task_id),
    CONSTRAINT fk_draft_task
        FOREIGN KEY (tenant_id, scope_id, task_id)
        REFERENCES dispatch.task (tenant_id, scope_id, id),
    CONSTRAINT fk_draft_activity
        FOREIGN KEY (tenant_id, scope_id, activity_id)
        REFERENCES dispatch.activity (tenant_id, scope_id, id),
    CONSTRAINT fk_draft_task_concerns
        FOREIGN KEY (tenant_id, scope_id, concerns_task_id)
        REFERENCES dispatch.task (tenant_id, scope_id, id),
    CONSTRAINT fk_draft_task_bound
        FOREIGN KEY (tenant_id, scope_id, bound_task_id)
        REFERENCES dispatch.task (tenant_id, scope_id, id)
);

ALTER TABLE dispatch.draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.draft FORCE  ROW LEVEL SECURITY;
CREATE POLICY draft_tenant_isolation ON dispatch.draft
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, DELETE ON dispatch.draft TO kumbuka_dispatch;
GRANT UPDATE (title, activity_id, concerns_task_id, bound_task_id)
    ON dispatch.draft TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.draft IS
    'The control a task in draft will carry when it is sent, changeable until then.';

-- ---------------------------------------------------------------------------
-- draft_criterion: the identifier of a criterion of a draft; its text is a
-- draft_text.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.draft_criterion (
    id          BIGINT   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   UUID     NOT NULL,
    scope_id    UUID     NOT NULL,

    draft_id    BIGINT   NOT NULL,
    identifier  TEXT     NOT NULL,
    ordinal     INTEGER  NOT NULL,

    CONSTRAINT uq_draft_criterion_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_draft_criterion_identifier
        UNIQUE (tenant_id, scope_id, draft_id, identifier),
    CONSTRAINT uq_draft_criterion_draft_id UNIQUE (tenant_id, scope_id, draft_id, id),
    CONSTRAINT fk_draft_criterion_draft
        FOREIGN KEY (tenant_id, scope_id, draft_id)
        REFERENCES dispatch.draft (tenant_id, scope_id, id)
);

ALTER TABLE dispatch.draft_criterion ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.draft_criterion FORCE  ROW LEVEL SECURITY;
CREATE POLICY draft_criterion_tenant_isolation ON dispatch.draft_criterion
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, DELETE ON dispatch.draft_criterion TO kumbuka_dispatch;
GRANT UPDATE (identifier, ordinal) ON dispatch.draft_criterion TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.draft_criterion IS
    'A criterion of a draft by its identifier and place, without its text.';

-- ---------------------------------------------------------------------------
-- draft_free_fields: the document of free fields a draft will carry.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.draft_free_fields (
    id         BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  UUID    NOT NULL,
    scope_id   UUID    NOT NULL,

    draft_id   BIGINT  NOT NULL,
    document   JSONB   NOT NULL,

    CONSTRAINT uq_draft_free_fields_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_draft_free_fields_draft UNIQUE (tenant_id, scope_id, draft_id),
    CONSTRAINT ck_draft_free_fields_document CHECK (jsonb_typeof(document) = 'object'),
    CONSTRAINT fk_draft_free_fields_draft
        FOREIGN KEY (tenant_id, scope_id, draft_id)
        REFERENCES dispatch.draft (tenant_id, scope_id, id)
);

ALTER TABLE dispatch.draft_free_fields ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.draft_free_fields FORCE  ROW LEVEL SECURITY;
CREATE POLICY draft_free_fields_tenant_isolation ON dispatch.draft_free_fields
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, DELETE ON dispatch.draft_free_fields TO kumbuka_dispatch;
GRANT UPDATE (document) ON dispatch.draft_free_fields TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.draft_free_fields IS
    'The document of free fields a draft will carry when it is sent, changeable until then.';

-- ---------------------------------------------------------------------------
-- draft_text: the text of the commission of a draft, or of one of its
-- criteria. The key to the criterion carries the draft, so a criterion text
-- hangs only on a criterion of the same draft.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.draft_text (
    id                  BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           UUID    NOT NULL,
    scope_id            UUID    NOT NULL,

    draft_id            BIGINT  NOT NULL,
    kind                TEXT    NOT NULL,
    draft_criterion_id  BIGINT,
    media_type          TEXT    NOT NULL,
    body                TEXT    NOT NULL,

    CONSTRAINT uq_draft_text_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT ck_draft_text_kind CHECK (kind IN ('commission', 'criterion')),
    CONSTRAINT ck_draft_text_criterion
        CHECK ((kind = 'criterion') = (draft_criterion_id IS NOT NULL)),
    CONSTRAINT fk_draft_text_draft
        FOREIGN KEY (tenant_id, scope_id, draft_id)
        REFERENCES dispatch.draft (tenant_id, scope_id, id),
    CONSTRAINT fk_draft_text_draft_criterion
        FOREIGN KEY (tenant_id, scope_id, draft_id, draft_criterion_id)
        REFERENCES dispatch.draft_criterion (tenant_id, scope_id, draft_id, id)
);

-- One commission text per draft and one text per criterion.
CREATE UNIQUE INDEX uq_draft_text_commission
    ON dispatch.draft_text (tenant_id, scope_id, draft_id)
    WHERE kind = 'commission';
CREATE UNIQUE INDEX uq_draft_text_draft_criterion
    ON dispatch.draft_text (tenant_id, scope_id, draft_criterion_id)
    WHERE draft_criterion_id IS NOT NULL;

ALTER TABLE dispatch.draft_text ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.draft_text FORCE  ROW LEVEL SECURITY;
CREATE POLICY draft_text_tenant_isolation ON dispatch.draft_text
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, DELETE ON dispatch.draft_text TO kumbuka_dispatch;
GRANT UPDATE (media_type, body) ON dispatch.draft_text TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.draft_text IS
    'The text of the commission of a draft or of one of its criteria, changeable until it is sent.';
