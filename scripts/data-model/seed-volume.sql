-- Volume dataset for the measurement of the data model
-- (docs/data-model/method/measurement.md, "Dataset").
--
-- Generated, never copied from production. Deterministic: every value is
-- computed from the row's position, no random function is called, so a
-- repeated run on an empty store line yields the same rows and the same ids.
--
-- Run as a superuser against a database migrated with the store line and
-- otherwise empty:
--   psql -v ON_ERROR_STOP=1 -f scripts/data-model/seed-volume.sql
-- A superuser bypasses row-level security; that is wanted here and nowhere
-- else. The measurements themselves run as the service role.
--
-- Shape: two tenants, three scopes each. Per scope 3,000 instances with
-- twelve tasks each and up to six acts per task. Nine instances in ten are
-- closed; a running instance carries tasks in every state but on_hold.
-- The numbers are an estimate of ten times the steering corpus (about 300
-- instances, 3,500 tasks), not a count read from production.

\set instances 3000
\set tasks 12
\set acts 6

BEGIN;
SET search_path = dispatch;

CREATE TEMP TABLE sc ON COMMIT DROP AS
SELECT n AS sc,
       ('00000000-0000-4000-8000-00000000000' || (n / 3 + 1))::uuid AS tenant_id,
       ('00000000-0000-4000-9000-00000000000' || (n + 1))::uuid     AS scope_id
FROM generate_series(0, 5) n;

-- Definition -----------------------------------------------------------------
INSERT INTO selector (id, tenant_id, scope_id, name, next_number) OVERRIDING SYSTEM VALUE
SELECT sc * 5 + j, tenant_id, scope_id,
       (ARRAY['sprint','satellite','orga','idea','bugfix'])[j], :instances + 1
FROM sc, generate_series(1, 5) j;

INSERT INTO definition_version (id, tenant_id, scope_id, selector_id, version,
                                verdict_required, outcome_required, state) OVERRIDING SYSTEM VALUE
SELECT id, tenant_id, scope_id, id, 1, true, false, 'active' FROM selector;

INSERT INTO definition_act (tenant_id, scope_id, definition_version_id, sequence, kind,
                            actor_subject, path, at)
SELECT tenant_id, scope_id, id, k, (ARRAY['define','activate'])[k], 'seed', 'interface',
       timestamptz '2026-01-01 00:00:00+00'
FROM definition_version, generate_series(1, 2) k;

INSERT INTO activity (id, tenant_id, scope_id, definition_version_id, name, verdict_required,
                      outcome_required, criteria_required, input_kind, may_ask, starts_excluded)
       OVERRIDING SYSTEM VALUE
SELECT dv.id * 3 + a, tenant_id, scope_id, dv.id, (ARRAY['concept','code','review'])[a + 1],
       true, false, true, 'text', true, false
FROM definition_version dv, generate_series(0, 2) a;

INSERT INTO enumeration (id, tenant_id, scope_id, definition_version_id, name) OVERRIDING SYSTEM VALUE
SELECT id, tenant_id, scope_id, id, 'risk' FROM definition_version;

INSERT INTO enumeration_value (id, tenant_id, scope_id, enumeration_id, value, ordinal)
       OVERRIDING SYSTEM VALUE
SELECT e.id * 4 + v, tenant_id, scope_id, e.id, (ARRAY['low','medium','high','critical'])[v + 1], v + 1
FROM enumeration e, generate_series(0, 3) v;

INSERT INTO control_field (id, tenant_id, scope_id, definition_version_id, name, type, enumeration_id)
       OVERRIDING SYSTEM VALUE
SELECT dv.id * 4 + c, tenant_id, scope_id, dv.id,
       (ARRAY['approved','effort','component','risk'])[c + 1],
       (ARRAY['boolean','number','text','choice'])[c + 1],
       CASE WHEN c = 3 THEN dv.id END
FROM definition_version dv, generate_series(0, 3) c;

