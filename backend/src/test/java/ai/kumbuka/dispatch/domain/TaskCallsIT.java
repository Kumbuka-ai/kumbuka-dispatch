/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.surface.InstanceWire;
import ai.kumbuka.dispatch.surface.TaskWire;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code create}, {@code update}, {@code delete} and {@code send} against a
 * version planted by SQL: the rows each writes, read back by SQL, and each
 * refusal beside its red state -- the same call corrected, which is taken.
 *
 * <p>The expectation is the call: every value checked in the store is one this
 * test handed in, the identifiers of the criteria are the ones the order has
 * the service give, and every refusal is held to the rule it names and to the
 * value it must not give back (REA-0010, catalogues "Calls that are not
 * transitions" and "Transitions of a task"; data model A.7).
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskCallsIT {

    /** A value a caller sends that no refusal may give back. */
    private static final String MARKED = "zz-marked-draft-4410";

    private static final String TITLE = "title";
    private static final String TEXT = "text";
    private static final String ACTIVITY = "activity";
    private static final String CRITERIA = "criteria";
    private static final String FREE_FIELDS = "free_fields";
    private static final String APPARATUS = "apparatus";
    private static final String CLEAR = "clear";
    private static final String REASON = "reason";
    private static final String OFFENDERS = "offenders";
    private static final String DRAFT = "draft";
    private static final String REVIEW = "review";

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        PlatformFixture.grantDirectoryAccess();
        InstanceWire.as(identity, InstanceWire.CONSOLE);
    }

    // -----------------------------------------------------------------------
    // create
    // -----------------------------------------------------------------------

    @Test
    void a_create_with_parent_writes_exactly_the_rows_of_the_draft_and_no_act() {
        TaskWire.Planted planted = TaskWire.plant("create", REVIEW);
        String instance = TaskWire.started(planted.selector());
        String concerned = String.valueOf(TaskWire.drafted(planted.selector(), instance,
            "the first").get(InstanceWire.ADDRESS));
        long actsBefore = InstanceWire.single(
            "SELECT count(*) FROM dispatch.act WHERE instance_id = ?",
            InstanceWire.instanceId(instance));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(TITLE, "the title");
        fields.put(APPARATUS, "code");
        fields.put(ACTIVITY, REVIEW);
        fields.put("concerns", concerned);
        fields.put(CRITERIA, List.of("the first criterion", "the second criterion"));
        fields.put(FREE_FIELDS, Map.of("pr", List.of("a", "b"), "note", "kept"));
        fields.put(TEXT, "the text");
        Map<String, Object> arguments = TaskWire.create(planted.selector(), instance, fields);
        arguments.put("idempotency_key", "full-" + InstanceWire.suffix());

        Map<String, Object> answer = InstanceWire.answer(InstanceWire.mcp(TaskWire.CREATE,
            arguments));

        String address = String.valueOf(answer.get(InstanceWire.ADDRESS));
        assertThat(address).as("the second task of the instance, at the next sub (D24)")
            .isEqualTo(instance + ".2");
        long task = TaskWire.taskId(address);
        assertThat(InstanceWire.rows("SELECT state, title, activity_id, concerns_task_id, "
                + "addressed_role FROM dispatch.task WHERE id = ?", task))
            .as("a task in draft, whose projection waits for its send")
            .containsExactly(row("state", DRAFT, TITLE, null, "activity_id", null,
                "concerns_task_id", null, "addressed_role", null));
        assertThat(InstanceWire.rows("SELECT title, activity_id, concerns_task_id, "
                + "addressed_role FROM dispatch.draft WHERE task_id = ?", task))
            .extracting(r -> List.of(String.valueOf(r.get(TITLE)),
                String.valueOf(r.get("activity_id")), String.valueOf(r.get("concerns_task_id")),
                String.valueOf(r.get("addressed_role"))))
            .as("one draft with the title, the activity, the task concerned and the role")
            .containsExactly(List.of("the title", String.valueOf(planted.activities().get(REVIEW)),
                String.valueOf(TaskWire.taskId(concerned)), "code"));
        assertThat(InstanceWire.rows("SELECT c.identifier, c.ordinal, t.body "
                + "FROM dispatch.draft_criterion c JOIN dispatch.draft d ON d.id = c.draft_id "
                + "JOIN dispatch.draft_text t ON t.draft_criterion_id = c.id "
                + "WHERE d.task_id = ? ORDER BY c.ordinal", task))
            .as("the criteria by the identifiers the service gives, each with its text")
            .containsExactly(row("identifier", "c1", "ordinal", 1, "body", "the first criterion"),
                row("identifier", "c2", "ordinal", 2, "body", "the second criterion"));
        assertThat(InstanceWire.rows("SELECT t.kind, t.media_type, t.body "
                + "FROM dispatch.draft_text t JOIN dispatch.draft d ON d.id = t.draft_id "
                + "WHERE d.task_id = ? AND t.draft_criterion_id IS NULL", task))
            .as("the text of the commission")
            .containsExactly(row("kind", "commission", "media_type", "text/plain",
                "body", "the text"));
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.draft_free_fields f "
                + "JOIN dispatch.draft d ON d.id = f.draft_id WHERE d.task_id = ? "
                + "AND f.document = '{\"pr\": [\"a\", \"b\"], \"note\": \"kept\"}'::jsonb", task))
            .as("the free fields as given").isEqualTo(1);
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.idempotency_key "
                + "WHERE task_id = ? AND argument_digest IS NOT NULL", task))
            .as("the key hangs on the task, with the digest of the arguments").isEqualTo(1);
        assertThat(TaskWire.rowsOf("act", task)).as("a draft writes no act (A.7)").isZero();
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.act WHERE instance_id = ?",
                InstanceWire.instanceId(instance)))
            .as("and none on its instance either").isEqualTo(actsBefore);
        Timestamp changedAt = (Timestamp) InstanceWire.rows("SELECT changed_at FROM "
            + "dispatch.draft WHERE task_id = ?", task).get(0).get("changed_at");
        assertThat(Instant.parse(String.valueOf(answer.get(InstanceWire.TOKEN))))
            .as("the token of a draft is its changed_at (D29)")
            .isEqualTo(changedAt.toInstant());
    }

    @Test
    void the_short_form_starts_an_instance_titled_as_the_task_and_adds_the_task_as_its_first() {
        TaskWire.Planted planted = TaskWire.plant("short", REVIEW);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(TITLE, "the short form");
        fields.put("trigger", "trigger-ref-1");
        fields.put("references", List.of("https://example.org/doc/1"));

        Map<String, Object> answer = InstanceWire.answer(InstanceWire.mcp(TaskWire.CREATE,
            TaskWire.create(planted.selector(), null, fields)));

        String address = String.valueOf(answer.get(InstanceWire.ADDRESS));
        String instance = "dispatch://" + InstanceWire.SLUG + "/" + planted.selector() + "/1";
        assertThat(address).as("the first task of the first instance of the selector")
            .isEqualTo(instance + ".1");
        long started = InstanceWire.instanceId(instance);
        assertThat(InstanceWire.rows("SELECT title, state, definition_version_id, next_sub "
                + "FROM dispatch.instance WHERE id = ?", started))
            .as("a running instance with the task's title, bound to the version in force")
            .containsExactly(row(TITLE, "the short form", "state", "running",
                "definition_version_id", planted.version(), "next_sub", 2));
        List<Map<String, Object>> acts = InstanceWire.rows("SELECT id, kind, task_id, "
            + "permission FROM dispatch.act WHERE instance_id = ?", started);
        assertThat(acts).as("one start act, an act of the instance and not of the task")
            .extracting(a -> List.of(String.valueOf(a.get("kind")), String.valueOf(a.get("task_id")),
                String.valueOf(a.get("permission"))))
            .containsExactly(List.of("start", "null", "dispatch.task.commission"));
        long act = ((Number) acts.get(0).get("id")).longValue();
        assertThat(InstanceWire.rows("SELECT kind, value, set_by_act_id FROM "
                + "dispatch.external_reference WHERE instance_id = ? ORDER BY id", started))
            .as("the trigger and the reference, set by the start act, as start sets them")
            .containsExactly(row("kind", "trigger", "value", "trigger-ref-1", "set_by_act_id", act),
                row("kind", "reference", "value", "https://example.org/doc/1",
                    "set_by_act_id", act));
        long task = TaskWire.taskId(address);
        assertThat(TaskWire.rowsOf(DRAFT, task)).as("and the task's draft").isEqualTo(1);
        assertThat(TaskWire.rowsOf("act", task)).as("and no act of the task").isZero();
    }

    @Test
    void the_short_form_meets_the_refusal_of_the_start_contract() {
        InstanceWire.Planted planted = InstanceWire.plant("short-contract");

        Response refused = InstanceWire.mcp(TaskWire.CREATE, TaskWire.create(planted.selector(),
            null, Map.of(TITLE, MARKED)));

        Map<String, Object> envelope = InstanceWire.refusal(refused);
        assertThat(envelope.get(REASON)).as(refused.asString()).isEqualTo("ARGUMENT_MISSING");
        assertThat(InstanceWire.data(envelope).get(OFFENDERS))
            .as("the field the start contract requires, which the short form does not take")
            .isEqualTo(List.of("fields.values.amount"));
        assertThat(refused.asString()).doesNotContain(MARKED);
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.instance i JOIN "
                + "dispatch.selector s ON s.id = i.selector_id WHERE s.name = ?",
                planted.selector()))
            .as("nothing was started").isZero();

        String instance = InstanceWire.started(planted.selector());
        assertThat(InstanceWire.mcp(TaskWire.CREATE, TaskWire.create(planted.selector(), instance,
                Map.of(TITLE, "with a parent"))).jsonPath().getBoolean("result.isError"))
            .as("RED STATE, observed: with a parent the create joins the instance start made")
            .isFalse();
    }

    @Test
    void a_parent_takes_no_trigger_and_no_references() {
        TaskWire.Planted planted = TaskWire.plant("parent-only", REVIEW);
        String instance = TaskWire.started(planted.selector());
        for (Map.Entry<String, Object> instanceOnly : Map.<String, Object>of("trigger", MARKED,
                "references", List.of(MARKED)).entrySet()) {
            Response refused = InstanceWire.mcp(TaskWire.CREATE, TaskWire.create(
                planted.selector(), instance, Map.of(TITLE, "a title", instanceOnly.getKey(),
                    instanceOnly.getValue())));
            Map<String, Object> envelope = InstanceWire.refusal(refused);
            assertThat(envelope.get(REASON)).isEqualTo("ARGUMENT_INVALID");
            assertThat(InstanceWire.data(envelope).get(OFFENDERS))
                .isEqualTo(List.of("fields." + instanceOnly.getKey()));
            assertThat(refused.asString()).doesNotContain(MARKED);
        }
    }

    // -----------------------------------------------------------------------
    // The activity of a task
    // -----------------------------------------------------------------------

    @Test
    void a_version_of_one_activity_gives_it_to_a_task_that_names_none() {
        TaskWire.Planted planted = TaskWire.plant("one-activity", REVIEW);
        String instance = TaskWire.started(planted.selector());

        Map<String, Object> answer = TaskWire.drafted(planted.selector(), instance, "unnamed");

        assertThat(InstanceWire.single("SELECT activity_id FROM dispatch.draft WHERE task_id = ?",
                TaskWire.taskId(String.valueOf(answer.get(InstanceWire.ADDRESS)))))
            .as("the version's only activity").isEqualTo(planted.activities().get(REVIEW));

        TaskWire.Planted two = TaskWire.plant("two-of-them", REVIEW, "build");
        assertThat(InstanceWire.refusal(InstanceWire.mcp(TaskWire.CREATE, TaskWire.create(
                two.selector(), TaskWire.started(two.selector()), Map.of(TITLE, "unnamed"))))
                .get(REASON))
            .as("RED STATE, observed: a version of two takes no task that names none")
            .isEqualTo("ARGUMENT_MISSING");
    }

    @Test
    void a_version_of_two_refuses_a_task_that_names_none() {
        TaskWire.Planted planted = TaskWire.plant("two-activities", REVIEW, "build");
        String instance = TaskWire.started(planted.selector());

        Response refused = InstanceWire.mcp(TaskWire.CREATE, TaskWire.create(planted.selector(),
            instance, Map.of(TITLE, MARKED)));

        Map<String, Object> envelope = InstanceWire.refusal(refused);
        assertThat(envelope.get(REASON)).isEqualTo("ARGUMENT_MISSING");
        assertThat(InstanceWire.data(envelope).get(OFFENDERS))
            .isEqualTo(List.of("fields.activity"));
        assertThat(refused.asString()).doesNotContain(MARKED);
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.task WHERE instance_id = ?",
                InstanceWire.instanceId(instance)))
            .as("nothing was written").isZero();

        assertThat(InstanceWire.mcp(TaskWire.CREATE, TaskWire.create(planted.selector(), instance,
                Map.of(TITLE, "named", ACTIVITY, "build"))).jsonPath()
                .getBoolean("result.isError"))
            .as("RED STATE, observed: the same create naming one of them is taken").isFalse();
    }

    @Test
    void an_activity_the_version_does_not_declare_is_refused() {
        TaskWire.Planted planted = TaskWire.plant("undeclared", REVIEW);
        String instance = TaskWire.started(planted.selector());

        Response refused = InstanceWire.mcp(TaskWire.CREATE, TaskWire.create(planted.selector(),
            instance, Map.of(TITLE, "a title", ACTIVITY, MARKED)));

        Map<String, Object> envelope = InstanceWire.refusal(refused);
        assertThat(envelope.get(REASON)).isEqualTo("ARGUMENT_INVALID");
        assertThat(InstanceWire.data(envelope).get(OFFENDERS))
            .isEqualTo(List.of("fields.activity"));
        assertThat(refused.asString()).as("the name is the caller's and is not repeated")
            .doesNotContain(MARKED);

        assertThat(InstanceWire.mcp(TaskWire.CREATE, TaskWire.create(planted.selector(), instance,
                Map.of(TITLE, "a title", ACTIVITY, REVIEW))).jsonPath()
                .getBoolean("result.isError"))
            .as("RED STATE, observed: the declared activity is taken").isFalse();
    }

    // -----------------------------------------------------------------------
    // update
    // -----------------------------------------------------------------------

    @Test
    void clear_empties_exactly_the_fields_it_names() {
        TaskWire.Planted planted = TaskWire.plant("clear", REVIEW);
        String instance = TaskWire.started(planted.selector());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(TITLE, "a title");
        fields.put(APPARATUS, "code");
        fields.put(CRITERIA, List.of("a criterion"));
        fields.put(FREE_FIELDS, Map.of("k", "v"));
        fields.put(TEXT, "a text");
        Map<String, Object> created = InstanceWire.answer(InstanceWire.mcp(TaskWire.CREATE,
            TaskWire.create(planted.selector(), instance, fields)));
        long task = TaskWire.taskId(String.valueOf(created.get(InstanceWire.ADDRESS)));

        Map<String, Object> updated = InstanceWire.answer(InstanceWire.mcp(TaskWire.UPDATE,
            TaskWire.on(created, Map.of(CLEAR, List.of("fields.text", "fields.criteria",
                "fields.activity")))));

        assertThat(TaskWire.rowsOf("draft_text", task)).as("the text and the criteria's texts "
            + "are gone").isZero();
        assertThat(TaskWire.rowsOf("draft_criterion", task)).as("and the criteria").isZero();
        assertThat(InstanceWire.rows("SELECT addressed_role, activity_id, title FROM "
                + "dispatch.draft WHERE task_id = ?", task))
            .as("the activity is cleared; the role and the title stay")
            .containsExactly(row("addressed_role", "code", "activity_id", null, TITLE, "a title"));
        assertThat(TaskWire.rowsOf("draft_free_fields", task)).as("and the free fields stay")
            .isEqualTo(1);
        assertThat(updated.get(InstanceWire.TOKEN)).as("the token moved on")
            .isNotEqualTo(created.get(InstanceWire.TOKEN));
    }

    @Test
    void a_field_cleared_and_given_at_once_is_refused() {
        TaskWire.Planted planted = TaskWire.plant("clear-and-value", REVIEW);
        Map<String, Object> created = TaskWire.drafted(planted.selector(),
            TaskWire.started(planted.selector()), "a title");

        Response refused = InstanceWire.mcp(TaskWire.UPDATE, TaskWire.on(created, Map.of(
            CLEAR, List.of("fields.text"), InstanceWire.FIELDS, Map.of(TEXT, MARKED))));

        Map<String, Object> envelope = InstanceWire.refusal(refused);
        assertThat(envelope.get(REASON)).isEqualTo("ARGUMENT_INVALID");
        assertThat(InstanceWire.data(envelope).get(OFFENDERS)).isEqualTo(List.of("fields.text"));
        assertThat(refused.asString()).doesNotContain(MARKED);

        Response title = InstanceWire.mcp(TaskWire.UPDATE, TaskWire.on(created,
            Map.of(CLEAR, List.of("fields.title"))));
        assertThat(InstanceWire.refusal(title).get(REASON))
            .as("and the title is none of the fields a draft may lose (C7.7)")
            .isEqualTo("ARGUMENT_INVALID");

        assertThat(InstanceWire.mcp(TaskWire.UPDATE, TaskWire.on(created,
                Map.of(CLEAR, List.of("fields.text")))).jsonPath().getBoolean("result.isError"))
            .as("RED STATE, observed: the clear alone is taken").isFalse();
    }

    @Test
    void a_stale_token_is_refused_with_the_task_as_it_stands() {
        TaskWire.Planted planted = TaskWire.plant("stale", REVIEW);
        Map<String, Object> created = TaskWire.drafted(planted.selector(),
            TaskWire.started(planted.selector()), "a title");
        Map<String, Object> changed = InstanceWire.answer(InstanceWire.mcp(TaskWire.UPDATE,
            TaskWire.on(created, Map.of(InstanceWire.FIELDS, Map.of(TITLE, "changed")))));

        Response stale = InstanceWire.mcp(TaskWire.UPDATE, TaskWire.on(created,
            Map.of(InstanceWire.FIELDS, Map.of(TITLE, MARKED))));

        Map<String, Object> envelope = InstanceWire.refusal(stale);
        assertThat(envelope.get(REASON)).isEqualTo("CONFLICT_TOKEN_STALE");
        Map<String, Object> data = InstanceWire.data(envelope);
        assertThat(List.of(data.get("address"), data.get("conflict_token"), data.get("service"),
                data.get("state")))
            .as("the task as it stands (DEC-0041)")
            .containsExactly(created.get(InstanceWire.ADDRESS), changed.get(InstanceWire.TOKEN),
                "dispatch", DRAFT);
        assertThat(stale.asString()).doesNotContain(MARKED);
        assertThat(InstanceWire.rows("SELECT title FROM dispatch.draft WHERE task_id = ?",
                TaskWire.taskId(String.valueOf(created.get(InstanceWire.ADDRESS)))))
            .as("and nothing changed").containsExactly(Map.of(TITLE, "changed"));

        Response overRest = TaskWire.rest("PATCH", String.valueOf(created.get(
            InstanceWire.ADDRESS)), null, Map.of(InstanceWire.FIELDS, Map.of(TITLE, "x")),
            String.valueOf(created.get(InstanceWire.TOKEN)));
        assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(412);

        assertThat(InstanceWire.mcp(TaskWire.UPDATE, TaskWire.on(changed,
                Map.of(InstanceWire.FIELDS, Map.of(TITLE, "again")))).jsonPath()
                .getBoolean("result.isError"))
            .as("RED STATE, observed: under the current token the update is taken").isFalse();
    }

    // -----------------------------------------------------------------------
    // Outside draft
    // -----------------------------------------------------------------------

    @Test
    void update_delete_and_send_are_refused_outside_draft() {
        TaskWire.Planted planted = TaskWire.plant("outside", REVIEW);
        String instance = TaskWire.started(planted.selector());
        Map<String, Object> created = TaskWire.drafted(planted.selector(), instance, "a title");
        Map<String, Object> sent = InstanceWire.answer(InstanceWire.mcp(TaskWire.SEND,
            TaskWire.on(created, Map.of())));

        for (String tool : List.of(TaskWire.UPDATE, TaskWire.DELETE, TaskWire.SEND)) {
            Map<String, Object> rest = TaskWire.UPDATE.equals(tool)
                ? Map.of(InstanceWire.FIELDS, Map.of(TITLE, MARKED)) : Map.of();
            Response refused = InstanceWire.mcp(tool, TaskWire.on(sent, rest));
            Map<String, Object> envelope = InstanceWire.refusal(refused);
            assertThat(envelope.get(REASON)).as("%s: %s", tool, refused.asString())
                .isEqualTo(InstanceWire.TRANSITION_REFUSED);
            Map<String, Object> data = InstanceWire.data(envelope);
            assertThat(data.get("state")).as("the state of the task, open").isEqualTo("open");
            assertThat(data.get("waiting_for")).as("and what it waits for")
                .isEqualTo("an executor to take it up");
            assertThat(refused.asString()).doesNotContain(MARKED);
        }
        assertThat(TaskWire.rowsOf("act", TaskWire.taskId(String.valueOf(
                sent.get(InstanceWire.ADDRESS)))))
            .as("nothing was written: the one act is the send").isEqualTo(1);

        Map<String, Object> other = TaskWire.drafted(planted.selector(), instance, "a draft");
        assertThat(InstanceWire.mcp(TaskWire.DELETE, TaskWire.on(other, Map.of())).jsonPath()
                .getBoolean("result.isError"))
            .as("RED STATE, observed: on a draft the same delete is taken").isFalse();
    }

    // -----------------------------------------------------------------------
    // delete
    // -----------------------------------------------------------------------

    @Test
    void delete_removes_the_task_and_its_draft_and_the_key_stays_without_its_task() {
        TaskWire.Planted planted = TaskWire.plant("delete", REVIEW);
        String instance = TaskWire.started(planted.selector());
        String key = "deleted-" + InstanceWire.suffix();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(TITLE, "to be deleted");
        fields.put(CRITERIA, List.of("a criterion"));
        fields.put(FREE_FIELDS, Map.of("k", "v"));
        fields.put(TEXT, "a text");
        Map<String, Object> arguments = TaskWire.create(planted.selector(), instance, fields);
        arguments.put("idempotency_key", key);
        Map<String, Object> created = InstanceWire.answer(InstanceWire.mcp(TaskWire.CREATE,
            arguments));
        String address = String.valueOf(created.get(InstanceWire.ADDRESS));
        long task = TaskWire.taskId(address);

        Response deleted = TaskWire.rest("DELETE", address, null, Map.of(),
            String.valueOf(created.get(InstanceWire.TOKEN)));

        assertThat(deleted.statusCode()).as(deleted.asString()).isEqualTo(200);
        assertThat(InstanceWire.object(deleted, "$"))
            .as("the address the draft had, and that alone").isEqualTo(Map.of("address", address));
        for (String table : List.of("task", DRAFT, "draft_criterion", "draft_free_fields",
                "draft_text")) {
            assertThat(TaskWire.rowsOf(table, task)).as("no row of %s is left", table).isZero();
        }
        assertThat(InstanceWire.rows("SELECT task_id FROM dispatch.idempotency_key "
                + "WHERE idempotency_key = ?", key))
            .as("the key stays, with an empty task (DEC-0015, D30)")
            .containsExactly(row("task_id", null));
    }

    // -----------------------------------------------------------------------
    // send
    // -----------------------------------------------------------------------

    @Test
    void send_writes_one_act_and_the_commission_with_every_row_of_the_draft() {
        TaskWire.Planted planted = TaskWire.plant("send", REVIEW);
        String instance = TaskWire.started(planted.selector());
        String concerned = String.valueOf(TaskWire.drafted(planted.selector(), instance,
            "concerned").get(InstanceWire.ADDRESS));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(TITLE, "to be sent");
        fields.put(APPARATUS, "code");
        fields.put("concerns", concerned);
        fields.put(CRITERIA, List.of("criterion one", "criterion two"));
        fields.put(FREE_FIELDS, Map.of("pr", List.of("x")));
        fields.put(TEXT, "the commission");
        Map<String, Object> created = InstanceWire.answer(InstanceWire.mcp(TaskWire.CREATE,
            TaskWire.create(planted.selector(), instance, fields)));
        String address = String.valueOf(created.get(InstanceWire.ADDRESS));
        long task = TaskWire.taskId(address);

        Response sent = TaskWire.rest("POST", address, "send", Map.of(),
            String.valueOf(created.get(InstanceWire.TOKEN)));

        assertThat(sent.statusCode()).as(sent.asString()).isEqualTo(200);
        List<Map<String, Object>> acts = InstanceWire.rows("SELECT id, kind, to_state, "
            + "permission, at::text AS at FROM dispatch.act WHERE task_id = ?", task);
        assertThat(acts).as("exactly one act, the send").hasSize(1);
        assertThat(acts.get(0)).containsAllEntriesOf(row("kind", "send", "to_state", "open",
            "permission", "dispatch.task.commission"));
        long act = ((Number) acts.get(0).get("id")).longValue();
        assertThat(InstanceWire.rows("SELECT title, activity_id, concerns_task_id, "
                + "addressed_role FROM dispatch.commission WHERE act_id = ?", act))
            .as("the commission, anchored at the act, from the draft")
            .containsExactly(row(TITLE, "to be sent", "activity_id",
                planted.activities().get(REVIEW), "concerns_task_id", TaskWire.taskId(concerned),
                "addressed_role", "code"));
        assertThat(InstanceWire.rows("SELECT cc.identifier, cc.ordinal, x.body FROM "
                + "dispatch.commission_criterion cc JOIN dispatch.commission c "
                + "ON c.id = cc.commission_id JOIN dispatch.content x "
                + "ON x.commission_criterion_id = cc.id WHERE c.act_id = ? ORDER BY cc.ordinal",
                act))
            .as("its criteria with the identifiers of the draft, each text as content")
            .containsExactly(row("identifier", "c1", "ordinal", 1, "body", "criterion one"),
                row("identifier", "c2", "ordinal", 2, "body", "criterion two"));
        assertThat(InstanceWire.rows("SELECT kind, body FROM dispatch.content WHERE act_id = ? "
                + "AND commission_criterion_id IS NULL", act))
            .as("the text of the commission as content")
            .containsExactly(row("kind", "commission", "body", "the commission"));
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.free_fields WHERE act_id = ? "
                + "AND document = '{\"pr\": [\"x\"]}'::jsonb", act))
            .as("the free fields at the act").isEqualTo(1);
        for (String table : TaskWire.WORKSPACE) {
            assertThat(TaskWire.rowsOf(table, task)).as("the workspace is empty: %s", table)
                .isZero();
        }
        assertThat(InstanceWire.rows("SELECT state, title, activity_id, concerns_task_id, "
                + "addressed_role FROM dispatch.task WHERE id = ?", task))
            .as("the projection is set and the task is open")
            .containsExactly(row("state", "open", TITLE, "to be sent", "activity_id",
                planted.activities().get(REVIEW), "concerns_task_id", TaskWire.taskId(concerned),
                "addressed_role", "code"));
        assertThat(sent.jsonPath().getString("conflict_token"))
            .as("from the send on the token is the time of the task's last act (D29)")
            .isNotEqualTo(created.get(InstanceWire.TOKEN));
        assertThat(sent.header("ETag")).isEqualTo("\"" + sent.jsonPath().getString(
            "conflict_token") + "\"");
    }

    @Test
    void a_draft_without_an_activity_is_refused_and_one_with_it_is_sent() {
        TaskWire.Planted planted = TaskWire.plant("no-activity", REVIEW, "build");
        String instance = TaskWire.started(planted.selector());
        Map<String, Object> created = InstanceWire.answer(InstanceWire.mcp(TaskWire.CREATE,
            TaskWire.create(planted.selector(), instance, Map.of(TITLE, "a title",
                ACTIVITY, REVIEW))));
        Map<String, Object> cleared = InstanceWire.answer(InstanceWire.mcp(TaskWire.UPDATE,
            TaskWire.on(created, Map.of(CLEAR, List.of("fields.activity")))));

        Response refused = InstanceWire.mcp(TaskWire.SEND, TaskWire.on(cleared, Map.of()));

        Map<String, Object> envelope = InstanceWire.refusal(refused);
        assertThat(envelope.get(REASON)).isEqualTo(InstanceWire.TRANSITION_REFUSED);
        assertThat(InstanceWire.data(envelope).get("state")).isEqualTo(DRAFT);
        assertThat(TaskWire.rowsOf("act", TaskWire.taskId(String.valueOf(
            cleared.get(InstanceWire.ADDRESS))))).as("nothing was sent").isZero();

        Map<String, Object> named = InstanceWire.answer(InstanceWire.mcp(TaskWire.UPDATE,
            TaskWire.on(cleared, Map.of(InstanceWire.FIELDS, Map.of(ACTIVITY, "build")))));
        assertThat(InstanceWire.mcp(TaskWire.SEND, TaskWire.on(named, Map.of())).jsonPath()
                .getBoolean("result.isError"))
            .as("RED STATE, observed: with its activity the draft is sent").isFalse();
    }

    // -----------------------------------------------------------------------
    // The REST forms
    // -----------------------------------------------------------------------

    @Test
    void rest_creates_with_the_plain_post_and_changes_with_patch() {
        TaskWire.Planted planted = TaskWire.plant("rest", REVIEW);
        String instance = TaskWire.started(planted.selector());

        Response created = given().contentType(ContentType.JSON)
            .body(Map.of("parent", instance, InstanceWire.FIELDS, Map.of(TITLE, "over rest")))
            .post("/api/" + InstanceWire.SLUG + "/" + planted.selector());

        assertThat(created.statusCode()).as(created.asString()).isEqualTo(201);
        String address = created.jsonPath().getString("address");
        assertThat(address).isEqualTo(instance + ".1");
        assertThat(created.header("Location")).endsWith(InstanceWire.path(address));
        assertThat(created.header("ETag"))
            .isEqualTo("\"" + created.jsonPath().getString("conflict_token") + "\"");
        assertThat(created.jsonPath().getList("next.call", String.class))
            .as("the calls of a draft, in the REST names").containsExactly("update", "delete",
                "send");

        Response patched = TaskWire.rest("PATCH", address, null,
            Map.of(InstanceWire.FIELDS, Map.of(TITLE, "patched")),
            created.jsonPath().getString("conflict_token"));
        assertThat(patched.statusCode()).as(patched.asString()).isEqualTo(200);
        assertThat(patched.jsonPath().getMap(InstanceWire.FIELDS)).containsEntry("state", DRAFT)
            .containsEntry("sub", 1);
    }

    // -----------------------------------------------------------------------

    /** Columns and their values, in pairs; a value may be null, an empty column. */
    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }
}
