-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- cutover-copy.sql
--
-- The copy of the stock: reads the old store dispatch_legacy (line
-- db/migration, V1 to V25, renamed by cutover-rename.sql) and writes the new
-- store dispatch (line db/store, V1 to V5). Not a migration; no build and no
-- service runs it. The operator runs it with psql:
--
--     psql -v ON_ERROR_STOP=1 -U <role reading every tenant> -d <database> \
--         -c "SET kumbuka.cutover_actor = '<subject>'" -f cutover-copy.sql
--
-- (psql runs -c and -f in one session, so the setting reaches the script.)
--
-- One transaction, under a role that reads every tenant: a superuser or a
-- role with BYPASSRLS. Both stores are under FORCE ROW LEVEL SECURITY, and any
-- other role would copy the bound tenant and silently nothing of the others.
--
-- IT REFUSES WITHOUT WRITING, naming the reason and, where there is one, the
-- address of the task as <scope_id>/<selector>/<number>.<sub>, never a text
-- or a value:
--
--   - kumbuka.cutover_actor is not set or empty;
--   - dispatch_legacy.cutover.opened_at is set (or the table does not hold
--     exactly one row): after the opening the copy would empty what the new
--     store took since;
--   - the schemas do not stand as expected, or the role reads not every tenant;
--   - a derived act lacks its recorded actor;
--   - return_metadata stands on a task without a return text;
--   - a bracket has no root task <number>.0;
--   - an apparatus that ck_task_addressed_role would refuse;
--   - an addendum to a text other than a question or an answer without a
--     base text of its type;
--   - an answer without an ask act of its task not after it;
--   - a question text of more than 2000 characters;
--   - an addendum to a question or an answer whose base text cannot be
--     determined.
--
-- OTHERWISE it empties every table of the new store and copies the stock. A
-- second run gives the same stock; only the time of the import acts, the time
-- of the run, differs. At the end it reports numbers and addresses only, in
-- the table pg_temp.cutover_copy_report, which the last statement prints.
--
-- THE RULES, in short (migration/README.md has them in full):
--
--   - no text is rewritten, and no event the old store did not record is made
--     up; every act of the copy names its recorded actor as actor_subject and
--     actor_owner_subject, and no permission, path or authentication level;
--   - a selector gets definition_version 1 (seed, active) with one activity
--     default, and a counter behind the larger of its old counter and its
--     highest copied number;
--   - a bracket (root task, sub 0) is an instance with the title of its root
--     task, closed when the root task is closed: concluded when the root task
--     was accepted, withdrawn otherwise;
--   - the trace of an instance begins with one act import (actor from
--     kumbuka.cutover_actor, time of the run); the derived acts follow, by
--     time, then sub-number, then the order: send, claim of a holder,
--     deliver, ask, answer, annotate, the act of the present state, the act
--     that closes the instance;
--   - a task keeps its address, state, hold reason, resolution, not_before
--     and holder; addressed_role is its apparatus;
--   - a question is an ask act with a bundle of one question q1, an answer
--     an answer act answering q1 of the youngest ask act of its task not
--     after it; their texts and addenda stand in elicitation_content;
--   - a never-sent task is a draft without an act.
--
-- Every table is named with its schema; nothing relies on search_path.
-- ===========================================================================

BEGIN;

-- The report outlives the transaction, so the last statement can print it.
DROP TABLE IF EXISTS pg_temp.cutover_copy_report;
CREATE TEMPORARY TABLE pg_temp.cutover_copy_report (
    ordinal  INTEGER  GENERATED ALWAYS AS IDENTITY,
    section  TEXT     NOT NULL,
    subject  TEXT     NOT NULL,
    amount   BIGINT   NOT NULL
);

-- ---------------------------------------------------------------------------
-- 1. The setting, the schemas, the role and the mark.
-- ---------------------------------------------------------------------------
DO $guard$
DECLARE
    actor   TEXT := current_setting('kumbuka.cutover_actor', true);
    latest  TEXT;
    marks   BIGINT;
    opened  TIMESTAMPTZ;
BEGIN
    IF actor IS NULL OR btrim(actor) = '' THEN
        RAISE EXCEPTION 'cutover copy refused: the setting kumbuka.cutover_actor is not set '
            'or empty; run SET kumbuka.cutover_actor = ''<subject>'' before the copy'
            USING ERRCODE = 'KC001';
    END IF;

    IF to_regclass('dispatch_legacy.cutover') IS NULL
       OR to_regclass('dispatch_legacy.task') IS NULL THEN
        RAISE EXCEPTION 'cutover copy refused: there is no renamed old store dispatch_legacy '
            'with its table cutover; run cutover-rename.sql first'
            USING ERRCODE = 'KC001';
    END IF;

    IF to_regclass('dispatch.flyway_schema_history') IS NULL
       OR to_regclass('dispatch.elicitation_content') IS NULL THEN
        RAISE EXCEPTION 'cutover copy refused: the schema dispatch does not hold the store '
            'line db/store'
            USING ERRCODE = 'KC001';
    END IF;

    SELECT h.version INTO latest
      FROM dispatch.flyway_schema_history h
     WHERE h.version IS NOT NULL AND h.success
     ORDER BY h.installed_rank DESC
     LIMIT 1;
    IF latest IS DISTINCT FROM '5' THEN
        RAISE EXCEPTION 'cutover copy refused: the store line stands at version %, expected 5',
            coalesce(latest, 'none')
            USING ERRCODE = 'KC001';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles r
                    WHERE r.rolname = current_user AND (r.rolsuper OR r.rolbypassrls)) THEN
        RAISE EXCEPTION 'cutover copy refused: the copy runs under a role that reads every '
            'tenant, a superuser or a role with BYPASSRLS; % is neither', current_user
            USING ERRCODE = 'KC001';
    END IF;

    SELECT count(*), max(c.opened_at) INTO marks, opened FROM dispatch_legacy.cutover c;
    IF marks <> 1 THEN
        RAISE EXCEPTION 'cutover copy refused: dispatch_legacy.cutover holds % rows, expected '
            'exactly one', marks
            USING ERRCODE = 'KC001';
    END IF;
    IF opened IS NOT NULL THEN
        RAISE EXCEPTION 'cutover copy refused: the new store is open since % '
            '(dispatch_legacy.cutover.opened_at is set), and a copy now would empty what it '
            'took since', opened
            USING ERRCODE = 'KC001';
    END IF;
END
$guard$;

-- ---------------------------------------------------------------------------
-- 2. The tasks of the old store, read once, with their addresses.
-- ---------------------------------------------------------------------------
CREATE TEMPORARY TABLE pg_temp.cutover_task ON COMMIT DROP AS
SELECT t.id AS legacy_id, t.tenant_id, t.scope_id, t.selector_id AS legacy_selector_id,
       s.name AS selector, t.number, t.sub, t.state, t.hold_reason, t.outcome,
       t.state_changed_at, t.state_changed_by, t.question_options, t.not_before,
       t.lapse_count, t.holder_subject, t.holder_receipt_hash, t.lease_expires_at,
       t.curated_in_id, t.title, t.apparatus, t.dispatch_metadata, t.return_metadata,
       t.first_taken_at,
       t.scope_id::text || '/' || s.name || '/' || t.number || '.' || t.sub AS address,
       NULL::BIGINT AS instance_id,
       NULL::BIGINT AS task_id
  FROM dispatch_legacy.task t
  JOIN dispatch_legacy.selector s ON s.tenant_id = t.tenant_id AND s.id = t.selector_id;

