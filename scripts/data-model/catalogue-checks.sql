-- Catalogue checks of the data model (docs/data-model/method/measurement.md).
-- Run against a database migrated with the store line, schema dispatch:
--   psql -v ON_ERROR_STOP=1 -f scripts/data-model/catalogue-checks.sql
-- Every query lists violations; an empty result is a pass.

\echo 'P1a: tables without tenant_id or scope_id'
SELECT c.relname
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'dispatch' AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history'
  AND (NOT EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped)
    OR NOT EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'scope_id' AND NOT a.attisdropped));

\echo 'P1b: tables without a unique key exactly (tenant_id, scope_id, id)'
SELECT c.relname
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'dispatch' AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history'
  AND NOT EXISTS (
    SELECT 1 FROM pg_constraint k
    WHERE k.conrelid = c.oid AND k.contype = 'u'
      AND (SELECT array_agg(a.attname::text ORDER BY u.ord)
           FROM unnest(k.conkey) WITH ORDINALITY u(attnum, ord)
           JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = u.attnum)
          = ARRAY['tenant_id', 'scope_id', 'id']);

\echo 'P1c: foreign keys whose columns do not begin with tenant_id, scope_id'
SELECT k.conrelid::regclass AS tbl, k.conname
FROM pg_constraint k JOIN pg_namespace n ON n.oid = k.connamespace
WHERE n.nspname = 'dispatch' AND k.contype = 'f'
  AND (SELECT array_agg(a.attname::text ORDER BY u.ord)
       FROM unnest(k.conkey) WITH ORDINALITY u(attnum, ord)
       JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = u.attnum)[1:2]
      IS DISTINCT FROM ARRAY['tenant_id', 'scope_id'];

\echo 'P1d: tables without row-level security enabled and forced, or without a policy'
SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity,
       (SELECT count(*) FROM pg_policy p WHERE p.polrelid = c.oid) AS policies
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'dispatch' AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history'
  AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity
       OR NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid));

\echo 'L5: tables on which the service role holds UPDATE on any column'
SELECT DISTINCT table_name, column_name
FROM information_schema.column_privileges
WHERE table_schema = 'dispatch' AND privilege_type = 'UPDATE' AND grantee = 'kumbuka_dispatch'
ORDER BY 1, 2;

\echo 'L5: tables on which the service role holds DELETE (expected: task and the draft workspace)'
SELECT table_name
FROM information_schema.role_table_grants
WHERE table_schema = 'dispatch' AND privilege_type = 'DELETE' AND grantee = 'kumbuka_dispatch'
ORDER BY 1;

\echo 'L5: privileges of the service role beyond SELECT, INSERT, UPDATE, DELETE'
SELECT table_name, privilege_type
FROM information_schema.role_table_grants
WHERE table_schema = 'dispatch' AND grantee = 'kumbuka_dispatch'
  AND privilege_type NOT IN ('SELECT', 'INSERT', 'UPDATE', 'DELETE')
ORDER BY 1, 2;

\echo 'P2: roles other than the service role and the owner with any privilege on a table of the schema'
SELECT DISTINCT g.grantee, g.table_name, g.privilege_type
FROM information_schema.role_table_grants g
JOIN pg_class c ON c.relname = g.table_name AND c.relnamespace = 'dispatch'::regnamespace
WHERE g.table_schema = 'dispatch' AND g.grantee <> 'kumbuka_dispatch'
  AND g.grantee <> pg_get_userbyid(c.relowner)
ORDER BY 1, 2, 3;

\echo 'P2: column privileges of any such role'
SELECT DISTINCT g.grantee, g.table_name, g.column_name, g.privilege_type
FROM information_schema.column_privileges g
JOIN pg_class c ON c.relname = g.table_name AND c.relnamespace = 'dispatch'::regnamespace
WHERE g.table_schema = 'dispatch' AND g.grantee <> 'kumbuka_dispatch'
  AND g.grantee <> pg_get_userbyid(c.relowner)
ORDER BY 1, 2, 3;
