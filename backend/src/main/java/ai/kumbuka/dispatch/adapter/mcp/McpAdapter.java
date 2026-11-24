/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.mcp;

import ai.kumbuka.dispatch.adapter.payload.CallForm;
import ai.kumbuka.dispatch.adapter.payload.CallRefusals;
import ai.kumbuka.dispatch.adapter.payload.CapabilitiesPayload;
import ai.kumbuka.dispatch.adapter.payload.DeclaredCalls;
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
import ai.kumbuka.dispatch.surface.CallerActor;
import ai.kumbuka.dispatch.surface.PlatformLog;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.Refusal;
import ai.kumbuka.dispatch.surface.RefusalCode;
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
import io.quarkus.security.ForbiddenException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The assistant surface: the declared calls as MCP tools, over JSON-RPC 2.0 at
 * {@code POST /mcp}, written by hand (C4.1).
 *
 * <p>The methods are {@code initialize}, {@code ping}, {@code tools/list} and
 * {@code tools/call}; a notification takes no answer and is acknowledged with
 * 202. {@code tools/list} is the declaration's projection ({@link McpTools});
 * {@code tools/call} checks the form of the tool's arguments with {@link
 * CallForm}, resolves the scope the call names through {@link VerbSurface},
 * and makes the call through {@link DeclaredCalls}, which checks the one
 * permission the call names: one call of the verb surface, as the REST surface
 * makes it.
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

    /** The revision of the MCP protocol this adapter speaks. */
    private static final String PROTOCOL_VERSION = "2025-06-18";

    /** The name this service gives itself in {@code initialize}. */
    private static final String SERVER_NAME = "kumbuka-dispatch";

    private static final String JSONRPC = "2.0";
    private static final String KEY_JSONRPC = "jsonrpc";
    private static final String KEY_ID = "id";
    private static final String KEY_PARAMS = "params";
    private static final String KEY_NAME = "name";
    private static final String KEY_ARGUMENTS = "arguments";

    /** JSON-RPC's own codes. Protocol faults only — a refused call is not one. */
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;

    @Inject DeclaredCalls calls;
    @Inject VerbSurface verbs;
    @Inject CallerActor caller;
    @Inject ObjectMapper json;

    /** The service's version, from the build: the POM's version, never a literal (C4.5). */
    @Inject
    @ConfigProperty(name = "quarkus.application.version")
    String version;

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
            case "tools/call" -> call(id, request.get(KEY_PARAMS));
            // The method that arrived is the caller's and stays with it (DEC-0043 R19).
            default -> error(id, METHOD_NOT_FOUND, Texts.protocol().methodUnknown(), null);
        };
    }

    /**
     * One tool call. A name the service does not declare is a fault of the
     * protocol and answers as one; every other outcome is the call's, made and
     * answered, refused or not.
     */
    private Response call(Object id, @Nullable Object rawParams) {
        Map<?, ?> params = rawParams instanceof Map<?, ?> map ? map : Map.of();
        Object name = params.get(KEY_NAME);
        Optional<ProcessVerb> named = name == null ? Optional.empty()
            : ProcessVerb.byName(Surface.MCP, name.toString());
        if (named.isEmpty()) {
            // The name that arrived is the caller's and stays with it (DEC-0043 R19).
            return error(id, INVALID_PARAMS, Texts.protocol().toolUnknown(),
                new RpcErrorDataPayload(McpTools.names()));
        }
        ProcessVerb verb = named.get();
        VerbName call = VerbName.of(verb, Surface.MCP);
        try {
            return result(id, content(make(verb, arguments(params.get(KEY_ARGUMENTS))), false));
        } catch (ForbiddenException notPermitted) {
            // The scope was resolved before the call was made under its
            // permission, so the caller sees it (C1.3).
            PlatformLog.refused(PlatformLog.Reason.of(RefusalCode.ROLE_INSUFFICIENT));
            return refusal(id, Refusal.ofPermission(call));
        } catch (SurfaceException refused) {
            PlatformLog.refused(PlatformLog.Reason.of(refused.refusal().code()));
            return refusal(id, refused.refusal());
        } catch (DispatchException refused) {
            // DEBUG by its code, and WARN where the scope refused (C13.3): the
            // directory below logs nothing, so its line is written here.
            PlatformLog.domainRefused(refused.reason());
            return refusal(id, CallRefusals.of(refused, call, Surface.MCP));
        } catch (WebApplicationException framework) {
            throw framework;
        } catch (RuntimeException failure) {
            return refusal(id, UnexpectedFailures.refuse(call, failure));
        }
    }

    /**
     * Makes one declared call: its form checked, the scope it names resolved
     * for the check order, and the call made under its permission.
     */
    private Object make(ProcessVerb verb, Map<String, Object> arguments) {
        return switch (verb) {
            case READ_DEFINITION -> {
                CallForm.ReadDefinition read = CallForm.readDefinition(Surface.MCP, arguments);
                Actor actor = caller.current();
                verbs.resolveScope(actor, read.scope());
                yield calls.readDefinition(actor, read);
            }
            case START -> {
                CallForm.Start start = CallForm.start(Surface.MCP, arguments);
                Actor actor = caller.current();
                verbs.resolveScopeToWrite(actor, start.scope());
                yield calls.start(actor, start, Surface.MCP);
            }
            case CONCLUDE, WITHDRAW -> {
                CallForm.Close close = CallForm.close(verb, Surface.MCP, arguments);
                Actor actor = caller.current();
                verbs.resolveScopeToWrite(actor, close.address().scope());
                yield verb == ProcessVerb.CONCLUDE ? calls.conclude(actor, close, Surface.MCP)
                    : calls.withdraw(actor, close, Surface.MCP);
            }
            case REFERENCE_ATTACH, REFERENCE_DETACH -> {
                CallForm.Refer refer = CallForm.refer(verb, Surface.MCP, arguments);
                Actor actor = caller.current();
                verbs.resolveScopeToWrite(actor, refer.address().scope());
                yield verb == ProcessVerb.REFERENCE_ATTACH
                    ? calls.referenceAttach(actor, refer, Surface.MCP)
                    : calls.referenceDetach(actor, refer, Surface.MCP);
            }
            case CREATE -> {
                CallForm.Create create = CallForm.create(Surface.MCP, arguments);
                Actor actor = caller.current();
                verbs.resolveScopeToWrite(actor, create.scope());
                yield calls.create(actor, create, Surface.MCP);
            }
            case UPDATE -> {
                CallForm.Update update = CallForm.update(Surface.MCP, arguments);
                Actor actor = caller.current();
                verbs.resolveScopeToWrite(actor, update.address().instance().scope());
                yield calls.update(actor, update, Surface.MCP);
            }
            case DELETE, SEND -> {
                CallForm.OnADraft onADraft = CallForm.onADraft(verb, Surface.MCP, arguments);
                Actor actor = caller.current();
                verbs.resolveScopeToWrite(actor, onADraft.address().instance().scope());
                yield verb == ProcessVerb.DELETE ? calls.delete(actor, onADraft)
                    : calls.send(actor, onADraft, Surface.MCP);
            }
        };
    }

    /** The arguments of a tool call; none where the envelope carries no object. */
    private static Map<String, Object> arguments(@Nullable Object raw) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> arguments.put(String.valueOf(key), value));
        }
        return arguments;
    }

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

    // ======================================================================
    // The JSON-RPC envelope
    // ======================================================================

    private static Response result(Object id, Object payload) {
        return Response.ok(new RpcResultPayload(JSONRPC, id, payload)).build();
    }

    private static Response error(@Nullable Object id, int code, String message,
                                  @Nullable RpcErrorDataPayload data) {
        return Response.ok(new RpcErrorPayload(JSONRPC, id,
            new RpcErrorBodyPayload(code, message, data))).build();
    }
}