INSERT INTO contract_field (tenant_id, scope_id, definition_version_id, activity_id, control_field_id, required)
SELECT a.tenant_id, a.scope_id, a.definition_version_id, a.id, a.definition_version_id * 4 + c, c = 0
FROM activity a, generate_series(0, 1) c;
INSERT INTO contract_field (tenant_id, scope_id, definition_version_id, activity_id, control_field_id, required)
SELECT tenant_id, scope_id, id, NULL, id * 4 + c, false
FROM definition_version, generate_series(2, 3) c;

-- Instances and tasks ----------------------------------------------------------
CREATE TEMP TABLE gi ON COMMIT DROP AS
SELECT sc.sc, sc.tenant_id, sc.scope_id, i,
       sc.sc * :instances + i AS instance_id,
       sc.sc * 5 + CASE WHEN i % 10 < 6 THEN 1 WHEN i % 10 < 8 THEN 2
                        WHEN i % 10 = 8 THEN 3 WHEN i % 20 = 9 THEN 4 ELSE 5 END AS selector_id,
       i <= :instances * 9 / 10 AS closed,
       timestamptz '2026-01-01 00:00:00+00' + i * interval '2 hours' AS t0
FROM sc, generate_series(1, :instances) i;

INSERT INTO instance (id, tenant_id, scope_id, selector_id, number, next_sub,
                      definition_version_id, state, resolution) OVERRIDING SYSTEM VALUE
SELECT instance_id, tenant_id, scope_id, selector_id, i, :tasks + 1, selector_id,
       CASE WHEN closed THEN 'closed' ELSE 'running' END,
       CASE WHEN closed THEN 'concluded' END
FROM gi;

-- stage: how many of the six acts of a task exist.
CREATE TEMP TABLE gt ON COMMIT DROP AS
SELECT gi.*, s,
       (instance_id - 1) * :tasks + s AS task_id,
       CASE WHEN closed OR s <= 6 THEN 'closed' WHEN s <= 8 THEN 'delivered'
            WHEN s <= 10 THEN 'active' WHEN s = 11 THEN 'open' ELSE 'draft' END AS state,
       CASE WHEN closed OR s <= 6 THEN 6 WHEN s <= 8 THEN 4
            WHEN s <= 10 THEN 3 WHEN s = 11 THEN 1 ELSE 0 END AS stage
FROM gi, generate_series(1, :tasks) s;

INSERT INTO task (id, tenant_id, scope_id, instance_id, sub, title, activity_id, state,
                  resolution, parked) OVERRIDING SYSTEM VALUE
SELECT task_id, tenant_id, scope_id, instance_id, s, 'Task ' || i || '.' || s,
       selector_id * 3 + s % 3, state, CASE WHEN state = 'closed' THEN 'accepted' END, false
FROM gt;

-- Trace --------------------------------------------------------------------------
CREATE TEMP TABLE ga ON COMMIT DROP AS
SELECT tenant_id, scope_id, instance_id, selector_id, NULL::bigint AS task_id, 0 AS s, 0 AS k, i,
       (instance_id - 1) * (:tasks * :acts + 2) + 1 AS act_id, 1 AS sequence,
       'start' AS kind, 'running' AS to_state, t0 AS at
FROM gi
UNION ALL
SELECT tenant_id, scope_id, instance_id, selector_id, task_id, s, k, i,
       (instance_id - 1) * (:tasks * :acts + 2) + 1 + (s - 1) * :acts + k,
       1 + (s - 1) * :acts + k,
       (ARRAY['send','claim','renew','deliver','accept','annotate'])[k],
       (ARRAY['open','active','active','delivered','closed','closed'])[k],
       t0 + ((s - 1) * :acts + k) * interval '5 minutes'
FROM gt, generate_series(1, :acts) k
WHERE k <= stage
UNION ALL
SELECT tenant_id, scope_id, instance_id, selector_id, NULL, 0, 99, i,
       (instance_id - 1) * (:tasks * :acts + 2) + :tasks * :acts + 2, :tasks * :acts + 2,
       'conclude', 'closed', t0 + (:tasks * :acts + 2) * interval '5 minutes'
FROM gi WHERE closed;

INSERT INTO act (id, tenant_id, scope_id, instance_id, sequence, task_id, kind, actor_subject,
                 permission, path, at, to_state, refers_to_act_id) OVERRIDING SYSTEM VALUE
