/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.rest;

import ai.kumbuka.dispatch.adapter.payload.CallForm;
import ai.kumbuka.dispatch.adapter.payload.DeclaredCalls;
import ai.kumbuka.dispatch.adapter.payload.DefinitionPayload;
import ai.kumbuka.dispatch.adapter.payload.InstancePayload;
import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.surface.AddressParser;
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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The REST surface of the calls: the routes of {@link CustomMethod}, and
 * nothing else.
 *
 * <p>A request becomes a call: its name from the route, its arguments from
 * the path (the scope, the selector and the instance), the query, the body
 * and the {@code If-Match} header, handed as one map to the same form check
 * the assistant surface uses ({@link CallForm}), and made through {@link
 * DeclaredCalls}, which checks the one permission the call names. Before it is
 * made, the scope it names is resolved through {@link VerbSurface}, so that a
 * caller without the permission meets {@code NOT_FOUND} for a scope it may not
 * see (C1.3), and a call that writes meets a locked scope before its
 * permission. It catches nothing: a refusal travels to {@link RefusalMapper},
 * which reads the call from the request (C8.2).
 *
 * <p>A call names calls in the vocabulary of the surface its caller came
 * through: without the {@code Kumbuka-Surface} header that is this one; with
 * {@code Kumbuka-Surface: assistant} it is the assistant surface (C14.4). The
 * header is no argument of the call and grants nothing.
 */
@Path("/api/{scope}")
@Authenticated
@TenantBound
@Produces(MediaType.APPLICATION_JSON)
public class ProcessResource {

    /** A whole number as a query carries it, up to the digits a version can have. */
    private static final Pattern WHOLE = Pattern.compile("[0-9]{1,10}");

    /** The argument a closing takes its fence in; on this surface it arrives as {@code If-Match}. */
    private static final String CONFLICT_TOKEN = "conflict_token";

