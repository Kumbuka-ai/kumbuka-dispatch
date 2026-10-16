/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.rest;

import ai.kumbuka.dispatch.adapter.payload.CallBinding;
import ai.kumbuka.dispatch.adapter.payload.CallPayload;
import ai.kumbuka.dispatch.adapter.payload.Payloads;
import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.surface.AddressParser;
import ai.kumbuka.dispatch.surface.Argument;
import ai.kumbuka.dispatch.surface.ArgumentPath;
import ai.kumbuka.dispatch.surface.CallerActor;
import ai.kumbuka.dispatch.surface.Form;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.Refusal;
import ai.kumbuka.dispatch.surface.Surface;
import ai.kumbuka.dispatch.surface.SurfaceException;
import ai.kumbuka.dispatch.surface.VerbName;
import ai.kumbuka.dispatch.surface.VerbSurface;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The REST surface: the twenty-five calls of the declaration on the routes of
 * {@link CustomMethod}, and nothing else.
 *
 * <p>A request becomes a call: its name from the route, its arguments from
 * the path (the scope and the bracket kind, or the complete address of one
 * task), the {@code If-Match} header (the conflict token), the {@code
 * Idempotency-Key} header (the idempotency key), the query and the body — the
 * same map the assistant surface hands over, closed against the same
 * declaration by the same {@link CallPayload} and made by the same {@link
 * CallBinding}: one call of the verb surface (C1.1). So a call refuses an
 * invented argument by name on both surfaces alike, whichever part of the
 * request it came in.
 *
 * <p>The idempotency key travels in its header on this surface and nowhere
 * else (C10.4): a request that carries {@code idempotency_key} in its query
 * or its body is refused as {@code ARGUMENT_UNKNOWN}, with what the call
 * accepts there. The header on a call that takes no key is a header this call
 * does not read, as {@code If-Match} on a call without a token is.
 *
 * <p>A call names calls in the vocabulary of the surface its caller came
 * through. Without the {@code Kumbuka-Surface} header that is this one. With
 * {@code Kumbuka-Surface: assistant} it is the assistant surface: a router
 * that publishes these calls as tools reaches this service over REST and says
 * so, and the call is made under its tool name, so that {@code next}, {@code
 * waiting_for}, every message and the {@code data} of every refusal name the
 * tools that caller holds. Any other value, or the header twice, is refused
 * as {@code HEADER_INVALID} by {@link SurfaceHeaderFilter} before the route
 * is looked up, and the call is not made. The header is not an argument of the call — it is in no declaration
 * and never reaches the call's arguments — and it grants nothing.
 * A {@code 405} names routes, which have one spelling, so it stays in the REST
 * form whatever the header says.
 *
 * <p>This class holds the HTTP expression of a call and nothing else: the
 * route, the status, {@code ETag} with the conflict token, {@code 201} with
 * {@code Location} for a created task, {@code 405} with {@code Allow} for a
 * call that is not at the address it was sent to. It catches nothing: a
 * refusal travels to {@link RefusalMapper}, which reads the call from {@link
 * CallContext} (C8.2).
 */
@Path("/api/{scope}")
@Authenticated
@TenantBound
@Produces(MediaType.APPLICATION_JSON)
public class TaskResource {

    private static final String IF_MATCH = "If-Match";

    /** The header that carries the idempotency key on this surface (C10.4). */
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /** The argument the key is on the assistant surface, and in the declaration. */
    private static final String KEY_ARGUMENT = "idempotency_key";

    /**
     * Names the surface the caller came through, where that is not this one.
     * A router that publishes these calls as assistant tools and reaches this
     * service over REST says so here, so that the calls in an answer carry the
     * names the caller knows them by. It decides nothing else: who calls, and
     * what that caller may do, come from the verified identity as always.
     */
    static final String SURFACE = "Kumbuka-Surface";

    /** The one value {@link #SURFACE} takes: the assistant surface. */
    static final String ASSISTANT = "assistant";

