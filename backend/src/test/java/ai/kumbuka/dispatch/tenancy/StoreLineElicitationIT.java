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
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The elicitation complex of V5 of the store line, on a database of its own
 * with the line db/store V1 to V5 applied.
 *
 * <p>Every expectation is written here, from the order of V5: the five
 * tables with their columns, the two marks on act, what V5 removes, the
 * tenancy and privileges of each new table, and what the store refuses and
 * takes. None is read from the migration.
 *
 * <p>Each refusal is shown with its red state: the same statement with a
 * valid value is taken, so the refusal is the value and not the row. The
 * statements run under the runtime role, bound to a tenant, in a transaction
 * that is rolled back.
 */
class StoreLineElicitationIT {

    private static final String MIGRATOR = "elicitation_migrator";
    private static final String MIGRATOR_PASSWORD = "test-only-elicitation-password";

    /** The runtime role, created by the line on a cold start with its placeholder password. */
    private static final String SERVICE_ROLE = "kumbuka_dispatch";
    private static final String SERVICE_PASSWORD = "change-me-kumbuka-dispatch";

    private static final String NOT_REFUSED = "not refused: the statement ran";
    private static final String CHECK_VIOLATED = "23514";
    private static final String UNIQUE_VIOLATED = "23505";
    private static final String KEY_VIOLATED = "23503";

    private static final String ID = "id bigint NO";
    private static final String TENANT = "tenant_id uuid NO";
    private static final String SCOPE = "scope_id uuid NO";

    /** The five tables of the complex with their columns, as the order names them. */
    private static final Map<String, List<String>> COMPLEX = new LinkedHashMap<>();

    static {
        COMPLEX.put("elicitation_question", List.of(ID, TENANT, SCOPE, "ask_act_id bigint NO",
            "identifier text NO", "ordinal integer NO", "selection text NO",
            "free_text_admitted boolean NO", "required boolean NO"));
        COMPLEX.put("elicitation_option", List.of(ID, TENANT, SCOPE, "question_id bigint NO",
            "identifier text NO", "label text NO", "ordinal integer NO"));
        COMPLEX.put("elicitation_answer", List.of(ID, TENANT, SCOPE, "answer_act_id bigint NO",
            "question_id bigint NO"));
        COMPLEX.put("elicitation_answer_option", List.of(ID, TENANT, SCOPE,
            "answer_id bigint NO", "question_id bigint NO", "option_id bigint NO"));
        COMPLEX.put("elicitation_content", List.of(ID, TENANT, SCOPE, "act_id bigint NO",
            "kind text NO", "question_id bigint YES", "answer_id bigint YES",
            "supplements_content_id bigint YES", "media_type text NO", "body text NO"));
    }

    /** The kinds of content after V5: those of V4 without question and answer. */
    private static final Set<String> CONTENT_KINDS = new TreeSet<>(List.of(
        "commission", "criterion", "criterion_evidence", "decision_basis", "return",
        "translation_basis", "remark", "addendum", "instance_return",
        "instance_translation_basis", "instance_input"));

    private static final String QUESTION = "INSERT INTO dispatch.elicitation_question "
        + "(tenant_id, scope_id, ask_act_id, identifier, ordinal, selection, free_text_admitted, "
        + "required) VALUES (?, ?, ?, ?, ?, ?, ?, true)";
    private static final String OPTION = "INSERT INTO dispatch.elicitation_option "
        + "(tenant_id, scope_id, question_id, identifier, label, ordinal) VALUES (?, ?, ?, ?, ?, ?)";
    private static final String ANSWER = "INSERT INTO dispatch.elicitation_answer "
        + "(tenant_id, scope_id, answer_act_id, question_id) VALUES (?, ?, ?, ?)";
    private static final String CHOSEN = "INSERT INTO dispatch.elicitation_answer_option "
        + "(tenant_id, scope_id, answer_id, question_id, option_id) VALUES (?, ?, ?, ?, ?)";
    private static final String TEXT = "INSERT INTO dispatch.elicitation_content "
        + "(tenant_id, scope_id, act_id, kind, question_id, supplements_content_id, media_type, "
        + "body) VALUES (?, ?, ?, ?, ?, ?, 'text/plain', ?)";
    private static final String ACT = "INSERT INTO dispatch.act "
        + "(tenant_id, scope_id, instance_id, sequence, task_id, kind, actor_subject, at, "
        + "to_state, human_required, answer_outcome) "
        + "VALUES (?, ?, ?, ?, ?, ?, 'probe-subject', now(), ?, ?, ?)";

