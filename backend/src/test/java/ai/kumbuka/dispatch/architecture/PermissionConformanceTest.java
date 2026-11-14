/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.architecture;

import ai.kumbuka.dispatch.adapter.mcp.ToolCalls;
import ai.kumbuka.dispatch.adapter.rest.TaskCalls;
import ai.kumbuka.dispatch.domain.DispatchPermission;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import io.quarkus.security.PermissionsAllowed;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every declared call names the permission it checks, and every method of an
 * adapter that makes a declared call checks it with {@code
 * @PermissionsAllowed} (concept roles and permissions, R20; DEC-0052).
 *
 * <p>The expectation is {@link #CATALOGUE}: the permission of each call as
 * the order of this step assigns it, written here call by call and never read
 * from the declaration or from the adapters. Three statements are held
 * against it:
 *
 * <ol>
 *   <li>every call the declaration carries names exactly the permissions of
 *       its row, and no call names none;</li>
 *   <li>each adapter has one method per call, it carries {@code
 *       @PermissionsAllowed} with exactly the permissions of the call's row,
 *       and no public method of it makes a call without the annotation;</li>
 *   <li>no other class of an adapter makes a prepared call, so no call reaches
 *       the verb surface past the methods that check it.</li>
 * </ol>
 *
 * <p>A call names one permission. Two calls name two, of which the caller
 * holds either, and they are the ones {@link #EITHER} lists with the reason
 * the catalogue gives; any other call naming more than one is reported.
 */
class PermissionConformanceTest {

    /** The permission of each call, as the order of REA-0010 step 2 assigns it. */
    static final Map<String, List<String>> CATALOGUE = catalogue();

    /** The calls that name two permissions, the caller holding either, and why. */
    static final Map<String, String> EITHER = Map.of(
        "read_text", "the catalogue names read-text, and read-text-as-holder for the holder",
        "annotate", "the catalogue names the permission the supplemented text was written "
            + "under: the commissioner's or the executor's");

    /** The classes of the two adapters whose methods make the declared calls. */
    static final List<Class<?>> GATES = List.of(TaskCalls.class, ToolCalls.class);

    /** A prepared call being made: the one way a call reaches the verb surface. */
    private static final Pattern MAKES_A_CALL = Pattern.compile("\\.make\\s*\\(");

    private static Map<String, List<String>> catalogue() {
        String commission = "dispatch.task.commission";
        String execute = "dispatch.task.execute";
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (String call : List.of("send", "recall", "create", "update", "delete", "relate",
                "unrelate")) {
            rows.put(call, List.of(commission));
        }
        for (String call : List.of("claim", "release", "defer", "renew", "ask", "hold",
                "resume", "deliver", "fail", "reject")) {
            rows.put(call, List.of(execute));
        }
        rows.put("answer", List.of("dispatch.task.decide"));
        rows.put("rework", List.of("dispatch.task.confirm"));
        rows.put("accept", List.of("dispatch.task.confirm"));
        rows.put("withdraw", List.of("dispatch.task.withdraw"));
        rows.put("tag_attach", List.of("dispatch.task.tag"));
        rows.put("tag_detach", List.of("dispatch.task.tag"));
        rows.put("read", List.of("dispatch.instance.read"));
        rows.put("query", List.of("dispatch.instance.read"));
        rows.put("read_text", List.of("dispatch.instance.read-text",
            "dispatch.instance.read-text-as-holder"));
        rows.put("annotate", List.of(commission, execute));
        return Map.copyOf(rows);
    }

    @Test
    void every_declared_call_names_the_permission_of_its_row() {
        assertThat(declarationOffences(declared(), CATALOGUE))
            .as("a call checks the one permission the catalogue assigns it, and nothing else "
                + "about its caller (DEC-0052). Name it in ProcessVerb, or change the "
                + "catalogue through the order that changes it")
            .isEmpty();
    }

    @Test
    void every_method_of_an_adapter_that_makes_a_call_checks_its_permission() {
        List<String> offences = new ArrayList<>();
        for (Class<?> gate : GATES) {
            offences.addAll(gateOffences(gate, CATALOGUE));
        }
        assertThat(offences)
            .as("each adapter makes a declared call in one method of its own, and the method "
                + "carries @PermissionsAllowed with exactly the permission of the call")
            .isEmpty();
    }

    @Test
    void no_class_of_an_adapter_but_the_gates_makes_a_call() {
        assertThat(callsPastTheGates(adapterSources()))
            .as("a prepared call is made in the methods that check its permission and "
                + "nowhere else: a second place that makes one is a way past the check")
            .isEmpty();
    }

    /**
     * The red state, observed on every build: a planted declaration, a planted
     * adapter and a planted source, each wrong in every way the three
     * statements exist for.
     */
    @Test
    void the_checks_report_each_offence_they_exist_for() {
        Map<String, List<String>> planted = new LinkedHashMap<>();
        planted.put("send", List.of("dispatch.task.commission"));
        planted.put("claim", List.of());
        planted.put("read", List.of("dispatch.instance.read", "dispatch.instance.read-metadata"));
        planted.put("stray", List.of("dispatch.task.tag"));
        Map<String, List<String>> rows = new LinkedHashMap<>();
        rows.put("send", List.of("dispatch.task.commission"));
        rows.put("claim", List.of("dispatch.task.execute"));
        rows.put("read", List.of("dispatch.instance.read"));
        rows.put("query", List.of("dispatch.instance.read"));

        assertThat(declarationOffences(planted, rows))
            .as("RED STATE, observed: a call that names no permission, one that names "
                + "another, one the catalogue has no row for, one the declaration lacks, and "
                + "one naming two that is no call of either kind, are each reported")
            .containsExactlyInAnyOrder(
                "claim names no permission",
                "read names [dispatch.instance.read, dispatch.instance.read-metadata]; its row "
                    + "names [dispatch.instance.read]",
                "read names more than one permission and is no call of either kind",
                "stray is declared and has no row in the catalogue",
                "query has a row in the catalogue and is not declared");

        assertThat(gateOffences(PlantedCalls.class, rows))
            .as("RED STATE, observed: a method making a call without @PermissionsAllowed, one "
                + "checking another permission than its call's, one checking two for a call "
                + "of one, a public method that is no call, and a call without a method, are "
                + "each reported")
            .containsExactlyInAnyOrder(
                "PlantedCalls.send makes a call without @PermissionsAllowed",
                "PlantedCalls.claim checks [dispatch.task.commission]; its call names "
                    + "[dispatch.task.execute]",
                "PlantedCalls.read checks [dispatch.instance.read, "
                    + "dispatch.instance.read-metadata]; its call names [dispatch.instance.read]",
                "PlantedCalls.read checks more than one permission and is no call of either kind",
                "PlantedCalls.sneak makes a call without @PermissionsAllowed",
                "PlantedCalls has no method for query");

        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("adapter.rest.TaskCalls", "Outcome send(Prepared call) { "
            + "return call.make(verbs); }");
        sources.put("adapter.rest.TaskResource", "Response route() { "
            + "return respond(prepared.make(verbs)); }");
        sources.put("surface.VerbSurface", "void act() { tasks.make(it); }");
        assertThat(callsPastTheGates(sources))
            .as("RED STATE, observed: a resource that makes a prepared call itself is reported, "
                + "the gate beside it and a class outside the adapters are not")
            .containsExactly("adapter.rest.TaskResource makes a call past the gates");
    }

    // -----------------------------------------------------------------------

    /** The call names and their permissions, as the declaration carries them. */
    private static Map<String, List<String>> declared() {
        Map<String, List<String>> calls = new LinkedHashMap<>();
        for (ProcessVerb call : ProcessVerb.values()) {
            calls.put(call.verb(), call.permissions().stream()
                .map(DispatchPermission::wireName).toList());
        }
        return calls;
    }

    static List<String> declarationOffences(Map<String, List<String>> declared,
                                            Map<String, List<String>> rows) {
        List<String> offences = new ArrayList<>();
        declared.forEach((call, permissions) -> {
            List<String> row = rows.get(call);
            if (row == null) {
                offences.add(call + " is declared and has no row in the catalogue");
                return;
            }
            if (permissions.isEmpty()) {
                offences.add(call + " names no permission");
                return;
            }
            if (!Set.copyOf(permissions).equals(Set.copyOf(row))) {
                offences.add(call + " names " + sorted(permissions) + "; its row names "
                    + sorted(row));
            }
            if (permissions.size() > 1 && !EITHER.containsKey(call)) {
                offences.add(call + " names more than one permission and is no call of "
                    + "either kind");
            }
        });
        rows.keySet().stream().filter(call -> !declared.containsKey(call))
            .forEach(call -> offences.add(call + " has a row in the catalogue and is not "
                + "declared"));
        return offences;
    }

    static List<String> gateOffences(Class<?> gate, Map<String, List<String>> rows) {
        List<String> offences = new ArrayList<>();
        Map<String, Method> methods = new LinkedHashMap<>();
        for (Method method : gate.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()
                    && !method.isBridge()) {
                methods.put(method.getName(), method);
            }
        }
        methods.values().stream()
            .filter(method -> method.getAnnotation(PermissionsAllowed.class) == null)
            .map(method -> gate.getSimpleName() + "." + method.getName()
                + " makes a call without @PermissionsAllowed")
            .forEach(offences::add);
        rows.forEach((call, row) -> {
            Method method = methods.get(methodNameOf(call));
            if (method == null) {
                offences.add(gate.getSimpleName() + " has no method for " + call);
                return;
            }
            PermissionsAllowed allowed = method.getAnnotation(PermissionsAllowed.class);
            if (allowed == null) {
                return;
            }
            List<String> checked = Arrays.asList(allowed.value());
            if (!Set.copyOf(checked).equals(Set.copyOf(row))) {
                offences.add(gate.getSimpleName() + "." + method.getName() + " checks "
                    + sorted(checked) + "; its call names " + sorted(row));
            }
            if (checked.size() > 1 && !EITHER.containsKey(call)) {
                offences.add(gate.getSimpleName() + "." + method.getName() + " checks more "
                    + "than one permission and is no call of either kind");
            }
        });
        return offences;
    }

    static List<String> callsPastTheGates(Map<String, String> sources) {
        Set<String> gates = new TreeSet<>();
        GATES.forEach(gate -> gates.add(gate.getName().substring(SourceTree.BASE.length() + 1)));
        List<String> offences = new ArrayList<>();
        sources.forEach((name, code) -> {
            if (name.startsWith("adapter.") && !gates.contains(name)
                    && MAKES_A_CALL.matcher(code).find()) {
                offences.add(name + " makes a call past the gates");
            }
        });
        return offences;
    }

    /** Every main source of the service, by its name below the service root, as code. */
    private static Map<String, String> adapterSources() {
        Path root = SourceTree.root("main");
        Map<String, String> sources = new LinkedHashMap<>();
        for (Path file : SourceTree.files(root)) {
            sources.put(SourceTree.fqcn(root, file).substring(SourceTree.BASE.length() + 1),
                SourceTree.code(file));
        }
        assertThat(sources).as("the gates must be found, or the check walks the wrong tree")
            .containsKeys("adapter.rest.TaskCalls", "adapter.mcp.ToolCalls");
        return sources;
    }

    /** The method a call is made in: its name in lower camel case. */
    private static String methodNameOf(String call) {
        StringBuilder name = new StringBuilder();
        boolean upper = false;
        for (char c : call.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                name.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return name.toString();
    }

    private static List<String> sorted(List<String> names) {
        return List.copyOf(new TreeSet<>(names));
    }

    /** An adapter wrong in every way the second statement exists for. */
    static final class PlantedCalls {

        public Object send(Object call) {
            return call;
        }

        @PermissionsAllowed("dispatch.task.commission")
        public Object claim(Object call) {
            return call;
        }

        @PermissionsAllowed({"dispatch.instance.read", "dispatch.instance.read-metadata"})
        public Object read(Object call) {
            return call;
        }

        public Object sneak(Object call) {
            return call;
        }
    }
}
