package ai.kumbuka.dispatch.tenancy;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * Boots the database the probes actually need: a real PostgreSQL, with the
 * real role shape the service runs under.
 *
 * <p><strong>Why not DevServices.</strong> A development datasource connects
 * as a superuser, and a superuser bypasses row-level security
 * unconditionally. Every isolation assertion made against it passes whether
 * the policies exist or not, so the suite would be green on a schema with the
 * security removed. The one thing this service most needs to prove is the one
 * thing that setup cannot prove.
 *
 * <p>So the container is started here and four roles are kept apart:
 *
 * <ul>
 *   <li><b>the administrator</b> — the container's own superuser. It stages
 *       the other roles and the neighbour, and the service never uses it.</li>
 *   <li><b>the migrator</b> — CREATEROLE, and deliberately nothing more.
 *       Creating the service role is the one privileged act the migration set
 *       performs; superuser would additionally hand it BYPASSRLS and make its
 *       DML untestable.</li>
 *   <li><b>the service role</b> — created by the migration itself, so the
 *       cold start is exercised rather than staged. Neither superuser nor
 *       BYPASSRLS; the policies bind it.</li>
 *   <li><b>the provider role</b> — created here, carrying BYPASSRLS, holding
 *       no grant on this service's schema. It is the operator boundary's
 *       counterparty, and BYPASSRLS is the point: a role that bypasses every
 *       policy still cannot read a table it was never granted, which is what
 *       makes the boundary a missing privilege rather than a filter.</li>
 * </ul>
 *
 * <p>A stand-in for a neighbouring service's schema is created as well — an
 * ordinary table in {@code public}, which is where the memory engine lives.
 * It is a stand-in and not a dependency: this service must not know that
 * service exists, and building a real one here would import exactly the
 * coupling the architecture forbids. What the stand-in makes observable is
 * one instance of a general claim; the general claim itself — that the
 * service role holds nothing outside its own schema — is asserted against the
 * whole catalog by {@code ServiceRoleConformanceIT}, which needs no stand-in
 * and would also see a real neighbour.
 */
public class SubstrateDatabaseResource implements QuarkusTestResourceLifecycleManager {

    /** Production major. Chosen over the newest so the gate tests what runs. */
    public static final String POSTGRES_IMAGE = "postgres:16";

    /**
     * The migrating role. CREATEROLE, and deliberately NOT a superuser.
     *
     * <p>A migrator needs exactly one privilege the service does not have —
     * the right to create the service's role — and CREATEROLE is that
     * privilege. Giving it superuser instead would hand it BYPASSRLS as a
     * side effect, and a migration that bypasses row-level security is one
     * whose DML cannot be observed failing when it forgets the tenant
     * binding. The template this service establishes therefore migrates
     * unprivileged, which is also the safer thing to hand to the next five
     * services.
     */
    public static final String MIGRATOR_ROLE = "kumbuka_dispatch_migrator";
    public static final String MIGRATOR_PASSWORD = "test-only-migrator-password";

    /** The service role. Created by V2 — NOT staged here, so the cold start is real. */
    public static final String SERVICE_ROLE = "kumbuka_dispatch";
    public static final String SERVICE_PASSWORD = "change-me-kumbuka-dispatch";

    /** The provider role. Deliberately BYPASSRLS, deliberately ungranted. */
    public static final String PROVIDER_ROLE = "kumbuka_operator";
    public static final String PROVIDER_PASSWORD = "test-only-operator-password";

    /** The neighbouring service's stand-in: schema and table it owns, we do not. */
    public static final String NEIGHBOUR_SCHEMA = "public";
    public static final String NEIGHBOUR_TABLE = "memory";

    /**
     * The platform's role, and the read contract it publishes.
     *
     * <p>The directory view is owned by this role rather than by the container
     * superuser, which is what a deployment's owner-normalisation sweep
     * arranges. The distinction is load-bearing: a view without
     * {@code security_invoker} reads its base tables with its OWNER's
     * privileges, so a superuser-owned view is exempt from
     * {@code FORCE ROW LEVEL SECURITY} — and the failure is silent, because
     * the view returns rows and raises nothing.
     */
    public static final String PLATFORM_ROLE = "kumbuka";
    public static final String PLATFORM_SCHEMA = "platform";
    public static final String DIRECTORY_VIEW = "scope_access";