SELECT act_id, tenant_id, scope_id, instance_id, sequence, task_id, kind,
       'subject-' || (act_id % 7), 'dispatch.' || kind, 'interface', at, to_state,
       CASE WHEN kind = 'accept' THEN act_id - 1 END  -- the delivery it accepts
FROM ga
ORDER BY act_id;

-- Commission, return, criteria -----------------------------------------------------
INSERT INTO commission (id, tenant_id, scope_id, act_id, title, activity_id) OVERRIDING SYSTEM VALUE
SELECT act_id, tenant_id, scope_id, act_id, 'Task ' || i || '.' || s, selector_id * 3 + s % 3
FROM ga WHERE kind = 'send';

INSERT INTO commission_criterion (id, tenant_id, scope_id, commission_id, identifier, ordinal)
       OVERRIDING SYSTEM VALUE
SELECT act_id * 2 + c, tenant_id, scope_id, act_id, 'AC' || (c + 1), c + 1
FROM ga, generate_series(0, 1) c WHERE kind = 'send';

INSERT INTO delivery (id, tenant_id, scope_id, act_id, verdict) OVERRIDING SYSTEM VALUE
SELECT act_id, tenant_id, scope_id, act_id, CASE WHEN act_id % 9 = 0 THEN 'red' ELSE 'green' END
FROM ga WHERE kind = 'deliver';

-- the commission of a deliver act is the send act of the same task: three acts earlier.
INSERT INTO delivery_criterion (id, tenant_id, scope_id, delivery_id, commission_criterion_id, status)
       OVERRIDING SYSTEM VALUE
SELECT act_id * 2 + c, tenant_id, scope_id, act_id, (act_id - 3) * 2 + c, 'met'
FROM ga, generate_series(0, 1) c WHERE kind = 'deliver';

INSERT INTO instance_return (tenant_id, scope_id, act_id, verdict)
SELECT tenant_id, scope_id, act_id, 'green' FROM ga WHERE kind = 'conclude';

-- Content: generated filler of the size of real text, no text of anyone.
INSERT INTO content (tenant_id, scope_id, act_id, kind, media_type, body)
SELECT tenant_id, scope_id, act_id,
       CASE kind WHEN 'send' THEN 'commission' WHEN 'deliver' THEN 'return'
                 WHEN 'annotate' THEN 'remark' ELSE 'instance_return' END,
       'text/markdown',
       repeat(md5(act_id::text) || ' ', CASE kind WHEN 'send' THEN 45 WHEN 'deliver' THEN 30 ELSE 6 END)
FROM ga WHERE kind IN ('send', 'deliver', 'annotate', 'conclude');

INSERT INTO content (tenant_id, scope_id, act_id, kind, commission_criterion_id, media_type, body)
SELECT tenant_id, scope_id, act_id, 'criterion', act_id * 2 + c, 'text/markdown',
       repeat(md5((act_id * 2 + c)::text) || ' ', 5)
FROM ga, generate_series(0, 1) c WHERE kind = 'send';

INSERT INTO content (tenant_id, scope_id, act_id, kind, delivery_criterion_id, media_type, body)
SELECT tenant_id, scope_id, act_id, 'criterion_evidence', act_id * 2 + c, 'text/markdown',
       repeat(md5((act_id * 2 + c)::text) || ' ', 5)
FROM ga, generate_series(0, 1) c WHERE kind = 'deliver';

-- Holding ---------------------------------------------------------------------------
INSERT INTO holding (id, tenant_id, scope_id, task_id, claim_act_id, ended_by_act_id)
       OVERRIDING SYSTEM VALUE
SELECT a.act_id, a.tenant_id, a.scope_id, a.task_id, a.act_id,
       CASE WHEN t.stage >= 4 THEN a.act_id + 2 END
FROM ga a JOIN gt t USING (task_id) WHERE a.kind = 'claim';

INSERT INTO lease (tenant_id, scope_id, holding_id, act_id, expires_at)
SELECT tenant_id, scope_id, act_id - (k - 2), act_id, at + interval '8 hours'
FROM ga WHERE kind IN ('claim', 'renew');

