-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V5 of the store line: the elicitation complex.
--
-- The question part of the store becomes a complex of five tables with the
-- prefix elicitation_, named after the elicitation of the MCP protocol: an
-- ask act carries a bundle of questions, an answer act one answer per
-- question. The prefix keeps the tables apart from the acts ask and answer,
-- which keep their names.
--
-- WHAT IT CHANGES: act loses free_text_admitted with ck_act_free_text_kind
-- and gains human_required on the ask act and answer_outcome on the answer
-- act; question_option and answer of V4 are dropped with their policies and
-- privileges; the content kinds question and answer leave ck_content_kind;
-- elicitation_question, elicitation_option, elicitation_answer,
-- elicitation_answer_option and elicitation_content are added.
--
-- WHY IT IS NOT ADDITIVE: V4 hangs the options and the one answer row on the
-- ask act (uq_answer_ask_act) and admits one answer row per answer act
-- (uq_answer_act of V1); a bundle of several questions breaks both, and
-- free_text_admitted moves from the act to each question. The line is not
-- deployed, no service reads or writes it and it holds no row, so V5 may
-- drop tables, columns, keys and checks. It is the second and last exception
-- after V4; from V6 on the line takes changes additively only.
--
-- The rules of V1 to V4 hold unchanged for every new and changed table:
-- tenant_id and scope_id and UNIQUE (tenant_id, scope_id, id); every foreign
-- key carries the tenant and the scope and none deletes or updates along;
-- ENABLE and FORCE ROW LEVEL SECURITY with one policy <table>_tenant_isolation;
-- the privileges of `kumbuka_dispatch` written out per table, SELECT and
-- INSERT, never TRUNCATE, REFERENCES or TRIGGER; no trigger, no JSON. Like V1
-- it writes no row.
--
-- What the store still does not check, and the service does: that
-- ask_act_id names an act of the kind ask and answer_act_id one of the kind
-- answer; that an answer answers a question of an ask act of the same task;
-- the bounds of a bundle (one to eight questions; two to twelve options
-- except for selection none, none for selection none); the rules of an
-- answer against its bundle; and that human_required is never lowered.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. act: free text is a property of each question now. human_required
-- belongs to the bundle of an ask act, answer_outcome to an answer act; each
-- is bound to the kind of its act by a check.
-- ---------------------------------------------------------------------------
ALTER TABLE dispatch.act
    DROP CONSTRAINT ck_act_free_text_kind;

ALTER TABLE dispatch.act
    DROP COLUMN free_text_admitted;

ALTER TABLE dispatch.act
    ADD COLUMN human_required BOOLEAN,
    ADD COLUMN answer_outcome TEXT,
    ADD CONSTRAINT ck_act_human_required_kind
        CHECK ((kind = 'ask') = (human_required IS NOT NULL)),
    ADD CONSTRAINT ck_act_answer_outcome
        CHECK (answer_outcome IS NULL OR answer_outcome IN ('answered', 'declined')),
    ADD CONSTRAINT ck_act_answer_outcome_kind
        CHECK ((kind = 'answer') = (answer_outcome IS NOT NULL));

-- ---------------------------------------------------------------------------
-- 2. answer and question_option of V4 are gone, with their policies and
-- privileges. answer goes first: its key points to question_option. No other
-- key points to either; without CASCADE one that did would stop the
-- migration.
-- ---------------------------------------------------------------------------
DROP TABLE dispatch.answer;
DROP TABLE dispatch.question_option;

-- ---------------------------------------------------------------------------
-- 3. content holds no text of the elicitation complex: the kinds question
-- and answer leave its check.
-- ---------------------------------------------------------------------------
ALTER TABLE dispatch.content
    DROP CONSTRAINT ck_content_kind;

ALTER TABLE dispatch.content
    ADD CONSTRAINT ck_content_kind CHECK (kind IN (
        'commission', 'criterion', 'criterion_evidence', 'decision_basis', 'return',
        'translation_basis', 'remark', 'addendum', 'instance_return',
        'instance_translation_basis', 'instance_input'));

