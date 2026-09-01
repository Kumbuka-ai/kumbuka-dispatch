-- ===========================================================================
-- V8: the runtime role owns nothing and holds what is written out below.
--
-- This migration replaces the arrangement V2 describes and the ownership
-- callback carried out. It is a correction, so it says what was wrong.
--
-- WHAT WAS MEASURED
--
-- 2026-08-23, in the deployment of v0.1.0: `kumbuka_dispatch` held DELETE,
-- INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE and UPDATE on all four
-- relations of this schema — `exchange`, `selector`, `number_circle` and
-- `flyway_schema_history`.
--
-- The obvious suspect is innocent and does not need looking at again: there
-- is no GRANT on a table anywhere in V1..V7. The privileges came from
-- OWNERSHIP. `SchemaOwnershipCallback` handed the schema and every relation
-- in it to the runtime role after each migration run, an owner holds the
-- full ACL on what it owns implicitly, and the Flyway history table travels
-- with the rest because `quarkus.flyway.default-schema=dispatch` puts it in
-- the same schema.
--
-- WHY A GRANT MIGRATION WOULD NOT HAVE FIXED IT
--
-- TRUNCATE bypasses row-level security completely — independently of every
-- policy and of whether `app.tenant_id` is bound. The apparatus V3 and V4
-- build has no reach over it at all, so a runtime role holding TRUNCATE can
-- empty a tenant-scoped table across the tenant boundary with nothing
-- observing it. On `flyway_schema_history` it additionally means the service
-- can erase its own migration history at runtime.
--
-- And a REVOKE against an owner does not hold. It survives until the next
-- migration run, when the callback fires again. An owner can also grant
-- itself back whatever was taken, can DROP POLICY, and can switch row-level
-- security off: FORCE ROW LEVEL SECURITY subjects an owner to its policies
-- but does not stop it removing them. So the repair has to reach the
-- OWNERSHIP MODEL, which is what this migration does, and the callback is
-- deleted in the same change.
--
-- THE PRICE, NAMED AND ACCEPTED
--
-- A later migration that forgets its grant produces a service that cannot
-- read its own new table. That failure is loud and immediate. The failure it
-- replaces was silent and permanent.
--
-- ONE OPERATOR STATEMENT IS NEEDED FIRST, ON A DATABASE THAT ALREADY RAN
--
-- Flyway holds its own schema-history table open for the whole of every run,
-- so no migration can ever take ownership of `dispatch.flyway_schema_history`
-- — it would not fail, it would BLOCK. On a database where the runtime role
-- owns that table (which is to say: the deployed one) this migration
-- therefore REFUSES, names the statement, and changes nothing. Run it once,
-- with no migration in progress, then deploy:
--
--     ALTER TABLE dispatch.flyway_schema_history OWNER TO <migrating role>;
--
-- On a cold start the question does not arise: the migrator created the table
-- and already owns it.
--
-- ROLLBACK, AND WHY THIS MIGRATION IS NOT ENOUGH ON ITS OWN
--
-- This schema state is compatible with the previous image — the old code runs
-- against it. But that image still contains the ownership callback, so
-- booting it RE-ARMS the defect: the callback fires after migrating, hands
-- the schema and the domain tables back to the runtime role, and the full
-- privilege set returns with no error anywhere. The migration and the
-- callback's removal therefore travel in the same image.
--
-- With one difference worth knowing before it is discovered at three in the
-- morning. The old callback sweeps EVERY relation of the schema and has no
-- lock timeout, and after this migration the history table belongs to the
-- migrator — so the sweep will reach it, ask for ACCESS EXCLUSIVE on the
-- table Flyway is holding open, and WAIT. The lock behaviour is measured (see
-- above); that the old callback runs into it follows from the same mechanism
-- and has not been observed separately. Expect a rollback boot that hangs
-- rather than one that quietly re-arms — and in either case the defect is
-- back for every relation the sweep did reach before it stopped.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1. The migrator carries no policy exemption.
--
-- Under enumerated grants the migrator owns every relation, and it will own
-- every VIEW this schema ever gains. A view without `security_invoker` reads
-- its base tables with its OWNER's privileges, and a superuser or BYPASSRLS
-- owner is exempt from FORCE ROW LEVEL SECURITY regardless — so a privileged
-- migrator turns a loud privilege defect into a silent confidentiality one:
-- rows returned, nothing raised, every test green.
--
-- This block REFUSES rather than repairs, and the reason is the same one V2
-- gives for the runtime role: stripping a security attribute is a superuser
-- act, this service migrates with CREATEROLE and nothing more, and a
-- migration that could quietly remove an attribute could quietly add one.
-- Role lifecycle belongs to the bootstrap.
--
-- The check runs when this migration is APPLIED. It is not a standing guard:
-- a later run against an already-migrated schema does not re-execute it, so a
-- migrator that acquires an attribute afterwards is not caught here. That is
-- a limit of putting the check in the chain rather than in a callback, and it
-- is stated rather than papered over.
--
-- ERRCODE `KD001` is application-defined and deliberately not one of
-- PostgreSQL's own: no standard class means "this role holds too much", and
-- `insufficient_privilege` would say the opposite of what happened. The probe
-- matches on this code rather than on the message text.
-- ---------------------------------------------------------------------------
DO $do$
DECLARE
    is_super   boolean;
    is_bypass  boolean;