-- ---------------------------------------------------------------------------
-- 3. What the stock must satisfy, checked before anything is written.
-- ---------------------------------------------------------------------------
DO $stock$
DECLARE
    offenders TEXT;
BEGIN
    -- Every act the rules derive names its recorded actor: the dispatch text
    -- of a sent task (send), each return, question and answer text (deliver,
    -- ask, answer), each addendum (annotate), and the state change behind the
    -- act of the present state. The claim of a holder names the holder.
    SELECT string_agg(DISTINCT c.address, ', ' ORDER BY c.address) INTO offenders
      FROM pg_temp.cutover_task c
     WHERE c.state <> 'draft'
       AND (NOT EXISTS (SELECT 1 FROM dispatch_legacy.task_text x
                         WHERE x.task_id = c.legacy_id AND x.text_type = 'dispatch'
                           AND x.addendum_suffix IS NULL AND x.created_by IS NOT NULL)
            OR EXISTS (SELECT 1 FROM dispatch_legacy.task_text x
                        WHERE x.task_id = c.legacy_id AND x.created_by IS NULL
                          AND (x.addendum_suffix IS NOT NULL
                               OR x.text_type IN ('dispatch', 'return', 'question', 'answer')))
            OR (c.state_changed_by IS NULL
                AND ((c.state = 'active' AND c.holder_subject IS NULL)
                     OR (c.state = 'on_hold' AND c.hold_reason IN ('dependency', 'external'))
                     OR (c.state = 'open' AND c.not_before IS NOT NULL)
                     OR c.state = 'closed')));
    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION 'cutover copy refused: an act the copy derives has no recorded actor, '
            'at %', offenders
            USING ERRCODE = 'KC003';
    END IF;

    SELECT string_agg(c.address, ', ' ORDER BY c.address) INTO offenders
      FROM pg_temp.cutover_task c
     WHERE c.return_metadata IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM dispatch_legacy.task_text x
                        WHERE x.task_id = c.legacy_id AND x.text_type = 'return'
                          AND x.addendum_suffix IS NULL);
    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION 'cutover copy refused: return_metadata stands on a task without a '
            'return text, at %', offenders
            USING ERRCODE = 'KC003';
    END IF;

    SELECT string_agg(c.address, ', ' ORDER BY c.address) INTO offenders
      FROM pg_temp.cutover_task c
     WHERE NOT EXISTS (SELECT 1 FROM pg_temp.cutover_task r
                        WHERE r.legacy_selector_id = c.legacy_selector_id
                          AND r.number = c.number AND r.sub = 0);
    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION 'cutover copy refused: a bracket has no root task <number>.0, at %',
            offenders
            USING ERRCODE = 'KC003';
    END IF;

    -- The pattern of ck_task_addressed_role, ck_commission_addressed_role and
    -- ck_draft_addressed_role (db/store V4), checked here so that the refusal
    -- names an address instead of a failing row.
    SELECT string_agg(c.address, ', ' ORDER BY c.address) INTO offenders
      FROM pg_temp.cutover_task c
     WHERE c.apparatus !~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$';
    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION 'cutover copy refused: an apparatus is not admitted as addressed_role, '
            'at %', offenders
            USING ERRCODE = 'KC003';
    END IF;

    SELECT string_agg(c.address || x.addendum_suffix, ', '
                      ORDER BY c.address || x.addendum_suffix) INTO offenders
      FROM pg_temp.cutover_task c
      JOIN dispatch_legacy.task_text x ON x.task_id = c.legacy_id
     WHERE x.addendum_suffix IS NOT NULL
       AND x.text_type NOT IN ('question', 'answer')
       AND NOT EXISTS (SELECT 1 FROM dispatch_legacy.task_text b
                        WHERE b.task_id = x.task_id AND b.text_type = x.text_type
                          AND b.addendum_suffix IS NULL);
    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION 'cutover copy refused: an addendum has no base text of its type, at %',
            offenders
            USING ERRCODE = 'KC003';
    END IF;

    -- An answer answers q1 of the youngest ask act of its task that is not
    -- after it, the act of the youngest question text not younger than the
    -- answer; an answer without one has no question to answer.
    SELECT string_agg(DISTINCT c.address, ', ' ORDER BY c.address) INTO offenders
      FROM pg_temp.cutover_task c
      JOIN dispatch_legacy.task_text x
        ON x.task_id = c.legacy_id AND x.text_type = 'answer' AND x.addendum_suffix IS NULL
     WHERE c.state <> 'draft'
       AND NOT EXISTS (SELECT 1 FROM dispatch_legacy.task_text q
                        WHERE q.task_id = c.legacy_id AND q.text_type = 'question'
                          AND q.addendum_suffix IS NULL AND q.created_at <= x.created_at);
    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION 'cutover copy refused: an answer has no ask act of its task that is not '
            'after it, at %', offenders
            USING ERRCODE = 'KC003';
    END IF;

    -- The bound of ck_elicitation_content_question_body (db/store V5), checked
    -- here so that the refusal names an address instead of a failing row.
    SELECT string_agg(DISTINCT c.address, ', ' ORDER BY c.address) INTO offenders
      FROM pg_temp.cutover_task c
      JOIN dispatch_legacy.task_text x
        ON x.task_id = c.legacy_id AND x.text_type = 'question' AND x.addendum_suffix IS NULL
     WHERE c.state <> 'draft' AND char_length(x.text) > 2000;
    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION 'cutover copy refused: a question text has more than 2000 characters, '
            'at %', offenders
            USING ERRCODE = 'KC003';
    END IF;

    -- An addendum to a question or an answer supplements its base text in
    -- elicitation_content; without a base text of its type there is none.
    SELECT string_agg(c.address || x.addendum_suffix, ', '
                      ORDER BY c.address || x.addendum_suffix) INTO offenders
      FROM pg_temp.cutover_task c
      JOIN dispatch_legacy.task_text x ON x.task_id = c.legacy_id
     WHERE x.addendum_suffix IS NOT NULL
       AND x.text_type IN ('question', 'answer')
       AND NOT EXISTS (SELECT 1 FROM dispatch_legacy.task_text b
                        WHERE b.task_id = x.task_id AND b.text_type = x.text_type
                          AND b.addendum_suffix IS NULL);
    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION 'cutover copy refused: an addendum to a question or an answer has no '
            'base text to supplement, at %', offenders
            USING ERRCODE = 'KC003';
    END IF;
END
$stock$;

-- ---------------------------------------------------------------------------
-- 4. The new store, emptied. Every table of V1 to V5; the identities restart,
-- so that a second run gives the same stock.
-- ---------------------------------------------------------------------------
TRUNCATE TABLE
    dispatch.selector, dispatch.definition_version, dispatch.definition_act,
    dispatch.definition_tag, dispatch.activity, dispatch.outcome_label,
    dispatch.instance, dispatch.task, dispatch.act,
    dispatch.commission, dispatch.commission_criterion, dispatch.holding, dispatch.receipt,
    dispatch.lease, dispatch.delivery,
    dispatch.delivery_criterion, dispatch.instance_return, dispatch.external_reference,
    dispatch.idempotency_key,
    dispatch.free_fields, dispatch.content,
    dispatch.draft, dispatch.draft_criterion, dispatch.draft_free_fields, dispatch.draft_text,
    dispatch.enumeration, dispatch.enumeration_value, dispatch.control_field,
    dispatch.contract_field, dispatch.field_value, dispatch.instance_field_value,
    dispatch.elicitation_question, dispatch.elicitation_option, dispatch.elicitation_answer,
    dispatch.elicitation_answer_option, dispatch.elicitation_content
    RESTART IDENTITY;

