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

        ExchangeService.ClaimResult drawn = exchanges.claimNext(SCOPE, SELECTOR, ONE, CLAIM);

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

        assertThatThrownBy(() -> exchanges.claimNext(SCOPE, SELECTOR, ONE, CLAIM))
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

        assertThatThrownBy(() -> exchanges.claimNext(SCOPE, SELECTOR, ONE, CLAIM))
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

        ExchangeService.ClaimResult drawn = exchanges.claimNext(SCOPE, SELECTOR, ONE, CLAIM);

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
        assertThatThrownBy(() -> exchanges.claimNext(SCOPE, SELECTOR, ONE, CLAIM))
            .isInstanceOf(DispatchException.class)
            .extracting(e -> ((DispatchException) e).reason())
            .isEqualTo(DispatchException.Reason.NOTHING_TO_CLAIM);
    }

    /** The duration contract is the claim's own and is not relaxed for the draw. */
    @Test
    void a_non_positive_duration_is_refused_before_anything_is_drawn() {
        openAndSend("untouched");

        assertThatThrownBy(() -> exchanges.claimNext(SCOPE, SELECTOR, ONE, Duration.ZERO))
            .as("a zero duration would award a claim that has already lapsed, and every "
                + "later reader would have to decide what that meant")
            .isInstanceOf(DispatchException.class)
            .extracting(e -> ((DispatchException) e).reason())
            .isEqualTo(DispatchException.Reason.CLAIM_DURATION_NOT_POSITIVE);
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
                    exchanges.claimNext(SCOPE, SELECTOR, actor, CLAIM);
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
        Exchange opened = exchanges.openBracket(SCOPE, SELECTOR, title, "code",
            LocalDate.now(), CONSOLE);
        exchanges.send(SCOPE, addressOf(opened), CONSOLE);
        return opened;
    }

    private static ExchangeAddress addressOf(Exchange e) {
        return new ExchangeAddress(e.selector, e.number, e.sub, e.addendumSuffix);
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
