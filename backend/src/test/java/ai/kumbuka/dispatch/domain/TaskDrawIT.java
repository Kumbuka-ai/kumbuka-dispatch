package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.arc.Arc;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static ai.kumbuka.dispatch.domain.TaskStage.H;
import static ai.kumbuka.dispatch.domain.TaskStage.K;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code claim_next}: the next drawable task by position, and never the same
 * one twice, under real concurrency (concept section 2.4).
 *
 * <p>Two draws on two threads, released together from a barrier, as {@code
 * ClaimNextIT} does it for the exchange. With one drawable task exactly one
 * draw wins; with two, both win and take different ones.
 *
 * <p>Neither of those is what {@code SKIP LOCKED} carries. Measured
 * 2026-10-06 by removing it: both stay green, because a draw that waited on
 * the locked row re-checks it, finds it taken and reads on to the next. What
 * the clause carries is that a draw does not wait at all, and the case that
 * shows it holds a row locked from another transaction and asks the database
 * whether the draw waits on it ({@link LockWaits}).
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskDrawIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final String SELECTOR = "sprint";

    @Inject TaskService tasks;
    @Inject TenantContext tenantContext;

    private UUID tenant;
    private AutoCloseable binding;
    private TaskStage stage;

    @BeforeEach
    void freshTenant() {
        tenant = UUID.randomUUID();
        DomainFixture.declareSelector(tenant, SCOPE, SELECTOR);
        binding = tenantContext.bind(tenant);
        stage = new TaskStage(tasks, SCOPE);
    }

    @AfterEach
    void unbind() throws Exception {
        binding.close();
    }

    @Test
    void two_simultaneous_draws_on_one_task_produce_one_winner() throws Exception {
        TaskStage.Staged only = stage.open(SELECTOR);

        List<Drawn> outcomes = drawConcurrently();

        assertThat(outcomes).filteredOn(Drawn::won).as("exactly one winner").hasSize(1);
        assertThat(outcomes).filteredOn(d -> !d.won()).singleElement()
            .satisfies(lost -> assertThat(lost.reason())
                .isEqualTo(DispatchException.Reason.NOTHING_TO_CLAIM));
        assertThat(outcomes).filteredOn(Drawn::won).singleElement()
            .satisfies(won -> assertThat(won.address()).isEqualTo(only.address()));
    }

    @Test
    void two_simultaneous_draws_on_two_tasks_take_one_each() throws Exception {
        stage.open(SELECTOR);
        stage.open(SELECTOR);

        List<Drawn> outcomes = drawConcurrently();

        assertThat(outcomes).as("a locked row is stepped over, not waited on")
            .allMatch(Drawn::won);
        assertThat(outcomes.stream().map(Drawn::address).distinct().toList())
            .as("and the two draws took different tasks").hasSize(2);
    }

    @Test
    void a_draw_steps_over_a_row_another_transaction_holds_instead_of_waiting()
            throws Exception {
        TaskStage.Staged first = stage.open(SELECTOR);
        TaskStage.Staged second = stage.open(SELECTOR);

        var config = ConfigProvider.getConfig();
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try (Connection holder = DriverManager.getConnection(
                config.getValue("test.db.url", String.class),
                config.getValue("test.db.admin.username", String.class),
                config.getValue("test.db.admin.password", String.class))) {
            holder.setAutoCommit(false);
            try (Statement s = holder.createStatement()) {
                s.execute("SELECT id FROM dispatch.task WHERE uuid = '" + first.identity()
                    + "' FOR UPDATE");
            }
            int holding = LockWaits.sessionOf(holder);
            Future<Drawn> drawn = thread.submit(draw(K, new CyclicBarrier(1)));
            try {
                assertThat(LockWaits.waitsOn(holding, drawn))
                    .as("the draw does not wait for the transaction that holds the row")
                    .isFalse();
                assertThat(drawn.get().address())
                    .as("it steps over the held row and takes the next one")
                    .isEqualTo(second.address());
            } finally {
                holder.rollback();
            }
        } finally {
            thread.shutdownNow();
        }
    }

    @Test
    void the_draw_takes_the_next_drawable_by_position_and_counts_a_lapse_it_takes_over() {
        stage.closed(SELECTOR);
        stage.active(SELECTOR);
        stage.deferred(SELECTOR);
        stage.parked(SELECTOR);
        stage.draft(SELECTOR);
        TaskStage.Staged lapsed = stage.lapse(stage.active(SELECTOR));
        TaskStage.Staged later = stage.open(SELECTOR);

        TaskClaim first = tasks.claimNext(SCOPE, SELECTOR, List.of("code"), TaskCall.by(K));
        assertThat(first.task().address())
            .as("closed, held, deferred, parked and draft are passed over")
            .isEqualTo(lapsed.address());
        assertThat(TaskStage.row(lapsed.identity()).lapseCount()).isEqualTo(1);

        TaskClaim second = tasks.claimNext(SCOPE, SELECTOR, List.of("co*"), TaskCall.by(K));
        assertThat(second.task().address()).isEqualTo(later.address());
    }

    @Test
    void a_task_of_another_apparatus_is_not_drawn() {
        TaskView review = tasks.create(SCOPE, SELECTOR, null,
            new TaskService.Draft("for review", "review", null, null), TaskStage.C,
            IdempotencyKey.NONE);
        tasks.act(SCOPE, review.address(), TaskVerb.SEND,
            TaskCall.by(TaskStage.C).withConflictToken(review.conflictToken()));
        TaskStage.Staged code = stage.open(SELECTOR);
        TaskClaim drawn = tasks.claimNext(SCOPE, SELECTOR, List.of("code"), TaskCall.by(K));
        assertThat(drawn.task().address()).isEqualTo(code.address());
    }

    // -----------------------------------------------------------------------
    // The patterns, in a real draw
    // -----------------------------------------------------------------------

    @Test
    void a_leading_star_matches_the_end_of_the_apparatus() {
        TaskStage.Staged review = openFor("agent-review");
        TaskStage.Staged code = openFor("agent-code");

        TaskClaim drawn = tasks.claimNext(SCOPE, SELECTOR, List.of("*-code"), TaskCall.by(K));

        assertThat(drawn.task().address())
            .as("'*-code' ends in '-code' and 'agent-review' does not, though it comes first")
            .isEqualTo(code.address());
        assertThat(TaskStage.row(review.identity()).state()).isEqualTo("open");
    }

    @Test
    void stars_on_both_sides_match_a_middle_and_not_an_end() {
        TaskStage.Staged endsWithCode = openFor("agent-code");
        TaskStage.Staged codeInside = openFor("x-code-y");

        TaskClaim drawn = tasks.claimNext(SCOPE, SELECTOR, List.of("*-code-*"), TaskCall.by(K));

        assertThat(drawn.task().address())
            .as("'*-code-*' needs '-code-' inside, and 'agent-code' only ends in '-code'")
            .isEqualTo(codeInside.address());
        assertThat(TaskStage.row(endsWithCode.identity()).state()).isEqualTo("open");
    }

    @Test
    void a_pattern_without_a_star_does_not_match_a_longer_apparatus() {
        TaskStage.Staged forAgent = openFor("agent-code");

        TaskHoldingIT.assertRefused(() -> tasks.claimNext(SCOPE, SELECTOR, List.of("code"),
                TaskCall.by(K)),
            DispatchException.Reason.NOTHING_TO_CLAIM);
        assertThat(TaskStage.row(forAgent.identity()).state())
            .as("'code' names one apparatus and is not a part of 'agent-code'").isEqualTo("open");
    }

    @Test
    void the_comparison_tells_upper_from_lower_case() {
        TaskStage.Staged capitalised = openFor("Agent-Code");

        TaskHoldingIT.assertRefused(() -> tasks.claimNext(SCOPE, SELECTOR,
                List.of("agent-*"), TaskCall.by(K)),
            DispatchException.Reason.NOTHING_TO_CLAIM);
        assertThat(TaskStage.row(capitalised.identity()).state())
            .as("'Agent-Code' and 'agent-code' are two apparatus values").isEqualTo("open");
    }

    // -----------------------------------------------------------------------

    /** A task addressed to {@code apparatus}, sent. */
    private TaskStage.Staged openFor(String apparatus) {
        TaskView v = tasks.create(SCOPE, SELECTOR, null,
            new TaskService.Draft("for " + apparatus, apparatus, null, null), TaskStage.C,
            IdempotencyKey.NONE);
        tasks.act(SCOPE, v.address(), TaskVerb.SEND,
            TaskCall.by(TaskStage.C).withConflictToken(v.conflictToken()));
        return new TaskStage.Staged(v.address(), v.identity(), SELECTOR, null);
    }

    private List<Drawn> drawConcurrently() throws Exception {
        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            List<Future<Drawn>> futures = new ArrayList<>();
            for (Actor executor : List.of(H, K)) {
                futures.add(threads.submit(draw(executor, together)));
            }
            List<Drawn> outcomes = new ArrayList<>();
            for (Future<Drawn> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            threads.shutdownNow();
        }
    }

    /** One draw on its own thread, with a request context and tenant binding of its own. */
    private Callable<Drawn> draw(Actor executor, CyclicBarrier together) {
        return () -> {
            var request = Arc.container().requestContext();
            request.activate();
            try (AutoCloseable ignored = tenantContext.bind(tenant)) {
                together.await(30, TimeUnit.SECONDS);
                TaskClaim drawn = tasks.claimNext(SCOPE, SELECTOR, List.of("code"),
                    TaskCall.by(executor));
                return new Drawn(true, drawn.task().address(), null);
            } catch (DispatchException e) {
                return new Drawn(false, null, e.reason());
            } finally {
                request.terminate();
            }
        };
    }

    private record Drawn(boolean won, ExchangeAddress address, DispatchException.Reason reason) {
    }
}