-- ---------------------------------------------------------------------------
-- 4. elicitation_question: one question of the bundle of an ask act, by the
-- identifier the asker gives it and its place. A question without a
-- selection admits free text.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.elicitation_question (
    id                  BIGINT   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           UUID     NOT NULL,
    scope_id            UUID     NOT NULL,

    ask_act_id          BIGINT   NOT NULL,
    identifier          TEXT     NOT NULL,
    ordinal             INTEGER  NOT NULL,
    selection           TEXT     NOT NULL,
    free_text_admitted  BOOLEAN  NOT NULL,
    required            BOOLEAN  NOT NULL,

    CONSTRAINT uq_elicitation_question_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_elicitation_question_identifier
        UNIQUE (tenant_id, scope_id, ask_act_id, identifier),
    CONSTRAINT uq_elicitation_question_ordinal
        UNIQUE (tenant_id, scope_id, ask_act_id, ordinal),
    CONSTRAINT ck_elicitation_question_selection
        CHECK (selection IN ('none', 'one', 'many')),
    CONSTRAINT ck_elicitation_question_free_text
        CHECK (selection <> 'none' OR free_text_admitted),
    CONSTRAINT fk_elicitation_question_act_ask
        FOREIGN KEY (tenant_id, scope_id, ask_act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, id)
);

ALTER TABLE dispatch.elicitation_question ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.elicitation_question FORCE  ROW LEVEL SECURITY;
CREATE POLICY elicitation_question_tenant_isolation ON dispatch.elicitation_question
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.elicitation_question TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.elicitation_question IS
    'A question of the bundle an ask act carries, by its identifier and place, with its selection and whether free text is admitted and an answer required.';

-- ---------------------------------------------------------------------------
-- 5. elicitation_option: an option of a question, by its identifier and
-- label. UNIQUE (…, question_id, id) is the target of the key by which a
-- chosen option belongs to the question of its answer.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.elicitation_option (
    id           BIGINT   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    UUID     NOT NULL,
    scope_id     UUID     NOT NULL,

    question_id  BIGINT   NOT NULL,
    identifier   TEXT     NOT NULL,
    label        TEXT     NOT NULL,
    ordinal      INTEGER  NOT NULL,

    CONSTRAINT uq_elicitation_option_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_elicitation_option_identifier
        UNIQUE (tenant_id, scope_id, question_id, identifier),
    CONSTRAINT uq_elicitation_option_label UNIQUE (tenant_id, scope_id, question_id, label),
    CONSTRAINT uq_elicitation_option_ordinal UNIQUE (tenant_id, scope_id, question_id, ordinal),
    CONSTRAINT uq_elicitation_option_question_id UNIQUE (tenant_id, scope_id, question_id, id),
    CONSTRAINT ck_elicitation_option_label CHECK (char_length(label) <= 200),
    CONSTRAINT fk_elicitation_option_elicitation_question
        FOREIGN KEY (tenant_id, scope_id, question_id)
        REFERENCES dispatch.elicitation_question (tenant_id, scope_id, id)
);

ALTER TABLE dispatch.elicitation_option ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.elicitation_option FORCE  ROW LEVEL SECURITY;
CREATE POLICY elicitation_option_tenant_isolation ON dispatch.elicitation_option
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.elicitation_option TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.elicitation_option IS
    'An option a question offers, by its identifier, label and place.';

-- ---------------------------------------------------------------------------
-- 6. elicitation_answer: the answer an answer act gives to one question; an
-- answer act answers a question at most once. UNIQUE (…, id, question_id) is
-- the target of the key by which a chosen option names the question of its
-- answer.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.elicitation_answer (
    id             BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      UUID    NOT NULL,
    scope_id       UUID    NOT NULL,

    answer_act_id  BIGINT  NOT NULL,
    question_id    BIGINT  NOT NULL,

    CONSTRAINT uq_elicitation_answer_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_elicitation_answer_act_question
        UNIQUE (tenant_id, scope_id, answer_act_id, question_id),
    CONSTRAINT uq_elicitation_answer_id_question UNIQUE (tenant_id, scope_id, id, question_id),
    CONSTRAINT fk_elicitation_answer_act_answer
        FOREIGN KEY (tenant_id, scope_id, answer_act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, id),
    CONSTRAINT fk_elicitation_answer_elicitation_question
        FOREIGN KEY (tenant_id, scope_id, question_id)
        REFERENCES dispatch.elicitation_question (tenant_id, scope_id, id)
);

ALTER TABLE dispatch.elicitation_answer ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.elicitation_answer FORCE  ROW LEVEL SECURITY;
CREATE POLICY elicitation_answer_tenant_isolation ON dispatch.elicitation_answer
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.elicitation_answer TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.elicitation_answer IS
    'The answer an answer act gives to one question of a bundle.';

