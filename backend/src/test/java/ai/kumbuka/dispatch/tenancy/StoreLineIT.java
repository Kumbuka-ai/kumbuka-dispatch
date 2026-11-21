/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.tenancy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store line ({@code db/store}) on a database of its own, with nothing
 * but that line applied.
 *
 * <p>The line stands beside {@code db/migration} and the service does not
 * read it yet, so nothing else in the suite reaches it: what is asserted about
 * it is asserted here. Every expectation is written in this class, from the
 * orders that specified the line; none is read from the migrations.
 *
 * <p>Every case that writes does so in a transaction it rolls back, so the
 * database stays as the migration left it.
 */
class StoreLineIT {

    private static final String MIGRATOR = "store_line_migrator";
    private static final String MIGRATOR_PASSWORD = "test-only-store-line-password";

    /** The runtime role, created by the line on a cold start with its placeholder password. */
    private static final String SERVICE_ROLE = "kumbuka_dispatch";
    private static final String SERVICE_PASSWORD = "change-me-kumbuka-dispatch";

    private static final String HISTORY = "flyway_schema_history";

    /**
     * The thirty-three tables the line builds, as the orders name them: 24 in V1, 6 in V2, 5 in
     * V3, and V4 drops hold, deferral and question and adds instance_field_value.
     */
    private static final Set<String> TABLES = new TreeSet<>(List.of(
        "selector", "definition_version", "definition_act", "definition_tag", "activity",
        "outcome_label",
        "instance", "task", "act",
        "commission", "commission_criterion", "holding", "receipt", "lease",
        "question_option", "answer", "delivery", "delivery_criterion",
        "instance_return", "external_reference", "idempotency_key",
        "free_fields", "content",
        "draft", "draft_criterion", "draft_free_fields", "draft_text",
        "enumeration", "enumeration_value", "control_field", "contract_field", "field_value",
        "instance_field_value"));

    /** The tables V4 drops, whose payloads became columns of act. */
    private static final List<String> DROPPED = List.of("hold", "deferral", "question");

    /** The tables of the draft workspace, the only ones besides task the runtime role deletes from. */
    private static final Set<String> DRAFT_WORKSPACE =
        Set.of("draft", "draft_criterion", "draft_free_fields", "draft_text");

    /**
     * The foreign keys the orders name, per table: in V1 7 in the definition,
     * 9 on instance, task and act, 30 on the payloads; in V2 1 on free_fields,
     * 5 on content and 8 in the draft workspace; in V3 1 on enumeration, 1 on
     * enumeration_value, 2 on control_field, 3 on contract_field and 4 on
     * field_value: 71. V4 adds 1 from commission to instance, replaces the keys on instance and
     * commission and the key of answer to its option without changing their number, replaces
     * the keys of question_option and answer to question by one each to act, drops the three
     * keys of hold, deferral and question to act, and adds 5 on instance_field_value:
     * 71 + 1 - 3 + 5 = 74.
     */
    private static final int FOREIGN_KEYS = 74;

    /** The only JSON columns of the schema, both JSONB, as the order names them. */
    private static final List<String> JSON_DOCUMENTS =
        List.of("draft_free_fields.document jsonb", "free_fields.document jsonb");

    /** What {@link #refusal} answers for a statement that ran. */
    private static final String NOT_REFUSED = "not refused: the statement ran";

    /**
     * The tables the isolation is observed on: two of V1, a content and a
     * draft table of V2, the field and the value table of V3, the projection
     * of V4.
     */
    private static final List<String> ISOLATED = List.of("task", "act", "content", "draft_text",
        "control_field", "field_value", "instance_field_value");

    /** An addressed role the pattern of the order refuses: it carries a space. */
    private static final String ROLE_WITH_SPACE = "reviewer lead";

    /** An act on the chain's task, with the three payload columns of V4, without RETURNING. */
    private static final String ACT = "INSERT INTO dispatch.act "
        + "(tenant_id, scope_id, instance_id, sequence, task_id, kind, actor_subject, at, "
        + "to_state, hold_reason, not_before, free_text_admitted) "
        + "VALUES (?, ?, ?, ?, ?, ?, 'probe-subject', now(), ?, ?, ?, ?)";

    /** A commission, without RETURNING. */
    private static final String COMMISSION = "INSERT INTO dispatch.commission "
        + "(tenant_id, scope_id, instance_id, act_id, title, activity_id, concerns_task_id, "
        + "bound_task_id, addressed_role) VALUES (?, ?, ?, ?, 'probe', ?, ?, ?, ?)";

    /** A row of the projection of field values, without RETURNING. */
    private static final String INSTANCE_FIELD_VALUE = "INSERT INTO dispatch.instance_field_value "
        + "(tenant_id, scope_id, instance_id, control_field_id, field_value_id, "
        + "accepted_by_act_id, text_value) VALUES (?, ?, ?, ?, ?, ?, 'probe')";

    /** A control field, without RETURNING so that {@link #refusal} can run it. */
    private static final String CONTROL_FIELD = "INSERT INTO dispatch.control_field "
        + "(tenant_id, scope_id, definition_version_id, name, type, enumeration_id) "
        + "VALUES (?, ?, ?, ?, ?, ?)";

    /** A field value, with every value column; the arguments come from {@link #fieldValue}. */
    private static final String FIELD_VALUE = "INSERT INTO dispatch.field_value "
        + "(tenant_id, scope_id, act_id, control_field_id, value_type, enumeration_id, "
        + "boolean_value, number_value, date_value, text_value, choice_value_id) "
        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /** A line of a contract, without RETURNING. */
    private static final String CONTRACT_FIELD = "INSERT INTO dispatch.contract_field "
        + "(tenant_id, scope_id, definition_version_id, activity_id, control_field_id, required) "
        + "VALUES (?, ?, ?, ?, ?, true)";

    /** The five value columns of a field value, in the order of {@link #FIELD_VALUE}. */
    private static final List<String> VALUE_COLUMNS = List.of(
        "boolean_value", "number_value", "date_value", "text_value", "choice_value_id");

    /** The five types of the platform's catalogue, as the order names them. */
    private static final List<String> TYPES = List.of("boolean", "number", "date", "text", "choice");

    /** The policy predicate of every table, as the order writes it. */
    private static final String PREDICATE =
        "tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid";

    /** The columns the runtime role may update, per table; every other table has none. */
    private static final Map<String, Set<String>> PROJECTION = Map.ofEntries(
        Map.entry("selector", Set.of("next_number")),
        Map.entry("definition_version", Set.of("state")),
        Map.entry("instance", Set.of("next_sub", "definition_version_id", "state", "resolution")),
        Map.entry("task", Set.of("title", "activity_id", "concerns_task_id", "state",
            "hold_reason", "resolution", "not_before", "parked", "addressed_role")),
        Map.entry("holding", Set.of("ended_by_act_id")),
        Map.entry("external_reference", Set.of("ended_by_act_id")),
        Map.entry("draft", Set.of("title", "activity_id", "concerns_task_id", "bound_task_id",
            "addressed_role")),
        Map.entry("draft_criterion", Set.of("identifier", "ordinal")),
        Map.entry("draft_free_fields", Set.of("document")),
        Map.entry("draft_text", Set.of("media_type", "body")),
        Map.entry("instance_field_value", Set.of("field_value_id", "accepted_by_act_id",
            "boolean_value", "number_value", "date_value", "text_value", "choice_value_id")));

    private static MigrationHarness harness;
    private static String url;

    /** Rows per relation of the schema, read as the superuser right after the migration. */
    private static final Map<String, Long> ROWS_AFTER_MIGRATION = new TreeMap<>();

    @BeforeAll
    static void migrateTheLine() throws SQLException {
        harness = MigrationHarness.start();
        harness.createMigrator(MIGRATOR, MIGRATOR_PASSWORD, "CREATEROLE NOSUPERUSER NOBYPASSRLS");
        url = harness.freshDatabase("store_line", MIGRATOR);
        harness.migrateLine(url, MIGRATOR, MIGRATOR_PASSWORD, "classpath:db/store",
            new TenantMigrationCallback());
        try (Connection c = harness.adminConnection(url)) {
            for (String table : baseTables(c)) {
                ROWS_AFTER_MIGRATION.put(table,
                    Long.parseLong(scalar(c, "SELECT count(*) FROM dispatch." + table)));
            }
        }
    }

    @AfterAll
    static void stop() {
        if (harness != null) {
            harness.close();
        }
    }

    // -----------------------------------------------------------------------
    // The line runs on an empty database and writes no row.
    // -----------------------------------------------------------------------