    /** A body read as the arguments of a call: a JSON object. */
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };

    @Inject DeclaredCalls calls;
    @Inject VerbSurface verbs;
    @Inject CallerActor caller;
    @Inject ObjectMapper json;

    /** GET on a collection: {@code read_definition}, the one call carried there. */
    @GET
    @Path("{selector}")
    public DefinitionPayload collectionGet(@PathParam("scope") String scope,
                                           @PathParam("selector") String segment,
                                           @Context HttpHeaders http, @Context UriInfo uri) {
        Surface surface = RefusalMapper.surfaceOf(http);
        String[] at = CustomMethod.split(segment);
        CustomMethod route = CustomMethod.find("GET", CustomMethod.Depth.COLLECTION, at[1])
            .orElseThrow(() -> notHere(surface, CustomMethod.Depth.COLLECTION));
        ProcessVerb verb = route.verb();
        VerbName call = VerbName.of(verb, surface);

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("scope", scope);
        arguments.put("selector", at[0]);
        uri.getQueryParameters().forEach((name, values) -> {
            if (arguments.containsKey(name) || values.size() > 1) {
                throw new SurfaceException(verb.argument(name)
                    .map(argument -> Refusal.argumentInvalid(call, ArgumentPath.of(argument),
                        Form.ONCE))
                    .orElseGet(() -> Refusal.valueGivenTwice(call)));
            }
            arguments.put(name, number(values.get(0)));
        });

        CallForm.ReadDefinition read = CallForm.readDefinition(surface, arguments);
        Actor actor = caller.current();
        verbs.resolveScope(actor, read.scope());
        return calls.readDefinition(actor, read);
    }

    /**
     * POST on a collection with a colon verb: {@code start}, the one call
     * carried there. Answers 201 with the instance, its address under {@code
     * Location} and its conflict token as {@code ETag}, as the platform's
     * vocabulary answers a thing created.
     */
    @POST
    @Path("{selector}")
    public Response collectionPost(@PathParam("scope") String scope,
                                   @PathParam("selector") String segment,
                                   @Nullable String body, @Context HttpHeaders http) {
        Surface surface = RefusalMapper.surfaceOf(http);
        String[] at = CustomMethod.split(segment);
        CustomMethod route = CustomMethod.find(HttpMethod.POST, CustomMethod.Depth.COLLECTION, at[1])
            .orElseThrow(() -> notHere(surface, CustomMethod.Depth.COLLECTION));
        ProcessVerb verb = route.verb();
        VerbName call = VerbName.of(verb, surface);

        Map<String, Object> arguments = body(call, body);
        once(verb, call, arguments, "scope", scope);
        once(verb, call, arguments, "selector", at[0]);

        CallForm.Start start = CallForm.start(surface, arguments);
        Actor actor = caller.current();
        verbs.resolveScopeToWrite(actor, start.scope());
        InstancePayload started = calls.start(actor, start, surface);
        return Response.created(URI.create("/api/" + start.scope() + "/" + start.selector()
                + "/" + started.fields().get("number")))
            .tag(started.conflictToken())
            .entity(started)
            .build();
    }

    /**
     * POST on an instance with a colon verb: {@code conclude}, {@code
     * withdraw}, {@code reference_attach} and {@code reference_detach}. The
     * instance is the one the path names; a closing takes its conflict token
     * from {@code If-Match} (C10.3). Answers the instance, with its conflict
     * token as {@code ETag}.
     */
    @POST
    @Path("{selector}/{id}")
    public Response itemPost(@PathParam("scope") String scope,
                             @PathParam("selector") String selector,
                             @PathParam("id") String segment,
                             @Nullable String body, @Context HttpHeaders http) {
        Surface surface = RefusalMapper.surfaceOf(http);
        String[] at = CustomMethod.split(segment);
        CustomMethod route = CustomMethod.find(HttpMethod.POST, CustomMethod.Depth.ITEM, at[1])
            .orElseThrow(() -> notHere(surface, CustomMethod.Depth.ITEM));
        ProcessVerb verb = route.verb();
        VerbName call = VerbName.of(verb, surface);

        Map<String, Object> arguments = body(call, body);
        once(verb, call, arguments, "address",
            AddressParser.SCHEME + "://" + scope + "/" + selector + "/" + at[0]);
        String fence = http.getHeaderString(HttpHeaders.IF_MATCH);
        if (fence != null && verb.argument(CONFLICT_TOKEN).isPresent()) {
            once(verb, call, arguments, CONFLICT_TOKEN, unquoted(fence));
        }

        Actor actor = caller.current();
        InstancePayload answered = switch (verb) {
            case CONCLUDE, WITHDRAW -> {
                CallForm.Close close = CallForm.close(verb, surface, arguments);
                verbs.resolveScopeToWrite(actor, close.address().scope());
                yield verb == ProcessVerb.CONCLUDE ? calls.conclude(actor, close, surface)
                    : calls.withdraw(actor, close, surface);
            }
            case REFERENCE_ATTACH, REFERENCE_DETACH -> {
                CallForm.Refer refer = CallForm.refer(verb, surface, arguments);
                verbs.resolveScopeToWrite(actor, refer.address().scope());
                yield verb == ProcessVerb.REFERENCE_ATTACH
                    ? calls.referenceAttach(actor, refer, surface)
                    : calls.referenceDetach(actor, refer, surface);
            }
            default -> throw notHere(surface, CustomMethod.Depth.ITEM);
        };
        return Response.ok(answered).tag(answered.conflictToken()).build();
    }

    /**
     * A colon verb at an address that carries none of that name: {@code
     * VERB_NOT_CARRIED} 405 with {@code Allow} and the calls this address
     * carries under {@code data.accepts}, and nothing of the verb that arrived
     * (C3.3, DEC-0043 R19).
     */
    private static SurfaceException notHere(Surface surface, CustomMethod.Depth depth) {
        List<VerbName> accepts = CustomMethod.accepts(depth, surface);
        return new SurfaceException(Refusal.callUnknown(accepts),
            CustomMethod.allow(depth).orElse(null));
    }

    /**
     * The body of a call as its arguments: a JSON object, or none at all,
     * which is an empty one. Anything else is {@code PAYLOAD_MALFORMED} with
     * the call alone (C8.7).
     */
    private Map<String, Object> body(VerbName call, @Nullable String body) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return arguments;
        }
        JsonNode parsed;
        try {
            parsed = json.readTree(body);
        } catch (JsonProcessingException notJson) {
            throw new SurfaceException(Refusal.payloadMalformed(call));
        }
        if (parsed == null || !parsed.isObject()) {
            throw new SurfaceException(Refusal.payloadMalformed(call));
        }
        arguments.putAll(json.convertValue(parsed, OBJECT));
        return arguments;
    }

    /**
     * Puts an argument the path or a header carries beside the body's; the
     * same argument in the body as well is refused, since a call takes each
     * argument once.
     */
    private static void once(ProcessVerb verb, VerbName call, Map<String, Object> arguments,
                             String name, String value) {
        if (arguments.get(name) != null) {
            throw new SurfaceException(Refusal.argumentInvalid(call,
                ArgumentPath.of(verb.declared(name)), Form.ONCE));
        }
        arguments.put(name, value);
    }

    /** An entity tag as a header carries it, without its quotes and its weak mark. */
    private static String unquoted(String tag) {
        String strong = tag.startsWith("W/") ? tag.substring(2) : tag;
        return strong.length() >= 2 && strong.startsWith("\"") && strong.endsWith("\"")
            ? strong.substring(1, strong.length() - 1) : strong;
    }

    /**
     * A query value as the call reads it: a whole number as a number, since a
     * query carries every value as text; any other value as it arrived, for
     * the form check to take or refuse.
     */
    private static Object number(String value) {
        return WHOLE.matcher(value).matches() ? (Object) Long.valueOf(value) : value;
    }
}
