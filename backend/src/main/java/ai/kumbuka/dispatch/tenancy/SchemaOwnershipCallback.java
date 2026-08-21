package ai.kumbuka.dispatch.tenancy;

import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Hands every object in this service's schema to the service's own role,
 * after every migration run.
 *
 * <p><strong>Why this exists at all.</strong> The migrator is a privileged
 * role, because creating a role is a privileged act. Everything a privileged
 * migrator creates is owned by the migrator. An object owned by a superuser
 * is an object for which {@code FORCE ROW LEVEL SECURITY} means nothing when
 * that superuser reads it — and, in the other direction, an object the
 * runtime role neither owns nor was granted is one it cannot read at all.
 * Ownership is therefore not tidiness here; it is the thing that decides
 * whether the policy binds and whether the service can work.
 *
 * <p><strong>Why a callback and not a migration.</strong> A migration can
 * only capture the objects that exist when it runs, so every future migration
 * would carry a step someone has to remember. This runs after every migration
 * run and captures whatever is there, including objects added years later by
 * someone who never read this file. The predecessor of this codebase learned
 * that distinction from two tables that were created after a one-off sweep
 * and stayed unreachable until a feature finally touched them.
 *
 * <p>Idempotent, and quiet when there is nothing to do: an object whose owner
 * already matches is skipped, so a deployment that migrates as the service
 * role itself never issues a statement.
 *
 * <h2>How this callback reaches Flyway</h2>
 *
 * Through {@code quarkus.flyway.callbacks} in application.properties, and
 * through nothing else. The Quarkus Flyway extension resolves callbacks from
 * that configuration key by class name and instantiates them REFLECTIVELY,
 * with the no-argument constructor. It does not discover them as CDI beans.
 *
 * <p>That distinction is worth a paragraph because getting it wrong is
 * silent: a callback written as an {@code @ApplicationScoped} bean and left
 * out of the configuration is simply never registered, the migrations run
 * without it, and nothing anywhere reports a callback that did not fire. So
 * this class is a plain class, it holds no injection point, and the probe
 * that observes it firing is the only thing standing between the code and
 * that silence.
 */
public class SchemaOwnershipCallback extends BaseCallback {

    private static final Logger LOG = Logger.getLogger(SchemaOwnershipCallback.class);

    /**
     * Relation kinds that carry an owner and can appear in this schema:
     * ordinary table, view, materialized view, sequence, partitioned table.
     * Indexes and constraints follow their table and have no separate owner.
     */
    private static final String RELATION_KINDS = "'r','v','m','S','p'";

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_MIGRATE;
    }

    @Override
    public void handle(Event event, Context context) {
        var config = ConfigProvider.getConfig();
        String schema = config.getValue("dispatch.database.schema", String.class);
        String role = config.getValue("dispatch.database.role", String.class);
        assertIdentifier("dispatch.database.schema", schema);
        assertIdentifier("dispatch.database.role", role);

        Connection connection = context.getConnection();
        try {
            // The schema goes first, and the order is not cosmetic: PostgreSQL
            // requires the incoming owner of a relation to hold CREATE on the
            // relation's schema. While the schema still belongs to the
            // migrator the service role holds nothing on it, and every
            // ALTER TABLE below would be refused with a message about the
            // schema rather than about the table.
            if (!schemaOwnedBy(connection, schema, role)) {
                execute(connection, "ALTER SCHEMA \"" + schema + "\" OWNER TO \"" + role + "\"");
                LOG.infof("normalised owner of schema %s to %s", schema, role);
            }
            List<String> relations = relationsNotOwnedBy(connection, schema, role);
            for (String relation : relations) {
                execute(connection, "ALTER TABLE IF EXISTS \"" + schema + "\".\"" + relation
                    + "\" OWNER TO \"" + role + "\"");
            }
            if (!relations.isEmpty()) {
                LOG.infof("normalised owner of %d object(s) in schema %s to %s",
                    relations.size(), schema, role);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                "failed to normalise object ownership in schema " + schema + " to " + role, e);
        }
    }

    private static List<String> relationsNotOwnedBy(Connection c, String schema, String role)
            throws SQLException {
        String sql = """
            SELECT c.relname
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = ?
              AND c.relkind IN (%s)
              AND pg_get_userbyid(c.relowner) <> ?
            """.formatted(RELATION_KINDS);
        List<String> out = new ArrayList<>();
        try (PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, schema);
            st.setString(2, role);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private static boolean schemaOwnedBy(Connection c, String schema, String role)
            throws SQLException {
        try (PreparedStatement st = c.prepareStatement(
                "SELECT pg_get_userbyid(nspowner) = ? FROM pg_namespace WHERE nspname = ?")) {
            st.setString(1, role);
            st.setString(2, schema);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static void execute(Connection c, String ddl) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(ddl);
        }
    }

    /**
     * An identifier cannot be a bind parameter, so it is concatenated — and a
     * concatenated identifier read from configuration is checked before it is
     * concatenated, not after. Quoting alone would not do: a quoted identifier
     * containing a quote still closes early.
     */
    private static void assertIdentifier(String key, String value) {
        if (value == null || !value.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalStateException(
                key + " must be a plain lower-case SQL identifier, was: " + value);
        }
    }
}