    @Inject VerbSurface verbs;
    @Inject CallerActor caller;
    @Inject CallContext context;
    @Inject ObjectMapper json;
    @Inject Clock clock;

    // ======================================================================
    // The six bindings: one per method and depth
    // ======================================================================

    @POST
    @Path("{selector}")
    public Response collectionPost(@PathParam("scope") String scope,
                                   @PathParam("selector") String segment,
                                   @Context HttpHeaders http,
                                   @Context UriInfo uri, @Nullable String body) {
        return route("POST", new At(CustomMethod.Depth.COLLECTION, scope, segment, null),
            new Headers(null, http), uri, body);
    }

    @GET
    @Path("{selector}")
    public Response collectionGet(@PathParam("scope") String scope,
                                  @PathParam("selector") String segment,
                                  @Context HttpHeaders http,
                                  @Context UriInfo uri) {
        return route("GET", new At(CustomMethod.Depth.COLLECTION, scope, segment, null),
            new Headers(null, http), uri, null);
    }

    @GET
    @Path("{selector}/{id}")
    public Response itemGet(@PathParam("scope") String scope,
                            @PathParam("selector") String selector,
                            @PathParam("id") String segment,
                            @HeaderParam(IF_MATCH) String ifMatch,
                            @Context HttpHeaders http,
                            @Context UriInfo uri) {
        return route("GET", new At(CustomMethod.Depth.ITEM, scope, selector, segment),
            new Headers(ifMatch, http), uri, null);
    }

    @PATCH
    @Path("{selector}/{id}")
    public Response itemPatch(@PathParam("scope") String scope,
                              @PathParam("selector") String selector,
                              @PathParam("id") String segment,
                              @HeaderParam(IF_MATCH) String ifMatch,
                              @Context HttpHeaders http,
                              @Context UriInfo uri, @Nullable String body) {
        return route("PATCH", new At(CustomMethod.Depth.ITEM, scope, selector, segment),
            new Headers(ifMatch, http), uri, body);
    }

    @DELETE
    @Path("{selector}/{id}")
    public Response itemDelete(@PathParam("scope") String scope,
                               @PathParam("selector") String selector,
                               @PathParam("id") String segment,
                               @HeaderParam(IF_MATCH) String ifMatch,
                               @Context HttpHeaders http,
                               @Context UriInfo uri) {
        return route("DELETE", new At(CustomMethod.Depth.ITEM, scope, selector, segment),
            new Headers(ifMatch, http), uri, null);
    }

    @POST
    @Path("{selector}/{id}")
    public Response itemPost(@PathParam("scope") String scope,
                             @PathParam("selector") String selector,
                             @PathParam("id") String segment,
                             @HeaderParam(IF_MATCH) String ifMatch,
                             @Context HttpHeaders http,
                             @Context UriInfo uri, @Nullable String body) {
        return route("POST", new At(CustomMethod.Depth.ITEM, scope, selector, segment),
            new Headers(ifMatch, http), uri, body);
    }

    // ======================================================================
    // A request, as a call
    // ======================================================================

    /**
     * Where a request is addressed: the depth and the path segments as they arrived.
     *
     * @param idSegment the segment of a task; absent on a collection
     */
    private record At(CustomMethod.Depth depth, String scope, String selectorSegment,
                      @Nullable String idSegment) {
    }

    /**
     * The headers a call reads, as they arrived. The surface is kept as every
     * value it arrived with, an empty list when there is none, because an
     * empty value or a second one is a value it cannot take, not an absence.
     *
     * @param ifMatch the conflict token's header; absent where the request
     *                carried none or its route reads none
     */
    private record Headers(@Nullable String ifMatch, List<String> surface, List<String> key) {

        Headers(@Nullable String ifMatch, HttpHeaders http) {
            this(ifMatch, orNone(http.getRequestHeader(SURFACE)),
                orNone(http.getRequestHeader(IDEMPOTENCY_KEY)));
        }

