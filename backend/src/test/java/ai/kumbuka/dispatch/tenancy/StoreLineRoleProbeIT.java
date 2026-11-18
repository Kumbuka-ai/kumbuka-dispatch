/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.tenancy;

import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two role checks of the store line ({@code db/store}) refuse, each on a
 * database of its own with nothing but that line applied.
 *
 * <p>The migrator check is recognised by its application-defined SQLSTATE
 * {@code KD001}. The check of the runtime role raises without a code of its
 * own, so the only thing that tells it from any other failure is its message
 * text, and that is what is matched.
 *
 * <p>Roles are global to a cluster. The probe runs on containers of its own,
 * so the roles it stages are seen by no other test: one container for the two
 * migrator cases, which share a clean runtime role, and one for the runtime
 * role carrying BYPASSRLS, closed when that case ends.
 */
class StoreLineRoleProbeIT {

    private static final String SUPERUSER_MIGRATOR = "store_super_migrator";
    private static final String BYPASS_MIGRATOR = "store_bypass_migrator";
    private static final String CLEAN_MIGRATOR = "store_clean_migrator";
    private static final String PASSWORD = "test-only-store-role-probe-password";

    private static final String SERVICE_ROLE = "kumbuka_dispatch";
    private static final String HISTORY = "flyway_schema_history";

    /** The SQLSTATE the migrator check raises, as the order of the line names it. */
    private static final String MIGRATOR_REFUSAL = "KD001";

    /**
     * What the runtime-role check says of a role carrying BYPASSRLS and not
     * SUPERUSER. RAISE renders a boolean through its output function, as
     * {@code f} and {@code t}; the long spelling is admitted as well, so the
     * probe holds on the meaning and not on the rendering.
     */
    private static final String SERVICE_ROLE_REFUSAL =
        "kumbuka_dispatch carries superuser=f(alse)? bypassrls=t(rue)?";

    private static MigrationHarness harness;

    @BeforeAll
    static void stageMigrators() throws SQLException {
        harness = MigrationHarness.start();
        harness.createMigrator(SUPERUSER_MIGRATOR, PASSWORD, "SUPERUSER");
        harness.createMigrator(BYPASS_MIGRATOR, PASSWORD, "CREATEROLE NOSUPERUSER BYPASSRLS");
        // A clean runtime role, present before the line runs, so its check
        // passes and the migrator check is the one under observation. The
        // migrator holds ADMIN OPTION on it, which setting its search_path
        // requires of a role that did not create it.
        harness.asAdmin(
            "CREATE ROLE " + SERVICE_ROLE + " LOGIN NOSUPERUSER NOBYPASSRLS",
            "GRANT " + SERVICE_ROLE + " TO " + BYPASS_MIGRATOR + " WITH ADMIN OPTION");
    }

    @AfterAll
    static void stop() {
        if (harness != null) {
            harness.close();
        }
    }

    /**
     * A migrator carrying SUPERUSER, and one carrying BYPASSRLS, each on an
     * empty database of its own: either attribute exempts the owner of every
     * relation from every policy, and the line refuses to build under it.
     */
    @ParameterizedTest
    @ValueSource(strings = {SUPERUSER_MIGRATOR, BYPASS_MIGRATOR})
    void an_exempt_migrator_is_refused_with_kd001_and_leaves_no_table(String migrator)
            throws SQLException {
        String url = harness.freshDatabase(migrator + "_db", migrator);

        assertThatThrownBy(() -> harness.migrateLine(url, migrator, PASSWORD,
                "classpath:db/store", new TenantMigrationCallback()))
            .as("the line refuses to build under %s", migrator)
            .isInstanceOf(FlywayException.class)
            .satisfies(thrown -> assertThat(sqlStateOf(thrown))
                .as("and it is the migrator check that refuses").isEqualTo(MIGRATOR_REFUSAL));

        try (Connection c = harness.adminConnection(url)) {
            assertThat(tables(c))
                .as("nothing of the line stands afterwards; only the history table Flyway "
                    + "creates before it migrates remains")
                .containsExactly(HISTORY);
        }
    }

    @Test
    void a_runtime_role_carrying_bypassrls_stops_the_line_at_its_check() throws SQLException {
        try (MigrationHarness own = MigrationHarness.start()) {
            own.createMigrator(CLEAN_MIGRATOR, PASSWORD, "CREATEROLE NOSUPERUSER NOBYPASSRLS");
            own.asAdmin("CREATE ROLE " + SERVICE_ROLE + " LOGIN NOSUPERUSER BYPASSRLS");
            String url = own.freshDatabase("store_service_bypass", CLEAN_MIGRATOR);

            assertThatThrownBy(() -> own.migrateLine(url, CLEAN_MIGRATOR, PASSWORD,
                    "classpath:db/store", new TenantMigrationCallback()))
                .as("a runtime role carrying BYPASSRLS makes every policy of the line inert; "
                    + "the line stops rather than build them")
                .isInstanceOf(FlywayException.class)
                .satisfies(thrown -> assertThat(messageOf(thrown))
                    .as("and it is the runtime-role check that stops it: the check raises "
                        + "without a code of its own, so its text is what identifies it")
                    .containsPattern(SERVICE_ROLE_REFUSAL));

            try (Connection c = own.adminConnection(url)) {
                assertThat(tables(c))
                    .as("nothing of the line stands afterwards; only the history table Flyway "
                        + "creates before it migrates remains")
                    .containsExactly(HISTORY);
            }
        }
    }

    /** The SQLSTATE of the first SQLException in the cause chain. */
    private static String sqlStateOf(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "no SQLException in the cause chain of: " + thrown;
    }

    /** The message of the first SQLException in the cause chain. */
    private static String messageOf(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getMessage();
            }
        }
        return "no SQLException in the cause chain of: " + thrown;
    }

    private static List<String> tables(Connection c) throws SQLException {
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
}