    /** The scope the directory publishes to the probing subject. */
    public static final String PROBE_SCOPE_SLUG = "probe-scope";
    public static final String PROBE_SUBJECT = "probe-subject";

    /**
     * A global scope, staged so that the absence of the kind filter is
     * observable.
     *
     * <p>The view used to end in {@code WHERE kind = 'project'}, and a service
     * reading it could not address a global scope at all. A probe against a
     * project scope cannot see that the filter is gone — only a scope of
     * another served kind can, and this is it.
     */
    public static final String GLOBAL_SCOPE_SLUG = "probe-scope-global";
    public static final String GLOBAL_SCOPE_ID = "00000000-0000-0000-0000-000000000011";

    /**
     * A private scope. Served by the read contract, refused by this service.
     *
     * <p>Autorless, which is the shape a fresh chain actually produces: the
     * core holds at most one private scope per tenant and records no author on
     * it, so it is visible to every active member. That is the case worth
     * probing — a private scope this caller can SEE and still may not use.
     */
    public static final String PRIVATE_SCOPE_SLUG = "probe-scope-private";
    public static final String PRIVATE_SCOPE_ID = "00000000-0000-0000-0000-000000000012";

    /** A locked scope: readable, and refusing every write over a service channel. */
    public static final String LOCKED_SCOPE_SLUG = "probe-scope-locked";
    public static final String LOCKED_SCOPE_ID = "00000000-0000-0000-0000-000000000013";

    /**
     * A muted member, which is how "no write right" actually arises.
     *
     * <p>{@code can_write} is derived from the MEMBER's mute flag and the
     * scope's lock, not from a per-scope permission row — so a read-only
     * situation is a subject, not a scope. Staging it the other way round
     * would have built a fixture the platform cannot produce.
     */
    public static final String MUTED_SUBJECT = "probe-subject-muted";

    /**
     * The tenancy axis and the scope under test. Fixed rather than random so
     * that the value in a failure message can be recognised, and matched to
     * the same two values in the test configuration.
     */
    public static final String TENANT_ID = "00000000-0000-0000-0000-000000000001";
    public static final String SCOPE_ID  = "00000000-0000-0000-0000-000000000010";

    private static PostgreSQLContainer<?> postgres;

    @Override
    public Map<String, String> start() {
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("kumbuka")
            .withUsername("postgres_admin")
            .withPassword("test-only-admin-password");
        postgres.start();

        seedRolesAndNeighbour();

        Map<String, String> cfg = new HashMap<>();

        // The runtime connection: the service's own role, created by V2.
        cfg.put("quarkus.datasource.jdbc.url", postgres.getJdbcUrl());
        cfg.put("quarkus.datasource.username", SERVICE_ROLE);
        cfg.put("quarkus.datasource.password", SERVICE_PASSWORD);

        // The migrating connection: CREATEROLE, and nothing more than that.
        cfg.put("quarkus.flyway.jdbc-url", postgres.getJdbcUrl());
        cfg.put("quarkus.flyway.username", MIGRATOR_ROLE);
        cfg.put("quarkus.flyway.password", MIGRATOR_PASSWORD);

        // Raw-JDBC coordinates for the probes, which open their own
        // connections under each role to see what that role can actually do.
        cfg.put("test.db.url", postgres.getJdbcUrl());
        cfg.put("test.db.admin.username", postgres.getUsername());
        cfg.put("test.db.admin.password", postgres.getPassword());

        return cfg;
    }

