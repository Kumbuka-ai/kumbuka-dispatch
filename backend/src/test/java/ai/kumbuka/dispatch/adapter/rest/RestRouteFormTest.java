/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.rest;

import ai.kumbuka.dispatch.surface.AddressParser;
import ai.kumbuka.dispatch.surface.EffectClass;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.RefusalCode;
import ai.kumbuka.dispatch.surface.Surface;
import ai.kumbuka.dispatch.surface.VerbName;
import jakarta.ws.rs.Path;
import org.jspecify.annotations.Nullable;
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
 * <p>The red state of the form is observed on every build: the same check,
 * handed every call as declared under another class, reports each call whose
 * route has another form.
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

    @Test
    void every_route_has_the_form_of_its_effect_class() {
        assertThat(formOffences(List.of(CustomMethod.values())))
            .as("every route has the HTTP form the router builds for its call's effect class")
            .isEmpty();
        assertThat(Arrays.stream(CustomMethod.values()).map(CustomMethod::verb).distinct().count())
            .as("every call has its route").isEqualTo(ProcessVerb.values().length);
    }

    @Test
    void the_check_reports_a_route_whose_form_is_not_that_of_its_effect_class() {
        assertThat(formOffences(List.of(CustomMethod.values()), EffectClass.ITEM_TRANSITION))
            .as("RED STATE, observed: every call declared as ITEM_TRANSITION, for the check, "
                + "turns it red on the three calls of a collection -- read_definition a GET on "
                + "a collection, start a custom method on one, create the plain POST on one -- "
                + "and on the plain PATCH and DELETE of a task; ITEM_TRANSITION is a custom "
                + "method on an item, so the four calls on an instance and send pass")
            .containsExactly(
                "dispatch_read_definition is declared ITEM_TRANSITION and routed "
                    + "GET COLLECTION, where the class takes POST ITEM :call",
                "dispatch_start is declared ITEM_TRANSITION and routed "
                    + "POST COLLECTION :call, where the class takes POST ITEM :call",
                "dispatch_create is declared ITEM_TRANSITION and routed "
                    + "POST COLLECTION, where the class takes POST ITEM :call",
                "dispatch_update is declared ITEM_TRANSITION and routed "
                    + "PATCH ITEM, where the class takes POST ITEM :call",
                "dispatch_delete is declared ITEM_TRANSITION and routed "
                    + "DELETE ITEM, where the class takes POST ITEM :call");
    }

    /**
     * The routes whose form is not the one their call's effect class names.
     * The form of each class is written here from the router's definitions,
     * not read from either side.
     */
    static List<String> formOffences(List<CustomMethod> routes) {
        return formOffences(routes, null);
    }

    /**
     * As above, with every call taken as declared under {@code planted} where
     * one is given, so that the check can be seen reporting a mismatch.
     */
    static List<String> formOffences(List<CustomMethod> routes, @Nullable EffectClass planted) {
        List<String> offences = new ArrayList<>();
        for (CustomMethod route : routes) {
            ProcessVerb verb = route.verb();
            EffectClass declared = planted == null ? verb.effectClass() : planted;
            String form = route.method() + " " + route.depth() + (route.custom() ? " :call" : "");
            String expected = switch (declared) {
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
            if (!form.equals(expected)) {
                offences.add(verb.call() + " is declared " + declared + " and routed " + form
                    + ", where the class takes " + expected);
            }
        }
        return offences;
    }

    /** The resources of the REST surface, by the class a test can load. */
    private static final List<Class<?>> RESOURCES = List.of(ProcessResource.class,
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

    @Test
    void a_verb_not_carried_at_an_address_answers_405_listing_what_the_address_carries() {
        assertThat(RefusalCode.VERB_NOT_CARRIED.status()).as("C3.3").isEqualTo(405);
        assertThat(RefusalCode.VERB_NOT_CARRIED.messages())
            .as("the unknown verb and the method no route takes are refused under the one code")
            .extracting(RefusalCode.Message::key)
            .contains("callUnknown", "methodNotCarried");
        for (CustomMethod.Depth depth : CustomMethod.Depth.values()) {
            assertThat(names(CustomMethod.accepts(depth, Surface.REST)))
                .as("data.accepts at %s lists the verbs carried there", depth)
                .isEqualTo(carriedAt(depth));
        }
        assertThat(carriedAt(CustomMethod.Depth.COLLECTION))
            .as("a collection carries read_definition, start and create")
            .containsExactly("create", "read_definition", "start");
        assertThat(CustomMethod.allow(CustomMethod.Depth.COLLECTION))
            .as("and is reached there with GET and POST").contains("GET, POST");
        assertThat(carriedAt(CustomMethod.Depth.ITEM))
            .as("an instance carries its two closings and the two calls on a reference, and a "
                + "task update, delete, send, and claim, renew, release and defer")
            .containsExactly("claim", "conclude", "defer", "delete", "reference_attach",
                "reference_detach", "release", "renew", "send", "update", "withdraw");
        assertThat(CustomMethod.allow(CustomMethod.Depth.ITEM))
            .as("and is reached there with POST, PATCH and DELETE").contains("POST, PATCH, DELETE");
    }

    @Test
    void the_check_reports_accepts_that_are_not_what_the_address_carries() {
        Set<String> planted = new TreeSet<>(carriedAt(CustomMethod.Depth.COLLECTION));
        planted.add("send");
        assertThat(planted)
            .as("RED STATE, observed: a list of accepts that names a verb the address does not "
                + "carry is not the set the address carries")
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
        assertThat(names).allSatisfy(name ->
            assertThat(ProcessVerb.byName(Surface.REST, name)).isPresent());
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
