-- next acts: the state of one task read from its trace, the check of the projection
SELECT kind, to_state, sequence FROM dispatch.act
WHERE tenant_id = '00000000-0000-4000-8000-000000000001' AND scope_id = '00000000-0000-4000-9000-000000000001' AND task_id = 35397
ORDER BY sequence DESC LIMIT 1;