        private static List<String> orNone(@Nullable List<String> values) {
            return values == null ? List.of() : values;
        }
    }

    private Response route(String method, At where, Headers headers, UriInfo uri,
                           @Nullable String body) {
        CustomMethod.Depth depth = where.depth();
        String scope = where.scope();
        String selectorSegment = where.selectorSegment();
        String idSegment = where.idSegment();
        String[] at = CustomMethod.split(depth == CustomMethod.Depth.COLLECTION
            ? selectorSegment
            : Objects.requireNonNull(idSegment));
        CustomMethod route = CustomMethod.find(method, depth, at[1])
            .orElseThrow(() -> notHere(depth, at[1]));
        // SurfaceHeaderFilter refused every value but the one, before the route.
        Surface surface = headers.surface().isEmpty() ? Surface.REST : Surface.MCP;
        VerbName refusing = VerbName.of(route.verb(), surface);

        Map<String, Object> arguments = new LinkedHashMap<>();
        if (depth == CustomMethod.Depth.COLLECTION) {
            arguments.put("scope", scope);
            arguments.put("selector", at[0]);
        } else {
            arguments.put("address", AddressParser.SCHEME + "://" + scope + "/"
                + selectorSegment + "/" + at[0]);
        }
        String ifMatch = headers.ifMatch();
        if (ifMatch != null && !ifMatch.isBlank()) {
            arguments.put("conflict_token", unquote(ifMatch.trim()));
        }
        merge(refusing, route.verb(), arguments,
            withoutTheKey(refusing, route.verb(), query(uri)));
        mergeBody(refusing, route.verb(), arguments, body);
        key(refusing, route.verb(), headers.key())
            .ifPresent(key -> arguments.put(KEY_ARGUMENT, key));

        context.call(surface, route.verb(), arguments);
        CallPayload in = new CallPayload(route.verb(), surface, arguments);
        Actor actor = caller.current();
        context.caller(actor);
        return respond(route, CallBinding.make(verbs, surface, actor, in, clock));
    }

    /**
     * A request at an address no route serves with that method and verb:
     * {@code VERB_NOT_CARRIED} 405 with {@code Allow} and the verbs this
     * address carries under {@code data.accepts} (C3.3). Where the verb is a
     * call of this surface at another depth, the message says where it
     * applies. Neither names a value the request carried: no path segment and
     * no verb that the declaration does not hold (DEC-0043 R18, R19).
     */
    private static SurfaceException notHere(CustomMethod.Depth depth, @Nullable String verb) {
        List<VerbName> accepts = CustomMethod.accepts(depth);
        Refusal refused = verb == null
            ? Refusal.callUnknown(accepts)
            : ProcessVerb.byName(Surface.REST, verb)
                .map(declared -> Refusal.callNotAtThisAddress(
                    VerbName.of(declared, Surface.REST),
                    CustomMethod.of(verb).map(CustomMethod::refusalRoute).orElseThrow(),
                    accepts))
                .orElseGet(() -> Refusal.callUnknown(accepts));
        return new SurfaceException(refused, CustomMethod.allow(depth));
    }

    private static Map<String, Object> query(UriInfo uri) {
        Map<String, Object> flat = new LinkedHashMap<>();
        uri.getQueryParameters().forEach((name, values) ->
            flat.put(name, String.join(",", values)));
        return flat;
    }

    /**
     * Adds what one part of the request carries; refuses an argument given
     * twice. What the path and the headers carry are declared arguments of the
     * call, so a name that arrives twice is always one the declaration holds.
     */
    private static void merge(VerbName call, ProcessVerb verb, Map<String, Object> into,
                              Map<String, Object> from) {
        for (Map.Entry<String, Object> given : from.entrySet()) {
            if (given.getValue() == null) {
                // JSON null is an argument not sent (DEC-0043 R17).
                continue;
            }
            if (into.containsKey(given.getKey())) {
                throw new SurfaceException(verb.argument(given.getKey(), Argument.Placement.TOP)
                    .map(argument -> Refusal.argumentInvalid(call, ArgumentPath.of(argument),
                        Form.ONCE))
                    .orElseGet(() -> Refusal.valueGivenTwice(call)));
            }
            into.put(given.getKey(), given.getValue());
        }
    }

