package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The claim's clock: evaluated lazily, and writing nothing.
 *
 * <p>Expiry does not move the exchange. It makes it claimable again, and the
 * transition is written by the NEXT claimant, in that claimant's transaction,
 * with that claimant as the actor. So there is no reaper, no release verb for
 * the clock and no expiry event — and the rule that every audit entry has a
 * verb call and an actor holds without an exception carved out for a
 * background job.
 *
 * <h2>The silent failure this guards</h2>
 *
 * Because expiry writes nothing, the stored holder outlives the claim. The row
 * is not wrong; it is simply not the answer to "who holds this". A read
 * surface that reported the stored value would show a free exchange as taken —
 * no error, no log line, nothing to notice it by except somebody wondering why
 * a queue never moves. That is why the effective projection is asserted
 * against the stored value directly: the gap between them is the failure, and
 * it has to be visible once for the guarantee to mean anything.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class LazyExpiryIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final Duration CLAIM = Duration.ofHours(1);

    static final Actor EXECUTOR = new Actor("probe-executor", Actor.Kind.EXECUTOR);
    static final Actor NEXT_EXECUTOR = new Actor("probe-executor-next", Actor.Kind.EXECUTOR);
    static final Actor CONSOLE = new Actor("probe-console", Actor.Kind.CONSOLE);

    @Inject ExchangeService exchanges;
    @Inject TenantContext tenantContext;

    private UUID tenant;
    private AutoCloseable binding;

    @BeforeEach
    void freshTenant() {
        tenant = UUID.randomUUID();
        DomainFixture.declareSelector(tenant, SCOPE, "sprint");
        binding = tenantContext.bind(tenant);
    }

    @AfterEach
    void unbind() throws Exception {
        binding.close();
    }

    /**
     * The whole of it: expiry writes nothing, the projection hides the lapsed
     * holder, and the next claimant is what finally writes the transition.
     */
    @Test
    void expiry_writes_nothing_and_the_next_claimant_writes_the_transition()
            throws SQLException {
        Exchange sent = openAndSend("a commission whose claim will lapse");
        exchanges.takeup(SCOPE, at(sent), EXECUTOR, CLAIM);

        Snapshot beforeExpiry = snapshot(sent.id);
        assertThat(beforeExpiry.status()).isEqualTo("active");
        assertThat(beforeExpiry.holder()).isEqualTo(EXECUTOR.subject());

        expireTheClaim(sent.id);
        Snapshot afterExpiry = snapshot(sent.id);

        // --- criterion: the clock writes nothing -------------------------
        assertThat(afterExpiry.status())
            .as("expiry does not move the exchange. Nothing acts on the clock — there is "
                + "no reaper and no expiry event, so there is no actor for such a write "
                + "to be attributed to")
            .isEqualTo("active");
        assertThat(afterExpiry.holder())
            .as("and the stored holder is still there, which is the point: the row was "
                + "not rewritten")
            .isEqualTo(EXECUTOR.subject());

        // --- criterion: every read surface projects the effective state ---
        Exchange read = exchanges.read(SCOPE, at(sent));
        assertThat(read.storedHolderForProbe())
            .as("RED STATE, made visible: the row still names a holder. A surface "
                + "reporting THIS value would show a free exchange as taken — no error, "
                + "no log line, and a queue that quietly never moves")
            .isEqualTo(EXECUTOR.subject());
        assertThat(read.effectiveHolder(Instant.now()))
            .as("and the effective projection reports none, which is the guarantee. The "
                + "gap between these two lines is the failure this exists to prevent")
            .isNull();

        ExchangeView view = exchanges.view(SCOPE, at(sent), CONSOLE);
        assertThat(view.effectiveHolder())
            .as("the view projects it too — every read surface, not just the one that "
                + "happened to be tested")
            .isNull();
        assertThat(view.claimExpiresAt())
            .as("and reports no expiry for a claim that no longer stands")
            .isNull();

        // --- criterion: the next claimant writes the transition -----------
        var reclaimed = exchanges.takeup(SCOPE, at(sent), NEXT_EXECUTOR, CLAIM);
        Snapshot afterReclaim = snapshot(sent.id);

        assertThat(afterReclaim.holder())
            .as("the transition is written by the next claimant, in that claimant's "
                + "transaction, with that claimant as the actor — which is what keeps "
                + "every audit entry attributable to a verb call")
            .isEqualTo(NEXT_EXECUTOR.subject());
        assertThat(afterReclaim.updatedBy()).isEqualTo(NEXT_EXECUTOR.subject());
        assertThat(reclaimed.receipt())
            .as("and a new receipt is minted: the old one proves nothing any more")
            .isNotBlank();
    }

    /**
     * An orphaned draft goes with the claim it belonged to.
     *
     * <p>A draft that was never ratified never happened. Inheriting a
     * stranger's half-written text would be worse than deleting it, because
     * the result looks plausible and reads as somebody's answer.
     */
    @Test
    void a_draft_left_by_a_lapsed_claim_is_gone_after_the_reclaim() throws SQLException {
        Exchange sent = openAndSend("a commission abandoned mid-answer");
        var claim = exchanges.takeup(SCOPE, at(sent), EXECUTOR, CLAIM);
        exchanges.writeHandoverDraft(SCOPE, at(sent), EXECUTOR, claim.receipt(),
            "half an answer, left behind", null);

        assertThat(exchanges.read(SCOPE, at(sent)).handoverBody())
            .as("the draft is there before the claim lapses, or the assertion below "
                + "would hold against an exchange that never had one")
            .isEqualTo("half an answer, left behind");

        expireTheClaim(sent.id);
        exchanges.takeup(SCOPE, at(sent), NEXT_EXECUTOR, CLAIM);

        assertThat(exchanges.read(SCOPE, at(sent)).handoverBody())
            .as("hard deleted in the reclaiming transaction. Nobody inherits a stranger's "
                + "half-written text")
            .isNull();
    }

    @Test
    void revert_also_drops_the_claim_and_the_draft() {
        Exchange sent = openAndSend("a commission handed back deliberately");
        var claim = exchanges.takeup(SCOPE, at(sent), EXECUTOR, CLAIM);
        exchanges.writeHandoverDraft(SCOPE, at(sent), EXECUTOR, claim.receipt(),
            "an answer being abandoned", null);

        Exchange reverted = exchanges.revert(SCOPE, at(sent), CONSOLE);

        assertThat(reverted.status())
            .as("revert stays as the deliberate human way back")
            .isEqualTo(ExchangeStatus.OPEN);
        assertThat(reverted.effectiveHolder(Instant.now())).isNull();
        assertThat(reverted.handoverBody())
            .as("and the draft goes with it, for the same reason it goes on a reclaim")
            .isNull();
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    /**
     * Moves a claim's expiry into the past.
     *
     * <p>Directly, rather than by waiting or by injecting a clock: the
     * property under test is what the service does with a lapsed claim, and
     * how it came to lapse is not part of it. Written as the administrator so
     * the freeze trigger and the tenancy policy are not what is being
     * exercised here.
     */
    private void expireTheClaim(UUID id) {
        PlatformFixture.run("UPDATE dispatch.exchange "
            + "SET claim_expires_at = now() - interval '1 hour' WHERE id = '" + id + "'");
    }

    private Snapshot snapshot(UUID id) throws SQLException {
        var config = ConfigProvider.getConfig();
        try (Connection c = DriverManager.getConnection(
                config.getValue("test.db.url", String.class),
                config.getValue("test.db.admin.username", String.class),
                config.getValue("test.db.admin.password", String.class));
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT status, holder_subject, updated_by FROM dispatch.exchange "
                     + "WHERE id = '" + id + "'")) {
            rs.next();
            return new Snapshot(rs.getString(1), rs.getString(2), rs.getString(3));
        }
    }

    private Exchange openAndSend(String title) {
        Exchange e = exchanges.openBracket(SCOPE, "sprint", title, "code",
            LocalDate.now(), CONSOLE);
        return exchanges.send(SCOPE, at(e), CONSOLE);
    }

    private static ExchangeAddress at(Exchange e) {
        return new ExchangeAddress(e.selector, e.number, e.sub, e.addendumSuffix);
    }

    private record Snapshot(String status, String holder, String updatedBy) {
    }
}