-- ---------------------------------------------------------------------------
-- 5. Definition: a selector per old selector, version 1 as the seed, one
-- activity default with no outcome labels, no control fields and no rules.
-- The counter stands behind the larger of the old counter and the highest
-- copied number, so a number once issued is never issued again.
-- ---------------------------------------------------------------------------
INSERT INTO dispatch.selector (tenant_id, scope_id, name, next_number)
SELECT s.tenant_id, s.scope_id, s.name,
       greatest(s.next_number,
                coalesce((SELECT max(t.number) FROM dispatch_legacy.task t
                           WHERE t.tenant_id = s.tenant_id AND t.selector_id = s.id), 0) + 1)
  FROM dispatch_legacy.selector s
 ORDER BY s.id;

INSERT INTO dispatch.definition_version (tenant_id, scope_id, selector_id, version,
                                         verdict_required, outcome_required, state, seed)
SELECT n.tenant_id, n.scope_id, n.id, 1, false, false, 'active', true
  FROM dispatch.selector n
 ORDER BY n.id;

INSERT INTO dispatch.activity (tenant_id, scope_id, definition_version_id, name,
                               verdict_required, outcome_required, criteria_required,
                               input_kind, may_ask, starts_excluded)
SELECT v.tenant_id, v.scope_id, v.id, 'default', false, false, false, 'text', true, false
  FROM dispatch.definition_version v
 ORDER BY v.id;

-- ---------------------------------------------------------------------------
-- 6. Instances, one per root task, and the tasks with their addresses.
-- ---------------------------------------------------------------------------
INSERT INTO dispatch.instance (tenant_id, scope_id, selector_id, number, title,
                               definition_version_id, next_sub, state, resolution)
SELECT r.tenant_id, r.scope_id, n.id, r.number, r.title, v.id,
       (SELECT max(c.sub) FROM pg_temp.cutover_task c
         WHERE c.legacy_selector_id = r.legacy_selector_id AND c.number = r.number) + 1,
       CASE WHEN r.state = 'closed' THEN 'closed' ELSE 'running' END,
       CASE WHEN r.state <> 'closed' THEN NULL
            WHEN r.outcome = 'accepted' THEN 'concluded'
            ELSE 'withdrawn' END
  FROM pg_temp.cutover_task r
  JOIN dispatch_legacy.selector l ON l.tenant_id = r.tenant_id AND l.id = r.legacy_selector_id
  JOIN dispatch.selector n
    ON n.tenant_id = l.tenant_id AND n.scope_id = l.scope_id AND n.name = l.name
  JOIN dispatch.definition_version v
    ON v.tenant_id = n.tenant_id AND v.scope_id = n.scope_id AND v.selector_id = n.id
   AND v.version = 1
 WHERE r.sub = 0
 ORDER BY r.legacy_selector_id, r.number;

UPDATE pg_temp.cutover_task c
   SET instance_id = i.id
  FROM dispatch_legacy.selector l
  JOIN dispatch.selector n
    ON n.tenant_id = l.tenant_id AND n.scope_id = l.scope_id AND n.name = l.name
  JOIN dispatch.instance i
    ON i.tenant_id = n.tenant_id AND i.scope_id = n.scope_id AND i.selector_id = n.id
 WHERE l.tenant_id = c.tenant_id AND l.id = c.legacy_selector_id AND i.number = c.number;

INSERT INTO dispatch.task (tenant_id, scope_id, instance_id, sub, title, activity_id, state,
                           hold_reason, resolution, not_before, parked, addressed_role)
SELECT c.tenant_id, c.scope_id, c.instance_id, c.sub, c.title, NULL, c.state,
       c.hold_reason, c.outcome, c.not_before,
       c.state = 'on_hold' AND c.hold_reason = 'external' AND c.holder_subject IS NULL
           AND c.lapse_count >= 3,
       c.apparatus
  FROM pg_temp.cutover_task c
 ORDER BY c.instance_id, c.sub;

UPDATE pg_temp.cutover_task c
   SET task_id = t.id
  FROM dispatch.task t
 WHERE t.tenant_id = c.tenant_id AND t.scope_id = c.scope_id
   AND t.instance_id = c.instance_id AND t.sub = c.sub;

-- ---------------------------------------------------------------------------
-- 7. The derived acts, first as events: what the old store records, and
-- nothing more. rank is the order of the rules at equal time and sub-number.
-- ---------------------------------------------------------------------------
CREATE TEMPORARY TABLE pg_temp.cutover_event (
    event_id            BIGINT       GENERATED ALWAYS AS IDENTITY,
    legacy_task_id      BIGINT       NOT NULL,
    tenant_id           UUID         NOT NULL,
    scope_id            UUID         NOT NULL,
    instance_id         BIGINT       NOT NULL,
    task_id             BIGINT,
    sub                 INTEGER,
    on_task             BOOLEAN      NOT NULL,
    kind                TEXT         NOT NULL,
    rank                INTEGER      NOT NULL,
    at                  TIMESTAMPTZ  NOT NULL,
    sort_at             TIMESTAMPTZ,
    actor               TEXT         NOT NULL,
    text_id             BIGINT,
    content_kind        TEXT,
    to_state            TEXT,
    hold_reason         TEXT,
    not_before          TIMESTAMPTZ,
    free_text_admitted  BOOLEAN,
    options             JSONB,
    options_recorded    BOOLEAN,
    holder_claim        BOOLEAN      NOT NULL DEFAULT false,
    sequence            INTEGER,
    act_id              BIGINT,
    asked_event_id      BIGINT
) ON COMMIT DROP;

-- send: every task but a draft, at the time and by the actor of its dispatch
-- text; it carries the commission.
INSERT INTO pg_temp.cutover_event (legacy_task_id, tenant_id, scope_id, instance_id, task_id,
                                   sub, on_task, kind, rank, at, actor, text_id, content_kind,
                                   to_state)
SELECT c.legacy_id, c.tenant_id, c.scope_id, c.instance_id, c.task_id, c.sub, true, 'send', 1,
       x.created_at, x.created_by, x.id, 'commission', 'open'
  FROM pg_temp.cutover_task c
  JOIN dispatch_legacy.task_text x
    ON x.task_id = c.legacy_id AND x.text_type = 'dispatch' AND x.addendum_suffix IS NULL
 WHERE c.state <> 'draft'
 ORDER BY c.legacy_id, x.id;

-- deliver: every return text.
INSERT INTO pg_temp.cutover_event (legacy_task_id, tenant_id, scope_id, instance_id, task_id,
                                   sub, on_task, kind, rank, at, actor, text_id, content_kind,
                                   to_state)
SELECT c.legacy_id, c.tenant_id, c.scope_id, c.instance_id, c.task_id, c.sub, true, 'deliver', 3,
       x.created_at, x.created_by, x.id, 'return', 'delivered'
  FROM pg_temp.cutover_task c
  JOIN dispatch_legacy.task_text x
    ON x.task_id = c.legacy_id AND x.text_type = 'return' AND x.addendum_suffix IS NULL
 WHERE c.state <> 'draft'
 ORDER BY c.legacy_id, x.id;

-- ask: every question text, a bundle of one question. The old store holds
-- question_options for the open question only, the youngest question of a
-- task on hold for a question; its options and free_text are taken from
-- there, with selection one. Every other question has selection none and
-- admits free text: that value is set by the copy, not recorded, and the
-- report counts it.
INSERT INTO pg_temp.cutover_event (legacy_task_id, tenant_id, scope_id, instance_id, task_id,
                                   sub, on_task, kind, rank, at, actor, text_id, content_kind,
                                   to_state, free_text_admitted, options, options_recorded)
