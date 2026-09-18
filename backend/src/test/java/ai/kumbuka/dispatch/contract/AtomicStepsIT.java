package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.domain.Exchange;
import ai.kumbuka.dispatch.domain.ExchangeAddress;
import ai.kumbuka.dispatch.domain.ExchangeService;
import ai.kumbuka.dispatch.domain.ExchangeStatus;
import ai.kumbuka.dispatch.domain.DomainFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A7: a compound process step leaves nothing behind when its last part fails.
 *
 * <p>The criterion is about what is NOT there afterwards, which is why it is
 * measured against a real database. A persistence context can make a failed
 * write look rolled back while the row is still there — the only witness that
 * settles it is a read in a later transaction.
 *
 * <h2>How the failure is forced</h2>
 *
 * Not with a mock. Each compound is driven into a state where its LAST part is
 * the one the kernel refuses, using the kernel's own rules: a bracket root with
 * an unfinished child refuses at the close, and an exchange with no delivered
 * answer refuses at the ratify. That is better than an injected fault, because
 * an injected fault proves the transaction boundary and these prove the
 * transaction boundary AND that the refusal a caller actually meets is one of
 * the ones that leaves nothing behind.
 *
 * <h2>This file is NOT a gate, and saying so is the point</h2>
 *
 * Three mutations were run against it on 2026-09-19 and all three left it
 * green:
 *
 * <ol>
 *   <li>Swapping the children gate and the ratification in
 *       {@code closeBracket}. Green: the refusal rolls the ratification back
 *       whichever of the two runs first.</li>
 *   <li>Removing the children gate from {@code closeBracket} entirely. Green:
 *       the kernel checks it again at the CLOSE transition, where it holds for
 *       every caller including a future adapter.</li>
 *   <li>{@code @Transactional(TxType.NEVER)} on the compound. Green, and this
 *       one is the instructive failure: without a transaction nothing is
 *       written at all, and "nothing was left behind" is trivially true of a
 *       call that wrote nothing.</li>
 * </ol>
 *
 * <p>The guarantee this file describes is carried by the transaction boundary
 * and by a kernel check that exists twice over, and neither is refutable by a
 * mutation of the shape a red probe uses: every edit that removes the
 * guarantee also removes the writing whose absence the probe asserts. So these
 * probes are a WITNESS — they record that the states and the stamps are
 * untouched after a refusal — and not a gate, and the distinction is stated
 * here rather than left for a reader to assume the stronger of the two.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Tag("TST-0007")
class AtomicStepsIT {

    private static final UUID SCOPE =
        UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final String SELECTOR = "sprint";

    private static final Actor CONSOLE =
        new Actor("probe-console", Actor.Kind.CONSOLE);

    @Inject ExchangeService exchanges;
    @Inject ai.kumbuka.dispatch.tenancy.TenantContext tenantContext;

    private AutoCloseable binding;

    /**
     * A fresh tenant per probe.
     *
     * <p>Each of these counts rows and numbers, and a shared tenant would let
     * one probe's bracket show up in another's count. The allocation probe in
     * particular reads consecutive numbers, which only means anything inside
     * one tenant's own circle.
     */
    @BeforeEach
    void freshTenant() {
        UUID tenant = UUID.randomUUID();
        DomainFixture.declareSelector(tenant, SCOPE, SELECTOR);
        binding = tenantContext.bind(tenant);
    }

    @org.junit.jupiter.api.AfterEach
    void unbind() throws Exception {
        binding.close();
    }

    // =======================================================================
    // close_bracket: check, ratify, close
    // =======================================================================

    /**
     * The bracket's gate fires before the ratification, so a refused close
     * leaves the root's answer unfrozen.
     *
     * <p>The failure this guards against is specific and would be permanent:
     * a compound that ratified first and then hit the children gate would
     * freeze the root's return with the bracket still open — and there is no
     * verb that un-freezes a return, so the bracket could never be closed by
     * anybody.
     */
    @Test
    void a_refused_close_bracket_leaves_the_root_unratified() {
        Exchange root = commission(null, "a bracket root");
        commission(root.number, "an unfinished child");
        deliverOn(root);

        assertThatThrownBy(() -> exchanges.closeBracket(SCOPE, addressOf(root), CONSOLE))
            .isInstanceOf(DispatchException.class);

        Exchange after = readBack(addressOf(root));
        assertThat(after.status())
            .as("the root must still be where it was: a compound that got halfway would "
                + "have left it RETURNED, with the bracket open and no verb able to "
                + "finish it")
            .isEqualTo(ExchangeStatus.NEEDS_INPUT);
        assertThat(after.ratifiedAt())
            .as("and its answer unfrozen — the ratification is the part that cannot be "
                + "undone")
            .isNull();
    }

    // =======================================================================
    // accept_return and curate_return: ratify, then close or consume
    // =======================================================================

