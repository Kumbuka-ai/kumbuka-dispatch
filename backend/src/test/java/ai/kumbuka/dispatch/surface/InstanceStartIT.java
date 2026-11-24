/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code start} against a version planted by SQL: the rows it writes, read
 * back by SQL, and the four refusals of the start contract, each beside its
 * red state -- the same call with the value corrected, which is taken.
 *
 * <p>The expectation is the call: every value checked in the store is one
 * this test handed in, and every refusal is held to the rule it names and to
 * the value it must not give back (DEC-0056/C7, C21).
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class InstanceStartIT {

    /** A value a caller sends that no refusal may give back. */
    private static final String MARKED = "zz-marked-start-7319";

    private static final String START = "dispatch_start";
    private static final String VALUES = "values";
    private static final String AMOUNT = "amount";
    private static final String URGENT = "urgent";
    private static final String SUMMARY = "summary";
    private static final String PRIORITY = "priority";
    private static final String DUE = "due";
    private static final String TITLE = "title";
    private static final String STATE = "state";
    private static final String RUNNING = "running";
    private static final String KIND = "kind";
    private static final String VALUE = "value";
    private static final String SET_BY = "set_by_act_id";
    private static final String ENDED_BY = "ended_by_act_id";
    private static final String FIELDS = "fields";
    private static final String API = "/api/";
    private static final String OFFENDERS = "offenders";
    private static final String NUMBER = "number";
    private static final String NONE = "null";
    private static final String YES = "true";

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        PlatformFixture.grantDirectoryAccess();
        InstanceWire.as(identity, InstanceWire.CONSOLE);
    }

    @Test
    void a_start_writes_exactly_the_rows_of_the_call() {
        InstanceWire.Planted planted = InstanceWire.plant("start");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(AMOUNT, 12.5);
        values.put(URGENT, true);
        values.put(DUE, "2026-12-01");
        values.put(SUMMARY, "the summary");
        values.put(PRIORITY, "high");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(TITLE, "the title");
        fields.put("input", "the input text");
        fields.put("trigger", "trigger-ref-7");
        fields.put("items", List.of("item-ref-8"));
        fields.put("references", List.of("https://example.org/doc/1"));
        fields.put(VALUES, values);

        Map<String, Object> answer = InstanceWire.answer(InstanceWire.mcp(START,
            InstanceWire.startArguments(planted.selector(), fields)));

        String address = String.valueOf(answer.get("address"));
        assertThat(address).as("the address of the first instance of the selector")
            .isEqualTo("dispatch://" + InstanceWire.SLUG + "/" + planted.selector() + "/1");
        long instance = InstanceWire.instanceId(address);
        assertThat(InstanceWire.rows("SELECT title, state, resolution, definition_version_id, "
                + "number FROM dispatch.instance WHERE id = ?", instance))
            .as("one running instance with its title, bound to the version in force")
            .containsExactly(row(TITLE, "the title", STATE, RUNNING, "resolution", null,
                "definition_version_id", planted.version(), NUMBER, 1L));

        List<Map<String, Object>> acts = InstanceWire.rows("SELECT id, sequence, kind, task_id, "
            + "to_state FROM dispatch.act WHERE instance_id = ?", instance);
        assertThat(acts).as("one start act, the first of the instance, about no task")
            .hasSize(1);
        long act = ((Number) acts.get(0).get("id")).longValue();
        assertThat(acts.get(0)).containsAllEntriesOf(row("sequence", 1, KIND, "start",
            "task_id", null, "to_state", RUNNING));

        assertThat(InstanceWire.rows("SELECT kind, media_type, body FROM dispatch.content "
                + "WHERE act_id = ?", act))
            .as("the input text as content of the kind instance_input at the start act (D19)")
            .containsExactly(row(KIND, "instance_input", "media_type", "text/plain",
                "body", "the input text"));

        assertThat(InstanceWire.rows("SELECT kind, value, set_by_act_id, ended_by_act_id "
                + "FROM dispatch.external_reference WHERE instance_id = ? ORDER BY id", instance))
            .as("the trigger (D4) and one reference of each kind (D12), set by the start act")
            .containsExactly(
                row(KIND, "trigger", VALUE, "trigger-ref-7", SET_BY, act,
                    ENDED_BY, null),
                row(KIND, "item", VALUE, "item-ref-8", SET_BY, act,
                    ENDED_BY, null),
                row(KIND, "reference", VALUE, "https://example.org/doc/1", SET_BY, act,
                    ENDED_BY, null));

        List<Map<String, Object>> written = InstanceWire.rows("SELECT f.name, v.value_type, "
            + "v.boolean_value, v.number_value, v.date_value, v.text_value, v.choice_value_id "
            + "FROM dispatch.field_value v JOIN dispatch.control_field f "
            + "ON f.id = v.control_field_id WHERE v.act_id = ? ORDER BY f.name", act);
        assertThat(written).as("one field value per field of the call, each in its typed column")
            .containsExactly(
                typed(AMOUNT, NUMBER, null, new BigDecimal("12.5"), null, null, null),
                typed(DUE, "date", null, null, Date.valueOf("2026-12-01"), null, null),
                typed(PRIORITY, "choice", null, null, null, null, planted.high()),
                typed(SUMMARY, "text", null, null, null, "the summary", null),
                typed(URGENT, "boolean", true, null, null, null, null));

        assertThat(InstanceWire.rows("SELECT f.name, p.accepted_by_act_id, "
                + "(p.field_value_id = v.id) AS of_the_start, "
                + "(p.number_value IS NOT DISTINCT FROM v.number_value "
                + "AND p.choice_value_id IS NOT DISTINCT FROM v.choice_value_id "
                + "AND p.boolean_value IS NOT DISTINCT FROM v.boolean_value "
                + "AND p.date_value IS NOT DISTINCT FROM v.date_value "
                + "AND p.text_value IS NOT DISTINCT FROM v.text_value) AS same_value "
                + "FROM dispatch.instance_field_value p JOIN dispatch.control_field f "
                + "ON f.id = p.control_field_id JOIN dispatch.field_value v "
                + "ON v.id = p.field_value_id WHERE p.instance_id = ? ORDER BY f.name", instance))
            .as("and the projection holds each value in force, the value the start wrote, "
                + "accepted by no act (D18)")
            .extracting(r -> List.of(String.valueOf(r.get("name")),
                String.valueOf(r.get("accepted_by_act_id")), String.valueOf(r.get("of_the_start")),
                String.valueOf(r.get("same_value"))))
            .containsExactly(List.of(AMOUNT, NONE, YES, YES), List.of(DUE, NONE, YES, YES),
                List.of(PRIORITY, NONE, YES, YES), List.of(SUMMARY, NONE, YES, YES),
                List.of(URGENT, NONE, YES, YES));
    }

    @Test
    void the_answer_names_the_instance_and_rest_answers_it_as_a_thing_created() {
        InstanceWire.Planted planted = InstanceWire.plant("created");

        Response rest = given().contentType(ContentType.JSON)
            .body(Map.of(FIELDS, InstanceWire.minimalFields("over rest")))
            .post(API + InstanceWire.SLUG + "/" + planted.selector() + ":start");

        assertThat(rest.statusCode()).as(rest.asString()).isEqualTo(201);
        assertThat(rest.header("Location"))
            .as("the path of the new instance").endsWith(API + InstanceWire.SLUG + "/"
                + planted.selector() + "/1");
        assertThat(rest.jsonPath().getString("address"))
            .isEqualTo("dispatch://" + InstanceWire.SLUG + "/" + planted.selector() + "/1");
        assertThat(rest.header("ETag")).as("the conflict token as ETag")
            .isEqualTo("\"" + rest.jsonPath().getString("conflict_token") + "\"");
        assertThat(rest.jsonPath().getMap(FIELDS)).containsEntry(TITLE, "over rest")
            .containsEntry(STATE, RUNNING).containsEntry(NUMBER, 1)
            .containsEntry("version", 1);
        assertThat(rest.jsonPath().getList("next.call", String.class))
            .as("the calls open to the console, in the REST names")
            .containsExactly("conclude", "withdraw", "reference_attach", "reference_detach");
    }

    // -----------------------------------------------------------------------
    // The four refusals of the start contract
    // -----------------------------------------------------------------------

    /**
     * Each rule of the start contract: the values it refuses, the code and the
     * member that names the field, and the values corrected, which are taken.
     */
    static Stream<Arguments> refusals() {
        return Stream.of(
            Arguments.of("a required field missing",
                Map.of(SUMMARY, MARKED), "ARGUMENT_MISSING", OFFENDERS,
                List.of("fields.values.amount"), Map.of(SUMMARY, MARKED, AMOUNT, 3)),
            Arguments.of("a field the contract does not name",
                Map.of(AMOUNT, 1, "internal", MARKED), "ARGUMENT_UNKNOWN", "accepts",
                List.of("fields.values.amount", "fields.values.urgent", "fields.values.due",
                    "fields.values.summary", "fields.values.priority"),
                Map.of(AMOUNT, 1, SUMMARY, MARKED)),
            Arguments.of("a value of another type", Map.of(AMOUNT, 1, URGENT, MARKED),
                "ARGUMENT_INVALID", OFFENDERS, List.of("fields.values.urgent"),
                Map.of(AMOUNT, 1, URGENT, false)),
            Arguments.of("a value outside its enumeration", Map.of(AMOUNT, 1, PRIORITY, MARKED),
                "ARGUMENT_INVALID", OFFENDERS, List.of("fields.values.priority"),
                Map.of(AMOUNT, 1, PRIORITY, "low")));
    }

    /**
     * A refused start: its code and the member naming the field, on both
     * surfaces, no value given back, and nothing written -- the selector's
     * counter stands and no instance stands under it. Then the red state: the
     * same start with the value corrected is taken.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    void each_rule_of_the_start_contract_refuses_naming_the_field_and_no_value(
            String rule, Map<String, Object> refusedValues, String code, String member,
            List<String> paths, Map<String, Object> takenValues) {
        InstanceWire.Planted planted = InstanceWire.plant("contract");
        Map<String, Object> fields = new HashMap<>(InstanceWire.minimalFields(MARKED));
        fields.put(VALUES, refusedValues);

        Response overMcp = InstanceWire.mcp(START,
            InstanceWire.startArguments(planted.selector(), fields));
        Map<String, Object> refusal = InstanceWire.refusal(overMcp);
        assertThat(refusal.get("reason")).as("%s: %s", rule, overMcp.asString()).isEqualTo(code);
        assertThat(InstanceWire.data(refusal).get(member)).as("the rule names the field")
            .isEqualTo(paths);
        assertThat(overMcp.asString()).as("and gives back no value the caller sent")
            .doesNotContain(MARKED);

        Response overRest = given().contentType(ContentType.JSON).body(Map.of(FIELDS, fields))
            .post(API + InstanceWire.SLUG + "/" + planted.selector() + ":start");
        assertThat(overRest.jsonPath().getString("reason")).as(overRest.asString())
            .isEqualTo(code);
        assertThat(overRest.asString()).doesNotContain(MARKED);

        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.instance i "
                + "JOIN dispatch.selector s ON s.id = i.selector_id WHERE s.name = ?",
                planted.selector()))
            .as("nothing was started").isZero();
        assertThat(InstanceWire.single("SELECT next_number FROM dispatch.selector WHERE name = ?",
                planted.selector()))
            .as("and no number was taken").isEqualTo(1);

        fields.put(VALUES, takenValues);
        assertThat(InstanceWire.mcp(START, InstanceWire.startArguments(planted.selector(),
                fields)).jsonPath().getBoolean("result.isError"))
            .as("RED STATE, observed: the same start with the value corrected is taken")
            .isFalse();
    }

    // -----------------------------------------------------------------------

    private static Map<String, Object> typed(String name, String type, Object bool,
                                             Object number, Object date, Object text,
                                             Object choice) {
        return row("name", name, "value_type", type, "boolean_value", bool,
            "number_value", number, "date_value", date, "text_value", text,
            "choice_value_id", choice);
    }

    /** Columns and their values, in pairs; a value may be null, an empty column. */
    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }
}
