/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.rest;

import ai.kumbuka.dispatch.surface.SurfaceException;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The header {@code Kumbuka-Surface} on the REST surface (C14.4, C14.5): an
 * authenticated request under {@code /api} that carries it with another value
 * than {@code assistant}, or more than once, is refused with {@code
 * HEADER_INVALID}; one that carries it once with that value, or not at all,
 * passes; a request outside {@code /api}, and one with no identity, is not
 * read.
 *
 * <p>The filter is handed each request directly, so every condition is met on
 * its own; it runs in the service because the refusal's message is the
 * bundle's. The values and the header's name are written here from the
 * contract.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class SurfaceHeaderFilterIT {

    private static final String HEADER = "Kumbuka-Surface";

    @Test
    void another_value_or_the_header_twice_is_refused_as_header_invalid() {
        SurfaceHeaderFilter filter = filter(false);
        for (List<String> sent : List.of(List.of("router"), List.of(""),
                List.of("assistant", "assistant"))) {
            ContainerRequestContext request = request("/api/whoami", sent);
            assertThatThrownBy(() -> filter.filter(request))
                .as("the header sent as %s is a value the surface cannot take", sent)
                .isInstanceOfSatisfying(SurfaceException.class, refused -> {
                    assertThat(refused.refusal().code().name()).isEqualTo("HEADER_INVALID");
                    assertThat(refused.refusal().data()).as("and carries no data").isNull();
                });
        }
    }

    @Test
    void the_assistant_value_once_or_no_header_passes() {
        SurfaceHeaderFilter filter = filter(false);
        ContainerRequestContext assistant = request("/api/whoami", List.of("assistant"));
        ContainerRequestContext empty = request("/api", List.of());
        ContainerRequestContext none = request("api/declaration", null);

        assertThatCode(() -> filter.filter(assistant))
            .as("the one value the header takes").doesNotThrowAnyException();
        assertThatCode(() -> filter.filter(empty))
            .as("the header sent with no value at all is no header")
            .doesNotThrowAnyException();
        assertThatCode(() -> filter.filter(none))
            .as("and a request without it is answered in the REST names")
            .doesNotThrowAnyException();
    }

    @Test
    void outside_the_rest_surface_and_without_an_identity_the_header_is_not_read() {
        SurfaceHeaderFilter authenticated = filter(false);
        SurfaceHeaderFilter anonymous = filter(true);
        ContainerRequestContext health = request("/q/health", List.of("router"));
        ContainerRequestContext apiary = request("/apiary", List.of("router"));
        ContainerRequestContext whoami = request("/api/whoami", List.of("router"));

        assertThatCode(() -> authenticated.filter(health))
            .as("a path outside /api does not read the header")
            .doesNotThrowAnyException();
        assertThatCode(() -> authenticated.filter(apiary))
            .as("nor one that merely begins with the letters of the base path")
            .doesNotThrowAnyException();
        assertThatCode(() -> anonymous.filter(whoami))
            .as("a caller with no identity is left to the challenge of the resource")
            .doesNotThrowAnyException();
    }

    private static SurfaceHeaderFilter filter(boolean anonymous) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(anonymous);
        SurfaceHeaderFilter filter = new SurfaceHeaderFilter();
        filter.identity = identity;
        return filter;
    }

    /** A request on a path, carrying the header with the values given; null for none. */
    private static ContainerRequestContext request(String path, List<String> values) {
        UriInfo uri = mock(UriInfo.class);
        when(uri.getPath()).thenReturn(path);
        MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        if (values != null) {
            headers.put(HEADER, values);
        }
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getUriInfo()).thenReturn(uri);
        when(request.getHeaders()).thenReturn(headers);
        return request;
    }
}