SELECT c.legacy_id, c.tenant_id, c.scope_id, c.instance_id, c.task_id, c.sub, true, 'ask', 4,
       x.created_at, x.created_by, x.id, 'question', 'on_hold',
       CASE WHEN o.recorded THEN (c.question_options ->> 'free_text')::boolean ELSE true END,
       CASE WHEN o.recorded THEN c.question_options -> 'options' END,
       o.recorded
  FROM pg_temp.cutover_task c
  JOIN dispatch_legacy.task_text x
    ON x.task_id = c.legacy_id AND x.text_type = 'question' AND x.addendum_suffix IS NULL
 CROSS JOIN LATERAL (
       SELECT c.state = 'on_hold' AND c.hold_reason = 'question'
              AND c.question_options IS NOT NULL
              AND x.id = (SELECT q.id FROM dispatch_legacy.task_text q
                           WHERE q.task_id = c.legacy_id AND q.text_type = 'question'
                             AND q.addendum_suffix IS NULL
                           ORDER BY q.created_at DESC, q.id DESC
                           LIMIT 1) AS recorded
     ) o
 WHERE c.state <> 'draft'
 ORDER BY c.legacy_id, x.id;

-- answer: every answer text.
INSERT INTO pg_temp.cutover_event (legacy_task_id, tenant_id, scope_id, instance_id, task_id,
                                   sub, on_task, kind, rank, at, actor, text_id, content_kind,
                                   to_state)
SELECT c.legacy_id, c.tenant_id, c.scope_id, c.instance_id, c.task_id, c.sub, true, 'answer', 5,
       x.created_at, x.created_by, x.id, 'answer', 'active'
  FROM pg_temp.cutover_task c
  JOIN dispatch_legacy.task_text x
    ON x.task_id = c.legacy_id AND x.text_type = 'answer' AND x.addendum_suffix IS NULL
 WHERE c.state <> 'draft'
 ORDER BY c.legacy_id, x.id;

-- annotate: every text with a suffix; its state is the task's at that point
-- of the trace and is filled in below.
INSERT INTO pg_temp.cutover_event (legacy_task_id, tenant_id, scope_id, instance_id, task_id,
                                   sub, on_task, kind, rank, at, actor, text_id, content_kind)
SELECT c.legacy_id, c.tenant_id, c.scope_id, c.instance_id, c.task_id, c.sub, true, 'annotate', 6,
       x.created_at, x.created_by, x.id, 'addendum'
  FROM pg_temp.cutover_task c
  JOIN dispatch_legacy.task_text x
    ON x.task_id = c.legacy_id AND x.addendum_suffix IS NOT NULL
 WHERE c.state <> 'draft'
 ORDER BY c.legacy_id, x.id;

-- claim of a holder: every sent task with a holder, active, on hold or
-- delivered, by the holder, at the first take-up or else at the send.
INSERT INTO pg_temp.cutover_event (legacy_task_id, tenant_id, scope_id, instance_id, task_id,
                                   sub, on_task, kind, rank, at, actor, to_state, holder_claim)
SELECT c.legacy_id, c.tenant_id, c.scope_id, c.instance_id, c.task_id, c.sub, true, 'claim', 2,
       coalesce(c.first_taken_at, s.at), c.holder_subject, 'active', true
  FROM pg_temp.cutover_task c
  JOIN pg_temp.cutover_event s ON s.legacy_task_id = c.legacy_id AND s.kind = 'send'
 WHERE c.state <> 'draft' AND c.holder_subject IS NOT NULL
 ORDER BY c.legacy_id;

-- The act of the present state, at state_changed_at by state_changed_by,
-- where no act above makes that state: claim for active without a holder,
-- hold for a dependency or an external cause, defer for open with
-- not_before, and the closing act for closed.
INSERT INTO pg_temp.cutover_event (legacy_task_id, tenant_id, scope_id, instance_id, task_id,
                                   sub, on_task, kind, rank, at, actor, to_state, hold_reason,
                                   not_before)
SELECT c.legacy_id, c.tenant_id, c.scope_id, c.instance_id, c.task_id, c.sub, true,
       CASE WHEN c.state = 'active' THEN 'claim'
            WHEN c.state = 'on_hold' THEN 'hold'
            WHEN c.state = 'open' THEN 'defer'
            WHEN c.outcome = 'accepted' THEN 'accept'
            WHEN c.outcome = 'rejected' THEN 'reject'
            WHEN c.outcome = 'failed' THEN 'fail'
            ELSE 'withdraw' END,
       7, c.state_changed_at, c.state_changed_by, c.state,
       CASE WHEN c.state = 'on_hold' THEN c.hold_reason END,
       CASE WHEN c.state = 'open' THEN c.not_before END
  FROM pg_temp.cutover_task c
 WHERE (c.state = 'active' AND c.holder_subject IS NULL)
    OR (c.state = 'on_hold' AND c.hold_reason IN ('dependency', 'external'))
    OR (c.state = 'open' AND c.not_before IS NOT NULL)
    OR c.state = 'closed'
 ORDER BY c.legacy_id;

-- The act that closes an instance whose root task is closed: conclude where
-- it was accepted, withdraw otherwise, at the time and by the actor of the
-- root task's change of state. It acts on the instance alone.
INSERT INTO pg_temp.cutover_event (legacy_task_id, tenant_id, scope_id, instance_id, task_id,
                                   sub, on_task, kind, rank, at, actor, to_state)
SELECT c.legacy_id, c.tenant_id, c.scope_id, c.instance_id, NULL, NULL, false,
       CASE WHEN c.outcome = 'accepted' THEN 'conclude' ELSE 'withdraw' END,
       8, c.state_changed_at, c.state_changed_by, 'closed'
  FROM pg_temp.cutover_task c
 WHERE c.sub = 0 AND c.state = 'closed'
 ORDER BY c.legacy_id;

-- Ordering: by time; the claim of a holder stands before the deliver, ask or
-- hold act that makes the task's present state.
UPDATE pg_temp.cutover_event e SET sort_at = e.at;

UPDATE pg_temp.cutover_event e
   SET sort_at = least(e.at, (
       SELECT max(o.at)
         FROM pg_temp.cutover_event o
         JOIN pg_temp.cutover_task c ON c.legacy_id = o.legacy_task_id
        WHERE o.legacy_task_id = e.legacy_task_id AND o.on_task
          AND ((c.state = 'delivered' AND o.kind = 'deliver')
               OR (c.state = 'on_hold' AND c.hold_reason = 'question' AND o.kind = 'ask')
               OR (c.state = 'on_hold' AND o.kind = 'hold'))))
 WHERE e.holder_claim;

-- Sequence 1 is the import act of each instance.
UPDATE pg_temp.cutover_event e
   SET sequence = o.sequence
  FROM (SELECT v.event_id,
               (1 + row_number() OVER (PARTITION BY v.instance_id
                                       ORDER BY v.sort_at, coalesce(v.sub, 2147483647), v.rank,
                                                v.text_id NULLS FIRST, v.event_id))::INTEGER
                   AS sequence
          FROM pg_temp.cutover_event v) o
 WHERE o.event_id = e.event_id;

-- An addendum changes no state: its act carries the state of its task after
-- the act before it, or the present state where there is none.
UPDATE pg_temp.cutover_event e
   SET to_state = coalesce(
       (SELECT p.to_state FROM pg_temp.cutover_event p
         WHERE p.legacy_task_id = e.legacy_task_id AND p.on_task AND p.kind <> 'annotate'
           AND p.sequence < e.sequence
         ORDER BY p.sequence DESC
         LIMIT 1),
       c.state)
  FROM pg_temp.cutover_task c
 WHERE c.legacy_id = e.legacy_task_id AND e.kind = 'annotate';

