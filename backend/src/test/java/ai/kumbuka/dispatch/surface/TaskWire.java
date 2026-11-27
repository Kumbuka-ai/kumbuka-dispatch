/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.DefinitionFixture;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * The calls on a task, made on the wire as a caller makes them: what the
 * integration tests of the draft and the commission share, beside {@link
 * InstanceWire}, which reads the store back by SQL.
 *
 * <p>Every expectation stays with the test that uses this: the version is
 * planted here row by row, with the activities a test names and a start
 * contract that requires nothing, so that the short form of {@code create}
 * starts an instance; and the calls are made by their names on the wire.
 */
public final class TaskWire {

    public static final String CREATE = "dispatch_create";
    public static final String UPDATE = "dispatch_update";
    public static final String DELETE = "dispatch_delete";
    public static final String SEND = "dispatch_send";

    private TaskWire() {
    }

    /** The planted version: its selector, and its activities by name in the order given. */
    public record Planted(String selector, long version, Map<String, Long> activities) {
    }

    /**
     * A selector with an active seeded version that declares the activities
     * named, and a start contract of one optional text field {@code summary}.
     */
    public static Planted plant(String prefix, String... activities) {
        DefinitionFixture plant = new DefinitionFixture(InstanceWire.TENANT, InstanceWire.SCOPE);
        String name = prefix + "-" + InstanceWire.suffix();
        long version = plant.version(plant.selector(name), 1, "active", true, false, false);
        long summary = plant.field(version, "summary", "text", null);
        plant.startField(version, summary, false);
        Map<String, Long> declared = new LinkedHashMap<>();
        for (String activity : activities) {
            declared.put(activity, plant.activity(version, activity, false, false, false,
                "text", false));
        }
        return new Planted(name, version, declared);
    }

    /** Starts an instance of the planted selector on the assistant surface; answers its address. */
    public static String started(String selector) {
        return String.valueOf(InstanceWire.answer(InstanceWire.mcp("dispatch_start",
            InstanceWire.startArguments(selector, Map.of("title", "an instance"))))
            .get(InstanceWire.ADDRESS));
    }

    /** The arguments of a create on the assistant surface. */
    public static Map<String, Object> create(String selector, @Nullable String parent,
                                             Map<String, Object> fields) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("scope", InstanceWire.SLUG);
        arguments.put("selector", selector);
        if (parent != null) {
            arguments.put("parent", parent);
        }
        arguments.put(InstanceWire.FIELDS, fields);
        return arguments;
    }

    /** Creates a draft with its title alone in the instance given; answers it. */
    public static Map<String, Object> drafted(String selector, String parent, String title) {
        return InstanceWire.answer(InstanceWire.mcp(CREATE,
            create(selector, parent, Map.of("title", title))));
    }

    /** The arguments of a call on a task: its address and its token, and the rest given. */
    public static Map<String, Object> on(Map<String, Object> answer, Map<String, Object> rest) {
        Map<String, Object> arguments = new LinkedHashMap<>(rest);
        arguments.put(InstanceWire.ADDRESS, answer.get(InstanceWire.ADDRESS));
        arguments.put(InstanceWire.TOKEN, answer.get(InstanceWire.TOKEN));
        return arguments;
    }

    /** A call on a task over REST: its method, a colon verb or none, the body and If-Match. */
    public static Response rest(String method, String address, @Nullable String verb,
                                Map<String, Object> body, @Nullable String ifMatch) {
        RequestSpecification request = given().contentType(ContentType.JSON);
        if (!body.isEmpty()) {
            request = request.body(body);
        }
        if (ifMatch != null) {
            request = request.header("If-Match", "\"" + ifMatch + "\"");
        }
        String path = InstanceWire.path(address) + (verb == null ? "" : ":" + verb);
        return request.request(method, path);
    }

    /** The surrogate of the task an address names, read by SQL. */
    public static long taskId(String address) {
        int dot = address.lastIndexOf('.');
        return InstanceWire.single("SELECT id FROM dispatch.task WHERE instance_id = ? AND sub = ?",
            InstanceWire.instanceId(address.substring(0, dot)),
            Integer.parseInt(address.substring(dot + 1)));
    }

    /** How many rows of a table hang on a task, through its draft where the table is one of it. */
    public static long rowsOf(String table, long task) {
        return switch (table) {
            case "task" -> InstanceWire.single(
                "SELECT count(*) FROM dispatch.task WHERE id = ?", task);
            case "act" -> InstanceWire.single(
                "SELECT count(*) FROM dispatch.act WHERE task_id = ?", task);
            case "idempotency_key" -> InstanceWire.single(
                "SELECT count(*) FROM dispatch.idempotency_key WHERE task_id = ?", task);
            case "draft" -> InstanceWire.single(
                "SELECT count(*) FROM dispatch.draft WHERE task_id = ?", task);
            case "commission" -> InstanceWire.single("SELECT count(*) FROM dispatch.commission c "
                + "JOIN dispatch.act a ON a.id = c.act_id WHERE a.task_id = ?", task);
            case "commission_criterion" -> InstanceWire.single("SELECT count(*) FROM "
                + "dispatch.commission_criterion cc JOIN dispatch.commission c "
                + "ON c.id = cc.commission_id JOIN dispatch.act a ON a.id = c.act_id "
                + "WHERE a.task_id = ?", task);
            case "content", "free_fields" -> InstanceWire.single("SELECT count(*) FROM dispatch."
                + table + " x JOIN dispatch.act a ON a.id = x.act_id WHERE a.task_id = ?", task);
            default -> InstanceWire.single("SELECT count(*) FROM dispatch." + table + " x "
                + "JOIN dispatch.draft d ON d.id = x.draft_id WHERE d.task_id = ?", task);
        };
    }

    /** The draft workspace a task's draft holds, table by table: what send and delete empty. */
    public static final List<String> WORKSPACE = List.of("draft", "draft_criterion",
        "draft_free_fields", "draft_text");
}
