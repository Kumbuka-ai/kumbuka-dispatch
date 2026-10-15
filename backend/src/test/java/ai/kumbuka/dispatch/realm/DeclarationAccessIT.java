/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.realm;

import ai.kumbuka.dispatch.architecture.DeclarationShapeTest;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who reads the declaration, where, and in which shape (C5.1, C5.2, C5.3).
 *
 * <p>Against a real identity provider, because the statement is about a real
 * token: the tenant realm's user holds the realm's {@code member} role and
 * neither of this service's capacities, so a token of it is a valid platform
 * token with no role here. That token reads the declaration; no token reads
 * nothing; and the declaration is served at {@code /api/declaration} and
 * nowhere else.
 *
 * <p>The shape of what is served is checked against the schema in the test
 * tree, through {@link DeclarationShapeTest#violations}: the declaration
 * carries the texts of the message bundles, which only the running service
 * can render.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = TwoRealmKeycloakResource.class, restrictToAnnotatedClass = true)
@TestProfile(RealmBoundProfile.class)
class DeclarationAccessIT {

    private static final String DECLARATION = "/api/declaration";

    @Test
    void a_valid_token_with_no_role_of_this_service_reads_the_declaration() {
        Response read = given()
            .header("Authorization", "Bearer " + memberToken())
            .when().get(DECLARATION);

        assertThat(read.statusCode())
            .as("the declaration needs a valid platform token and no role (C5.2): the "
                + "router reads it with its own service identity, which carries no capacity "
                + "of this service")
            .isEqualTo(200);
        assertThat(read.header("ETag"))
            .as("the ETag is the declaration's version (C5.1)")
            .isEqualTo("\"" + read.jsonPath().getString("version") + "\"");
    }

    @Test
    void no_token_reads_no_declaration() {
        assertThat(given().when().get(DECLARATION).statusCode())
            .as("the declaration is behind authentication like every route of the surface")
            .isEqualTo(401);
    }

    @Test
    void the_declaration_is_served_at_one_path_only() {
        assertThat(given()
                .header("Authorization", "Bearer " + memberToken())
                .when().get("/mcp/declaration").statusCode())
            .as("the declaration is served at /api/declaration and nowhere else (C5.1); a "
                + "second copy is a second place a reader may find a different version")
            .isEqualTo(404);
    }

    @Test
    void the_served_declaration_has_the_contract_shape() {
        Map<String, Object> declaration = given()
            .header("Authorization", "Bearer " + memberToken())
            .when().get(DECLARATION)
            .then().statusCode(200)
            .extract().jsonPath().getMap("$");

        assertThat(DeclarationShapeTest.violations(declaration))
            .as("the declaration, as served, against the schema of C5.3 and C5.4 held in the "
                + "test tree")
            .isEmpty();
    }

    private static String memberToken() {
        return RealmBindingIT.tokenFrom(
            RealmBindingIT.issuer(TwoRealmKeycloakResource.TENANT_ISSUER_KEY),
            TwoRealmKeycloakResource.TENANT_USER);
    }
}