-- An answer answers q1 of the youngest ask act of its task that is not after
-- it; section 3 refused an answer without one.
UPDATE pg_temp.cutover_event e
   SET asked_event_id = (
       SELECT a.event_id FROM pg_temp.cutover_event a
        WHERE a.legacy_task_id = e.legacy_task_id AND a.kind = 'ask' AND a.at <= e.at
        ORDER BY a.sequence DESC
        LIMIT 1)
 WHERE e.kind = 'answer';

-- ---------------------------------------------------------------------------
-- 8. The acts: the import act, the acts on tasks, the closing acts.
-- ---------------------------------------------------------------------------
INSERT INTO dispatch.act (tenant_id, scope_id, instance_id, sequence, task_id, kind,
                          actor_subject, actor_owner_subject, at, to_state)
SELECT i.tenant_id, i.scope_id, i.id, 1, NULL, 'import',
       current_setting('kumbuka.cutover_actor'), current_setting('kumbuka.cutover_actor'),
       now(), 'running'
  FROM dispatch.instance i
 ORDER BY i.id;

-- The old store knows no mark human_required, and a copied question does not
-- raise it; every copied answer is answered, since the old store records no
-- declined answer.
INSERT INTO dispatch.act (tenant_id, scope_id, instance_id, sequence, task_id, kind,
                          actor_subject, actor_owner_subject, at, to_state, hold_reason,
                          not_before, human_required, answer_outcome)
SELECT e.tenant_id, e.scope_id, e.instance_id, e.sequence, e.task_id, e.kind,
       e.actor, e.actor, e.at, e.to_state, e.hold_reason, e.not_before,
       CASE WHEN e.kind = 'ask' THEN false END,
       CASE WHEN e.kind = 'answer' THEN 'answered' END
  FROM pg_temp.cutover_event e
 WHERE e.on_task
 ORDER BY e.instance_id, e.sequence;

UPDATE pg_temp.cutover_event e
   SET act_id = a.id
  FROM dispatch.act a
 WHERE a.tenant_id = e.tenant_id AND a.scope_id = e.scope_id
   AND a.instance_id = e.instance_id AND a.sequence = e.sequence AND e.on_task;

-- A conclude refers to the deliver act of the valid return of the root
-- task, the youngest.
INSERT INTO dispatch.act (tenant_id, scope_id, instance_id, sequence, task_id, kind,
                          actor_subject, actor_owner_subject, at, to_state, refers_to_act_id)
SELECT e.tenant_id, e.scope_id, e.instance_id, e.sequence, NULL, e.kind,
       e.actor, e.actor, e.at, e.to_state,
       CASE WHEN e.kind = 'conclude' THEN
           (SELECT d.act_id FROM pg_temp.cutover_event d
             WHERE d.legacy_task_id = e.legacy_task_id AND d.kind = 'deliver'
             ORDER BY d.at DESC, d.text_id DESC
             LIMIT 1) END
  FROM pg_temp.cutover_event e
 WHERE NOT e.on_task
 ORDER BY e.instance_id, e.sequence;

UPDATE pg_temp.cutover_event e
   SET act_id = a.id
  FROM dispatch.act a
 WHERE a.tenant_id = e.tenant_id AND a.scope_id = e.scope_id
   AND a.instance_id = e.instance_id AND a.sequence = e.sequence AND NOT e.on_task;

-- The return of the instance, on its closing act, without verdict or outcome.
INSERT INTO dispatch.instance_return (tenant_id, scope_id, act_id)
SELECT e.tenant_id, e.scope_id, e.act_id
  FROM pg_temp.cutover_event e
 WHERE NOT e.on_task
 ORDER BY e.act_id;

-- ---------------------------------------------------------------------------
-- 9. What hangs on the acts: commission, the elicitation complex, holding,
-- receipt, lease.
-- ---------------------------------------------------------------------------
INSERT INTO dispatch.commission (tenant_id, scope_id, instance_id, act_id, title, activity_id,
                                 addressed_role)
SELECT e.tenant_id, e.scope_id, e.instance_id, e.act_id, c.title, a.id, c.apparatus
  FROM pg_temp.cutover_event e
  JOIN pg_temp.cutover_task c ON c.legacy_id = e.legacy_task_id
  JOIN dispatch.instance i ON i.id = e.instance_id
  JOIN dispatch.activity a
    ON a.tenant_id = i.tenant_id AND a.scope_id = i.scope_id
   AND a.definition_version_id = i.definition_version_id
 WHERE e.kind = 'send'
 ORDER BY e.act_id;

-- Each ask act carries a bundle of one question q1, required: selection one
-- where the old store recorded options for it, none otherwise.
INSERT INTO dispatch.elicitation_question (tenant_id, scope_id, ask_act_id, identifier, ordinal,
                                           selection, free_text_admitted, required)
SELECT e.tenant_id, e.scope_id, e.act_id, 'q1', 1,
       CASE WHEN e.options_recorded THEN 'one' ELSE 'none' END,
       e.free_text_admitted, true
  FROM pg_temp.cutover_event e
 WHERE e.kind = 'ask'
 ORDER BY e.act_id;

-- The options recorded, in their order, as o1, o2, ...
INSERT INTO dispatch.elicitation_option (tenant_id, scope_id, question_id, identifier, label,
                                         ordinal)
SELECT q.tenant_id, q.scope_id, q.id, 'o' || o.ordinal, o.label, o.ordinal::INTEGER
  FROM pg_temp.cutover_event e
  JOIN dispatch.elicitation_question q
    ON q.tenant_id = e.tenant_id AND q.scope_id = e.scope_id AND q.ask_act_id = e.act_id
 CROSS JOIN LATERAL jsonb_array_elements_text(e.options) WITH ORDINALITY AS o(label, ordinal)
 WHERE e.kind = 'ask' AND e.options IS NOT NULL
 ORDER BY q.id, o.ordinal;

-- Each answer act answers q1 of its ask act, by text alone: the old store
-- records no option chosen, so no elicitation_answer_option is written.
INSERT INTO dispatch.elicitation_answer (tenant_id, scope_id, answer_act_id, question_id)
SELECT e.tenant_id, e.scope_id, e.act_id, q.id
  FROM pg_temp.cutover_event e
  JOIN pg_temp.cutover_event a ON a.event_id = e.asked_event_id
  JOIN dispatch.elicitation_question q
    ON q.tenant_id = a.tenant_id AND q.scope_id = a.scope_id AND q.ask_act_id = a.act_id
   AND q.identifier = 'q1'
 WHERE e.kind = 'answer'
 ORDER BY e.act_id;

INSERT INTO dispatch.holding (tenant_id, scope_id, task_id, claim_act_id)
SELECT e.tenant_id, e.scope_id, e.task_id, e.act_id
  FROM pg_temp.cutover_event e
 WHERE e.holder_claim
 ORDER BY e.act_id;

INSERT INTO dispatch.receipt (tenant_id, scope_id, holding_id, receipt_hash, issued_at)
SELECT h.tenant_id, h.scope_id, h.id, c.holder_receipt_hash, e.at
  FROM dispatch.holding h
  JOIN pg_temp.cutover_event e ON e.act_id = h.claim_act_id
  JOIN pg_temp.cutover_task c ON c.legacy_id = e.legacy_task_id
 ORDER BY h.id;

