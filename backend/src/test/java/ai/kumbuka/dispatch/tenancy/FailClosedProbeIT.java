package ai.kumbuka.dispatch.tenancy;

import ai.kumbuka.dispatch.domain.DomainFixture;
import ai.kumbuka.dispatch.domain.Exchange;
import ai.kumbuka.dispatch.domain.ExchangeService;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Probe 3 — the lock closes, and the data is still there.
 *
 * <p>Both halves belong to the probe. A read with no tenant bound returns
 * nothing, which is the guarantee; and the same read with the tenant bound
 * returns the rows, which is what distinguishes a lock from an empty table.
 * Asserting only the first half would pass against a database where the
 * insert never landed, the schema is wrong, or the rows were deleted — every
 * one of which looks exactly like perfect isolation from the outside.
 *
 * <p>The second pair of tests takes the two enforcement layers apart. Both
 * are on at once in normal operation, so an ordinary assertion cannot say
 * which one did the work — and a layer that is quietly doing nothing is a
 * layer that will not be there when the other one is removed. Each is
 * therefore observed alone, by switching the other off for the length of one
 * assertion.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class FailClosedProbeIT {

    /** The scope the selectors are declared in. Any uuid; fixed for legibility. */
    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");

    /**
     * A fresh pair of tenants per test method — the tests share one database,
     * and a count under a fixed tenant would include whatever an earlier test
     * planted there. See the same note in {@link RowLevelSecurityProbeIT}.
     */
    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void freshTenants() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
    }

    @Inject ExchangeService exchanges;
    @Inject TenantContext tenantContext;

    @Test
    void an_unbound_read_returns_nothing_and_the_rows_are_still_there() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            Db.insertExchange(c, tenantA, "fail-closed-a");
            c.commit();

            // First half: no binding, no rows. The predicate compares against
            // NULL, which a policy treats as failing, so the table is closed
            // rather than open.
            Db.bindTenant(c, null);
            assertThat(Db.countExchanges(c))
                .as("a transaction that never bound a tenant must see nothing at all — "
                    + "the predicate fails closed, and this is the half that is the guarantee")
                .isZero();

            // Second half: bind, and the row is there. Without this the
            // assertion above would hold just as well against a table that is
            // simply empty, and an empty table proves nothing about a policy.
            Db.bindTenant(c, tenantA);
            assertThat(Db.countExchanges(c))
                .as("and with the tenant bound the row is present and unchanged — which is "
                    + "what makes the emptiness above a lock rather than an absence")
                .isEqualTo(1);
        }
    }

    /**
     * Layer 1 alone: the ORM filter, with the database policy switched off.
     *
     * <p>Hibernate rewrites every statement it routes with the tenant
     * predicate. That holds without any help from the database, and this is
     * where it is seen holding: the policy is disabled for the length of the
     * assertion, so nothing but the filter is left to do the work.
     */
    @Test
    void the_orm_filter_isolates_on_its_own_when_the_policy_is_off() throws Exception {
        plantOneScopePerTenant();

        try (Connection c = Db.asService()) {
            try {
                Db.exec(c, "ALTER TABLE dispatch.exchange DISABLE ROW LEVEL SECURITY");
                c.commit();

                try (AutoCloseable ignored = tenantContext.bind(tenantA)) {
                    List<Exchange> rows = exchanges.children(SCOPE, "sprint", 1);
                    // The bracket itself, read back through the ORM.
                    Exchange bracket = exchanges.read(SCOPE,
                        ai.kumbuka.dispatch.domain.ExchangeAddress.bracket("sprint", 1));
                    assertThat(bracket.tenantId)
                        .as("with the policy disabled, the ORM filter is the only thing "
                            + "scoping this read — and it must still scope it")
                        .isEqualTo(tenantA.toString());
                    assertThat(rows).isEmpty();
                }

                // The other side of the same observation: raw SQL, which the
                // ORM never rewrote, now sees everything. That is precisely
                // the gap layer 2 exists to close, and it is visible here.
                Db.bindTenant(c, tenantA);
                assertThat(Db.countExchanges(c))
                    .as("RED STATE, observed: with the policy off, raw SQL under tenant A "
                        + "reads tenant B's row too. The ORM filter cannot reach a statement "
                        + "it did not build, which is the whole reason for a second layer")
                    .isEqualTo(2);
            } finally {
                Db.exec(c, "ALTER TABLE dispatch.exchange ENABLE ROW LEVEL SECURITY");
                c.commit();
            }
        }
    }

    /**
     * Layer 2 alone: the database policy, against a statement the ORM never
     * saw. No switching is needed here — raw SQL is by definition outside
     * layer 1, so anything that scopes it is the policy.
     */
    @Test
    void the_policy_isolates_raw_sql_that_the_orm_never_touched() throws Exception {
        plantOneScopePerTenant();

        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            assertThat(Db.countExchanges(c))
                .as("raw SQL bypasses the ORM filter entirely, so this count is the policy's "
                    + "work and nobody else's")
                .isEqualTo(1);
        }
    }

    /** One row per tenant, written through the ORM so both layers are on the path. */
    private void plantOneScopePerTenant() throws Exception {
        DomainFixture.declareSelector(tenantA, SCOPE, "sprint");
        DomainFixture.declareSelector(tenantB, SCOPE, "sprint");
        try (AutoCloseable ignored = tenantContext.bind(tenantA)) {
            exchanges.openBracket(SCOPE, "sprint", "layers-a", "code", LocalDate.now(), "probe");
        }
        try (AutoCloseable ignored = tenantContext.bind(tenantB)) {
            exchanges.openBracket(SCOPE, "sprint", "layers-b", "code", LocalDate.now(), "probe");
        }
    }
}