    @Test
    void the_line_builds_the_thirty_three_tables_on_an_empty_database_and_writes_no_row()
            throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            assertThat(scalar(c, "SELECT string_agg(version, ',' ORDER BY installed_rank) FROM "
                    + "dispatch." + HISTORY + " WHERE success AND version IS NOT NULL"))
                .as("the line's four migrations are recorded as applied, V1 to V4 in order")
                .isEqualTo("1,2,3,4");
        }
        Map<String, Long> expected = new TreeMap<>();
        TABLES.forEach(table -> expected.put(table, 0L));
        Map<String, Long> withoutHistory = new TreeMap<>(ROWS_AFTER_MIGRATION);
        withoutHistory.remove(HISTORY);
        assertThat(withoutHistory)
            .as("the schema holds exactly the thirty-three tables of the orders, and none of them "
                + "a row: selectors are declared later by a migration of the installation")
            .isEqualTo(expected);
        assertThat(ROWS_AFTER_MIGRATION.keySet())
            .as("hold, deferral and question no longer exist: V4 dropped them")
            .doesNotContainAnyElementsOf(DROPPED);
        assertThat(ROWS_AFTER_MIGRATION.get(HISTORY))
            .as("only the history of the migration tool carries rows")
            .isPositive();
    }

    // -----------------------------------------------------------------------
    // Tenancy, read from the catalog.
    // -----------------------------------------------------------------------

    @Test
    void every_table_carries_tenant_and_scope_and_a_forced_policy_in_the_form_of_the_order()
            throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            c.setAutoCommit(false);
            List<String> tables = new ArrayList<>(baseTables(c));
            tables.remove(HISTORY);
            String[] form = referencePolicy(c);
            assertThat(tables).as("the check must have tables to read").hasSize(TABLES.size());
            assertThat(tenancyDefects(c, tables, form))
                .as("every table carries tenant_id and scope_id NOT NULL, row-level security "
                    + "enabled and forced, and one policy <table>_tenant_isolation whose USING "
                    + "and WITH CHECK are the predicate of the order")
                .isEmpty();
            c.rollback();
        }
    }

    @Test
    void every_foreign_key_leads_with_the_tenant_and_the_scope_on_both_sides()
            throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            assertThat(keysWithoutTenantAndScope(c))
                .as("a key is checked with row-level security bypassed, so a key that does not "
                    + "carry tenant and scope pairs a row with a target in any tenant or scope")
                .isEmpty();
            assertThat(scalar(c, """
                    SELECT count(*) FROM pg_constraint k
                    JOIN pg_namespace n ON n.oid = k.connamespace
                    WHERE n.nspname = 'dispatch' AND k.contype = 'f'
                    """))
                .as("the orders name seventy-four keys; a different count is a key missing or one "
                    + "the orders do not name")
                .isEqualTo(String.valueOf(FOREIGN_KEYS));
        }
    }

    /** The red state of both catalog checks above, observed on every build. */
    @Test
    void the_catalog_checks_report_each_defect_they_exist_for() throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            c.setAutoCommit(false);
            String[] form = referencePolicy(c);
            exec(c, "CREATE TABLE dispatch.planted_open (id BIGINT PRIMARY KEY, "
                + "tenant_id UUID NOT NULL, scope_id UUID NOT NULL)");
            exec(c, "CREATE TABLE dispatch.planted_unforced (id BIGINT PRIMARY KEY, "
                + "tenant_id UUID NOT NULL, scope_id UUID NOT NULL)");
            exec(c, "ALTER TABLE dispatch.planted_unforced ENABLE ROW LEVEL SECURITY");
            exec(c, "CREATE POLICY planted_unforced_tenant_isolation ON dispatch.planted_unforced "
                + "USING (" + PREDICATE + ") WITH CHECK (" + PREDICATE + ")");
            exec(c, "CREATE TABLE dispatch.planted_loose (id BIGINT PRIMARY KEY, "
                + "tenant_id UUID NOT NULL, scope_id UUID NOT NULL)");
            exec(c, "ALTER TABLE dispatch.planted_loose ENABLE ROW LEVEL SECURITY");
            exec(c, "ALTER TABLE dispatch.planted_loose FORCE ROW LEVEL SECURITY");
            exec(c, "CREATE POLICY planted_loose_tenant_isolation ON dispatch.planted_loose "
                + "USING (true) WITH CHECK (" + PREDICATE + ")");
            exec(c, "CREATE TABLE dispatch.planted_tenant_only (id BIGINT PRIMARY KEY, "
                + "tenant_id UUID NOT NULL)");
            exec(c, "ALTER TABLE dispatch.selector ADD CONSTRAINT planted_unique "
                + "UNIQUE (tenant_id, id)");
            exec(c, "CREATE TABLE dispatch.planted_reference (tenant_id UUID NOT NULL, "
                + "scope_id UUID NOT NULL, selector_id BIGINT, "
                + "CONSTRAINT planted_key FOREIGN KEY (tenant_id, selector_id) "
                + "REFERENCES dispatch.selector (tenant_id, id))");

            List<String> tenancy = tenancyDefects(c, List.of("planted_open", "planted_unforced",
                "planted_loose", "planted_tenant_only"), form);
            List<String> keys = keysWithoutTenantAndScope(c);
            c.rollback();

            assertThat(tenancy)
                .as("RED STATE, observed: a table without row-level security, one enabled but "
                    + "not forced, one whose policy reads no tenant, and one without scope_id "
                    + "are each reported")
                .anyMatch(d -> d.startsWith("planted_open: row-level security not enabled"))
                .anyMatch(d -> d.startsWith("planted_unforced: row-level security not forced"))
                .anyMatch(d -> d.startsWith("planted_loose: policy"))
                .anyMatch(d -> d.startsWith("planted_tenant_only: scope_id"));
            assertThat(keys)
                .as("RED STATE, observed: a key that carries the tenant but not the scope is "
                    + "reported")
                .containsExactly("planted_reference.planted_key");
        }
    }

    // -----------------------------------------------------------------------
    // Isolation under the runtime role, with the red state observed.
    // -----------------------------------------------------------------------

    @Test
    void under_the_service_role_a_tenant_reads_no_row_of_another_in_the_tables_observed()
            throws SQLException {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        try (Connection c = service()) {
            bind(c, a);
            insertChainAndWork(c, a, UUID.randomUUID(), "a");
            bind(c, b);
            insertChainAndWork(c, b, UUID.randomUUID(), "b");

            bind(c, a);
            for (String table : ISOLATED) {
                assertThat(countOf(c, table, b))
                    .as("bound to tenant A, the service role reads no %s of tenant B", table)
                    .isZero();
                assertThat(countOf(c, table, a))
                    .as("while A's own %s is there, so the emptiness above is a filter and "
                        + "not a failed insert", table)
                    .isEqualTo(1);
            }
            c.rollback();
        }
    }

    /**
     * The red state of the isolation, on every table of {@link #ISOLATED}:
     * without the policy in force the runtime role reads the foreign row, and
     * without FORCE so does the owner. Each removal is made in a transaction
     * and rolled back, so it is observed on every build and undone with it.
     */
    @Test
    void without_the_policy_or_without_force_the_foreign_row_appears() throws SQLException {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        try (Connection c = harness.adminConnection(url)) {
            c.setAutoCommit(false);
            insertChainAndWork(c, a, UUID.randomUUID(), "a");
            insertChainAndWork(c, b, UUID.randomUUID(), "b");

            for (String table : ISOLATED) {
                assertThat(foreignRowsAs(c, SERVICE_ROLE, table, a, b))
                    .as("green: the runtime role bound to A reads no %s of B", table).isZero();
                assertThat(foreignRowsAs(c, MIGRATOR, table, a, b))
                    .as("green: the owner bound to A reads no %s of B, because the policy is "
                        + "forced", table).isZero();

                Savepoint removed = c.setSavepoint();
                exec(c, "ALTER TABLE dispatch." + table + " DISABLE ROW LEVEL SECURITY");
                assertThat(foreignRowsAs(c, SERVICE_ROLE, table, a, b))
                    .as("RED STATE, observed: with the policy of %s out of force, the runtime "
                        + "role bound to A reads B's row", table)
                    .isEqualTo(1);
                c.rollback(removed);

                removed = c.setSavepoint();
                exec(c, "ALTER TABLE dispatch." + table + " NO FORCE ROW LEVEL SECURITY");
                assertThat(foreignRowsAs(c, MIGRATOR, table, a, b))
                    .as("RED STATE, observed: with FORCE taken off %s, its owner bound to A "
                        + "reads B's row", table)
                    .isEqualTo(1);
                c.rollback(removed);

                assertThat(foreignRowsAs(c, SERVICE_ROLE, table, a, b))
                    .as("and restored: the isolation of %s is back, so the red states above "
                        + "were the removals", table).isZero();
            }
            c.rollback();
        }
    }

    // -----------------------------------------------------------------------
    // Privileges of the runtime role.
    // -----------------------------------------------------------------------

    @Test
    void the_service_role_can_neither_change_nor_delete_an_act_nor_update_a_non_projection()
            throws SQLException {
        UUID tenant = UUID.randomUUID();
        try (Connection c = service()) {
            bind(c, tenant);
            Chain chain = insertChain(c, tenant, UUID.randomUUID(), "privileges");

            assertThat(refusal(c, "UPDATE dispatch.act SET kind = kind WHERE id = ?", chain.act()))
                .as("an act is never changed: the runtime role holds no UPDATE on act")
                .isEqualTo("42501");
            assertThat(refusal(c, "DELETE FROM dispatch.act WHERE id = ?", chain.act()))
                .as("nor DELETE")
                .isEqualTo("42501");
            assertThat(refusal(c, "UPDATE dispatch.instance SET number = number WHERE id = ?",
                    chain.instance()))
                .as("number is no projection of instance and cannot be updated")
                .isEqualTo("42501");
            try (PreparedStatement st = c.prepareStatement(
                    "UPDATE dispatch.instance SET state = 'closed', resolution = 'concluded' "
                        + "WHERE id = ?")) {
                st.setLong(1, chain.instance());
                assertThat(st.executeUpdate())
                    .as("while state, a projection, is updated").isEqualTo(1);
            }
            c.rollback();
        }
    }

    @Test
    void the_service_role_can_neither_change_nor_delete_content_or_free_fields_but_a_draft_text()
            throws SQLException {
        UUID tenant = UUID.randomUUID();
        try (Connection c = service()) {
            bind(c, tenant);
            Work work = insertChainAndWork(c, tenant, UUID.randomUUID(), "content-privileges");

            assertThat(refusal(c, "UPDATE dispatch.content SET body = body WHERE id = ?",
                    work.content()))
                .as("content is never changed: the runtime role holds no UPDATE on content")
                .isEqualTo("42501");
            assertThat(refusal(c, "DELETE FROM dispatch.content WHERE id = ?", work.content()))
                .as("nor DELETE")
                .isEqualTo("42501");
            assertThat(refusal(c, "UPDATE dispatch.free_fields SET document = document WHERE id = ?",
                    work.freeFields()))
                .as("free fields are never changed: no UPDATE on free_fields")
                .isEqualTo("42501");
            assertThat(refusal(c, "DELETE FROM dispatch.free_fields WHERE id = ?",
                    work.freeFields()))
                .as("nor DELETE")
                .isEqualTo("42501");
            assertThat(refusal(c, "UPDATE dispatch.draft_text SET draft_id = draft_id WHERE id = ?",
                    work.draftText()))
                .as("draft_id is not among the columns of draft_text the runtime role may update")
                .isEqualTo("42501");

            assertThat(executed(c, "UPDATE dispatch.draft_text SET body = 'changed' WHERE id = ?",
                    work.draftText()))
                .as("while the body of a draft text is changed").isEqualTo(1);
            assertThat(executed(c, "DELETE FROM dispatch.draft_text WHERE id = ?",
                    work.draftText()))
                .as("and a draft text is deleted").isEqualTo(1);
            c.rollback();
        }
    }

    @Test
    void the_service_role_holds_exactly_the_privileges_of_the_order() throws SQLException {
        Map<String, Set<String>> tablePrivileges = new TreeMap<>();
        Map<String, Set<String>> updatable = new TreeMap<>();
        try (Connection c = harness.adminConnection(url)) {
            try (PreparedStatement st = c.prepareStatement("""
                    SELECT cl.relname, a.privilege_type
                    FROM pg_class cl
                    JOIN pg_namespace n ON n.oid = cl.relnamespace
                    CROSS JOIN LATERAL aclexplode(cl.relacl) a
                    WHERE n.nspname = 'dispatch' AND cl.relkind = 'r'
                      AND a.grantee = (SELECT oid FROM pg_roles WHERE rolname = ?)
                    """)) {
                st.setString(1, SERVICE_ROLE);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        tablePrivileges.computeIfAbsent(rs.getString(1), k -> new TreeSet<>())
                            .add(rs.getString(2));
                    }
                }
            }
            try (PreparedStatement st = c.prepareStatement("""
                    SELECT cl.relname, att.attname, a.privilege_type
                    FROM pg_attribute att
                    JOIN pg_class cl ON cl.oid = att.attrelid
                    JOIN pg_namespace n ON n.oid = cl.relnamespace
                    CROSS JOIN LATERAL aclexplode(att.attacl) a
                    WHERE n.nspname = 'dispatch' AND cl.relkind = 'r'
                      AND a.grantee = (SELECT oid FROM pg_roles WHERE rolname = ?)
                    """)) {
                st.setString(1, SERVICE_ROLE);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        updatable.computeIfAbsent(rs.getString(1), k -> new TreeSet<>())
                            .add(rs.getString(3) + " " + rs.getString(2));
                    }
                }
            }
        }

        Map<String, Set<String>> expectedTables = new TreeMap<>();
        TABLES.forEach(t -> expectedTables.put(t, new TreeSet<>(Set.of("SELECT", "INSERT"))));
        expectedTables.get("task").add("DELETE");
        DRAFT_WORKSPACE.forEach(t -> expectedTables.get(t).add("DELETE"));
        assertThat(tablePrivileges)
            .as("SELECT and INSERT on every table, DELETE on task and the four tables of the "
                + "draft workspace alone, nothing on the history of the migration tool, and never "
                + "UPDATE, TRUNCATE, REFERENCES or TRIGGER on a whole table")
            .isEqualTo(expectedTables);

        Map<String, Set<String>> expectedColumns = new TreeMap<>();
        PROJECTION.forEach((table, columns) -> {
            Set<String> granted = new TreeSet<>();
            columns.forEach(column -> granted.add("UPDATE " + column));
            expectedColumns.put(table, granted);
        });
        assertThat(updatable)
            .as("UPDATE only on the projection columns the order names, and no other column "
                + "privilege")
            .isEqualTo(expectedColumns);
    }

    @Test
    void a_draft_is_deleted_its_idempotency_key_stays_and_a_task_with_an_act_is_not()
            throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        try (Connection c = service()) {
            bind(c, tenant);
            Chain chain = insertChain(c, tenant, scope, "delete");
            long draft = insertReturningId(c, "INSERT INTO dispatch.task "
                + "(tenant_id, scope_id, instance_id, sub) VALUES (?, ?, ?, 2) RETURNING id",
                tenant, scope, chain.instance());
            long key = insertReturningId(c, "INSERT INTO dispatch.idempotency_key "
                + "(tenant_id, scope_id, caller_subject, idempotency_key, task_id, created_at) "
                + "VALUES (?, ?, 'probe-subject', 'probe-key', ?, now()) RETURNING id",
                tenant, scope, draft);

            try (PreparedStatement st = c.prepareStatement(
                    "DELETE FROM dispatch.task WHERE id = ?")) {
                st.setLong(1, draft);
                assertThat(st.executeUpdate())
                    .as("the runtime role deletes a task that has no act").isEqualTo(1);
            }
            try (PreparedStatement st = c.prepareStatement(
                    "SELECT count(*), count(task_id), "
                        + "count(*) FILTER (WHERE tenant_id = ? AND scope_id = ?) "
                        + "FROM dispatch.idempotency_key WHERE id = ?")) {
                st.setObject(1, tenant);
                st.setObject(2, scope);
                st.setLong(3, key);
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    assertThat(rs.getLong(1)).as("the key outlives its draft").isEqualTo(1);
                    assertThat(rs.getLong(2)).as("and lets go of it").isZero();
                    assertThat(rs.getLong(3))
                        .as("while its tenant_id and scope_id stay as they were: the key sets "
                            + "task_id alone to NULL")
                        .isEqualTo(1);
                }
            }
            assertThat(refusal(c, "DELETE FROM dispatch.task WHERE id = ?", chain.task()))
                .as("a task with an act is not deleted: the key of act refuses it")
                .isEqualTo("23503");
            c.rollback();
        }
    }

    // -----------------------------------------------------------------------
    // Checks and keys of content, free fields and the draft workspace.
    // -----------------------------------------------------------------------

    /**
     * Each check refuses its row with 23514, and the same row is taken once
     * the check is dropped: the red state, observed in a savepoint on every
     * build and rolled back with it.
     */
    @Test
    void the_checks_of_free_fields_and_content_refuse_and_without_them_the_row_is_taken()
            throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        try (Connection c = harness.adminConnection(url)) {
            c.setAutoCommit(false);
            Chain chain = insertChain(c, tenant, scope, "checks");
            long supplemented = insertReturningId(c, "INSERT INTO dispatch.content "
                + "(tenant_id, scope_id, act_id, kind, media_type, body) "
                + "VALUES (?, ?, ?, 'remark', 'text/plain', 'probe') RETURNING id",
                tenant, scope, chain.act());
            String content = "INSERT INTO dispatch.content (tenant_id, scope_id, act_id, kind, "
                + "supplements_content_id, supplements_act_id, media_type, body) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'text/plain', 'probe')";

            assertThat(withAndWithoutCheck(c, "free_fields", "ck_free_fields_document",
                    "INSERT INTO dispatch.free_fields (tenant_id, scope_id, act_id, document) "
                        + "VALUES (?, ?, ?, '[\"no\", \"object\"]'::jsonb)",
                    tenant, scope, chain.act()))
                .as("a document of free fields that is no JSON object is refused; RED STATE, "
                    + "observed: without the check it is taken")
                .containsExactly("23514", NOT_REFUSED);
            assertThat(withAndWithoutCheck(c, "content", "ck_content_criterion", content,
                    tenant, scope, chain.act(), "criterion", null, null))
                .as("a criterion text without its criterion is refused; RED STATE, observed: "
                    + "without the check it is taken")
                .containsExactly("23514", NOT_REFUSED);
            assertThat(withAndWithoutCheck(c, "content", "ck_content_supplements", content,
                    tenant, scope, chain.act(), "addendum", null, null))
                .as("an addendum that supplements nothing is refused; RED STATE, observed: "
                    + "without the check it is taken")
                .containsExactly("23514", NOT_REFUSED);
            assertThat(withAndWithoutCheck(c, "content", "ck_content_supplements", content,
                    tenant, scope, chain.act(), "addendum", supplemented, chain.act()))
                .as("an addendum that supplements a content row and an act at once is refused; "
                    + "RED STATE, observed: without the check it is taken")
                .containsExactly("23514", NOT_REFUSED);
            c.rollback();
        }
    }

    @Test
    void a_criterion_text_of_a_draft_hangs_only_on_a_criterion_of_the_same_draft()
            throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        try (Connection c = service()) {
            bind(c, tenant);
            Work work = insertChainAndWork(c, tenant, scope, "draft-criterion");
            long otherTask = insertReturningId(c, "INSERT INTO dispatch.task "
                + "(tenant_id, scope_id, instance_id, sub) VALUES (?, ?, ?, 2) RETURNING id",
                tenant, scope, work.chain().instance());
            long otherDraft = insertReturningId(c, "INSERT INTO dispatch.draft "
                + "(tenant_id, scope_id, task_id) VALUES (?, ?, ?) RETURNING id",
                tenant, scope, otherTask);
            String criterion = "INSERT INTO dispatch.draft_criterion "
                + "(tenant_id, scope_id, draft_id, identifier, ordinal) "
                + "VALUES (?, ?, ?, 'C1', 1) RETURNING id";
            long foreignCriterion = insertReturningId(c, criterion, tenant, scope, otherDraft);
            long ownCriterion = insertReturningId(c, criterion, tenant, scope, work.draft());
            String text = "INSERT INTO dispatch.draft_text "
                + "(tenant_id, scope_id, draft_id, kind, draft_criterion_id, media_type, body) "
                + "VALUES (?, ?, ?, 'criterion', ?, 'text/plain', 'probe')";

            assertThat(refusal(c, text, tenant, scope, work.draft(), foreignCriterion))
                .as("a criterion text of one draft on a criterion of another is refused by the "
                    + "key that carries the draft")
                .isEqualTo("23503");
            assertThat(refusal(c, text, tenant, scope, work.draft(), ownCriterion))
                .as("while the same text on a criterion of its own draft is taken, so the "
                    + "refusal above is the pairing and not the row")
                .isEqualTo(NOT_REFUSED);
            c.rollback();
        }
    }

    // -----------------------------------------------------------------------
    // Control fields and their values.
    // -----------------------------------------------------------------------

    @Test
    void the_service_role_can_neither_change_nor_delete_a_control_field_or_a_field_value()
            throws SQLException {
        UUID tenant = UUID.randomUUID();
        try (Connection c = service()) {
            bind(c, tenant);
            Work work = insertChainAndWork(c, tenant, UUID.randomUUID(), "field-privileges");

            assertThat(refusal(c, "UPDATE dispatch.control_field SET name = name WHERE id = ?",
                    work.controlField()))
                .as("a control field is never changed: the runtime role holds no UPDATE on "
                    + "control_field")
                .isEqualTo("42501");
            assertThat(refusal(c, "DELETE FROM dispatch.control_field WHERE id = ?",
                    work.controlField()))
                .as("nor DELETE")
                .isEqualTo("42501");
            assertThat(refusal(c, "UPDATE dispatch.field_value SET text_value = text_value "
                    + "WHERE id = ?", work.fieldValue()))
                .as("a field value is never changed: the runtime role holds no UPDATE on "
                    + "field_value")
                .isEqualTo("42501");
            assertThat(refusal(c, "DELETE FROM dispatch.field_value WHERE id = ?",
                    work.fieldValue()))
                .as("nor DELETE")
                .isEqualTo("42501");
            c.rollback();
        }
    }

    /**
     * Each check refuses its row with 23514, and the same row is taken once
     * the check is dropped: the red state, observed in a savepoint on every
     * build and rolled back with it.
     */
    @Test
    void the_checks_of_control_fields_and_field_values_refuse_and_without_them_the_row_is_taken()
            throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        try (Connection c = harness.adminConnection(url)) {
            c.setAutoCommit(false);
            Schema schema = insertSchema(c, tenant, scope, "field-checks");
            Target number = schema.target("number");
            long version = schema.chain().version();

            assertThat(withAndWithoutCheck(c, "field_value", "ck_field_value_one_value",
                    FIELD_VALUE, fieldValue(number, "number",
                        Map.of("number_value", 1, "text_value", "probe"))))
                .as("a field value with two value columns set is refused; RED STATE, observed: "
                    + "without the check it is taken")
                .containsExactly("23514", NOT_REFUSED);
            assertThat(withAndWithoutCheck(c, "field_value", "ck_field_value_typed_value",
                    FIELD_VALUE, fieldValue(number, "number", Map.of("text_value", "probe"))))
                .as("a number whose set column is text_value is refused; RED STATE, observed: "
                    + "without the check it is taken")
                .containsExactly("23514", NOT_REFUSED);
            assertThat(withAndWithoutCheck(c, "field_value", "ck_field_value_text_value",
                    FIELD_VALUE, fieldValue(schema.target("text"), "text",
                        Map.of("text_value", "x".repeat(4097)))))
                .as("a text of 4097 characters is refused; RED STATE, observed: without the "
                    + "check it is taken")
                .containsExactly("23514", NOT_REFUSED);
            assertThat(withAndWithoutCheck(c, "control_field", "ck_control_field_enumeration",
                    CONTROL_FIELD, tenant, scope, version, "unbound_choice", "choice", null))
                .as("a control field of the type choice without an enumeration is refused; "
                    + "RED STATE, observed: without the check it is taken")
                .containsExactly("23514", NOT_REFUSED);
            assertThat(withAndWithoutCheck(c, "control_field", "ck_control_field_name",
                    CONTROL_FIELD, tenant, scope, version, "Status", "text", null))
                .as("a field name with a capital letter is refused, since it is later an "
                    + "identifier in expressions; RED STATE, observed: without the check it is "
                    + "taken")
                .containsExactly("23514", NOT_REFUSED);
            c.rollback();
        }
    }

    @Test
    void a_field_value_takes_only_the_type_and_the_enumeration_of_its_field()
            throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        try (Connection c = service()) {
            bind(c, tenant);
            Schema schema = insertSchema(c, tenant, scope, "field-pairing");
            Map<String, Object> text = Map.of("text_value", "probe");
            Target choice = schema.target("choice");

            assertThat(refusal(c, FIELD_VALUE,
                    fieldValue(schema.target("number"), "text", text)))
                .as("a text value on a number field is refused by the key that carries the "
                    + "type of the field")
                .isEqualTo("23503");
            assertThat(refusal(c, FIELD_VALUE, fieldValue(schema.target("text"), "text", text)))
                .as("while the same text value on a text field is taken, so the refusal above "
                    + "is the pairing and not the row")
                .isEqualTo(NOT_REFUSED);

            assertThat(refusal(c, FIELD_VALUE,
                    choiceValue(choice, schema.enumeration(), schema.otherEnumerationValue())))
                .as("a choice naming a value of another enumeration than its field's is "
                    + "refused by the key to the values of the enumeration")
                .isEqualTo("23503");
            assertThat(refusal(c, FIELD_VALUE,
                    choiceValue(choice, schema.otherEnumeration(), schema.otherEnumerationValue())))
                .as("and so is one that names the other enumeration as well, by the key that "
                    + "carries the enumeration of the field")
                .isEqualTo("23503");
            assertThat(refusal(c, FIELD_VALUE,
                    choiceValue(choice, schema.enumeration(), schema.enumerationValue())))
                .as("while a choice of a value of its field's own enumeration is taken, so the "
                    + "refusals above are the pairing and not the row")
                .isEqualTo(NOT_REFUSED);
            c.rollback();
        }
    }

    @Test
    void a_control_field_and_a_contract_field_take_only_what_belongs_to_their_version()
            throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        try (Connection c = service()) {
            bind(c, tenant);
            Schema schema = insertSchema(c, tenant, scope, "version-pairing");
            long version = schema.chain().version();
            long otherVersion = insertReturningId(c, "INSERT INTO dispatch.definition_version "
                + "(tenant_id, scope_id, selector_id, version, verdict_required, outcome_required, "
                + "state) VALUES (?, ?, ?, 2, false, false, 'draft') RETURNING id",
                tenant, scope, schema.chain().selector());
            long foreignEnumeration = insertReturningId(c, "INSERT INTO dispatch.enumeration "
                + "(tenant_id, scope_id, definition_version_id, name) "
                + "VALUES (?, ?, ?, 'status') RETURNING id", tenant, scope, otherVersion);
            long foreignField = insertReturningId(c, CONTROL_FIELD + " RETURNING id",
                tenant, scope, otherVersion, "note", "text", null);
            long activity = insertReturningId(c, "INSERT INTO dispatch.activity "
                + "(tenant_id, scope_id, definition_version_id, name, verdict_required, "
                + "outcome_required, criteria_required, input_kind, may_ask, starts_excluded) "
                + "VALUES (?, ?, ?, 'review', false, false, false, 'none', false, false) "
                + "RETURNING id", tenant, scope, version);

            assertThat(refusal(c, CONTROL_FIELD, tenant, scope, version, "stage", "choice",
                    foreignEnumeration))
                .as("a control field taking an enumeration of another version is refused by "
                    + "the key that carries the version")
                .isEqualTo("23503");
            assertThat(refusal(c, CONTROL_FIELD, tenant, scope, version, "stage", "choice",
                    schema.enumeration()))
                .as("while the same field with an enumeration of its own version is taken, so "
                    + "the refusal above is the pairing and not the row")
                .isEqualTo(NOT_REFUSED);

            assertThat(refusal(c, CONTRACT_FIELD, tenant, scope, version, activity, foreignField))
                .as("a result contract of an activity naming a field of another version is "
                    + "refused by the key that carries the version")
                .isEqualTo("23503");
            assertThat(refusal(c, CONTRACT_FIELD, tenant, scope, version, activity,
                    schema.fields().get("text")))
                .as("while the same line naming a field of the activity's own version is taken, "
                    + "so the refusal above is the pairing and not the row")
                .isEqualTo(NOT_REFUSED);
            c.rollback();
        }
    }

    @Test
    void a_value_of_each_type_is_taken_the_four_plain_ones_without_an_enumeration()
            throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        try (Connection c = service()) {
            bind(c, tenant);
            Schema schema = insertSchema(c, tenant, scope, "field-types");
            Map<String, Map<String, Object>> plain = new LinkedHashMap<>();
            plain.put("boolean", Map.of("boolean_value", true));
            plain.put("number", Map.of("number_value", new BigDecimal("42.5")));
            plain.put("date", Map.of("date_value", LocalDate.of(2026, 11, 19)));
            plain.put("text", Map.of("text_value", "probe"));

            for (Map.Entry<String, Map<String, Object>> value : plain.entrySet()) {
                assertThat(refusal(c, FIELD_VALUE,
                        fieldValue(schema.target(value.getKey()), value.getKey(), value.getValue())))
                    .as("a %s value without an enumeration is taken: the keys over the "
                        + "enumeration are not checked where it is empty", value.getKey())
                    .isEqualTo(NOT_REFUSED);
            }
            assertThat(refusal(c, FIELD_VALUE, choiceValue(schema.target("choice"),
                    schema.enumeration(), schema.enumerationValue())))
                .as("and a choice of a value of its own enumeration is taken")
                .isEqualTo(NOT_REFUSED);
            c.rollback();
        }
    }

    @Test
    void an_act_writes_a_field_once_and_a_later_act_writes_it_anew() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        try (Connection c = service()) {
            bind(c, tenant);
            Schema schema = insertSchema(c, tenant, scope, "field-once");
            Chain chain = schema.chain();
            Target first = schema.target("text");
            Map<String, Object> text = Map.of("text_value", "probe");
            executed(c, FIELD_VALUE, fieldValue(first, "text", text));
            long later = insertReturningId(c, "INSERT INTO dispatch.act "
                + "(tenant_id, scope_id, instance_id, sequence, task_id, kind, actor_subject, at, "
                + "to_state) VALUES (?, ?, ?, 2, ?, 'deliver', 'probe-subject', now(), "
                + "'delivered') RETURNING id", tenant, scope, chain.instance(), chain.task());

            assertThat(refusal(c, FIELD_VALUE, fieldValue(first, "text", text)))
                .as("a second value of the same field at the same act is refused")
                .isEqualTo("23505");
            assertThat(refusal(c, FIELD_VALUE, fieldValue(
                    new Target(tenant, scope, later, first.field()), "text", text)))
                .as("while the same value at a later act is taken: a later act writes a new "
                    + "value of the field")
                .isEqualTo(NOT_REFUSED);
            c.rollback();
        }
    }

    // -----------------------------------------------------------------------
    // V4: the projection of field values, the payload columns of act, the
    // addressed role and the keys that bind a commission to one instance.
    // -----------------------------------------------------------------------

    @Test
    void the_service_role_changes_the_seven_projection_columns_of_a_field_value_and_nothing_else()
            throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        try (Connection c = service()) {
            bind(c, tenant);
            Work work = insertChainAndWork(c, tenant, scope, "projection-privileges");
            Chain chain = work.chain();
            long projection = instanceFieldValueOf(c, chain.instance());

            assertThat(refusal(c, "DELETE FROM dispatch.instance_field_value WHERE id = ?",
                    projection))
                .as("a row of the projection is never deleted: no DELETE on instance_field_value")
                .isEqualTo("42501");
            assertThat(refusal(c, "UPDATE dispatch.instance_field_value "
                    + "SET instance_id = instance_id WHERE id = ?", projection))
                .as("instance_id is no projection column and cannot be updated")
                .isEqualTo("42501");
            assertThat(refusal(c, "UPDATE dispatch.instance_field_value "
                    + "SET control_field_id = control_field_id WHERE id = ?", projection))
                .as("nor control_field_id")
                .isEqualTo("42501");
            assertThat(executed(c, "UPDATE dispatch.instance_field_value SET "
                    + "field_value_id = ?, accepted_by_act_id = ?, boolean_value = NULL, "
                    + "number_value = NULL, date_value = NULL, text_value = 'changed', "
                    + "choice_value_id = NULL WHERE id = ?",
                    work.fieldValue(), chain.act(), projection))
                .as("while the seven projection columns are updated").isEqualTo(1);
            c.rollback();
        }
    }

    @Test
    void the_service_role_cannot_change_the_columns_v4_freezes() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        try (Connection c = service()) {
            bind(c, tenant);
            Chain chain = insertChain(c, tenant, scope, "frozen-columns");
            long commission = insertReturningId(c, COMMISSION + " RETURNING id", tenant, scope,
                chain.instance(), chain.act(), insertActivity(c, chain), null, null, "reviewer");

            assertThat(refusal(c, "UPDATE dispatch.act SET hold_reason = 'external' WHERE id = ?",
                    chain.act()))
                .as("act.hold_reason is written with its act and never changed")
                .isEqualTo("42501");
            assertThat(refusal(c, "UPDATE dispatch.commission SET addressed_role = 'other' "
                    + "WHERE id = ?", commission))
                .as("commission.addressed_role freezes when the commission is sent")
                .isEqualTo("42501");
            assertThat(refusal(c, "UPDATE dispatch.instance SET title = 'other' WHERE id = ?",
                    chain.instance()))
                .as("instance.title is set by start and never changed")
                .isEqualTo("42501");
            assertThat(refusal(c, "UPDATE dispatch.definition_version SET seed = true "
                    + "WHERE id = ?", chain.version()))
                .as("definition_version.seed is written by no call")
                .isEqualTo("42501");
            c.rollback();
        }
    }

    @Test
    void a_task_takes_sub_zero_and_refuses_sub_minus_one() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        try (Connection c = harness.adminConnection(url)) {
            c.setAutoCommit(false);
            Chain chain = insertChain(c, tenant, scope, "sub-zero");
            String task = "INSERT INTO dispatch.task (tenant_id, scope_id, instance_id, sub) "
                + "VALUES (?, ?, ?, ?)";

            assertThat(refusal(c, task, tenant, scope, chain.instance(), 0))
                .as("a task under .0 is taken: the copy of the stock places a root task there")
                .isEqualTo(NOT_REFUSED);
            assertThat(refusal(c, task, tenant, scope, chain.instance(), -1))
                .as("a task under .-1 is refused")
                .isEqualTo("23514");

            Savepoint old = c.setSavepoint();
            exec(c, "ALTER TABLE dispatch.task DROP CONSTRAINT ck_task_sub");
            exec(c, "ALTER TABLE dispatch.task ADD CONSTRAINT ck_task_sub CHECK (sub >= 1)");
            String withOldCheck = refusal(c, task, tenant, scope, chain.instance(), 0);
            c.rollback(old);
            assertThat(withOldCheck)
                .as("RED STATE, observed: with the check of V1, sub >= 1, a task under .0 is "
                    + "refused")
                .isEqualTo("23514");
            c.rollback();
        }
    }

    /**
     * Each check refuses its row with 23514, and the same row is taken once
     * the check is dropped: the red state, observed in a savepoint on every
     * build and rolled back with it.
     */
    @Test
    void the_payload_checks_of_an_act_refuse_and_without_them_the_row_is_taken()
            throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        OffsetDateTime later = OffsetDateTime.now().plusDays(1);
        try (Connection c = harness.adminConnection(url)) {
            c.setAutoCommit(false);
            Chain chain = insertChain(c, tenant, scope, "act-payloads");

            assertThat(withAndWithoutCheck(c, "act", "ck_act_hold_kind", ACT,
                    act(chain, "claim", "active", "dependency", null, null)))
                .as("a claim with a hold reason is refused; RED STATE, observed: without the "
                    + "check it is taken")
                .containsExactly("23514", NOT_REFUSED);
            assertThat(withAndWithoutCheck(c, "act", "ck_act_hold_kind", ACT,
                    act(chain, "hold", "on_hold", null, null, null)))
                .as("a hold without a hold reason is refused; RED STATE, observed: without the "
                    + "check it is taken")
                .containsExactly("23514", NOT_REFUSED);
            assertThat(withAndWithoutCheck(c, "act", "ck_act_hold_reason", ACT,
                    act(chain, "hold", "on_hold", "question", null, null)))
                .as("a hold reason other than dependency and external is refused; RED STATE, "
                    + "observed: without the check it is taken")
                .containsExactly("23514", NOT_REFUSED);
            assertThat(withAndWithoutCheck(c, "act", "ck_act_not_before_kind", ACT,
                    act(chain, "claim", "active", null, later, null)))
                .as("a claim with a not-before time is refused; RED STATE, observed: without "
                    + "the check it is taken")
                .containsExactly("23514", NOT_REFUSED);
            assertThat(withAndWithoutCheck(c, "act", "ck_act_free_text_kind", ACT,
                    act(chain, "ask", "on_hold", null, null, null)))
                .as("an ask that does not say whether free text is admitted is refused; RED "
                    + "STATE, observed: without the check it is taken")
                .containsExactly("23514", NOT_REFUSED);
            assertThat(withAndWithoutCheck(c, "act", "ck_act_free_text_kind", ACT,
                    act(chain, "claim", "active", null, null, true)))
                .as("a claim that says whether free text is admitted is refused; RED STATE, "
                    + "observed: without the check it is taken")
                .containsExactly("23514", NOT_REFUSED);

            assertThat(List.of(
                    refusal(c, ACT, act(chain, "hold", "on_hold", "dependency", null, null)),
                    refusal(c, ACT, act(chain, "defer", "open", null, later, null)),
                    refusal(c, ACT, act(chain, "ask", "on_hold", null, null, false))))
                .as("while each payload on the act of its own kind is taken, so the refusals "
                    + "above are the pairing with the kind and not the row")
                .containsOnly(NOT_REFUSED);
            c.rollback();
        }
    }

    /**
     * Each check refuses its row with 23514, and the same row is taken once
     * the check is dropped: the red state, observed in a savepoint on every
     * build and rolled back with it.
     */
    @Test
    void the_checks_of_the_addressed_role_and_the_item_reference_refuse_and_without_them_the_row_is_taken()
            throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        try (Connection c = harness.adminConnection(url)) {
            c.setAutoCommit(false);
            Chain chain = insertChain(c, tenant, scope, "role-and-item");
            long activity = insertActivity(c, chain);

            assertThat(withAndWithoutCheck(c, "task", "ck_task_addressed_role",
                    "INSERT INTO dispatch.task (tenant_id, scope_id, instance_id, sub, "
                        + "addressed_role) VALUES (?, ?, ?, 2, ?)",
                    tenant, scope, chain.instance(), ROLE_WITH_SPACE))
                .as("a task addressed to a role with a space is refused; RED STATE, observed: "
                    + "without the check it is taken")
                .containsExactly("23514", NOT_REFUSED);
            assertThat(withAndWithoutCheck(c, "commission", "ck_commission_addressed_role",
                    COMMISSION, tenant, scope, chain.instance(), chain.act(), activity, null, null,
                    ROLE_WITH_SPACE))
                .as("so is a commission; RED STATE, observed: without the check it is taken")
                .containsExactly("23514", NOT_REFUSED);
            assertThat(withAndWithoutCheck(c, "draft", "ck_draft_addressed_role",
                    "INSERT INTO dispatch.draft (tenant_id, scope_id, task_id, addressed_role) "
                        + "VALUES (?, ?, ?, ?)",
                    tenant, scope, chain.task(), ROLE_WITH_SPACE))
                .as("and a draft; RED STATE, observed: without the check it is taken")
                .containsExactly("23514", NOT_REFUSED);
            assertThat(withAndWithoutCheck(c, "external_reference",
                    "ck_external_reference_instance_kinds",
                    "INSERT INTO dispatch.external_reference "
                        + "(tenant_id, scope_id, task_id, kind, value, set_by_act_id) "
                        + "VALUES (?, ?, ?, 'item', 'worklist://probe/item/1', ?)",
                    tenant, scope, chain.task(), chain.act()))
                .as("a reference of the kind item without an instance is refused; RED STATE, "
                    + "observed: without the check it is taken")
                .containsExactly("23514", NOT_REFUSED);
            c.rollback();
        }
    }

    @Test
    void an_instance_is_bound_only_to_a_version_of_its_own_selector() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        try (Connection c = service()) {
            bind(c, tenant);
            Chain chain = insertChain(c, tenant, scope, "own-selector");
            long otherSelector = insertReturningId(c, "INSERT INTO dispatch.selector "
                + "(tenant_id, scope_id, name) VALUES (?, ?, 'other-selector') RETURNING id",
                tenant, scope);
            long otherVersion = insertReturningId(c, "INSERT INTO dispatch.definition_version "
                + "(tenant_id, scope_id, selector_id, version, verdict_required, outcome_required, "
                + "state) VALUES (?, ?, ?, 1, false, false, 'active') RETURNING id",
                tenant, scope, otherSelector);
            String instance = "INSERT INTO dispatch.instance "
                + "(tenant_id, scope_id, selector_id, number, definition_version_id) "
                + "VALUES (?, ?, ?, 2, ?)";

            assertThat(refusal(c, instance, tenant, scope, chain.selector(), otherVersion))
                .as("an instance bound to a version of another selector is refused by the key "
                    + "that carries the selector")
                .isEqualTo("23503");
            assertThat(refusal(c, instance, tenant, scope, chain.selector(), chain.version()))
                .as("while the same instance bound to a version of its own selector is taken, "
                    + "so the refusal above is the pairing and not the row")
                .isEqualTo(NOT_REFUSED);
            c.rollback();
        }
    }

    @Test
    void a_commission_takes_its_act_and_its_tasks_only_from_its_own_instance()
            throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        try (Connection c = service()) {
            bind(c, tenant);
            Chain chain = insertChain(c, tenant, scope, "commission-instance");
            Other other = insertOtherInstance(c, chain);
            long activity = insertActivity(c, chain);
            long instance = chain.instance();

            assertThat(refusal(c, COMMISSION, tenant, scope, instance, other.act(), activity,
                    null, null, null))
                .as("a commission whose act belongs to another instance is refused")
                .isEqualTo("23503");
            assertThat(refusal(c, COMMISSION, tenant, scope, instance, chain.act(), activity,
                    null, null, null))
                .as("while the same commission with an act of its own instance is taken")
                .isEqualTo(NOT_REFUSED);
            assertThat(refusal(c, COMMISSION, tenant, scope, instance, chain.act(), activity,
                    other.task(), null, null))
                .as("a commission concerning a task of another instance is refused")
                .isEqualTo("23503");
            assertThat(refusal(c, COMMISSION, tenant, scope, instance, chain.act(), activity,
                    chain.task(), null, null))
                .as("while the same commission concerning a task of its own instance is taken")
                .isEqualTo(NOT_REFUSED);
            assertThat(refusal(c, COMMISSION, tenant, scope, instance, chain.act(), activity,
                    null, other.task(), null))
                .as("a commission bound to a task of another instance is refused")
                .isEqualTo("23503");
            assertThat(refusal(c, COMMISSION, tenant, scope, instance, chain.act(), activity,
                    null, chain.task(), null))
                .as("while the same commission bound to a task of its own instance is taken")
                .isEqualTo(NOT_REFUSED);
            c.rollback();
        }
    }

    @Test
    void an_answer_chooses_only_an_option_of_its_own_question() throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        try (Connection c = service()) {
            bind(c, tenant);
            Chain chain = insertChain(c, tenant, scope, "answer-option");
            long asked = insertReturningId(c, ACT + " RETURNING id",
                act(chain, 2, "ask", "on_hold", null, null, false));
            long otherAsked = insertReturningId(c, ACT + " RETURNING id",
                act(chain, 3, "ask", "on_hold", null, null, false));
            long answering = insertReturningId(c, ACT + " RETURNING id",
                act(chain, 4, "answer", "active", null, null, null));
            long option = insertReturningId(c, "INSERT INTO dispatch.question_option "
                + "(tenant_id, scope_id, ask_act_id, label, ordinal) VALUES (?, ?, ?, 'yes', 1) "
                + "RETURNING id", tenant, scope, asked);
            String answer = "INSERT INTO dispatch.answer "
                + "(tenant_id, scope_id, act_id, ask_act_id, option_id) VALUES (?, ?, ?, ?, ?)";

            assertThat(refusal(c, answer, tenant, scope, answering, otherAsked, option))
                .as("an answer choosing an option of another question is refused by the key "
                    + "that carries the ask act")
                .isEqualTo("23503");
            assertThat(refusal(c, answer, tenant, scope, answering, asked, option))
                .as("while the same answer to the option's own question is taken")
                .isEqualTo(NOT_REFUSED);
            c.rollback();
        }
    }

    @Test
    void a_field_value_is_projected_once_per_instance_and_field_and_accepted_only_within_it()
            throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        try (Connection c = service()) {
            bind(c, tenant);
            Work work = insertChainAndWork(c, tenant, scope, "projection-keys");
            Chain chain = work.chain();
            Other other = insertOtherInstance(c, chain);

            assertThat(refusal(c, INSTANCE_FIELD_VALUE, tenant, scope, chain.instance(),
                    work.controlField(), work.fieldValue(), null))
                .as("a second row for the same instance and field is refused")
                .isEqualTo("23505");
            assertThat(refusal(c, INSTANCE_FIELD_VALUE, tenant, scope, other.instance(),
                    work.controlField(), work.fieldValue(), chain.act()))
                .as("a row accepted by an act of another instance is refused")
                .isEqualTo("23503");
            assertThat(refusal(c, INSTANCE_FIELD_VALUE, tenant, scope, other.instance(),
                    work.controlField(), work.fieldValue(), other.act()))
                .as("while the same row of the same field for another instance, accepted by an "
                    + "act of that instance, is taken: the refusals above are the pair and the "
                    + "pairing, not the row")
                .isEqualTo(NOT_REFUSED);
            c.rollback();
        }
    }

    @Test
    void an_item_reference_on_an_instance_and_an_input_text_on_its_start_are_taken()
            throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        try (Connection c = service()) {
            bind(c, tenant);
            Chain chain = insertChain(c, tenant, scope, "start-input");
            long start = insertReturningId(c, "INSERT INTO dispatch.act "
                + "(tenant_id, scope_id, instance_id, sequence, kind, actor_subject, at, to_state) "
                + "VALUES (?, ?, ?, 2, 'start', 'probe-subject', now(), 'running') RETURNING id",
                tenant, scope, chain.instance());

            assertThat(refusal(c, "INSERT INTO dispatch.external_reference "
                    + "(tenant_id, scope_id, instance_id, kind, value, set_by_act_id) "
                    + "VALUES (?, ?, ?, 'item', 'worklist://probe/item/1', ?)",
                    tenant, scope, chain.instance(), start))
                .as("a reference of the kind item on an instance is taken")
                .isEqualTo(NOT_REFUSED);
            assertThat(refusal(c, "INSERT INTO dispatch.content "
                    + "(tenant_id, scope_id, act_id, kind, media_type, body) "
                    + "VALUES (?, ?, ?, 'instance_input', 'text/plain', 'probe')",
                    tenant, scope, start))
                .as("and a content of the kind instance_input on a start act")
                .isEqualTo(NOT_REFUSED);
            c.rollback();
        }
    }

    // -----------------------------------------------------------------------
    // JSON only in the two documents of free fields.
    // -----------------------------------------------------------------------

    @Test
    void the_only_json_columns_are_the_two_jsonb_documents_of_free_fields() throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            assertThat(scalar(c, "SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_schema = 'dispatch'"))
                .as("the check must have columns to read").isNotEqualTo("0");
            assertThat(jsonDefects(c))
                .as("no json column, and jsonb exactly for free_fields.document and "
                    + "draft_free_fields.document: the control carries no JSON")
                .isEmpty();

            c.setAutoCommit(false);
            Savepoint planted = c.setSavepoint();
            exec(c, "ALTER TABLE dispatch.act ADD COLUMN planted_payload JSONB");
            exec(c, "ALTER TABLE dispatch.task ADD COLUMN planted_metadata JSON");
            List<String> added = jsonDefects(c);
            c.rollback(planted);
            exec(c, "ALTER TABLE dispatch.draft_free_fields DROP COLUMN document");
            List<String> dropped = jsonDefects(c);
            c.rollback();

            assertThat(added)
                .as("RED STATE, observed: a third jsonb column and a json column are both "
                    + "reported")
                .containsExactly("not admitted: act.planted_payload jsonb",
                    "not admitted: task.planted_metadata json");
            assertThat(dropped)
                .as("RED STATE, observed: one of the two documents missing is reported")
                .containsExactly("missing: draft_free_fields.document jsonb");
        }
    }

    // -----------------------------------------------------------------------
    // Reading.
    // -----------------------------------------------------------------------

    /** The ids the cases read of one chain from selector to act, in one tenant and scope. */
    private record Chain(UUID tenant, UUID scope, long selector, long version, long instance,
                         long task, long act) {
    }

    private static Chain insertChain(Connection c, UUID tenant, UUID scope, String name)
            throws SQLException {
        long selector = insertReturningId(c, "INSERT INTO dispatch.selector "
            + "(tenant_id, scope_id, name) VALUES (?, ?, ?) RETURNING id", tenant, scope, name);
        long version = insertReturningId(c, "INSERT INTO dispatch.definition_version "
            + "(tenant_id, scope_id, selector_id, version, verdict_required, outcome_required, "
            + "state) VALUES (?, ?, ?, 1, false, false, 'active') RETURNING id",
            tenant, scope, selector);
        long instance = insertReturningId(c, "INSERT INTO dispatch.instance "
            + "(tenant_id, scope_id, selector_id, number, definition_version_id) "
            + "VALUES (?, ?, ?, 1, ?) RETURNING id", tenant, scope, selector, version);
        long task = insertReturningId(c, "INSERT INTO dispatch.task "
            + "(tenant_id, scope_id, instance_id, sub) VALUES (?, ?, ?, 1) RETURNING id",
            tenant, scope, instance);
        long act = insertReturningId(c, "INSERT INTO dispatch.act "
            + "(tenant_id, scope_id, instance_id, sequence, task_id, kind, actor_subject, at, "
            + "to_state) VALUES (?, ?, ?, 1, ?, 'send', 'probe-subject', now(), 'open') "
            + "RETURNING id", tenant, scope, instance, task);
        return new Chain(tenant, scope, selector, version, instance, task, act);
    }

    /**
     * The ids the cases read of what V2 and V3 hang on one chain: a content
     * row and a document of free fields on its act, a draft of its task and
     * the draft's commission text, a text field of its version and a value of
     * that field on its act. The value also stands as the projection of V4 on
     * the chain's instance, read back by {@link #instanceFieldValueOf}.
     */
    private record Work(Chain chain, long content, long freeFields, long draft, long draftText,
                        long controlField, long fieldValue) {
    }

    private static Work insertChainAndWork(Connection c, UUID tenant, UUID scope, String name)
            throws SQLException {
        Chain chain = insertChain(c, tenant, scope, name);
        long content = insertReturningId(c, "INSERT INTO dispatch.content "
            + "(tenant_id, scope_id, act_id, kind, media_type, body) "
            + "VALUES (?, ?, ?, 'commission', 'text/plain', 'probe') RETURNING id",
            tenant, scope, chain.act());
        long freeFields = insertReturningId(c, "INSERT INTO dispatch.free_fields "
            + "(tenant_id, scope_id, act_id, document) "
            + "VALUES (?, ?, ?, '{\"probe\": true}'::jsonb) RETURNING id",
            tenant, scope, chain.act());
        long draft = insertReturningId(c, "INSERT INTO dispatch.draft "
            + "(tenant_id, scope_id, task_id) VALUES (?, ?, ?) RETURNING id",
            tenant, scope, chain.task());
        long draftText = insertReturningId(c, "INSERT INTO dispatch.draft_text "
            + "(tenant_id, scope_id, draft_id, kind, media_type, body) "
            + "VALUES (?, ?, ?, 'commission', 'text/plain', 'probe') RETURNING id",
            tenant, scope, draft);
        long controlField = insertReturningId(c, CONTROL_FIELD + " RETURNING id",
            tenant, scope, chain.version(), "probe_field", "text", null);
        long fieldValue = insertReturningId(c, FIELD_VALUE + " RETURNING id",
            fieldValue(new Target(tenant, scope, chain.act(), controlField), "text",
                Map.of("text_value", "probe")));
        executed(c, INSTANCE_FIELD_VALUE, tenant, scope, chain.instance(), controlField,
            fieldValue, null);
        return new Work(chain, content, freeFields, draft, draftText, controlField, fieldValue);
    }

    /** The id of the row of the projection on an instance, as the service role reads it. */
    private static long instanceFieldValueOf(Connection c, long instance) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(
                "SELECT id FROM dispatch.instance_field_value WHERE instance_id = ?")) {
            st.setLong(1, instance);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** The ids of a second instance of a chain's selector and version, with a task and an act. */
    private record Other(long instance, long task, long act) {
    }

    private static Other insertOtherInstance(Connection c, Chain chain) throws SQLException {
        long instance = insertReturningId(c, "INSERT INTO dispatch.instance "
            + "(tenant_id, scope_id, selector_id, number, definition_version_id) "
            + "VALUES (?, ?, ?, 2, ?) RETURNING id",
            chain.tenant(), chain.scope(), chain.selector(), chain.version());
        long task = insertReturningId(c, "INSERT INTO dispatch.task "
            + "(tenant_id, scope_id, instance_id, sub) VALUES (?, ?, ?, 1) RETURNING id",
            chain.tenant(), chain.scope(), instance);
        long act = insertReturningId(c, "INSERT INTO dispatch.act "
            + "(tenant_id, scope_id, instance_id, sequence, task_id, kind, actor_subject, at, "
            + "to_state) VALUES (?, ?, ?, 1, ?, 'send', 'probe-subject', now(), 'open') "
            + "RETURNING id", chain.tenant(), chain.scope(), instance, task);
        return new Other(instance, task, act);
    }

    /** An activity of the chain's version that takes no input. */
    private static long insertActivity(Connection c, Chain chain) throws SQLException {
        return insertReturningId(c, "INSERT INTO dispatch.activity "
            + "(tenant_id, scope_id, definition_version_id, name, verdict_required, "
            + "outcome_required, criteria_required, input_kind, may_ask, starts_excluded) "
            + "VALUES (?, ?, ?, 'probe-activity', false, false, false, 'none', false, false) "
            + "RETURNING id", chain.tenant(), chain.scope(), chain.version());
    }

    /** The arguments of {@link #ACT} for the second act of the chain, on its task. */
    private static Object[] act(Chain chain, String kind, String toState, String holdReason,
                                OffsetDateTime notBefore, Boolean freeTextAdmitted) {
        return act(chain, 2, kind, toState, holdReason, notBefore, freeTextAdmitted);
    }

    /** The arguments of {@link #ACT} for an act of the chain at a sequence, on its task. */
    private static Object[] act(Chain chain, int sequence, String kind, String toState,
                                String holdReason, OffsetDateTime notBefore,
                                Boolean freeTextAdmitted) {
        return new Object[] {chain.tenant(), chain.scope(), chain.instance(), sequence,
            chain.task(), kind, toState, holdReason, notBefore, freeTextAdmitted};
    }

    /**
     * The ids the cases read of the schema V3 declares on one chain's version:
     * an enumeration with one value, another enumeration with one value, and
     * one control field per type, the choice field on the first enumeration.
     */
    private record Schema(Chain chain, long enumeration, long enumerationValue,
                          long otherEnumeration, long otherEnumerationValue,
                          Map<String, Long> fields) {

        /** Where a value of the field of that type is written: the chain's act. */
        Target target(String type) {
            return new Target(chain.tenant(), chain.scope(), chain.act(), fields.get(type));
        }
    }

    private static Schema insertSchema(Connection c, UUID tenant, UUID scope, String name)
            throws SQLException {
        Chain chain = insertChain(c, tenant, scope, name);
        String enumeration = "INSERT INTO dispatch.enumeration "
            + "(tenant_id, scope_id, definition_version_id, name) VALUES (?, ?, ?, ?) RETURNING id";
        String value = "INSERT INTO dispatch.enumeration_value "
            + "(tenant_id, scope_id, enumeration_id, value, ordinal) VALUES (?, ?, ?, ?, 1) "
            + "RETURNING id";
        long status = insertReturningId(c, enumeration, tenant, scope, chain.version(), "status");
        long open = insertReturningId(c, value, tenant, scope, status, "open");
        long priority = insertReturningId(c, enumeration, tenant, scope, chain.version(),
            "priority");
        long high = insertReturningId(c, value, tenant, scope, priority, "high");
        Map<String, Long> fields = new LinkedHashMap<>();
        for (String type : TYPES) {
            fields.put(type, insertReturningId(c, CONTROL_FIELD + " RETURNING id", tenant, scope,
                chain.version(), type + "_field", type,
                "choice".equals(type) ? status : null));
        }
        return new Schema(chain, status, open, priority, high, fields);
    }

    /** The act and the control field a field value is written for, in one tenant and scope. */
    private record Target(UUID tenant, UUID scope, long act, long field) {
    }

    /**
     * The arguments of {@link #FIELD_VALUE} for a value of a type other than
     * choice: no enumeration, and the value columns named set, every other
     * one empty.
     */
    private static Object[] fieldValue(Target target, String valueType,
                                       Map<String, Object> set) {
        List<Object> args = new ArrayList<>(Arrays.asList(
            target.tenant(), target.scope(), target.act(), target.field(), valueType, null));
        VALUE_COLUMNS.forEach(column -> args.add(set.get(column)));
        return args.toArray();
    }

    /** The arguments of {@link #FIELD_VALUE} for a choice of an enumeration and a value. */
    private static Object[] choiceValue(Target target, long enumeration, long value) {
        Object[] args = fieldValue(target, "choice", Map.of("choice_value_id", value));
        args[5] = enumeration; // enumeration_id, after the four keys and value_type
        return args;
    }

    private static long insertReturningId(Connection c, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                st.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * Rows of {@code foreign} in a table, read as {@code role} bound to
     * {@code own}, inside the caller's transaction; the role is reset after.
     */
    private static long foreignRowsAs(Connection c, String role, String table, UUID own,
                                      UUID foreign) throws SQLException {
        exec(c, "SET LOCAL ROLE " + role);
        try {
            bind(c, own);
            return countOf(c, table, foreign);
        } finally {
            exec(c, "RESET ROLE");
        }
    }

    private static long countOf(Connection c, String table, UUID tenant) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(
                "SELECT count(*) FROM dispatch." + table + " WHERE tenant_id = ?")) {
            st.setObject(1, tenant);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Binds the tenant for the rest of the current transaction. */
    private static void bind(Connection c, UUID tenant) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(
                "SELECT set_config('app.tenant_id', ?, true)")) {
            st.setString(1, tenant.toString());
            st.execute();
        }
    }

    /**
     * Runs a statement and answers the SQLSTATE it was refused with; a
     * statement that ran is answered as such, never folded into a pass.
     */
    private static String refusal(Connection c, String sql, Object... args) throws SQLException {
        Savepoint before = c.setSavepoint();
        try {
            executed(c, sql, args);
            c.rollback(before);
            return NOT_REFUSED;
        } catch (SQLException refused) {
            c.rollback(before);
            return refused.getSQLState();
        }
    }

    /** Runs a statement and answers the number of rows it changed. */
    private static int executed(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                st.setObject(i + 1, args[i]);
            }
            return st.executeUpdate();
        }
    }

    /**
     * Runs a statement with the check named in force and once more with it
     * dropped, inside the caller's transaction, and answers both outcomes as
     * {@link #refusal} does. The drop is rolled back before this returns.
     */
    private static List<String> withAndWithoutCheck(Connection c, String table, String check,
                                                    String sql, Object... args)
            throws SQLException {
        String inForce = refusal(c, sql, args);
        Savepoint dropped = c.setSavepoint();
        exec(c, "ALTER TABLE dispatch." + table + " DROP CONSTRAINT " + check);
        String without = refusal(c, sql, args);
        c.rollback(dropped);
        return List.of(inForce, without);
    }

    /**
     * The policy of the order as this server reads it back: written once on a
     * planted table, inside the caller's transaction, and compared with every
     * table's. Answers USING and WITH CHECK.
     */
    private static String[] referencePolicy(Connection c) throws SQLException {
        exec(c, "CREATE TABLE dispatch.planted_reference_policy (tenant_id UUID NOT NULL)");
        exec(c, "CREATE POLICY planted_reference_policy_tenant_isolation "
            + "ON dispatch.planted_reference_policy USING (" + PREDICATE + ") "
            + "WITH CHECK (" + PREDICATE + ")");
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                SELECT qual, with_check FROM pg_policies
                WHERE schemaname = 'dispatch' AND tablename = 'planted_reference_policy'
                """)) {
            rs.next();
            return new String[] {rs.getString(1), rs.getString(2)};
        }
    }

    /** Every tenancy defect of the tables named, each beginning with its table. */
    private static List<String> tenancyDefects(Connection c, List<String> tables, String[] form)
            throws SQLException {
        List<String> defects = new ArrayList<>();
        try (PreparedStatement columnOf = c.prepareStatement("""
                 SELECT data_type, is_nullable FROM information_schema.columns
                 WHERE table_schema = 'dispatch' AND table_name = ? AND column_name = ?
                 """);
             PreparedStatement securityOf = c.prepareStatement("""
                 SELECT cl.relrowsecurity, cl.relforcerowsecurity FROM pg_class cl
                 JOIN pg_namespace n ON n.oid = cl.relnamespace
                 WHERE n.nspname = 'dispatch' AND cl.relname = ?
                 """);
             PreparedStatement policiesOf = c.prepareStatement("""
                 SELECT policyname, permissive, cmd, qual, with_check FROM pg_policies
                 WHERE schemaname = 'dispatch' AND tablename = ?
                 """)) {
            for (String table : tables) {
                for (String column : List.of("tenant_id", "scope_id")) {
                    columnOf.setString(1, table);
                    columnOf.setString(2, column);
                    try (ResultSet rs = columnOf.executeQuery()) {
                        if (!rs.next() || !"uuid".equals(rs.getString(1))
                                || !"NO".equals(rs.getString(2))) {
                            defects.add(table + ": " + column + " is not a UUID NOT NULL column");
                        }
                    }
                }
                securityOf.setString(1, table);
                try (ResultSet rs = securityOf.executeQuery()) {
                    rs.next();
                    if (!rs.getBoolean(1)) {
                        defects.add(table + ": row-level security not enabled");
                    } else if (!rs.getBoolean(2)) {
                        defects.add(table + ": row-level security not forced");
                    }
                }
                Map<String, String[]> policies = new LinkedHashMap<>();
                policiesOf.setString(1, table);
                try (ResultSet rs = policiesOf.executeQuery()) {
                    while (rs.next()) {
                        policies.put(rs.getString(1), new String[] {rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getString(5)});
                    }
                }
                String name = table + "_tenant_isolation";
                String[] policy = policies.get(name);
                if (policies.size() != 1 || policy == null) {
                    defects.add(table + ": policy " + name + " is not its one policy: "
                        + policies.keySet());
                } else if (!"PERMISSIVE".equals(policy[0]) || !"ALL".equals(policy[1])
                        || !form[0].equals(policy[2]) || !form[1].equals(policy[3])) {
                    defects.add(table + ": policy " + name + " is not in the form of the order: "
                        + String.join(" | ", policy));
                }
            }
        }
        return defects;
    }

    /**
     * Every foreign key of the schema whose two column lists do not begin with
     * tenant_id and then scope_id.
     */
    private static List<String> keysWithoutTenantAndScope(Connection c) throws SQLException {
        List<String> found = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                SELECT k.conrelid::regclass::text, k.conname,
                       (SELECT array_agg(a.attname ORDER BY i.n)
                          FROM unnest(k.conkey) WITH ORDINALITY i(att, n)
                          JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = i.att),
                       (SELECT array_agg(a.attname ORDER BY i.n)
                          FROM unnest(k.confkey) WITH ORDINALITY i(att, n)
                          JOIN pg_attribute a ON a.attrelid = k.confrelid AND a.attnum = i.att)
                FROM pg_constraint k JOIN pg_namespace n ON n.oid = k.connamespace
                WHERE n.nspname = 'dispatch' AND k.contype = 'f'
                ORDER BY 1, 2
                """)) {
            while (rs.next()) {
                String[] own = (String[]) rs.getArray(3).getArray();
                String[] target = (String[]) rs.getArray(4).getArray();
                if (!leadsWithTenantAndScope(own) || !leadsWithTenantAndScope(target)) {
                    found.add(rs.getString(1).replaceFirst("^dispatch\\.", "") + "."
                        + rs.getString(2));
                }
            }
        }
        return found;
    }

    private static boolean leadsWithTenantAndScope(String[] columns) {
        return columns.length >= 3 && "tenant_id".equals(columns[0])
            && "scope_id".equals(columns[1]);
    }

    private static List<String> jsonColumns(Connection c) throws SQLException {
        List<String> found = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                SELECT table_name, column_name, udt_name FROM information_schema.columns
                WHERE table_schema = 'dispatch' AND udt_name IN ('json', 'jsonb')
                ORDER BY table_name, column_name
                """)) {
            while (rs.next()) {
                found.add(rs.getString(1) + "." + rs.getString(2) + " " + rs.getString(3));
            }
        }
        return found;
    }

    /**
     * Every json or jsonb column of the schema other than the two documents,
     * and every one of the two documents that is not there as jsonb.
     */
    private static List<String> jsonDefects(Connection c) throws SQLException {
        List<String> found = jsonColumns(c);
        List<String> defects = new ArrayList<>();
        found.stream().filter(column -> !JSON_DOCUMENTS.contains(column))
            .forEach(column -> defects.add("not admitted: " + column));
        JSON_DOCUMENTS.stream().filter(column -> !found.contains(column))
            .forEach(column -> defects.add("missing: " + column));
        return defects;
    }

    private static List<String> baseTables(Connection c) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'dispatch' AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """)) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    private static Connection service() throws SQLException {
        Connection c = DriverManager.getConnection(url, SERVICE_ROLE, SERVICE_PASSWORD);
        c.setAutoCommit(false);
        return c;
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
