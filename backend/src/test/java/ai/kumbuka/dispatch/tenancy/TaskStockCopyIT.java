package ai.kumbuka.dispatch.tenancy;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The copy of the stock from {@code exchange} into {@code task},
 * {@code task_text} and {@code task_idempotency_key} (V17), run against a
 * stock that V16 left and that holds two tenants.
 *
 * <h2>Why two tenants</h2>
 *
 * The migration runs with {@code app.tenant_id} bound to the configured tenant,
 * and {@code exchange} is under forced row-level security. A copy that reads
 * through the policy copies one tenant and nothing of the other, without an
 * error. So every comparison below is made per tenant, and the second tenant
 * is the one that tells a complete copy from a silent partial one.
 *
 * <h2>Where the expectation comes from</h2>
 *
 * From the mapping the dispatch writes out, re-stated in {@link #expectedState}
 * and {@link #expectedTexts}, and from the stock as this test staged it. Never
 * from the migrated tables: a comparison of the copy with itself is green
 * whatever the copy did.
 */
class TaskStockCopyIT {

    private static final String MIGRATOR = "copy_migrator";
    private static final String MIGRATOR_PASSWORD = "test-only-copy-password";

    private static final UUID TENANT_A = UUID.fromString(config("dispatch.tenant-id"));
    private static final UUID SCOPE_A = UUID.fromString(config("dispatch.scope-id"));
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID SCOPE_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static MigrationHarness harness;
    private static String url;

    /** The stock as staged, read back before V17 ran. */
    private static List<Map<String, Object>> stock;
    private static String exchangeBefore;
    private static String idempotencyBefore;
    private static String sourceCatalogueBefore;

    @BeforeAll
    static void migrateAStockedDatabase() throws SQLException {
        harness = MigrationHarness.start();
        harness.createMigrator(MIGRATOR, MIGRATOR_PASSWORD, "CREATEROLE NOSUPERUSER NOBYPASSRLS");
        url = harness.freshDatabase("task_copy", MIGRATOR);

        harness.migrateTo(url, MIGRATOR, MIGRATOR_PASSWORD, "16", new TenantMigrationCallback());

        try (Connection c = harness.adminConnection(url)) {
            exec(c, "INSERT INTO dispatch.selector (tenant_id, scope_id, name) VALUES ('"
                + TENANT_B + "', '" + SCOPE_B + "', 'sprint')");
            stage(c, TENANT_A, SCOPE_A, "a");
            stage(c, TENANT_B, SCOPE_B, "b");

            stock = rows(c, "SELECT * FROM dispatch.exchange ORDER BY id");
            exchangeBefore = digest(c, "dispatch.exchange");
            idempotencyBefore = digest(c, "dispatch.idempotency_key");
            sourceCatalogueBefore = sourceCatalogue(c);
        }

        harness.migrate(url, MIGRATOR, MIGRATOR_PASSWORD, new TenantMigrationCallback());
    }

    @AfterAll
    static void stopDatabase() {
        if (harness != null) {
            harness.close();
        }
    }

    // ------------------------------------------------------------------
    // The stock
    // ------------------------------------------------------------------

    /**
     * One tenant's stock: every source status at least once, a closed row
     * with and one without {@code ratified_at}, an open and an active row with
     * a return body, a held row, a draft, two addenda and an idempotency
     * record. The texts carry the tenant's tag so a text copied into the wrong
     * tenant is told apart from one copied into the right one.
     */
    private static void stage(Connection c, UUID tenant, UUID scope, String tag) throws SQLException {
        long selector = scalarLong(c, "SELECT id FROM dispatch.selector WHERE tenant_id = '"
            + tenant + "' AND scope_id = '" + scope + "' AND name = 'sprint'");
        Instant past = Instant.parse("2026-09-01T10:00:00Z");
        Instant lapsed = Instant.parse("2026-09-02T10:00:00Z");
        Instant future = Instant.now().plus(30, ChronoUnit.DAYS);
        Instant ratified = Instant.parse("2026-09-03T10:00:00Z");

        Row draft = new Row(1, "draft").body("");
        insert(c, tenant, scope, selector, draft);
        insert(c, tenant, scope, selector, new Row(2, "open").sent(past));
        long openWithReturn = insert(c, tenant, scope, selector,
            new Row(3, "open").sent(past).returnBody("early return " + tag));
        insert(c, tenant, scope, selector, new Row(4, "active").sent(past)
            .holder("exec-" + tag, lapsed).returnBody("active return " + tag)
            .metadata("{\"pr\": \"https://example.org/pr/4\"}"));
        insert(c, tenant, scope, selector, new Row(5, "active").sent(past).holder("exec-" + tag, future));
        insert(c, tenant, scope, selector, new Row(6, "needs_input").sent(past)
            .holder("exec-" + tag, future).question("which one " + tag)
            .message("this one " + tag));
        insert(c, tenant, scope, selector, new Row(7, "needs_input").sent(past)
            .holder("exec-" + tag, future).returnBody("held return " + tag));
        long returned = insert(c, tenant, scope, selector, new Row(8, "returned").sent(past)
            .returnBody("accepted return " + tag).ratified(ratified));
        insert(c, tenant, scope, selector, new Row(9, "consumed").sent(past)
            .returnBody("curated return " + tag).ratified(ratified).curatedInto(returned));
        insert(c, tenant, scope, selector, new Row(10, "consumed").sent(past)
            .returnBody("uncurated return " + tag).ratified(ratified));
        insert(c, tenant, scope, selector, new Row(11, "closed").sent(past)
            .returnBody("closed accepted " + tag).ratified(ratified));
        insert(c, tenant, scope, selector, new Row(12, "closed").sent(past)
            .returnBody("closed unratified " + tag).message("withdrawn after all " + tag));
        insert(c, tenant, scope, selector, new Row(13, "closed").sent(past));
        insert(c, tenant, scope, selector, new Row(14, "rejected").sent(past)
            .termination("not ours " + tag));
        insert(c, tenant, scope, selector, new Row(15, "failed").sent(past)
            .holder("exec-" + tag, lapsed).termination("could not " + tag));

        insert(c, tenant, scope, selector, new Row(2, "open").sent(past).suffix("a")
            .title("first correction " + tag).body("read it this way " + tag));
        insert(c, tenant, scope, selector, new Row(11, "closed").sent(past).suffix("a")
            .title("late note " + tag).body("for the record " + tag));
        insert(c, tenant, scope, selector, new Row(11, "closed").sent(past).suffix("b")
            .title("later note " + tag).body("and again " + tag));

        exec(c, "INSERT INTO dispatch.idempotency_key (tenant_id, scope_id, caller_subject, "
            + "idempotency_key, call_name, argument_digest, exchange_id) VALUES ('" + tenant
            + "', '" + scope + "', 'caller-" + tag + "', 'key-" + tag + "', 'dispatch_commission', "
            + "'digest-" + tag + "', " + openWithReturn + ")");
    }

    // ------------------------------------------------------------------
    // Criterion 2 — the copy, per tenant
    // ------------------------------------------------------------------

    @Test
    void every_tenant_gets_one_task_per_row_without_a_suffix() throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            for (UUID tenant : List.of(TENANT_A, TENANT_B)) {
                long expected = stock.stream()
                    .filter(r -> tenant.equals(r.get("tenant_id")) && r.get("addendum_suffix") == null)
                    .count();
                assertThat(scalarLong(c, "SELECT count(*) FROM dispatch.task WHERE tenant_id = '"
                        + tenant + "'"))
                    .as("tenant %s: one task per exchange row without a suffix. A copy that read "
                        + "through the forced policy copies the bound tenant and silently nothing "
                        + "of this one", tenant)
                    .isEqualTo(expected);
                assertThat(ids(c, "SELECT id FROM dispatch.task WHERE tenant_id = '" + tenant
                        + "' ORDER BY id"))
                    .as("tenant %s: and each task carries the id of its exchange row", tenant)
                    .containsExactlyElementsOf(stock.stream()
                        .filter(r -> tenant.equals(r.get("tenant_id")) && r.get("addendum_suffix") == null)
                        .map(r -> (Long) r.get("id")).toList());
            }
        }
    }

    @Test
    void every_tenant_has_as_many_tasks_per_state_and_outcome_as_the_mapping_gives() throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            for (UUID tenant : List.of(TENANT_A, TENANT_B)) {
                Map<String, Long> expected = stock.stream()
                    .filter(r -> tenant.equals(r.get("tenant_id")) && r.get("addendum_suffix") == null)
                    .collect(Collectors.groupingBy(TaskStockCopyIT::expectedState, Collectors.counting()));
                Map<String, Long> actual = new HashMap<>();
                for (Map<String, Object> t : rows(c, "SELECT state, hold_reason, outcome FROM "
                        + "dispatch.task WHERE tenant_id = '" + tenant + "'")) {
                    actual.merge(t.get("state") + "/" + t.get("hold_reason") + "/" + t.get("outcome"),
                        1L, Long::sum);
                }
                assertThat(actual)
                    .as("tenant %s: the count per target state, hold reason and outcome is the "
                        + "count per source status under the mapping", tenant)
                    .isEqualTo(expected);
            }
        }
    }

    @Test
    void every_text_of_the_stock_stands_exactly_once_at_its_task() throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            for (UUID tenant : List.of(TENANT_A, TENANT_B)) {
                List<String> expected = expectedTexts(tenant);
                List<String> actual = rows(c, "SELECT * FROM dispatch.task_text WHERE tenant_id = '"
                        + tenant + "'").stream()
                    .map(t -> textKey(t.get("task_id"), t.get("text_type"), t.get("addendum_suffix"),
                        t.get("text"), t.get("created_at"), t.get("created_by")))
                    .toList();
                assertThat(actual)
                    .as("tenant %s: every text of exchange exactly once, with its type, its suffix, "
                        + "its task and its stamps; each addendum at the task of its address", tenant)
                    .containsExactlyInAnyOrderElementsOf(expected);
                assertThat(expected)
                    .as("and the expectation must hold the texts this case is about, or the "
                        + "comparison above is about nothing")
                    .anyMatch(k -> k.contains("|answer|"))
                    .anyMatch(k -> k.contains("|question|"))
                    .anyMatch(k -> k.contains("|remark|"))
                    .anyMatch(k -> k.contains("|dispatch|b|"));
            }
        }
    }

    @Test
    void the_process_columns_follow_the_mapping_row_by_row() throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            Map<Long, Map<String, Object>> tasks = new HashMap<>();
            for (Map<String, Object> t : rows(c, "SELECT * FROM dispatch.task")) {
                tasks.put((Long) t.get("id"), t);
            }
            for (Map<String, Object> e : stock) {
                if (e.get("addendum_suffix") != null) {
                    continue;
                }
                Map<String, Object> t = tasks.get((Long) e.get("id"));
                String state = expectedState(e).split("/")[0];
                boolean held = List.of("active", "on_hold", "delivered").contains(state);
                String at = "task " + e.get("id") + " from " + e.get("status");

                assertThat(t.get("uuid")).as(at).isNotNull();
                assertThat(t.get("state_changed_at")).as(at).isEqualTo(e.get("updated_at"));
                assertThat(t.get("state_changed_by")).as(at).isEqualTo(e.get("updated_by"));
                assertThat(t.get("holder_subject")).as(at + ": holder only where held")
                    .isEqualTo(held ? e.get("holder_subject") : null);
                assertThat(t.get("holder_receipt_hash")).as(at + ": hash only where held")
                    .isEqualTo(held ? e.get("holder_receipt_hash") : null);
                assertThat(t.get("lease_expires_at")).as(at + ": lease only in active, as it stands")
                    .isEqualTo("active".equals(state) ? e.get("claim_expires_at") : null);
                assertThat(t.get("curated_in_id")).as(at).isEqualTo(e.get("curated_into_id"));
                assertThat((String) t.get("dispatch_metadata")).as(at + ": the date as a key")
                    .contains("\"date\": \"" + e.get("dispatch_date") + "\"");
                if (e.get("dispatch_metadata") != null) {
                    assertThat((String) t.get("dispatch_metadata")).as(at + ": the caller's keys stay")
                        .contains("\"pr\"");
                }
                assertThat(t.get("lapse_count")).as(at).isEqualTo(0);
                assertThat(t.get("not_before")).as(at).isNull();
                assertThat(t.get("question_options")).as(at + ": free text, no options, on a question")
                    .isEqualTo("on_hold".equals(state)
                        ? "{\"options\": [], \"free_text\": true}" : null);
                for (String column : List.of("title", "apparatus", "return_metadata",
                        "created_at", "created_by", "updated_at", "updated_by", "tenant_id",
                        "scope_id", "selector_id", "number", "sub")) {
                    assertThat(t.get(column)).as(at + ": " + column).isEqualTo(e.get(column));
                }
            }
        }
    }

    @Test
    void the_idempotency_records_follow_their_tasks() throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            assertThat(rows(c, "SELECT id, tenant_id, scope_id, task_id AS target, caller_subject, "
                    + "idempotency_key, call_name, argument_digest, first_seen_at "
                    + "FROM dispatch.task_idempotency_key ORDER BY id"))
                .as("every record, with its id, pointing at the task of its exchange")
                .isEqualTo(rows(c, "SELECT id, tenant_id, scope_id, exchange_id AS target, "
                    + "caller_subject, idempotency_key, call_name, argument_digest, first_seen_at "
                    + "FROM dispatch.idempotency_key ORDER BY id"))
                .hasSize(2);
        }
    }

    // ------------------------------------------------------------------
    // Criterion 3 — the identity counter
    // ------------------------------------------------------------------

    @Test
    void an_insert_without_an_id_collides_with_no_copied_task() throws SQLException {
        long highest;
        try (Connection c = harness.adminConnection(url)) {
            highest = scalarLong(c, "SELECT max(id) FROM dispatch.task");
        }
        // Under the runtime role and its grants, bound to the tenant as the
        // service binds it: the insert the kernel will make.
        try (Connection c = DriverManager.getConnection(url, SubstrateDatabaseResource.SERVICE_ROLE,
                SubstrateDatabaseResource.SERVICE_PASSWORD)) {
            c.setAutoCommit(false);
            exec(c, "SELECT set_config('app.tenant_id', '" + TENANT_A + "', true)");
            long id = scalarLong(c, "INSERT INTO dispatch.task (tenant_id, scope_id, selector_id, "
                + "number, sub, title, apparatus) SELECT tenant_id, scope_id, id, 900, 0, 'new', "
                + "'code' FROM dispatch.selector WHERE name = 'sprint' RETURNING id");
            c.rollback();
            assertThat(id)
                .as("the identity counter stands above the highest copied id")
                .isGreaterThan(highest);
        }
    }

    // ------------------------------------------------------------------
    // Criterion 4 — the source is untouched
    // ------------------------------------------------------------------

    @Test
    void the_source_is_unchanged_row_for_row_and_in_the_catalogue() throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            assertThat(digest(c, "dispatch.exchange"))
                .as("exchange row for row as before V17")
                .isEqualTo(exchangeBefore);
            assertThat(digest(c, "dispatch.idempotency_key"))
                .as("idempotency_key row for row as before V17")
                .isEqualTo(idempotencyBefore);
            assertThat(sourceCatalogue(c))
                .as("and its row security, policies, triggers, constraints and grants as before: "
                    + "V17 lifts FORCE for the copy and must have put it back")
                .isEqualTo(sourceCatalogueBefore)
                .contains("exchange rls=true force=true")
                .contains("idempotency_key rls=true force=true");
        }
    }

    // ------------------------------------------------------------------
    // The mapping, as the dispatch writes it
    // ------------------------------------------------------------------

    /** state/hold_reason/outcome for a source row. */
    static String expectedState(Map<String, Object> e) {
        String status = (String) e.get("status");
        boolean hasReturn = e.get("return_body") != null;
        boolean ratified = e.get("ratified_at") != null;
        return switch (status) {
            case "draft", "open", "active" -> status + "/null/null";
            case "needs_input" -> hasReturn ? "delivered/null/null" : "on_hold/question/null";
            case "returned", "consumed" -> "closed/null/accepted";
            case "rejected" -> "closed/null/rejected";
            case "failed" -> "closed/null/failed";
            case "closed" -> ratified ? "closed/null/accepted" : "closed/null/withdrawn";
            default -> throw new IllegalStateException("unmapped status " + status);
        };
    }

    private static List<String> expectedTexts(UUID tenant) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> e : stock) {
            if (!tenant.equals(e.get("tenant_id"))) {
                continue;
            }
            Object created = e.get("created_at");
            Object createdBy = e.get("created_by");
            Object updated = e.get("updated_at");
            Object updatedBy = e.get("updated_by");
            if (e.get("addendum_suffix") != null) {
                long base = stock.stream()
                    .filter(b -> tenant.equals(b.get("tenant_id")) && b.get("addendum_suffix") == null
                        && b.get("number").equals(e.get("number")) && b.get("sub").equals(e.get("sub")))
                    .map(b -> (Long) b.get("id")).findFirst().orElseThrow();
                out.add(textKey(base, "dispatch", e.get("addendum_suffix"),
                    e.get("title") + "\n\n" + e.get("dispatch_body"), created, createdBy));
                continue;
            }
            Object id = e.get("id");
            out.add(textKey(id, "dispatch", null, e.get("dispatch_body"), created, createdBy));
            if (e.get("return_body") != null) {
                out.add(textKey(id, "return", null, e.get("return_body"), updated, updatedBy));
            }
            if (e.get("executor_question") != null) {
                out.add(textKey(id, "question", null, e.get("executor_question"), updated, updatedBy));
            }
            if (e.get("commissioner_message") != null) {
                out.add(textKey(id, e.get("executor_question") != null ? "answer" : "remark", null,
                    e.get("commissioner_message"), updated, updatedBy));
            }
            if (e.get("termination_reason") != null) {
                out.add(textKey(id, "remark", null, e.get("termination_reason"), updated, updatedBy));
            }
        }
        return out;
    }

    private static String textKey(Object task, Object type, Object suffix, Object text,
                                  Object at, Object by) {
        return task + "|" + type + "|" + Objects.toString(suffix, "") + "|" + text + "|" + at + "|" + by;
    }

    // ------------------------------------------------------------------
    // Staging and reading
    // ------------------------------------------------------------------

    /** One exchange row to stage. Only what a case sets differs from the defaults. */
    private static final class Row {
        final int number;
        final String status;
        String suffix;
        String title;
        String body;
        Instant sent;
        String returnBody;
        Instant ratified;
        String holder;
        Instant expires;
        String metadata;
        Long curatedInto;
        String question;
        String message;
        String termination;

        Row(int number, String status) {
            this.number = number;
            this.status = status;
            this.title = "task " + number;
            this.body = "commission " + number;
        }

        Row suffix(String s) { suffix = s; return this; }
        Row title(String t) { title = t; return this; }
        Row body(String b) { body = b; return this; }
        Row sent(Instant at) { sent = at; return this; }
        Row returnBody(String r) { returnBody = r; return this; }
        Row ratified(Instant at) { ratified = at; return this; }
        Row holder(String subject, Instant until) { holder = subject; expires = until; return this; }
        Row metadata(String m) { metadata = m; return this; }
        Row curatedInto(long id) { curatedInto = id; return this; }
        Row question(String q) { question = q; return this; }
        Row message(String m) { message = m; return this; }
        Row termination(String t) { termination = t; return this; }
    }

    private static long insert(Connection c, UUID tenant, UUID scope, long selector, Row r)
            throws SQLException {
        // Distinct creation and change stamps, so a text carrying the wrong one
        // is told apart from a text carrying the right one.
        Instant created = Instant.parse("2026-08-01T08:00:00Z").plusSeconds(r.number * 60L)
            .plus(r.suffix == null ? 0 : r.suffix.charAt(0), ChronoUnit.HOURS);
        Instant updated = created.plus(3, ChronoUnit.DAYS);
        try (PreparedStatement st = c.prepareStatement("""
                INSERT INTO dispatch.exchange (tenant_id, scope_id, selector_id, number, sub,
                    addendum_suffix, status, title, dispatch_body, apparatus, dispatch_date, sent_at,
                    return_body, ratified_at, holder_subject, holder_receipt_hash, claim_expires_at,
                    dispatch_metadata, curated_into_id, executor_question, commissioner_message,
                    termination_reason, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?, 'code', DATE '2026-08-31', ?, ?, ?, ?, ?, ?,
                        ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)) {
            int i = 1;
            st.setObject(i++, tenant);
            st.setObject(i++, scope);
            st.setLong(i++, selector);
            st.setInt(i++, r.number);
            st.setString(i++, r.suffix);
            st.setString(i++, r.status);
            st.setString(i++, r.title);
            st.setString(i++, r.body);
            st.setTimestamp(i++, ts(r.sent));
            st.setString(i++, r.returnBody);
            st.setTimestamp(i++, ts(r.ratified));
            st.setString(i++, r.holder);
            st.setString(i++, r.holder == null ? null : "hash-of-" + r.holder + "-" + r.number);
            st.setTimestamp(i++, ts(r.expires));
            st.setString(i++, r.metadata);
            st.setObject(i++, r.curatedInto);
            st.setString(i++, r.question);
            st.setString(i++, r.message);
            st.setString(i++, r.termination);
            st.setTimestamp(i++, ts(created));
            st.setString(i++, "author-" + r.number + Objects.toString(r.suffix, ""));
            st.setTimestamp(i++, ts(updated));
            st.setString(i++, "changer-" + r.number + Objects.toString(r.suffix, ""));
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static Timestamp ts(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }

    /** Every row of a table as text, in id order, folded into one digest. */
    private static String digest(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) || ':' || coalesce(md5(string_agg("
                 + "t::text, '|' ORDER BY t.id)), '') FROM " + table + " t")) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** What the catalogue says about the two source tables, as one comparable text. */
    private static String sourceCatalogue(Connection c) throws SQLException {
        List<String> facts = new ArrayList<>();
        for (Map<String, Object> r : rows(c, """
                SELECT 'table ' || relname || ' rls=' || relrowsecurity || ' force=' || relforcerowsecurity AS f
                  FROM pg_class WHERE oid IN ('dispatch.exchange'::regclass, 'dispatch.idempotency_key'::regclass)
                UNION ALL
                SELECT 'policy ' || tablename || ' ' || policyname || ' ' || coalesce(qual, '')
                       || ' ' || coalesce(with_check, '')
                  FROM pg_policies WHERE schemaname = 'dispatch'
                   AND tablename IN ('exchange', 'idempotency_key')
                UNION ALL
                SELECT 'trigger ' || tgrelid::regclass || ' ' || tgname || ' ' || tgenabled::text
                  FROM pg_trigger WHERE tgrelid IN ('dispatch.exchange'::regclass,
                                                    'dispatch.idempotency_key'::regclass)
                   AND NOT tgisinternal
                UNION ALL
                SELECT 'constraint ' || conrelid::regclass || ' ' || conname || ' '
                       || pg_get_constraintdef(oid)
                  FROM pg_constraint WHERE conrelid IN ('dispatch.exchange'::regclass,
                                                        'dispatch.idempotency_key'::regclass)
                UNION ALL
                SELECT 'grant ' || table_name || ' ' || grantee || ' ' || privilege_type
                  FROM information_schema.role_table_grants
                 WHERE table_schema = 'dispatch' AND table_name IN ('exchange', 'idempotency_key')
                ORDER BY 1
                """)) {
            facts.add(((String) r.get("f")).replace("table dispatch.", "").replace("table ", ""));
        }
        return String.join("\n", facts);
    }

    private static List<Map<String, Object>> rows(Connection c, String sql) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 1; i <= columns; i++) {
                    Object value = rs.getObject(i);
                    // jsonb and timestamps compared as their text: the driver
                    // hands out PGobject and Timestamp, both of which compare
                    // by value as text.
                    if (value instanceof org.postgresql.util.PGobject pg) {
                        value = pg.getValue();
                    } else if (value instanceof Timestamp t) {
                        value = t.toInstant().toString();
                    } else if (value instanceof Short sh) {
                        value = sh.intValue();
                    }
                    row.put(rs.getMetaData().getColumnName(i), value);
                }
                out.add(row);
            }
        }
        return out;
    }

    private static List<Long> ids(Connection c, String sql) throws SQLException {
        List<Long> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getLong(1));
            }
        }
        return out;
    }

    private static long scalarLong(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
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