    /** Adds what the body carries; refuses a body that is no JSON object (C8.7). */
    private void mergeBody(VerbName call, ProcessVerb verb, Map<String, Object> into,
                           @Nullable String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        Object parsed;
        try {
            parsed = json.readValue(body, Object.class);
        } catch (JsonProcessingException e) {
            parsed = null;
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new SurfaceException(Refusal.payloadMalformed(call));
        }
        Map<String, Object> carried = new LinkedHashMap<>();
        map.forEach((name, value) -> carried.put(String.valueOf(name), value));
        merge(call, verb, into, withoutTheKey(call, verb, carried));
    }

    /**
     * Refuses the idempotency key where this surface does not carry it: in the
     * query or the body. The refusal lists what the call accepts there, which
     * the key is not, and names nothing the request carried (DEC-0043 R19). A
     * key sent as JSON {@code null} is a key not sent (DEC-0043 R17).
     */
    private static Map<String, Object> withoutTheKey(VerbName call, ProcessVerb verb,
                                                     Map<String, Object> part) {
        if (part.get(KEY_ARGUMENT) != null) {
            List<ArgumentPath> accepts = new ArrayList<>(ArgumentPath.of(verb.topArguments()
                .stream().filter(a -> !KEY_ARGUMENT.equals(a.name())).toList()));
            if (verb.hasFields()) {
                accepts.add(ArgumentPath.FIELDS);
            }
            throw new SurfaceException(Refusal.argumentUnknown(call, false, accepts));
        }
        return part;
    }

    /**
     * The idempotency key the header carries, for a call that takes one. Sent
     * twice it is a value the call cannot take; a blank one is no key.
     */
    private static Optional<String> key(VerbName call, ProcessVerb verb,
                                                  List<String> header) {
        Optional<Argument> declared = verb.argument(KEY_ARGUMENT, Argument.Placement.TOP);
        if (declared.isEmpty() || header.isEmpty()) {
            return Optional.empty();
        }
        Argument argument = declared.get();
        if (header.size() > 1) {
            throw new SurfaceException(Refusal.argumentInvalid(call, ArgumentPath.of(argument),
                Form.ONCE));
        }
        String key = header.get(0).trim();
        return key.isEmpty() ? Optional.empty() : Optional.of(key);
    }

    // ======================================================================
    // An outcome, in HTTP
    // ======================================================================

    private static Response respond(CustomMethod route, CallBinding.Outcome outcome) {
        Object body = Payloads.of(outcome);
        if (outcome instanceof CallBinding.Answered answered) {
            Response.ResponseBuilder response = route == CustomMethod.CREATE
                ? Response.created(location(answered)).entity(body)
                : Response.ok(body);
            if (answered.view().conflictToken() != null) {
                response.tag(new EntityTag(answered.view().conflictToken()));
            }
            return response.build();
        }
        return Response.ok(body).build();
    }

    /** Built from the address, never echoed from the request. */
    private static java.net.URI location(CallBinding.Answered created) {
        AddressParser.Parts at = AddressParser.uri(created.address());
        return UriBuilder.fromResource(TaskResource.class)
            .path("{selector}/{id}")
            .build(at.scope(), at.selector(), at.id());
    }

    /** Tolerates the quoted form an HTTP entity tag arrives in. */
    private static String unquote(String raw) {
        String value = raw.startsWith("W/") ? raw.substring(2) : raw;
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
            ? value.substring(1, value.length() - 1)
            : value;
    }
}
