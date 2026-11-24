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
import ai.kumbuka.dispatch.domain.Actor;
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
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The REST surface of the calls: the routes of {@link CustomMethod}, and
 * nothing else.
 *
 * <p>A request becomes a call: its name from the route, its arguments from
 * the path (the scope and the selector) and the query, handed as one map to
 * the same form check the assistant surface uses ({@link CallForm}), and made
 * through {@link DeclaredCalls}, which checks the one permission the call
 * names. Before it is made, the scope it names is resolved through {@link
 * VerbSurface}, so that a caller without the permission meets {@code
 * NOT_FOUND} for a scope it may not see (C1.3). It catches nothing: a refusal
 * travels to {@link RefusalMapper}, which reads the call from the request
 * (C8.2).
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

    @Inject DeclaredCalls calls;
    @Inject VerbSurface verbs;
    @Inject CallerActor caller;

    /** GET on a collection: {@code read_definition}, the one call carried there. */
    @GET
    @Path("{selector}")
    public DefinitionPayload collectionGet(@PathParam("scope") String scope,
                                           @PathParam("selector") String segment,
                                           @Context HttpHeaders http, @Context UriInfo uri) {
        Surface surface = RefusalMapper.surfaceOf(http);
        String[] at = CustomMethod.split(segment);
        CustomMethod route = CustomMethod.find("GET", CustomMethod.Depth.COLLECTION, at[1])
            .orElseThrow(() -> notHere(surface));
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
     * A colon verb at a collection, which carries none: {@code
     * VERB_NOT_CARRIED} 405 with {@code Allow} and the calls this address
     * carries under {@code data.accepts}, and nothing of the verb that arrived
     * (C3.3, DEC-0043 R19).
     */
    private static SurfaceException notHere(Surface surface) {
        List<VerbName> accepts = CustomMethod.accepts(CustomMethod.Depth.COLLECTION, surface);
        return new SurfaceException(Refusal.callUnknown(accepts),
            CustomMethod.allow(CustomMethod.Depth.COLLECTION).orElse(null));
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
