-- read_trace: the trace of one instance, first page
SELECT sequence, task_id, kind, actor_subject, at, to_state FROM dispatch.act
WHERE tenant_id = '00000000-0000-4000-8000-000000000001' AND scope_id = '00000000-0000-4000-9000-000000000001' AND instance_id = 2950
ORDER BY sequence LIMIT 50;
