package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.domain.DomainFixture;
import ai.kumbuka.dispatch.domain.Exchange;
import ai.kumbuka.dispatch.domain.ExchangeAddress;
import ai.kumbuka.dispatch.domain.ExchangeService;
import ai.kumbuka.dispatch.domain.ExchangeStatus;
import ai.kumbuka.dispatch.domain.IdempotencyKey;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three paths that write to the database and had no green run.
 *
 * <p>Every one of these was found by the review of 2026-09-19, and the
 * shape of the finding was the same each time: a write the code performs and
 * no probe ever performs. A refused curation was exercised twice and a
 * successful one never, so the FK column V13 adds and the query that resolves
 * it back to an address had never run green; the new kernel rule "no
 * correction on a finished exchange" shipped with no probe calling it; and the
 * idempotency ledger was not there at all.
 *
 * <p>Against a real database under the real role, per the testing discipline:
 * a mocked suite cannot see a missing grant, and V14 adds a relation with a new
 * one. The tenant is fresh per probe, so a count here is a count of what this
 * probe made.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Tag("TST-0012")
@Tag("TST-0013")
class CurationAndCorrectionIT {

    private static final UUID SCOPE =
        UUID.fromString("00000000-0000-0000-0000-000000000010");

    /**
     * A second scope, so the cross-scope curation the contract admits is
     * exercised rather than described.
     *
     * <p>Section 5.1: the target is "an exchange of this service that the
     * caller may see, in any scope and bracket kind, other than the exchange
     * itself". The predecessor refused any target outside the exchange's own
     * scope and selector, so the sentence was true of the contract and false of
     * the service.
     */
    private static final UUID OTHER_SCOPE =
        UUID.fromString("00000000-0000-0000-0000-000000000011");

    private static final String SELECTOR = "sprint";

    /** The scope slug the probe scope goes by, for the addresses it renders. */
    private static final String PROBE_SLUG =
        SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String OTHER_SELECTOR = "satellite";

    private static final Actor CONSOLE =
        new Actor("probe-console", Actor.Kind.CONSOLE);
    private static final Actor EXECUTOR =
        new Actor("probe-executor", Actor.Kind.EXECUTOR);

    @Inject ExchangeService exchanges;
    @Inject ai.kumbuka.dispatch.tenancy.TenantContext tenantContext;

    private AutoCloseable binding;

    @BeforeEach
    void freshTenant() {
        // The directory read, because the projection renders a curated
        // target's address from the target's OWN scope slug and reads that
        // slug from the subject-filtered directory view. Without the grant the
        // read is a permission error rather than an empty answer — which is
        // correct and is not what these probes are measuring.
        ai.kumbuka.dispatch.platform.PlatformFixture.grantDirectoryAccess();

        UUID tenant = UUID.randomUUID();
        DomainFixture.declareSelector(tenant, SCOPE, SELECTOR);
        DomainFixture.declareSelector(tenant, SCOPE, OTHER_SELECTOR);
        DomainFixture.declareSelector(tenant, OTHER_SCOPE, OTHER_SELECTOR);
        binding = tenantContext.bind(tenant);
    }

    @org.junit.jupiter.api.AfterEach
    void unbind() throws Exception {
        binding.close();
    }

    // =======================================================================
    // curate_return: the success path, and the column it writes
    // =======================================================================

    /**
     * A curation into an exchange of another scope and another bracket kind
     * succeeds, stores the target, and reads it back as a complete address.
     *
     * <p>Three assertions and each covers a different half-built outcome: the
     * exchange ends {@code consumed}; the FK column carries the target's
     * durable identity; and the projection resolves that identity back into an
     * address the caller can act on. The last is the one that needs a real
     * database — it is a second query, against a row written in the same
     * transaction.
     */
    @Test
    void a_curation_into_another_bracket_kind_succeeds_and_reads_its_target_back() {
        Exchange answered = anExchangeWithAnAnswer();
        Exchange target = commissionIn(SCOPE, OTHER_SELECTOR, "the record it goes into");

        exchanges.curateReturn(SCOPE, addressOf(answered), SCOPE, addressOf(target),
            CONSOLE);

        Exchange after = readBack(SCOPE, addressOf(answered));
        assertThat(after.status())
            .as("ratify and consume in one transaction, and the exchange is finished")
            .isEqualTo(ExchangeStatus.CONSUMED);
        assertThat(after.curatedIntoId())
            .as("the target is stored by durable identity (ADR-0014), and the reference is "
                + "a real FK — a value no probe had ever written Before 2026-09-19")
            .isEqualTo(target.id);

        assertThat(exchanges.view(SCOPE, PROBE_SLUG, addressOf(answered), CONSOLE)
            .curatedInto())
            .as("and the projection reads it back, as a complete address of the target's "
                + "OWN bracket kind. The predecessor refused this curation outright, so "
                + "neither the write nor this resolving query had ever run green")
            .isEqualTo("dispatch://" + PROBE_SLUG + "/" + OTHER_SELECTOR + "/"
                + target.number + "." + target.sub);
    }

