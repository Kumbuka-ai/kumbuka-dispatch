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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * What the database secures about the task store of the store line (V1, V2,
 * V4, V6): the column order of a task, its draft and the ledger of keys, the
 * value sets and the shape of a row, the draft workspace deleted children
 * first, a task with an act kept, and a key that outlives its draft. Each rule
 * is proven by a statement in plain SQL that the database refuses, issued
 * under the runtime role with its real grants and the tenant bound as the
 * service binds it; each refusal stands beside the valid form, which passes.
 *
 * <p>Every probe runs in a transaction that is rolled back, under a savepoint
 * of its own, so a refused statement leaves nothing behind and does not
 * poison the probes after it.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskStoreShapeIT {

    /** The column order of a task, written out rather than read from anywhere: V1, then V4. */
    private static final List<String> TASK_COLUMNS = List.of(
        "id", "tenant_id", "scope_id", "instance_id", "sub", "title", "activity_id",
        "concerns_task_id", "state", "hold_reason", "resolution", "not_before", "parked",
        "addressed_role");

    /** The column order of a draft: V2, then V4 and V6. */
    private static final List<String> DRAFT_COLUMNS = List.of(
        "id", "tenant_id", "scope_id", "task_id", "title", "activity_id", "concerns_task_id",
        "bound_task_id", "addressed_role", "changed_at");

    /** The column order of the ledger of keys: V1, then V6. */
    private static final List<String> IDEMPOTENCY_KEY_COLUMNS = List.of(
        "id", "tenant_id", "scope_id", "caller_subject", "idempotency_key", "task_id", "act_id",
        "created_at", "argument_digest");

    // ------------------------------------------------------------------
    // The column order
    // ------------------------------------------------------------------

    @Test
    void the_columns_stand_in_the_order_of_the_store_line() throws SQLException {
        try (Connection c = admin()) {
            assertThat(columns(c, "task")).isEqualTo(TASK_COLUMNS);
            assertThat(columns(c, "draft")).as("V6 appends the token of a draft")
                .isEqualTo(DRAFT_COLUMNS);
            assertThat(columns(c, "idempotency_key")).as("V6 appends the digest of a key")
                .isEqualTo(IDEMPOTENCY_KEY_COLUMNS);
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
    // The list of open tasks by role, with an index to walk
    // ------------------------------------------------------------------

    /** The order the open tasks of a role are read in, written out (V4). */
    private static final List<String> BY_ROLE = List.of(
        "tenant_id", "scope_id", "state", "addressed_role", "instance_id", "sub");

    @Test
    void the_list_of_tasks_by_role_has_its_index_and_loses_it_when_dropped() throws SQLException {
        try (Connection c = admin()) {
            assertThat(indexes(c, "task")).as("the tasks by state and role walk their index")
                .contains(BY_ROLE);
            c.setAutoCommit(false);
            try {
                exec(c, "DROP INDEX dispatch.idx_task_state_addressed_role");
                assertThat(indexes(c, "task"))
                    .as("RED STATE, observed: without the index the order has none")
                    .doesNotContain(BY_ROLE);
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
            long instance = plantInstance(c);
            refused(c, insertTask(instance, 1, "state", "'returned'"), "ck_task_state");
            refused(c, insertTask(instance, 2, "state, hold_reason", "'on_hold', 'waiting'"),
                "ck_task_hold_reason");
            refused(c, insertTask(instance, 3, "state, resolution", "'closed', 'consumed'"),
                "ck_task_resolution");
            refused(c, insertTask(instance, -1, "state", "'draft'"), "ck_task_sub");
            refused(c, insertTask(instance, 4, "addressed_role", "'-starts-with-a-mark'"),
                "ck_task_addressed_role");
            succeeds(c, insertTask(instance, 5, "addressed_role", "'code.review_2'"));

            long task = id(c, insertTask(instance, 6, "state", "'draft'") + " RETURNING id");
            refused(c, insertDraft(task, "addressed_role", "' '"), "ck_draft_addressed_role");
            long draft = id(c, insertDraft(task, "title", "'a title'") + " RETURNING id");
            refused(c, insertText(draft, "'return'", "NULL"), "ck_draft_text_kind");
            refused(c, "INSERT INTO dispatch.draft_free_fields (tenant_id, scope_id, draft_id, "
                + "document) SELECT tenant_id, scope_id, id, '[1, 2]' FROM dispatch.draft "
                + "WHERE id = " + draft, "ck_draft_free_fields_document");
            succeeds(c, "INSERT INTO dispatch.draft_free_fields (tenant_id, scope_id, draft_id, "
                + "document) SELECT tenant_id, scope_id, id, '{\"k\": [1, 2]}' FROM "
                + "dispatch.draft WHERE id = " + draft);
        });
    }

    @Test
    void every_shape_rule_refuses_a_row_that_breaks_it() throws SQLException {
        withServiceRole(c -> {
            long instance = plantInstance(c);
            refused(c, insertTask(instance, 10, "state, hold_reason", "'open', 'question'"),
                "ck_task_on_hold_reason");
            refused(c, insertTask(instance, 11, "state", "'on_hold'"), "ck_task_on_hold_reason");
            refused(c, insertTask(instance, 12, "state, resolution", "'delivered', 'accepted'"),
                "ck_task_closed_resolved");
            refused(c, insertTask(instance, 13, "state", "'closed'"), "ck_task_closed_resolved");
            refused(c, insertTask(instance, 14, "state, not_before", "'active', now()"),
                "ck_task_not_before_open");
            refused(c, insertTask(instance, 15, "state, parked", "'open', true"),
                "ck_task_parked");

            long open = id(c, insertTask(instance, 16, "state", "'open'") + " RETURNING id");
            refused(c, "UPDATE dispatch.task SET concerns_task_id = id WHERE id = " + open,
                "ck_task_concerns_other");
            refused(c, "UPDATE dispatch.task SET state = 'closed' WHERE id = " + open,
                "ck_task_closed_resolved");

            long draftTask = id(c, insertTask(instance, 17, "state", "'draft'") + " RETURNING id");
            long draft = id(c, insertDraft(draftTask, "title", "'a title'") + " RETURNING id");
            refused(c, insertText(draft, "'criterion'", "NULL"), "ck_draft_text_criterion");

            // the valid forms pass, so the refusals above were the rules and not the base row
            succeeds(c, insertTask(instance, 18, "state, hold_reason", "'on_hold', 'question'"));
            succeeds(c, insertTask(instance, 19, "state, resolution", "'closed', 'withdrawn'"));
            succeeds(c, insertTask(instance, 20, "state, not_before", "'open', now()"));
            succeeds(c, insertTask(instance, 21, "state, hold_reason, parked",
                "'on_hold', 'external', true"));
            succeeds(c, "UPDATE dispatch.task SET state = 'closed', resolution = 'withdrawn' "
                + "WHERE id = " + open);
        });
    }

    // ------------------------------------------------------------------
    // The draft workspace, and a task with an act
    // ------------------------------------------------------------------

    @Test
    void a_draft_is_changed_and_deleted_children_first() throws SQLException {
        withServiceRole(c -> {
            long instance = plantInstance(c);
            long task = id(c, insertTask(instance, 30, "state", "'draft'") + " RETURNING id");
            long draft = id(c, insertDraft(task, "title", "'a title'") + " RETURNING id");
            long criterion = id(c, "INSERT INTO dispatch.draft_criterion (tenant_id, scope_id, "
                + "draft_id, identifier, ordinal) SELECT tenant_id, scope_id, id, 'c1', 1 "
                + "FROM dispatch.draft WHERE id = " + draft + " RETURNING id");
            succeeds(c, insertText(draft, "'criterion'", String.valueOf(criterion)));
            succeeds(c, insertText(draft, "'commission'", "NULL"));

            assertThat(count(c, "UPDATE dispatch.draft SET title = 'changed', "
                    + "changed_at = changed_at + interval '1 microsecond' WHERE id = " + draft))
                .as("a draft and its token are changed under the runtime role (V6)").isEqualTo(1);

            refusedByKey(c, "DELETE FROM dispatch.draft WHERE id = " + draft,
                "the draft before its criteria and texts");
            refusedByKey(c, "DELETE FROM dispatch.task WHERE id = " + task,
                "the task before its draft");

            assertThat(count(c, "DELETE FROM dispatch.draft_text WHERE draft_id = " + draft))
                .as("its texts are deleted").isEqualTo(2);
            assertThat(count(c, "DELETE FROM dispatch.draft_criterion WHERE draft_id = " + draft))
                .as("then its criteria").isEqualTo(1);
            assertThat(count(c, "DELETE FROM dispatch.draft WHERE id = " + draft))
                .as("then the draft").isEqualTo(1);
            assertThat(count(c, "DELETE FROM dispatch.task WHERE id = " + task))
                .as("and then the task itself, under the runtime role's own DELETE").isEqualTo(1);
        });
    }

    @Test
    void a_task_with_an_act_is_never_deleted() throws SQLException {
        withServiceRole(c -> {
            long instance = plantInstance(c);
            long sent = id(c, insertTask(instance, 40, "state", "'open'") + " RETURNING id");
            succeeds(c, "INSERT INTO dispatch.act (tenant_id, scope_id, instance_id, sequence, "
                + "task_id, kind, actor_subject, permission, path, at, to_state) "
                + "SELECT tenant_id, scope_id, instance_id, 1, id, 'send', 'probe', "
                + "'dispatch.task.commission', 'interface', now(), 'open' FROM dispatch.task "
                + "WHERE id = " + sent);
            refusedByKey(c, "DELETE FROM dispatch.task WHERE id = " + sent,
                "a task with an act: its trace begins with its first send");
            long unsent = id(c, insertTask(instance, 41, "state", "'draft'") + " RETURNING id");
            assertThat(count(c, "DELETE FROM dispatch.task WHERE id = " + unsent))
                .as("RED STATE, observed: a task without an act is deleted").isEqualTo(1);
        });
    }

    /**
     * The image before V6 names {@code changed_at} in no statement; its insert
     * of a draft stands, and the row takes the time of the store (N-1).
     */
    @Test
    void a_draft_inserted_without_its_token_takes_the_default_of_v6() throws SQLException {
        withServiceRole(c -> {
            long instance = plantInstance(c);
            long task = id(c, insertTask(instance, 50, "state", "'draft'") + " RETURNING id");
            long draft = id(c, insertDraft(task, "title", "'a title'") + " RETURNING id");
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT changed_at FROM dispatch.draft WHERE id = "
                     + draft)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getTimestamp(1)).as("the default fills the token").isNotNull();
            }
        });
    }

    // ------------------------------------------------------------------
    // The ledger of keys
    // ------------------------------------------------------------------

    @Test
    void a_key_outlives_its_draft_and_an_expired_one_is_deleted() throws SQLException {
        withServiceRole(c -> {
            long instance = plantInstance(c);
            long task = id(c, insertTask(instance, 60, "state", "'draft'") + " RETURNING id");
            long key = id(c, "INSERT INTO dispatch.idempotency_key (tenant_id, scope_id, "
                + "caller_subject, idempotency_key, task_id, argument_digest, created_at) "
                + "SELECT tenant_id, scope_id, 'probe', 'shape-" + UUID.randomUUID() + "', id, "
                + "'a digest', now() FROM dispatch.task WHERE id = " + task + " RETURNING id");

            assertThat(count(c, "DELETE FROM dispatch.task WHERE id = " + task))
                .as("the draft is deleted with a key on it").isEqualTo(1);
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT task_id, argument_digest FROM "
                     + "dispatch.idempotency_key WHERE id = " + key)) {
                assertThat(rs.next()).as("the key stays").isTrue();
                assertThat(rs.getObject(1)).as("and lets go of the task by the store's own key")
                    .isNull();
                assertThat(rs.getString(2)).isEqualTo("a digest");
            }
            assertThat(count(c, "DELETE FROM dispatch.idempotency_key WHERE id = " + key))
                .as("and an expired key is deleted under the runtime role (V6)").isEqualTo(1);
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
                exec(c, "SELECT set_config('app.tenant_id', '" + config("dispatch.tenant-id")
                    + "', true)");
                probe.run(c);
            } finally {
                c.rollback();
            }
        }
    }

    /**
     * A selector, a version and an instance of the configured tenant in a scope
     * of their own, written under the runtime role; answers the instance.
     */
    private static long plantInstance(Connection c) throws SQLException {
        String scope = "'" + UUID.randomUUID() + "'";
        String tenant = "'" + config("dispatch.tenant-id") + "'";
        long selector = id(c, "INSERT INTO dispatch.selector (tenant_id, scope_id, name) VALUES ("
            + tenant + ", " + scope + ", 'shape') RETURNING id");
        long version = id(c, "INSERT INTO dispatch.definition_version (tenant_id, scope_id, "
            + "selector_id, version, verdict_required, outcome_required, state) VALUES (" + tenant
            + ", " + scope + ", " + selector + ", 1, false, false, 'active') RETURNING id");
        return id(c, "INSERT INTO dispatch.instance (tenant_id, scope_id, selector_id, number, "
            + "definition_version_id) VALUES (" + tenant + ", " + scope + ", " + selector
            + ", 1, " + version + ") RETURNING id");
    }

    /** A task of the instance at a sub, with the columns and values given. */
    private static String insertTask(long instance, int sub, String columns, String values) {
        return "INSERT INTO dispatch.task (tenant_id, scope_id, instance_id, sub, " + columns
            + ") SELECT tenant_id, scope_id, id, " + sub + ", " + values
            + " FROM dispatch.instance WHERE id = " + instance;
    }

    /** The draft of a task, with the column and value given, the token left to its default. */
    private static String insertDraft(long task, String column, String value) {
        return "INSERT INTO dispatch.draft (tenant_id, scope_id, task_id, " + column
            + ") SELECT tenant_id, scope_id, id, " + value + " FROM dispatch.task WHERE id = "
            + task;
    }

    private static String insertText(long draft, String kind, String criterion) {
        return "INSERT INTO dispatch.draft_text (tenant_id, scope_id, draft_id, kind, "
            + "draft_criterion_id, media_type, body) SELECT tenant_id, scope_id, id, " + kind
            + ", " + criterion + ", 'text/plain', 'a text' FROM dispatch.draft WHERE id = "
            + draft;
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

    /** The statement is refused by a foreign key, which deletes nothing along. */
    private static void refusedByKey(Connection c, String sql, String what) throws SQLException {
        Savepoint sp = c.setSavepoint();
        try {
            exec(c, sql);
            fail("RED STATE expected: a key must refuse deleting %s%n  %s", what, sql);
        } catch (PSQLException e) {
            assertThat(e.getSQLState()).as("foreign key violation for: " + sql)
                .isEqualTo("23503");
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
