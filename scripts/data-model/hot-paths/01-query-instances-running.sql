-- query: the running instances of one selector, first page
SELECT id, number, state, resolution FROM dispatch.instance
WHERE tenant_id = '00000000-0000-4000-8000-000000000001' AND scope_id = '00000000-0000-4000-9000-000000000001' AND selector_id = 1 AND state = 'running'
ORDER BY number DESC LIMIT 50;