    /**
     * And across scopes, which the contract admits and the predecessor refused.
     *
     * <p>The stored reference is asserted and the rendered address is not: the
     * second scope of this probe has no row in the directory view, so its slug
     * is unknown to the caller and the address is withheld — which is the
     * designed behaviour (a target in a scope the caller may not see is not
     * named) and not the thing under test here. What is under test is that the
     * curation happens at all.
     */
    @Test
    void a_curation_into_another_scope_stores_its_target() {
        Exchange answered = anExchangeWithAnAnswer();
        Exchange target = commissionIn(OTHER_SCOPE, OTHER_SELECTOR, "a record elsewhere");

        exchanges.curateReturn(SCOPE, addressOf(answered), OTHER_SCOPE, addressOf(target),
            CONSOLE);

        Exchange after = readBack(SCOPE, addressOf(answered));
        assertThat(after.status()).isEqualTo(ExchangeStatus.CONSUMED);
        assertThat(after.curatedIntoId())
            .as("section 5.1 admits a target in any scope, and the reference is an "
                + "identity — so nothing about the target's address constrains where it "
                + "may live")
            .isEqualTo(target.id);
    }

    /** An exchange cannot be carried into itself, and the refusal is typed. */
    @Test
    void a_curation_into_the_exchange_itself_is_refused_and_writes_nothing() {
        Exchange answered = anExchangeWithAnAnswer();

        assertThatThrownBy(() -> exchanges.curateReturn(SCOPE, addressOf(answered), SCOPE,
            addressOf(answered), CONSOLE))
            .isInstanceOf(DispatchException.class)
            .extracting(e -> ((DispatchException) e).reason())
            .as("its own reason, so a surface can word it specifically without reading the "
                + "kernel's sentence")
            .isEqualTo(DispatchException.Reason.CURATION_TARGET_SELF);

        Exchange after = readBack(SCOPE, addressOf(answered));
        assertThat(after.status())
            .as("the ratification is rolled back with the refusal")
            .isEqualTo(ExchangeStatus.NEEDS_INPUT);
        assertThat(after.curatedIntoId()).isNull();
    }

    /**
     * A target in a scope this caller cannot see is a {@code NOT_FOUND}, and
     * never a statement that the scope exists.
     *
     * <p>Resolved against the scope the caller named, so the refusal is the
     * one section 4.3 fixes: absent and invisible are not told apart.
     */
    @Test
    void a_curation_into_a_target_that_is_not_there_is_refused_and_writes_nothing() {
        Exchange answered = anExchangeWithAnAnswer();
        ExchangeAddress nowhere = ExchangeAddress.bracket(OTHER_SELECTOR, 99999);

        assertThatThrownBy(() -> exchanges.curateReturn(SCOPE, addressOf(answered),
            OTHER_SCOPE, nowhere, CONSOLE))
            .isInstanceOf(DispatchException.class)
            .extracting(e -> ((DispatchException) e).reason())
            .isEqualTo(DispatchException.Reason.NOT_FOUND);

        assertThat(readBack(SCOPE, addressOf(answered)).curatedIntoId()).isNull();
    }

    // =======================================================================
    // add_correction: not on a finished exchange
    // =======================================================================