INSERT INTO dispatch.lease (tenant_id, scope_id, holding_id, act_id, expires_at)
SELECT h.tenant_id, h.scope_id, h.id, h.claim_act_id, c.lease_expires_at
  FROM dispatch.holding h
  JOIN pg_temp.cutover_event e ON e.act_id = h.claim_act_id
  JOIN pg_temp.cutover_task c ON c.legacy_id = e.legacy_task_id
 WHERE c.state = 'active' AND c.lease_expires_at IS NOT NULL
 ORDER BY h.id;

-- ---------------------------------------------------------------------------
-- 10. Content. Every text keeps its body. A remark hangs on the last derived
-- act of its task whose time is not after the remark's; an addendum
-- supplements the text of its type it belongs to. Question and answer texts
-- and their addenda stand in elicitation_content, every other text in
-- content.
-- ---------------------------------------------------------------------------
CREATE TEMPORARY TABLE pg_temp.cutover_remark ON COMMIT DROP AS
SELECT x.id AS text_id, c.tenant_id, c.scope_id,
       coalesce(
           (SELECT e.act_id FROM pg_temp.cutover_event e
             WHERE e.legacy_task_id = c.legacy_id AND e.on_task AND e.at <= x.created_at
             ORDER BY e.sequence DESC
             LIMIT 1),
           (SELECT e.act_id FROM pg_temp.cutover_event e
             WHERE e.legacy_task_id = c.legacy_id AND e.on_task
             ORDER BY e.sequence
             LIMIT 1)) AS act_id,
       NOT EXISTS (SELECT 1 FROM pg_temp.cutover_event e
                    WHERE e.legacy_task_id = c.legacy_id AND e.on_task
                      AND e.at <= x.created_at) AS before_every_act
  FROM pg_temp.cutover_task c
  JOIN dispatch_legacy.task_text x
    ON x.task_id = c.legacy_id AND x.text_type = 'remark' AND x.addendum_suffix IS NULL
 WHERE c.state <> 'draft';

-- Which text became which row; elicitation says in which table it stands.
CREATE TEMPORARY TABLE pg_temp.cutover_content (
    text_id      BIGINT   PRIMARY KEY,
    content_id   BIGINT   NOT NULL,
    elicitation  BOOLEAN  NOT NULL
) ON COMMIT DROP;

DO $content$
DECLARE
    r     RECORD;
    made  BIGINT;
BEGIN
    FOR r IN
        SELECT e.tenant_id, e.scope_id, e.act_id, e.content_kind AS kind, e.text_id, x.text AS body
          FROM pg_temp.cutover_event e
          JOIN dispatch_legacy.task_text x ON x.id = e.text_id
         WHERE e.kind IN ('send', 'deliver')
        UNION ALL
        SELECT m.tenant_id, m.scope_id, m.act_id, 'remark', m.text_id, x.text
          FROM pg_temp.cutover_remark m
          JOIN dispatch_legacy.task_text x ON x.id = m.text_id
         ORDER BY 3, 5
    LOOP
        INSERT INTO dispatch.content (tenant_id, scope_id, act_id, kind, media_type, body)
        VALUES (r.tenant_id, r.scope_id, r.act_id, r.kind, 'text/plain', r.body)
        RETURNING id INTO made;
        INSERT INTO pg_temp.cutover_content (text_id, content_id, elicitation)
        VALUES (r.text_id, made, false);
    END LOOP;

    -- A question text on the question of its ask act, an answer text on the
    -- answer of its answer act.
    FOR r IN
        SELECT e.tenant_id, e.scope_id, e.act_id, e.content_kind AS kind, e.text_id,
               x.text AS body, q.id AS question_id, w.id AS answer_id
          FROM pg_temp.cutover_event e
          JOIN dispatch_legacy.task_text x ON x.id = e.text_id
          LEFT JOIN dispatch.elicitation_question q
            ON e.kind = 'ask' AND q.tenant_id = e.tenant_id AND q.scope_id = e.scope_id
           AND q.ask_act_id = e.act_id
          LEFT JOIN dispatch.elicitation_answer w
            ON e.kind = 'answer' AND w.tenant_id = e.tenant_id AND w.scope_id = e.scope_id
           AND w.answer_act_id = e.act_id
         WHERE e.kind IN ('ask', 'answer')
         ORDER BY e.act_id
    LOOP
        INSERT INTO dispatch.elicitation_content (tenant_id, scope_id, act_id, kind, question_id,
                                                  answer_id, media_type, body)
        VALUES (r.tenant_id, r.scope_id, r.act_id, r.kind, r.question_id, r.answer_id,
                'text/plain', r.body)
        RETURNING id INTO made;
        INSERT INTO pg_temp.cutover_content (text_id, content_id, elicitation)
        VALUES (r.text_id, made, true);
    END LOOP;

    -- The base of an addendum: the youngest text of its type on its task
    -- that is not younger than the addendum, or else the oldest. An addendum
    -- to a question or an answer supplements its base in elicitation_content.
    FOR r IN
        SELECT e.tenant_id, e.scope_id, e.act_id, e.text_id, x.text AS body,
               x.text_type IN ('question', 'answer') AS elicitation,
               coalesce(
                   (SELECT b.id FROM dispatch_legacy.task_text b
                     WHERE b.task_id = x.task_id AND b.text_type = x.text_type
                       AND b.addendum_suffix IS NULL AND b.created_at <= x.created_at
                     ORDER BY b.created_at DESC, b.id DESC
                     LIMIT 1),
                   (SELECT b.id FROM dispatch_legacy.task_text b
                     WHERE b.task_id = x.task_id AND b.text_type = x.text_type
                       AND b.addendum_suffix IS NULL
                     ORDER BY b.created_at, b.id
                     LIMIT 1)) AS base_text_id
          FROM pg_temp.cutover_event e
          JOIN dispatch_legacy.task_text x ON x.id = e.text_id
         WHERE e.kind = 'annotate'
         ORDER BY e.act_id
    LOOP
        IF r.elicitation THEN
            INSERT INTO dispatch.elicitation_content (tenant_id, scope_id, act_id, kind,
                                                      supplements_content_id, media_type, body)
            VALUES (r.tenant_id, r.scope_id, r.act_id, 'addendum',
                    (SELECT m.content_id FROM pg_temp.cutover_content m
                      WHERE m.text_id = r.base_text_id AND m.elicitation),
                    'text/plain', r.body)
            RETURNING id INTO made;
        ELSE
            INSERT INTO dispatch.content (tenant_id, scope_id, act_id, kind,
                                          supplements_content_id, media_type, body)
            VALUES (r.tenant_id, r.scope_id, r.act_id, 'addendum',
                    (SELECT m.content_id FROM pg_temp.cutover_content m
                      WHERE m.text_id = r.base_text_id AND NOT m.elicitation),
                    'text/plain', r.body)
            RETURNING id INTO made;
        END IF;
        INSERT INTO pg_temp.cutover_content (text_id, content_id, elicitation)
        VALUES (r.text_id, made, r.elicitation);
    END LOOP;
END
$content$;

