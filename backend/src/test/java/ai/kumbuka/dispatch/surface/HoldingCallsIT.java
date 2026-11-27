/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.DefinitionFixture;
import ai.kumbuka.dispatch.fixture.LogLines;
import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
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
 * The four calls that hold a task on the verb surface: who may make them on
 * each surface, what the surface offers, the form of their arguments and
 * answers, and what the log keeps of them.
 *
 * <p>Every expectation is written here from the order and the interim mapping
 * of REA-0010: the executor's realm role holds {@code dispatch.task.execute}
 * and the console's does not; the tool list and the declaration name the
 * fourteen calls the service offers and no other, the four new ones custom
 * methods on a task; {@code claim} answers the receipt beside the task and the
 * end of the lease under {@code fields}; and no log line carries a receipt, a
 * remark, a title or a role (DEC-0051).
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class HoldingCallsIT {

    private static final String PREFIX = "dispatch_";
    private static final String CLAIM = "claim";
    private static final String RENEW = "renew";
    private static final String RELEASE = "release";
    private static final String DEFER = "defer";
    private static final String RECEIPT = "receipt";
    private static final String TRANSITION = "ITEM_TRANSITION";
    private static final String INVALID = "ARGUMENT_INVALID";

    private static final List<String> FOURTEEN = List.of(PREFIX + "read_definition",
        PREFIX + "start", PREFIX + "conclude", PREFIX + "withdraw", PREFIX + "reference_attach",
        PREFIX + "reference_detach", PREFIX + "create", PREFIX + "update", PREFIX + "delete",
        PREFIX + "send", PREFIX + CLAIM, PREFIX + RENEW, PREFIX + RELEASE, PREFIX + DEFER);

    private static final String TITLE = "zz-title-5521";
    private static final String ROLE = "zz-role-5521";
    private static final String REMARK = "zz-remark-5521";

    @Inject TestIdentityAssociation identity;

    private TaskWire.Planted planted;
    private String instance;

    @BeforeEach
    void stage() {
        PlatformFixture.grantDirectoryAccess();
        InstanceWire.as(identity, InstanceWire.CONSOLE);
        planted = TaskWire.plant("holding-calls", "review");
        instance = TaskWire.started(planted.selector());
    }

    // -----------------------------------------------------------------------
    // Who may
    // -----------------------------------------------------------------------

    @Test
    void the_executor_makes_each_call_on_both_surfaces_and_the_console_none() {
        for (boolean rest : List.of(false, true)) {
            String task = sent("admitted");

            Map<String, Object> claimed = admitted(task, CLAIM, Map.of(), null, rest);
            String receipt = String.valueOf(claimed.get(RECEIPT));
            Map<String, Object> renewed = admitted(task, RENEW, Map.of(RECEIPT, receipt),
                InstanceWire.token(claimed), rest);
            admitted(task, RELEASE, Map.of(RECEIPT, receipt), null, rest);

            Map<String, Object> again = admitted(task, CLAIM, Map.of(), null, rest);
            admitted(task, DEFER, Map.of(RECEIPT, String.valueOf(again.get(RECEIPT)),
                    InstanceWire.FIELDS, Map.of("not_before", "2001-01-01T00:00:00Z")),
                InstanceWire.token(again), rest);
            assertThat(renewed.get(InstanceWire.ADDRESS)).isEqualTo(task);
        }
    }

    // -----------------------------------------------------------------------
    // What the surface offers
    // -----------------------------------------------------------------------

    @Test
    void the_tool_list_and_the_declaration_name_exactly_the_fourteen_calls() {
        assertThat(InstanceWire.rpc("tools/list", Map.of()).jsonPath()
                .getList("result.tools.name", String.class))
            .as("tools/list offers the fourteen tools: the ten of T2 to T4 and the four that "
                + "hold a task, and no other")
            .containsExactlyElementsOf(FOURTEEN);
        Response declaration = given().get("/api/declaration");
        assertThat(declaration.jsonPath().getList("calls.identity", String.class))
            .as("and the declaration names the fourteen calls").containsExactlyElementsOf(FOURTEEN);
        assertThat(declaration.jsonPath().getList("calls.effect_class", String.class)
                .subList(10, 14))
            .as("each of the four a custom method on a task")
            .containsExactly(TRANSITION, TRANSITION, TRANSITION, TRANSITION);
        assertThat(declaration.jsonPath().getList("calls.rest_name", String.class).subList(10, 14))
            .containsExactly(CLAIM, RENEW, RELEASE, DEFER);
    }

    @Test
    void a_claim_answers_the_receipt_beside_the_task_and_the_lease_end_under_fields() {
        String task = sent("answered");
        InstanceWire.as(identity, InstanceWire.EXECUTOR);

        Map<String, Object> claimed = InstanceWire.answer(InstanceWire.mcp(PREFIX + CLAIM,
            Map.of(InstanceWire.ADDRESS, task)));

        assertThat(claimed).containsKeys(InstanceWire.ADDRESS, InstanceWire.FIELDS,
            InstanceWire.TOKEN, InstanceWire.NEXT, RECEIPT);
        assertThat(fields(claimed)).containsEntry(InstanceWire.STATE, "active")
            .containsKey("lease_expires_at");
        assertThat(steps(claimed)).as("next names the holder's calls the service offers")
            .containsExactly(PREFIX + RENEW, PREFIX + RELEASE, PREFIX + DEFER);
        assertThat(String.valueOf(claimed.get(RECEIPT))).matches("[A-Za-z0-9_-]{43}");
    }

    // -----------------------------------------------------------------------
    // The form of the arguments
    // -----------------------------------------------------------------------

    @Test
    void a_duration_that_is_no_positive_iso_8601_duration_is_refused_on_both_surfaces() {
        String task = sent("durations");
        InstanceWire.as(identity, InstanceWire.EXECUTOR);
        Map<String, Long> before = DefinitionFixture.rowCounts();

        for (String duration : List.of("PT0S", "-PT1M", "2h")) {
            Map<String, Object> overMcp = InstanceWire.refusal(InstanceWire.mcp(PREFIX + CLAIM,
                Map.of(InstanceWire.ADDRESS, task, "duration", duration)));
            assertThat(overMcp.get(InstanceWire.REASON)).as(duration).isEqualTo(INVALID);
            assertThat(InstanceWire.data(overMcp).get("offenders")).isEqualTo(List.of("duration"));
            Response overRest = post(task, CLAIM, Map.of("duration", duration), null);
            assertThat(overRest.statusCode()).as(duration).isEqualTo(400);
            assertThat(overRest.jsonPath().getString(InstanceWire.REASON)).isEqualTo(INVALID);
        }
        Map<String, Object> beyond = InstanceWire.refusal(InstanceWire.mcp(PREFIX + CLAIM,
            Map.of(InstanceWire.ADDRESS, task, "duration", "PT2700000000H")));
        assertThat(beyond.get(InstanceWire.REASON))
            .as("a lease that would end past the year 294276").isEqualTo(INVALID);
        assertThat(InstanceWire.data(beyond).get("offenders")).isEqualTo(List.of("duration"));
        assertThat(DefinitionFixture.rowCounts()).as("no refused claim wrote anything")
            .isEqualTo(before);
        assertThat(InstanceWire.answer(InstanceWire.mcp(PREFIX + CLAIM,
                Map.of(InstanceWire.ADDRESS, task, "duration", "PT2H"))))
            .as("RED STATE, observed: a positive duration of the form is taken")
            .containsKey(RECEIPT);
    }

    @Test
    void a_holders_call_without_its_receipt_is_refused_as_missing_and_an_instant_out_of_form() {
        String task = sent("receipts");
        InstanceWire.as(identity, InstanceWire.EXECUTOR);
        Map<String, Object> claimed = InstanceWire.answer(InstanceWire.mcp(PREFIX + CLAIM,
            Map.of(InstanceWire.ADDRESS, task)));
        Map<String, Long> before = DefinitionFixture.rowCounts();

        for (String call : List.of(RENEW, RELEASE, DEFER)) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put(InstanceWire.ADDRESS, task);
            arguments.put(InstanceWire.TOKEN, InstanceWire.token(claimed));
            if (DEFER.equals(call)) {
                arguments.put(InstanceWire.FIELDS, Map.of("not_before", "2099-01-01T00:00:00Z"));
            } else if (RELEASE.equals(call)) {
                arguments.remove(InstanceWire.TOKEN);
            }
            Map<String, Object> refused = InstanceWire.refusal(InstanceWire.mcp(PREFIX + call,
                arguments));
            assertThat(refused.get(InstanceWire.REASON)).as(call).isEqualTo("ARGUMENT_MISSING");
            assertThat(InstanceWire.data(refused).get("offenders")).isEqualTo(List.of(RECEIPT));
        }
        Map<String, Object> unreadable = InstanceWire.refusal(InstanceWire.mcp(PREFIX + DEFER,
            Map.of(InstanceWire.ADDRESS, task, RECEIPT, String.valueOf(claimed.get(RECEIPT)),
                InstanceWire.TOKEN, InstanceWire.token(claimed),
                InstanceWire.FIELDS, Map.of("not_before", "tomorrow"))));
        assertThat(unreadable.get(InstanceWire.REASON)).isEqualTo(INVALID);
        assertThat(InstanceWire.data(unreadable).get("offenders"))
            .isEqualTo(List.of("fields.not_before"));
        assertThat(DefinitionFixture.rowCounts()).isEqualTo(before);
    }

    @Test
    void a_receipt_another_executor_presents_and_a_wrong_one_are_refused_with_the_state() {
        String task = sent("refusals");
        InstanceWire.as(identity, InstanceWire.EXECUTOR);
        Map<String, Object> claimed = InstanceWire.answer(InstanceWire.mcp(PREFIX + CLAIM,
            Map.of(InstanceWire.ADDRESS, task)));

        Response wrong = post(task, RELEASE, Map.of(RECEIPT, "zz-not-the-receipt-5521"), null);
        assertThat(wrong.statusCode()).isEqualTo(409);
        assertThat(wrong.jsonPath().getString(InstanceWire.REASON)).isEqualTo("RECEIPT_WRONG");
        assertThat(wrong.jsonPath().getString("data.state")).isEqualTo("active");
        assertThat(wrong.asString()).as("the refusal carries no receipt")
            .doesNotContain("zz-not-the-receipt-5521")
            .doesNotContain(String.valueOf(claimed.get(RECEIPT)));

        Response stale = post(task, RENEW, Map.of(RECEIPT, String.valueOf(claimed.get(RECEIPT))),
            "2001-01-01T00:00:00Z");
        assertThat(stale.statusCode()).isEqualTo(412);
        assertThat(stale.jsonPath().getString(InstanceWire.REASON))
            .isEqualTo("CONFLICT_TOKEN_STALE");
        assertThat(stale.asString()).doesNotContain(String.valueOf(claimed.get(RECEIPT)));
    }

    // -----------------------------------------------------------------------
    // What the log keeps
    // -----------------------------------------------------------------------

    @Test
    void no_log_line_carries_a_receipt_a_remark_a_title_or_a_role() {
        List<String> receipts = new java.util.ArrayList<>();
        List<LogRecord> lines = LogLines.of(PlatformLog.class, () -> {
            InstanceWire.as(identity, InstanceWire.CONSOLE, InstanceWire.EXECUTOR);
            Map<String, Object> draft = InstanceWire.answer(InstanceWire.mcp(TaskWire.CREATE,
                TaskWire.create(planted.selector(), instance,
                    Map.of("title", TITLE, "apparatus", ROLE))));
            String task = String.valueOf(InstanceWire.answer(InstanceWire.mcp(TaskWire.SEND,
                TaskWire.on(draft, Map.of()))).get(InstanceWire.ADDRESS));
            Map<String, Object> claimed = InstanceWire.answer(InstanceWire.mcp(PREFIX + CLAIM,
                Map.of(InstanceWire.ADDRESS, task)));
            receipts.add(String.valueOf(claimed.get(RECEIPT)));
            InstanceWire.refusal(InstanceWire.mcp(PREFIX + RELEASE, Map.of(InstanceWire.ADDRESS,
                task, RECEIPT, "zz-wrong-receipt-5521")));
            InstanceWire.answer(InstanceWire.mcp(PREFIX + RELEASE, Map.of(InstanceWire.ADDRESS,
                task, RECEIPT, receipts.get(0), InstanceWire.FIELDS, Map.of("remark", REMARK))));
        });

        assertThat(lines).extracting(LogLines::text)
            .as("RED STATE, observed: the capture sees the lines of the writes, one per call, "
                + "so its emptiness below is no capture that saw nothing")
            .filteredOn(line -> line.startsWith("written: "))
            .hasSize(4);
        assertThat(lines).extracting(LogLines::text)
            .as("no line carries a receipt, a remark, a title or a role (DEC-0051)")
            .noneMatch(line -> line.contains(receipts.get(0)) || line.contains("zz-wrong-receipt")
                || line.contains(REMARK) || line.contains(TITLE) || line.contains(ROLE));
        assertThat(given().get("/q/metrics").statusCode())
            .as("the service keeps no metric, so no metric can carry any of them")
            .isEqualTo(404);
    }

    // -----------------------------------------------------------------------

    /** A task created and sent by the console; answers its address. */
    private String sent(String title) {
        InstanceWire.as(identity, InstanceWire.CONSOLE);
        Map<String, Object> draft = InstanceWire.answer(InstanceWire.mcp(TaskWire.CREATE,
            TaskWire.create(planted.selector(), instance, Map.of("title", title))));
        return String.valueOf(InstanceWire.answer(InstanceWire.mcp(TaskWire.SEND,
            TaskWire.on(draft, Map.of()))).get(InstanceWire.ADDRESS));
    }

    /**
     * One call on a task by the console, refused, and then by the executor,
     * answered; answers the executor's answer.
     */
    private Map<String, Object> admitted(String task, String call, Map<String, Object> arguments,
                                         @Nullable String token, boolean rest) {
        InstanceWire.as(identity, InstanceWire.CONSOLE);
        Response refused = make(task, call, arguments, token, rest);
        if (rest) {
            assertThat(refused.statusCode()).as("%s over REST by the console", call)
                .isEqualTo(403);
            assertThat(refused.jsonPath().getString(InstanceWire.REASON))
                .isEqualTo("ROLE_INSUFFICIENT");
            assertThat(refused.jsonPath().getString("data.attempted")).isEqualTo(call);
        } else {
            Map<String, Object> envelope = InstanceWire.refusal(refused);
            assertThat(envelope.get(InstanceWire.REASON)).as("%s over MCP by the console", call)
                .isEqualTo("ROLE_INSUFFICIENT");
            assertThat(InstanceWire.data(envelope).get(InstanceWire.ATTEMPTED))
                .isEqualTo(PREFIX + call);
        }
        InstanceWire.as(identity, InstanceWire.EXECUTOR);
        Response answered = make(task, call, arguments, token, rest);
        if (rest) {
            assertThat(answered.statusCode()).as("%s over REST: %s", call, answered.asString())
                .isEqualTo(200);
            assertThat(answered.header("ETag")).as("its token as the entity tag")
                .contains(answered.jsonPath().getString(InstanceWire.TOKEN));
            return InstanceWire.object(answered, "$");
        }
        return InstanceWire.answer(answered);
    }

    private static Response make(String task, String call, Map<String, Object> arguments,
                                 @Nullable String token, boolean rest) {
        if (rest) {
            return post(task, call, arguments, token);
        }
        Map<String, Object> withAddress = new LinkedHashMap<>(arguments);
        withAddress.put(InstanceWire.ADDRESS, task);
        if (token != null) {
            withAddress.put(InstanceWire.TOKEN, token);
        }
        return InstanceWire.mcp(PREFIX + call, withAddress);
    }

    /** A POST on the REST path of a task with a colon verb, the token in If-Match. */
    private static Response post(String task, String call, Map<String, Object> body,
                                 @Nullable String token) {
        RequestSpecification request = given().contentType(ContentType.JSON);
        if (!body.isEmpty()) {
            request = request.body(body);
        }
        if (token != null) {
            request = request.header("If-Match", "\"" + token + "\"");
        }
        return request.post(InstanceWire.path(task) + ":" + call);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fields(Map<String, Object> answer) {
        return (Map<String, Object>) answer.get(InstanceWire.FIELDS);
    }

    @SuppressWarnings("unchecked")
    private static List<String> steps(Map<String, Object> answer) {
        return ((List<Map<String, Object>>) answer.get(InstanceWire.NEXT)).stream()
            .map(step -> String.valueOf(step.get("call"))).toList();
    }
}
