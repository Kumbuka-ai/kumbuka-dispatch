-- ===========================================================================
-- V3: row-level security on the tenant axis.
--
-- This is layer 2 of a two-layer enforcement model. Layer 1 is the Hibernate
-- @TenantId filter, which scopes every ORM-routed read and write. Layer 2 is
-- here, and it catches what layer 1 structurally cannot: raw SQL, native
-- queries, and any code path that reaches the database without passing the
-- ORM. Either layer alone would be load-bearing; both together are the seam.
--
-- THE PREDICATE, AND WHY IT IS WRITTEN THIS WAY
--
--     tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
--
-- `current_setting(…, true)` returns NULL instead of raising when the
-- setting is absent; NULLIF turns an empty string into NULL as well; and
-- `tenant_id = NULL` is not FALSE but NULL, which a policy treats as failing.
-- So a session that never bound a tenant sees no rows at all rather than
-- every row. RLS fails CLOSED, and that is the design rather than a
-- side effect — it is what makes a forgotten binding a visible emptiness
-- instead of a silent leak.
--
-- BOTH `USING` AND `WITH CHECK` ARE REQUIRED
--
-- `USING` filters what a statement may see; `WITH CHECK` constrains what it
-- may write. A policy with only `USING` lets a session insert a row under a
-- foreign tenant and then lose sight of it — data planted across the
-- boundary, invisible to the planter and to the tenant that now owns it.
--
-- BOTH `ENABLE` AND `FORCE` ARE REQUIRED
--
-- `ENABLE` switches the policy on for everyone EXCEPT the table's owner.
-- This service connects as the owner of its own tables (V2), so `ENABLE`
-- alone would leave the policy switched off for the only role that ever
-- connects. `FORCE` is what binds the owner too. The probe that removes
-- FORCE and watches the foreign row appear is the reason this sentence can
-- be written as a fact rather than as an intention.
--
-- This migration is pure DDL. Row-level security filters DML only, so no
-- tenant context is needed to apply it. A `beforeEachMigrate` callback
-- (TenantMigrationCallback) binds the GUC for any future migration that does
-- carry DML — without it, a seed or backfill would fail closed under FORCE
-- and quietly write nothing.
-- ===========================================================================

ALTER TABLE dispatch.scope ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispatch.scope FORCE  ROW LEVEL SECURITY;

CREATE POLICY scope_tenant_isolation ON dispatch.scope
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