-- ---------------------------------------------------------------------------
-- 11. Metadata, split by key and never by what a value says. On a sent root
-- task the listed keys become references of the instance; every other key,
-- unknown ones included, and on every other task the listed ones too, stays
-- with its value in the free fields of the send or of the valid deliver. A
-- never-sent task keeps its dispatch_metadata whole in its draft.
-- ---------------------------------------------------------------------------
CREATE TEMPORARY TABLE pg_temp.cutover_metadata ON COMMIT DROP AS
SELECT c.legacy_id, c.tenant_id, c.scope_id, c.instance_id, d.side, kv.key, kv.value,
       CASE
           WHEN c.state = 'draft' AND d.side = 'dispatch' THEN 'draft free fields'
           WHEN c.state = 'draft' THEN 'not carried: task never sent'
           WHEN c.sub = 0 AND kv.key IN ('items', 'intake-items') THEN 'reference of kind item'
           WHEN c.sub = 0 AND kv.key IN ('origin', 'predecessor', 'follow-up', 'merged',
                                         'handover-pr', 'handover-state')
               THEN 'reference of kind reference'
           WHEN d.side = 'dispatch' THEN 'free fields of send'
           ELSE 'free fields of deliver'
       END AS target,
       CASE WHEN jsonb_typeof(kv.value) = 'array'
                THEN (SELECT count(*) FROM jsonb_array_elements(kv.value) AS el(e)
                       WHERE jsonb_typeof(el.e) <> 'null')
            WHEN jsonb_typeof(kv.value) = 'null' THEN 0
            ELSE 1 END AS value_count
  FROM pg_temp.cutover_task c
 CROSS JOIN LATERAL (VALUES ('dispatch', c.dispatch_metadata),
                            ('return', c.return_metadata)) AS d(side, document)
 CROSS JOIN LATERAL jsonb_each(CASE WHEN jsonb_typeof(d.document) = 'object'
                                    THEN d.document ELSE '{}'::jsonb END) AS kv(key, value);

INSERT INTO dispatch.free_fields (tenant_id, scope_id, act_id, document)
SELECT f.tenant_id, f.scope_id, f.act_id, f.document
  FROM (SELECT e.tenant_id, e.scope_id, e.act_id,
               CASE WHEN c.sub = 0
                    THEN c.dispatch_metadata - ARRAY['items', 'intake-items', 'origin',
                         'predecessor', 'follow-up', 'merged', 'handover-pr', 'handover-state']
                    ELSE c.dispatch_metadata END AS document
          FROM pg_temp.cutover_event e
          JOIN pg_temp.cutover_task c ON c.legacy_id = e.legacy_task_id
         WHERE e.kind = 'send'
        UNION ALL
        SELECT e.tenant_id, e.scope_id, e.act_id,
               CASE WHEN c.sub = 0
                    THEN c.return_metadata - ARRAY['items', 'intake-items', 'origin',
                         'predecessor', 'follow-up', 'merged', 'handover-pr', 'handover-state']
                    ELSE c.return_metadata END
          FROM pg_temp.cutover_event e
          JOIN pg_temp.cutover_task c ON c.legacy_id = e.legacy_task_id
         WHERE e.kind = 'deliver'
           AND e.event_id = (SELECT d.event_id FROM pg_temp.cutover_event d
                              WHERE d.legacy_task_id = e.legacy_task_id AND d.kind = 'deliver'
                              ORDER BY d.at DESC, d.text_id DESC
                              LIMIT 1)) f
 WHERE f.document IS NOT NULL AND f.document <> '{}'::jsonb
 ORDER BY f.act_id;

-- Each value of a list is a reference; a value standing on both sides, or
-- twice, is one reference.
INSERT INTO dispatch.external_reference (tenant_id, scope_id, instance_id, kind, value,
                                         set_by_act_id)
SELECT DISTINCT ON (v.instance_id, v.kind, v.value)
       v.tenant_id, v.scope_id, v.instance_id, v.kind, v.value, a.id
  FROM (SELECT m.tenant_id, m.scope_id, m.instance_id,
               CASE WHEN m.target = 'reference of kind item' THEN 'item'
                    ELSE 'reference' END AS kind,
               CASE WHEN jsonb_typeof(el.e) = 'string' THEN el.e #>> '{}'
                    ELSE el.e::text END AS value
          FROM pg_temp.cutover_metadata m
         CROSS JOIN LATERAL jsonb_array_elements(
                   CASE WHEN jsonb_typeof(m.value) = 'array' THEN m.value
                        ELSE jsonb_build_array(m.value) END) AS el(e)
         WHERE m.target IN ('reference of kind item', 'reference of kind reference')
           AND jsonb_typeof(el.e) <> 'null') v
  JOIN dispatch.act a
    ON a.tenant_id = v.tenant_id AND a.scope_id = v.scope_id
   AND a.instance_id = v.instance_id AND a.sequence = 1
 ORDER BY v.instance_id, v.kind, v.value;

-- ---------------------------------------------------------------------------
-- 12. Tags of sent tasks and curations, as references of the task set by the
-- import act.
-- ---------------------------------------------------------------------------
INSERT INTO dispatch.external_reference (tenant_id, scope_id, task_id, kind, value,
                                         set_by_act_id)
SELECT c.tenant_id, c.scope_id, c.task_id, 'tag', g.tag_id::text, a.id
  FROM pg_temp.cutover_task c
  JOIN dispatch_legacy.task_tag g ON g.tenant_id = c.tenant_id AND g.task_id = c.legacy_id
  JOIN dispatch.act a
    ON a.tenant_id = c.tenant_id AND a.scope_id = c.scope_id
   AND a.instance_id = c.instance_id AND a.sequence = 1
 WHERE c.state <> 'draft'
 ORDER BY c.task_id, g.tag_id;

INSERT INTO dispatch.external_reference (tenant_id, scope_id, task_id, kind, value,
                                         set_by_act_id)
SELECT c.tenant_id, c.scope_id, c.task_id, 'curation', target.address, a.id
  FROM pg_temp.cutover_task c
  JOIN pg_temp.cutover_task target ON target.legacy_id = c.curated_in_id
  JOIN dispatch.act a
    ON a.tenant_id = c.tenant_id AND a.scope_id = c.scope_id
   AND a.instance_id = c.instance_id AND a.sequence = 1
 WHERE c.curated_in_id IS NOT NULL AND c.state <> 'draft'
 ORDER BY c.task_id;

-- ---------------------------------------------------------------------------
-- 13. The drafts: a never-sent task is a task in draft with its workspace and
-- no act.
-- ---------------------------------------------------------------------------
INSERT INTO dispatch.draft (tenant_id, scope_id, task_id, title, addressed_role)
SELECT c.tenant_id, c.scope_id, c.task_id, c.title, c.apparatus
  FROM pg_temp.cutover_task c
 WHERE c.state = 'draft'
 ORDER BY c.task_id;

INSERT INTO dispatch.draft_text (tenant_id, scope_id, draft_id, kind, media_type, body)
SELECT d.tenant_id, d.scope_id, d.id, 'commission', 'text/plain', x.text
  FROM pg_temp.cutover_task c
  JOIN dispatch.draft d
    ON d.tenant_id = c.tenant_id AND d.scope_id = c.scope_id AND d.task_id = c.task_id
  JOIN dispatch_legacy.task_text x
    ON x.task_id = c.legacy_id AND x.text_type = 'dispatch' AND x.addendum_suffix IS NULL
 WHERE c.state = 'draft'
 ORDER BY d.id;

INSERT INTO dispatch.draft_free_fields (tenant_id, scope_id, draft_id, document)
SELECT d.tenant_id, d.scope_id, d.id, c.dispatch_metadata
  FROM pg_temp.cutover_task c
  JOIN dispatch.draft d
    ON d.tenant_id = c.tenant_id AND d.scope_id = c.scope_id AND d.task_id = c.task_id
 WHERE c.state = 'draft' AND c.dispatch_metadata IS NOT NULL
 ORDER BY d.id;

