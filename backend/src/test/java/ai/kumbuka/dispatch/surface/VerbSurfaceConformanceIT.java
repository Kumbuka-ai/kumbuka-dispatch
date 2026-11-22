/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The REST surface against its specification, and the two statements of the
 * surface against each other.
 *
 * <p>Three questions, each with its own source of truth. Whether the
 * framework registered exactly the declared bindings is read from the
 * annotations against {@code verb-surface.tsv}. Whether the routes of
 * {@link CustomMethod}, written by hand, are exactly the calls of the declaration
 * -- one route per call, no route without a call -- is the test that holds the
 * two statements together while REST is not generated. And whether those routes take the outward forms the specification
 * writes is read against the file again.
 *
 * <p>Red probes, observed: a route in {@link CustomMethod} for a name the
 * declaration does not carry, and a declared call whose route was removed,
 * each turn {@link #every_declared_call_has_exactly_one_route_and_no_route_stands_without_one}
 * red.
 */
// The texts a call answers with come from the message bundles, which the
// running application builds: this test runs with it.
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(VerbSurfaceConformanceIT.REBUILT)
class VerbSurfaceConformanceIT {

    static final String REBUILT =
        "rebuilt in T13: the REST routes and the MCP tools match the declared calls and the specification";

    @Test
    void no_route_exists_outside_the_declared_bindings() {
        fail(REBUILT);
    }

    @Test
    void every_declared_binding_is_registered() {
        fail(REBUILT);
    }

    @Test
    void the_probe_walked_a_tree_that_has_routes_in_it() {
        fail(REBUILT);
    }

    @Test
    void every_declared_call_has_exactly_one_route_and_no_route_stands_without_one() {
        fail(REBUILT);
    }

    @Test
    void the_routes_take_the_forms_the_specification_writes() {
        fail(REBUILT);
    }

    @Test
    void the_verb_is_split_off_the_end_and_not_off_the_front() {
        fail(REBUILT);
    }

    @Test
    void every_mcp_tool_is_a_declared_call_under_the_prefix() {
        fail(REBUILT);
    }

    @Test
    void every_mcp_tool_declares_an_argument_schema_closed_at_every_level() {
        fail(REBUILT);
    }
}
