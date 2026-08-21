-- ===========================================================================
-- V2: the service's own database role.
--
-- The service connects as `kumbuka_dispatch` and as nothing else. The role
-- holds rights on exactly this schema, and the way it holds them is by
-- OWNING its objects rather than by carrying a list of grants — an owner
-- needs no GRANT for its own tables, and a grant list is a thing that drifts
-- from the schema it is supposed to describe.
--
-- Ownership is not set here. A migration can only capture the objects that
-- exist when it runs, and the next migration would have to remember. The
-- normalisation is an `afterMigrate` Flyway callback instead
-- (SchemaOwnershipCallback), so it captures every future object with no
-- per-migration step to forget.
--
-- TWO ATTRIBUTES ARE LOAD-BEARING, AND THIS MIGRATION ONLY CHECKS THEM
--
--   NOSUPERUSER   a superuser bypasses row-level security unconditionally.
--   NOBYPASSRLS   so does a role carrying BYPASSRLS.
--
-- Either one silently evaporates the tenant filter: rows returned, no error,
-- every test green. Together with FORCE ROW LEVEL SECURITY in V3 — which is
-- what makes the policy bind the table's OWNER, and this role is the owner —
-- they are the three lines the isolation actually rests on.
--
-- The migrator cannot grant either attribute and cannot take it away: those
-- are superuser-only operations, and this service migrates with CREATEROLE
-- and nothing more. That is a better arrangement than the alternative and it
-- is why the block below RAISES instead of repairing. A migration that
-- quietly stripped a security attribute would be a migration that could
-- quietly add one; refusing to run against a wrongly-shaped role leaves the
-- decision with whoever shaped it, and leaves a message saying so.
--
-- THE OPERATOR BOUNDARY IS THE LINE THAT IS NOT HERE
--
-- No grant is issued to the provider role. It cannot read an exchange
-- because no privilege exists that would let it, not because a rule in the
-- application forbids it. There is deliberately no statement below naming
-- that role: an assurance about an absence is kept by writing nothing, and
-- it is proven by a probe that observes the refusal at the database. That
-- probe observes both states — the refusal, and the access a temporarily
-- granted privilege allows — because an absence that was never seen to
-- matter is not a boundary.
--
-- THE PASSWORD BELOW IS A PLACEHOLDER AND MUST BE ROTATED
--
-- It is written so that the service comes up against an empty database with
-- no manual step, which is what makes a cold start reproducible. It is not a
-- credential: any deployment reachable from outside a development machine
-- replaces it with `ALTER ROLE kumbuka_dispatch PASSWORD …` from its own
-- secret store, as an operational act outside this repository.
-- ===========================================================================

DO $do$
DECLARE
    is_super   boolean;
    is_bypass  boolean;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'kumbuka_dispatch') THEN
        -- A CREATEROLE migrator cannot confer SUPERUSER or BYPASSRLS, so a
        -- role created here structurally cannot carry either. The check below
        -- is for the other path: a role an operator created beforehand.
        CREATE ROLE kumbuka_dispatch LOGIN PASSWORD 'change-me-kumbuka-dispatch';
        RAISE NOTICE 'created role kumbuka_dispatch with the placeholder password — rotate it';
    END IF;

    SELECT rolsuper, rolbypassrls INTO is_super, is_bypass
    FROM pg_catalog.pg_roles WHERE rolname = 'kumbuka_dispatch';

    IF is_super OR is_bypass THEN
        RAISE EXCEPTION
            'kumbuka_dispatch carries superuser=% bypassrls=% — either one makes the '
            'row-level-security policies in V3 inert, and this migration will not '
            'create a schema whose isolation cannot hold. Recreate the role without '
            'them.', is_super, is_bypass;
    END IF;

    -- PostgreSQL will not let a role hand ownership to a role it is not a
    -- member of, and the ownership callback does exactly that after every
    -- migration. A CREATEROLE migrator receives ADMIN OPTION on a role it
    -- creates but not, by default, the ability to SET ROLE to it, so the
    -- membership is taken here — explicitly, where a reader can see why it
    -- exists — rather than left to a server setting that differs between
    -- installations.
    --
    -- The other path is an operator who created the role beforehand. Then
    -- there is no ADMIN OPTION to grant from, the GRANT is refused, and the
    -- migration stops with a message naming what is missing. Stopping is
    -- right: without the membership the callback cannot transfer ownership,
    -- the service would not reach its own tables, and a failure at that point
    -- would be much harder to read than this one.
    -- Issued unconditionally rather than behind a membership check. The
    -- check would have to ask whether the current role may SET ROLE to the
    -- service role, and membership carrying ADMIN OPTION without the SET
    -- option answers "member: yes" while still refusing the very operation
    -- the callback performs. A repeated GRANT is idempotent; a check that
    -- reports the wrong thing is not.
    BEGIN
        EXECUTE format('GRANT kumbuka_dispatch TO %I', current_user);
    EXCEPTION WHEN insufficient_privilege THEN
        RAISE EXCEPTION
            'the migrating role % cannot become a member of kumbuka_dispatch. '
            'Ownership of this schema cannot then be transferred to the service '
            'role, and the service would not reach its own tables. Grant the '
            'membership (GRANT kumbuka_dispatch TO %I) and re-run.',
            current_user, current_user;
    END;

    -- Raw SQL and psql sessions land in the service's own schema rather than
    -- in `public`. Hibernate and Flyway are pinned by configuration and do
    -- not depend on this; it is here so an unqualified statement typed by a
    -- human fails in the right place.
    EXECUTE 'ALTER ROLE kumbuka_dispatch SET search_path = dispatch';
END
$do$;
