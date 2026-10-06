package ai.kumbuka.dispatch.domain;

import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.ConfigProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Whether one transaction waits on a lock another holds, as the database
 * itself reports it.
 *
 * <p>A race test that gives the second transaction a fixed time to reach its
 * lock decides nothing: on a slow machine the second may not have got there
 * yet, and the test is green without the lock ever having been met. This asks
 * {@code pg_stat_activity} instead, through {@code pg_blocking_pids}, which
 * names the sessions that block a waiting one. The poll interval only sets
 * how often it asks; the answer comes from the database, not from the clock.
 */
final class LockWaits {

    /** How often the database is asked while the second transaction runs. */
    private static final long POLL_MILLIS = 10;

    /** A guard against a hung run, never the measure of a wait. */
    private static final long GIVE_UP_SECONDS = 60;

    private LockWaits() {
    }

    /** The database session of the connection the caller's transaction runs on. */
    static int sessionOf(EntityManager em) {
        return ((Number) em.createNativeQuery("SELECT pg_backend_pid()").getSingleResult())
            .intValue();
    }

    /** The database session of a connection the test holds itself. */
    static int sessionOf(Connection connection) throws SQLException {
        try (var s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * Watches {@code second} until it waits on a lock the session {@code
     * holder} holds, or ends.
     *
     * <p>Sound only while {@code holder} keeps its locks: a wait that started
     * then lasts until the holder ends, so no poll can miss it.
     *
     * @return true when {@code second} was seen waiting on {@code holder},
     *     false when it ended without having been
     */
    static boolean waitsOn(int holder, Future<?> second) throws Exception {
        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(GIVE_UP_SECONDS);
        var config = ConfigProvider.getConfig();
        try (Connection c = DriverManager.getConnection(
                config.getValue("test.db.url", String.class),
                config.getValue("test.db.admin.username", String.class),
                config.getValue("test.db.admin.password", String.class));
             PreparedStatement blocked = c.prepareStatement(
                 "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))")) {
            blocked.setInt(1, holder);
            while (System.nanoTime() < giveUp) {
                try (ResultSet rs = blocked.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        return true;
                    }
                }
                if (second.isDone()) {
                    return false;
                }
                Thread.sleep(POLL_MILLIS);
            }
        }
        throw new AssertionError("the second transaction neither waited on session " + holder
            + " nor ended within " + GIVE_UP_SECONDS + " s");
    }
}
