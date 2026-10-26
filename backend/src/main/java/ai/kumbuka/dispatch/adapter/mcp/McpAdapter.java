/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.mcp;

import ai.kumbuka.dispatch.adapter.payload.CallBinding;
import ai.kumbuka.dispatch.adapter.payload.CallPayload;
import ai.kumbuka.dispatch.adapter.payload.CapabilitiesPayload;
import ai.kumbuka.dispatch.adapter.payload.DomainRefusals;
import ai.kumbuka.dispatch.adapter.payload.EmptyPayload;
import ai.kumbuka.dispatch.adapter.payload.InitializePayload;
import ai.kumbuka.dispatch.adapter.payload.Payloads;
import ai.kumbuka.dispatch.adapter.payload.RpcErrorBodyPayload;
import ai.kumbuka.dispatch.adapter.payload.RpcErrorDataPayload;
import ai.kumbuka.dispatch.adapter.payload.RpcErrorPayload;
import ai.kumbuka.dispatch.adapter.payload.RpcResultPayload;
import ai.kumbuka.dispatch.adapter.payload.ServerInfoPayload;
import ai.kumbuka.dispatch.adapter.payload.TextContentPayload;
import ai.kumbuka.dispatch.adapter.payload.ToolListPayload;
import ai.kumbuka.dispatch.adapter.payload.ToolResultPayload;
import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.surface.AddressParser;
import ai.kumbuka.dispatch.surface.CallerActor;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.Refusal;
import ai.kumbuka.dispatch.surface.Surface;
import ai.kumbuka.dispatch.surface.SurfaceException;
import ai.kumbuka.dispatch.surface.Texts;
import ai.kumbuka.dispatch.surface.UnexpectedFailures;
import ai.kumbuka.dispatch.surface.VerbName;
import ai.kumbuka.dispatch.surface.VerbSurface;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The assistant surface: the twenty-six calls of TAR-0004 section 3 as MCP
 * tools, over JSON-RPC 2.0 at {@code POST /mcp}, written by hand (C4.1).
 *
 * <p>The methods are {@code initialize}, {@code ping}, {@code tools/list} and
 * {@code tools/call}; a notification takes no answer and is acknowledged with
 * 202. {@code tools/list} is the declaration's projection ({@link McpTools});
 * {@code tools/call} reads the tool's arguments into a {@link CallPayload} and
 * makes the call through {@link CallBinding}: one call of the verb surface,
 * as the REST surface makes it.
 *
 * <h2>Two kinds of error, kept apart</h2>
 *
 * A fault of the protocol is a JSON-RPC error: an unknown method is -32601;
 * an unknown tool is -32602 with the declared tool names under {@code
 * data.accepts} and no part of the name that arrived (C4.3, DEC-0043 R19). A
 * refused call is a call that was made and answered: a tool result with {@code
 * isError: true} carrying the envelope of every refusal (C4.4).
 *
 * <p>This class is, with {@code RefusalMapper}, the outermost edge: it is
 * where a refusal of a tool call is caught and put into its envelope, and it
 * has the catch-all every adapter has (C8.2, C8.3).
 *
 * <p>Present only where the build says so: {@code kumbuka.local-mcp.enabled},
 * on unless set to {@code false} (C4.6). The declaration is not served here;
 * it is at {@code GET /api/declaration} and nowhere else (C5.1).
 */
