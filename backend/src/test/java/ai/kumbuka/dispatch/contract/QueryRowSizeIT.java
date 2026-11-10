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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A measurement, not a gate: the size of a {@code dispatch_query} page of 50
 * rows, per row, on both surfaces (F-0407).
 *
 * <p>Every row carries the full head of its task with every {@code next} step
 * and its description. Which members a row carries is the catalogue's to fix in
 * a later step of REA-0010; this step records the size and changes nothing.
 * The numbers are written to the test output under {@code F-0407}; the only
 * assertion is that the page measured is the page of 50 it claims to be.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class QueryRowSizeIT {

    private static final int ROWS = 50;
    private static final String APPARATUS = "measured";

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    void a_page_of_fifty_rows_is_measured_per_row() {
        for (int i = 0; i < ROWS; i++) {
            SurfaceFixture.open("measured task " + i, APPARATUS);
        }
        Map<String, Object> query = Map.of("scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR, "apparatus", APPARATUS, "page_size", ROWS);

        Response overMcp = Mcp.call("dispatch_query", query);
        List<?> mcpRows = (List<?>) Mcp.answer(overMcp).get("objects");
        String mcpPage = Mcp.answer(overMcp).toString();
        Response overRest = given().queryParam("apparatus", APPARATUS)
            .queryParam("page_size", ROWS).get(SurfaceFixture.collection());
        List<?> restRows = overRest.jsonPath().getList("objects");

        assertThat(mcpRows).as("the page measured holds %d rows", ROWS).hasSize(ROWS);
        assertThat(restRows).hasSize(ROWS);

        int mcpBytes = overMcp.asString().getBytes(StandardCharsets.UTF_8).length;
        int restBytes = overRest.asString().getBytes(StandardCharsets.UTF_8).length;
        System.out.printf("F-0407 measurement: MCP tools/call response %d bytes for %d rows, "
                + "%d bytes per row (structuredContent as text %d characters)%n",
            mcpBytes, ROWS, mcpBytes / ROWS, mcpPage.length());
        System.out.printf("F-0407 measurement: REST query response %d bytes for %d rows, "
                + "%d bytes per row%n", restBytes, ROWS, restBytes / ROWS);
    }
}
