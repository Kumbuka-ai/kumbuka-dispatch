/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.rest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The route a request takes, read from its method and its path, as the edge
 * reads it to word a refusal against the call the request became.
 */
class CustomMethodTest {

    @Test
    void a_get_on_a_collection_is_read_definition_and_nothing_else_is_a_route() {
        assertThat(CustomMethod.of("GET", "/api/probe/sprint"))
            .as("GET on a collection").contains(CustomMethod.READ_DEFINITION);
        assertThat(CustomMethod.of("GET", "api/probe/sprint/"))
            .as("without the leading slash and with a trailing one alike")
            .contains(CustomMethod.READ_DEFINITION);
        assertThat(CustomMethod.of("POST", "/api/probe/sprint"))
            .as("the plain POST on a collection is create").contains(CustomMethod.CREATE);
        assertThat(CustomMethod.of("PUT", "/api/probe/sprint"))
            .as("another method on a collection").isEmpty();
        assertThat(CustomMethod.of("GET", "/api/probe/sprint:read_definition"))
            .as("the call in colon notation, which it does not take").isEmpty();
        assertThat(CustomMethod.of("GET", "/api/probe/sprint/1.0"))
            .as("GET on an item, which carries no call").isEmpty();
        assertThat(CustomMethod.of("GET", "/api/whoami")).as("a literal path").isEmpty();
        assertThat(CustomMethod.of("GET", "/mcp/probe/sprint")).as("a path outside the base")
            .isEmpty();
    }

    @Test
    void a_segment_is_split_at_its_last_colon() {
        assertThat(CustomMethod.split("sprint")).containsExactly("sprint", null);
        assertThat(CustomMethod.split("1.0:send")).containsExactly("1.0", "send");
        assertThat(CustomMethod.split("a:b:c")).containsExactly("a:b", "c");
    }

    @Test
    void the_depth_of_a_path_is_read_off_its_segments() {
        assertThat(CustomMethod.depthOf("/api/probe/sprint"))
            .contains(CustomMethod.Depth.COLLECTION);
        assertThat(CustomMethod.depthOf("/api/probe/sprint/1.0"))
            .contains(CustomMethod.Depth.ITEM);
        assertThat(CustomMethod.depthOf("/api/probe")).isEmpty();
        assertThat(CustomMethod.depthOf("/api/probe/sprint/1.0/more")).isEmpty();
    }
}
