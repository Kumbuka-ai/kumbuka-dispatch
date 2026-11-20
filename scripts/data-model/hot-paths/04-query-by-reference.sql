-- query: the instances that carry a given reference of a given kind
SELECT i.id, i.number, i.state
FROM dispatch.external_reference r
JOIN dispatch.instance i ON i.tenant_id = r.tenant_id AND i.scope_id = r.scope_id AND i.id = r.instance_id
WHERE r.tenant_id = '00000000-0000-4000-8000-000000000001' AND r.scope_id = '00000000-0000-4000-9000-000000000001'
  AND r.kind = 'reference' AND r.value = 'worklist://seed/item/123' AND r.ended_by_act_id IS NULL
ORDER BY i.number DESC LIMIT 50;
