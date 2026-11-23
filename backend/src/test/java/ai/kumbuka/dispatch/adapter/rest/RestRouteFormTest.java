/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.rest;

import ai.kumbuka.dispatch.surface.AddressParser;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every REST route of this service has the HTTP form its declared effect
 * class names, so the router, which turns an effect class into an HTTP form
 * (ADR-0039), reaches the route the service serves.
 *
 * <p>The form of each class is written here from the router's definitions,
 * not read from either side: GET on an item, GET on a collection, POST on a
 * collection, PATCH on an item, DELETE on an item, a custom method in colon
 * notation on an item by POST or by GET, and on a collection by POST.
 *
 * <p>RED STATE, observed 2026-10-15: {@code dispatch_relate} declared as
 * {@code GRAPH_ADD_OR_REMOVE} for the run turns this red on that call alone.
 *
 * <p>Two clauses of the service contract stand beside it. C3.2: the REST
 * surface has two literal paths, {@code /api/declaration} and {@code
 * /api/whoami}, and every other path is a template under {@code
 * /api/{scope}}; the names of the two literal paths are reserved, so a scope
 * named {@code declaration} or {@code whoami} is out of the address grammar and
 * refused before the route is looked up. C3.3: a verb the service does not carry at an address
 * answers {@code VERB_NOT_CARRIED} 405, and {@code data.accepts} lists the
 * verbs that address carries.
 */
class RestRouteFormTest {

    private static final String REBUILT =
        "rebuilt in T2: every route of a call has the form of its effect class and accepts its verbs";

    /**
     * Stands for the checks of the routes of the calls until their order rebuilds them:
     * <ul>
     *   <li>{@code every_route_has_the_form_of_its_effect_class}</li>
     *   <li>{@code a_verb_not_carried_at_an_address_answers_405_listing_what_the_address_carries}</li>
     *   <li>{@code the_check_reports_accepts_that_are_not_what_the_address_carries}</li>
     * </ul>
     */
    @Test
    @Disabled(REBUILT)
    void the_routes_of_the_calls_are_rebuilt_in_their_order() {
        fail(REBUILT);
    }

    /** The resources of the REST surface, by the class a test can load. */
    private static final List<Class<?>> RESOURCES = List.of(DeclarationResource.class,
        WhoamiResource.class);

    /** C3.2: the two literal paths, as the contract names them. */
    private static final Set<String> LITERAL = Set.of("/api/declaration", "/api/whoami");

    @Test
    void the_rest_surface_has_two_literal_paths_and_templates_under_a_scope() {
        assertThat(pathOffences(pathsOf(RESOURCES)))
            .as("C3.2: /api/declaration and /api/whoami are the only literal paths; every "
                + "other path addresses a scope")
            .isEmpty();
    }

    @Test
    void the_check_reports_a_third_literal_path_and_a_path_outside_api() {
        assertThat(pathOffences(List.of("/api/declaration", "/api/status",
                "/admin/{scope}", "/api/{scope}/{selector}")))
            .as("RED STATE, observed: a third literal path and a path outside /api must "
                + "each be reported")
            .containsExactly("/api/status", "/admin/{scope}");
    }

    @Test
    void the_names_of_the_literal_paths_are_no_scope() {
        assertThat(reservedOffences(LITERAL))
            .as("C3.2: a scope named like a literal path would answer under a path the "
                + "surface keeps for itself; the grammar refuses the name")
            .isEmpty();
        assertThat(AddressParser.RESERVED_SCOPES).as("and reserves no other name")
            .containsExactlyInAnyOrderElementsOf(LITERAL.stream()
                .map(path -> path.substring("/api/".length())).toList());
        assertThat(AddressParser.scopeOf("declarations")).as("a longer name is a scope")
            .isPresent();
        assertThat(AddressParser.partsOf("dispatch://whoami/sprint/1.0"))
            .as("and a complete address under a reserved name is no address").isEmpty();
    }

    @Test
    void the_check_reports_a_literal_path_whose_name_is_still_a_scope() {
        assertThat(reservedOffences(Set.of("/api/status")))
            .as("RED STATE, observed: a literal path whose name the grammar takes as a scope "
                + "must be reported")
            .containsExactly("status");
    }

    /** The names of literal paths the address grammar still takes as a scope. */
    static List<String> reservedOffences(Set<String> literals) {
        return literals.stream().map(path -> path.substring("/api/".length()))
            .filter(name -> AddressParser.scopeOf(name).isPresent())
            .sorted().toList();
    }

    static List<String> pathOffences(List<String> paths) {
        List<String> offences = new ArrayList<>();
        for (String path : paths) {
            boolean literal = !path.contains("{");
            if (literal ? !LITERAL.contains(path)
                    : !path.startsWith("/api/{scope}")) {
                offences.add(path);
            }
        }
        return offences;
    }

    /** Every path a resource answers at: the class's path, joined with each method's. */
    private static List<String> pathsOf(List<Class<?>> resources) {
        List<String> paths = new ArrayList<>();
        for (Class<?> resource : resources) {
            String base = resource.getAnnotation(Path.class).value();
            boolean any = false;
            for (Method method : resource.getDeclaredMethods()) {
                Path sub = method.getAnnotation(Path.class);
                if (sub != null) {
                    paths.add(base + "/" + sub.value());
                    any = true;
                }
            }
            if (!any) {
                paths.add(base);
            }
        }
        return paths;
    }
}
