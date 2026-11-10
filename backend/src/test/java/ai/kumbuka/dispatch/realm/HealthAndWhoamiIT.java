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
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/whoami} answers the authenticated subject's platform view
 * (C16.2), beside the health probes of C16.1.
 *
 * <p>The token is a real one of the tenant realm. The subject answered is the
 * token's {@code sub}, the identifier the service records an actor by (C15.1),
 * and never a display name.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = TwoRealmKeycloakResource.class, restrictToAnnotatedClass = true)
@TestProfile(RealmBoundProfile.class)
class HealthAndWhoamiIT {

    @Test
    void whoami_answers_the_subject_of_the_token_and_the_tenant() {
        String token = RealmBindingIT.tokenFrom(
            RealmBindingIT.issuer(TwoRealmKeycloakResource.TENANT_ISSUER_KEY),
            TwoRealmKeycloakResource.TENANT_USER);

        Response whoami = given().header("Authorization", "Bearer " + token)
            .when().get("/api/whoami");

        assertThat(whoami.statusCode()).as(whoami.asString()).isEqualTo(200);
        assertThat(whoami.jsonPath().getString("subject"))
            .as("the token's subject, never the user name")
            .isNotBlank()
            .isNotEqualTo(TwoRealmKeycloakResource.TENANT_USER);
        assertThat(whoami.jsonPath().getString("tenant")).isNotBlank();
    }

    @Test
    void the_health_probes_answer_beside_it() {
        assertThat(given().get("/q/health/live").statusCode()).isEqualTo(200);
        assertThat(given().get("/q/health/ready").statusCode()).isEqualTo(200);
    }
}
