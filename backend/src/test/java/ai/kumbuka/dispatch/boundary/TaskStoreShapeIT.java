/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
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
 * What the database secures about the store of the rebuilt lifecycle (V17,
 * V18, V22): the column order, the value sets, the shape of a row, the guard on the
 * texts after the send, the freeze of a sent task's content and the mark of a
 * self-acceptance. Each rule is proven by a statement in plain SQL that the database
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
        "created_at", "created_by", "updated_at", "updated_by",
        // V22 appends the mark: a column added later stands at the end.
        "self_accepted");

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
    // The two orders a listing pages in, each with an index to walk
    // ------------------------------------------------------------------

    /** The orders of a listing, written out: by address, and by creation. */
    private static final List<String> BY_ADDRESS = List.of(
        "tenant_id", "scope_id", "selector_id", "number", "sub");
    private static final List<String> BY_CREATION = List.of(
        "tenant_id", "scope_id", "selector_id", "created_at", "number", "sub");

    @Test
    void a_listing_has_an_index_for_each_order_it_pages_in() throws SQLException {
        try (Connection c = admin()) {
            assertThat(indexes(c, "task"))
                .as("the address order walks the address key, the creation order its index")
                .contains(BY_ADDRESS, BY_CREATION);
        }
    }

    @Test
    void a_dropped_index_leaves_the_order_without_one() throws SQLException {
        try (Connection c = admin()) {
            c.setAutoCommit(false);
            try {
                exec(c, "DROP INDEX dispatch.idx_task_created_address");
                assertThat(indexes(c, "task"))
                    .as("RED STATE, observed: without the index the creation order has none")
                    .doesNotContain(BY_CREATION);
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
    // The mark of a self-acceptance (V22)
    // ------------------------------------------------------------------

    /**
     * The mark stands only on a task closed as accepted, is never cleared, and
     * an insert that does not name it -- the image before V22 -- writes false.
     *
     * <p>RED PROBE: with the check of V22 left out, the first two refusals
     * pass; with its trigger left out, the clearing update passes.
     */
    @Test
    void the_mark_of_a_self_acceptance_stands_only_where_it_belongs_and_stays()
            throws SQLException {
        withServiceRole(c -> {
            refused(c, insertTask(50, "state, holder_subject, holder_receipt_hash, "
                    + "self_accepted", "'delivered', 'exec', 'hash', true"),
                "ck_task_self_accepted_only_accepted");
            refused(c, insertTask(51, "state, outcome, self_accepted", "'closed', 'withdrawn', true"),
                "ck_task_self_accepted_only_accepted");

            long marked = id(c, insertTask(52, "state, outcome, self_accepted",
                "'closed', 'accepted', true") + " RETURNING id");
            Savepoint sp = c.setSavepoint();
            try {
                assertThatThrownBy(() -> exec(c,
                        "UPDATE dispatch.task SET self_accepted = false WHERE id = " + marked))
                    .as("the mark is never cleared")
                    .isInstanceOf(PSQLException.class)
                    .satisfies(e -> {
                        assertThat(((PSQLException) e).getSQLState()).isEqualTo("P0001");
                        assertThat(e.getMessage()).contains("never removed");
                    });
            } finally {
                c.rollback(sp);
            }

            long unnamed = id(c, insertTask(53, "state, outcome", "'closed', 'accepted'")
                + " RETURNING id");
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT self_accepted FROM dispatch.task WHERE id = " + unnamed)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1))
                    .as("an insert that does not name the column, as the image before V22 "
                        + "writes, takes false").isFalse();
            }
            assertThat(count(c, "UPDATE dispatch.task SET self_accepted = true WHERE id = "
                    + unnamed))
                .as("the acceptance sets it, under the runtime role").isEqualTo(1);
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
    // The content of a sent task (V18)
    // ------------------------------------------------------------------

    /** Each frozen column, with a value it does not have yet. */
    private static final List<String> CONTENT_WRITES = List.of(
        "title = 'rewritten'",
        "apparatus = 'design'",
        "dispatch_metadata = '{\"pr\": \"https://example.org/pr/1\"}'");

    @Test
    void a_sent_task_keeps_its_title_apparatus_and_metadata_and_a_draft_does_not() throws SQLException {
        withServiceRole(c -> {
            long sent = id(c, insertTask(50, "state", "'open'") + " RETURNING id");
            long draft = id(c, insertTask(51, "state", "'draft'") + " RETURNING id");
            for (String write : CONTENT_WRITES) {
                refusedByContentFreeze(c, "UPDATE dispatch.task SET " + write + " WHERE id = " + sent,
                    "title, apparatus and dispatch_metadata are changed only while it is a draft");
                succeeds(c, "UPDATE dispatch.task SET " + write + " WHERE id = " + draft);
            }
        });
    }

    @Test
    void the_change_that_sends_a_draft_still_sets_its_content() throws SQLException {
        withServiceRole(c -> {
            long draft = id(c, insertTask(52, "state", "'draft'") + " RETURNING id");
            succeeds(c, "UPDATE dispatch.task SET state = 'open', title = 'as sent', "
                + "apparatus = 'design', dispatch_metadata = '{\"date\": \"2026-10-06\"}' "
                + "WHERE id = " + draft);
        });
    }

    @Test
    void the_creation_stamps_never_change_not_even_on_a_draft() throws SQLException {
        withServiceRole(c -> {
            long draft = id(c, insertTask(53, "state", "'draft'") + " RETURNING id");
            long sent = id(c, insertTask(54, "state", "'open'") + " RETURNING id");
            for (long task : new long[] {draft, sent}) {
                refusedByContentFreeze(c, "UPDATE dispatch.task SET created_at = created_at - "
                    + "interval '1 day' WHERE id = " + task, "created_at and created_by");
                refusedByContentFreeze(c, "UPDATE dispatch.task SET created_by = 'someone else' "
                    + "WHERE id = " + task, "created_at and created_by");
            }
        });
    }

    @Test
    void the_lifecycle_of_a_sent_task_stays_writable() throws SQLException {
        withServiceRole(c -> {
            long target = id(c, insertTask(55, "state, outcome", "'closed', 'accepted'") + " RETURNING id");
            long sent = id(c, insertTask(56, "state", "'open'") + " RETURNING id");
            String at = " WHERE id = " + sent;

            succeeds(c, "UPDATE dispatch.task SET return_metadata = '{\"pr\": \"x\"}'" + at);
            succeeds(c, "UPDATE dispatch.task SET not_before = now(), lapse_count = 1" + at);
            succeeds(c, "UPDATE dispatch.task SET state = 'active', not_before = NULL, "
                + "holder_subject = 'exec', holder_receipt_hash = 'hash', lease_expires_at = now(), "
                + "state_changed_at = now(), state_changed_by = 'exec'" + at);
            succeeds(c, "UPDATE dispatch.task SET state = 'on_hold', hold_reason = 'question', "
                + "question_options = '{\"options\": [], \"free_text\": true}', "
                + "lease_expires_at = NULL" + at);
            succeeds(c, "UPDATE dispatch.task SET state = 'delivered', hold_reason = NULL, "
                + "question_options = NULL" + at);
            succeeds(c, "UPDATE dispatch.task SET state = 'closed', outcome = 'accepted', "
                + "holder_subject = NULL, holder_receipt_hash = NULL, curated_in_id = " + target + at);
            succeeds(c, "UPDATE dispatch.task SET return_metadata = '{\"pr\": \"y\"}', "
                + "updated_at = now(), updated_by = 'kernel'" + at);
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
            + "apparatus, updated_at, " + columns + ") SELECT tenant_id, scope_id, id, "
            + (9000 + number) + ", 0, 'probe', 'code', now(), " + values
            + " FROM dispatch.selector WHERE name = 'sprint'";
    }

    private static String insertTaskAt(int number, int sub) {
        return "INSERT INTO dispatch.task (tenant_id, scope_id, selector_id, number, sub, title, "
            + "apparatus, updated_at) SELECT tenant_id, scope_id, id, " + number + ", " + sub
            + ", 'probe', 'code', now() FROM dispatch.selector WHERE name = 'sprint'";
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

    /**
     * The statement is refused by the content freeze of V18: the error class and
     * form of the guard on the texts, with the sentence that names what is frozen.
     */
    private static void refusedByContentFreeze(Connection c, String sql, String frozen)
            throws SQLException {
        Savepoint sp = c.setSavepoint();
        try {
            assertThatThrownBy(() -> exec(c, sql))
                .as("RED STATE expected: the content freeze must refuse%n  %s", sql)
                .isInstanceOf(PSQLException.class)
                .satisfies(e -> {
                    assertThat(((PSQLException) e).getSQLState()).isEqualTo("P0001");
                    assertThat(e.getMessage()).contains("is frozen").contains(frozen);
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

    /** The key columns of every index on a table, each in its order. */
    private static List<List<String>> indexes(Connection c, String table) throws SQLException {
        List<List<String>> out = new ArrayList<>();
        try (var st = c.prepareStatement("""
                SELECT array_to_string(ARRAY(
                         SELECT a.attname FROM unnest(i.indkey) WITH ORDINALITY k(attnum, n)
                         JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
                         ORDER BY k.n), ',')
                  FROM pg_index i
                 WHERE i.indrelid = ('dispatch.' || ?)::regclass
                """)) {
            st.setString(1, table);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(List.of(rs.getString(1).split(",")));
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