BEGIN
    SELECT rolsuper, rolbypassrls INTO is_super, is_bypass
    FROM pg_catalog.pg_roles WHERE rolname = current_user;

    IF is_super OR is_bypass THEN
        RAISE EXCEPTION
            'the migrating role % carries superuser=% bypassrls=% and this schema will '
            'not be created under it. The migrator owns every relation here, including '
            'every future view, and a view reads its base tables with its owner''s '
            'privileges — either attribute makes that owner exempt from the policies, '
            'silently. Migrate as a role that carries neither (CREATEROLE is all this '
            'chain needs).',
            current_user, is_super::text, is_bypass::text
            USING ERRCODE = 'KD001';
    END IF;
END
$do$;

-- ---------------------------------------------------------------------------
-- 2. The migrator takes its schema back.
--
-- On a cold start this is a no-op: without the callback the migrator already
-- owns everything it created. On the deployed database it is the correction —
-- the schema and all four relations sit with the runtime role, and three of
-- the four come back here. The fourth is the Flyway history table and it
-- needs one statement from the operator first — see below, and see the head
-- of this file.
--
-- The schema goes FIRST, and the order is not cosmetic: PostgreSQL requires
-- the incoming owner of a relation to hold CREATE on the relation's schema.
-- While the schema still belongs to the runtime role, every ALTER below would
-- be refused with a message about the schema rather than about the table.
--
-- The migrator can do this at all because V2 made it a member of
-- `kumbuka_dispatch`. That membership was taken so the callback could hand
-- ownership OVER; it is what now lets ownership come BACK. It is left in
-- place: taking it away is a role-lifecycle act and belongs to the bootstrap,
-- not to a migration, by the same rule as section 1.
--
-- THE HISTORY TABLE IS THE ONE THIS MIGRATION MAY NOT BE ABLE TO MOVE
--
-- Measured 2026-09-01 against PostgreSQL 16.13 and Flyway as this service
-- runs it, on a database reproducing the deployed state: Flyway keeps its own
-- schema-history connection open in a transaction for the whole run, holding
-- ACCESS SHARE on `dispatch.flyway_schema_history`. `ALTER TABLE … OWNER TO`
-- needs ACCESS EXCLUSIVE, so a migration that tries to move that one table
-- does not fail — it BLOCKS, forever, with the deploy hanging and no message
-- anywhere saying why. The other three relations move without trouble; the
-- table Flyway is reading is the exception, and it is the exception on any
-- database where the runtime role owns it, which is to say on the deployed
-- one.
--
-- So this block moves everything it can and then CHECKS the history table
-- rather than assuming it. It still tries, under a short `lock_timeout`: the
-- measurement above is one measurement, and if a future Flyway holds no such
-- lock the migration should simply work. What it must never do is wait.
--
-- On a cold start none of this arises: the migrator created the table and
-- already owns it, so the check passes without a statement being issued.
-- ---------------------------------------------------------------------------
DO $do$
DECLARE
    relation  record;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_namespace
                   WHERE nspname = 'dispatch'
                     AND pg_get_userbyid(nspowner) = current_user) THEN
        BEGIN
            EXECUTE format('ALTER SCHEMA dispatch OWNER TO %I', current_user);
            RAISE NOTICE 'took schema dispatch back to %', current_user;
        EXCEPTION WHEN insufficient_privilege THEN
            RAISE EXCEPTION
                'the migrating role % cannot take ownership of schema dispatch back from '
                'its current owner. That requires membership of the owning role, which V2 '
                'takes on a cold start (GRANT kumbuka_dispatch TO %I). Without it the '
                'runtime role stays the owner and keeps the full privilege set implicitly, '
                'which is the defect this migration exists to remove.',
                current_user, current_user;
        END;
    END IF;

    FOR relation IN
        SELECT c.relname
        FROM pg_catalog.pg_class c
        JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'dispatch'
          -- Ordinary table, view, materialized view, sequence, partitioned
          -- table: the relation kinds that carry an owner. Indexes and
          -- constraints follow their table and have none of their own.
          AND c.relkind IN ('r', 'v', 'm', 'S', 'p')
          AND pg_get_userbyid(c.relowner) <> current_user
        ORDER BY c.relname
    LOOP
        -- Never wait. A blocked ALTER here is a deploy that hangs with no
        -- message; a refused one is a deploy that says what to do.
        SET LOCAL lock_timeout = '2s';
        BEGIN
            EXECUTE format('ALTER TABLE IF EXISTS dispatch.%I OWNER TO %I',
                           relation.relname, current_user);
            RAISE NOTICE 'took dispatch.% back to %', relation.relname, current_user;
        EXCEPTION WHEN lock_not_available THEN
            RAISE EXCEPTION
                'dispatch.% could not be taken back from its current owner: the lock was '
                'held and this migration will not wait for it. For '
                'dispatch.flyway_schema_history this is expected and is not a fault — '
                'Flyway holds its own schema-history table open for the whole run, so no '
                'migration can ever take ownership of it. Run this ONCE as the operator, '
                'with no migration in progress, and deploy again: '
                'ALTER TABLE dispatch.flyway_schema_history OWNER TO %I;',
                relation.relname, current_user
                USING ERRCODE = 'KD002';
        END;
    END LOOP;

    -- The history table, checked rather than assumed. Reaching this line with
    -- it owned elsewhere means the ALTER above never ran for it, which on a
    -- deployed database is the normal case: the loop only selects relations
    -- whose owner differs, and the operator statement below is what changes
    -- that. Refusing here rather than carrying on is the point — the runtime
    -- role owning this table holds the whole ACL on it implicitly, TRUNCATE
    -- included, and a migration that granted the rest and stayed quiet about
    -- this one would report a target state it had not reached.
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_class c
               JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
               WHERE n.nspname = 'dispatch'
                 AND c.relname = 'flyway_schema_history'
                 AND pg_get_userbyid(c.relowner) <> current_user) THEN
        RAISE EXCEPTION
            'dispatch.flyway_schema_history still belongs to another role. Its owner holds '
            'DELETE, TRUNCATE and UPDATE on it implicitly, which lets the service rewrite '
            'the record of its own schema version. No migration can move it, because '
            'Flyway holds it open for the length of every run. Run this ONCE as the '
            'operator, with no migration in progress, and deploy again: '
            'ALTER TABLE dispatch.flyway_schema_history OWNER TO %I;',
            current_user
            USING ERRCODE = 'KD002';
    END IF;
