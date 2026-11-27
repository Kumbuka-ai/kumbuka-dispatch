/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.fixture.LogLines;
import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.LogRecord;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four calls of the draft and the commission on the verb surface: who may
 * make them on each surface, where the act of a send says it came from, and
 * what the log keeps of them.
 *
 * <p>Every expectation is written here from the order and the interim mapping
 * of REA-0010: the console's realm role holds {@code dispatch.task.commission}
 * and the executor's does not, so the console makes each of the four calls on
 * both surfaces and the executor none of them, refused {@code
 * ROLE_INSUFFICIENT} with the call as its surface names it; the act of a send
 * carries the path {@code interface}, the token's subject and the permission
 * {@code dispatch.task.commission} (D17); and no log line carries a title, a
 * text, a criterion, a free field or a role (D23, DEC-0051).
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskSurfaceIT {

    private static final String PREFIX = "dispatch_";
    private static final String CREATE = "create";
    private static final String UPDATE = "update";
    private static final String DELETE = "delete";
    private static final String SEND = "send";

    private static final String TITLE = "zz-title-4410";
    private static final String TEXT = "zz-text-4410";
    private static final String CRITERION = "zz-criterion-4410";
    private static final String FREE = "zz-free-4410";
    private static final String ROLE = "zz-role-4410";

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        PlatformFixture.grantDirectoryAccess();
        InstanceWire.as(identity, InstanceWire.CONSOLE);
    }

    @Test
    void the_console_makes_each_call_on_both_surfaces_and_the_executor_none() {
        TaskWire.Planted planted = TaskWire.plant("task-admission", "review");
        String instance = TaskWire.started(planted.selector());

        for (boolean rest : List.of(false, true)) {
            InstanceWire.as(identity, InstanceWire.EXECUTOR);
            assertRefused(create(planted.selector(), instance, rest), rest, CREATE);
            InstanceWire.as(identity, InstanceWire.CONSOLE);
            Map<String, Object> draft = answered(create(planted.selector(), instance, rest), rest,
                CREATE);

            InstanceWire.as(identity, InstanceWire.EXECUTOR);
            assertRefused(on(draft, UPDATE, rest), rest, UPDATE);
            InstanceWire.as(identity, InstanceWire.CONSOLE);
            draft = answered(on(draft, UPDATE, rest), rest, UPDATE);

            InstanceWire.as(identity, InstanceWire.EXECUTOR);
            assertRefused(on(draft, SEND, rest), rest, SEND);
            InstanceWire.as(identity, InstanceWire.CONSOLE);
            answered(on(draft, SEND, rest), rest, SEND);

            Map<String, Object> other = answered(create(planted.selector(), instance, rest), rest,
                CREATE);
            InstanceWire.as(identity, InstanceWire.EXECUTOR);
            assertRefused(on(other, DELETE, rest), rest, DELETE);
            InstanceWire.as(identity, InstanceWire.CONSOLE);
            answered(on(other, DELETE, rest), rest, DELETE);
        }
    }

    @Test
    void the_act_of_a_send_carries_the_path_interface_the_subject_and_its_permission() {
        TaskWire.Planted planted = TaskWire.plant("task-provenance", "review");
        Map<String, Object> draft = TaskWire.drafted(planted.selector(),
            TaskWire.started(planted.selector()), "a title");

        InstanceWire.answer(InstanceWire.mcp(TaskWire.SEND, TaskWire.on(draft, Map.of())));

        assertThat(InstanceWire.rows("SELECT kind, path, actor_subject, actor_owner_subject, "
                + "authentication_level, permission FROM dispatch.act WHERE task_id = ?",
                TaskWire.taskId(String.valueOf(draft.get(InstanceWire.ADDRESS)))))
            .extracting(act -> List.of(String.valueOf(act.get("kind")),
                String.valueOf(act.get("path")), String.valueOf(act.get("actor_subject")),
                String.valueOf(act.get("actor_owner_subject")),
                String.valueOf(act.get("authentication_level")),
                String.valueOf(act.get("permission"))))
            .as("one act, the send, over an interface by the token's subject, with no owner and "
                + "no authentication level, under the permission of the call")
            .containsExactly(List.of("send", "interface", InstanceWire.SUBJECT, "null", "null",
                "dispatch.task.commission"));
    }

    @Test
    void no_log_line_carries_a_title_a_text_a_criterion_a_free_field_or_a_role() {
        TaskWire.Planted planted = TaskWire.plant("task-log", "review");
        String instance = TaskWire.started(planted.selector());
        List<LogRecord> lines = LogLines.of(PlatformLog.class, () -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("title", TITLE);
            fields.put("apparatus", ROLE);
            fields.put("criteria", List.of(CRITERION));
            fields.put("free_fields", Map.of("k", FREE));
            fields.put("text", TEXT);
            Map<String, Object> draft = InstanceWire.answer(InstanceWire.mcp(TaskWire.CREATE,
                TaskWire.create(planted.selector(), instance, fields)));
            InstanceWire.refusal(InstanceWire.mcp(TaskWire.CREATE, TaskWire.create(
                planted.selector(), instance, Map.of("title", TITLE, "activity", TEXT))));
            Map<String, Object> updated = InstanceWire.answer(InstanceWire.mcp(TaskWire.UPDATE,
                TaskWire.on(draft, Map.of(InstanceWire.FIELDS, Map.of("text", TEXT + "-2")))));
            InstanceWire.answer(InstanceWire.mcp(TaskWire.SEND, TaskWire.on(updated, Map.of())));
            Map<String, Object> other = InstanceWire.answer(InstanceWire.mcp(TaskWire.CREATE,
                TaskWire.create(planted.selector(), instance, fields)));
            InstanceWire.answer(InstanceWire.mcp(TaskWire.DELETE, TaskWire.on(other, Map.of())));
        });

        assertThat(lines).extracting(LogLines::text)
            .as("RED STATE, observed: the capture sees the lines of the writes, one per call, "
                + "so its emptiness below is no capture that saw nothing")
            .filteredOn(line -> line.startsWith("written: "))
            .hasSize(5);
        assertThat(lines).extracting(LogLines::text)
            .as("no line carries a title, a text, a criterion, a free field or a role (D23, "
                + "DEC-0051)")
            .noneMatch(line -> line.contains(TITLE) || line.contains(TEXT)
                || line.contains(CRITERION) || line.contains(FREE) || line.contains(ROLE)
                || line.contains(planted.selector()));
    }

    // -----------------------------------------------------------------------

    private static Response create(String selector, String instance, boolean rest) {
        if (rest) {
            return given().contentType(ContentType.JSON)
                .body(Map.of("parent", instance, InstanceWire.FIELDS, Map.of("title", "a title")))
                .post("/api/" + InstanceWire.SLUG + "/" + selector);
        }
        return InstanceWire.mcp(TaskWire.CREATE, TaskWire.create(selector, instance,
            Map.of("title", "a title")));
    }

    /** One call on a task, by its answer: its address and its token, on either surface. */
    private static Response on(Map<String, Object> task, String call, boolean rest) {
        Map<String, Object> body = UPDATE.equals(call)
            ? Map.of(InstanceWire.FIELDS, Map.of("title", "changed")) : Map.of();
        if (!rest) {
            return InstanceWire.mcp(PREFIX + call, TaskWire.on(task, body));
        }
        String address = String.valueOf(task.get(InstanceWire.ADDRESS));
        String token = String.valueOf(task.get(InstanceWire.TOKEN));
        return switch (call) {
            case UPDATE -> TaskWire.rest("PATCH", address, null, body, token);
            case DELETE -> TaskWire.rest("DELETE", address, null, body, token);
            default -> TaskWire.rest("POST", address, call, body, token);
        };
    }

    private static Map<String, Object> answered(Response response, boolean rest,
                                                @Nullable String call) {
        if (rest) {
            assertThat(response.statusCode()).as("%s over REST: %s", call, response.asString())
                .isIn(200, 201);
            return InstanceWire.object(response, "$");
        }
        return InstanceWire.answer(response);
    }

    private static void assertRefused(Response response, boolean rest, String call) {
        if (rest) {
            assertThat(response.statusCode()).as("%s over REST by the executor: %s", call,
                response.asString()).isEqualTo(403);
            assertThat(response.jsonPath().getString("reason")).isEqualTo("ROLE_INSUFFICIENT");
            assertThat(response.jsonPath().getString("data.attempted")).isEqualTo(call);
        } else {
            Map<String, Object> refusal = InstanceWire.refusal(response);
            assertThat(refusal.get("reason")).as("%s over MCP by the executor", call)
                .isEqualTo("ROLE_INSUFFICIENT");
            assertThat(InstanceWire.data(refusal).get("attempted")).isEqualTo(PREFIX + call);
        }
    }
}
