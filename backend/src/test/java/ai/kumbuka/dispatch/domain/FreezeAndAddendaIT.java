package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.eclipse.microprofile.config.ConfigProvider.getConfig;

/**
 * The freeze, and the addenda that exist because of it.
 *
 * <p>Before send an exchange is fully mutable and hard-deletable; after send
 * its dispatch fields are immutable while the status goes on moving. That
 * asymmetry is the whole point of having a gate rather than a rule: everything
 * before it is provisional, everything after it was committed to, and a
 * correction to something committed to is itself a recorded act.
 *
 * <p>Which is what an addendum is for, and why it is numbered with a letter.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class FreezeAndAddendaIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final String ACTOR = "probe";

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

    // -----------------------------------------------------------------------
    // The freeze
    // -----------------------------------------------------------------------

    @Test
    void a_draft_is_fully_mutable() throws SQLException {
        Exchange draft = exchanges.openBracket(SCOPE, "sprint", "first wording", "code",
            LocalDate.now(), ACTOR);

        assertThat(draft.frozen())
            .as("before send there is no commitment to protect")
            .isFalse();

        rewriteTitle(draft.id, "second wording");

        assertThat(titleOf(draft.id))
            .as("a draft is edited, not corrected — an addendum before a commitment "
                + "would be recording a change nobody had yet relied on")
            .isEqualTo("second wording");
    }

    @Test
    void a_frozen_dispatch_refuses_a_write_to_its_fields() throws SQLException {
        Exchange sent = openAndSend("the wording as committed to");

        assertThat(sent.frozen()).isTrue();
        assertThatThrownBy(() -> rewriteTitle(sent.id, "a wording nobody agreed to"))
            .as("after send the dispatch fields are immutable. The refusal comes from the "
                + "table, so it holds for raw SQL and for a migration written in a hurry — "
                + "a freeze enforced in one layer is a freeze with a known way around it")
            .hasMessageContaining("frozen");

        assertThat(titleOf(sent.id))
            .as("and the stored wording is untouched, which is the half that matters")
            .isEqualTo("the wording as committed to");
    }

    /**
     * The red state of the freeze, observed on every build.
     *
     * <p>The refusal is removed for the length of one write and the write must
     * then go through. Without this the assertion above would hold just as
     * well against an UPDATE that fails for some entirely different reason — a
     * typo in the column name, a missing row, a constraint nobody was thinking
     * about — and the test would be reporting a freeze that is not there.
     */
    @Test
    void with_the_refusal_removed_the_same_write_goes_through() throws SQLException {
        Exchange sent = openAndSend("the wording as committed to");

        assertThatThrownBy(() -> rewriteTitle(sent.id, "green state: refused"))
            .hasMessageContaining("frozen");

        try {
            PlatformFixture.run("ALTER TABLE dispatch.exchange DISABLE TRIGGER exchange_freeze");

            rewriteTitle(sent.id, "RED STATE: written past the freeze");
            assertThat(titleOf(sent.id))
                .as("RED STATE, observed: with the trigger disabled the very same "
                    + "statement rewrites a frozen dispatch. So the refusal above was the "
                    + "freeze doing its work and not the statement failing for its own "
                    + "reasons")
                .isEqualTo("RED STATE: written past the freeze");
        } finally {
            PlatformFixture.run("ALTER TABLE dispatch.exchange ENABLE TRIGGER exchange_freeze");
        }

        assertThatThrownBy(() -> rewriteTitle(sent.id, "and refused again"))
            .as("and restored, so the red state was the disabled trigger and nothing else")
            .hasMessageContaining("frozen");
    }

    // -----------------------------------------------------------------------
    // Addenda
    // -----------------------------------------------------------------------

    @Test
    void an_addendum_takes_a_letter_and_never_a_sub_number() {
        Exchange base = openAndSend("the exchange being corrected");
        ExchangeAddress at = new ExchangeAddress(base.selector, base.number, base.sub, null);

        Exchange first = exchanges.addAddendum(SCOPE, at, "a correction", "code",
            LocalDate.now(), ACTOR);
        Exchange second = exchanges.addAddendum(SCOPE, at, "another correction", "code",
            LocalDate.now(), ACTOR);

        assertThat(first.addendumSuffix).isEqualTo("a");
        assertThat(second.addendumSuffix).isEqualTo("b");
        assertThat(first.sub)
            .as("the addendum keeps the sub of what it corrects. A regular sub-number "
                + "would make it an ordinary child of the bracket — and an ordinary child "
                + "carries the handover expectation and counts in the terminality check")
            .isEqualTo(base.sub);
        assertThat(first.address()).endsWith(".0a");
    }

    @Test
    void an_address_with_a_sub_number_where_a_letter_belongs_is_refused() {
        assertThatThrownBy(() -> new ExchangeAddress("sprint", 149, 0, "1"))
            .isInstanceOfSatisfying(DispatchException.class, x -> assertThat(x.reason())
                .as("a digit in the suffix position is the exact mistake the rule exists "
                    + "to prevent, so it is refused where the address is built rather "
                    + "than somewhere downstream")
                .isEqualTo(DispatchException.Reason.ADDENDUM_MALFORMED));
    }

    @Test
    void an_addendum_is_not_independently_drawable() {
        Exchange base = openAndSend("the exchange being corrected");
        ExchangeAddress at = new ExchangeAddress(base.selector, base.number, base.sub, null);
        exchanges.addAddendum(SCOPE, at, "a correction", "code", LocalDate.now(), ACTOR);

        ExchangeAddress addendumAddress =
            new ExchangeAddress(base.selector, base.number, base.sub, "a");

        assertThatThrownBy(() -> exchanges.read(SCOPE, addendumAddress))
            .isInstanceOfSatisfying(DispatchException.class, x -> assertThat(x.reason())
                .as("an addendum is a correction to something and has no standing without "
                    + "it; served on its own it would read as an exchange in its own right")
                .isEqualTo(DispatchException.Reason.ADDENDUM_NOT_DRAWABLE));

        assertThat(exchanges.addenda(SCOPE, at))
            .as("it reaches a caller through the exchange it corrects, which is the only "
                + "context in which it means anything")
            .hasSize(1);
    }

    @Test
    void an_addendum_cannot_hang_from_a_draft() {
        Exchange draft = exchanges.openBracket(SCOPE, "sprint", "still provisional", "code",
            LocalDate.now(), ACTOR);
        ExchangeAddress at = new ExchangeAddress(draft.selector, draft.number, draft.sub, null);

        assertThatThrownBy(() -> exchanges.addAddendum(SCOPE, at, "a correction", "code",
            LocalDate.now(), ACTOR))
            .as("there is nothing to correct until a commitment was acquired; before that "
                + "the exchange is simply edited")
            .isInstanceOfSatisfying(DispatchException.class, x -> assertThat(x.reason())
                .isEqualTo(DispatchException.Reason.TRANSITION_NOT_PERMITTED));
    }

    @Test
    void terminating_the_base_cascades_onto_its_addenda_in_one_transaction() {
        Exchange base = openAndSend("the exchange being corrected");
        ExchangeAddress at = new ExchangeAddress(base.selector, base.number, base.sub, null);
        exchanges.addAddendum(SCOPE, at, "a correction", "code", LocalDate.now(), ACTOR);

        assertThat(exchanges.addenda(SCOPE, at).get(0).status().terminal())
            .as("the addendum starts non-terminal, or the cascade below would prove nothing")
            .isFalse();

        exchanges.close(SCOPE, at, ACTOR);

        assertThat(exchanges.addenda(SCOPE, at).get(0).status())
            .as("a terminal transition of the base cascades onto its addenda. Leaving one "
                + "behind would create an object nobody can reach and nothing can close — "
                + "an addendum has no standing of its own to be closed through")
            .isEqualTo(ExchangeStatus.CLOSED);
    }

    // -----------------------------------------------------------------------
    // Selectors
    // -----------------------------------------------------------------------

    @Test
    void an_undeclared_selector_is_a_typed_refusal() {
        assertThatThrownBy(() -> exchanges.openBracket(SCOPE, "never-declared", "a title",
            "code", LocalDate.now(), ACTOR))
            .isInstanceOfSatisfying(DispatchException.class, x -> assertThat(x.reason())
                .as("bracket names are declared before use, never by first use: a typo "
                    + "must not silently open a namespace")
                .isEqualTo(DispatchException.Reason.SELECTOR_NOT_DECLARED));
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private Exchange openAndSend(String title) {
        Exchange e = exchanges.openBracket(SCOPE, "sprint", title, "code",
            LocalDate.now(), ACTOR);
        return exchanges.send(SCOPE,
            new ExchangeAddress(e.selector, e.number, e.sub, e.addendumSuffix), ACTOR);
    }

    /**
     * Rewrites a title through raw SQL under the service role.
     *
     * <p>Deliberately not through the ORM: the freeze has to hold against a
     * statement the application never built, because that is the case the
     * table-level trigger exists for.
     */
    private void rewriteTitle(UUID id, String title) throws SQLException {
        try (Connection c = serviceConnection(); Statement s = c.createStatement()) {
            s.execute("SELECT set_config('app.tenant_id', '" + tenant + "', true)");
            s.executeUpdate("UPDATE dispatch.exchange SET title = '" + title
                + "' WHERE id = '" + id + "'");
            c.commit();
        }
    }

    private String titleOf(UUID id) throws SQLException {
        try (Connection c = serviceConnection(); Statement s = c.createStatement()) {
            s.execute("SELECT set_config('app.tenant_id', '" + tenant + "', true)");
            try (var rs = s.executeQuery(
                    "SELECT title FROM dispatch.exchange WHERE id = '" + id + "'")) {
                rs.next();
                String title = rs.getString(1);
                c.commit();
                return title;
            }
        }
    }

    private static Connection serviceConnection() throws SQLException {
        Connection c = DriverManager.getConnection(
            getConfig().getValue("test.db.url", String.class),
            SubstrateDatabaseResource.SERVICE_ROLE,
            SubstrateDatabaseResource.SERVICE_PASSWORD);
        c.setAutoCommit(false);
        return c;
    }
}