-- ---------------------------------------------------------------------------
-- 14. The report: numbers, and the keys of the metadata. No title, no text and
-- no value of the stock.
-- ---------------------------------------------------------------------------
INSERT INTO pg_temp.cutover_copy_report (section, subject, amount)
SELECT 'metadata', m.key || ' -> ' || m.target,
       sum(CASE WHEN m.target LIKE 'reference of kind %' THEN m.value_count ELSE 1 END)
  FROM pg_temp.cutover_metadata m
 GROUP BY m.key, m.target
 ORDER BY m.key, m.target;

INSERT INTO pg_temp.cutover_copy_report (section, subject, amount)
SELECT 'tags', 'left behind on never-sent tasks', count(*)
  FROM dispatch_legacy.task_tag g
  JOIN pg_temp.cutover_task c ON c.tenant_id = g.tenant_id AND c.legacy_id = g.task_id
 WHERE c.state = 'draft';

INSERT INTO pg_temp.cutover_copy_report (section, subject, amount)
SELECT 'idempotency keys', 'not copied: dispatch_legacy.task_idempotency_key', count(*)
  FROM dispatch_legacy.task_idempotency_key;

INSERT INTO pg_temp.cutover_copy_report (section, subject, amount)
SELECT 'idempotency keys', 'not copied: dispatch_legacy.idempotency_key', count(*)
  FROM dispatch_legacy.idempotency_key;

INSERT INTO pg_temp.cutover_copy_report (section, subject, amount)
SELECT 'elicitation', 'bundles with selection none, free text admitted set by the copy', count(*)
  FROM dispatch.elicitation_question q
 WHERE q.selection = 'none';

INSERT INTO pg_temp.cutover_copy_report (section, subject, amount)
SELECT 'elicitation', 'bundles with selection one, options and free_text from question_options',
       count(*)
  FROM dispatch.elicitation_question q
 WHERE q.selection = 'one';

INSERT INTO pg_temp.cutover_copy_report (section, subject, amount)
SELECT 'elicitation', 'answers to q1 of the youngest ask act not after them', count(*)
  FROM dispatch.elicitation_answer;

INSERT INTO pg_temp.cutover_copy_report (section, subject, amount)
SELECT 'elicitation', 'addenda to a question or answer text', count(*)
  FROM dispatch.elicitation_content x
 WHERE x.kind = 'addendum';

INSERT INTO pg_temp.cutover_copy_report (section, subject, amount)
SELECT 'holders', 'claim acts of a holder', count(*)
  FROM pg_temp.cutover_event e
 WHERE e.holder_claim;

INSERT INTO pg_temp.cutover_copy_report (section, subject, amount)
SELECT 'instances', 'concluded without a return to refer to', count(*)
  FROM pg_temp.cutover_event e
 WHERE e.kind = 'conclude'
   AND NOT EXISTS (SELECT 1 FROM pg_temp.cutover_event d
                    WHERE d.legacy_task_id = e.legacy_task_id AND d.kind = 'deliver');

INSERT INTO pg_temp.cutover_copy_report (section, subject, amount)
SELECT 'remarks', 'placed on the first act of their task, no act before them', count(*)
  FROM pg_temp.cutover_remark m
 WHERE m.before_every_act;

INSERT INTO pg_temp.cutover_copy_report (section, subject, amount)
SELECT 'texts', 'of never-sent tasks besides the commission, not carried', count(*)
  FROM dispatch_legacy.task_text x
  JOIN pg_temp.cutover_task c ON c.legacy_id = x.task_id
 WHERE c.state = 'draft'
   AND NOT (x.text_type = 'dispatch' AND x.addendum_suffix IS NULL);

INSERT INTO pg_temp.cutover_copy_report (section, subject, amount)
          SELECT 'rows', 'dispatch.selector', count(*) FROM dispatch.selector
UNION ALL SELECT 'rows', 'dispatch.definition_version', count(*) FROM dispatch.definition_version
UNION ALL SELECT 'rows', 'dispatch.definition_act', count(*) FROM dispatch.definition_act
UNION ALL SELECT 'rows', 'dispatch.definition_tag', count(*) FROM dispatch.definition_tag
UNION ALL SELECT 'rows', 'dispatch.activity', count(*) FROM dispatch.activity
UNION ALL SELECT 'rows', 'dispatch.outcome_label', count(*) FROM dispatch.outcome_label
UNION ALL SELECT 'rows', 'dispatch.instance', count(*) FROM dispatch.instance
UNION ALL SELECT 'rows', 'dispatch.task', count(*) FROM dispatch.task
UNION ALL SELECT 'rows', 'dispatch.act', count(*) FROM dispatch.act
UNION ALL SELECT 'rows', 'dispatch.commission', count(*) FROM dispatch.commission
UNION ALL SELECT 'rows', 'dispatch.commission_criterion', count(*) FROM dispatch.commission_criterion
UNION ALL SELECT 'rows', 'dispatch.holding', count(*) FROM dispatch.holding
UNION ALL SELECT 'rows', 'dispatch.receipt', count(*) FROM dispatch.receipt
UNION ALL SELECT 'rows', 'dispatch.lease', count(*) FROM dispatch.lease
UNION ALL SELECT 'rows', 'dispatch.delivery', count(*) FROM dispatch.delivery
UNION ALL SELECT 'rows', 'dispatch.delivery_criterion', count(*) FROM dispatch.delivery_criterion
UNION ALL SELECT 'rows', 'dispatch.instance_return', count(*) FROM dispatch.instance_return
UNION ALL SELECT 'rows', 'dispatch.external_reference', count(*) FROM dispatch.external_reference
UNION ALL SELECT 'rows', 'dispatch.idempotency_key', count(*) FROM dispatch.idempotency_key
UNION ALL SELECT 'rows', 'dispatch.free_fields', count(*) FROM dispatch.free_fields
UNION ALL SELECT 'rows', 'dispatch.content', count(*) FROM dispatch.content
UNION ALL SELECT 'rows', 'dispatch.draft', count(*) FROM dispatch.draft
UNION ALL SELECT 'rows', 'dispatch.draft_criterion', count(*) FROM dispatch.draft_criterion
UNION ALL SELECT 'rows', 'dispatch.draft_free_fields', count(*) FROM dispatch.draft_free_fields
UNION ALL SELECT 'rows', 'dispatch.draft_text', count(*) FROM dispatch.draft_text
UNION ALL SELECT 'rows', 'dispatch.enumeration', count(*) FROM dispatch.enumeration
UNION ALL SELECT 'rows', 'dispatch.enumeration_value', count(*) FROM dispatch.enumeration_value
UNION ALL SELECT 'rows', 'dispatch.control_field', count(*) FROM dispatch.control_field
UNION ALL SELECT 'rows', 'dispatch.contract_field', count(*) FROM dispatch.contract_field
UNION ALL SELECT 'rows', 'dispatch.field_value', count(*) FROM dispatch.field_value
UNION ALL SELECT 'rows', 'dispatch.instance_field_value', count(*) FROM dispatch.instance_field_value
UNION ALL SELECT 'rows', 'dispatch.elicitation_question', count(*) FROM dispatch.elicitation_question
UNION ALL SELECT 'rows', 'dispatch.elicitation_option', count(*) FROM dispatch.elicitation_option
UNION ALL SELECT 'rows', 'dispatch.elicitation_answer', count(*) FROM dispatch.elicitation_answer
UNION ALL SELECT 'rows', 'dispatch.elicitation_answer_option', count(*) FROM dispatch.elicitation_answer_option
UNION ALL SELECT 'rows', 'dispatch.elicitation_content', count(*) FROM dispatch.elicitation_content;

COMMIT;

SELECT r.section, r.subject, r.amount
  FROM pg_temp.cutover_copy_report r
 ORDER BY r.ordinal;
