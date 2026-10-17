/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.tenancy;

import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * V16_1 against a stock that V16 left: every addendum that carries a text
 * besides its own gets that text as an addendum beside it, and loses it.
 *
 * <h2>The stock</h2>
 *
 * Two tenants, because the migration runs bound to one and reads every one.
 * In the bound tenant: an addendum with a ratified return, the shape the
 * deployed stock holds; an addendum with a question, a message and a reason
 * under a root whose next letter is already taken; two answered addenda under
 * one root, so the second takes the letter the first just took plus one; and
 * an addendum with no further text, which stays as it is. In the other
 * tenant: one addendum with a return.
 *
 * <h2>Where the expectation comes from</h2>
 *
 * Titles, texts, letters and stamps are written out below as fixed values,
 * never derived from the migrated rows: a check that read its expectation
 * from what the migration wrote would be green whatever it wrote.
 */
class AddendumAnswerMigrationIT {

    private static final String MIGRATOR = "answer_migrator";
    private static final String MIGRATOR_PASSWORD = "test-only-answer-password";
    private static final String MIGRATION = "db/migration/V16_1__addendum_answers_become_sibling_addenda.sql";

    private static final UUID TENANT_A = UUID.fromString(config("dispatch.tenant-id"));
    private static final UUID SCOPE_A = UUID.fromString(config("dispatch.scope-id"));
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID SCOPE_B = UUID.fromString("00000000-0000-0000-0000-0000000000d2");

    private static final Instant SENT = Instant.parse("2026-08-20T09:00:00Z");
    private static final Instant RATIFIED = Instant.parse("2026-08-21T09:00:00Z");

    private static MigrationHarness harness;
    private static String url;

    /** Every row of the stock, by address, as staged and read back before V16_1. */
    private static Map<String, Map<String, Object>> before;
    /** The same after V16_1. */
    private static Map<String, Map<String, Object>> after;

    @BeforeAll
    static void migrateAStockedDatabase() throws SQLException {
        harness = MigrationHarness.start();
        harness.createMigrator(MIGRATOR, MIGRATOR_PASSWORD, "CREATEROLE NOSUPERUSER NOBYPASSRLS");
        url = harness.freshDatabase("addendum_answers", MIGRATOR);
        harness.migrateTo(url, MIGRATOR, MIGRATOR_PASSWORD, "16", new TenantMigrationCallback());

        try (Connection c = harness.adminConnection(url)) {
            long a = selector(c, TENANT_A, SCOPE_A);
            long b = selector(c, TENANT_B, SCOPE_B);

            insert(c, TENANT_A, a, 1, null, "closed", ", return_body, ratified_at",
                ", 'the return of one', " + ts(RATIFIED));
            insert(c, TENANT_A, a, 1, "a", "closed", ", return_body, ratified_at",
                ", 'the answer to one a', " + ts(RATIFIED));

            insert(c, TENANT_A, a, 2, null, "open", "", "");
            insert(c, TENANT_A, a, 2, "a", "open",
                ", executor_question, commissioner_message, termination_reason",
                ", 'which way', 'this way', 'no longer needed'");
            insert(c, TENANT_A, a, 2, "b", "open", "", "");

            insert(c, TENANT_A, a, 3, null, "consumed", ", return_body, ratified_at",
                ", 'the return of three', " + ts(RATIFIED));
            insert(c, TENANT_A, a, 3, "a", "closed", ", return_body, ratified_at",
                ", 'the answer to three a', " + ts(RATIFIED));
            insert(c, TENANT_A, a, 3, "b", "closed", ", return_body, executor_question, ratified_at",
                ", 'the answer to three b', 'and one more thing', " + ts(RATIFIED));

            insert(c, TENANT_A, a, 4, null, "open", "", "");
            insert(c, TENANT_A, a, 4, "a", "open", "", "");

            insert(c, TENANT_B, b, 1, null, "closed", "", "");
            insert(c, TENANT_B, b, 1, "a", "closed", ", return_body, ratified_at",
                ", 'the answer in the other tenant', " + ts(RATIFIED));

            before = stock(c);
            assertThat(catalogue(c))
                .as("the stock is staged under forced row security and an armed freeze, as a "
                    + "deployed database has it")
                .isEqualTo("force=true selector=true freeze=O");
        }

        harness.migrateTo(url, MIGRATOR, MIGRATOR_PASSWORD, "16.1", new TenantMigrationCallback());

        try (Connection c = harness.adminConnection(url)) {
            after = stock(c);
        }
    }

    @AfterAll
    static void stopDatabase() {
        if (harness != null) {
            harness.close();
        }
    }

