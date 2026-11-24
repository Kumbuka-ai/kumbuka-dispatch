/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.DefinitionFixture;
import ai.kumbuka.dispatch.domain.InstanceFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.jspecify.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/**
 * The acts on an instance, made on the wire as a caller makes them, and the
 * store read back by SQL as it holds them: what the integration tests of the
 * acts on an instance share.
 *
 * <p>Every expectation stays with the test that uses this: the version is
 * planted here row by row, the calls are made by their names on the wire, and
 * the rows are read as the store holds them, never through the mapping under
 * test. The caller is the probing member of the substrate, under the realm
 * roles the interim source of permissions reads.
 */
public final class InstanceWire {

    public static final UUID TENANT = UUID.fromString(SubstrateDatabaseResource.TENANT_ID);
    public static final UUID SCOPE = UUID.fromString(SubstrateDatabaseResource.SCOPE_ID);
    public static final String SLUG = SubstrateDatabaseResource.PROBE_SCOPE_SLUG;
    public static final String SUBJECT = SubstrateDatabaseResource.PROBE_SUBJECT;
    public static final String CONSOLE = "dispatch-console";
    public static final String EXECUTOR = "dispatch-executor";

    /** The names of the wire the tests of the acts on an instance share. */
    public static final String ADDRESS = "address";
    public static final String FIELDS = "fields";
    public static final String TOKEN = "conflict_token";
    public static final String REASON = "reason";
    public static final String ATTEMPTED = "attempted";
    public static final String STATE = "state";
    public static final String NEXT = "next";
    public static final String TRANSITION_REFUSED = "TRANSITION_REFUSED";

    private static final String SCHEME = "dispatch://";
    private static final String IS_ERROR = "result.isError";
    private static final String STRUCTURED = "result.structuredContent";

    private InstanceWire() {
    }