END
$do$;

-- ---------------------------------------------------------------------------
-- 3. The entitlement, written out.
--
-- This block is the whole of what `kumbuka_dispatch` may do in this schema,
-- and it is meant to be read as a list rather than trusted as a rule.
--
-- The REVOKE comes first, and the asymmetry is deliberate. A collective
-- REVOKE can only ever remove, so it cannot widen anything and it makes the
-- GRANTs below the exact statement of what is held rather than an addition to
-- whatever was held before. A collective GRANT is the opposite in every
-- respect, which is why there is none.
--
-- On the deployed database the REVOKE is also doing nothing on its own: the
-- runtime role held its privileges by ownership, and section 2 has already
-- taken that away. It is issued regardless, because an explicit grant made by
-- hand at some point would not show up as ownership and would survive.
--
-- `flyway_schema_history` is inside this schema, is therefore covered by the
-- REVOKE, and is named in no GRANT. The runtime role holds nothing on it: a
-- role that can rewrite it can make a schema lie about its own version, and
-- no verb in this service has any business reading it.
-- ---------------------------------------------------------------------------
REVOKE ALL ON ALL TABLES    IN SCHEMA dispatch FROM kumbuka_dispatch;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA dispatch FROM kumbuka_dispatch;
REVOKE ALL ON SCHEMA dispatch FROM kumbuka_dispatch;

-- Reaching the schema at all. USAGE is not a privilege on anything in it, and
-- CREATE is deliberately not granted: a runtime role that can create a table
-- in its own schema owns that table, and the enumeration is defeated in one
-- statement.
GRANT USAGE ON SCHEMA dispatch TO kumbuka_dispatch;

-- Three tables, three privileges, each named. No TRUNCATE, no TRIGGER, no
-- REFERENCES — the three an owner holds implicitly and no verb needs.
--
-- NO DELETE, and it is shown rather than assumed. The thirteen verbs of the
-- surface are create, read, update, append, send, accept, claim, release,
-- abandon, block, resume, close and consume; none of them deletes. The
-- candidate named in the dispatch was checked and does not hold: `revert`
-- (ExchangeService.revert -> Exchange.discardDraft) discards an unratified
-- handover draft by setting `handover_body` and `handover_metadata` to NULL,
-- which is an UPDATE. `Exchange.releaseClaim` is the same shape. No path in
-- this repository issues a DELETE against any table of this schema. The day a
-- verb genuinely deletes is the day the grant is added with that verb.
--
-- No sequence appears here because this schema has none: every key is a uuid
-- with `gen_random_uuid()`, and `number_circle.next_number` is an ordinary
-- integer column advanced by UPDATE under a row lock. A sequence added later
-- needs its USAGE written here with the migration that adds it.
GRANT SELECT, INSERT, UPDATE ON dispatch.exchange      TO kumbuka_dispatch;
GRANT SELECT, INSERT, UPDATE ON dispatch.selector      TO kumbuka_dispatch;
GRANT SELECT, INSERT, UPDATE ON dispatch.number_circle TO kumbuka_dispatch;