@IfBuildProperty(name = "kumbuka.local-mcp.enabled", stringValue = "true", enableIfMissing = true)
@Path("/mcp")
@Authenticated
@TenantBound
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class McpAdapter {

    private static final Logger LOG = Logger.getLogger(McpAdapter.class);

    /** The revision of the MCP protocol this adapter speaks. */
    private static final String PROTOCOL_VERSION = "2025-06-18";

    /** The name this service gives itself in {@code initialize}. */
    private static final String SERVER_NAME = "kumbuka-dispatch";

    private static final String JSONRPC = "2.0";
    private static final String KEY_JSONRPC = "jsonrpc";
    private static final String KEY_ID = "id";
    private static final String KEY_NAME = "name";
    private static final String KEY_PARAMS = "params";
    private static final String KEY_ARGUMENTS = "arguments";

    /** JSON-RPC's own codes. Protocol faults only — a refused call is not one. */
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;

    @Inject VerbSurface verbs;
    @Inject CallerActor caller;
    @Inject ObjectMapper json;
    @Inject Clock clock;

    /** The service's version, from the build: the POM's version, never a literal (C4.5). */
    @Inject
    @ConfigProperty(name = "quarkus.application.version")
    String version;

    /**
     * One tool call's name and arguments, as the envelope carries them.
     *
     * @param name the tool's name; absent where the envelope carried none
     */
    private record Params(@Nullable String name, Map<String, Object> arguments) {
    }

    @POST
    public Response rpc(@Nullable Map<String, Object> request) {
        if (request == null || !JSONRPC.equals(request.get(KEY_JSONRPC))) {
            return error(null, INVALID_PARAMS, Texts.protocol().envelopeRequired(), null);
        }
        Object id = request.get(KEY_ID);
        Object method = request.get("method");

        // A notification carries no id and takes no answer.
        if (id == null) {
            return Response.accepted().build();
        }

        return switch (method == null ? "" : method.toString()) {
            case "initialize" -> result(id, new InitializePayload(PROTOCOL_VERSION,
                new CapabilitiesPayload(new EmptyPayload()),
                new ServerInfoPayload(SERVER_NAME, version)));
            case "ping" -> result(id, new EmptyPayload());
            case "tools/list" -> result(id, new ToolListPayload(McpTools.declared()));
            case "tools/call" -> call(id, params(request.get(KEY_PARAMS)));
            // The method that arrived is the caller's and stays with it (DEC-0043 R19).
            default -> error(id, METHOD_NOT_FOUND, Texts.protocol().methodUnknown(), null);
        };
    }

    /**
     * One tool call. A name the service does not declare is a fault of the
     * protocol and answers as one; every other outcome is the call's, made and
     * answered, refused or not.
     */
    private Response call(Object id, Params params) {
        String name = params.name();
        Optional<ProcessVerb> named = name == null ? Optional.empty()
            : ProcessVerb.byName(Surface.MCP, name);
        if (named.isEmpty()) {
            // The name that arrived is the caller's and stays with it (DEC-0043 R19).
            return error(id, INVALID_PARAMS, Texts.protocol().toolUnknown(),
                new RpcErrorDataPayload(McpTools.names()));
        }
        ProcessVerb verb = named.get();
        @Nullable Actor actor = null;
        try {
            CallPayload in = new CallPayload(verb, Surface.MCP, params.arguments());
            actor = caller.current();
            return result(id, content(Payloads.of(
                CallBinding.make(verbs, Surface.MCP, actor, in, clock)), false));
        } catch (SurfaceException refused) {
            LOG.debugf("refusal: %s", refused.refusal().code());
            return refusal(id, refused.refusal());
        } catch (DispatchException refused) {
            LOG.debugf("refusal: %s", refused.reason());
            @Nullable Actor who = actor;
            return refusal(id, DomainRefusals.of(refused, Surface.MCP, who, verb,
                params.arguments(), complete -> readQuietly(who, complete), clock));
        } catch (WebApplicationException framework) {
            throw framework;
        } catch (RuntimeException failure) {
            return refusal(id, UnexpectedFailures.refuse(VerbName.of(verb, Surface.MCP),
                DomainRefusals.whereIn(params.arguments()), failure));
        }
    }

    /**
     * The task at a complete address, read in a transaction of its own after
     * the refused one rolled back; empty where the caller may not read it,
     * which is itself a refusal and the reason this read lives at the edge.
     */
    private Optional<VerbSurface.Result> readQuietly(@Nullable Actor actor, String complete) {
        if (actor == null) {
            return Optional.empty();
        }
        try {
            AddressParser.Parts parts = AddressParser.uri(complete);
            return Optional.of(verbs.read(actor, new VerbSurface.Item(parts.scope(),
                AddressParser.item(parts.selector(), parts.id()))));
        } catch (DispatchException | SurfaceException unreadable) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static Params params(@Nullable Object raw) {
        Map<String, Object> params = raw instanceof Map ? (Map<String, Object>) raw : Map.of();
        Object name = params.get(KEY_NAME);
        Object arguments = params.get(KEY_ARGUMENTS);
        return new Params(name == null ? null : name.toString(),
            arguments instanceof Map ? (Map<String, Object>) arguments : Map.of());
    }

    // ======================================================================
    // The JSON-RPC envelope
    // ======================================================================

    private Response refusal(Object id, Refusal refusal) {
        return result(id, content(Payloads.refusal(refusal), true));
    }

    /** The answer twice: as structured content, and as its JSON text for clients that read only text. */
    private ToolResultPayload content(Object payload, boolean isError) {
        return new ToolResultPayload(List.of(new TextContentPayload("text", asJson(payload))),
            payload, isError);
    }

    private String asJson(Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("an answer could not be written as JSON", e);
        }
    }

    private static Response result(Object id, Object payload) {
        return Response.ok(new RpcResultPayload(JSONRPC, id, payload)).build();
    }

    private static Response error(@Nullable Object id, int code, String message,
                                  @Nullable RpcErrorDataPayload data) {
        return Response.ok(new RpcErrorPayload(JSONRPC, id,
            new RpcErrorBodyPayload(code, message, data))).build();
    }
}
