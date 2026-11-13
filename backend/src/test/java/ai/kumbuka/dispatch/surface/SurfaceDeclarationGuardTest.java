/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The start-up guard of the declaration, observed refusing.
 *
 * <p>A start-up check is a statement about a mechanism, and a mechanism nobody
 * has watched fail is a description of one. So each check {@code
 * requireServable} makes is run against a declaration that breaks it, and the
 * refusal is read. The checks take names and texts handed in, so this runs
 * without the service; that the real declaration passes them is observed in
 * {@code DeclarationConformanceIT}, and that every refusal code can be worded
 * in {@code RefusalCatalogueGuardTest}.
 *
 * <p>Of the five checks, the one on a call with no arguments is here because
 * it was NOT here. The javadoc of {@code
 * requireServable} said three checks ran and two did: "a call with no
 * arguments cannot be schema'd" was named and never implemented, which is a
 * comment asserting a guarantee no mechanism enforced. It is implemented now,
 * and this file is what makes that sentence checkable rather than hopeful.
 */
class SurfaceDeclarationGuardTest {

    /** Two calls under one name are refused: a flat tool list reaches one of them only. */
    @Test
    void two_calls_under_one_name_refuse_the_start() {
        assertThatThrownBy(() -> SurfaceDeclaration.requireDistinctNames(
            List.of("dispatch_read", "dispatch_claim", "dispatch_read")))
            .as("RED STATE, observed: a duplicate call name refuses the start, and names it")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("dispatch_read");
    }

    /** A description over the budget is refused: a caller cuts what it does not read whole. */
    @Test
    void a_description_over_the_budget_refuses_the_start() {
        assertThatThrownBy(() -> SurfaceDeclaration.requireWithinBudget("dispatch_something",
            "x".repeat(SurfaceDeclaration.DESCRIPTION_BUDGET + 1)))
            .as("RED STATE, observed: a description one character over the budget refuses "
                + "the start, and names the call")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("dispatch_something");
    }

    /** A call with no description is refused: it is all a caller with no skill has. */
    @Test
    void a_call_with_no_description_refuses_the_start() {
        assertThatThrownBy(() -> SurfaceDeclaration.requireDescribed("dispatch_something", " "))
            .as("RED STATE, observed: a call declared with a blank description refuses the "
                + "start, and names the call")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("dispatch_something");
        assertThatThrownBy(() -> SurfaceDeclaration.requireDescribed("dispatch_something", null))
            .isInstanceOf(IllegalStateException.class);
    }

    /**
     * A call with no arguments is refused — the check the javadoc named and
     * the code did not make.
     *
     * <p>Its input schema would be an empty closed object, and the check that
     * names an undeclared argument would have no declaration to name instead.
     */
    @Test
    void a_call_with_no_arguments_refuses_the_start() {
        assertThatThrownBy(() ->
            SurfaceDeclaration.requireDescribedArguments("dispatch_nothing", List.of()))
            .as("RED STATE, observed: the check the javadoc named and the code did not "
                + "make now refuses a call with no arguments")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no arguments");
    }

    /**
     * An argument with no description is refused, because the description is
     * what a refusal says the value IS.
     *
     * <p>The declaration is what a caller reads to learn what an argument is;
     * an {@code ARGUMENT_MISSING} names the argument and leaves the rest to
     * it. Without a description the caller has nothing to fill it with.
     */
    @Test
    void an_argument_with_no_description_refuses_the_start() {
        assertThatThrownBy(() -> SurfaceDeclaration.requireDescribedArguments(
            "dispatch_something", List.of(Argument.top("address", "string", () -> "  "))))
            .as("RED STATE, observed: an argument with no description refuses the start, "
                + "and names the argument")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("address");
    }

    // ======================================================================
    // The document as served (C5.6): the guard checks what the declaration
    // route serves, not only the verb records it is built from
    // ======================================================================

    /** A served document the guard passes: the floor under the red states below. */
    @Test
    void a_complete_document_starts() {
        DeclarationGuard.requireServable(document(call("dispatch_read", "Reads a task.",
            List.of(argument("address", "The task's address.")), List.of("NOT_FOUND"))));
    }

    @Test
    void a_document_naming_an_undeclared_refusal_refuses_the_start() {
        assertThatThrownBy(() -> DeclarationGuard.requireServable(document(call("dispatch_read",
            "Reads a task.", List.of(argument("address", "The task's address.")),
            List.of("NOT_FOUND", "ZZ_UNDECLARED")))))
            .as("RED STATE, observed: the guard checked the verb records only, and a served "
                + "document naming a refusal its reasons do not declare started")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("dispatch_read")
            .hasMessageContaining("ZZ_UNDECLARED");
    }

    @Test
    void a_document_with_two_calls_under_one_name_refuses_the_start() {
        Map<String, Object> read = call("dispatch_read", "Reads a task.",
            List.of(argument("address", "The task's address.")), List.of("NOT_FOUND"));
        assertThatThrownBy(() -> DeclarationGuard.requireServable(document(read, read)))
            .as("RED STATE, observed: a served document with a duplicate call started")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("dispatch_read");
    }

    @Test
    void a_document_with_an_undescribed_call_or_argument_refuses_the_start() {
        assertThatThrownBy(() -> DeclarationGuard.requireServable(document(call("dispatch_read",
            " ", List.of(argument("address", "The task's address.")), List.of()))))
            .as("RED STATE, observed: a served call without a description started")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("dispatch_read");
        assertThatThrownBy(() -> DeclarationGuard.requireServable(document(call("dispatch_read",
            "Reads a task.", List.of(argument("address", "")), List.of()))))
            .as("RED STATE, observed: a served argument without a description started")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("address");
    }

    @Test
    void a_document_with_a_description_over_the_budget_refuses_the_start() {
        assertThatThrownBy(() -> DeclarationGuard.requireServable(document(call("dispatch_read",
            "x".repeat(SurfaceDeclaration.DESCRIPTION_BUDGET + 1),
            List.of(argument("address", "The task's address.")), List.of()))))
            .as("RED STATE, observed: a served description over the budget started")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("dispatch_read");
    }

    @SafeVarargs
    private static Map<String, Object> document(Map<String, Object>... calls) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("calls", List.of(calls));
        document.put("reasons", List.of(Map.of("reason", "NOT_FOUND")));
        return document;
    }

    private static Map<String, Object> call(String name, String description,
                                            List<Map<String, Object>> arguments,
                                            List<String> refusals) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("call", name);
        call.put("description", description);
        call.put("arguments", arguments);
        call.put("refusals", refusals);
        return call;
    }

    private static Map<String, Object> argument(String name, String description) {
        Map<String, Object> argument = new LinkedHashMap<>();
        argument.put("name", name);
        argument.put("description", description);
        return argument;
    }
}
