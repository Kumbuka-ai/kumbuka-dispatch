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
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * The audience binding (C15.1, C15.2, F-0358): a token from the tenant realm,
 * valid in every other respect, is refused when the service is configured for
 * another audience than the one the token names.
 *
 * <p>{@link RealmBindingIT} varies the issuer and keeps the audience; this
 * varies the audience and keeps the issuer. The token names {@code
 * kumbuka-dispatch} as its audience, and the service here expects {@code
 * kumbuka-elsewhere}. That an absent audience refuses the start is {@code
 * IdentityConfigurationGuardTest}'s.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = TwoRealmKeycloakResource.class, restrictToAnnotatedClass = true)
@TestProfile(AudienceBindingIT.ForeignAudience.class)
class AudienceBindingIT {

    /** The realm binding of {@link RealmBoundProfile}, expecting another audience. */
    public static class ForeignAudience implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(
                new RealmBoundProfile().getConfigOverrides());
            overrides.put("quarkus.oidc.token.audience", "kumbuka-elsewhere");
            return overrides;
        }
    }

    @Test
    void a_token_for_another_audience_is_refused() {
        String token = RealmBindingIT.tokenFrom(
            RealmBindingIT.issuer(TwoRealmKeycloakResource.TENANT_ISSUER_KEY),
            TwoRealmKeycloakResource.TENANT_USER);

        given()
            .header("Authorization", "Bearer " + token)
            .when().get("/api/whoami")
            .then()
            .statusCode(401);
    }
}
