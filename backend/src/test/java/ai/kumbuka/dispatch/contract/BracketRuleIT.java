/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.surface.InstanceWire;
import ai.kumbuka.dispatch.surface.Mcp;
import ai.kumbuka.dispatch.surface.SurfaceFixture;
import ai.kumbuka.dispatch.surface.TaskWire;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule of the instance, rebuilt from the bracket rule of the old kernel
 * (TAR-0004 2.3, REA-0010 S5): a closed instance takes no further act. Each of
 * the four calls on its address -- {@code conclude}, {@code withdraw}, {@code
 * reference_attach} and {@code reference_detach} -- is refused on both
 * surfaces as {@code TRANSITION_REFUSED} 409 with {@code attempted}, the state
 * {@code closed} and no call open, and writes nothing. The codes, statuses and
 * members are written here from the contract (C8.7), not read from the
 * catalogue.
 *
 * <p>A task is created only under a running instance of the scope and the
 * selector the call names, and a task its instance's closing withdrew is no
 * draft and is not deleted. The case of the old bracket that a later order
 * rebuilds stands below, deactivated with that order: the addendum after
 * delivery belongs to {@code annotate} (T11). Its body is the old kernel's,
 * kept for that order.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class BracketRuleIT {

    private static final String T11 = "rebuilt in T11: a delivered task takes no addendum to its "
        + "commission and names rework";

    private static final String PREFIX = "dispatch_";
    private static final String START = "start";
    private static final String CONCLUDE = "conclude";
    private static final String ATTACH = "reference_attach";
    private static final String CLOSED = "closed";
    private static final String ACTS = "SELECT count(*) FROM dispatch.act WHERE instance_id = ?";

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        PlatformFixture.grantDirectoryAccess();
        InstanceWire.as(identity, InstanceWire.CONSOLE);
    }

    @Test
    void a_closed_instance_refuses_each_call_on_its_address_on_both_surfaces() {
        Map<String, Object> started = InstanceWire.answer(InstanceWire.mcp(PREFIX + START,
            InstanceWire.startArguments(InstanceWire.plant(CLOSED).selector(),
                InstanceWire.minimalFields("an instance to close"))));
        String address = String.valueOf(started.get(InstanceWire.ADDRESS));
        Map<String, Object> closing = Map.of("text", "done");
        Map<String, Object> reference = Map.of("kind", "item", "value", "item-1");
        String current = InstanceWire.token(InstanceWire.answer(InstanceWire.mcp(
            PREFIX + ATTACH, Map.of(InstanceWire.ADDRESS, address, InstanceWire.FIELDS,
                reference))));

        assertThat(InstanceWire.answer(InstanceWire.mcp(PREFIX + CONCLUDE, Map.of(
                InstanceWire.ADDRESS, address, InstanceWire.TOKEN, current, InstanceWire.FIELDS,
                closing))).get(InstanceWire.FIELDS))
            .as("RED STATE, observed: the instance runs, and its first conclude closes it")
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry(InstanceWire.STATE, CLOSED);
        long acts = InstanceWire.single(ACTS, InstanceWire.instanceId(address));

        Map<String, Map<String, Object>> calls = new LinkedHashMap<>();
        calls.put(CONCLUDE, Map.of(InstanceWire.TOKEN, current, InstanceWire.FIELDS, closing));
        calls.put("withdraw", Map.of(InstanceWire.TOKEN, current, InstanceWire.FIELDS, closing));
        calls.put(ATTACH, Map.of(InstanceWire.FIELDS, Map.of("kind", "item", "value", "x")));
        calls.put("reference_detach", Map.of(InstanceWire.FIELDS, reference));
        for (Map.Entry<String, Map<String, Object>> call : calls.entrySet()) {
            Map<String, Object> arguments = new LinkedHashMap<>(call.getValue());
            arguments.put(InstanceWire.ADDRESS, address);
            Map<String, Object> overMcp = InstanceWire.refusal(InstanceWire.mcp(
                PREFIX + call.getKey(), arguments));
            assertClosed(overMcp, PREFIX + call.getKey());

            Map<String, Object> body = new LinkedHashMap<>(call.getValue());
            Object fence = body.remove(InstanceWire.TOKEN);
            Response overRest = InstanceWire.rest(address, call.getKey(), body,
                fence == null ? null : String.valueOf(fence));
            assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(409);
            assertClosed(InstanceWire.object(overRest, "$"), call.getKey());
        }
        assertThat(InstanceWire.single(ACTS, InstanceWire.instanceId(address)))
            .as("and nothing was written").isEqualTo(acts);
    }

    private static void assertClosed(Map<String, Object> refusal, String call) {
        assertThat(refusal.get(InstanceWire.REASON)).as(String.valueOf(refusal))
            .isEqualTo(InstanceWire.TRANSITION_REFUSED);
        assertThat(InstanceWire.data(refusal)).as("the call, the state, and no call open")
            .containsOnlyKeys(InstanceWire.ATTEMPTED, InstanceWire.STATE, InstanceWire.NEXT)
            .containsEntry(InstanceWire.ATTEMPTED, call)
            .containsEntry(InstanceWire.STATE, CLOSED)
            .containsEntry(InstanceWire.NEXT, List.of());
    }

    /**
     * A task is created only in a running instance: under a closed one the
     * create is {@code TRANSITION_REFUSED} 409 on both surfaces, with the
     * instance's state and no call open, and nothing is created.
     */
    @Test
    void a_task_under_a_closed_instance_is_refused_409_and_nothing_is_created() {
        TaskWire.Planted planted = TaskWire.plant("closed-parent", "review");
        String address = TaskWire.started(planted.selector());
        Map<String, Object> create = TaskWire.create(planted.selector(), address,
            Map.of("title", "a task"));
        assertThat(InstanceWire.mcp(TaskWire.CREATE, create).jsonPath()
                .getBoolean("result.isError"))
            .as("RED STATE, observed: the running instance takes the task").isFalse();
        close(address);
        long tasks = tasksOf(address);

        assertClosed(InstanceWire.refusal(InstanceWire.mcp(TaskWire.CREATE, create)),
            TaskWire.CREATE);
        Response overRest = given().contentType(ContentType.JSON)
            .body(Map.of("parent", address, InstanceWire.FIELDS, Map.of("title", "a task")))
            .post(COLLECTION + InstanceWire.SLUG + "/" + planted.selector());
        assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(409);
        assertClosed(InstanceWire.object(overRest, "$"), "create");
        assertThat(tasksOf(address)).as("no task was created").isEqualTo(tasks);
    }

    /**
     * A parent names an instance of the scope and the selector the call
     * names: one of another selector is refused by its form, before any scope
     * is read, and nothing is created.
     */
    @Test
    void a_parent_of_another_selector_is_argument_invalid_400() {
        TaskWire.Planted planted = TaskWire.plant("parent-here", "review");
        TaskWire.Planted other = TaskWire.plant("parent-there", "review");
        String elsewhere = TaskWire.started(other.selector());

        Response overMcp = InstanceWire.mcp(TaskWire.CREATE, TaskWire.create(planted.selector(),
            elsewhere, Map.of("title", "a task")));
        Map<String, Object> refusal = InstanceWire.refusal(overMcp);
        assertThat(refusal.get(InstanceWire.REASON)).isEqualTo("ARGUMENT_INVALID");
        assertThat(InstanceWire.data(refusal).get("offenders")).isEqualTo(List.of("parent"));
        assertThat(tasksOf(elsewhere)).as("nothing was created").isZero();
        assertThat(InstanceWire.mcp(TaskWire.CREATE, TaskWire.create(other.selector(),
                elsewhere, Map.of("title", "a task"))).jsonPath().getBoolean("result.isError"))
            .as("RED STATE, observed: under its own selector the parent is taken").isFalse();
    }

    /**
     * A draft whose instance was closed was withdrawn with it, and is no
     * draft any more: its delete is {@code TRANSITION_REFUSED} 409 on both
     * surfaces, with the state {@code closed} and no call open, and nothing is
     * deleted.
     */
    @Test
    void a_task_withdrawn_with_its_instance_is_not_deleted_409() {
        TaskWire.Planted planted = TaskWire.plant("closed-draft", "review");
        String address = TaskWire.started(planted.selector());
        Map<String, Object> draft = TaskWire.drafted(planted.selector(), address, "a draft");
        close(address);
        String task = String.valueOf(draft.get(InstanceWire.ADDRESS));

        assertClosed(InstanceWire.refusal(InstanceWire.mcp(TaskWire.DELETE,
            TaskWire.on(draft, Map.of()))), TaskWire.DELETE);
        Response overRest = TaskWire.rest("DELETE", task, null, Map.of(),
            String.valueOf(draft.get(InstanceWire.TOKEN)));
        assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(409);
        assertClosed(InstanceWire.object(overRest, "$"), "delete");
        assertThat(InstanceWire.rows("SELECT state, resolution FROM dispatch.task WHERE id = ?",
                TaskWire.taskId(task)))
            .as("the task stands, withdrawn with its instance")
            .containsExactly(Map.of(InstanceWire.STATE, CLOSED, "resolution", "withdrawn"));
    }

    @Test
    @Disabled(T11)
    void a_delivered_task_takes_no_addendum_to_its_commission_and_names_rework() {
        String address = SurfaceFixture.address(SurfaceFixture.open("delivered", "code"));
        Map<String, Object> addendum = Map.of("address", address,
            "fields", Map.of("part", "dispatch", "text", "one more thing"));
        SurfaceFixture.asExecutor(identity);
        String receipt = String.valueOf(Mcp.answer(Mcp.call("dispatch_claim",
            Map.of("address", address))).get("receipt"));
        SurfaceFixture.asConsole(identity);
        Mcp.answer(Mcp.call("dispatch_annotate", addendum));

        SurfaceFixture.asExecutor(identity);
        Mcp.answer(Mcp.call("dispatch_deliver", Map.of("address", address, "receipt", receipt,
            "fields", Map.of("text", "the answer"))));
        SurfaceFixture.asConsole(identity);

        Map<String, Object> overMcp = Mcp.refusal(Mcp.call("dispatch_annotate", Map.of(
            "address", address, "fields", Map.of("part", "dispatch", "text", "too late"))));
        assertThat(overMcp.get("reason")).isEqualTo("TRANSITION_REFUSED");
        assertThat(data(overMcp).get("state")).isEqualTo("delivered");
        assertThat(Mcp.nextCalls(Map.of("next", data(overMcp).get("next"))))
            .as("rework takes the addendum's place").contains("dispatch_rework");

        Response overRest = given().contentType(ContentType.JSON)
            .body(Map.of("fields", Map.of("part", "dispatch", "text", "too late")))
            .post(SurfaceFixture.item(address.substring(address.lastIndexOf('/') + 1))
                + ":annotate");
        assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(409);
        assertThat(overRest.jsonPath().getString("reason")).isEqualTo("TRANSITION_REFUSED");
        assertThat(overRest.jsonPath().getList("data.next.call", String.class))
            .contains("rework");
        assertThat(Mcp.answer(Mcp.call("dispatch_read_text", Map.of("address", address,
                "part", "addenda"))).toString())
            .as("the addendum made while active stands, and only it")
            .contains("one more thing").doesNotContain("too late");
    }

    // -----------------------------------------------------------------------

    /**
     * Concludes an instance: under the token of its last answer, and where
     * tasks of it are unfinished with the confirmation the first call hands
     * out, which withdraws them.
     */
    private static void close(String address) {
        Map<String, Object> current = InstanceWire.answer(InstanceWire.mcp(PREFIX + ATTACH,
            Map.of(InstanceWire.ADDRESS, address, InstanceWire.FIELDS,
                Map.of("kind", "item", "value", "item-" + InstanceWire.suffix()))));
        Map<String, Object> closing = new LinkedHashMap<>();
        closing.put(InstanceWire.ADDRESS, address);
        closing.put(InstanceWire.TOKEN, InstanceWire.token(current));
        closing.put(InstanceWire.FIELDS, Map.of("text", "done"));
        Response first = InstanceWire.mcp(PREFIX + CONCLUDE, closing);
        if (Boolean.TRUE.equals(first.jsonPath().get("result.isError"))) {
            closing.put("confirmation", InstanceWire.data(InstanceWire.refusal(first))
                .get("confirmation"));
            InstanceWire.answer(InstanceWire.mcp(PREFIX + CONCLUDE, closing));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> refusal) {
        return (Map<String, Object>) refusal.get("data");
    }

    private static long tasksOf(String instance) {
        return InstanceWire.single("SELECT count(*) FROM dispatch.task WHERE instance_id = ?",
            InstanceWire.instanceId(instance));
    }

    private static final String COLLECTION = "/api/";
}
