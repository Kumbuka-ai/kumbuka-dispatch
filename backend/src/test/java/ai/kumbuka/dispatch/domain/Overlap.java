package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.persistence.EntityManager;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Two calls in two transactions that overlap, the first holding its locks
 * until the database reports the second waiting on it ({@link LockWaits}).
 *
 * <p>The first runs in the test thread inside a transaction this class opens
 * and commits; the second runs on a thread of its own, with a request context
 * and the tenant binding of its own. No fixed time decides the overlap.
 *
 * @param first what the first call answered
 * @param second what the second answered, or the exception it raised -- a
 *     refusal or a failure the kernel did not turn into one
 * @param waited whether the second was seen waiting on the first
 */
record Overlap(Object first, Object second, boolean waited) {

    /** The test's own wiring: where the first runs, and which tenant the second binds. */
    record Stage(EntityManager em, TenantContext tenantContext, UUID tenant) {

        /**
         * Runs {@code first} in a transaction held open until {@code second}
         * is seen waiting on it or has ended; then commits.
         */
        Overlap run(Callable<?> first, Callable<?> second) throws Exception {
            ExecutorService thread = Executors.newSingleThreadExecutor();
            try {
                QuarkusTransaction.begin();
                Object answered;
                Future<Object> running;
                boolean waited;
                try {
                    answered = first.call();
                    int holder = LockWaits.sessionOf(em);
                    running = thread.submit(onItsOwn(second));
                    waited = LockWaits.waitsOn(holder, running);
                } finally {
                    QuarkusTransaction.commit();
                }
                return new Overlap(answered, running.get(30, TimeUnit.SECONDS), waited);
            } finally {
                thread.shutdownNow();
            }
        }

        private Callable<Object> onItsOwn(Callable<?> call) {
            return () -> {
                var request = Arc.container().requestContext();
                request.activate();
                try (AutoCloseable ignored = tenantContext.bind(tenant)) {
                    return call.call();
                } catch (RuntimeException raised) {
                    return raised;
                } finally {
                    request.terminate();
                }
            };
        }
    }
}
