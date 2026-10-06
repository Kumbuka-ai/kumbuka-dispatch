package ai.kumbuka.dispatch.boundary;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * What the database secures about the store of the rebuilt lifecycle (V17):
 * the column order, the value sets, the shape of a row and the one guard after
 * the send. Each rule is proven by a statement in plain SQL that the database
 * refuses, issued under the runtime role with its real grants and the tenant
 * bound as the service binds it.
 *
 * <p>Every probe runs in a transaction that is rolled back, under a savepoint
 * of its own, so a refused statement leaves nothing behind and does not
 * poison the probes after it.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskStoreShapeIT {

    /** The column order of the dispatch, written out rather than read from anywhere. */
    private static final List<String> TASK_COLUMNS = List.of(
        "id", "uuid", "tenant_id", "scope_id", "selector_id", "number", "sub", "state",
        "hold_reason", "outcome", "state_changed_at", "state_changed_by", "question_options",
        "not_before", "lapse_count", "holder_subject", "holder_receipt_hash", "lease_expires_at",
        "curated_in_id", "title", "apparatus", "dispatch_metadata", "return_metadata",
        "created_at", "created_by", "updated_at", "updated_by");

    private static final List<String> TASK_TEXT_COLUMNS = List.of(
        "id", "tenant_id", "scope_id", "task_id", "text_type", "addendum_suffix", "text",
        "created_at", "created_by");

    /** Surrogate, tenant and scope, parent, own columns, stamps — as V14's, onto task. */
    private static final List<String> TASK_IDEMPOTENCY_KEY_COLUMNS = List.of(
        "id", "tenant_id", "scope_id", "task_id", "caller_subject", "idempotency_key",
        "call_name", "argument_digest", "first_seen_at");

    // ------------------------------------------------------------------
    // Criterion 1 — the column order
    // ------------------------------------------------------------------

    @Test
    void the_columns_stand_in_the_order_of_the_dispatch() throws SQLException {
        try (Connection c = admin()) {
            assertThat(columns(c, "task")).isEqualTo(TASK_COLUMNS);
            assertThat(columns(c, "task_text")).isEqualTo(TASK_TEXT_COLUMNS);
            assertThat(columns(c, "task_idempotency_key")).isEqualTo(TASK_IDEMPOTENCY_KEY_COLUMNS);
        }
    }

    @Test
    void a_column_appended_later_breaks_the_order() throws SQLException {
        try (Connection c = admin()) {
            c.setAutoCommit(false);
            try {
                exec(c, "ALTER TABLE dispatch.task ADD COLUMN planted_later text");
                assertThat(columns(c, "task"))
                    .as("RED STATE, observed: PostgreSQL appends a column added later at the "
                        + "end, so the order the test holds no longer matches")
                    .isNotEqualTo(TASK_COLUMNS)
                    .endsWith("planted_later");
            } finally {
                c.rollback();
            }
        }
    }

    // ------------------------------------------------------------------
    // The value sets and the shape of a row, each refused in plain SQL
    // ------------------------------------------------------------------

    @Test
    void every_value_set_refuses_a_value_outside_it() throws SQLException {
        withServiceRole(c -> {
            refused(c, insertTask(1, "state", "'returned'"), "ck_task_state");
            refused(c, insertTask(2, "state, hold_reason", "'on_hold', 'waiting'"), "ck_task_hold_reason");
            refused(c, insertTask(3, "state, outcome", "'closed', 'consumed'"), "ck_task_outcome");
            refused(c, insertTaskAt(0, 0), "ck_task_number");
            refused(c, insertTaskAt(4, -1), "ck_task_sub");
            refused(c, insertTask(5, "lapse_count", "-1"), "ck_task_lapse_count");

            long task = id(c, insertTask(6, "state", "'draft'") + " RETURNING id");
            refused(c, insertText(task, "'commission'", "NULL"), "ck_task_text_type");
            refused(c, insertText(task, "'dispatch'", "'aa'"), "ck_task_text_suffix");
        });
    }

    @Test
    void every_shape_rule_refuses_a_row_that_breaks_it() throws SQLException {
        withServiceRole(c -> {
            // hold_reason exactly in on_hold
            refused(c, insertTask(10, "state, hold_reason", "'open', 'question'"),
                "ck_task_hold_reason_exactly_on_hold");
            refused(c, insertTask(11, "state", "'on_hold'"), "ck_task_hold_reason_exactly_on_hold");
            // outcome exactly in closed
            refused(c, insertTask(12, "state, outcome", "'delivered', 'accepted'"),
                "ck_task_outcome_exactly_closed");
            refused(c, insertTask(13, "state", "'closed'"), "ck_task_outcome_exactly_closed");
            // holder and receipt hash only together
            refused(c, insertTask(14, "state, holder_subject", "'active', 'exec'"),
                "ck_task_holder_whole");
            refused(c, insertTask(15, "state, holder_receipt_hash", "'active', 'hash'"),
                "ck_task_holder_whole");
            // the lease only in active, and only with a holder
            refused(c, insertTask(16, "state, hold_reason, holder_subject, holder_receipt_hash, "
                    + "lease_expires_at", "'on_hold', 'question', 'exec', 'hash', now()"),
                "ck_task_lease_only_active_and_held");
            refused(c, insertTask(17, "state, lease_expires_at", "'active', now()"),
                "ck_task_lease_only_active_and_held");
            // question_options only on a question
            refused(c, insertTask(18, "state, hold_reason, question_options",
                    "'on_hold', 'dependency', '{\"options\": [], \"free_text\": true}'"),
                "ck_task_question_options_only_on_question");
            // not_before only in open
            refused(c, insertTask(19, "state, not_before", "'active', now()"),
                "ck_task_not_before_only_open");

            // curated_in only in closed and never the task itself
            long target = id(c, insertTask(20, "state, outcome", "'closed', 'accepted'")
                + " RETURNING id");
            refused(c, insertTask(21, "state, curated_in_id", "'delivered', " + target),
                "ck_task_curated_in_only_closed_and_not_self");
            refused(c, "UPDATE dispatch.task SET curated_in_id = id WHERE id = " + target,
                "ck_task_curated_in_only_closed_and_not_self");

            // and the same rules by UPDATE, on a row that was valid
            long open = id(c, insertTask(22, "state", "'open'") + " RETURNING id");
            refused(c, "UPDATE dispatch.task SET state = 'on_hold' WHERE id = " + open,
                "ck_task_hold_reason_exactly_on_hold");
            refused(c, "UPDATE dispatch.task SET state = 'closed' WHERE id = " + open,
                "ck_task_outcome_exactly_closed");
            refused(c, "UPDATE dispatch.task SET state = 'active', not_before = now() WHERE id = " + open,
                "ck_task_not_before_only_open");

            // the valid forms pass, so the refusals above were the rules and not the base row
            succeeds(c, insertTask(23, "state, hold_reason, holder_subject, holder_receipt_hash, "
                + "question_options", "'on_hold', 'question', 'exec', 'hash', "
                + "'{\"options\": [], \"free_text\": true}'"));
            succeeds(c, insertTask(24, "state, holder_subject, holder_receipt_hash, lease_expires_at",
                "'active', 'exec', 'hash', now()"));
            succeeds(c, insertTask(25, "state, holder_subject, holder_receipt_hash",
                "'delivered', 'exec', 'hash'"));
            succeeds(c, insertTask(26, "state, not_before", "'open', now()"));
            succeeds(c, insertTask(27, "state, outcome, curated_in_id", "'closed', 'accepted', " + target));
        });
    }

    // ------------------------------------------------------------------
    // The one guard after the send
    // ------------------------------------------------------------------

    @Test
    void a_sent_task_keeps_its_texts_and_itself() throws SQLException {
        withServiceRole(c -> {
            long sent = id(c, insertTask(30, "state", "'open'") + " RETURNING id");
            long text = id(c, insertText(sent, "'dispatch'", "NULL") + " RETURNING id");
            long sentWithoutText = id(c, insertTask(31, "state", "'active'") + " RETURNING id");

            refusedByGuard(c, "UPDATE dispatch.task_text SET text = 'rewritten' WHERE id = " + text);
            refusedByGuard(c, "DELETE FROM dispatch.task_text WHERE id = " + text);
            refusedByGuard(c, "DELETE FROM dispatch.task WHERE id = " + sentWithoutText);

            // An insert is not guarded: a transition adds a text to a sent task.
            succeeds(c, insertText(sent, "'return'", "NULL"));
        });
    }

    @Test
    void a_draft_is_changed_and_deleted_texts_first() throws SQLException {
        withServiceRole(c -> {
            long draft = id(c, insertTask(40, "state", "'draft'") + " RETURNING id");
            long text = id(c, insertText(draft, "'dispatch'", "NULL") + " RETURNING id");

            assertThat(count(c, "UPDATE dispatch.task_text SET text = 'rewritten' WHERE id = " + text))
                .as("a text of a draft is changed").isEqualTo(1);
            assertThat(count(c, "DELETE FROM dispatch.task_text WHERE task_id = " + draft))
                .as("its texts are deleted").isEqualTo(1);
            assertThat(count(c, "DELETE FROM dispatch.task WHERE id = " + draft))
                .as("and then the draft itself, under the runtime role's own DELETE").isEqualTo(1);
        });
    }

    // ------------------------------------------------------------------

    private interface Probe {
        void run(Connection c) throws SQLException;
    }

    /** Runs the probes as the runtime role, tenant bound, and rolls everything back. */
    private static void withServiceRole(Probe probe) throws SQLException {
        try (Connection c = DriverManager.getConnection(config("test.db.url"),
                SubstrateDatabaseResource.SERVICE_ROLE, SubstrateDatabaseResource.SERVICE_PASSWORD)) {
            c.setAutoCommit(false);
            try {
                exec(c, "SELECT set_config('app.tenant_id', '" + config("dispatch.tenant-id") + "', true)");
                probe.run(c);
            } finally {
                c.rollback();
            }
        }
    }

    /** A task in the configured tenant and scope; numbers start high to stay clear of other tests. */
    private static String insertTask(int number, String columns, String values) {
        return "INSERT INTO dispatch.task (tenant_id, scope_id, selector_id, number, sub, title, "
            + "apparatus, " + columns + ") SELECT tenant_id, scope_id, id, " + (9000 + number)
            + ", 0, 'probe', 'code', " + values + " FROM dispatch.selector WHERE name = 'sprint'";
    }

    private static String insertTaskAt(int number, int sub) {
        return "INSERT INTO dispatch.task (tenant_id, scope_id, selector_id, number, sub, title, "
            + "apparatus) SELECT tenant_id, scope_id, id, " + number + ", " + sub
            + ", 'probe', 'code' FROM dispatch.selector WHERE name = 'sprint'";
    }

    private static String insertText(long task, String type, String suffix) {
        return "INSERT INTO dispatch.task_text (tenant_id, scope_id, task_id, text_type, "
            + "addendum_suffix, text) SELECT tenant_id, scope_id, id, " + type + ", " + suffix
            + ", 'a text' FROM dispatch.task WHERE id = " + task;
    }

    /** The statement is refused by the named check constraint, and by nothing else. */
    private static void refused(Connection c, String sql, String constraint) throws SQLException {
        Savepoint sp = c.setSavepoint();
        try {
            exec(c, sql);
            fail("RED STATE expected: %s must refuse%n  %s", constraint, sql);
        } catch (PSQLException e) {
            assertThat(e.getSQLState()).as("check violation for: " + sql).isEqualTo("23514");
            assertThat(e.getServerErrorMessage().getConstraint()).as(sql).isEqualTo(constraint);
        } finally {
            c.rollback(sp);
        }
    }

    /** The statement is refused by the guard trigger after the send. */
    private static void refusedByGuard(Connection c, String sql) throws SQLException {
        Savepoint sp = c.setSavepoint();
        try {
            assertThatThrownBy(() -> exec(c, sql))
                .as("RED STATE expected: the guard after the send must refuse%n  %s", sql)
                .isInstanceOf(PSQLException.class)
                .satisfies(e -> {
                    assertThat(((PSQLException) e).getSQLState()).isEqualTo("P0001");
                    assertThat(e.getMessage()).containsAnyOf("is frozen", "only a draft is deleted");
                });
        } finally {
            c.rollback(sp);
        }
    }

    private static void succeeds(Connection c, String sql) throws SQLException {
        assertThat(count(c, sql)).as("must be accepted: " + sql).isEqualTo(1);
    }

    private static int count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            return s.executeUpdate(sql);
        }
    }

    private static long id(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).as("one row from: " + sql).isTrue();
            return rs.getLong(1);
        }
    }

    private static List<String> columns(Connection c, String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (var st = c.prepareStatement("""
                SELECT a.attname FROM pg_attribute a
                 WHERE a.attrelid = ('dispatch.' || ?)::regclass
                   AND a.attnum > 0 AND NOT a.attisdropped
                 ORDER BY a.attnum
                """)) {
            st.setString(1, table);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(config("test.db.url"),
            config("test.db.admin.username"), config("test.db.admin.password"));
    }

    private static String config(String key) {
        return ConfigProvider.getConfig().getValue(key, String.class);
    }
}
