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
 * From {@link #EXPECTED}: beside every row of the stock, its target state,
 * hold reason and outcome and the texts its task must carry, written out as
 * fixed values. Nothing in this test re-derives them from the source status: a
 * function carrying the same case analysis as the migration's SQL would share
 * any misreading of the mapping and stay green with it. Values the stock
 * stages (ids, stamps) are looked up in the stock as read back before V17;
 * never in the migrated tables, because a comparison of the copy with itself is
 * green whatever the copy did.
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
    private static SourceTables.Snapshot sourceBefore;

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
            sourceBefore = SourceTables.snapshot(c);
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
                    .collect(Collectors.groupingBy(r -> expected(r).stateKey(), Collectors.counting()));
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
                String state = expected(e).state();
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
            SourceTables.Snapshot after = SourceTables.snapshot(c);
            assertThat(after.exchange())
                .as("exchange row for row as before V17")
                .isEqualTo(sourceBefore.exchange());
            assertThat(after.idempotencyKey())
                .as("idempotency_key row for row as before V17")
                .isEqualTo(sourceBefore.idempotencyKey());
            assertThat(after.catalogue())
                .as("and its row security, policies, triggers, constraints and grants as before: "
                    + "V17 lifts FORCE for the copy and must have put it back")
                .isEqualTo(sourceBefore.catalogue())
                .contains("exchange rls=true force=true")
                .contains("idempotency_key rls=true force=true");
        }
    }

    // ------------------------------------------------------------------
    // The expectation, as fixed values beside every row of the stock
    // ------------------------------------------------------------------

    /** One text a task must carry: type, suffix, the text with the tenant tag at %s, and who stamped it. */
    private record Text(String type, String suffix, String text, String stampedBy) {
    }

    /** What the row staged under a number must become: state, hold reason, outcome and texts. */
    private record Expected(String state, String holdReason, String outcome, List<Text> texts) {
        String stateKey() {
            return state + "/" + holdReason + "/" + outcome;
        }
    }

    private static Text text(String type, String suffix, String text, String stampedBy) {
        return new Text(type, suffix, text, stampedBy);
    }

    /**
     * Per number staged in {@link #stage}: the target. An addendum appears as a
     * text of the task at its address, never as a row of its own.
     */
    private static final Map<Integer, Expected> EXPECTED = Map.ofEntries(
        Map.entry(1, new Expected("draft", null, null, List.of(
            text("dispatch", null, "", "author-1")))),
        Map.entry(2, new Expected("open", null, null, List.of(
            text("dispatch", null, "commission 2", "author-2"),
            text("dispatch", "a", "first correction %s\n\nread it this way %s", "author-2a")))),
        Map.entry(3, new Expected("open", null, null, List.of(
            text("dispatch", null, "commission 3", "author-3"),
            text("return", null, "early return %s", "changer-3")))),
        Map.entry(4, new Expected("active", null, null, List.of(
            text("dispatch", null, "commission 4", "author-4"),
            text("return", null, "active return %s", "changer-4")))),
        Map.entry(5, new Expected("active", null, null, List.of(
            text("dispatch", null, "commission 5", "author-5")))),
        Map.entry(6, new Expected("on_hold", "question", null, List.of(
            text("dispatch", null, "commission 6", "author-6"),
            text("question", null, "which one %s", "changer-6"),
            text("answer", null, "this one %s", "changer-6")))),
        Map.entry(7, new Expected("delivered", null, null, List.of(
            text("dispatch", null, "commission 7", "author-7"),
            text("return", null, "held return %s", "changer-7")))),
        Map.entry(8, new Expected("closed", null, "accepted", List.of(
            text("dispatch", null, "commission 8", "author-8"),
            text("return", null, "accepted return %s", "changer-8")))),
        Map.entry(9, new Expected("closed", null, "accepted", List.of(
            text("dispatch", null, "commission 9", "author-9"),
            text("return", null, "curated return %s", "changer-9")))),
        Map.entry(10, new Expected("closed", null, "accepted", List.of(
            text("dispatch", null, "commission 10", "author-10"),
            text("return", null, "uncurated return %s", "changer-10")))),
        Map.entry(11, new Expected("closed", null, "accepted", List.of(
            text("dispatch", null, "commission 11", "author-11"),
            text("return", null, "closed accepted %s", "changer-11"),
            text("dispatch", "a", "late note %s\n\nfor the record %s", "author-11a"),
            text("dispatch", "b", "later note %s\n\nand again %s", "author-11b")))),
        Map.entry(12, new Expected("closed", null, "withdrawn", List.of(
            text("dispatch", null, "commission 12", "author-12"),
            text("return", null, "closed unratified %s", "changer-12"),
            text("remark", null, "withdrawn after all %s", "changer-12")))),
        Map.entry(13, new Expected("closed", null, "withdrawn", List.of(
            text("dispatch", null, "commission 13", "author-13")))),
        Map.entry(14, new Expected("closed", null, "rejected", List.of(
            text("dispatch", null, "commission 14", "author-14"),
            text("remark", null, "not ours %s", "changer-14")))),
        Map.entry(15, new Expected("closed", null, "failed", List.of(
            text("dispatch", null, "commission 15", "author-15"),
            text("remark", null, "could not %s", "changer-15")))));

    private static final Map<UUID, String> TAG = Map.of(TENANT_A, "a", TENANT_B, "b");

    /** The fixed expectation for a staged row without a suffix, found by its number. */
    private static Expected expected(Map<String, Object> e) {
        return Objects.requireNonNull(EXPECTED.get((Integer) e.get("number")),
            "no expectation written for number " + e.get("number"));
    }

    /** The text keys of a tenant: the literals, placed at the ids and stamps the stock was staged with. */
    private static List<String> expectedTexts(UUID tenant) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Integer, Expected> entry : EXPECTED.entrySet()) {
            long task = stagedId(tenant, entry.getKey());
            for (Text t : entry.getValue().texts()) {
                out.add(textKey(task, t.type(), t.suffix(), t.text().replace("%s", TAG.get(tenant)),
                    stampOf(tenant, t.stampedBy()), t.stampedBy()));
            }
        }
        return out;
    }

    /** The id the stock gave the row without a suffix under this number. */
    private static long stagedId(UUID tenant, int number) {
        return stock.stream()
            .filter(r -> tenant.equals(r.get("tenant_id")) && r.get("addendum_suffix") == null
                && Integer.valueOf(number).equals(r.get("number")))
            .map(r -> (Long) r.get("id")).findFirst().orElseThrow();
    }

    /** The instant the stock stamped beside a name, in the column the name was staged in. */
    private static Object stampOf(UUID tenant, String by) {
        for (Map<String, Object> r : stock) {
            if (!tenant.equals(r.get("tenant_id"))) {
                continue;
            }
            if (by.equals(r.get("created_by"))) {
                return r.get("created_at");
            }
            if (by.equals(r.get("updated_by"))) {
                return r.get("updated_at");
            }
        }
        throw new IllegalStateException("nothing in the stock was stamped by " + by);
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