    private static MigrationHarness harness;
    private static String url;

    @BeforeAll
    static void migrateTheLine() throws SQLException {
        harness = MigrationHarness.start();
        harness.createMigrator(MIGRATOR, MIGRATOR_PASSWORD, "CREATEROLE NOSUPERUSER NOBYPASSRLS");
        url = harness.freshDatabase("store_line_elicitation", MIGRATOR);
        harness.migrateLine(url, MIGRATOR, MIGRATOR_PASSWORD, "classpath:db/store",
            new TenantMigrationCallback());
    }

    @AfterAll
    static void stop() {
        if (harness != null) {
            harness.close();
        }
    }

    // -----------------------------------------------------------------------
    // The form after V5, read from the catalog.
    // -----------------------------------------------------------------------

    @Test
    void the_line_to_v5_builds_the_five_tables_of_the_complex_and_removes_what_they_replace()
            throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            assertThat(strings(c, "SELECT string_agg(version, ',' ORDER BY installed_rank) "
                    + "FROM dispatch.flyway_schema_history WHERE success AND version IS NOT NULL"))
                .as("the line is applied from V1 to V6")
                .containsExactly("1,2,3,4,5,6");
            Map<String, List<String>> columns = new LinkedHashMap<>();
            for (String table : COMPLEX.keySet()) {
                columns.put(table, columnsOf(c, table));
            }
            assertThat(columns)
                .as("the five tables of the complex, each with exactly the columns of the order")
                .isEqualTo(COMPLEX);
            assertThat(strings(c, "SELECT column_name || ' ' || data_type || ' ' || is_nullable "
                    + "FROM information_schema.columns WHERE table_schema = 'dispatch' "
                    + "AND table_name = 'act' AND column_name IN ('human_required', "
                    + "'answer_outcome', 'free_text_admitted') ORDER BY ordinal_position"))
                .as("act carries human_required and answer_outcome and no longer "
                    + "free_text_admitted")
                .containsExactly("human_required boolean YES", "answer_outcome text YES");
            assertThat(strings(c, "SELECT table_name FROM information_schema.tables "
                    + "WHERE table_schema = 'dispatch' AND table_name IN ('question_option', "
                    + "'answer')"))
                .as("question_option and answer of V4 are gone").isEmpty();
            assertThat(kindsOf(c, "ck_content_kind"))
                .as("ck_content_kind admits the kinds of V4 without question and answer")
                .isEqualTo(CONTENT_KINDS);
        }
    }

    @Test
    void every_table_of_the_complex_carries_the_tenant_a_forced_policy_and_select_and_insert_alone()
            throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            List<String> defects = new ArrayList<>();
            for (String table : COMPLEX.keySet()) {
                defects.addAll(tenancyAndPrivilegeDefects(c, table));
            }
            assertThat(defects)
                .as("tenant_id and scope_id UUID NOT NULL, row-level security enabled and "
                    + "forced, the one policy <table>_tenant_isolation, and for the runtime role "
                    + "exactly SELECT and INSERT on the table and no column privilege")
                .isEmpty();

            c.setAutoCommit(false);
            exec(c, "DROP POLICY elicitation_question_tenant_isolation "
                + "ON dispatch.elicitation_question");
            exec(c, "ALTER TABLE dispatch.elicitation_option NO FORCE ROW LEVEL SECURITY");
            exec(c, "GRANT UPDATE ON dispatch.elicitation_answer TO " + SERVICE_ROLE);
            exec(c, "REVOKE INSERT ON dispatch.elicitation_answer_option FROM " + SERVICE_ROLE);
            exec(c, "GRANT UPDATE (body) ON dispatch.elicitation_content TO " + SERVICE_ROLE);
            List<String> planted = new ArrayList<>();
            for (String table : COMPLEX.keySet()) {
                planted.addAll(tenancyAndPrivilegeDefects(c, table));
            }
            c.rollback();

            assertThat(planted)
                .as("RED STATE, observed: a dropped policy, an unforced table, a table "
                    + "privilege too many, one too few and a column privilege are each reported")
                .containsExactly(
                    "elicitation_question: policies []",
                    "elicitation_option: row-level security enabled, forced: true false",
                    "elicitation_answer: privileges [INSERT, SELECT, UPDATE]",
                    "elicitation_answer_option: privileges [SELECT]",
                    "elicitation_content: column privileges [UPDATE body]");
        }
    }

    // -----------------------------------------------------------------------
    // What the store refuses, each with its red state, and what it takes.
    // -----------------------------------------------------------------------

    @Test
    void the_store_refuses_a_second_question_option_or_answer_of_one_name_and_takes_two_answers()
            throws SQLException {
        try (Connection c = service()) {
            Bundle b = insertBundle(c);

            assertThat(outcomes(c, QUESTION,
                    args(b.tenant(), b.scope(), b.ask(), "q1", 3, "one", false),
                    args(b.tenant(), b.scope(), b.ask(), "q3", 3, "one", false)))
                .as("a second question q1 in the bundle is refused; RED STATE, observed: the "
                    + "same question as q3 is taken")
                .containsExactly(UNIQUE_VIOLATED, NOT_REFUSED);
            assertThat(outcomes(c, OPTION,
                    args(b.tenant(), b.scope(), b.q1(), "o1", "no", 2),
                    args(b.tenant(), b.scope(), b.q1(), "o2", "no", 2)))
                .as("a second option o1 of the question is refused; RED STATE, observed: the "
                    + "same option as o2 is taken")
                .containsExactly(UNIQUE_VIOLATED, NOT_REFUSED);
            assertThat(outcomes(c, ANSWER,
                    args(b.tenant(), b.scope(), b.answer(), b.q1()),
                    args(b.tenant(), b.scope(), b.answer(), b.q2())))
                .as("a second answer to q1 in the same answer act is refused; RED STATE, "
                    + "observed: the same answer to q2 is taken")
                .containsExactly(UNIQUE_VIOLATED, NOT_REFUSED);

            executed(c, ANSWER, b.tenant(), b.scope(), b.answer(), b.q2());
            assertThat(strings(c, "SELECT q.identifier FROM dispatch.elicitation_answer w "
                    + "JOIN dispatch.elicitation_question q ON q.id = w.question_id "
                    + "WHERE w.answer_act_id = " + b.answer() + " ORDER BY q.ordinal"))
                .as("one answer act holds two answers to two questions of the same ask act")
                .containsExactly("q1", "q2");
            c.rollback();
        }
    }

    @Test
    void the_store_refuses_an_option_of_another_question_and_selection_none_without_free_text()
            throws SQLException {
        try (Connection c = service()) {
            Bundle b = insertBundle(c);
            long toSecond = insertReturningId(c, ANSWER + " RETURNING id",
                b.tenant(), b.scope(), b.answer(), b.q2());

            assertThat(outcomes(c, CHOSEN,
                    args(b.tenant(), b.scope(), toSecond, b.q2(), b.o1()),
                    args(b.tenant(), b.scope(), b.a1(), b.q1(), b.o1())))
                .as("an answer to q2 choosing the option o1 of q1 is refused by the key that "
                    + "carries the question; RED STATE, observed: o1 chosen by the answer to q1 "
                    + "is taken")
                .containsExactly(KEY_VIOLATED, NOT_REFUSED);
            assertThat(outcomes(c, QUESTION,
                    args(b.tenant(), b.scope(), b.ask(), "q3", 3, "none", false),
                    args(b.tenant(), b.scope(), b.ask(), "q3", 3, "none", true)))
                .as("a question with selection none that refuses free text is refused; RED "
                    + "STATE, observed: the same question admitting free text is taken")
                .containsExactly(CHECK_VIOLATED, NOT_REFUSED);
            c.rollback();
        }
    }

    @Test
    void the_store_refuses_a_text_outside_its_form_and_a_question_text_in_content()
            throws SQLException {
        try (Connection c = service()) {
            Bundle b = insertBundle(c);

            assertThat(outcomes(c, TEXT,
                    args(b.tenant(), b.scope(), b.ask(), "question", b.q2(), null,
                        "x".repeat(2001)),
                    args(b.tenant(), b.scope(), b.ask(), "question", b.q2(), null,
                        "x".repeat(2000))))
                .as("a question text of 2001 characters is refused; RED STATE, observed: one of "
                    + "2000 is taken")
                .containsExactly(CHECK_VIOLATED, NOT_REFUSED);
            assertThat(outcomes(c, TEXT,
                    args(b.tenant(), b.scope(), b.ask(), "question", null, null, "probe"),
                    args(b.tenant(), b.scope(), b.ask(), "question", b.q2(), null, "probe")))
                .as("a question text without its question is refused; RED STATE, observed: the "
                    + "same text on q2 is taken")
                .containsExactly(CHECK_VIOLATED, NOT_REFUSED);
            assertThat(outcomes(c, TEXT,
                    args(b.tenant(), b.scope(), b.ask(), "addendum", null, null, "probe"),
                    args(b.tenant(), b.scope(), b.ask(), "addendum", null, b.questionText(),
                        "probe")))
                .as("an addendum that supplements no entry is refused; RED STATE, observed: the "
                    + "same addendum to the text of q1 is taken")
                .containsExactly(CHECK_VIOLATED, NOT_REFUSED);
            String content = "INSERT INTO dispatch.content "
                + "(tenant_id, scope_id, act_id, kind, media_type, body) "
                + "VALUES (?, ?, ?, ?, 'text/plain', 'probe')";
            assertThat(outcomes(c, content,
                    args(b.tenant(), b.scope(), b.ask(), "question"),
                    args(b.tenant(), b.scope(), b.ask(), "remark")))
                .as("a content of the kind question is refused: content holds no text of the "
                    + "complex; RED STATE, observed: the same content as a remark is taken")
                .containsExactly(CHECK_VIOLATED, NOT_REFUSED);
            c.rollback();
        }
    }

    @Test
    void the_store_refuses_human_required_and_answer_outcome_on_an_act_of_another_kind()
            throws SQLException {
        try (Connection c = service()) {
            Bundle b = insertBundle(c);

            assertThat(outcomes(c, ACT,
                    args(b.tenant(), b.scope(), b.instance(), 4, b.task(), "claim", "active",
                        false, null),
                    args(b.tenant(), b.scope(), b.instance(), 4, b.task(), "claim", "active",
                        null, null)))
                .as("a claim carrying human_required is refused; RED STATE, observed: the same "
                    + "claim without it is taken")
                .containsExactly(CHECK_VIOLATED, NOT_REFUSED);
            assertThat(outcomes(c, ACT,
                    args(b.tenant(), b.scope(), b.instance(), 4, b.task(), "claim", "active",
                        null, "answered"),
                    args(b.tenant(), b.scope(), b.instance(), 4, b.task(), "claim", "active",
                        null, null)))
                .as("a claim carrying answer_outcome is refused; RED STATE, observed: the same "
                    + "claim without it is taken")
                .containsExactly(CHECK_VIOLATED, NOT_REFUSED);
            c.rollback();
        }
    }

    // -----------------------------------------------------------------------
    // The bundle the cases write against.
    // -----------------------------------------------------------------------

    /** A fresh tenant and scope with an instance and its task. */
    private record Place(UUID tenant, UUID scope, long instance, long task) {
    }

    /** The ask act with its text of q1, and the answer act with its answer a1 to q1. */
    private record Acts(long ask, long questionText, long answer, long a1) {
    }

    /**
     * The ids of one bundle: an ask act with the questions q1 and q2, the
     * option o1 of q1, the text of q1, and an answer act with the answer a1
     * to q1.
     */
    private record Bundle(Place at, Acts acts, long q1, long q2, long o1) {

        UUID tenant() {
            return at.tenant();
        }

        UUID scope() {
            return at.scope();
        }

        long instance() {
            return at.instance();
        }

        long task() {
            return at.task();
        }

        long ask() {
            return acts.ask();
        }

        long questionText() {
            return acts.questionText();
        }

        long answer() {
            return acts.answer();
        }

        long a1() {
            return acts.a1();
        }
    }

    private static Bundle insertBundle(Connection c) throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID scope = UUID.randomUUID();
        bind(c, tenant);
        long selector = insertReturningId(c, "INSERT INTO dispatch.selector "
            + "(tenant_id, scope_id, name) VALUES (?, ?, 'elicitation') RETURNING id",
            tenant, scope);
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
        long ask = insertReturningId(c, ACT + " RETURNING id",
            tenant, scope, instance, 1, task, "ask", "on_hold", false, null);
        long answer = insertReturningId(c, ACT + " RETURNING id",
            tenant, scope, instance, 2, task, "answer", "active", null, "answered");
        long q1 = insertReturningId(c, QUESTION + " RETURNING id",
            tenant, scope, ask, "q1", 1, "one", false);
        long q2 = insertReturningId(c, QUESTION + " RETURNING id",
            tenant, scope, ask, "q2", 2, "one", false);
        long o1 = insertReturningId(c, OPTION + " RETURNING id", tenant, scope, q1, "o1", "yes", 1);
        long a1 = insertReturningId(c, ANSWER + " RETURNING id", tenant, scope, answer, q1);
        long questionText = insertReturningId(c, TEXT + " RETURNING id",
            tenant, scope, ask, "question", q1, null, "probe question");
        return new Bundle(new Place(tenant, scope, instance, task),
            new Acts(ask, questionText, answer, a1), q1, q2, o1);
    }

    // -----------------------------------------------------------------------
    // Reading.
    // -----------------------------------------------------------------------

    /** Every tenancy and privilege defect of one table, each beginning with the table. */
    private static List<String> tenancyAndPrivilegeDefects(Connection c, String table)
            throws SQLException {
        List<String> defects = new ArrayList<>();
        List<String> columns = columnsOf(c, table);
        for (String column : List.of(TENANT, SCOPE)) {
            if (!columns.contains(column)) {
                defects.add(table + ": not a column: " + column);
            }
        }
        String relation = "'dispatch." + table + "'::regclass";
        String security = strings(c, "SELECT relrowsecurity || ' ' || relforcerowsecurity "
            + "FROM pg_class WHERE oid = " + relation).get(0);
        if (!"true true".equals(security)) {
            defects.add(table + ": row-level security enabled, forced: " + security);
        }
        List<String> policies = strings(c, "SELECT policyname || ' ' || permissive || ' ' || cmd "
            + "FROM pg_policies WHERE schemaname = 'dispatch' AND tablename = '" + table + "'");
        if (!policies.equals(List.of(table + "_tenant_isolation PERMISSIVE ALL"))) {
            defects.add(table + ": policies " + policies);
        }
        String grantee = "(SELECT oid FROM pg_roles WHERE rolname = '" + SERVICE_ROLE + "')";
        List<String> privileges = strings(c, "SELECT a.privilege_type FROM pg_class cl "
            + "CROSS JOIN LATERAL aclexplode(cl.relacl) a WHERE cl.oid = " + relation
            + " AND a.grantee = " + grantee + " ORDER BY 1");
        if (!privileges.equals(List.of("INSERT", "SELECT"))) {
            defects.add(table + ": privileges " + privileges);
        }
        List<String> columnPrivileges = strings(c, "SELECT a.privilege_type || ' ' || att.attname "
            + "FROM pg_attribute att CROSS JOIN LATERAL aclexplode(att.attacl) a "
            + "WHERE att.attrelid = " + relation + " AND a.grantee = " + grantee + " ORDER BY 1");
        if (!columnPrivileges.isEmpty()) {
            defects.add(table + ": column privileges " + columnPrivileges);
        }
        return defects;
    }

    /** The columns of a table of the schema as name, type and nullability, in their order. */
    private static List<String> columnsOf(Connection c, String table) throws SQLException {
        return strings(c, "SELECT column_name || ' ' || data_type || ' ' || is_nullable "
            + "FROM information_schema.columns WHERE table_schema = 'dispatch' "
            + "AND table_name = '" + table + "' ORDER BY ordinal_position");
    }

    /** The quoted values a check of content admits, read from its definition. */
    private static Set<String> kindsOf(Connection c, String check) throws SQLException {
        String definition = strings(c, "SELECT pg_get_constraintdef(k.oid) FROM pg_constraint k "
            + "JOIN pg_namespace n ON n.oid = k.connamespace WHERE n.nspname = 'dispatch' "
            + "AND k.conrelid = 'dispatch.content'::regclass AND k.conname = '" + check + "'")
            .get(0);
        Set<String> kinds = new TreeSet<>();
        Matcher quoted = Pattern.compile("'([a-z_]+)'").matcher(definition);
        while (quoted.find()) {
            kinds.add(quoted.group(1));
        }
        return kinds;
    }

    /**
     * Runs a statement with the refused arguments and then with the valid
     * ones, each in a savepoint rolled back, and answers both outcomes.
     */
    private static List<String> outcomes(Connection c, String sql, Object[] refused,
                                         Object[] valid) throws SQLException {
        return List.of(refusal(c, sql, refused), refusal(c, sql, valid));
    }

    private static Object[] args(Object... values) {
        return values;
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

    private static void executed(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                st.setObject(i + 1, args[i]);
            }
            st.executeUpdate();
        }
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

    /** Binds the tenant for the rest of the current transaction. */
    private static void bind(Connection c, UUID tenant) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(
                "SELECT set_config('app.tenant_id', ?, true)")) {
            st.setString(1, tenant.toString());
            st.execute();
        }
    }

    /** The first column of every row of a query, as text. */
    private static List<String> strings(Connection c, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
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
}
