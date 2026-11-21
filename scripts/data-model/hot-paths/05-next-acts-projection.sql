-- next acts: the state of one task, its current holding and the lease in force
SELECT t.state, t.hold_reason, t.parked, t.not_before, h.id AS holding_id,
       (SELECT max(l.expires_at) FROM dispatch.lease l
        WHERE l.tenant_id = h.tenant_id AND l.scope_id = h.scope_id AND l.holding_id = h.id) AS lease_until
FROM dispatch.task t
LEFT JOIN dispatch.holding h ON h.tenant_id = t.tenant_id AND h.scope_id = t.scope_id
     AND h.task_id = t.id AND h.ended_by_act_id IS NULL
WHERE t.tenant_id = '00000000-0000-4000-8000-000000000001' AND t.scope_id = '00000000-0000-4000-9000-000000000001' AND t.id = 35397;
