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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V6 of the store line, on databases of their own: an empty one, and one that
 * stands in the state V1 to V5 left, filled with the rows the service of T3
 * writes -- an instance, a task in draft with its draft, and a key on it.
 *
 * <p>Every expectation is written here, from the order of V6 and REA-0010 D29
 * and D30: two columns, each nullable or defaulted, and two grants, and
 * nothing else; the rows that stood before stand after, the draft with a
 * token, the key without a digest. The red state is the state before V6, read
 * on the filled database before it runs: neither column is there, and the
 * runtime role holds neither grant.
 */
class StoreLineDraftTokenIT {

    private static final String MIGRATOR = "draft_token_migrator";
    private static final String MIGRATOR_PASSWORD = "test-only-draft-token-password";
    private static final String LINE = "classpath:db/store";
    private static final String SERVICE_ROLE = "kumbuka_dispatch";

    private static MigrationHarness harness;

    @BeforeAll
    static void start() throws SQLException {
        harness = MigrationHarness.start();
        harness.createMigrator(MIGRATOR, MIGRATOR_PASSWORD, "CREATEROLE NOSUPERUSER NOBYPASSRLS");
    }

    @AfterAll
    static void stop() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void v6_runs_on_an_empty_database_and_adds_two_columns_and_two_grants() throws SQLException {
        String url = harness.freshDatabase("draft_token_empty", MIGRATOR);
        harness.migrateLine(url, MIGRATOR, MIGRATOR_PASSWORD, LINE, new TenantMigrationCallback());

        try (Connection c = harness.adminConnection(url)) {
            assertThat(scalar(c, "SELECT string_agg(version, ',' ORDER BY installed_rank) "
                    + "FROM dispatch.flyway_schema_history WHERE success AND version IS NOT NULL"))
                .as("the line stands at V6").isEqualTo("1,2,3,4,5,6");
            assertThat(column(c, "draft", "changed_at"))
                .as("the token of a draft: a time, never empty, by default the store's time")
                .isEqualTo("timestamp with time zone NO now()");
            assertThat(column(c, "idempotency_key", "argument_digest"))
                .as("the digest of a key: a text, empty where no row wrote one")
                .isEqualTo("text YES null");
            assertThat(grants(c)).as("UPDATE of the token, and DELETE of a key")
                .containsExactly(true, true);
        }
    }

    @Test
    void v6_runs_on_the_state_t3_left_and_keeps_every_row() throws SQLException {
        String url = harness.freshDatabase("draft_token_filled", MIGRATOR);
        harness.migrateLineTo(url, MIGRATOR, MIGRATOR_PASSWORD, LINE, "5",
            new TenantMigrationCallback());
        UUID tenant = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        try (Connection c = harness.adminConnection(url)) {
            fill(c, tenant, scope);
            assertThat(List.of(column(c, "draft", "changed_at"),
                    column(c, "idempotency_key", "argument_digest")))
                .as("RED STATE, observed: before V6 neither column is there")
                .containsExactly("absent", "absent");
            assertThat(grants(c)).as("and the runtime role holds neither grant")
                .containsExactly(false, false);
        }

        harness.migrateLine(url, MIGRATOR, MIGRATOR_PASSWORD, LINE, new TenantMigrationCallback());

        try (Connection c = harness.adminConnection(url)) {
            assertThat(scalar(c, "SELECT string_agg(version, ',' ORDER BY installed_rank) "
                    + "FROM dispatch.flyway_schema_history WHERE success AND version IS NOT NULL"))
                .as("V6 ran on the filled database").isEqualTo("1,2,3,4,5,6");
            assertThat(scalar(c, "SELECT count(*) || ' ' || bool_and(changed_at IS NOT NULL) "
                    + "FROM dispatch.draft WHERE tenant_id = '" + tenant + "'"))
                .as("the draft that stood stands, and has a token").isEqualTo("1 true");
            assertThat(scalar(c, "SELECT count(*) || ' ' || bool_and(argument_digest IS NULL) "
                    + "|| ' ' || bool_and(task_id IS NOT NULL) FROM dispatch.idempotency_key "
                    + "WHERE tenant_id = '" + tenant + "'"))
                .as("the key that stood stands, on its task, without a digest")
                .isEqualTo("1 true true");
            assertThat(scalar(c, "SELECT count(*) FROM dispatch.task WHERE tenant_id = '"
                    + tenant + "' AND state = 'draft'"))
                .as("and so does its task").isEqualTo("1");
            assertThat(grants(c)).containsExactly(true, true);
        }
    }

    // -----------------------------------------------------------------------

    /**
     * The rows the service of T3 writes, with the columns it names: a
     * selector, a version, an instance, a task in draft, its draft and a key.
     */
    private static void fill(Connection c, UUID tenant, UUID scope) throws SQLException {
        String at = "'" + tenant + "', '" + scope + "'";
        long selector = id(c, "INSERT INTO dispatch.selector (tenant_id, scope_id, name) "
            + "VALUES (" + at + ", 'sprint') RETURNING id");
        long version = id(c, "INSERT INTO dispatch.definition_version (tenant_id, scope_id, "
            + "selector_id, version, verdict_required, outcome_required, state, seed) VALUES ("
            + at + ", " + selector + ", 1, false, false, 'active', true) RETURNING id");
        long instance = id(c, "INSERT INTO dispatch.instance (tenant_id, scope_id, selector_id, "
            + "number, definition_version_id, title) VALUES (" + at + ", " + selector + ", 1, "
            + version + ", 'an instance') RETURNING id");
        long task = id(c, "INSERT INTO dispatch.task (tenant_id, scope_id, instance_id, sub) "
            + "VALUES (" + at + ", " + instance + ", 1) RETURNING id");
        id(c, "INSERT INTO dispatch.draft (tenant_id, scope_id, task_id, title) VALUES (" + at
            + ", " + task + ", 'a draft') RETURNING id");
        id(c, "INSERT INTO dispatch.idempotency_key (tenant_id, scope_id, caller_subject, "
            + "idempotency_key, task_id, created_at) VALUES (" + at + ", 'probe', 'a key', " + task
            + ", now()) RETURNING id");
    }

    /** Type, nullability and default of a column; "absent" where the table has none of the name. */
    private static String column(Connection c, String table, String column) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT data_type || ' ' || is_nullable || ' ' || coalesce(column_default, 'null') "
                    + "FROM information_schema.columns WHERE table_schema = 'dispatch' "
                    + "AND table_name = '" + table + "' AND column_name = '" + column + "'")) {
            return rs.next() ? rs.getString(1) : "absent";
        }
    }

    /** Whether the runtime role may update the token of a draft, and delete a key. */
    private static List<Boolean> grants(Connection c) throws SQLException {
        List<Boolean> held = new ArrayList<>();
        held.add(!"absent".equals(column(c, "draft", "changed_at"))
            && bool(c, "SELECT has_column_privilege('" + SERVICE_ROLE + "', 'dispatch.draft', "
                + "'changed_at', 'UPDATE')"));
        held.add(bool(c, "SELECT has_table_privilege('" + SERVICE_ROLE
            + "', 'dispatch.idempotency_key', 'DELETE')"));
        return held;
    }

    private static boolean bool(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private static long id(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
