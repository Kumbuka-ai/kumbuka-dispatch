package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.platform.PlatformFixture;

import java.util.UUID;

/**
 * Declares a selector and its number circle for a tenant, so a probe can use
 * the real creation path.
 *
 * <p>Staged rather than called through a verb, because there is no verb: a
 * selector is declared deliberately — through the interface, or for our own
 * estate through a migration — and never by first use. A test helper that
 * created one through the domain would be exercising a path the design
 * refuses to have.
 *
 * <p>It runs as the container superuser, which also side-steps the tenancy
 * policy: the rows have to land under a tenant the test invented moments ago,
 * and binding that tenant only to insert its own fixture would be ceremony.
 */
public final class DomainFixture {

    private DomainFixture() {
    }

    /** A declared selector plus its counter, ready for openBracket. */
    public static void declareSelector(UUID tenant, UUID scope, String name) {
        PlatformFixture.run(
            "INSERT INTO dispatch.selector (tenant_id, scope_id, name) VALUES ('"
                + tenant + "', '" + scope + "', '" + name + "') ON CONFLICT DO NOTHING",
            "INSERT INTO dispatch.number_circle (tenant_id, scope_id, selector) VALUES ('"
                + tenant + "', '" + scope + "', '" + name + "') ON CONFLICT DO NOTHING");
    }
}
