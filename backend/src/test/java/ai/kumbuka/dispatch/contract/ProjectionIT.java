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
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fields a caller may not see are absent, never present and empty (C6.5,
 * DEC-0040).
 *
 * <p>The end of a lease is the holder's to read: the holder's read carries
 * {@code lease_expires_at}, and the read of another executor and of the
 * commissioner carries no such member — not {@code null}, not an empty
 * string, no member. The options of a question are the commissioner's and the
 * holder's while the question stands, and no member before it is asked. Held
 * on both surfaces.
 *
 * <p>RED STATE, observed 2026-10-16 with a violation planted for the run: the
 * end of the lease handed to every reader turned the first case red.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ProjectionIT {

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    void the_end_of_a_lease_is_in_the_holder_s_read_and_absent_from_every_other() {
        String id = SurfaceFixture.open("projected", "code");
        String address = SurfaceFixture.address(id);
        SurfaceFixture.asExecutor(identity);
        Mcp.answer(Mcp.call("dispatch_claim", Map.of("address", address)));

        assertThat(fieldsOverBoth(id, address)).as("the holder reads the end of its lease")
            .allSatisfy(fields -> assertThat(fields).containsKey("lease_expires_at"));

        SurfaceFixture.asOtherExecutor(identity);
        assertThat(fieldsOverBoth(id, address)).as("another executor reads no such member")
            .allSatisfy(fields -> assertThat(fields).doesNotContainKey("lease_expires_at"));

        SurfaceFixture.asConsole(identity);
        assertThat(fieldsOverBoth(id, address)).as("nor does the commissioner")
            .allSatisfy(fields -> assertThat(fields).doesNotContainKey("lease_expires_at")
                .doesNotContainKey("question"));
    }

    @Test
    void the_options_of_a_question_are_absent_until_it_is_asked() {
        String id = SurfaceFixture.open("asked", "code");
        String address = SurfaceFixture.address(id);
        assertThat(fieldsOverBoth(id, address))
            .allSatisfy(fields -> assertThat(fields).doesNotContainKey("question"));

        SurfaceFixture.asExecutor(identity);
        String receipt = String.valueOf(Mcp.answer(Mcp.call("dispatch_claim",
            Map.of("address", address))).get("receipt"));
        Mcp.answer(Mcp.call("dispatch_ask", Map.of("address", address, "receipt", receipt,
            "fields", Map.of("question", "which?", "options", java.util.List.of("a", "b")))));

        SurfaceFixture.asConsole(identity);
        assertThat(fieldsOverBoth(id, address))
            .allSatisfy(fields -> assertThat(fields).containsKey("question"));
    }

    /** The fields of one task as a read answers them, over MCP and over REST. */
    @SuppressWarnings("unchecked")
    private static java.util.List<Map<String, Object>> fieldsOverBoth(String id, String address) {
        Map<String, Object> overMcp = (Map<String, Object>) Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", address))).get("fields");
        Map<String, Object> overRest = given().get(SurfaceFixture.item(id))
            .jsonPath().getMap("fields");
        return java.util.List.of(overMcp, overRest);
    }
}
