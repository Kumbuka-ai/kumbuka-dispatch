/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.rest;

import ai.kumbuka.dispatch.adapter.payload.DomainRefusals;
import ai.kumbuka.dispatch.adapter.payload.Payloads;
import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.surface.AddressParser;
import ai.kumbuka.dispatch.surface.Refusal;
import ai.kumbuka.dispatch.surface.RefusalCode;
import ai.kumbuka.dispatch.surface.SurfaceException;
import ai.kumbuka.dispatch.surface.UnexpectedFailures;
import ai.kumbuka.dispatch.surface.VerbName;
import ai.kumbuka.dispatch.surface.VerbSurface;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Every refusal of the REST surface, in its envelope and with its status: the
 * outermost edge, and with {@code McpAdapter} the only place a refusal is
 * caught (C8.2).
 *
 * <p>One mapper per type, nested here: the surface's refusals, which arrive
 * worded; the domain's, which are worded here against the call the request
 * became; and everything else, which is ours and answered as {@code
 * UNEXPECTED_FAILURE} with a reference (C8.3). The status of a refusal is its
 * code's, from the catalogue.
 */
@Provider
public class RefusalMapper implements ExceptionMapper<SurfaceException> {

    private static final Logger LOG = Logger.getLogger(RefusalMapper.class);

    /** Where an unexpected failure's log line says the request was, when it became no call. */
    private static final String UNREAD = "<unread>";

    @Override
    public Response toResponse(SurfaceException refused) {
        LOG.debugf("refusal: %s", refused.refusal().code());
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
     * A refusal of the domain. Under a call it is worded against that call,
     * with the task's situation read again; outside one only the not-found
     * class can be said, and anything else is ours.
     */
    @Provider
    public static class Domain implements ExceptionMapper<DispatchException> {

        @Inject CallContext context;
        @Inject VerbSurface verbs;
        @Inject Clock clock;

        @Override
        public Response toResponse(DispatchException e) {
            LOG.debugf("refusal: %s", e.reason());
            Optional<CallContext.Made> made = context.made();
            if (made.isEmpty()) {
                return answer(RefusalCode.of(e.reason()) == RefusalCode.NOT_FOUND
                    ? Refusal.notFound()
                    : UnexpectedFailures.refuse(null, UNREAD, e), null);
            }
            CallContext.Made call = made.get();
            return answer(DomainRefusals.of(e, call.surface(), context.actor().orElse(null),
                call.verb(), call.given(), this::readQuietly, clock), null);
        }

        /**
         * The task at a complete address, read in a transaction of its own
         * after the refused one rolled back; empty where the caller may not
         * read it, which is itself a refusal and the reason this read lives
         * at the edge.
         */
        private Optional<VerbSurface.Result> readQuietly(String complete) {
            Optional<Actor> actor = context.actor();
            if (actor.isEmpty()) {
                return Optional.empty();
            }
            try {
                AddressParser.Parts parts = AddressParser.uri(complete);
                return Optional.of(verbs.read(actor.get(), new VerbSurface.Item(
                    parts.scope(), AddressParser.item(parts.selector(), parts.id()))));
            } catch (DispatchException | SurfaceException unreadable) {
                return Optional.empty();
            }
        }
    }

    /**
     * Everything that is no refusal (C8.3). A {@link WebApplicationException}
     * is the framework's own answer. A route that does not exist is {@code
     * NOT_FOUND}, byte for byte the one of an absent task; a method the route
     * does not take is {@code VERB_NOT_CARRIED} with the route's {@code Allow}
     * and the calls carried at the address. Every other status of the
     * framework, the security layer's 401 and 403 among them, goes back
     * unchanged (the authentication exception of DEC-0042). Anything else is a
     * defect on our side, logged once with a reference and answered with it.
     */
    @Provider
    public static class Unexpected implements ExceptionMapper<Throwable> {

        @Inject CallContext context;
        @Context UriInfo uri;

        @Override
        public Response toResponse(Throwable failure) {
            if (failure instanceof WebApplicationException framework) {
                Response given = framework.getResponse();
                return switch (given.getStatus()) {
                    case 404 -> answer(Refusal.notFound(), null);
                    case 405 -> methodNotCarried(given, depthOf(uri.getPath()));
                    default -> given;
                };
            }
            Optional<CallContext.Made> made = context.made();
            @Nullable VerbName call = made.map(m -> VerbName.of(m.verb(), m.surface()))
                .orElse(null);
            return answer(UnexpectedFailures.refuse(call,
                made.map(m -> DomainRefusals.whereIn(m.given())).orElse(UNREAD), failure), null);
        }

        /**
         * The framework's 405 in the envelope: the calls carried at the address
         * where it is one, and the route's methods in {@code Allow}. The
         * framework's own {@code Allow} is taken where it sends one; where it
         * does not, the route's methods are this surface's, as every other 405
         * of it names them.
         */
        private static Response methodNotCarried(Response given,
                                                 Optional<CustomMethod.Depth> depth) {
            String allow = given.getHeaderString(HttpHeaders.ALLOW);
            if (allow == null) {
                allow = depth.map(CustomMethod::allow).orElse(null);
            }
            return answer(Refusal.methodNotCarried(
                depth.map(CustomMethod::accepts).orElse(List.of())), allow);
        }

        /** The depth of a REST path: a collection, a task, or neither. */
        private static Optional<CustomMethod.Depth> depthOf(String path) {
            String[] segments = (path.startsWith("/") ? path.substring(1) : path).split("/", -1);
            if (segments.length < 3 || !"api".equals(segments[0])) {
                return Optional.empty();
            }
            return switch (segments.length) {
                case 3 -> Optional.of(CustomMethod.Depth.COLLECTION);
                case 4 -> Optional.of(CustomMethod.Depth.ITEM);
                default -> Optional.empty();
            };
        }
    }
}
