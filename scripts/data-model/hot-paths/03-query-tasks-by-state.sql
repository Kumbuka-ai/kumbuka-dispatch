-- query: the open tasks of one scope, first page
SELECT id, instance_id, sub, title, activity_id FROM dispatch.task
WHERE tenant_id = '00000000-0000-4000-8000-000000000001' AND scope_id = '00000000-0000-4000-9000-000000000001' AND state = 'open'
ORDER BY instance_id, sub LIMIT 50;
