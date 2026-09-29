package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.arc.Arc;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The draw: exactly one exchange, chosen by position, and never the same one
 * twice.
 *
 * <h2>Why the concurrency case cannot be a sequential test</h2>
 *
 * A draw that runs after another draw finished proves nothing about a draw
 * that runs DURING one. The defect this verb exists to rule out is two
 * executors holding what each believes is an exclusive lease on the same
 * commission, and that state is only reachable while two transactions overlap:
 * under READ COMMITTED the second reader does not see the first one's
 * uncommitted claim, passes the same status check, and awards a second claim
 * over the first.
 *
 * <p>So the probe runs two real draws on two real threads, released together
 * from a barrier. The assertion holds whatever the timing turns out to be —
 * either the second draw steps over a locked row and finds nothing, or it
 * arrives after the commit and finds the exchange claimed — and in both cases
 * exactly one caller comes away with the work. What is NOT admissible is both.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ClaimNextIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final Duration CLAIM = Duration.ofHours(1);
    static final String SELECTOR = "sprint";

    static final Actor ONE = new Actor("draw-executor-1", Actor.Kind.EXECUTOR);
    static final Actor TWO = new Actor("draw-executor-2", Actor.Kind.EXECUTOR);
    static final Actor CONSOLE = new Actor("draw-console", Actor.Kind.CONSOLE);

    @Inject ExchangeService exchanges;
    @Inject TenantContext tenantContext;

    private UUID tenant;
    private AutoCloseable binding;

    @BeforeEach
    void freshTenant() {
        tenant = UUID.randomUUID();
        DomainFixture.declareSelector(tenant, SCOPE, SELECTOR);
        binding = tenantContext.bind(tenant);
    }

    @AfterEach
    void unbind() throws Exception {
        binding.close();
    }

    // =======================================================================
    // Probe B — exactly one, under real concurrency
    // =======================================================================

    /**
     * One claimable exchange, two simultaneous draws: one winner, one typed
     * refusal, and the winner's exchange is the one that moved.
     */
    @Test
    void two_simultaneous_draws_on_one_exchange_produce_one_winner() throws Exception {
        String address = openAndSend("the only one").address();

        List<Outcome> outcomes = drawConcurrently();

        assertThat(outcomes).filteredOn(Outcome::won)
            .as("exactly one draw may come away with the work. Two winners is two "
                + "executors each believing they hold an exclusive lease on the same "
                + "commission, which is the one state this verb exists to rule out")
            .hasSize(1);

        assertThat(outcomes).filteredOn(o -> !o.won())
            .as("and the loser is told why, in a form it can act on: the selector is "
                + "there and nothing in it is free, which is a wait — not a 'no such "
                + "address', which would be a retry with a different spelling")
            .singleElement()
            .satisfies(lost -> assertThat(lost.reason())
                .isEqualTo(DispatchException.Reason.NOTHING_TO_CLAIM));

        Outcome winner = outcomes.stream().filter(Outcome::won).findFirst().orElseThrow();
        assertThat(winner.address())
            .as("and what the winner holds is the exchange that was there")
            .isEqualTo(address);
    }

    /**
     * The other half, and it is not the same assertion: with two claimable
     * exchanges both draws succeed, and they must come away with DIFFERENT
     * ones.
     *
     * <p>Without this, a verb that serialised every draw and handed the second
     * caller a refusal would pass the case above perfectly while being useless
     * — the point of skipping a locked row rather than waiting for it is that
     * two executors can work at once.
     */
    @Test
    void two_simultaneous_draws_on_two_exchanges_take_one_each() throws Exception {
        openAndSend("the first");
        openAndSend("the second");

        List<Outcome> outcomes = drawConcurrently();

        assertThat(outcomes).allMatch(Outcome::won)
            .as("with two claimable exchanges neither draw has to wait: a locked row is "
                + "stepped over, not queued behind");
        assertThat(outcomes.stream().map(Outcome::address).distinct().toList())
            .as("and the two draws must not have taken the same exchange — that is the "
                + "failure the lock exists to prevent, and it looks like success from "
                + "inside either caller")
            .hasSize(2);
    }

    // =======================================================================
    // Probe C — the selection order
    // =======================================================================

    /**
     * The next one by position, with the terminal and the held ones skipped.
     *
     * <p>Four exchanges are staged in address order: the first is terminated,
     * the second is claimed and effectively held, the third is claimable, the
     * fourth is claimable and further along. The draw must return the third —
     * not the first (terminal), not the second (held), and not the fourth
     * (which is claimable but not next).
     */
    @Test
    void the_draw_takes_the_next_claimable_one_by_position() {
        Exchange first = openAndSend("terminated");
        Exchange second = openAndSend("held by somebody else");
        Exchange third = openAndSend("the next one");
        openAndSend("further along");

        exchanges.reject(SCOPE, addressOf(first), CONSOLE);
        exchanges.takeup(SCOPE, addressOf(second), TWO, CLAIM);

        ExchangeService.ClaimResult drawn = exchanges.claimNext(SCOPE, SELECTOR, ONE, drawFor("code"));

        assertThat(drawn.exchange().address())
            .as("the draw follows the order of the address space — number then sub — and "
                + "skips what it cannot take. A terminal exchange is done and a held one "
                + "is somebody's work in progress; handing either out is handing out work "
                + "twice or work that is over")
            .isEqualTo(third.address());
    }

    /**
     * The red half of the order: a terminal exchange must not be drawable even
     * when it is the only thing there.
     *
     * <p>Separate from the case above because that one would still pass if
     * terminal exchanges were merely sorted last. Here there is nothing else
     * to sort them behind.
     */
    @Test
    void a_terminal_exchange_is_not_drawn_even_when_it_is_the_only_one() {
        Exchange only = openAndSend("finished");
        exchanges.reject(SCOPE, addressOf(only), CONSOLE);

        assertThatThrownBy(() -> exchanges.claimNext(SCOPE, SELECTOR, ONE, drawFor("code")))
            .as("a terminated exchange is not work waiting to be done, and a draw that "
                + "returned one would reopen something that was closed")
            .isInstanceOf(DispatchException.class)
            .extracting(e -> ((DispatchException) e).reason())
            .isEqualTo(DispatchException.Reason.NOTHING_TO_CLAIM);
    }

    /**
     * A draft is not drawable either: it was never sent, so nobody has
     * committed to it.
     */
    @Test
    void an_unsent_draft_is_not_drawn() {
        exchanges.openBracket(SCOPE, SELECTOR, "still being written", "code",
            LocalDate.now(), CONSOLE);

        assertThatThrownBy(() -> exchanges.claimNext(SCOPE, SELECTOR, ONE, drawFor("code")))
            .as("send is what opens an exchange to an executor; before it the author is "
                + "still writing and the text is not committed to")
            .isInstanceOf(DispatchException.class)
            .extracting(e -> ((DispatchException) e).reason())
            .isEqualTo(DispatchException.Reason.NOTHING_TO_CLAIM);
    }

    /**
     * A lapsed claim is drawable again, and by the same rule the named claim
     * uses rather than a second one written for the drawn case.
     */
    @Test
    void an_exchange_whose_claim_has_lapsed_is_drawable_again() {
        Exchange only = openAndSend("claimed and forgotten");
        exchanges.takeup(SCOPE, addressOf(only), TWO, CLAIM);

        // The lease is moved into the past rather than waited out. Nothing
        // writes on expiry by design, so the row goes on naming the old holder
        // while the claim stops being effective — which is exactly the state
        // this case is about, and a wall-clock wait would reach it more slowly
        // and less certainly.
        lapseTheClaim(only);

        ExchangeService.ClaimResult drawn = exchanges.claimNext(SCOPE, SELECTOR, ONE, drawFor("code"));

        assertThat(drawn.exchange().address())
            .as("a lapsed claim frees the exchange, and the draw reclaims it in the "
                + "claimant's own transaction exactly as a named takeup does")
            .isEqualTo(only.address());
        assertThat(drawn.receipt())
            .as("with a receipt of its own: the previous holder's proof is not reissued")
            .isNotBlank();
    }

    /** An empty selector is a typed refusal and never a not-found. */
    @Test
    void an_empty_selector_refuses_with_nothing_to_claim() {
        assertThatThrownBy(() -> exchanges.claimNext(SCOPE, SELECTOR, ONE, drawFor("code")))
            .isInstanceOf(DispatchException.class)
            .extracting(e -> ((DispatchException) e).reason())
            .isEqualTo(DispatchException.Reason.NOTHING_TO_CLAIM);
    }

    /** The duration contract is the claim's own and is not relaxed for the draw. */
    @Test
    void a_non_positive_duration_is_refused_before_anything_is_drawn() {
        openAndSend("untouched");

        assertThatThrownBy(() -> exchanges.claimNext(SCOPE, SELECTOR, ONE,
            new ExchangeService.ClaimTerms(Duration.ZERO, List.of("code"))))
            .as("a zero duration would award a claim that has already lapsed, and every "
                + "later reader would have to decide what that meant")
            .isInstanceOf(DispatchException.class)
            .extracting(e -> ((DispatchException) e).reason())
            .isEqualTo(DispatchException.Reason.CLAIM_DURATION_NOT_POSITIVE);
    }

    // =======================================================================
    // Probe D -- the draw is narrowed to an apparatus pattern
    //
    // Every case here fails against a draw that ignores the patterns: the
    // filter is the only thing that makes the drawn exchange the one asserted,
    // because in each staging the exchange at the smaller address is the one a
    // blind draw would take.
    // =======================================================================

    /**
     * An exchange addressed to another apparatus is passed over, and is left
     * exactly as it was.
     *
     * <p>The two halves are one case and neither alone is the statement. That
     * the draw returns {@code agent-code} would also hold if the filter merely
     * SORTED the other one later; that the {@code code} exchange is still open
     * and unheld is what says it was never locked, never claimed and never
     * quietly reopened.
     */
    @Test
    void an_exchange_of_another_apparatus_is_passed_over_and_left_untouched() {
        Exchange manual = openAndSend("addressed to a person", "code");
        Exchange forAgent = openAndSend("addressed to an agent", "agent-code");

        ExchangeService.ClaimResult drawn =
            exchanges.claimNext(SCOPE, SELECTOR, ONE, drawFor("agent-*"));

        assertThat(drawn.exchange().address())
            .as("the draw is for 'agent-*', and the exchange at the smaller address is "
                + "addressed to 'code'. A draw that took it would hand an agent "
                + "controller every commission a person was meant to answer, which is "
                + "the defect this argument exists to close")
            .isEqualTo(forAgent.address());

        Exchange untouched = exchanges.read(SCOPE, addressOf(manual));
        assertThat(untouched.status())
            .as("and the one that was passed over is still waiting for its own executor")
            .isEqualTo(ExchangeStatus.OPEN);
        assertThat(untouched.effectiveHolder(Instant.now()))
            .as("with nobody holding it: a passed-over exchange is not a claimed one, "
                + "and a draw that locked it and gave it back would still have written "
                + "a holder somebody has to wait out")
            .isNull();
    }

    /** A wildcard at the front matches a suffix, and only that suffix. */
    @Test
    void a_leading_wildcard_matches_the_end_of_the_apparatus() {
        Exchange review = openAndSend("for review", "agent-review");
        Exchange code = openAndSend("for code", "agent-code");

        ExchangeService.ClaimResult drawn =
            exchanges.claimNext(SCOPE, SELECTOR, ONE, drawFor("*-code"));

        assertThat(drawn.exchange().address())
            .as("'*-code' ends in '-code', and 'agent-review' does not — even though it "
                + "sits at the smaller address and a blind draw would take it")
            .isEqualTo(code.address());
        assertThat(exchanges.read(SCOPE, addressOf(review)).status())
            .isEqualTo(ExchangeStatus.OPEN);
    }

    /**
     * A wildcard on both sides matches an infix, and an infix pattern does not
     * match a value that merely ends with the text.
     */
    @Test
    void a_wildcard_on_both_sides_matches_an_infix_and_not_a_suffix() {
        Exchange endsWithCode = openAndSend("ends with code", "agent-code");
        Exchange codeInside = openAndSend("code in the middle", "x-code-y");

        ExchangeService.ClaimResult drawn =
            exchanges.claimNext(SCOPE, SELECTOR, ONE, drawFor("*-code-*"));

        assertThat(drawn.exchange().address())
            .as("'*-code-*' needs something after '-code-', and 'agent-code' has "
                + "nothing there. Matching it would mean the trailing wildcard was "
                + "being read as optional, which is a different pattern language")
            .isEqualTo(codeInside.address());
        assertThat(exchanges.read(SCOPE, addressOf(endsWithCode)).status())
            .isEqualTo(ExchangeStatus.OPEN);
    }

    /**
     * A pattern with no wildcard is an exact match and nothing else.
     *
     * <p>The case that rules out the reading a caller would most easily assume:
     * that a pattern matches any apparatus CONTAINING it. Under that reading
     * {@code code} would draw {@code agent-code}, and every controller naming
     * its own apparatus exactly would still be drawing everybody's work.
     */
    @Test
    void an_exact_pattern_does_not_match_a_longer_apparatus() {
        Exchange forAgent = openAndSend("addressed to an agent", "agent-code");

        assertThatThrownBy(() -> exchanges.claimNext(SCOPE, SELECTOR, ONE, drawFor("code")))
            .as("'code' is not 'agent-code'. A pattern without a wildcard names one "
                + "apparatus, and a draw that matched substrings would make every short "
                + "apparatus name a filter for every longer one")
            .isInstanceOf(DispatchException.class)
            .extracting(e -> ((DispatchException) e).reason())
            .isEqualTo(DispatchException.Reason.NOTHING_TO_CLAIM);

        assertThat(exchanges.read(SCOPE, addressOf(forAgent)).status())
            .as("and the refused draw changed nothing")
            .isEqualTo(ExchangeStatus.OPEN);
    }

    /**
     * Several patterns are alternatives, and the address order decides between
     * them — not the order the patterns were written in.
     *
     * <p>The patterns are given in the reverse of the address order on purpose.
     * A draw that took the first matching PATTERN rather than the first
     * matching EXCHANGE would pass a probe whose patterns happened to be
     * written in address order, and would silently reorder the queue for every
     * controller that serves two apparatus values.
     */
    @Test
    void several_patterns_are_alternatives_and_the_address_order_decides() {
        Exchange first = openAndSend("for code, and first", "agent-code");
        openAndSend("for review, and second", "agent-review");

        ExchangeService.ClaimResult drawn = exchanges.claimNext(SCOPE, SELECTOR, ONE,
            new ExchangeService.ClaimTerms(CLAIM, List.of("agent-review", "agent-code")));

        assertThat(drawn.exchange().address())
            .as("the draw follows the address space, as it does without a filter. The "
                + "filter decides WHICH exchanges are candidates and never their order")
            .isEqualTo(first.address());
    }

    /**
     * A pattern nothing matches is an empty draw, and nothing moves.
     *
     * <p>The same refusal an empty selector gives, and deliberately not a new
     * one: from the caller's side the two are the same situation — the selector
     * is there and there is nothing in it for this caller — and a second reason
     * would be a distinction the caller has no different remedy for.
     */
    @Test
    void a_pattern_that_matches_nothing_draws_nothing_and_moves_nothing() {
        Exchange only = openAndSend("addressed to a person", "code");

        assertThatThrownBy(() ->
            exchanges.claimNext(SCOPE, SELECTOR, ONE, drawFor("agent-*")))
            .isInstanceOf(DispatchException.class)
            .extracting(e -> ((DispatchException) e).reason())
            .isEqualTo(DispatchException.Reason.NOTHING_TO_CLAIM);

        Exchange untouched = exchanges.read(SCOPE, addressOf(only));
        assertThat(untouched.status())
            .as("an empty draw is a wait, not a write. The exchange that did not match "
                + "must be in exactly the state the next matching draw will find")
            .isEqualTo(ExchangeStatus.OPEN);
        assertThat(untouched.effectiveHolder(Instant.now())).isNull();
    }

    /**
     * The draw is case-sensitive.
     *
     * <p>An apparatus value is an identifier the scope's operator gave out, so
     * {@code Agent-Code} and {@code agent-code} are two values and not one
     * spelling of one. A case-insensitive comparison would merge them, and the
     * merge would only ever be noticed by the controller that drew somebody
     * else's work.
     */
    @Test
    void the_comparison_is_case_sensitive() {
        Exchange capitalised = openAndSend("addressed in another spelling", "Agent-Code");

        assertThatThrownBy(() ->
            exchanges.claimNext(SCOPE, SELECTOR, ONE, drawFor("agent-*")))
            .isInstanceOf(DispatchException.class)
            .extracting(e -> ((DispatchException) e).reason())
            .isEqualTo(DispatchException.Reason.NOTHING_TO_CLAIM);

        assertThat(exchanges.read(SCOPE, addressOf(capitalised)).status())
            .isEqualTo(ExchangeStatus.OPEN);
    }

    // =======================================================================
    // The concurrency harness
    // =======================================================================

    /**
     * Two draws, released together, each on its own thread and therefore in
     * its own transaction.
     *
     * <p>The tenant is bound inside each thread: the binding is thread-local
     * because the tenant is a property of a request, and a probe that leaned
     * on the test thread's binding would be measuring a code path no request
     * takes.
     */
    private List<Outcome> drawConcurrently() throws Exception {
        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Actor actor : List.of(ONE, TWO)) {
                futures.add(threads.submit(draw(actor, together)));
            }

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            threads.shutdownNow();
        }
    }

    /**
     * One draw on its own thread.
     *
     * <p>The request context is activated by hand. A thread this test starts
     * is not one the framework manages, and the entity manager is reached
     * through a context-bound proxy — without an active request context the
     * session is opened with no tenant and Hibernate refuses it before the
     * probe reaches anything worth measuring. The tenant binding is
     * thread-local for the same reason and is taken inside the thread too.
     */
    private Callable<Outcome> draw(Actor actor, CyclicBarrier together) {
        return () -> {
            var request = Arc.container().requestContext();
            request.activate();
            try (AutoCloseable ignored = tenantContext.bind(tenant)) {
                together.await(30, TimeUnit.SECONDS);
                ExchangeService.ClaimResult drawn =
                    exchanges.claimNext(SCOPE, SELECTOR, actor, drawFor("code"));
                return new Outcome(true, drawn.exchange().address(), null);
            } catch (DispatchException e) {
                return new Outcome(false, null, e.reason());
            } finally {
                request.terminate();
            }
        };
    }

    /** What one draw came away with: the work, or the reason it did not. */
    private record Outcome(boolean won, String address, DispatchException.Reason reason) {
    }

    // =======================================================================

    private Exchange openAndSend(String title) {
        return openAndSend(title, "code");
    }

    private Exchange openAndSend(String title, String apparatus) {
        Exchange opened = exchanges.openBracket(SCOPE, SELECTOR, title, apparatus,
            LocalDate.now(), CONSOLE);
        exchanges.send(SCOPE, addressOf(opened), CONSOLE);
        return opened;
    }

    /** A draw of the standard lease length, for one apparatus pattern. */
    private static ExchangeService.ClaimTerms drawFor(String pattern) {
        return new ExchangeService.ClaimTerms(CLAIM, List.of(pattern));
    }

    private static ExchangeAddress addressOf(Exchange e) {
        return new ExchangeAddress(e.selectorName(), e.number, e.sub, e.addendumSuffix);
    }

    /**
     * Moves one exchange's lease into the past.
     *
     * <p>Staged as the container superuser, which also side-steps the tenancy
     * policy — the row belongs to a tenant this test invented moments ago, and
     * binding it only to age one column would be ceremony. The service reads
     * {@code Clock.systemUTC()} and takes no injectable clock, so moving the
     * stored expiry is the way to reach a lapsed claim without waiting for one.
     */
    private void lapseTheClaim(Exchange e) {
        ai.kumbuka.dispatch.platform.PlatformFixture.run(
            "UPDATE dispatch.exchange SET claim_expires_at = now() - interval '1 hour' "
                + "WHERE tenant_id = '" + tenant + "' AND id = '" + e.id + "'");
    }
}
