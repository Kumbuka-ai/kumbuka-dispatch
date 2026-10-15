/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.rest;

import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.RefusalCode;
import ai.kumbuka.dispatch.surface.Surface;
import ai.kumbuka.dispatch.surface.VerbName;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

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
 * /api/{scope}}. C3.3: a verb the service does not carry at an address
 * answers {@code VERB_NOT_CARRIED} 405, and {@code data.accepts} lists the
 * verbs that address carries.
 */
class RestRouteFormTest {

    @Test
    void every_route_has_the_form_of_its_effect_class() {
        for (CustomMethod route : CustomMethod.values()) {
            ProcessVerb verb = route.verb();
            String form = route.method() + " " + route.depth() + (route.custom() ? " :call" : "");
            String expected = switch (verb.effectClass()) {
                case ITEM_READ -> "GET ITEM";
                case COLLECTION_READ -> "GET COLLECTION";
                case CREATE -> "POST COLLECTION";
                case FIELD_WRITE -> "PATCH ITEM";
                case ITEM_DELETE -> "DELETE ITEM";
                case ITEM_TRANSITION -> "POST ITEM :call";
                case ITEM_READ_CALL -> "GET ITEM :call";
                case COLLECTION_COMPOUND -> "POST COLLECTION :call";
                default -> "a class this service declares for no call";
            };
            assertThat(form).as("%s is declared %s", verb.call(), verb.effectClass())
                .isEqualTo(expected);
        }
        assertThat(Arrays.stream(CustomMethod.values()).map(CustomMethod::verb).distinct().count())
            .as("every call has its route").isEqualTo(ProcessVerb.values().length);
    }

    /** The resources of the REST surface, by the class a test can load. */
    private static final List<Class<?>> RESOURCES = List.of(TaskResource.class,
        DeclarationResource.class, WhoamiResource.class);

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
    void a_verb_not_carried_at_an_address_answers_405_listing_what_the_address_carries() {
        assertThat(RefusalCode.VERB_NOT_CARRIED.status()).as("C3.3").isEqualTo(405);
        assertThat(RefusalCode.VERB_NOT_CARRIED.messages())
            .as("the unknown verb and every route form are refused under the one code")
            .extracting(RefusalCode.Message::key)
            .contains("callUnknown", "routeCreate", "routeOnATask");
        for (CustomMethod.Depth depth : CustomMethod.Depth.values()) {
            assertThat(names(CustomMethod.accepts(depth)))
                .as("data.accepts at %s lists the verbs carried there", depth)
                .isEqualTo(carriedAt(depth));
        }
    }

    @Test
    void the_check_reports_accepts_that_are_not_what_the_address_carries() {
        Set<String> planted = new TreeSet<>(carriedAt(CustomMethod.Depth.COLLECTION));
        planted.add("send");
        assertThat(planted)
            .as("RED STATE, observed: a list of accepts that names a verb of another depth "
                + "is not the set the address carries")
            .isNotEqualTo(carriedAt(CustomMethod.Depth.COLLECTION));
    }

    /** The verbs carried at one depth, read from the routes, by their REST names. */
    private static Set<String> carriedAt(CustomMethod.Depth depth) {
        Set<String> carried = new TreeSet<>();
        for (CustomMethod route : CustomMethod.values()) {
            if (route.depth() == depth) {
                carried.add(route.call());
            }
        }
        return carried;
    }

    private static Set<String> names(List<VerbName> verbs) {
        Set<String> names = new TreeSet<>();
        verbs.forEach(verb -> names.add(verb.toString()));
        assertThat(verbs).allSatisfy(verb ->
            assertThat(ProcessVerb.byName(Surface.REST, verb.toString())).isNotNull());
        return names;
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