    // ------------------------------------------------------------------
    // What V16_1 writes
    // ------------------------------------------------------------------

    @Test
    void each_answer_stands_as_an_addendum_beside_the_one_it_answers() {
        assertThat(after.keySet()).as("five new addenda, at the next free letter of their root")
            .containsExactlyInAnyOrderElementsOf(concat(before.keySet(),
                "A 1.0b", "A 2.0c", "A 3.0c", "A 3.0d", "B 1.0b"));

        assertAnswer("A 1.0b", "A 1.0a", "Antwort auf sprint/1.0a",
            "## Rückgabe\n\nthe answer to one a");
        assertAnswer("A 2.0c", "A 2.0a", "Antwort auf sprint/2.0a",
            "## Frage\n\nwhich way\n\n## Nachricht\n\nthis way\n\n## Grund\n\nno longer needed");
        assertAnswer("A 3.0c", "A 3.0a", "Antwort auf sprint/3.0a",
            "## Rückgabe\n\nthe answer to three a");
        assertAnswer("A 3.0d", "A 3.0b", "Antwort auf sprint/3.0b",
            "## Rückgabe\n\nthe answer to three b\n\n## Frage\n\nand one more thing");
        assertAnswer("B 1.0b", "B 1.0a", "Antwort auf sprint/1.0a",
            "## Rückgabe\n\nthe answer in the other tenant");
    }

    @Test
    void the_answered_addendum_loses_its_four_texts_and_keeps_everything_else() {
        for (String answered : List.of("A 1.0a", "A 2.0a", "A 3.0a", "A 3.0b", "B 1.0a")) {
            Map<String, Object> was = new LinkedHashMap<>(before.get(answered));
            Map<String, Object> is = new LinkedHashMap<>(after.get(answered));
            for (String emptied : List.of("return_body", "executor_question",
                    "commissioner_message", "termination_reason")) {
                assertThat(is.get(emptied)).as("%s: %s is emptied", answered, emptied).isNull();
                was.remove(emptied);
                is.remove(emptied);
            }
            // The change stamp is the table trigger's: the row was written.
            was.remove("updated_at");
            is.remove("updated_at");
            assertThat(is).as("%s: every other column as it was, ratified_at included",
                answered).isEqualTo(was);
        }
    }

    @Test
    void a_row_with_nothing_to_answer_is_not_touched() {
        for (String untouched : List.of("A 1.0", "A 2.0", "A 2.0b", "A 3.0", "A 4.0", "A 4.0a",
                "B 1.0")) {
            assertThat(after.get(untouched)).as("%s as it was", untouched)
                .isEqualTo(before.get(untouched));
        }
    }

