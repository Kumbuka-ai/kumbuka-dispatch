-- write one act: one claim and one deliver on the open task 35399 of the volume dataset
-- (tenant 1, scope 1, instance 2950, sub 11), rolled back. Run with \timing as the service role.
\set t '00000000-0000-4000-8000-000000000001'
\set s '00000000-0000-4000-9000-000000000001'
BEGIN;
\echo claim
WITH a AS (INSERT INTO dispatch.act (tenant_id, scope_id, instance_id, sequence, task_id, kind, actor_subject, permission, path, at, to_state)
           VALUES (:'t', :'s', 2950, 1000, 35399, 'claim', 'bench', 'dispatch.claim', 'interface', now(), 'active') RETURNING id),
     h AS (INSERT INTO dispatch.holding (tenant_id, scope_id, task_id, claim_act_id) SELECT :'t', :'s', 35399, id FROM a RETURNING id, claim_act_id),
     l AS (INSERT INTO dispatch.lease (tenant_id, scope_id, holding_id, act_id, expires_at) SELECT :'t', :'s', id, claim_act_id, now() + interval '1 hour' FROM h),
     r AS (INSERT INTO dispatch.receipt (tenant_id, scope_id, holding_id, receipt_hash, issued_at) SELECT :'t', :'s', id, 'hash', now() FROM h),
     k AS (INSERT INTO dispatch.idempotency_key (tenant_id, scope_id, caller_subject, idempotency_key, act_id, created_at) SELECT :'t', :'s', 'bench', 'bench-key', id, now() FROM a)
UPDATE dispatch.task SET state = 'active' WHERE tenant_id = :'t' AND scope_id = :'s' AND id = 35399;
\echo deliver
WITH a AS (INSERT INTO dispatch.act (tenant_id, scope_id, instance_id, sequence, task_id, kind, actor_subject, permission, path, at, to_state)
           VALUES (:'t', :'s', 2950, 1001, 35399, 'deliver', 'bench', 'dispatch.deliver', 'interface', now(), 'delivered') RETURNING id),
     d AS (INSERT INTO dispatch.delivery (tenant_id, scope_id, act_id, verdict) SELECT :'t', :'s', id, 'green' FROM a RETURNING id, act_id),
     dc AS (INSERT INTO dispatch.delivery_criterion (tenant_id, scope_id, delivery_id, commission_criterion_id, status)
            SELECT :'t', :'s', d.id, cc.id, 'met' FROM d, dispatch.act sa
            JOIN dispatch.commission c ON c.tenant_id = sa.tenant_id AND c.scope_id = sa.scope_id AND c.act_id = sa.id
            JOIN dispatch.commission_criterion cc ON cc.tenant_id = c.tenant_id AND cc.scope_id = c.scope_id AND cc.commission_id = c.id
            WHERE sa.tenant_id = :'t' AND sa.scope_id = :'s' AND sa.task_id = 35399 AND sa.kind = 'send' RETURNING id),
     c1 AS (INSERT INTO dispatch.content (tenant_id, scope_id, act_id, kind, media_type, body) SELECT :'t', :'s', id, 'return', 'text/markdown', repeat('x', 1000) FROM a),
     c2 AS (INSERT INTO dispatch.content (tenant_id, scope_id, act_id, kind, delivery_criterion_id, media_type, body) SELECT :'t', :'s', a.id, 'criterion_evidence', dc.id, 'text/markdown', 'evidence' FROM a, dc),
     f1 AS (INSERT INTO dispatch.field_value (tenant_id, scope_id, act_id, control_field_id, value_type, boolean_value) SELECT :'t', :'s', id, 4, 'boolean', true FROM a),
     f2 AS (INSERT INTO dispatch.field_value (tenant_id, scope_id, act_id, control_field_id, value_type, number_value) SELECT :'t', :'s', id, 5, 'number', 3.5 FROM a),
     f3 AS (INSERT INTO dispatch.field_value (tenant_id, scope_id, act_id, control_field_id, value_type, enumeration_id, choice_value_id) SELECT :'t', :'s', id, 7, 'choice', 1, 6 FROM a),
     ff AS (INSERT INTO dispatch.free_fields (tenant_id, scope_id, act_id, document) SELECT :'t', :'s', id, '{"note": "bench"}' FROM a),
     h AS (UPDATE dispatch.holding SET ended_by_act_id = (SELECT id FROM a) WHERE tenant_id = :'t' AND scope_id = :'s' AND task_id = 35399 AND ended_by_act_id IS NULL)
UPDATE dispatch.task SET state = 'delivered' WHERE tenant_id = :'t' AND scope_id = :'s' AND id = 35399;
ROLLBACK;
