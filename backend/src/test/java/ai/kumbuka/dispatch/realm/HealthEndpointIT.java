/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.realm;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only {@code /q/health} answers without a token (C15.1, C16.1).
 *
 * <p>Run with the bearer tenant on, against the tenant realm, so that "without
 * a token" means what it means in a deployment: the liveness and readiness
 * probes answer, and every route of the verb surface, the declaration and
 * whoami included, answers the protocol's challenge and nothing else (C15.4).
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = TwoRealmKeycloakResource.class, restrictToAnnotatedClass = true)
@TestProfile(RealmBoundProfile.class)
class HealthEndpointIT {

    @Test
    void liveness_and_readiness_answer_without_a_token() {
        for (String probe : List.of("/q/health/live", "/q/health/ready")) {
            given().when().get(probe).then().statusCode(200);
        }
    }

    @Test
    void every_other_route_answers_the_challenge_without_a_token() {
        for (String path : List.of("/api/whoami", "/api/declaration", "/api/probe/sprint",
                "/api/probe/sprint/1.0")) {
            var answer = given().when().get(path);
            assertThat(answer.statusCode()).as("%s without a token", path).isEqualTo(401);
            assertThat(answer.asString()).as("%s: the challenge, no envelope", path)
                .doesNotContain("\"reason\"");
        }
        assertThat(given().contentType(ContentType.JSON)
                .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                .post("/mcp").statusCode())
            .as("/mcp without a token").isEqualTo(401);
    }
}
