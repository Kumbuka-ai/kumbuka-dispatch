/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.rest;

import ai.kumbuka.dispatch.surface.ProcessVerb;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

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
 */
class RestRouteFormTest {

    @Test
    void every_route_has_the_form_of_its_effect_class() {
        for (RestRoute route : RestRoute.values()) {
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
        assertThat(Arrays.stream(RestRoute.values()).map(RestRoute::verb).distinct().count())
            .as("every call has its route").isEqualTo(ProcessVerb.values().length);
    }
}
