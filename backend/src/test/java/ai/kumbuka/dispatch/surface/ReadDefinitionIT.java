/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.boundary.RecordedStatements;
import ai.kumbuka.dispatch.domain.DefinitionFixture;
import ai.kumbuka.dispatch.domain.InstanceFixture;
import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.security.StringPermission;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code read_definition} on both surfaces, against versions planted by SQL:
 * what it answers, which version, what it refuses and to whom, how many
 * statements it sends, and that it writes nothing.
 *
 * <p>Every expectation is the planting, written here, or the store itself:
 * the answer is compared with the rows this test planted, the enumerations of
 * the system fields with the CHECK conditions of the migrated store read from
 * {@code pg_constraint}, and the tool list and the declaration with the one
 * call the order of this step names. Nothing is read off the code under test.
 *
 * <p>The callers are the probing subject of the substrate, an active member of
 * the tenant, under the realm roles the interim source of permissions reads.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ReadDefinitionIT {

    private static final UUID TENANT = UUID.fromString(SubstrateDatabaseResource.TENANT_ID);
    private static final UUID SCOPE = UUID.fromString(SubstrateDatabaseResource.SCOPE_ID);
    private static final String SLUG = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    private static final String SUBJECT = SubstrateDatabaseResource.PROBE_SUBJECT;
    private static final String CONSOLE = "dispatch-console";
    private static final String EXECUTOR = "dispatch-executor";
    private static final String TOOL = "dispatch_read_definition";
    private static final String CALL = "read_definition";
    private static final String SCOPE_ARG = "scope";
    private static final String SELECTOR_ARG = "selector";
    private static final String VERSION_ARG = "version";
    private static final String STATE = "state";
    private static final String ACTIVE = "active";
    private static final String RETIRED = "retired";
    private static final String FIELDS = "fields";
    private static final String NAME = "name";
    private static final String TYPE = "type";
    private static final String VALUES = "values";
    private static final String ENUMERATION = "enumeration";
    private static final String CHOICE = "choice";
    private static final String PRIORITY = "priority";
    private static final String CHANNEL = "channel";
    private static final String URGENT = "urgent";
    private static final String AMOUNT = "amount";
    private static final String SUMMARY = "summary";
    private static final String CONTACT = "contact";
    private static final String DONE = "done";
    private static final String FIELD = "field";
    private static final String REQUIRED = "required";
    private static final String VERDICT_REQUIRED = "verdict_required";
    private static final String OUTCOME_REQUIRED = "outcome_required";
    private static final String OUTCOME_LABELS = "outcome_labels";
    private static final String ABSENT = "absent-";
    private static final String REASON = "reason";
    private static final String IS_ERROR = "result.isError";
    private static final String MCP_REASON = "result.structuredContent.reason";
    private static final String NOT_FOUND = "NOT_FOUND";
    private static final String ROLE_INSUFFICIENT = "ROLE_INSUFFICIENT";
    private static final String ARGUMENT_INVALID = "ARGUMENT_INVALID";

    @Inject TestIdentityAssociation identity;

    private final DefinitionFixture plant = new DefinitionFixture(TENANT, SCOPE);

    @BeforeEach
    void stage() {
        PlatformFixture.grantDirectoryAccess();
        as(CONSOLE, EXECUTOR);
    }

    // -----------------------------------------------------------------------
    // What it answers
    // -----------------------------------------------------------------------

    @Test
    void the_answer_carries_every_element_of_the_planted_version_on_both_surfaces() {
        String selector = plantedSelector();

        Response rest = rest(selector, "");
        assertThat(rest.statusCode()).as("the active version is read").isEqualTo(200);
        Map<String, Object> answered = new LinkedHashMap<>(rest.jsonPath().getMap(FIELDS));
        assertThat(rest.jsonPath().getMap("$").keySet())
            .as("the answer carries fields alone: a version has no address in the address "
                + "form and no conflict token, since no call writes one")
            .containsExactly(FIELDS);
        assertThat(answered.remove("system_enumerations"))
            .as("and the enumerations of the system fields, held against the store below")
            .isNotNull();
        assertThat(answered).as("every element of the planting, as planted")
            .isEqualTo(expectedActiveVersion(selector));

        Response mcp = mcp(arguments(selector));
        assertThat(mcp.jsonPath().getBoolean(IS_ERROR)).isFalse();
        assertThat(mcp.jsonPath().getMap("result.structuredContent"))
            .as("the assistant surface answers what the REST surface answers")
            .isEqualTo(rest.jsonPath().getMap("$"));
    }

    @Test
    void without_a_version_the_active_one_answers_and_with_one_the_one_named() {
        String selector = plantedSelector();

        assertThat(rest(selector, "").jsonPath().getMap(FIELDS))
            .as("without version: the active version, number 2")
            .containsEntry(VERSION_ARG, 2).containsEntry(STATE, ACTIVE);

        Map<String, Object> first = rest(selector, "?version=1").jsonPath().getMap(FIELDS);
        assertThat(first).as("with version 1: the retired version 1, as planted")
            .containsEntry(VERSION_ARG, 1).containsEntry(STATE, RETIRED)
            .containsEntry(VERDICT_REQUIRED, false).containsEntry(OUTCOME_REQUIRED, true);
        for (String empty : List.of(OUTCOME_LABELS, "schema", "enumerations", "activities",
                "start_contract")) {
            assertThat(first.get(empty))
                .as("a version that declares no %s answers an empty list", empty)
                .isEqualTo(List.of());
        }

        Map<String, Object> named = new HashMap<>(arguments(selector));
        named.put(VERSION_ARG, 1);
        assertThat(mcp(named).jsonPath().getMap("result.structuredContent.fields"))
            .as("and the same on the assistant surface")
            .isEqualTo(first);
    }

    // -----------------------------------------------------------------------
    // What it refuses
    // -----------------------------------------------------------------------

    @Test
    void an_unknown_selector_an_unknown_version_and_an_unseeded_version_answer_the_same() {
        String selector = plantedSelector();
        String unseeded = "unseeded-" + suffix();
        long version = plant.version(plant.selector(unseeded), 1, ACTIVE, false, false, false);
        Map<String, Object> unknownVersion = new HashMap<>(arguments(selector));
        unknownVersion.put(VERSION_ARG, 9);

        List<Response> rest = List.of(rest(ABSENT + suffix(), ""),
            rest(selector, "?version=9"), rest(unseeded, ""));
        for (Response refused : rest) {
            assertThat(refused.statusCode()).isEqualTo(404);
            assertThat(reason(refused)).isEqualTo(NOT_FOUND);
        }
        assertThat(rest.stream().map(Response::asString).distinct().toList())
            .as("an unknown selector, an unknown version and an unseeded version answer one "
                + "NOT_FOUND, byte for byte, and say nothing of which it was")
            .hasSize(1);

        List<Response> mcp = List.of(mcp(arguments(ABSENT + suffix())), mcp(unknownVersion),
            mcp(arguments(unseeded)));
        for (Response refused : mcp) {
            assertThat(refused.jsonPath().getBoolean(IS_ERROR)).isTrue();
        }
        assertThat(mcp.stream()
                .map(refused -> refused.jsonPath().getString("result.content[0].text"))
                .distinct().toList())
            .as("and so on the assistant surface, as the REST body is")
            .containsExactly(rest.get(0).asString());

        DefinitionFixture.markSeed(version, true);
        assertThat(rest(unseeded, "").statusCode())
            .as("RED STATE, observed: the same row with seed = true is answered, so the "
                + "refusal above was the seed mark")
            .isEqualTo(200);
    }

    @Test
    void a_caller_without_instance_read_is_refused_and_either_role_alone_is_admitted() {
        String selector = plantedSelector();

        as();
        assertThat(reason(rest(selector, "")))
            .as("a token holding no permission of this service sees no scope of it: NOT_FOUND "
                + "on REST (status classes of C8.7)")
            .isEqualTo(NOT_FOUND);
        assertThat(mcp(arguments(selector)).jsonPath().getString(MCP_REASON))
            .as("and on the assistant surface").isEqualTo(NOT_FOUND);

        asHolderOnlyOf("dispatch.task.tag");
        Response refused = rest(selector, "");
        assertThat(refused.statusCode())
            .as("a caller holding another permission of this service, and not "
                + "dispatch.instance.read, sees the scope and is refused the call")
            .isEqualTo(403);
        assertThat(reason(refused)).isEqualTo(ROLE_INSUFFICIENT);
        assertThat(refused.jsonPath().getString("data.attempted")).isEqualTo(CALL);
        Response refusedMcp = mcp(arguments(selector));
        assertThat(refusedMcp.jsonPath().getString(MCP_REASON))
            .as("and on the assistant surface, naming the tool")
            .isEqualTo(ROLE_INSUFFICIENT);
        assertThat(refusedMcp.jsonPath().getString("result.structuredContent.data.attempted"))
            .isEqualTo(TOOL);

        for (String role : List.of(EXECUTOR, CONSOLE)) {
            as(role);
            assertThat(rest(selector, "").statusCode())
                .as("%s alone holds dispatch.instance.read and reads on REST", role)
                .isEqualTo(200);
            assertThat(mcp(arguments(selector)).jsonPath().getBoolean(IS_ERROR))
                .as("%s alone reads on the assistant surface", role)
                .isFalse();
        }
    }

    @Test
    void the_form_of_a_call_is_refused_before_its_scope_is_read() {
        String selector = plantedSelector();

        assertThat(reason(rest(selector, "?draft=1")))
            .as("an argument the call does not declare").isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(reason(rest(selector, "?version=0"))).as("a version below 1")
            .isEqualTo(ARGUMENT_INVALID);
        assertThat(reason(rest(selector, "?version=first"))).as("a version that is no number")
            .isEqualTo(ARGUMENT_INVALID);
        assertThat(reason(rest(selector, "?version=1&version=2"))).as("a version given twice")
            .isEqualTo(ARGUMENT_INVALID);
        assertThat(reason(rest(selector, "?selector=" + selector)))
            .as("a selector given in the path and in the query").isEqualTo(ARGUMENT_INVALID);
        assertThat(reason(rest("Not_A_Selector", ""))).as("a selector outside the grammar")
            .isEqualTo("ADDRESS_MALFORMED");

        Response verb = given().get(collection(SLUG, selector) + ":query");
        assertThat(verb.statusCode()).as("a colon verb a collection does not carry")
            .isEqualTo(405);
        assertThat(reason(verb)).isEqualTo("VERB_NOT_CARRIED");
        assertThat(verb.jsonPath().getList("data.accepts")).containsExactly(CALL);
        assertThat(verb.header("Allow")).isEqualTo("GET");
        Response method = given().post(collection(SLUG, selector));
        assertThat(method.statusCode()).as("a method no route at a collection takes")
            .isEqualTo(405);
        assertThat(method.jsonPath().getList("data.accepts")).containsExactly(CALL);

        assertThat(mcpReason(Map.of(SELECTOR_ARG, selector))).as("a scope missing")
            .isEqualTo("ARGUMENT_MISSING");
        assertThat(mcpReason(Map.of(SCOPE_ARG, 7, SELECTOR_ARG, selector)))
            .as("a scope that is no string").isEqualTo(ARGUMENT_INVALID);
        assertThat(mcpReason(Map.of(SCOPE_ARG, SLUG, SELECTOR_ARG, selector, VERSION_ARG, "1")))
            .as("a version sent as text on the assistant surface").isEqualTo(ARGUMENT_INVALID);
        assertThat(mcpReason(Map.of(SCOPE_ARG, SLUG, SELECTOR_ARG, selector, VERSION_ARG, 1.5)))
            .as("a version that is no whole number").isEqualTo(ARGUMENT_INVALID);

        assertThat(reason(given().get(collection("no-such-scope", selector))))
            .as("a scope the caller cannot see").isEqualTo(NOT_FOUND);
        assertThat(reason(given().get(collection(SubstrateDatabaseResource.PRIVATE_SCOPE_SLUG,
                selector))))
            .as("a scope of a kind this service does not serve")
            .isEqualTo("SCOPE_KIND_UNSUPPORTED");
        assertThat(rpc("tools/call", Map.of(NAME, "dispatch_query", "arguments", Map.of()))
                .jsonPath().getInt("error.code"))
            .as("a tool the service does not declare is a fault of the protocol")
            .isEqualTo(-32602);
    }

    // -----------------------------------------------------------------------
    // What it offers
    // -----------------------------------------------------------------------

    @Test
    void the_tool_list_carries_exactly_the_one_tool_and_the_declaration_the_one_call() {
        assertThat(rpc("tools/list", Map.of()).jsonPath().getList("result.tools.name"))
            .as("tools/list offers dispatch_read_definition and nothing else")
            .containsExactly(TOOL);
        Response declaration = given().get("/api/declaration");
        assertThat(declaration.jsonPath().getList("calls.identity"))
            .as("the declaration names the one call").containsExactly(TOOL);
        assertThat(declaration.jsonPath().getList("calls[0].arguments.name"))
            .as("with its arguments").containsExactly(SCOPE_ARG, SELECTOR_ARG, VERSION_ARG);
        assertThat(declaration.jsonPath().getString("calls[0].effect_class"))
            .as("as a read of a collection").isEqualTo("COLLECTION_READ");
    }

    // -----------------------------------------------------------------------
    // What it costs, and what it leaves behind
    // -----------------------------------------------------------------------

    @Test
    void the_statements_of_a_read_do_not_grow_with_the_activities_and_enumerations() {
        String small = sizedSelector(1);
        String large = sizedSelector(5);
        rest(small, "").then().statusCode(200);

        int readOfOne = statementsOf(small);
        int readOfFive = statementsOf(large);
        assertThat(readOfOne).as("a read was recorded at all").isPositive();
        assertThat(readOfFive)
            .as("a version with five activities and five enumerations is read in as many "
                + "statements as one with one of each: no statement per activity or per "
                + "enumeration")
            .isEqualTo(readOfOne);
        assertThat(rest(large, "").jsonPath().getList("fields.activities"))
            .as("and the larger version was read whole").hasSize(5);
    }

    @Test
    void a_read_writes_no_act_and_no_row() {
        String selector = plantedSelector();
        Map<String, Long> before = DefinitionFixture.rowCounts();

        rest(selector, "").then().statusCode(200);
        assertThat(mcp(arguments(selector)).jsonPath().getBoolean(IS_ERROR)).isFalse();
        rest(ABSENT + suffix(), "").then().statusCode(404);

        assertThat(before).as("the store holds rows to count").containsKey("act");
        assertThat(DefinitionFixture.rowCounts())
            .as("a read, answered or refused, writes no act and no row of any table")
            .isEqualTo(before);
    }

    // -----------------------------------------------------------------------
    // The enumerations of the system fields, against the store
    // -----------------------------------------------------------------------

    /**
     * The system field each enumeration stands for, by the table and the
     * CHECK that fixes its values in the store line, as V1 to V5 name them.
     */
    private static final Map<String, List<String>> CHECKS = new LinkedHashMap<>();

    static {
        CHECKS.put("task_state", List.of("task", "ck_task_state"));
        CHECKS.put("instance_state", List.of("instance", "ck_instance_state"));
        CHECKS.put("act_kind", List.of("act", "ck_act_kind"));
        CHECKS.put("verdict", List.of("delivery", "ck_delivery_verdict"));
        CHECKS.put("task_resolution", List.of("task", "ck_task_resolution"));
        CHECKS.put("hold_reason", List.of("task", "ck_task_hold_reason"));
        CHECKS.put("criterion_status", List.of("delivery_criterion",
            "ck_delivery_criterion_status"));
        CHECKS.put("path", List.of("act", "ck_act_path"));
    }

    @Test
    void every_system_enumeration_of_the_answer_is_the_values_of_its_check_in_their_order()
            throws SQLException {
        List<Map<String, Object>> answered = rest(plantedSelector(), "").jsonPath()
            .getList("fields.system_enumerations");
        Map<String, List<String>> byName = new LinkedHashMap<>();
        for (Map<String, Object> enumeration : answered) {
            byName.put((String) enumeration.get(NAME), strings(enumeration.get(VALUES)));
        }
        assertThat(byName.keySet())
            .as("the eight system fields the order names, in the form of a customer's "
                + "enumeration, and no other")
            .containsExactlyElementsOf(CHECKS.keySet());

        List<String> offences = new ArrayList<>();
        for (Map.Entry<String, List<String>> check : CHECKS.entrySet()) {
            offences.addAll(differences(check.getKey(), byName.get(check.getKey()),
                checked(check.getValue().get(0), check.getValue().get(1))));
        }
        assertThat(offences)
            .as("each system enumeration answers exactly the values its CHECK admits, in the "
                + "order of the CHECK")
            .isEmpty();
    }

    @Test
    void the_comparison_reports_a_value_too_many_and_a_value_too_few() throws SQLException {
        List<String> checked = checked("task", "ck_task_state");
        List<String> tooMany = new ArrayList<>(checked);
        tooMany.add("parked");
        List<String> tooFew = new ArrayList<>(checked);
        tooFew.remove("recalled");

        assertThat(differences("task_state", tooMany, checked))
            .as("RED STATE, observed: a constant with a value its CHECK does not admit is "
                + "reported")
            .containsExactly("task_state answers 'parked', which its CHECK does not admit");
        assertThat(differences("task_state", tooFew, checked))
            .as("RED STATE, observed: a constant missing a value its CHECK admits is reported")
            .containsExactly("task_state leaves out 'recalled', which its CHECK admits");
    }

    @Test
    void the_comparison_reports_a_value_out_of_the_order_of_its_check() throws SQLException {
        List<String> checked = checked("task", "ck_task_state");
        List<String> swapped = new ArrayList<>(checked);
        Collections.swap(swapped, 0, 1);

        assertThat(differences("task_state", swapped, checked))
            .as("RED STATE, observed: a constant with the values of its CHECK in another "
                + "order is reported, at the first place the two part")
            .containsExactly("task_state answers '" + checked.get(1) + "' at place 1, where "
                + "its CHECK has '" + checked.get(0) + "'");
    }

    /**
     * Where an answered enumeration and the values of its CHECK part: a value
     * the CHECK does not admit, a value it admits that is left out, and, where
     * the two hold the same values, the first place their order parts.
     */
    static List<String> differences(String name, List<String> answered, List<String> checked) {
        List<String> found = new ArrayList<>();
        for (String value : answered) {
            if (!checked.contains(value)) {
                found.add(name + " answers '" + value + "', which its CHECK does not admit");
            }
        }
        for (String value : checked) {
            if (!answered.contains(value)) {
                found.add(name + " leaves out '" + value + "', which its CHECK admits");
            }
        }
        if (found.isEmpty()) {
            for (int place = 0; place < answered.size(); place++) {
                if (!answered.get(place).equals(checked.get(place))) {
                    found.add(name + " answers '" + answered.get(place) + "' at place "
                        + (place + 1) + ", where its CHECK has '" + checked.get(place) + "'");
                    break;
                }
            }
        }
        return found;
    }

    /** The values a CHECK of the migrated store admits, in its order, from {@code pg_constraint}. */
    private static List<String> checked(String table, String constraint) throws SQLException {
        try (Connection c = InstanceFixture.admin();
             PreparedStatement st = c.prepareStatement("""
                 SELECT pg_get_constraintdef(k.oid) FROM pg_constraint k
                 JOIN pg_class t ON t.oid = k.conrelid
                 JOIN pg_namespace n ON n.oid = t.relnamespace
                 WHERE n.nspname = 'dispatch' AND t.relname = ? AND k.conname = ?
                 """)) {
            st.setString(1, table);
            st.setString(2, constraint);
            try (ResultSet rs = st.executeQuery()) {
                assertThat(rs.next()).as("the store holds %s on %s", constraint, table).isTrue();
                Set<String> values = new LinkedHashSet<>();
                Matcher literal = Pattern.compile("'([^']*)'::text").matcher(rs.getString(1));
                while (literal.find()) {
                    values.add(literal.group(1));
                }
                assertThat(values).as("%s names values", constraint).isNotEmpty();
                return List.copyOf(values);
            }
        }
    }

    // -----------------------------------------------------------------------
    // The planting
    // -----------------------------------------------------------------------

    /**
     * A selector with two seeded versions: version 1, retired, declaring
     * nothing; and version 2, active, with two enumerations of which one is
     * shared by two choice fields, a control field of every type, two
     * activities with outcome labels and a result contract each, a start
     * contract, and outcome labels of the version. Labels and values are
     * planted out of their order, so an answer in their order is the
     * ordinal's.
     */
    private String plantedSelector() {
        String name = "def-" + suffix();
        long selector = plant.selector(name);
        plant.version(selector, 1, RETIRED, true, false, true);
        long version = plant.version(selector, 2, ACTIVE, true, true, false);

        long priority = plant.enumeration(version, PRIORITY, List.of("low", "medium", "high"));
        long channel = plant.enumeration(version, CHANNEL, List.of("mail", "phone"));

        long urgent = plant.field(version, URGENT, "boolean", null);
        long amount = plant.field(version, AMOUNT, "number", null);
        plant.field(version, "due", "date", null);
        long summary = plant.field(version, SUMMARY, "text", null);
        long priorityField = plant.field(version, PRIORITY, CHOICE, priority);
        plant.field(version, "fallback_priority", CHOICE, priority);
        long contact = plant.field(version, CONTACT, CHOICE, channel);

        long review = plant.activity(version, "review", true, true, true, "text", true);
        plant.activityLabel(review, "rejected", 2);
        plant.activityLabel(review, "accepted", 1);
        plant.resultField(version, review, amount, true);
        plant.resultField(version, review, priorityField, false);
        long approve = plant.activity(version, "approve", false, false, false, "none", false);
        plant.activityLabel(approve, DONE, 1);
        plant.resultField(version, approve, urgent, true);

        plant.startField(version, summary, true);
        plant.startField(version, contact, false);
        plant.versionLabel(version, "declined", 2);
        plant.versionLabel(version, "approved", 1);
        return name;
    }

    /** The planting of version 2 of {@link #plantedSelector()}, as its read answers it. */
    private static Map<String, Object> expectedActiveVersion(String selector) {
        Map<String, Object> expected = new HashMap<>();
        expected.put(SELECTOR_ARG, selector);
        expected.put(VERSION_ARG, 2);
        expected.put(STATE, ACTIVE);
        expected.put(VERDICT_REQUIRED, true);
        expected.put(OUTCOME_REQUIRED, false);
        expected.put(OUTCOME_LABELS, List.of("approved", "declined"));
        expected.put("schema", List.of(
            Map.of(NAME, URGENT, TYPE, "boolean"),
            Map.of(NAME, AMOUNT, TYPE, "number"),
            Map.of(NAME, "due", TYPE, "date"),
            Map.of(NAME, SUMMARY, TYPE, "text"),
            Map.of(NAME, PRIORITY, TYPE, CHOICE, ENUMERATION, PRIORITY),
            Map.of(NAME, "fallback_priority", TYPE, CHOICE, ENUMERATION, PRIORITY),
            Map.of(NAME, CONTACT, TYPE, CHOICE, ENUMERATION, CHANNEL)));
        expected.put("enumerations", List.of(
            Map.of(NAME, PRIORITY, VALUES, List.of("low", "medium", "high")),
            Map.of(NAME, CHANNEL, VALUES, List.of("mail", "phone"))));
        expected.put("activities", List.of(
            activity("review", true, "text", List.of("accepted", "rejected"), List.of(
                Map.of(FIELD, AMOUNT, REQUIRED, true),
                Map.of(FIELD, PRIORITY, REQUIRED, false))),
            activity("approve", false, "none", List.of(DONE), List.of(
                Map.of(FIELD, URGENT, REQUIRED, true)))));
        expected.put("start_contract", List.of(
            Map.of(FIELD, SUMMARY, REQUIRED, true),
            Map.of(FIELD, CONTACT, REQUIRED, false)));
        return expected;
    }

    /** An activity as planted: every requirement and may_ask set alike. */
    private static Map<String, Object> activity(String name, boolean needs, String inputKind,
                                                List<String> labels,
                                                List<Map<String, Object>> contract) {
        Map<String, Object> activity = new HashMap<>();
        activity.put(NAME, name);
        activity.put(VERDICT_REQUIRED, needs);
        activity.put(OUTCOME_REQUIRED, needs);
        activity.put("criteria_required", needs);
        activity.put(OUTCOME_LABELS, labels);
        activity.put("result_contract", contract);
        activity.put("input_kind", inputKind);
        activity.put("may_ask", needs);
        return activity;
    }

    /**
     * A selector whose active version declares {@code size} activities, each
     * with a label and a result field, and {@code size} enumerations, each
     * with two values and a choice field.
     */
    private String sizedSelector(int size) {
        String name = "sized-" + size + "-" + suffix();
        long version = plant.version(plant.selector(name), 1, ACTIVE, true, false, false);
        for (int i = 1; i <= size; i++) {
            long enumeration = plant.enumeration(version, "list_" + i, List.of("a", "b"));
            long field = plant.field(version, "field_" + i, CHOICE, enumeration);
            long activity = plant.activity(version, "step-" + i, false, false, false, "none",
                false);
            plant.activityLabel(activity, DONE, 1);
            plant.resultField(version, activity, field, true);
        }
        return name;
    }

    // -----------------------------------------------------------------------

    /** The statements one read sends, as the persistence provider prepares them. */
    private int statementsOf(String selector) {
        RecordedStatements.clear();
        rest(selector, "").then().statusCode(200);
        return RecordedStatements.recorded().size();
    }

    private static String collection(String scope, String selector) {
        return "/api/" + scope + "/" + selector;
    }

    private static Response rest(String selector, String query) {
        return given().get(collection(SLUG, selector) + query);
    }

    /** The arguments that name a selector of the probing scope, and no version. */
    private static Map<String, Object> arguments(String selector) {
        return Map.of(SCOPE_ARG, SLUG, SELECTOR_ARG, selector);
    }

    private static Response mcp(Map<String, Object> arguments) {
        return rpc("tools/call", Map.of(NAME, TOOL, "arguments", arguments));
    }

    private static Response rpc(String method, Map<String, Object> params) {
        return given().contentType(ContentType.JSON)
            .body(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params))
            .post("/mcp");
    }

    private static String reason(Response refused) {
        return refused.jsonPath().getString(REASON);
    }

    private static String mcpReason(Map<String, Object> arguments) {
        return mcp(arguments).jsonPath().getString(MCP_REASON);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object values) {
        return (List<String>) values;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Calls as the probing member under the realm roles named, through the interim source. */
    private void as(String... roles) {
        var builder = QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal(SUBJECT));
        for (String role : roles) {
            builder.addRole(role);
        }
        identity.setTestIdentity(InterimRolePermissions.withPermissions(builder.build()));
    }

    /** Calls as the probing member holding one permission of this service and no other. */
    private void asHolderOnlyOf(String permission) {
        StringPermission held = new StringPermission(permission);
        identity.setTestIdentity(QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal(SUBJECT))
            .addPermissionChecker(required -> Uni.createFrom().item(held.implies(required)))
            .build());
    }
}
