package ai.kumbuka.dispatch.surface;

import org.eclipse.microprofile.config.ConfigProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A fingerprint of everything a call of the verb surface can write, read as
 * the administrator across every tenant: the rows of {@code task}, {@code
 * task_text} and {@code task_idempotency_key}, the latest change of a task, and
 * the bracket counters. Two equal fingerprints around a call say it wrote
 * nothing.
 */
public final class Writes {

    private Writes() {
    }

    public static String snapshot() {
        var config = ConfigProvider.getConfig();
        try (Connection c = DriverManager.getConnection(
                config.getValue("test.db.url", String.class),
                config.getValue("test.db.admin.username", String.class),
                config.getValue("test.db.admin.password", String.class));
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT "
                 + "(SELECT count(*) FROM dispatch.task), "
                 + "(SELECT coalesce(max(updated_at)::text, '-') FROM dispatch.task), "
                 + "(SELECT count(*) FROM dispatch.task_text), "
                 + "(SELECT count(*) FROM dispatch.task_idempotency_key), "
                 + "(SELECT coalesce(sum(next_number), 0) FROM dispatch.selector)")) {
            rs.next();
            return "tasks=" + rs.getLong(1) + " changed=" + rs.getString(2)
                + " texts=" + rs.getLong(3) + " keys=" + rs.getLong(4)
                + " numbers=" + rs.getLong(5);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