INSERT INTO receipt (tenant_id, scope_id, holding_id, receipt_hash, issued_at)
SELECT tenant_id, scope_id, act_id, md5('receipt' || act_id), at FROM ga WHERE kind = 'claim';

-- Control fields, free fields ---------------------------------------------------------
INSERT INTO field_value (tenant_id, scope_id, act_id, control_field_id, value_type, boolean_value)
SELECT tenant_id, scope_id, act_id, selector_id * 4, 'boolean', act_id % 3 <> 0
FROM ga WHERE kind = 'deliver';
INSERT INTO field_value (tenant_id, scope_id, act_id, control_field_id, value_type, number_value)
SELECT tenant_id, scope_id, act_id, selector_id * 4 + 1, 'number', (act_id % 400) / 4.0
FROM ga WHERE kind = 'deliver';
INSERT INTO field_value (tenant_id, scope_id, act_id, control_field_id, value_type, text_value)
SELECT tenant_id, scope_id, act_id, selector_id * 4 + 2, 'text', 'component-' || (act_id % 40)
FROM ga WHERE kind = 'start';
INSERT INTO field_value (tenant_id, scope_id, act_id, control_field_id, value_type, enumeration_id,
                         choice_value_id)
SELECT tenant_id, scope_id, act_id, selector_id * 4 + 3, 'choice', selector_id,
       selector_id * 4 + act_id % 4
FROM ga WHERE kind IN ('start', 'deliver');

INSERT INTO free_fields (tenant_id, scope_id, act_id, document)
SELECT tenant_id, scope_id, act_id,
       jsonb_build_object('date', (at::date)::text, 'items', jsonb_build_array(i, i + 1), 'note', md5(i::text))
FROM ga WHERE kind = 'start';

-- References, idempotency ---------------------------------------------------------------
INSERT INTO external_reference (tenant_id, scope_id, instance_id, kind, value, set_by_act_id)
SELECT tenant_id, scope_id, instance_id, r.kind, r.value, act_id
FROM ga, LATERAL (VALUES ('trigger', 'worklist://seed/item/' || i),
                         ('tag', 'tag-' || (i % 25)),
                         ('reference', 'worklist://seed/item/' || (i % 500))) r(kind, value)
WHERE ga.kind = 'start';
INSERT INTO external_reference (tenant_id, scope_id, task_id, kind, value, set_by_act_id)
SELECT tenant_id, scope_id, task_id, 'tag', 'tag-' || (task_id % 25), act_id
FROM ga WHERE kind = 'send' AND s % 2 = 0;

INSERT INTO idempotency_key (tenant_id, scope_id, caller_subject, idempotency_key, act_id, created_at)
SELECT tenant_id, scope_id, 'subject-' || (act_id % 7), md5('key' || act_id), act_id, at
FROM ga WHERE kind = 'send';

-- Draft workspace of the tasks never sent ---------------------------------------------------
INSERT INTO draft (id, tenant_id, scope_id, task_id, title, activity_id) OVERRIDING SYSTEM VALUE
SELECT task_id, tenant_id, scope_id, task_id, 'Task ' || i || '.' || s, selector_id * 3 + s % 3
FROM gt WHERE state = 'draft';
INSERT INTO draft_text (tenant_id, scope_id, draft_id, kind, media_type, body)
SELECT tenant_id, scope_id, task_id, 'commission', 'text/markdown', repeat(md5(task_id::text) || ' ', 45)
FROM gt WHERE state = 'draft';

-- Identity sequences behind the rows written with explicit ids.
SELECT setval(pg_get_serial_sequence('dispatch.' || t, 'id'),
              (xpath('/row/m/text()', query_to_xml('SELECT max(id) AS m FROM dispatch.' || t, false, true, '')))[1]::text::bigint)
FROM unnest(ARRAY['selector','definition_version','activity','enumeration','enumeration_value',
                  'control_field','instance','task','act','commission','commission_criterion',
                  'delivery','delivery_criterion','holding','draft']) t;

COMMIT;
ANALYZE;