    /**
     * The kernel rule that shipped without its guard.
     *
     * <p>A correction closes together with what it corrects, and the cascade
     * runs at the base's terminal transition. One attached afterwards would be
     * non-terminal for ever, hanging off a finished exchange, closable by
     * nothing. {@code next} withholds the call on a terminal exchange, but
     * {@code next} is a list and not a gate: a caller that calls it anyway
     * reaches the kernel, and before this probe nothing had ever watched the
     * kernel refuse.
     */
    @Test
    void a_correction_on_a_finished_exchange_is_refused() {
        Exchange finished = anExchangeWithAnAnswer();
        exchanges.acceptReturn(SCOPE, addressOf(finished), CONSOLE);

        assertThatThrownBy(() -> exchanges.addCorrection(SCOPE, addressOf(finished),
            "too late", "the exchange is over", CONSOLE))
            .isInstanceOf(DispatchException.class)
            .extracting(e -> ((DispatchException) e).reason())
            .as("the surface answers STATE_DOES_NOT_ALLOW for this, which is what a "
                + "caller that called an unlisted call is told")
            .isEqualTo(DispatchException.Reason.TRANSITION_NOT_PERMITTED);

        assertThat(exchanges.addenda(SCOPE, addressOf(finished)))
            .as("and nothing was attached: an orphan correction is exactly what the rule "
                + "exists to prevent")
            .isEmpty();
    }

    // =======================================================================
    // The ledger, on the correction path
    // =======================================================================

    /**
     * Two arrivals of one correction are one correction.
     *
     * <p>Counted through {@link ExchangeService#addenda}, because a correction
     * has no standing of its own and the surface has no read path to one — the
     * only way to see whether there are two is to ask the store. Which makes
     * this the probe that would have caught a duplicate correction, and a
     * correction cannot be removed: a duplicate is permanent.
     */
    @Test
    void a_repeated_correction_under_one_key_attaches_once() {
        Exchange commissioned = commissionIn(SCOPE, SELECTOR, "a commission to correct");

        correctTwiceUnder("a-key-of-my-own", commissioned);

        assertThat(exchanges.addenda(SCOPE, addressOf(commissioned)))
            .as("the second arrival is the first call again, and adds nothing")
            .hasSize(1);
    }

    /** The same key on a different correction is refused, and adds nothing. */
    @Test
    void the_same_key_on_a_different_correction_is_refused() {
        Exchange commissioned = commissionIn(SCOPE, SELECTOR, "a commission to correct");
        IdempotencyKey key = IdempotencyKey.of("a-key-spent-on-a-correction");

        exchanges.addCorrectionUnder(SCOPE, addressOf(commissioned), "a correction",
            "its text", CONSOLE, key);

        assertThatThrownBy(() -> exchanges.addCorrectionUnder(SCOPE,
            addressOf(commissioned), "a different correction", "different text", CONSOLE,
            key))
            .isInstanceOf(DispatchException.class)
            .extracting(e -> ((DispatchException) e).reason())
            .isEqualTo(DispatchException.Reason.IDEMPOTENCY_KEY_REUSED);

        assertThat(exchanges.addenda(SCOPE, addressOf(commissioned)))
            .as("a refusal that had written would be the worst of both outcomes")
            .hasSize(1);
    }

    // =======================================================================
    // Building the states
    // =======================================================================

    @Transactional
    void correctTwiceUnder(String key, Exchange commissioned) {
        IdempotencyKey chosen = IdempotencyKey.of(key);
        exchanges.addCorrectionUnder(SCOPE, addressOf(commissioned), "a correction",
            "its text", CONSOLE, chosen);
        exchanges.addCorrectionUnder(SCOPE, addressOf(commissioned), "a correction",
            "its text", CONSOLE, chosen);
    }

    private Exchange commissionIn(UUID scope, String selector, String title) {
        return exchanges.commission(scope, selector, null, title, "code",
            "the body of " + title, LocalDate.parse("2026-09-01"), null, CONSOLE);
    }

    /** A commissioned exchange, taken up and answered. */
    private Exchange anExchangeWithAnAnswer() {
        Exchange e = commissionIn(SCOPE, SELECTOR, "an exchange to finish");
        String receipt = exchanges.takeup(SCOPE, addressOf(e), EXECUTOR,
            java.time.Duration.ofHours(1)).receipt();
        exchanges.deliverReturn(SCOPE, addressOf(e), EXECUTOR, receipt, "the answer");
        return e;
    }

    @Transactional
    Exchange readBack(UUID scope, ExchangeAddress address) {
        return exchanges.read(scope, address);
    }

    private static ExchangeAddress addressOf(Exchange e) {
        return new ExchangeAddress(e.selectorName(), e.number, e.sub, e.addendumSuffix);
    }
}