    /** Calls as the probing member under the realm roles named. */
    public static void as(TestIdentityAssociation identity, String... roles) {
        var builder = QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal(SUBJECT));
        for (String role : roles) {
            builder.addRole(role);
        }
        identity.setTestIdentity(InterimRolePermissions.withPermissions(builder.build()));
    }

    // -----------------------------------------------------------------------
    // The planting
    // -----------------------------------------------------------------------

    /** The planted version of {@link #plant}: its selector, its rows, and its enumeration. */
    public record Planted(String selector, long version, long high, long approved) {
    }

    /**
     * A selector with an active seeded version: a start contract of a
     * required number {@code amount} and an optional field of every other
     * type -- {@code urgent} boolean, {@code due} date, {@code summary} text
     * and {@code priority} choice of the enumeration low, high -- and the
     * outcome label {@code approved} of the version.
     */
    public static Planted plant(String prefix, boolean verdictRequired, boolean outcomeRequired) {
        DefinitionFixture plant = new DefinitionFixture(TENANT, SCOPE);
        String name = prefix + "-" + suffix();
        long version = plant.version(plant.selector(name), 1, "active", true, verdictRequired,
            outcomeRequired);
        long levels = plant.enumeration(version, "level", List.of("low", "high"));
        long amount = plant.field(version, "amount", "number", null);
        long urgent = plant.field(version, "urgent", "boolean", null);
        long due = plant.field(version, "due", "date", null);
        long summary = plant.field(version, "summary", "text", null);
        long priority = plant.field(version, "priority", "choice", levels);
        plant.field(version, "internal", "text", null);
        plant.startField(version, amount, true);
        plant.startField(version, urgent, false);
        plant.startField(version, due, false);
        plant.startField(version, summary, false);
        plant.startField(version, priority, false);
        plant.versionLabel(version, "approved", 1);
        long high = single("SELECT id FROM dispatch.enumeration_value WHERE enumeration_id = ? "
            + "AND value = 'high'", levels);
        long approved = single("SELECT id FROM dispatch.outcome_label "
            + "WHERE definition_version_id = ? AND label = 'approved'", version);
        return new Planted(name, version, high, approved);
    }

    /** A plain version that requires nothing of a return. */
    public static Planted plant(String prefix) {
        return plant(prefix, false, false);
    }

    /** A task of an instance in the state given, planted by SQL; answers its surrogate. */
    public static long plantTask(long instance, int sub, String state) {
        return single("INSERT INTO dispatch.task (tenant_id, scope_id, instance_id, sub, state, "
            + "hold_reason, resolution) SELECT tenant_id, scope_id, id, ?, ?, "
            + "CASE WHEN CAST(? AS text) = 'on_hold' THEN 'external' END, "
            + "CASE WHEN CAST(? AS text) = 'closed' THEN 'accepted' END "
            + "FROM dispatch.instance WHERE id = ? RETURNING id", sub, state, state, state,
            instance);
    }

    // -----------------------------------------------------------------------
    // The calls
    // -----------------------------------------------------------------------

    /** A start's arguments on the assistant surface, with the one value the contract requires. */
    public static Map<String, Object> startArguments(String selector, Map<String, Object> fields) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("scope", SLUG);
        arguments.put("selector", selector);
        arguments.put(FIELDS, fields);
        return arguments;
    }

    /** The fields of the smallest start the planted contract takes. */
    public static Map<String, Object> minimalFields(String title) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("values", Map.of("amount", 1));
        return fields;
    }

    /** Starts an instance of the planted selector on the assistant surface; answers its address. */
    public static String started(String selector) {
        return String.valueOf(answer(mcp("dispatch_start",
            startArguments(selector, minimalFields("a title")))).get(ADDRESS));
    }

    /** One tool call on the assistant surface. */
    public static Response mcp(String tool, Map<String, Object> arguments) {
        return rpc("tools/call", Map.of("name", tool, "arguments", arguments));
    }

    /** One JSON-RPC request on the assistant surface. */
    public static Response rpc(String method, Map<String, Object> params) {
        return given().contentType(ContentType.JSON)
            .body(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params))
            .post("/mcp");
    }

    /** The structured answer of a tool call that was not refused. */
    public static Map<String, Object> answer(Response mcp) {
        if (Boolean.TRUE.equals(mcp.jsonPath().get(IS_ERROR))) {
            throw new AssertionError("the call was refused: " + mcp.asString());
        }
        return mcp.jsonPath().getMap(STRUCTURED, String.class, Object.class);
    }

    /** The envelope of a tool call that was refused. */
    public static Map<String, Object> refusal(Response mcp) {
        if (!Boolean.TRUE.equals(mcp.jsonPath().get(IS_ERROR))) {
            throw new AssertionError("the call was answered: " + mcp.asString());
        }
        return mcp.jsonPath().getMap(STRUCTURED, String.class, Object.class);
    }

    /** The {@code data} of a refusal's envelope. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> data(Map<String, Object> refusal) {
        return (Map<String, Object>) refusal.get("data");
    }

    /** The current conflict token of an instance, as its last answer handed it out. */
    public static String token(Map<String, Object> answer) {
        return String.valueOf(answer.get("conflict_token"));
    }

    /** A POST on the REST path of an instance with a colon verb. */
    public static Response rest(String address, String verb, Map<String, Object> body,
                                @Nullable String ifMatch) {
        RequestSpecification request = given().contentType(ContentType.JSON).body(body);
        if (ifMatch != null) {
            request = request.header("If-Match", "\"" + ifMatch + "\"");
        }
        return request.post(path(address) + ":" + verb);
    }

    /** The REST path of an instance's complete address. */
    public static String path(String address) {
        return "/api/" + address.substring(SCHEME.length());
    }

    /** The number of an instance, read off its complete address. */
    public static long number(String address) {
        return Long.parseLong(address.substring(address.lastIndexOf('/') + 1));
    }

    // -----------------------------------------------------------------------
    // The store, by SQL
    // -----------------------------------------------------------------------

    /** The surrogate of the instance of a selector by its number. */
    public static long instanceId(String selector, long number) {
        return single("SELECT i.id FROM dispatch.instance i JOIN dispatch.selector s "
            + "ON s.id = i.selector_id WHERE s.name = ? AND i.number = ?", selector, number);
    }

    /** The surrogate of the instance an address names. */
    public static long instanceId(String address) {
        String[] parts = address.substring(SCHEME.length()).split("/");
        return instanceId(parts[1], Long.parseLong(parts[2]));
    }

    /** Every row a statement answers, each by its column names, as the container's superuser. */
    public static List<Map<String, Object>> rows(String sql, Object... values) {
        try (Connection c = InstanceFixture.admin();
             PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                st.setObject(i + 1, values[i]);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            try (ResultSet rs = st.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnName(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("the rows could not be read", e);
        }
    }

    /** The one value the one row of a statement answers, as a number. */
    public static long single(String sql, Object... values) {
        List<Map<String, Object>> rows = rows(sql, values);
        if (rows.size() != 1) {
            throw new AssertionError("one row expected, " + rows.size() + " found: " + sql);
        }
        return ((Number) rows.get(0).values().iterator().next()).longValue();
    }

    /** A suffix that keeps the names of one test apart from another's. */
    public static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
