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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
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
 * order that specified the line; none is read from the migration.
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

    /** The twenty-four tables the line builds, as the order names them. */
    private static final Set<String> TABLES = new TreeSet<>(List.of(
        "selector", "definition_version", "definition_act", "definition_tag", "activity",
        "outcome_label",
        "instance", "task", "act",
        "commission", "commission_criterion", "holding", "receipt", "lease", "deferral", "hold",
        "question", "question_option", "answer", "delivery", "delivery_criterion",
        "instance_return", "external_reference", "idempotency_key"));

    /**
     * The foreign keys the order names, per table: 7 in the definition, 9 on
     * instance, task and act, 30 on the payloads.
     */
    private static final int FOREIGN_KEYS = 46;

    /** The policy predicate of every table, as the order writes it. */
    private static final String PREDICATE =
        "tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid";

    /** The columns the runtime role may update, per table; every other table has none. */
    private static final Map<String, Set<String>> PROJECTION = Map.of(
        "selector", Set.of("next_number"),
        "definition_version", Set.of("state"),
        "instance", Set.of("next_sub", "definition_version_id", "state", "resolution"),
        "task", Set.of("title", "activity_id", "concerns_task_id", "state", "hold_reason",
            "resolution", "not_before", "parked"),
        "holding", Set.of("ended_by_act_id"),
        "external_reference", Set.of("ended_by_act_id"));

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
    void the_line_builds_the_twenty_four_tables_on_an_empty_database_and_writes_no_row()
            throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            assertThat(scalar(c, "SELECT count(*) FROM dispatch." + HISTORY
                    + " WHERE success AND version = '1'"))
                .as("the line's single migration is recorded as applied")
                .isEqualTo("1");
        }
        Map<String, Long> expected = new TreeMap<>();
        TABLES.forEach(table -> expected.put(table, 0L));
        Map<String, Long> withoutHistory = new TreeMap<>(ROWS_AFTER_MIGRATION);
        withoutHistory.remove(HISTORY);
        assertThat(withoutHistory)
            .as("the schema holds exactly the twenty-four tables of the order, and none of them "
                + "a row: selectors are declared later by a migration of the installation")
            .isEqualTo(expected);
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
                .as("the order names forty-six keys; a different count is a key missing or one "
                    + "the order does not name")
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
    void under_the_service_role_a_tenant_reads_no_task_and_no_act_of_another()
            throws SQLException {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        try (Connection c = service()) {
            bind(c, a);
            insertChain(c, a, UUID.randomUUID(), "a");
            bind(c, b);
            insertChain(c, b, UUID.randomUUID(), "b");

            bind(c, a);
            for (String table : List.of("task", "act")) {
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
     * The red state of the isolation, on act and on task: without the policy
     * in force the runtime role reads the foreign row, and without FORCE so
     * does the owner. Each removal is made in a transaction and rolled back,
     * so it is observed on every build and undone with it.
     */
    @Test
    void without_the_policy_or_without_force_the_foreign_row_appears() throws SQLException {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        try (Connection c = harness.adminConnection(url)) {
            c.setAutoCommit(false);
            insertChain(c, a, UUID.randomUUID(), "a");
            insertChain(c, b, UUID.randomUUID(), "b");

            for (String table : List.of("task", "act")) {
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
        assertThat(tablePrivileges)
            .as("SELECT and INSERT on every table, DELETE on task alone, nothing on the history "
                + "of the migration tool, and never UPDATE, TRUNCATE, REFERENCES or TRIGGER on "
                + "a whole table")
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
                    "SELECT count(*), count(task_id) FROM dispatch.idempotency_key WHERE id = ?")) {
                st.setLong(1, key);
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    assertThat(rs.getLong(1)).as("the key outlives its draft").isEqualTo(1);
                    assertThat(rs.getLong(2)).as("and lets go of it").isZero();
                }
            }
            assertThat(refusal(c, "DELETE FROM dispatch.task WHERE id = ?", chain.task()))
                .as("a task with an act is not deleted: the key of act refuses it")
                .isEqualTo("23503");
            c.rollback();
        }
    }

    // -----------------------------------------------------------------------
    // No JSON.
    // -----------------------------------------------------------------------

    @Test
    void no_column_of_the_schema_is_json_or_jsonb() throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            assertThat(scalar(c, "SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_schema = 'dispatch'"))
                .as("the check must have columns to read").isNotEqualTo("0");
            assertThat(jsonColumns(c))
                .as("the control carries no JSON: every value has a column of its own")
                .isEmpty();

            c.setAutoCommit(false);
            exec(c, "ALTER TABLE dispatch.act ADD COLUMN planted_payload JSONB");
            exec(c, "ALTER TABLE dispatch.task ADD COLUMN planted_metadata JSON");
            List<String> planted = jsonColumns(c);
            c.rollback();
            assertThat(planted)
                .as("RED STATE, observed: a jsonb and a json column are both reported")
                .containsExactly("act.planted_payload jsonb", "task.planted_metadata json");
        }
    }

    // -----------------------------------------------------------------------
    // Reading.
    // -----------------------------------------------------------------------

    /** The ids the cases read of one chain from selector to act, in one tenant and scope. */
    private record Chain(long instance, long task, long act) {
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
        return new Chain(instance, task, act);
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
    private static String refusal(Connection c, String sql, long id) throws SQLException {
        Savepoint before = c.setSavepoint();
        try (PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, id);
            st.executeUpdate();
            c.rollback(before);
            return "not refused: the statement ran";
        } catch (SQLException refused) {
            c.rollback(before);
            return refused.getSQLState();
        }
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