    /**
     * Creates what the migration must not create: the migrating role itself,
     * the provider role, and a neighbouring service's table.
     *
     * <p>Neither belongs in this service's migration set. The provider role is
     * the platform's, and a service that created its own counterparty could
     * quietly grant it something. The neighbour is another service's schema,
     * and reaching into it from here is the coupling the architecture rules
     * out. Both are staged from the test harness because the assertions are
     * about relationships between things this service does not own.
     */
    private void seedRolesAndNeighbour() {
        try (Connection c = adminConnection(); Statement s = c.createStatement()) {
            // The migrator. CREATEROLE lets it create the service role in V2;
            // NOSUPERUSER NOBYPASSRLS mean its own DML is subject to the
            // policies, which is what makes a forgotten tenant binding in a
            // migration observable instead of accidentally harmless.
            s.execute("""
                DO $$ BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%s') THEN
                        CREATE ROLE %s LOGIN CREATEROLE NOSUPERUSER NOBYPASSRLS PASSWORD '%s';
                    END IF;
                END $$;
                """.formatted(MIGRATOR_ROLE, MIGRATOR_ROLE, MIGRATOR_PASSWORD));
            s.execute("GRANT CREATE ON DATABASE " + postgres.getDatabaseName()
                + " TO " + MIGRATOR_ROLE);

            s.execute("""
                DO $$ BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%s') THEN
                        CREATE ROLE %s LOGIN BYPASSRLS PASSWORD '%s';
                    END IF;
                END $$;
                """.formatted(PROVIDER_ROLE, PROVIDER_ROLE, PROVIDER_PASSWORD));

            stagePlatformDirectory(s);

            // The neighbour. Owned by the migrator, granted to nobody. Its
            // one row exists so that a successful read is distinguishable
            // from a permitted read that happens to find nothing.
            s.execute("CREATE TABLE IF NOT EXISTS " + NEIGHBOUR_SCHEMA + "." + NEIGHBOUR_TABLE
                + " (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), content text NOT NULL)");
            s.execute("INSERT INTO " + NEIGHBOUR_SCHEMA + "." + NEIGHBOUR_TABLE + " (content) "
                + "SELECT 'a neighbouring service owns this row' "
                + "WHERE NOT EXISTS (SELECT 1 FROM " + NEIGHBOUR_SCHEMA + "." + NEIGHBOUR_TABLE + ")");
        } catch (SQLException e) {
            throw new IllegalStateException("failed to stage the roles and the neighbour", e);
        }
    }

