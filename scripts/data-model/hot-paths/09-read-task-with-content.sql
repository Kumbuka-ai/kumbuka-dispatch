-- read: one task with its commission, criteria and their text
SELECT c.title, cc.identifier, co.kind, co.body
FROM dispatch.act a
JOIN dispatch.commission c ON c.tenant_id = a.tenant_id AND c.scope_id = a.scope_id AND c.act_id = a.id
LEFT JOIN dispatch.content co ON co.tenant_id = a.tenant_id AND co.scope_id = a.scope_id AND co.act_id = a.id
LEFT JOIN dispatch.commission_criterion cc ON cc.tenant_id = co.tenant_id AND cc.scope_id = co.scope_id
     AND cc.id = co.commission_criterion_id
WHERE a.tenant_id = '00000000-0000-4000-8000-000000000001' AND a.scope_id = '00000000-0000-4000-9000-000000000001' AND a.task_id = 35397 AND a.kind = 'send';
