/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.rest;

import ai.kumbuka.dispatch.adapter.payload.AddressPayload;
import ai.kumbuka.dispatch.adapter.payload.CallForm;
import ai.kumbuka.dispatch.adapter.payload.DeclaredCalls;
import ai.kumbuka.dispatch.adapter.payload.DefinitionPayload;
import ai.kumbuka.dispatch.adapter.payload.InstancePayload;
import ai.kumbuka.dispatch.adapter.payload.TaskPayload;
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
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.PATCH;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** The argument of an idempotency key; on this surface it arrives as its header. */
    private static final String IDEMPOTENCY_KEY = "idempotency_key";

    /** The header that carries the idempotency key on this surface (C10.3, C10.4). */
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** The method of a field write. */
    private static final String METHOD_PATCH = "PATCH";

    /** The argument and path parameter of the scope. */
    private static final String SCOPE = "scope";

    /** The argument and path parameter of the selector. */
    private static final String SELECTOR = "selector";

    /** The path parameter of an object in a collection. */
    private static final String ID = "id";

    /** The argument of an object's address; on this surface the path carries it. */
    private static final String ADDRESS = "address";

    /** The arguments the path or a header carries on this surface, never the body. */
    private static final java.util.Set<String> PATH_OR_HEADER = java.util.Set.of(SCOPE,
        SELECTOR, ADDRESS, CONFLICT_TOKEN, IDEMPOTENCY_KEY);

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
    public DefinitionPayload collectionGet(@PathParam(SCOPE) String scope,
                                           @PathParam(SELECTOR) String segment,
                                           @Context HttpHeaders http, @Context UriInfo uri) {
        Surface surface = RefusalMapper.surfaceOf(http);
        String[] at = CustomMethod.split(segment);
        CustomMethod route = CustomMethod.find("GET", CustomMethod.Depth.COLLECTION, at[1])
            .orElseThrow(() -> notHere(surface, CustomMethod.Depth.COLLECTION));
        ProcessVerb verb = route.verb();
        VerbName call = VerbName.of(verb, surface);

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put(SCOPE, scope);
        arguments.put(SELECTOR, at[0]);
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
     * POST on a collection: {@code create} as the plain POST, and {@code
     * start} with its colon verb. Each answers 201 with what it created, its
     * address under {@code Location} and its conflict token as {@code ETag},
     * as the platform's vocabulary answers a thing created. {@code create}
     * takes its idempotency key from the header {@code Idempotency-Key} and
     * nowhere else (C10.4).
     */
    @POST
    @Path("{selector}")
    public Response collectionPost(@PathParam(SCOPE) String scope,
                                   @PathParam(SELECTOR) String segment,
                                   @Nullable String body, @Context HttpHeaders http,
                                   @Context UriInfo uri) {
        Surface surface = RefusalMapper.surfaceOf(http);
        String[] at = CustomMethod.split(segment);
        CustomMethod route = CustomMethod.find(HttpMethod.POST, CustomMethod.Depth.COLLECTION, at[1])
            .orElseThrow(() -> notHere(surface, CustomMethod.Depth.COLLECTION));
        ProcessVerb verb = route.verb();
        VerbName call = VerbName.of(verb, surface);

        Map<String, Object> arguments = body(call, body);
        Actor actor;
        if (verb == ProcessVerb.CREATE) {
            closedOutsideTheBody(verb, call, arguments, uri);
            key(call, verb, http.getRequestHeader(IDEMPOTENCY_KEY_HEADER))
                .ifPresent(key -> arguments.put(IDEMPOTENCY_KEY, key));
            once(verb, call, arguments, SCOPE, scope);
            once(verb, call, arguments, SELECTOR, at[0]);
            CallForm.Create create = CallForm.create(surface, arguments);
            actor = caller.current();
            verbs.resolveScopeToWrite(actor, create.scope());
            TaskPayload created = calls.create(actor, create, surface);
            return Response.created(URI.create(path(created.address())))
                .tag(created.conflictToken())
                .entity(created)
                .build();
        }
        once(verb, call, arguments, SCOPE, scope);
        once(verb, call, arguments, SELECTOR, at[0]);

        CallForm.Start start = CallForm.start(surface, arguments);
        actor = caller.current();
        verbs.resolveScopeToWrite(actor, start.scope());
        InstancePayload started = calls.start(actor, start, surface);
        return Response.created(URI.create("/api/" + start.scope() + "/" + start.selector()
                + "/" + started.fields().get("number")))
            .tag(started.conflictToken())
            .entity(started)
            .build();
    }

    /**
     * PATCH on a task: {@code update}, the one call carried by this method.
     * The task is the one the path names; its conflict token arrives as {@code
     * If-Match} (C10.3). Answers the task, with its conflict token as {@code
     * ETag}.
     */
    @PATCH
    @Path("{selector}/{id}")
    public Response itemPatch(@PathParam(SCOPE) String scope,
                              @PathParam(SELECTOR) String selector,
                              @PathParam(ID) String segment,
                              @Nullable String body, @Context HttpHeaders http,
                              @Context UriInfo uri) {
        Surface surface = RefusalMapper.surfaceOf(http);
        String[] at = CustomMethod.split(segment);
        CustomMethod route = CustomMethod.find(METHOD_PATCH, CustomMethod.Depth.ITEM, at[1])
            .orElseThrow(() -> notHere(surface, CustomMethod.Depth.ITEM));
        ProcessVerb verb = route.verb();
        Map<String, Object> arguments = onATask(verb, surface,
            address(scope, selector, at[0]), body, http, uri);

        CallForm.Update update = CallForm.update(surface, arguments);
        Actor actor = caller.current();
        verbs.resolveScopeToWrite(actor, update.address().instance().scope());
        TaskPayload updated = calls.update(actor, update, surface);
        return Response.ok(updated).tag(updated.conflictToken()).build();
    }

    /**
     * DELETE on a task: {@code delete}, the one call carried by this method.
     * The task is the one the path names; its conflict token arrives as {@code
     * If-Match}, and the call takes nothing else, so it reads no body.
     * Answers the address the draft had.
     */
    @DELETE
    @Path("{selector}/{id}")
    public AddressPayload itemDelete(@PathParam(SCOPE) String scope,
                                     @PathParam(SELECTOR) String selector,
                                     @PathParam(ID) String segment,
                                     @Context HttpHeaders http, @Context UriInfo uri) {
        Surface surface = RefusalMapper.surfaceOf(http);
        String[] at = CustomMethod.split(segment);
        CustomMethod route = CustomMethod.find(HttpMethod.DELETE, CustomMethod.Depth.ITEM, at[1])
            .orElseThrow(() -> notHere(surface, CustomMethod.Depth.ITEM));
        ProcessVerb verb = route.verb();
        Map<String, Object> arguments = onATask(verb, surface,
            address(scope, selector, at[0]), null, http, uri);

        CallForm.OnADraft delete = CallForm.onADraft(verb, surface, arguments);
        Actor actor = caller.current();
        verbs.resolveScopeToWrite(actor, delete.address().instance().scope());
        return calls.delete(actor, delete);
    }

    /**
     * POST on an object with a colon verb: on an instance {@code conclude},
     * {@code withdraw}, {@code reference_attach} and {@code reference_detach},
     * and on a task {@code send}. The object is the one the path names; a
     * closing and a send take the conflict token from {@code If-Match}
     * (C10.3). Answers the object, with its conflict token as {@code ETag}.
     */
    @POST
    @Path("{selector}/{id}")
    public Response itemPost(@PathParam(SCOPE) String scope,
                             @PathParam(SELECTOR) String selector,
                             @PathParam(ID) String segment,
                             @Nullable String body, @Context HttpHeaders http,
                             @Context UriInfo uri) {
        Surface surface = RefusalMapper.surfaceOf(http);
        String[] at = CustomMethod.split(segment);
        CustomMethod route = CustomMethod.find(HttpMethod.POST, CustomMethod.Depth.ITEM, at[1])
            .orElseThrow(() -> notHere(surface, CustomMethod.Depth.ITEM));
        ProcessVerb verb = route.verb();
        if (verb == ProcessVerb.SEND) {
            Map<String, Object> arguments = onATask(verb, surface,
                address(scope, selector, at[0]), body, http, uri);
            CallForm.OnADraft send = CallForm.onADraft(verb, surface, arguments);
            Actor actor = caller.current();
            verbs.resolveScopeToWrite(actor, send.address().instance().scope());
            TaskPayload sent = calls.send(actor, send, surface);
            return Response.ok(sent).tag(sent.conflictToken()).build();
        }
        VerbName call = VerbName.of(verb, surface);

        Map<String, Object> arguments = body(call, body);
        once(verb, call, arguments, ADDRESS, address(scope, selector, at[0]));
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
     * The arguments of a call on a task: the body, closed against what the
     * call takes there, the address the path names, and the conflict token
     * of {@code If-Match}. A query is refused, since the call takes nothing
     * there.
     */
    private Map<String, Object> onATask(ProcessVerb verb, Surface surface, String address,
                                        @Nullable String body, HttpHeaders http, UriInfo uri) {
        VerbName call = VerbName.of(verb, surface);
        Map<String, Object> arguments = body(call, body);
        closedOutsideTheBody(verb, call, arguments, uri);
        once(verb, call, arguments, ADDRESS, address);
        String fence = http.getHeaderString(HttpHeaders.IF_MATCH);
        if (fence != null) {
            once(verb, call, arguments, CONFLICT_TOKEN, unquoted(fence));
        }
        return arguments;
    }

    /** The address of the object the path names. */
    private static String address(String scope, String selector, String id) {
        return AddressParser.SCHEME + "://" + scope + "/" + selector + "/" + id;
    }

    /**
     * Refuses an argument a call on this surface takes outside the body: any
     * query, and the idempotency key, which travels as {@code Idempotency-Key}
     * and nowhere else (C7.8, C10.4). The refusal lists what the body takes
     * and names nothing that arrived.
     */
    private static void closedOutsideTheBody(ProcessVerb verb, VerbName call,
                                             Map<String, Object> arguments, UriInfo uri) {
        if (!uri.getQueryParameters().isEmpty() || arguments.get(IDEMPOTENCY_KEY) != null) {
            List<ArgumentPath> accepts = new ArrayList<>();
            verb.topArguments().stream()
                .filter(argument -> !PATH_OR_HEADER.contains(argument.name()))
                .forEach(argument -> accepts.add(ArgumentPath.of(argument)));
            if (verb.hasFields()) {
                accepts.add(ArgumentPath.FIELDS);
            }
            throw new SurfaceException(Refusal.argumentUnknown(call, false, accepts));
        }
    }

    /**
     * The idempotency key the header {@code Idempotency-Key} carries for a
     * call that takes one: a header sent empty is an empty key, which the form
     * check refuses, and never no key; a header sent twice is refused as given
     * twice. Empty where the request carries no such header, or the call takes
     * no key.
     */
    static Optional<String> key(VerbName call, ProcessVerb verb, @Nullable List<String> sent) {
        if (verb.argument(IDEMPOTENCY_KEY).isEmpty() || sent == null || sent.isEmpty()) {
            return Optional.empty();
        }
        if (sent.size() > 1) {
            throw new SurfaceException(Refusal.argumentInvalid(call,
                ArgumentPath.of(verb.declared(IDEMPOTENCY_KEY)), Form.ONCE));
        }
        return Optional.of(sent.get(0).strip());
    }

    /** The REST path of a complete address. */
    private static String path(String address) {
        return "/api/" + address.substring((AddressParser.SCHEME + "://").length());
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
