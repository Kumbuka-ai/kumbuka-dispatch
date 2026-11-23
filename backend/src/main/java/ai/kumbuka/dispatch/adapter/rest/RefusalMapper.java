/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.rest;

import ai.kumbuka.dispatch.adapter.payload.Payloads;
import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.surface.PlatformLog;
import ai.kumbuka.dispatch.surface.Refusal;
import ai.kumbuka.dispatch.surface.RefusalCode;
import ai.kumbuka.dispatch.surface.SurfaceException;
import ai.kumbuka.dispatch.surface.UnexpectedFailures;
import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Every refusal of the REST surface, in its envelope and with its status: the
 * outermost edge, and with {@code McpAdapter} the only place a refusal is
 * caught (C8.2).
 *
 * <p>One mapper per type, nested here: the surface's refusals, which arrive
 * worded; the domain's; and everything else, which is ours and answered as
 * {@code UNEXPECTED_FAILURE} with a reference (C8.3). The status of a refusal
 * is its code's, from the catalogue.
 *
 * <p>No call is offered while the service is rebuilt on the store line, so no
 * refusal is raised under a call yet: a domain refusal is answered as one
 * outside a call, and a refusal worded against the call it refuses comes back
 * with the change that binds the call.
 */
@Provider
public class RefusalMapper implements ExceptionMapper<SurfaceException> {

    @Override
    public Response toResponse(SurfaceException refused) {
        PlatformLog.refused(PlatformLog.Reason.of(refused.refusal().code()));
        return answer(refused.refusal(), refused.allow().orElse(null));
    }

    /** One refusal as an HTTP response: its code's status, its envelope, and for a 405 the methods. */
    static Response answer(Refusal refusal, @Nullable String allow) {
        Response.ResponseBuilder response = Response.status(refusal.code().status())
            .type(MediaType.APPLICATION_JSON)
            .entity(Payloads.refusal(refusal));
        if (allow != null) {
            response.header(HttpHeaders.ALLOW, allow);
        }
        return response.build();
    }

    /**
     * A refusal of the domain, outside a call: only the not-found class can
     * be said there, and anything else is ours.
     */
    @Provider
    public static class Domain implements ExceptionMapper<DispatchException> {

        @Override
        public Response toResponse(DispatchException e) {
            // DEBUG by its code, and WARN where the scope refused (C13.3): the
            // directory below logs nothing, so its line is written here.
            PlatformLog.domainRefused(e.reason());
            return answer(RefusalCode.of(e.reason()) == RefusalCode.NOT_FOUND
                ? Refusal.notFound()
                : UnexpectedFailures.refuse(null, e), null);
        }
    }

    /**
     * A 403 of the security layer, outside a call: it goes back as it is. A
     * call the caller's permission does not reach is answered {@code
     * ROLE_INSUFFICIENT} after the call's scopes, from the change that binds
     * the call.
     */
    @Provider
    public static class NotPermitted implements ExceptionMapper<ForbiddenException> {

        @Override
        public Response toResponse(ForbiddenException e) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
    }

    /**
     * Everything that is no refusal (C8.3). A {@link WebApplicationException}
     * is the framework's own answer. A route that does not exist is {@code
     * NOT_FOUND}, byte for byte the one of an absent object; a method the route
     * does not take is {@code VERB_NOT_CARRIED} with the route's {@code Allow}.
     * Every other status of the framework, the security layer's 401 and 403
     * among them, goes back unchanged (the authentication exception of
     * DEC-0042). Anything else is a defect on our side, logged once with a
     * reference and answered with it.
     */
    @Provider
    public static class Unexpected implements ExceptionMapper<Throwable> {

        @Override
        public Response toResponse(Throwable failure) {
            if (failure instanceof WebApplicationException framework) {
                Response given = framework.getResponse();
                return switch (given.getStatus()) {
                    case 404 -> answer(Refusal.notFound(), null);
                    case 405 -> answer(Refusal.methodNotCarried(List.of()),
                        given.getHeaderString(HttpHeaders.ALLOW));
                    default -> given;
                };
            }
            return answer(UnexpectedFailures.refuse(null, failure), null);
        }
    }
}