    @Test
    void the_table_has_its_forced_security_and_its_freeze_back() throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            assertThat(catalogue(c)).isEqualTo("force=true selector=true freeze=O");
        }
    }

    @Test
    void a_second_run_changes_nothing() throws SQLException, IOException {
        try (Connection c = java.sql.DriverManager.getConnection(url, MIGRATOR, MIGRATOR_PASSWORD);
             Statement s = c.createStatement()) {
            s.execute(migrationText());
        }
        try (Connection c = harness.adminConnection(url)) {
            assertThat(stock(c)).as("the stock after a second run is the stock after the first")
                .isEqualTo(after);
        }
    }

    @Test
    void the_copy_then_takes_each_answer_as_an_addendum_text_of_its_task() throws SQLException {
        String copied = harness.freshDatabase("addendum_answers_copied", MIGRATOR);
        harness.migrateTo(copied, MIGRATOR, MIGRATOR_PASSWORD, "16", new TenantMigrationCallback());
        try (Connection c = harness.adminConnection(copied)) {
            long a = selector(c, TENANT_A, SCOPE_A);
            insert(c, TENANT_A, a, 1, null, "closed", "", "");
            insert(c, TENANT_A, a, 1, "a", "closed", ", return_body, ratified_at",
                ", 'the answer to one a', " + ts(RATIFIED));
        }

        harness.migrate(copied, MIGRATOR, MIGRATOR_PASSWORD, new TenantMigrationCallback());

        try (Connection c = harness.adminConnection(copied)) {
            assertThat(rows(c, "SELECT x.addendum_suffix AS suffix, x.text FROM dispatch.task_text x "
                    + "JOIN dispatch.task t ON t.id = x.task_id WHERE t.number = 1 "
                    + "AND x.addendum_suffix IS NOT NULL ORDER BY x.addendum_suffix"))
                .as("V17 runs through and copies the answer as the addendum text b of task 1")
                .containsExactly(
                    Map.of("suffix", "a", "text", "addendum 1a\n\ncommission 1a"),
                    Map.of("suffix", "b", "text",
                        "Antwort auf sprint/1.0a\n\n## Rückgabe\n\nthe answer to one a"));
        }
    }

    // ------------------------------------------------------------------
    // Where V16_1 does not write, and where it stops
    // ------------------------------------------------------------------

    @Test
    void an_answer_that_already_stands_is_skipped() throws SQLException {
        String skipped = harness.freshDatabase("addendum_answers_skipped", MIGRATOR);
        harness.migrateTo(skipped, MIGRATOR, MIGRATOR_PASSWORD, "16", new TenantMigrationCallback());
        Map<String, Map<String, Object>> staged;
        try (Connection c = harness.adminConnection(skipped)) {
            long a = selector(c, TENANT_A, SCOPE_A);
            insert(c, TENANT_A, a, 5, null, "closed", "", "");
            insert(c, TENANT_A, a, 5, "a", "closed", ", return_body, ratified_at",
                ", 'answered before', " + ts(RATIFIED));
            insert(c, TENANT_A, a, 5, "b", "closed", ", title", ", 'Antwort auf sprint/5.0a'");
            staged = stock(c);
        }

        harness.migrateTo(skipped, MIGRATOR, MIGRATOR_PASSWORD, "16.1", new TenantMigrationCallback());

        try (Connection c = harness.adminConnection(skipped)) {
            assertThat(stock(c)).as("no second answer, and the answered row left as it is")
                .isEqualTo(staged);
        }
    }

    @Test
    void a_root_without_a_free_letter_stops_the_run() throws SQLException {
        String full = harness.freshDatabase("addendum_answers_full", MIGRATOR);
        harness.migrateTo(full, MIGRATOR, MIGRATOR_PASSWORD, "16", new TenantMigrationCallback());
        try (Connection c = harness.adminConnection(full)) {
            long a = selector(c, TENANT_A, SCOPE_A);
            insert(c, TENANT_A, a, 6, null, "closed", "", "");
            insert(c, TENANT_A, a, 6, "a", "closed", ", return_body, ratified_at",
                ", 'no room for me', " + ts(RATIFIED));
            for (char letter = 'b'; letter <= 'z'; letter++) {
                insert(c, TENANT_A, a, 6, String.valueOf(letter), "closed", "", "");
            }
        }

        FlywayException stopped = catchThrowableOfType(FlywayException.class, () -> harness
            .migrateTo(full, MIGRATOR, MIGRATOR_PASSWORD, "16.1", new TenantMigrationCallback()));

        assertThat(stopped).as("V16_1 must stop rather than wrap past z").isNotNull();
        PSQLException cause = rootSqlError(stopped);
        assertThat((Throwable) cause).as("the stop is a database refusal: %s", stopped.getMessage())
            .isNotNull();
        assertThat(cause.getSQLState()).isEqualTo("KD003");
        assertThat(cause.getServerErrorMessage().getMessage())
            .isEqualTo("V16_1 stops: sprint/6.0 has no free addendum letter after z");
    }

    // ------------------------------------------------------------------

    private static void assertAnswer(String at, String answered, String title, String text) {
        Map<String, Object> answer = after.get(at);
        Map<String, Object> origin = before.get(answered);
        assertThat(answer.get("title")).as("%s: title", at).isEqualTo(title);
        assertThat(answer.get("dispatch_body")).as("%s: text", at).isEqualTo(text);
        assertThat(answer.get("apparatus")).as("%s: addressed to the commissioning side", at)
            .isEqualTo("concept");
        for (String inherited : List.of("status", "dispatch_date", "sent_at", "created_at",
                "created_by", "updated_at", "updated_by")) {
            assertThat(answer.get(inherited)).as("%s: %s is the answered addendum's", at, inherited)
                .isEqualTo(origin.get(inherited));
        }
        for (String empty : List.of("return_body", "executor_question", "commissioner_message",
                "termination_reason", "ratified_at", "return_metadata", "dispatch_metadata",
                "holder_subject", "curated_into_id")) {
            assertThat(answer.get(empty)).as("%s: %s is empty", at, empty).isNull();
        }
    }

    /** One exchange row; extra columns and values come with a leading comma. */
    private static void insert(Connection c, UUID tenant, long selector, int number, String suffix,
                               String status, String columns, String values) throws SQLException {
        String address = number + ".0" + (suffix == null ? "" : suffix);
        // Distinct stamps per row, so a row carrying another row's is told apart.
        Instant created = Instant.parse("2026-08-01T08:00:00Z").plusSeconds(number * 3600L
            + (suffix == null ? 0 : suffix.charAt(0) * 60L));
        boolean titled = columns.contains("title");
        try (PreparedStatement st = c.prepareStatement("INSERT INTO dispatch.exchange (tenant_id, "
                + "scope_id, selector_id, number, sub, addendum_suffix, status, "
                + (titled ? "" : "title, ") + "dispatch_body, apparatus, dispatch_date, sent_at, "
                + "created_at, created_by, updated_at, updated_by" + columns + ") VALUES "
                + "(?, ?, ?, ?, 0, ?, ?, " + (titled ? "" : "?, ") + "?, 'code', "
                + "DATE '2026-08-19', ?, ?, ?, ?, ?" + values + ")")) {
            int i = 1;
            st.setObject(i++, tenant);
            st.setObject(i++, tenant.equals(TENANT_A) ? SCOPE_A : SCOPE_B);
            st.setLong(i++, selector);
            st.setInt(i++, number);
            st.setString(i++, suffix);
            st.setString(i++, status);
            if (!titled) {
                st.setString(i++, suffix == null ? "task " + number : "addendum " + number + suffix);
            }
            st.setString(i++, "commission " + number + (suffix == null ? "" : suffix));
            st.setTimestamp(i++, Timestamp.from(SENT));
            st.setTimestamp(i++, Timestamp.from(created));
            st.setString(i++, "author " + address);
            st.setTimestamp(i++, Timestamp.from(created.plusSeconds(86_400)));
            st.setString(i++, "changer " + address);
            st.executeUpdate();
        }
    }

    private static long selector(Connection c, UUID tenant, UUID scope) throws SQLException {
        if (!tenant.equals(TENANT_A)) {
            exec(c, "INSERT INTO dispatch.selector (tenant_id, scope_id, name) VALUES ('" + tenant
                + "', '" + scope + "', 'sprint')");
        }
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT id FROM dispatch.selector WHERE tenant_id = '" + tenant + "' AND scope_id = '"
                    + scope + "' AND name = 'sprint'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Every row of exchange, keyed by tenant letter and address, across both tenants. */
    private static Map<String, Map<String, Object>> stock(Connection c) throws SQLException {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows(c, "SELECT * FROM dispatch.exchange ORDER BY id")) {
            String tenant = TENANT_A.equals(row.get("tenant_id")) ? "A" : "B";
            out.put(tenant + " " + row.get("number") + "." + row.get("sub")
                + (row.get("addendum_suffix") == null ? "" : row.get("addendum_suffix")), row);
        }
        return out;
    }

    private static String catalogue(Connection c) throws SQLException {
        List<Map<String, Object>> r = rows(c, "SELECT (SELECT relforcerowsecurity FROM pg_class "
            + "WHERE oid = 'dispatch.exchange'::regclass)::text AS force, (SELECT "
            + "relforcerowsecurity FROM pg_class WHERE oid = 'dispatch.selector'::regclass)::text "
            + "AS selector, (SELECT tgenabled::text "
            + "FROM pg_trigger WHERE tgrelid = 'dispatch.exchange'::regclass "
            + "AND tgname = 'exchange_freeze') AS freeze");
        return "force=" + r.get(0).get("force") + " selector=" + r.get(0).get("selector")
            + " freeze=" + r.get(0).get("freeze");
    }

    private static List<Map<String, Object>> rows(Connection c, String sql) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columns; i++) {
                    Object value = rs.getObject(i);
                    if (value instanceof org.postgresql.util.PGobject pg) {
                        value = pg.getValue();
                    } else if (value instanceof Timestamp t) {
                        value = t.toInstant().toString();
                    }
                    row.put(rs.getMetaData().getColumnName(i), value);
                }
                out.add(row);
            }
        }
        return out;
    }

    private static String migrationText() throws IOException {
        try (InputStream in = AddendumAnswerMigrationIT.class.getClassLoader()
                .getResourceAsStream(MIGRATION)) {
            assertThat(in).as("the migration %s is on the class path", MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> concat(java.util.Set<String> keys, String... more) {
        List<String> all = new ArrayList<>(keys);
        all.addAll(List.of(more));
        return all;
    }

    private static String ts(Instant at) {
        return "TIMESTAMPTZ '" + at + "'";
    }

    private static PSQLException rootSqlError(Throwable t) {
        for (Throwable at = t; at != null; at = at.getCause()) {
            if (at instanceof PSQLException e) {
                return e;
            }
        }
        return null;
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String config(String key) {
        return ConfigProvider.getConfig().getValue(key, String.class);
    }
}