-- ---------------------------------------------------------------------------
-- 7. elicitation_answer_option: an option an answer chooses. The two keys
-- share question_id, so the option chosen belongs to the question of its
-- answer.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.elicitation_answer_option (
    id           BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    UUID    NOT NULL,
    scope_id     UUID    NOT NULL,

    answer_id    BIGINT  NOT NULL,
    question_id  BIGINT  NOT NULL,
    option_id    BIGINT  NOT NULL,

    CONSTRAINT uq_elicitation_answer_option_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT uq_elicitation_answer_option_option
        UNIQUE (tenant_id, scope_id, answer_id, option_id),
    CONSTRAINT fk_elicitation_answer_option_elicitation_answer
        FOREIGN KEY (tenant_id, scope_id, answer_id, question_id)
        REFERENCES dispatch.elicitation_answer (tenant_id, scope_id, id, question_id),
    CONSTRAINT fk_elicitation_answer_option_elicitation_option
        FOREIGN KEY (tenant_id, scope_id, question_id, option_id)
        REFERENCES dispatch.elicitation_option (tenant_id, scope_id, question_id, id)
);

ALTER TABLE dispatch.elicitation_answer_option ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.elicitation_answer_option FORCE  ROW LEVEL SECURITY;
CREATE POLICY elicitation_answer_option_tenant_isolation ON dispatch.elicitation_answer_option
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.elicitation_answer_option TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.elicitation_answer_option IS
    'An option an answer chooses, one of the options of the question it answers.';

-- ---------------------------------------------------------------------------
-- 8. elicitation_content: the texts of the complex, after the pattern of
-- content. A text hangs on the act that wrote it: a question text on its ask
-- act, an answer text on its answer act, an addendum on its annotate act. A
-- question text belongs to its question, an answer text to its answer; an
-- addendum supplements another text of the complex. The texts stand apart
-- from the questions and answers, so that a listing of these carries none.
-- ---------------------------------------------------------------------------
CREATE TABLE dispatch.elicitation_content (
    id                      BIGINT  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id               UUID    NOT NULL,
    scope_id                UUID    NOT NULL,

    act_id                  BIGINT  NOT NULL,
    kind                    TEXT    NOT NULL,
    question_id             BIGINT,
    answer_id               BIGINT,
    supplements_content_id  BIGINT,
    media_type              TEXT    NOT NULL,
    body                    TEXT    NOT NULL,

    CONSTRAINT uq_elicitation_content_tenant_scope_id UNIQUE (tenant_id, scope_id, id),
    CONSTRAINT ck_elicitation_content_kind CHECK (kind IN ('question', 'answer', 'addendum')),
    -- Each kind names exactly its own reference and none of the others.
    CONSTRAINT ck_elicitation_content_question
        CHECK ((kind = 'question') = (question_id IS NOT NULL)),
    CONSTRAINT ck_elicitation_content_answer
        CHECK ((kind = 'answer') = (answer_id IS NOT NULL)),
    CONSTRAINT ck_elicitation_content_supplements
        CHECK ((kind = 'addendum') = (supplements_content_id IS NOT NULL)),
    CONSTRAINT ck_elicitation_content_supplements_other
        CHECK (supplements_content_id IS NULL OR supplements_content_id <> id),
    CONSTRAINT ck_elicitation_content_question_body
        CHECK (kind <> 'question' OR char_length(body) <= 2000),
    CONSTRAINT fk_elicitation_content_act
        FOREIGN KEY (tenant_id, scope_id, act_id)
        REFERENCES dispatch.act (tenant_id, scope_id, id),
    CONSTRAINT fk_elicitation_content_elicitation_question
        FOREIGN KEY (tenant_id, scope_id, question_id)
        REFERENCES dispatch.elicitation_question (tenant_id, scope_id, id),
    CONSTRAINT fk_elicitation_content_elicitation_answer
        FOREIGN KEY (tenant_id, scope_id, answer_id)
        REFERENCES dispatch.elicitation_answer (tenant_id, scope_id, id),
    CONSTRAINT fk_elicitation_content_elicitation_content_supplements
        FOREIGN KEY (tenant_id, scope_id, supplements_content_id)
        REFERENCES dispatch.elicitation_content (tenant_id, scope_id, id)
);

-- At most one question text per question and one answer text per answer.
CREATE UNIQUE INDEX uq_elicitation_content_question
    ON dispatch.elicitation_content (tenant_id, scope_id, question_id)
    WHERE kind = 'question';
CREATE UNIQUE INDEX uq_elicitation_content_answer
    ON dispatch.elicitation_content (tenant_id, scope_id, answer_id)
    WHERE kind = 'answer';
CREATE INDEX idx_elicitation_content_act
    ON dispatch.elicitation_content (tenant_id, scope_id, act_id);

ALTER TABLE dispatch.elicitation_content ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.elicitation_content FORCE  ROW LEVEL SECURITY;
CREATE POLICY elicitation_content_tenant_isolation ON dispatch.elicitation_content
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON dispatch.elicitation_content TO kumbuka_dispatch;

COMMENT ON TABLE dispatch.elicitation_content IS
    'A text of the elicitation complex an act writes: a question text, an answer text, or an addendum to one of them.';