    /**
     * An accept with nothing to accept leaves the exchange exactly as it was.
     *
     * <p>The refusal comes from the first part rather than the last, and that
     * is the honest shape of this one: {@code ratify} is what checks for an
     * answer. What is asserted is the same thing either way — nothing moved.
     */
    @Test
    void a_refused_accept_return_leaves_no_half_state() {
        Exchange e = commission(null, "an exchange with no answer");

        assertThatThrownBy(() -> exchanges.acceptReturn(SCOPE, addressOf(e), CONSOLE))
            .isInstanceOf(DispatchException.class);

        assertThat(readBack(addressOf(e)).status())
            .as("neither ratified nor closed: a compound whose first part succeeded and "
                + "whose second failed would leave the exchange RETURNED, which is a "
                + "state this surface never shows")
            .isEqualTo(ExchangeStatus.OPEN);
    }

    /**
     * A curation into a target that does not exist writes neither the target
     * nor the state.
     *
     * <p>The target is resolved FIRST, deliberately: resolving it last would
     * mean ratifying and consuming an exchange and then discovering there was
     * nothing to consume it into.
     */
    @Test
    void a_curate_return_into_a_target_that_does_not_exist_writes_nothing() {
        Exchange e = commission(null, "an exchange to curate");
        deliverOn(e);

        ExchangeAddress nowhere = ExchangeAddress.bracket(SELECTOR, 99999);
        assertThatThrownBy(() -> exchanges.curateReturn(SCOPE, addressOf(e), nowhere, CONSOLE))
            .isInstanceOf(DispatchException.class);

        Exchange after = readBack(addressOf(e));
        assertThat(after.status()).isEqualTo(ExchangeStatus.NEEDS_INPUT);
        assertThat(after.curatedIntoId())
            .as("the stored target is written in the same transaction as the consumption, "
                + "so a refused curation records no target either")
            .isNull();
        assertThat(after.ratifiedAt()).isNull();
    }

    // =======================================================================
    // commission: create, write, send
    // =======================================================================

    /**
     * A commission into a bracket that does not exist allocates no number and
     * leaves no row.
     *
     * <p>The number matters as much as the row. It is allocated under a lock
     * in the same transaction, so a rolled-back commission gives it back — and
     * the class "burned number" has nowhere to occur. A commission that failed
     * after allocating would leave a gap in the address space that nothing
     * explains.
     */
    @Test
    void a_refused_commission_leaves_no_row_and_burns_no_number() {
        Exchange first = commission(null, "the one before");
        int numberBefore = first.number;

        assertThatThrownBy(() -> exchanges.commission(SCOPE, SELECTOR, 99999,
            "a child of nothing", "code", "its body", LocalDate.parse("2026-09-18"),
            Map.of(), CONSOLE))
            .isInstanceOf(DispatchException.class);

        Exchange next = commission(null, "the one after");
        assertThat(next.number)
            .as("the refused commission must have given its number back: a gap in the "
                + "address space is a permanent artefact of a transaction that failed")
            .isEqualTo(numberBefore + 1);
    }

    /**
     * A commission never leaves a draft behind, whether it succeeds or not.
     *
     * <p>The state {@code draft} exists only inside the transaction of a
     * commission and is never visible. That is the property that makes the
     * measured defect impossible: an assistant cannot be stranded in a state
     * it cannot see and has no call to leave.
     */
    @Test
    void no_draft_is_ever_left_behind_by_a_commission() {
        Exchange e = commission(null, "a commission");

        assertThat(readBack(addressOf(e)).status())
            .as("commissioning creates, writes and freezes in one transaction — measured "
                + "2026-09-18, an assistant called create and stopped, because nothing "
                + "it could see said a send was owed")
            .isEqualTo(ExchangeStatus.OPEN);
    }

    // =======================================================================
    // Driving the domain
    // =======================================================================

    @Transactional
    Exchange commission(Integer parent, String title) {
        return exchanges.commission(SCOPE, SELECTOR, parent, title, "code", "its body",
            LocalDate.parse("2026-09-18"), Map.of(), CONSOLE);
    }

    /** Takes the exchange up and delivers an answer on it, as the console. */
    @Transactional
    void deliverOn(Exchange e) {
        exchanges.takeup(SCOPE, addressOf(e), CONSOLE, java.time.Duration.ofHours(1));
        exchanges.deliverReturn(SCOPE, addressOf(e), CONSOLE, null, "the answer");
    }

    /**
     * The row as a LATER transaction sees it.
     *
     * <p>The whole point of this file. A persistence context can answer from
     * memory what the database never accepted, so every assertion here reads
     * back rather than inspecting the object the call returned.
     */
    @Transactional
    Exchange readBack(ExchangeAddress address) {
        return exchanges.read(SCOPE, address);
    }

    private static ExchangeAddress addressOf(Exchange e) {
        return new ExchangeAddress(e.selectorName(), e.number, e.sub, e.addendumSuffix);
    }
}
