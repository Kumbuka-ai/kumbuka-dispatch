-- ===========================================================================
-- V5: declare this deployment's bracket names.
--
-- A selector is never created by first use and never by a verb. For our own
-- estate the declaration is this migration: it is versioned, it is reviewable,
-- and it is not a verb — which is exactly the set of properties the rule asks
-- for. A scope belonging to somebody else declares its own through the
-- interface.
--
-- THIS MIGRATION IS ALSO A PROBE, AND THAT IS ITS SECOND PURPOSE
--
-- It is the first migration in this service carrying DML, so it is the first
-- one that has to pass a row-level-security policy in order to write. Under
-- `FORCE ROW LEVEL SECURITY` the `WITH CHECK` clause compares the incoming
-- tenant against `app.tenant_id`, and that setting is bound by a Flyway
-- callback which the extension registers from configuration by class name —
-- never as a CDI bean.
--
-- A callback that is written but not named in `quarkus.flyway.callbacks` is
-- silently never registered. Before this migration existed there was nothing
-- in the service that would notice: pure DDL does not touch a policy, so the
-- callback could have been absent for years without a symptom. Now its
-- absence stops the boot here, loudly, with the policy naming itself in the
-- error. The tenant id below is written as a literal for exactly that reason
-- — taking it from the setting instead would turn a policy violation into a
-- not-null violation, and the error would name the wrong cause.
-- ===========================================================================

INSERT INTO dispatch.selector (tenant_id, scope_id, name) VALUES
    ('${dispatchTenantId}'::uuid, '${dispatchScopeId}'::uuid, 'sprint'),
    ('${dispatchTenantId}'::uuid, '${dispatchScopeId}'::uuid, 'satellite');

-- The counters for those two circles. Created here rather than on first use:
-- a counter that springs into existence when somebody numbers something is a
-- counter whose starting value depends on who got there first.
INSERT INTO dispatch.number_circle (tenant_id, scope_id, selector) VALUES
    ('${dispatchTenantId}'::uuid, '${dispatchScopeId}'::uuid, 'sprint'),
    ('${dispatchTenantId}'::uuid, '${dispatchScopeId}'::uuid, 'satellite');
