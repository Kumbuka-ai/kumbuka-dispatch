package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.security.TestIdentityAssociation;

/**
 * The two identities the surface probes call as, and the accounts behind them.
 *
 * <p>Two are needed rather than one because the two guarantees this surface
 * carries are permissions bound to the caller: an executing apparatus does not
 * receive the body of an exchange it has not claimed, and it cannot ratify. A
 * suite with one identity could observe neither, and would report the surface
 * as covered.
 *
 * <p>The identity is switched programmatically rather than per test method,
 * because the acts under test form a chain across both capacities — a console
 * commissions, an executor takes up and answers, the console ratifies — and
 * splitting that chain across methods would mean staging the middle of it
 * behind the surface being probed.
 */
public final class SurfaceFixture {

    /** The scope the staged directory publishes, by the name a caller uses. */
    public static final String SCOPE = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;

    /** A selector V5 declares for this deployment's own scope. */
    public static final String SELECTOR = "sprint";

    public static final String CONSOLE = "probe-console";
    public static final String EXECUTOR = "probe-executor";
    public static final String OTHER_EXECUTOR = "probe-executor-2";

    private SurfaceFixture() {
    }

    /**
     * Grants the directory read and registers the probing subjects as active
     * members of the tenant.
     *
     * <p>Membership is what the read contract answers on: existence in its
     * result IS the permission, so a subject with no account resolves no scope
     * and every probe would fail at stage 2 with a 404 that looks like a
     * missing fixture.
     */
    public static void stage() {
        PlatformFixture.grantDirectoryAccess();
        for (String subject : new String[] {CONSOLE, EXECUTOR, OTHER_EXECUTOR}) {
            PlatformFixture.run(
                "SELECT set_config('app.tenant_id', '"
                    + SubstrateDatabaseResource.TENANT_ID + "', false)",
                "INSERT INTO public.user_account (tenant_id, subject) SELECT '"
                    + SubstrateDatabaseResource.TENANT_ID + "', '" + subject + "' "
                    + "WHERE NOT EXISTS (SELECT 1 FROM public.user_account WHERE subject = '"
                    + subject + "')",
                "RESET app.tenant_id");
        }
    }

    /** Calls as a human-facing console identity. */
    public static void asConsole(TestIdentityAssociation identity) {
        as(identity, CONSOLE, Actor.ROLE_CONSOLE);
    }

    /** Calls as the executing apparatus. */
    public static void asExecutor(TestIdentityAssociation identity) {
        as(identity, EXECUTOR, Actor.ROLE_EXECUTOR);
    }

    /** Calls as a second executor, which is how "only the holder" is observable. */
    public static void asOtherExecutor(TestIdentityAssociation identity) {
        as(identity, OTHER_EXECUTOR, Actor.ROLE_EXECUTOR);
    }

    /** Calls as an authenticated token carrying neither capacity. */
    public static void asCapacitylessToken(TestIdentityAssociation identity) {
        identity.setTestIdentity(QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal(CONSOLE))
            .build());
    }

    private static void as(TestIdentityAssociation identity, String subject, String role) {
        identity.setTestIdentity(QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal(subject))
            .addRole(role)
            .build());
    }

    /** The item URI of an exchange, as this surface exposes it. */
    public static String item(String id) {
        return "/api/" + SCOPE + "/" + SELECTOR + "/" + id;
    }

    /** The collection URI of the selector. */
    public static String collection() {
        return "/api/" + SCOPE + "/" + SELECTOR;
    }

    /** The complete address of an exchange, as the MCP form spells one. */
    public static String address(String id) {
        return "dispatch://" + SCOPE + "/" + SELECTOR + "/" + id;
    }
}
