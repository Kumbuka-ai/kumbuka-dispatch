/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.rest;

import ai.kumbuka.dispatch.adapter.payload.CallRefusals;
import ai.kumbuka.dispatch.adapter.payload.Payloads;
import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.surface.PlatformLog;
import ai.kumbuka.dispatch.surface.Refusal;
import ai.kumbuka.dispatch.surface.RefusalCode;
import ai.kumbuka.dispatch.surface.Surface;
import ai.kumbuka.dispatch.surface.SurfaceException;
import ai.kumbuka.dispatch.surface.UnexpectedFailures;
import ai.kumbuka.dispatch.surface.VerbName;
import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Every refusal of the REST surface, in its envelope and with its status: the
 * outermost edge, and with {@code McpAdapter} the only place a refusal is
 * caught (C8.2).
 *
 * <p>One mapper per type, nested here: the surface's refusals, which arrive
 * worded; the domain's, which are worded here against the call the request
 * became; a call the caller's permission does not reach; and everything else,
 * which is ours and answered as {@code UNEXPECTED_FAILURE} with a reference
 * (C8.3). The status of a refusal is its code's, from the catalogue.
 *
 * <p>The call a request became is read from the request itself: its method
 * and its path name the route ({@link CustomMethod#of}), and the {@code
 * Kumbuka-Surface} header the vocabulary the call is named in (C14.4). A
 * request that names no route became no call, and its refusal says so.
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
     * The surface a REST caller came through, for the names an answer gives
     * the calls: the assistant surface where {@code Kumbuka-Surface} says so,
     * this one otherwise. {@link SurfaceHeaderFilter} refused every other
     * value before the route was looked up.
     */
    static Surface surfaceOf(HttpHeaders http) {
        List<String> named = http.getRequestHeader(SurfaceHeaderFilter.SURFACE);
        return named == null || named.isEmpty() ? Surface.REST : Surface.MCP;
    }

    /**
     * The request a mapper answers, read once for the three mappers that word
     * a refusal against the call the request became. Each part is absent where
     * the mapper is handed an exception outside a request.
     */
    abstract static class OnTheRequest {

        @Context @Nullable Request request;
        @Context @Nullable UriInfo uri;
        @Context @Nullable HttpHeaders http;

        /** The call the request became, as its surface names it; empty where it became none. */
        Optional<VerbName> call() {
            if (request == null || uri == null || http == null) {
                return Optional.empty();
            }
            Surface surface = surfaceOf(http);
            return CustomMethod.of(request.getMethod(), uri.getPath())
                .map(route -> VerbName.of(route.verb(), surface));
        }

        /** The depth of the address the request names; empty where it names none. */
        Optional<CustomMethod.Depth> depth() {
            return uri == null ? Optional.empty() : CustomMethod.depthOf(uri.getPath());
        }

        /** The surface the caller came through; this one where no request is known. */
        Surface surface() {
            return http == null ? Surface.REST : surfaceOf(http);
        }
    }

    /**
     * A refusal of the domain or of the platform's directory. Under a call it
     * is worded against that call; outside one only the not-found class can be
     * said, and anything else is ours.
     */
    @Provider
    public static class Domain extends OnTheRequest implements ExceptionMapper<DispatchException> {

        @Override
        public Response toResponse(DispatchException e) {
            // DEBUG by its code, and WARN where the scope refused (C13.3): the
            // directory below logs nothing, so its line is written here.
            PlatformLog.domainRefused(e.reason());
            Optional<VerbName> call = call();
            if (call.isPresent()) {
                return answer(CallRefusals.of(e, call.get()), null);
            }
            return answer(RefusalCode.of(e.reason()) == RefusalCode.NOT_FOUND
                ? Refusal.notFound()
                : UnexpectedFailures.refuse(null, e), null);
        }
    }

    /**
     * A call the caller's permission does not reach, refused by the check at
     * {@code DeclaredCalls} before the call was made: {@code
     * ROLE_INSUFFICIENT}, a refusal of the service in the platform's table and
     * not the security layer's bare 403. The scope the call names was resolved
     * before, so a scope the caller may not see has answered {@code NOT_FOUND}
     * already (C1.3). A 403 outside a call is the security layer's and goes
     * back as it is.
     */
    @Provider
    public static class NotPermitted extends OnTheRequest
        implements ExceptionMapper<ForbiddenException> {

        @Override
        public Response toResponse(ForbiddenException e) {
            Optional<VerbName> call = call();
            if (call.isEmpty()) {
                return Response.status(Response.Status.FORBIDDEN).build();
            }
            PlatformLog.refused(PlatformLog.Reason.of(RefusalCode.ROLE_INSUFFICIENT));
            return answer(Refusal.ofPermission(call.get()), null);
        }
    }

    /**
     * Everything that is no refusal (C8.3). A {@link WebApplicationException}
     * is the framework's own answer. A route that does not exist is {@code
     * NOT_FOUND}, byte for byte the one of an absent object; a method the route
     * does not take is {@code VERB_NOT_CARRIED} with the route's {@code Allow}
     * and the calls carried at the address (C3.3). Every other status of the
     * framework, the security layer's 401 and 403 among them, goes back
     * unchanged (the authentication exception of DEC-0042). Anything else is a
     * defect on our side, logged once with a reference and answered with it.
     */
    @Provider
    public static class Unexpected extends OnTheRequest implements ExceptionMapper<Throwable> {

        @Override
        public Response toResponse(Throwable failure) {
            if (failure instanceof WebApplicationException framework) {
                Response given = framework.getResponse();
                return switch (given.getStatus()) {
                    case 404 -> answer(Refusal.notFound(), null);
                    case 405 -> methodNotCarried(given);
                    default -> given;
                };
            }
            return answer(UnexpectedFailures.refuse(call().orElse(null), failure), null);
        }

        /**
         * The framework's 405 in the envelope: the calls carried at the address
         * where it is one, and the route's methods in {@code Allow}. The
         * framework's own {@code Allow} is taken where it sends one; where it
         * does not, the methods of the routes at that depth.
         */
        private Response methodNotCarried(Response given) {
            Optional<CustomMethod.Depth> depth = depth();
            Surface surface = surface();
            String allow = given.getHeaderString(HttpHeaders.ALLOW);
            if (allow == null) {
                allow = depth.flatMap(CustomMethod::allow).orElse(null);
            }
            return answer(Refusal.methodNotCarried(depth
                .map(at -> CustomMethod.accepts(at, surface))
                .orElse(List.of())), allow);
        }
    }
}
