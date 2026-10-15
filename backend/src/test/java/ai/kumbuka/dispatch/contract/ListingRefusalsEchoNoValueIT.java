/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.surface.Mcp;
import ai.kumbuka.dispatch.surface.SurfaceFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The refusals of a listing that the kernel decides carry no value the caller
 * sent, on REST and on MCP (DEC-0042, DEC-0043 R18).
 *
 * <p>Each case sends a value that passes every check of form the adapter makes
 * and reaches the kernel's own refusal: a page size that is a whole number
 * outside the page's bounds, a cursor that is a string and no cursor, a filter
 * value outside the field's form. The refusal is then searched for the value,
 * and {@code data.offenders} must name the declared argument — {@code
 * page_size}, {@code after}, the filter by its name, with the value's position
 * where it is one of several — and nothing else.
 *
 * <p>A sort or a direction the listing does not declare is refused by the
 * adapter before the kernel is reached, because both are declared closed sets;
 * the case stands here so that the wire carries no value for either, and the
 * kernel's own refusal of them is held in {@code ListingRefusalsTest}.
 *
 * <p>RED STATE, observed 2026-10-16 against the tree in which the edge maps a
 * kernel refusal with the kernel's {@code offenders} and the kernel still
 * named the values: page size, cursor and filter turned this red on both
 * surfaces, each carrying the value sent in {@code data.offenders}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ListingRefusalsEchoNoValueIT {

    private static final String PAGE_SIZE = "73190731";
    private static final String CURSOR = "zzcursor7319";
    private static final String SORT = "zzsort7319";
    private static final String DIRECTION = "zzdirection7319";
    private static final String STATE = "zzstate7319";
    private static final String BRACKET = "zzbracket7319";

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    void a_page_size_outside_the_page_names_page_size_and_not_the_value() {
        for (Refused refused : both(Map.of("page_size", PAGE_SIZE))) {
            refused.is("PAGE_SIZE_REJECTED", List.of("page_size"), PAGE_SIZE);
        }
    }

    @Test
    void a_cursor_this_service_did_not_hand_out_names_after_and_not_the_value() {
        for (Refused refused : both(Map.of("after", CURSOR))) {
            refused.is("CURSOR_MALFORMED", List.of("after"), CURSOR);
        }
    }

    @Test
    void an_undeclared_sort_or_direction_names_the_argument_and_not_the_value() {
        for (Refused refused : both(Map.of("sort", SORT))) {
            refused.is("SORT_UNDECLARED", List.of("sort"), SORT);
        }
        for (Refused refused : both(Map.of("direction", DIRECTION))) {
            refused.is("ARGUMENT_INVALID", List.of("direction"), DIRECTION);
        }
    }

    @Test
    void a_filter_value_the_field_cannot_take_names_the_filter_and_not_the_value() {
        for (Refused refused : both(Map.of("state", STATE))) {
            refused.is("ARGUMENT_INVALID", List.of("state"), STATE);
        }
        for (Refused refused : both(Map.of("state", "open," + STATE))) {
            refused.is("ARGUMENT_INVALID", List.of("state[1]"), STATE);
        }
        for (Refused refused : both(Map.of("bracket", "1," + BRACKET))) {
            refused.is("ARGUMENT_INVALID", List.of("bracket[1]"), BRACKET);
        }
    }

    // -----------------------------------------------------------------------

    /** One refusal as a surface answered it: its status where it has one, and the envelope. */
    private record Refused(String surface, Integer status, String body,
                           Map<String, Object> envelope) {

        @SuppressWarnings("unchecked")
        void is(String reason, List<String> offenders, String sent) {
            assertThat(envelope.get("reason")).as("%s: %s", surface, body).isEqualTo(reason);
            if (status != null) {
                assertThat(status).as("%s status", surface).isEqualTo(400);
            }
            Map<String, Object> data = (Map<String, Object>) envelope.get("data");
            assertThat(data.get("offenders"))
                .as("%s names the declared argument (DEC-0042)", surface)
                .isEqualTo(offenders);
            assertThat(body)
                .as("%s carries no value the caller sent (DEC-0043 R18)", surface)
                .doesNotContain(sent);
        }
    }

    /** The same listing refused on both surfaces. */
    private static List<Refused> both(Map<String, Object> narrowed) {
        Map<String, Object> mcp = new LinkedHashMap<>(Map.of("scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR));
        mcp.putAll(narrowed);
        Response overMcp = Mcp.call("dispatch_query", mcp);
        Response overRest = given().queryParams(narrowed).get(SurfaceFixture.collection());
        return List.of(
            new Refused("MCP", null, overMcp.asString(), Mcp.refusal(overMcp)),
            new Refused("REST", overRest.statusCode(), overRest.asString(),
                overRest.jsonPath().getMap("$")));
    }
}