    /**
     * Reconstructs the platform's published read contract, as a deployment
     * has it: base tables under FORCE row-level security, a view over them in
     * its own schema, and the consuming role holding SELECT on the view and
     * nothing else.
     *
     * <p>Staged here rather than migrated, because none of it belongs to this
     * service. Building it in a migration would mean this service creates the
     * contract it consumes, and a consumer that can create its own contract
     * can widen it.
     */
    private void stagePlatformDirectory(Statement s) throws SQLException {
        s.execute("""
            DO $$ BEGIN
                IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%s') THEN
                    CREATE ROLE %s NOSUPERUSER NOBYPASSRLS;
                END IF;
            END $$;
            """.formatted(PLATFORM_ROLE, PLATFORM_ROLE));

        // The base tables carry the columns V24's view reads: `locked` and
        // `created_by` on the scope, `muted` on the membership. They are the
        // core's and are reproduced here rather than invented — a substrate
        // that published a column the core does not have would let a probe
        // pass against a contract no deployment offers.
        s.execute("""
            CREATE TABLE IF NOT EXISTS public.scope (
                id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                tenant_id uuid NOT NULL,
                slug text NOT NULL,
                -- VARCHAR(16) with the check, as V1 of the core declares it.
                -- Measured against the pinned core on 2026-09-21: the view
                -- publishes `kind` as `character varying`, and the check is
                -- what stops a probe staging a kind no deployment can hold —
                -- a substrate that accepted one would let a test assert
                -- behaviour for a value the platform cannot produce.
                kind varchar(16) NOT NULL
                    CHECK (kind IN ('private','project','global')),
                archived boolean NOT NULL DEFAULT false,
                locked boolean NOT NULL DEFAULT false,
                created_by text)
            """);
        s.execute("""
            CREATE TABLE IF NOT EXISTS public.user_account (
                id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                tenant_id uuid NOT NULL,
                subject text NOT NULL,
                status text NOT NULL DEFAULT 'active',
                muted boolean NOT NULL DEFAULT false)
            """);

        for (String table : new String[] {"scope", "user_account"}) {
            s.execute("ALTER TABLE public." + table + " ENABLE ROW LEVEL SECURITY");
            s.execute("ALTER TABLE public." + table + " FORCE  ROW LEVEL SECURITY");
            s.execute("DROP POLICY IF EXISTS " + table + "_tenant_isolation ON public." + table);
            s.execute("CREATE POLICY " + table + "_tenant_isolation ON public." + table
                + " USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)"
                + " WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)");
        }

        s.execute("CREATE SCHEMA IF NOT EXISTS " + PLATFORM_SCHEMA);
        s.execute("REVOKE ALL ON SCHEMA " + PLATFORM_SCHEMA + " FROM PUBLIC");
        // The V24 form of the contract, column for column and predicate for
        // predicate as `kumbuka-server` v0.10.0 publishes it — seven columns,
        // no kind filter, `can_write` derived rather than stored, and the
        // author clause that binds a private scope to its author where one is
        // recorded without blanking out the autorless per-tenant one.
        //
        // The only deviation is the schema the base tables sit in: `public`
        // here, `platform` there. That is this substrate's own arrangement and
        // predates the contract; nothing in the published shape depends on it.
        s.execute("""
            CREATE OR REPLACE VIEW platform.scope_access AS
                SELECT s.id        AS scope_id,
                       s.tenant_id AS tenant_id,
                       s.slug      AS slug,
                       s.archived  AS archived,
                       s.kind      AS kind,
                       s.locked    AS locked,
                       (NOT s.locked AND (s.kind = 'private' OR NOT ua.muted)) AS can_write
                FROM public.scope s
                JOIN public.user_account ua ON ua.tenant_id = s.tenant_id
                WHERE s.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
                  AND ua.subject  = NULLIF(current_setting('app.subject',   true), '')
                  AND ua.status   = 'active'
                  AND (s.kind <> 'private'
                       OR s.created_by IS NULL
                       OR s.created_by = NULLIF(current_setting('app.subject', true), ''))
            """);

        // The owner-normalisation sweep, in the one respect this suite cares
        // about: the view must not be owned by a superuser.
        s.execute("ALTER TABLE public.scope        OWNER TO " + PLATFORM_ROLE);
        s.execute("ALTER TABLE public.user_account OWNER TO " + PLATFORM_ROLE);
        s.execute("ALTER VIEW  platform.scope_access OWNER TO " + PLATFORM_ROLE);

        // The grants are NOT issued here, and the omission is deliberate. The
        // service role does not exist yet — the migration creates it during
        // boot, which is after this runs — and in a deployment the platform
        // grants to a role the platform knows about, in its own migration.
        // PlatformFixture issues them once the role exists, which is also the
        // order a deployment has.

        // The four scopes and the two subjects every probe in this suite reads
        // against. One of each kind the contract now publishes, plus a locked
        // one, plus the muted member that produces a missing write right.
        s.execute("SELECT set_config('app.tenant_id', '" + TENANT_ID + "', false)");
        stageScope(s, SCOPE_ID, PROBE_SCOPE_SLUG, "project", false);
        stageScope(s, GLOBAL_SCOPE_ID, GLOBAL_SCOPE_SLUG, "global", false);
        stageScope(s, PRIVATE_SCOPE_ID, PRIVATE_SCOPE_SLUG, "private", false);
        stageScope(s, LOCKED_SCOPE_ID, LOCKED_SCOPE_SLUG, "project", true);
        stageMember(s, PROBE_SUBJECT, false);
        stageMember(s, MUTED_SUBJECT, true);
        s.execute("RESET app.tenant_id");
    }

    /**
     * One scope of the staged tenant. Idempotent on the id, so a re-run of the
     * resource against a surviving container stages nothing twice.
     */
    private static void stageScope(Statement s, String id, String slug, String kind,
                                   boolean locked) throws SQLException {
        s.execute("INSERT INTO public.scope (id, tenant_id, slug, kind, locked) "
            + "SELECT '" + id + "', '" + TENANT_ID + "', '" + slug + "', '" + kind + "', "
            + locked + " "
            + "WHERE NOT EXISTS (SELECT 1 FROM public.scope WHERE id = '" + id + "')");
    }

    /**
     * One active member of the staged tenant, muted or not.
     *
     * <p>The mute flag is the membership's, not the scope's, which is why a
     * read-only situation here is a second SUBJECT rather than a second scope.
     */
    private static void stageMember(Statement s, String subject, boolean muted)
            throws SQLException {
        s.execute("INSERT INTO public.user_account (tenant_id, subject, muted) "
            + "SELECT '" + TENANT_ID + "', '" + subject + "', " + muted + " "
            + "WHERE NOT EXISTS (SELECT 1 FROM public.user_account "
            + "WHERE subject = '" + subject + "')");
    }

    private Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Override
    public void stop() {
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
    }
}
