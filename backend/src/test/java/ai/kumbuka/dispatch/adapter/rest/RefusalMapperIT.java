/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.rest;

import ai.kumbuka.dispatch.adapter.payload.RefusalPayload;
import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.surface.Refusal;
import ai.kumbuka.dispatch.surface.SurfaceException;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every refusal of the REST surface outside a call: in the envelope of the
 * contract, with the status of its code (C8.2 to C8.4, C3.3).
 *
 * <p>The mappers are handed the exception outside a request, so they answer
 * without a call: a domain refusal is {@code NOT_FOUND} or ours, a 403 of the
 * security layer goes back bare, the framework's 404 is the envelope of
 * {@code NOT_FOUND} and its 405 is {@code VERB_NOT_CARRIED} with the route's
 * {@code Allow} and nothing accepted, and everything else is {@code
 * UNEXPECTED_FAILURE} with a reference. The refusals under the one call the
 * service offers are observed in {@code ReadDefinitionIT}. The statuses and
 * codes are written here from the shared code table of the contract.
 *
 * <p>Runs in the service, because the messages are the bundles' and the
 * bundles exist only in a running application; the mappers are handed the
 * exception directly, so each condition is met on its own.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class RefusalMapperIT {

    /** A reference, as the contract writes one: a fresh UUID. */
    private static final String REFERENCE =
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Test
    void a_domain_refusal_of_the_not_found_class_answers_not_found_without_data() {
        Response answer = new RefusalMapper.Domain().toResponse(new DispatchException(
            DispatchException.Reason.NOT_FOUND, "nothing at zzmarker7319"));

        RefusalPayload refusal = refusalOf(answer, 404);
        assertThat(refusal.reason()).isEqualTo("NOT_FOUND");
        assertThat(refusal.data()).as("NOT_FOUND carries nothing (C8.4)").isNull();
        assertThat(refusal.message()).doesNotContain("zzmarker7319");
    }

    @Test
    void any_other_domain_refusal_outside_a_call_is_ours_and_answers_with_a_reference() {
        Response answer = new RefusalMapper.Domain().toResponse(new DispatchException(
            DispatchException.Reason.SCOPE_LOCKED, "the scope zzlocked is locked"));

        RefusalPayload refusal = refusalOf(answer, 500);
        assertThat(refusal.reason()).isEqualTo("UNEXPECTED_FAILURE");
        assertThat(refusal.data()).isNotNull();
        assertThat(refusal.data().reference()).matches(REFERENCE);
        assertThat(refusal.message()).doesNotContain("zzlocked");
    }

    @Test
    void a_refusal_of_the_surface_answers_with_its_code_s_status_and_its_methods() {
        Response answer = new RefusalMapper().toResponse(
            new SurfaceException(Refusal.methodNotCarried(List.of()), "GET"));

        RefusalPayload refusal = refusalOf(answer, 405);
        assertThat(refusal.reason()).isEqualTo("VERB_NOT_CARRIED");
        assertThat(answer.getHeaderString(HttpHeaders.ALLOW)).isEqualTo("GET");

        Response header = new RefusalMapper().toResponse(
            new SurfaceException(Refusal.headerInvalid()));
        assertThat(refusalOf(header, 400).reason()).isEqualTo("HEADER_INVALID");
        assertThat(header.getHeaderString(HttpHeaders.ALLOW))
            .as("a refusal that is no 405 names no methods").isNull();
    }

    @Test
    void a_403_of_the_security_layer_goes_back_bare() {
        Response answer = new RefusalMapper.NotPermitted().toResponse(new ForbiddenException());

        assertThat(answer.getStatus()).isEqualTo(403);
        assertThat(answer.hasEntity()).as("no envelope: the security layer's own answer")
            .isFalse();
    }

    @Test
    void the_framework_s_404_and_405_are_answered_in_the_envelope_and_its_401_unchanged() {
        RefusalMapper.Unexpected mapper = new RefusalMapper.Unexpected();

        RefusalPayload notFound = refusalOf(mapper.toResponse(new NotFoundException()), 404);
        assertThat(notFound.reason()).isEqualTo("NOT_FOUND");
        assertThat(notFound.data()).isNull();

        Response notAllowed = mapper.toResponse(new NotAllowedException("GET"));
        RefusalPayload refused = refusalOf(notAllowed, 405);
        assertThat(refused.reason()).isEqualTo("VERB_NOT_CARRIED");
        assertThat(refused.data()).isNotNull();
        assertThat(refused.data().accepts())
            .as("outside a request no address is known, so none is accepted").isEmpty();
        assertThat(notAllowed.getHeaderString(HttpHeaders.ALLOW))
            .as("the route's methods, as the framework names them").isEqualTo("GET");

        NotAuthorizedException unauthenticated = new NotAuthorizedException("Bearer");
        assertThat(mapper.toResponse(unauthenticated))
            .as("the challenge of the security layer goes back unchanged")
            .isSameAs(unauthenticated.getResponse());
    }

    @Test
    void a_failure_that_is_no_refusal_is_ours_and_names_nothing_it_held() {
        Response answer = new RefusalMapper.Unexpected().toResponse(
            new IllegalStateException("broke on zzecho4411"));

        RefusalPayload refusal = refusalOf(answer, 500);
        assertThat(refusal.reason()).isEqualTo("UNEXPECTED_FAILURE");
        assertThat(refusal.data()).isNotNull();
        assertThat(refusal.data().reference()).matches(REFERENCE);
        assertThat(refusal.data().attempted()).as("no call was made").isNull();
        assertThat(refusal.message()).doesNotContain("zzecho4411");
    }

    @Test
    void a_path_no_route_serves_answers_not_found_in_the_envelope_over_http() {
        // A path below an object: no route serves it. The collection and the
        // object above it carry calls and answer their own refusals.
        given().when().get("/api/probe-scope/sprint/1/more")
            .then().statusCode(404)
            .body("reason", org.hamcrest.Matchers.equalTo("NOT_FOUND"));
    }

    private static RefusalPayload refusalOf(Response answer, int status) {
        assertThat(answer.getStatus()).as("the status of the code").isEqualTo(status);
        assertThat(answer.getEntity()).isInstanceOf(RefusalPayload.class);
        return (RefusalPayload) answer.getEntity();
    }
}
