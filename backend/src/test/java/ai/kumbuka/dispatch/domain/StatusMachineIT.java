package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The status machine: nine values, one verb per transition, and no way in
 * besides those verbs.
 *
 * <p>The assertions that matter here are the ones about what CANNOT happen. A
 * machine is only as good as the transitions it refuses, and a test suite that
 * walks the happy path proves that the path exists — which was never the
 * question.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class StatusMachineIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    /**
     * Two actors, because two of this service's guarantees are permissions
     * bound to the caller. Where a test does not care which, it uses the
     * console — the capacity with the fewer restrictions, so a refusal in such
     * a test is never about the capacity.
     *
     * <p>{@code CLAIM} is long enough that nothing in a test lapses by accident.
     */
    static final java.time.Duration CLAIM = java.time.Duration.ofHours(1);
    static final Actor EXECUTOR = new Actor("probe-executor", Actor.Kind.EXECUTOR);
    static final Actor CONSOLE = new Actor("probe-console", Actor.Kind.CONSOLE);

    @Inject ExchangeService exchanges;
    @Inject TenantContext tenantContext;

    private UUID tenant;
    private AutoCloseable binding;

    /**
     * A fresh tenant per test, bound for the whole test.
     *
     * <p>Bound here rather than around each call: an exchange and the
     * transitions applied to it have to land under one tenant, and a binding
     * that opened and closed per call would let a helper create under one
     * tenant and a verb run under another — which the policy would refuse in a
     * way that reads like a broken transition.
     */
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
    // The shape of the machine
    // -----------------------------------------------------------------------

    @Test
    void all_nine_values_exist_and_the_right_four_are_terminal() {
        assertThat(ExchangeStatus.values()).hasSize(9);

        assertThat(EnumSet.allOf(ExchangeStatus.class).stream()
                .filter(ExchangeStatus::terminal).toList())
            .as("terminal are closed, consumed, rejected and failed. `returned` is "
                + "deliberately NOT — an answered but uncurated exchange is not finished — "
                + "and neither is `needs_input`, which is a pause and not an end")
            .containsExactlyInAnyOrder(ExchangeStatus.CLOSED, ExchangeStatus.CONSUMED,
                ExchangeStatus.REJECTED, ExchangeStatus.FAILED);
    }

    /**
     * Every transition has exactly one verb, and the entity offers no other
     * way to move.
     *
     * <p>Read from the class rather than asserted about a list somebody keeps:
     * a setter added later would pass any check that only enumerates the verbs
     * we already know about.
     *
     * <p>The {@code $$_hibernate_} accessors are excluded, and the exclusion is
     * narrow on purpose. Bytecode enhancement generates a writer for every
     * persistent field — it is how dirty checking works — so the entity does
     * carry {@code $$_hibernate_write_status} at runtime whatever the source
     * says. It is not an API: nothing declares it, nothing outside Hibernate
     * calls it, and code that did would be reaching past the mapping layer on
     * purpose rather than finding a door left open. What this test guards is
     * the surface a developer can reach without meaning to, which is the
     * surface the source declares.
     */
    @Test
    void the_entity_exposes_no_generic_status_write() {
        Set<String> statusWriters = Arrays.stream(Exchange.class.getMethods())
            .filter(m -> m.getName().toLowerCase().contains("status"))
            .filter(m -> m.getParameterCount() > 0)
            .filter(m -> !m.getName().startsWith("$$_hibernate_"))
            .map(Method::getName)
            .collect(java.util.stream.Collectors.toSet());

        assertThat(statusWriters)
            .as("a method taking a status would let any state be reached from any other, "
                + "and the freeze is a rule about transitions rather than about fields — "
                + "so a generic write is not a shortcut past one check but past all of them")
            .isEmpty();

        Set<String> serviceStatusWriters = Arrays.stream(ExchangeService.class.getMethods())
            .filter(m -> Arrays.stream(m.getParameterTypes())
                .anyMatch(ExchangeStatus.class::equals))
            .map(Method::getName)
            .collect(java.util.stream.Collectors.toSet());

        assertThat(serviceStatusWriters)
            .as("and the service must not accept a status either — the verbs are the surface")
            .isEmpty();
    }

    // -----------------------------------------------------------------------
    // The refusals
    // -----------------------------------------------------------------------

    @Test
    void rejected_is_reachable_from_open_and_from_nowhere_else() {
        Exchange e = openAndSend("a commission that will be refused");
        exchanges.takeup(SCOPE, address(e), EXECUTOR, CLAIM);

        assertThatThrownBy(() -> exchanges.reject(SCOPE, address(e), CONSOLE))
            .as("a refusal says the commission was wrong, which can only be said before "
                + "taking it up. After takeup the honest verb is `fail`")
            .isInstanceOfSatisfying(DispatchException.class, x -> assertThat(x.reason())
                .isEqualTo(DispatchException.Reason.TRANSITION_NOT_PERMITTED));
    }

    @Test
    void failed_is_reachable_from_active_and_from_nowhere_else() {
        Exchange e = openAndSend("a commission nobody took up");

        assertThatThrownBy(() -> exchanges.fail(SCOPE, address(e), CONSOLE))
            .as("a failure says the WORK was wrong, and there is no work until somebody "
                + "took the exchange up. From open the honest verb is `reject`")
            .isInstanceOfSatisfying(DispatchException.class, x -> assertThat(x.reason())
                .isEqualTo(DispatchException.Reason.TRANSITION_NOT_PERMITTED));
    }

    @Test
    void a_refusal_names_what_is_permitted_instead() {
        Exchange e = openAndSend("a draft that cannot be consumed");

        assertThatThrownBy(() -> exchanges.consume(SCOPE, address(e), CONSOLE))
            .isInstanceOfSatisfying(DispatchException.class, x -> assertThat(x.getMessage())
                .as("a refusal that only states the rule sends the reader back to the "
                    + "documentation; naming the permitted predecessors answers the "
                    + "question the reader actually has")
                .contains("returned"));
    }

    // -----------------------------------------------------------------------
    // Re-terminating
    // -----------------------------------------------------------------------

    @Test
    void closing_an_already_closed_exchange_succeeds_and_changes_nothing() {
        Exchange e = openAndSend("an exchange closed twice");
        exchanges.close(SCOPE, address(e), CONSOLE);

        Exchange again = exchanges.close(SCOPE, address(e), CONSOLE);

        assertThat(again.status())
            .as("re-terminating a terminal exchange is a successful no-op. Without it a "
                + "sequence across two services that is retried after a partial failure "
                + "can never complete: the retry finds what it already terminated")
            .isEqualTo(ExchangeStatus.CLOSED);
    }

    @Test
    void re_sending_is_NOT_a_no_op() {
        Exchange e = openAndSend("an exchange sent twice");

        assertThatThrownBy(() -> exchanges.send(SCOPE, address(e), CONSOLE))
            .as("only the TERMINATING verbs are idempotent. Send carries content and "
                + "takeup carries a holder; repeating either silently would hide a real "
                + "conflict rather than absorb a retry")
            .isInstanceOfSatisfying(DispatchException.class, x -> assertThat(x.reason())
                .isEqualTo(DispatchException.Reason.TRANSITION_NOT_PERMITTED));
    }

    // -----------------------------------------------------------------------
    // The bracket
    // -----------------------------------------------------------------------

    @Test
    void a_bracket_cannot_terminate_while_a_sibling_is_non_terminal() {
        Exchange bracket = openAndSend("the bracket");
        Exchange child = exchanges.addChild(SCOPE, "sprint", bracket.number,
            "a child still running", "code", LocalDate.now(), CONSOLE);
        exchanges.send(SCOPE, address(child), CONSOLE);

        assertThatThrownBy(() -> exchanges.close(SCOPE, address(bracket), CONSOLE))
            .isInstanceOfSatisfying(DispatchException.class, x -> {
                assertThat(x.reason())
                    .isEqualTo(DispatchException.Reason.SIBLINGS_NON_TERMINAL);
                assertThat(x.offenders())
                    .as("the blocking objects must be NAMED. A refusal that only states "
                        + "the rule sends the reader back to the store to work out which "
                        + "object it meant — and the whole reason for checking here rather "
                        + "than in a separate closure verb is that the answer is available "
                        + "at the moment the check runs")
                    .hasSize(1);
                assertThat(x.offenders().get(0)).contains(child.address());
            });

        // And once the child is terminal the bracket closes. Without this the
        // assertion above would hold against a bracket that never closes.
        exchanges.close(SCOPE, address(child), CONSOLE);
        assertThat(exchanges.close(SCOPE, address(bracket), CONSOLE).status())
            .as("with every sibling terminal the bracket closes, so the refusal above was "
                + "about the sibling and not about the bracket")
            .isEqualTo(ExchangeStatus.CLOSED);
    }

    // -----------------------------------------------------------------------
    // Numbering
    // -----------------------------------------------------------------------

    @Test
    void the_caller_never_supplies_a_number() {
        Set<String> acceptingANumber = Arrays.stream(ExchangeService.class.getMethods())
            .filter(m -> m.getName().equals("openBracket"))
            .filter(m -> Arrays.stream(m.getParameterTypes())
                .anyMatch(t -> t.equals(int.class) || t.equals(Integer.class)))
            .map(Method::getName)
            .collect(java.util.stream.Collectors.toSet());

        assertThat(acceptingANumber)
            .as("opening a bracket allocates its number; a signature that took one would "
                + "be a signature that could be given a number already in use, or one "
                + "that was never allocated")
            .isEmpty();
    }

    @Test
    void numbers_are_allocated_in_sequence_within_a_circle() {
        Exchange first = exchanges.openBracket(SCOPE, "sprint", "first", "code",
            LocalDate.now(), CONSOLE);
        Exchange second = exchanges.openBracket(SCOPE, "sprint", "second", "code",
            LocalDate.now(), CONSOLE);

        assertThat(second.number)
            .as("each bracket takes the next number in its circle")
            .isEqualTo(first.number + 1);
        assertThat(first.sub).isZero();
    }

    @Test
    void children_number_within_the_bracket_instance() {
        Exchange bracket = openAndSend("a bracket with children");
        Exchange one = exchanges.addChild(SCOPE, "sprint", bracket.number, "one", "code",
            LocalDate.now(), CONSOLE);
        Exchange two = exchanges.addChild(SCOPE, "sprint", bracket.number, "two", "code",
            LocalDate.now(), CONSOLE);

        assertThat(one.sub).isEqualTo(1);
        assertThat(two.sub)
            .as("the second level is a property of the bracket, not a declared circle of "
                + "its own — it counts within this bracket instance")
            .isEqualTo(2);
        assertThat(two.number).isEqualTo(bracket.number);
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private Exchange openAndSend(String title) {
        Exchange e = exchanges.openBracket(SCOPE, "sprint", title, "code",
            LocalDate.now(), CONSOLE);
        return exchanges.send(SCOPE, address(e), CONSOLE);
    }

    private static ExchangeAddress address(Exchange e) {
        return new ExchangeAddress(e.selector, e.number, e.sub, e.addendumSuffix);
    }
}
