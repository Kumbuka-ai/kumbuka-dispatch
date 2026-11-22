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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static ai.kumbuka.dispatch.tenancy.CutoverStock.ACTOR;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.COPY;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.HISTORY;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.LEGACY;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.NOT_REFUSED;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.OLD_SCHEMA;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.RENAME;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.SCOPE_A;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.SCOPE_B;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.TENANT_A;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cut-over on a database of its own: the old line db/migration V1 to V25,
 * a stock in two tenants, the rename, the line db/store V1 to V4, and the copy
 * (migration/cutover-copy.sql), each as the operator runs it.
 *
 * <p>Every run happens once, in {@link #cutOver}, in the order of the
 * cut-over: a copy without the actor setting and one under a role that does
 * not read every tenant, both refused; the copy; a second copy; a copy after
 * the opening, refused. The cases read what each run left.
 *
 * <p>Expectations come from the order and are written here, or from the stock
 * as planted, read from the old store. None is read from the new store to be
 * compared with itself.
 */
class CutoverCopyIT {

    /** The thirty-three tables of the store line after V4, as StoreLineIT names them. */
    private static final Set<String> TABLES = new TreeSet<>(List.of(
        "selector", "definition_version", "definition_act", "definition_tag", "activity",
        "outcome_label", "instance", "task", "act", "commission", "commission_criterion",
        "holding", "receipt", "lease", "question_option", "answer", "delivery",
        "delivery_criterion", "instance_return", "external_reference", "idempotency_key",
        "free_fields", "content", "draft", "draft_criterion", "draft_free_fields", "draft_text",
        "enumeration", "enumeration_value", "control_field", "contract_field", "field_value",
        "instance_field_value"));

    /** The tables no rule of the copy writes: no criterion, verdict, outcome, field or rule. */
    private static final List<String> UNWRITTEN = List.of("definition_act", "definition_tag",
        "outcome_label", "commission_criterion", "answer", "delivery", "delivery_criterion",
        "idempotency_key", "draft_criterion", "enumeration", "enumeration_value",
        "control_field", "contract_field", "field_value", "instance_field_value");

    /** A task of the new store and its address within its tenant. */
    private static final String TASKS = " FROM dispatch.task t "
        + "JOIN dispatch.instance i ON i.id = t.instance_id "
        + "JOIN dispatch.selector s ON s.id = i.selector_id ";
    private static final String ADDRESS = "s.name || '/' || i.number || '.' || t.sub";

    /** A task of the old store and its address within its tenant. */
    private static final String OLD_TASKS = " FROM dispatch_legacy.task t "
        + "JOIN dispatch_legacy.selector s ON s.id = t.selector_id ";
    private static final String OLD_ADDRESS = "s.name || '/' || t.number || '.' || t.sub";

    private static final String IN_TENANT_A = " WHERE t.tenant_id = ? ";

    /** A count of nothing, as a query answers it. */
    private static final String NONE = "0";

    /** How every refusal of the copy begins. */
    private static final String REFUSED = "cutover copy refused";

    /** The trace of sprint/1 of a tenant, by the rules: kind@sub, an act on the instance as kind*. */
    private static final List<String> TRACE_OF_SPRINT_1 = List.of(
        "import*", "send@0",
        "send@1", "send@2", "send@3", "claim@3", "send@4", "send@6", "send@7", "send@8",
        "send@9", "send@10", "send@12",
        "send@5", "claim@5", "annotate@0", "claim@6", "claim@2", "hold@3", "hold@4", "ask@5",
        "deliver@0", "answer@5", "ask@5", "deliver@6", "annotate@6", "reject@7", "fail@8",
        "withdraw@9", "deliver@10", "accept@10", "deliver@0", "defer@12", "accept@0",
        "conclude*");

    private static MigrationHarness harness;
    private static String url;

    private static String renamed;
    private static String withoutActor;
    private static String underMigrator;
    private static String firstRun;
    private static String secondRun;
    private static String afterOpening;

    private static Map<String, String> legacyBefore;
    private static Map<String, String> legacyAfter;
    private static Map<String, String> storeWithoutActor;
    private static Map<String, String> storeUnderMigrator;
    private static Map<String, String> storeAfterFirst;
    private static Map<String, String> storeAfterSecond;
    private static Map<String, String> storeAfterOpening;

    private static List<String> firstReport;
    private static List<String> secondReport;
    private static Set<String> planted;

    @BeforeAll
    static void cutOver() throws SQLException {
        harness = MigrationHarness.start();
        harness.createMigrator(CutoverStock.MIGRATOR, CutoverStock.MIGRATOR_PASSWORD,
            "CREATEROLE NOSUPERUSER NOBYPASSRLS");
        url = CutoverStock.oldStore(harness, "cutover_copy");
        try (Connection admin = harness.adminConnection(url)) {
            CutoverStock.stage(admin, TENANT_A, SCOPE_A, "a");
            CutoverStock.stage(admin, TENANT_B, SCOPE_B, "b");
            planted = CutoverStock.planted(admin, OLD_SCHEMA);
        }
        try (Connection owner = CutoverStock.asMigrator(url)) {
            renamed = CutoverStock.outcome(owner, RENAME);
        }
        CutoverStock.newStore(harness, url);

        try (Connection admin = harness.adminConnection(url)) {
            legacyBefore = CutoverStock.rows(admin, LEGACY);
            withoutActor = CutoverStock.outcome(admin, COPY);
            storeWithoutActor = CutoverStock.rows(admin, OLD_SCHEMA);
        }
        try (Connection migrator = CutoverStock.asMigrator(url)) {
            underMigrator = CutoverStock.copy(migrator, ACTOR);
        }
        try (Connection admin = harness.adminConnection(url)) {
            storeUnderMigrator = CutoverStock.rows(admin, OLD_SCHEMA);

            firstRun = CutoverStock.copy(admin, ACTOR);
            firstReport = CutoverStock.report(admin);
            storeAfterFirst = CutoverStock.rows(admin, OLD_SCHEMA);

            secondRun = CutoverStock.copy(admin, ACTOR);
            secondReport = CutoverStock.report(admin);
            storeAfterSecond = CutoverStock.rows(admin, OLD_SCHEMA);

            CutoverStock.exec(admin, "UPDATE dispatch_legacy.cutover SET opened_at = now()");
            afterOpening = CutoverStock.copy(admin, ACTOR);
            storeAfterOpening = CutoverStock.rows(admin, OLD_SCHEMA);
            CutoverStock.exec(admin, "UPDATE dispatch_legacy.cutover SET opened_at = NULL");

            legacyAfter = CutoverStock.rows(admin, LEGACY);
        }
    }

    @AfterAll
    static void stop() {
        if (harness != null) {
            harness.close();
        }
    }

    // -----------------------------------------------------------------------
    // Tasks, instances, definitions.
    // -----------------------------------------------------------------------

    @Test
    void every_old_task_has_exactly_one_new_task_with_its_address_state_title_and_role()
            throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            List<String> old = lines(c, "SELECT t.tenant_id || ' ' || " + OLD_ADDRESS
                + ", t.state, coalesce(t.hold_reason, '-'), coalesce(t.outcome, '-'), t.title, "
                + "t.apparatus, coalesce(t.not_before::text, '-')" + OLD_TASKS + "ORDER BY 1");
            List<String> copied = lines(c, "SELECT t.tenant_id || ' ' || " + ADDRESS
                + ", t.state, coalesce(t.hold_reason, '-'), coalesce(t.resolution, '-'), t.title, "
                + "t.addressed_role, coalesce(t.not_before::text, '-')" + TASKS + "ORDER BY 1");

            assertThat(renamed).as("the rename ran").isEqualTo(NOT_REFUSED);
            assertThat(firstRun).as("the copy ran").isEqualTo(NOT_REFUSED);
            assertThat(old).as("the stock as planted: sixteen tasks in each of two tenants")
                .hasSize(32);
            assertThat(copied)
                .as("each old task is exactly one new task with the same address, the root under "
                    + ".0, the same state, hold reason, resolution from outcome, not_before and "
                    + "title, and its apparatus as addressed_role")
                .isEqualTo(old);
            assertThat(lines(c, "SELECT t.tenant_id || ' ' || " + ADDRESS + TASKS
                    + "WHERE t.parked ORDER BY 1"))
                .as("parked is true exactly for the task on hold external without a holder and "
                    + "with three lapses")
                .containsExactlyInAnyOrder(TENANT_A + " sprint/1.4", TENANT_B + " sprint/1.4");
            assertThat(scalar(c, "SELECT count(*) FROM dispatch.task WHERE activity_id IS NOT NULL"))
                .as("no task carries an activity").isEqualTo(NONE);
        }
    }

    @Test
    void every_bracket_is_one_instance_with_the_title_of_its_root_task() throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            List<String> roots = lines(c, "SELECT t.tenant_id || ' ' || s.name || '/' || t.number, "
                + "t.title" + OLD_TASKS + "WHERE t.sub = 0 ORDER BY 1");
            List<String> instances = lines(c, "SELECT i.tenant_id || ' ' || s.name || '/' "
                + "|| i.number, i.title FROM dispatch.instance i "
                + "JOIN dispatch.selector s ON s.id = i.selector_id ORDER BY 1");
            List<String> rootsUnderZero = lines(c, "SELECT i.tenant_id || ' ' || s.name || '/' "
                + "|| i.number, t.title" + TASKS + "WHERE t.sub = 0 ORDER BY 1");

            assertThat(roots).as("four brackets in each tenant").hasSize(8);
            assertThat(instances).as("one instance per bracket, titled as its root task")
                .isEqualTo(roots);
            assertThat(rootsUnderZero).as("and each root task stands under .0 of its instance")
                .isEqualTo(roots);
            assertThat(lines(c, "SELECT s.name || '/' || i.number, i.state, "
                    + "coalesce(i.resolution, '-'), i.next_sub FROM dispatch.instance i "
                    + "JOIN dispatch.selector s ON s.id = i.selector_id WHERE i.tenant_id = ? "
                    + "ORDER BY 1", TENANT_A))
                .as("closed with concluded where the root task was accepted, withdrawn where it "
                    + "was withdrawn, running otherwise; next_sub behind the highest sub")
                .containsExactly(
                    "satellite/3 | running | - | 1",
                    "sprint/1 | closed | concluded | 13",
                    "sprint/2 | closed | withdrawn | 1",
                    "sprint/3 | running | - | 1");
        }
    }

    @Test
    void every_selector_has_one_seed_version_with_one_activity_and_a_counter_never_reissued()
            throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            String shape = "SELECT s.name || ' ' || s.next_number || ' ' || count(DISTINCT v.id) "
                + "|| ' ' || count(DISTINCT v.id) FILTER (WHERE v.seed AND v.version = 1 "
                + "AND v.state = 'active' AND NOT v.verdict_required AND NOT v.outcome_required) "
                + "|| ' ' || count(DISTINCT a.id) || ' ' || count(DISTINCT a.id) FILTER ("
                + "WHERE a.name = 'default' AND a.input_kind = 'text' AND a.may_ask "
                + "AND NOT a.starts_excluded AND NOT a.criteria_required "
                + "AND NOT a.verdict_required AND NOT a.outcome_required "
                + "AND a.bound_activity_id IS NULL AND a.handbook_address IS NULL) "
                + "FROM dispatch.selector s "
                + "LEFT JOIN dispatch.definition_version v ON v.selector_id = s.id "
                + "LEFT JOIN dispatch.activity a ON a.definition_version_id = v.id ";

            assertThat(lines(c, shape + "WHERE s.tenant_id = ? GROUP BY s.id ORDER BY 1", TENANT_A))
                .as("sprint stood at 2 behind its highest number 3 and continues at 4; satellite "
                    + "stood at 40 and stays there; each has one version, the seed, and one "
                    + "activity default")
                .containsExactly("satellite 40 1 1 1 1", "sprint 4 1 1 1 1");
            assertThat(lines(c, shape + "GROUP BY s.id ORDER BY 1").stream()
                    .filter(line -> !line.endsWith(" 1 1 1 1")).toList())
                .as("every selector of every tenant has the same shape")
                .isEmpty();
            assertThat(scalar(c, "SELECT count(*) FROM dispatch.selector"))
                .as("one selector per old selector: two in each tenant of the stock and the two "
                    + "V5 declares").isEqualTo("6");
            assertThat(scalar(c, "SELECT count(*) FROM dispatch.definition_version "
                    + "WHERE seed AND version <> 1"))
                .as("seed stands on no other version").isEqualTo(NONE);
            for (String table : UNWRITTEN) {
                assertThat(storeAfterFirst.get(table))
                    .as("%s holds no row: no definition act, outcome label, control field, "
                        + "verdict, outcome or field value is copied", table)
                    .startsWith("0:");
            }
        }
    }

    // -----------------------------------------------------------------------
    // The trace.
    // -----------------------------------------------------------------------

    @Test
    void every_trace_begins_with_one_import_act_of_the_cutover_actor() throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            assertThat(scalar(c, "SELECT count(*) FROM dispatch.instance i WHERE NOT EXISTS ("
                    + "SELECT 1 FROM dispatch.act a WHERE a.instance_id = i.id AND a.sequence = 1 "
                    + "AND a.kind = 'import' AND a.task_id IS NULL AND a.actor_subject = '" + ACTOR
                    + "' AND a.actor_owner_subject = '" + ACTOR + "')"))
                .as("every instance begins with an import act of the actor of the setting")
                .isEqualTo(NONE);
            assertThat(scalar(c, "SELECT count(*) FROM dispatch.act WHERE kind = 'import'"))
                .as("exactly one per instance, eight in all").isEqualTo("8");
            assertThat(scalar(c, "SELECT count(*) FROM dispatch.act WHERE kind = 'import' "
                    + "AND sequence <> 1"))
                .as("and no other act carries the kind").isEqualTo(NONE);
        }
    }

    @Test
    void the_derived_acts_follow_by_time_then_sub_number_then_the_order_of_the_rules()
            throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            assertThat(lines(c, "SELECT a.kind || coalesce('@' || t.sub, '*') "
                    + "FROM dispatch.act a JOIN dispatch.instance i ON i.id = a.instance_id "
                    + "JOIN dispatch.selector s ON s.id = i.selector_id "
                    + "LEFT JOIN dispatch.task t ON t.id = a.task_id "
                    + "WHERE a.tenant_id = ? AND s.name = 'sprint' AND i.number = 1 "
                    + "ORDER BY a.sequence", TENANT_A))
                .as("the import, then what the old store records: send, the claim of a holder "
                    + "before the act that makes its state, deliver, ask, answer, annotate, the act "
                    + "of the present state, the conclusion of the instance")
                .containsExactlyElementsOf(TRACE_OF_SPRINT_1);
            assertThat(lines(c, "SELECT s.name || '/' || i.number, string_agg(a.kind || "
                    + "CASE WHEN a.task_id IS NULL THEN '*' ELSE '' END, ' ' ORDER BY a.sequence) "
                    + "FROM dispatch.act a JOIN dispatch.instance i ON i.id = a.instance_id "
                    + "JOIN dispatch.selector s ON s.id = i.selector_id WHERE a.tenant_id = ? "
                    + "AND NOT (s.name = 'sprint' AND i.number = 1) GROUP BY 1 ORDER BY 1",
                    TENANT_A))
                .as("a withdrawn root closes its instance with withdraw; a never-sent root has "
                    + "the import alone")
                .containsExactly("satellite/3 | import* send", "sprint/2 | import* send withdraw "
                    + "withdraw*", "sprint/3 | import*");
            assertThat(lines(c, "SELECT r.kind, to_char(r.at AT TIME ZONE 'UTC', 'HH24:MI') "
                    + "FROM dispatch.act a JOIN dispatch.act r ON r.id = a.refers_to_act_id "
                    + "WHERE a.kind = 'conclude' AND a.tenant_id = ?", TENANT_A))
                .as("the conclusion refers to the deliver act of the valid return of the root "
                    + "task, the younger of its two")
                .containsExactly("deliver | 08:20");
            assertThat(scalar(c, "SELECT count(*) FROM dispatch.instance_return r "
                    + "JOIN dispatch.act a ON a.id = r.act_id WHERE a.task_id IS NULL "
                    + "AND a.kind IN ('conclude', 'withdraw') AND r.verdict IS NULL "
                    + "AND r.outcome_label_id IS NULL"))
                .as("each of the four closing acts carries the return of its instance, without "
                    + "verdict or outcome").isEqualTo("4");
        }
    }

    @Test
    void an_ask_carries_the_options_where_the_old_store_recorded_them_and_free_text_otherwise()
            throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            assertThat(lines(c, "SELECT a.free_text_admitted, coalesce((SELECT string_agg("
                    + "o.label, ',' ORDER BY o.ordinal) FROM dispatch.question_option o "
                    + "WHERE o.ask_act_id = a.id), '-') FROM dispatch.act a "
                    + "JOIN dispatch.task t ON t.id = a.task_id "
                    + "WHERE a.kind = 'ask' AND a.tenant_id = ? AND t.sub = 5 ORDER BY a.sequence",
                    TENANT_A))
                .as("the answered question admits free text and offers no option, a value the "
                    + "copy sets; the open one carries the options and free_text of "
                    + "question_options, in their order")
                .containsExactly("t | -", "f | left,right");
            assertThat(firstReport)
                .as("the report counts each kind of ask apart")
                .contains("ask acts | free_text_admitted set by the copy, not recorded | 2",
                    "ask acts | options and free_text from question_options | 2");
        }
    }

    @Test
    void every_task_keeps_its_holder_with_its_receipt_and_an_active_one_its_lease()
            throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            List<String> old = lines(c, "SELECT t.tenant_id || ' ' || " + OLD_ADDRESS
                + ", coalesce(t.holder_subject, '-'), coalesce(t.holder_receipt_hash, '-'), "
                + "coalesce(t.lease_expires_at::text, '-')" + OLD_TASKS + "ORDER BY 1");
            String holders = "SELECT t.tenant_id || ' ' || " + ADDRESS
                + ", coalesce(a.actor_subject, '-'), coalesce(r.receipt_hash, '-'), "
                + "coalesce(l.expires_at::text, '-')" + TASKS
                + "LEFT JOIN dispatch.holding h ON h.task_id = t.id AND h.ended_by_act_id IS NULL "
                + "LEFT JOIN dispatch.act a ON a.id = h.claim_act_id AND a.kind = 'claim' "
                + "LEFT JOIN dispatch.receipt r ON r.holding_id = h.id "
                + "LEFT JOIN dispatch.lease l ON l.holding_id = h.id ";

            assertThat(lines(c, holders + "ORDER BY 1"))
                .as("every task keeps its holder, as the subject of an unended holding opened by a "
                    + "claim, its receipt hash, and its lease end")
                .isEqualTo(old);
            assertThat(lines(c, "SELECT " + ADDRESS + ", a.actor_subject, r.receipt_hash, "
                    + "l.id IS NOT NULL" + TASKS
                    + "JOIN dispatch.holding h ON h.task_id = t.id AND h.ended_by_act_id IS NULL "
                    + "JOIN dispatch.act a ON a.id = h.claim_act_id "
                    + "JOIN dispatch.receipt r ON r.holding_id = h.id "
                    + "LEFT JOIN dispatch.lease l ON l.holding_id = h.id"
                    + IN_TENANT_A + "ORDER BY 1", TENANT_A))
                .as("an active, two on hold and a delivered task keep holder and receipt; only "
                    + "the active one keeps a lease")
                .containsExactly(
                    "sprint/1.2 | exec-a | receipt-hash-1.2-a | t",
                    "sprint/1.3 | exec-a | receipt-hash-1.3-a | f",
                    "sprint/1.5 | exec-a | receipt-hash-1.5-a | f",
                    "sprint/1.6 | exec-a | receipt-hash-1.6-a | f");
            assertThat(firstReport).as("and the report counts the claims of holders")
                .contains("holders | claim acts of a holder | 8");
        }
    }

    // -----------------------------------------------------------------------
    // Texts, tags, metadata, curation, drafts.
    // -----------------------------------------------------------------------

    @Test
    void every_task_keeps_its_texts_unchanged_and_a_sent_one_its_tags() throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            List<String> oldTexts = lines(c, "SELECT t.tenant_id || ' ' || " + OLD_ADDRESS
                + ", string_agg(x.text, '|' ORDER BY x.text)" + OLD_TASKS
                + "JOIN dispatch_legacy.task_text x ON x.task_id = t.id GROUP BY 1 ORDER BY 1");
            List<String> newTexts = lines(c, "SELECT t.tenant_id || ' ' || " + ADDRESS
                + ", string_agg(b.body, '|' ORDER BY b.body)" + TASKS
                + "JOIN (SELECT a.task_id, x.body FROM dispatch.content x "
                + "JOIN dispatch.act a ON a.id = x.act_id "
                + "UNION ALL SELECT d.task_id, x.body FROM dispatch.draft_text x "
                + "JOIN dispatch.draft d ON d.id = x.draft_id) b ON b.task_id = t.id "
                + "GROUP BY 1 ORDER BY 1");
            List<String> oldTags = lines(c, "SELECT t.tenant_id || ' ' || " + OLD_ADDRESS
                + ", g.tag_id::text" + OLD_TASKS + "JOIN dispatch_legacy.task_tag g "
                + "ON g.task_id = t.id WHERE t.state <> 'draft' ORDER BY 1");
            List<String> newTags = lines(c, "SELECT t.tenant_id || ' ' || " + ADDRESS
                + ", r.value" + TASKS + "JOIN dispatch.external_reference r ON r.task_id = t.id "
                + "WHERE r.kind = 'tag' ORDER BY 1");

            assertThat(oldTexts).as("the stock carries texts on every one of its thirty-two "
                    + "tasks").hasSize(32);
            assertThat(newTexts).as("every text is carried with its body unchanged")
                .isEqualTo(oldTexts);
            assertThat(oldTags).as("one tag on a sent task per tenant").hasSize(2);
            assertThat(newTags).as("each tag of a sent task is a reference of kind tag")
                .isEqualTo(oldTags);
            assertThat(lines(c, "SELECT " + ADDRESS + ", b.kind" + TASKS
                    + "JOIN dispatch.act a ON a.task_id = t.id "
                    + "JOIN dispatch.content x ON x.act_id = a.id "
                    + "JOIN dispatch.content b ON b.id = x.supplements_content_id"
                    + IN_TENANT_A + "AND x.kind = 'addendum' ORDER BY 1", TENANT_A))
                .as("an addendum supplements the text of its type it belongs to")
                .containsExactly("sprint/1.0 | commission", "sprint/1.6 | return");
            assertThat(lines(c, "SELECT " + ADDRESS + ", a.kind" + TASKS
                    + "JOIN dispatch.act a ON a.task_id = t.id "
                    + "JOIN dispatch.content x ON x.act_id = a.id"
                    + IN_TENANT_A + "AND x.kind = 'remark' ORDER BY 1", TENANT_A))
                .as("a remark hangs on the last act of its task not after it")
                .containsExactly("sprint/1.7 | reject", "sprint/1.8 | fail");
            assertThat(scalar(c, "SELECT count(*) FROM dispatch.commission m "
                    + "JOIN dispatch.act a ON a.id = m.act_id JOIN dispatch.task t "
                    + "ON t.id = a.task_id WHERE a.kind <> 'send' OR m.title <> t.title "
                    + "OR m.addressed_role IS DISTINCT FROM t.addressed_role"))
                .as("the commission hangs on the send and carries title and role of its task")
                .isEqualTo(NONE);
        }
    }

    @Test
    void the_metadata_are_split_by_key_into_references_of_the_instance_and_free_fields()
            throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            assertThat(lines(c, "SELECT r.kind, r.value, a.kind, s.name || '/' || i.number "
                    + "FROM dispatch.external_reference r "
                    + "JOIN dispatch.instance i ON i.id = r.instance_id "
                    + "JOIN dispatch.selector s ON s.id = i.selector_id "
                    + "JOIN dispatch.act a ON a.id = r.set_by_act_id "
                    + "WHERE r.tenant_id = ? ORDER BY 1, 2", TENANT_A))
                .as("items and the reference keys of the root task, each value a reference set "
                    + "by the import act; item 8 on both sides is one; the child's items none")
                .containsExactly(
                    "item | worklist://acme/item/7 | import | sprint/1",
                    "item | worklist://acme/item/8 | import | sprint/1",
                    "reference | dispatch://acme/satellite/3.0 | import | sprint/1",
                    "reference | https://example.org/acme/pull/1 | import | sprint/1");
            assertThat(lines(c, "SELECT " + ADDRESS + ", a.kind, "
                    + "to_char(a.at AT TIME ZONE 'UTC', 'HH24:MI'), f.document::text" + TASKS
                    + "JOIN dispatch.act a ON a.task_id = t.id "
                    + "JOIN dispatch.free_fields f ON f.act_id = a.id" + IN_TENANT_A
                    + "ORDER BY 1, 2", TENANT_A))
                .as("every other key keeps its value in the free fields of the send or of the "
                    + "valid deliver, the review keys and the unknown one included, and the "
                    + "child keeps its items")
                .containsExactly(
                    "sprint/1.0 | deliver | 08:20 | " + json(c,
                        "{\"review-round\": \"second round\", "
                            + "\"review-verdict\": \"changes-required\"}"),
                    "sprint/1.0 | send | 08:01 | " + json(c,
                        "{\"custom-key\": \"a value nobody declared\", "
                            + "\"review-verdict\": \"approved\"}"),
                    "sprint/1.1 | send | 08:02 | " + json(c, CutoverStock.CHILD_DISPATCH_METADATA));
            assertThat(firstReport.stream().filter(line -> line.startsWith("metadata |")).toList())
                .as("the report counts per key how many values went where")
                .containsExactlyInAnyOrder(
                    "metadata | custom-key -> free fields of send | 2",
                    "metadata | draft-key -> draft free fields | 2",
                    "metadata | handover-pr -> reference of kind reference | 2",
                    "metadata | items -> draft free fields | 2",
                    "metadata | items -> free fields of send | 2",
                    "metadata | items -> reference of kind item | 6",
                    "metadata | origin -> reference of kind reference | 2",
                    "metadata | review-round -> free fields of deliver | 2",
                    "metadata | review-verdict -> free fields of deliver | 2",
                    "metadata | review-verdict -> free fields of send | 2");
        }
    }

    @Test
    void a_curated_task_carries_a_curation_reference_to_the_address_it_was_curated_into()
            throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            assertThat(lines(c, "SELECT " + ADDRESS + ", r.value, a.kind" + TASKS
                    + "JOIN dispatch.external_reference r ON r.task_id = t.id "
                    + "JOIN dispatch.act a ON a.id = r.set_by_act_id" + IN_TENANT_A
                    + "AND r.kind = 'curation'", TENANT_A))
                .as("the address <scope_id>/<selector>/<number>.<sub>, set by the import act")
                .containsExactly("sprint/1.10 | " + SCOPE_A + "/sprint/1.0 | import");
        }
    }

    @Test
    void a_never_sent_task_is_a_draft_without_an_act_its_metadata_unchanged_its_tag_counted()
            throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            assertThat(lines(c, "SELECT " + ADDRESS + ", t.state, d.title = t.title, "
                    + "d.addressed_role, (SELECT count(*) FROM dispatch.act a "
                    + "WHERE a.task_id = t.id), (SELECT count(*) FROM dispatch.external_reference r "
                    + "WHERE r.task_id = t.id)" + TASKS
                    + "JOIN dispatch.draft d ON d.task_id = t.id" + IN_TENANT_A + "ORDER BY 1",
                    TENANT_A))
                .as("both never-sent tasks are drafts with title and role, without an act, and "
                    + "the tag of the one is gone")
                .containsExactly("sprint/1.11 | draft | t | code | 0 | 0",
                    "sprint/3.0 | draft | t | concept | 0 | 0");
            assertThat(scalar(c, "SELECT count(*) FROM dispatch.draft_free_fields f "
                    + "JOIN dispatch.draft d ON d.id = f.draft_id JOIN dispatch.task t "
                    + "ON t.id = d.task_id JOIN dispatch.instance i ON i.id = t.instance_id "
                    + "JOIN dispatch.selector s ON s.id = i.selector_id "
                    + "JOIN dispatch_legacy.selector ls ON ls.tenant_id = s.tenant_id "
                    + "AND ls.name = s.name JOIN dispatch_legacy.task lt "
                    + "ON lt.selector_id = ls.id AND lt.number = i.number AND lt.sub = t.sub "
                    + "WHERE f.document = lt.dispatch_metadata AND f.document = '"
                    + CutoverStock.DRAFT_METADATA + "'::jsonb"))
                .as("the metadata of the never-sent task stand unchanged in the free fields of "
                    + "its draft, in both tenants").isEqualTo("2");
            assertThat(firstReport).as("and its tag is counted as left behind")
                .contains("tags | left behind on never-sent tasks | 2");
        }
    }

    @Test
    void no_act_of_the_copy_names_a_permission_a_path_or_an_authentication_level()
            throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            assertThat(scalar(c, "SELECT count(*) FROM dispatch.act"))
                .as("the check must have acts to read").isNotEqualTo("0");
            assertThat(scalar(c, "SELECT count(*) FROM dispatch.act WHERE permission IS NOT NULL "
                    + "OR path IS NOT NULL OR authentication_level IS NOT NULL "
                    + "OR actor_owner_subject IS DISTINCT FROM actor_subject"))
                .as("no permission, path or authentication level; the owner is the actor")
                .isEqualTo(NONE);
            assertThat(firstReport).as("and the idempotency keys are counted, not copied")
                .contains("idempotency keys | not copied: dispatch_legacy.task_idempotency_key | 2");
        }
    }

    // -----------------------------------------------------------------------
    // Refusals, repetition, the old store left alone.
    // -----------------------------------------------------------------------

    @Test
    void a_run_without_the_actor_setting_is_refused_and_writes_nothing() {
        assertThat(withoutActor).as("refused, naming the setting")
            .contains(REFUSED,"kumbuka.cutover_actor");
        assertThat(rowCounts(storeWithoutActor)).as("every table of the new store is empty")
            .containsOnly(0L);
        assertThat(firstRun)
            .as("RED STATE, observed: the same copy with the setting runs")
            .isEqualTo(NOT_REFUSED);
        assertThat(rowCount(storeAfterFirst.get("task")))
            .as("and writes the thirty-two tasks of the stock").isEqualTo(32L);
    }

    @Test
    void a_run_under_a_role_that_does_not_read_every_tenant_is_refused_and_writes_nothing() {
        assertThat(underMigrator).as("refused under the migrating role")
            .contains(REFUSED,"reads every tenant");
        assertThat(storeUnderMigrator).as("and nothing is written").isEqualTo(storeWithoutActor);
        assertThat(firstRun).as("RED STATE, observed: under the superuser the copy runs")
            .isEqualTo(NOT_REFUSED);
    }

    @Test
    void a_second_run_changes_nothing() {
        assertThat(secondRun).as("the second copy ran").isEqualTo(NOT_REFUSED);
        assertThat(storeAfterSecond)
            .as("every table holds the same rows; only the import acts carry the time of the run")
            .isEqualTo(storeAfterFirst);
        assertThat(secondReport).as("and the report is the same").isEqualTo(firstReport);
    }

    @Test
    void a_run_after_the_opening_is_refused_and_writes_nothing() {
        assertThat(afterOpening).as("refused, naming the opening")
            .contains(REFUSED,"opened_at is set");
        assertThat(storeAfterOpening).as("the new store keeps what it held")
            .isEqualTo(storeAfterSecond);
        assertThat(secondRun).as("RED STATE, observed: before the opening the same copy ran")
            .isEqualTo(NOT_REFUSED);
    }

    @Test
    void the_copy_writes_nothing_into_the_old_store() {
        assertThat(legacyBefore.keySet())
            .as("the comparison runs over every table of the old store and the mark")
            .contains("selector", "task", "task_text", "task_tag", "task_idempotency_key",
                "idempotency_key", "exchange", "cutover", HISTORY);
        assertThat(legacyBefore.get("task")).as("and the stock is there").startsWith("32:");
        assertThat(legacyAfter).as("every table of dispatch_legacy is as it was after the rename")
            .isEqualTo(legacyBefore);
    }

    @Test
    void the_mark_is_the_one_row_of_cutover_and_every_table_of_the_new_store_carries_the_tenant()
            throws SQLException {
        try (Connection c = harness.adminConnection(url)) {
            assertThat(lines(c, "SELECT count(*), count(opened_at) FROM dispatch_legacy.cutover"))
                .as("one row, not opened").containsExactly("1 | 0");
            Set<String> tables = new TreeSet<>(CutoverStock.tables(c, OLD_SCHEMA));
            tables.remove(HISTORY);
            assertThat(tables).as("the new store holds the thirty-three tables").isEqualTo(TABLES);
            assertThat(lines(c, "SELECT cl.relname FROM pg_class cl "
                    + "JOIN pg_namespace n ON n.oid = cl.relnamespace "
                    + "WHERE n.nspname = 'dispatch' AND cl.relkind = 'r' "
                    + "AND cl.relname <> '" + HISTORY + "' AND NOT EXISTS (SELECT 1 "
                    + "FROM pg_attribute a WHERE a.attrelid = cl.oid AND a.attname = 'tenant_id' "
                    + "AND NOT a.attisdropped)"))
                .as("no table of the new store is without tenant_id").isEmpty();
        }
    }

    @Test
    void the_report_counts_the_rows_of_every_table_and_names_no_title_text_or_value()
            throws SQLException {
        List<String> rows = new ArrayList<>();
        for (String table : TABLES) {
            rows.add("rows | dispatch." + table + " | " + rowCount(storeAfterFirst.get(table)));
        }
        assertThat(firstReport.stream().filter(line -> line.startsWith("rows |")).toList())
            .as("the row count of each of the thirty-three tables").containsExactlyInAnyOrderElementsOf(rows);
        assertThat(firstReport).as("and the remaining counts of the order and of its answer")
            .contains("idempotency keys | not copied: dispatch_legacy.idempotency_key | 0",
                "instances | concluded without a return to refer to | 0",
                "remarks | placed on the first act of their task, no act before them | 0",
                "texts | of never-sent tasks besides the commission, not carried | 0");

        assertThat(planted).as("the check must have planted values to look for").hasSizeGreaterThan(60);
        List<String> spoken = new ArrayList<>(firstReport);
        spoken.addAll(List.of(withoutActor, underMigrator, afterOpening));
        try (Connection c = harness.adminConnection(url)) {
            assertThat(leaks(spoken, planted)).as("no title, text or metadata value in the "
                + "report or a refusal").isEmpty();
            assertThat(leaks(List.of("planted " + scalar(c, "SELECT title FROM "
                    + "dispatch_legacy.task ORDER BY id LIMIT 1")), planted))
                .as("RED STATE, observed: a line carrying a planted title is found")
                .hasSize(1);
        }
    }

    // -----------------------------------------------------------------------
    // Reading.
    // -----------------------------------------------------------------------

    /** The planted values a line carries, per line. */
    private static List<String> leaks(List<String> lines, Set<String> values) {
        return lines.stream()
            .filter(line -> values.stream().anyMatch(line::contains))
            .toList();
    }

    private static List<Long> rowCounts(Map<String, String> digests) {
        Map<String, String> tables = new TreeMap<>(digests);
        tables.remove(HISTORY);
        assertThat(tables.keySet()).as("the snapshot covers the new store").isEqualTo(TABLES);
        return tables.values().stream().map(CutoverCopyIT::rowCount).toList();
    }

    private static long rowCount(String digest) {
        return Long.parseLong(digest.substring(0, digest.indexOf(':')));
    }

    /** A JSON document as PostgreSQL writes it back. */
    private static String json(Connection c, String document) throws SQLException {
        return lines(c, "SELECT ?::jsonb::text", document).get(0);
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        return CutoverStock.scalar(c, sql);
    }

    /** Every row of a query, its columns joined by " | ". */
    private static List<String> lines(Connection c, String sql, Object... args)
            throws SQLException {
        List<String> lines = new ArrayList<>();
        try (PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                st.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = st.executeQuery()) {
                int columns = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    List<String> values = new ArrayList<>();
                    for (int i = 1; i <= columns; i++) {
                        values.add(rs.getString(i));
                    }
                    lines.add(String.join(" | ", values));
                }
            }
        }
        return lines;
    }
}
