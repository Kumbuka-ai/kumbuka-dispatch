/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.mcp;

import ai.kumbuka.dispatch.adapter.payload.CapabilitiesPayload;
import ai.kumbuka.dispatch.adapter.payload.EmptyPayload;
import ai.kumbuka.dispatch.adapter.payload.InitializePayload;
import ai.kumbuka.dispatch.adapter.payload.RpcErrorBodyPayload;
import ai.kumbuka.dispatch.adapter.payload.RpcErrorDataPayload;
import ai.kumbuka.dispatch.adapter.payload.RpcErrorPayload;
import ai.kumbuka.dispatch.adapter.payload.RpcResultPayload;
import ai.kumbuka.dispatch.adapter.payload.ServerInfoPayload;
import ai.kumbuka.dispatch.adapter.payload.ToolListPayload;
import ai.kumbuka.dispatch.surface.Texts;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * The assistant surface: the declared calls as MCP tools, over JSON-RPC 2.0 at
 * {@code POST /mcp}, written by hand (C4.1).
 *
 * <p>The methods are {@code initialize}, {@code ping}, {@code tools/list} and
 * {@code tools/call}; a notification takes no answer and is acknowledged with
 * 202. {@code tools/list} is the declaration's projection ({@link McpTools}).
 *
 * <p><strong>No call is offered yet.</strong> The service is rebuilt on the
 * store line, and no call is declared until it is bound to the new kernel: the
 * tool list is empty, and every {@code tools/call} names a tool the service
 * does not declare. A call's tool comes back with the change that binds it,
 * and with it the path that reads its arguments, makes it through the verb
 * surface and answers its refusals as a tool result with {@code isError: true}
 * (C4.4).
 *
 * <h2>Faults of the protocol</h2>
 *
 * A fault of the protocol is a JSON-RPC error: an unknown method is -32601;
 * an unknown tool is -32602 with the declared tool names under {@code
 * data.accepts} and no part of the name that arrived (C4.3, DEC-0043 R19).
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

    /** JSON-RPC's own codes. Protocol faults only — a refused call is not one. */
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;

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
            // No call is declared, so no tool name is one the service carries.
            // The name that arrived is the caller's and stays with it (DEC-0043 R19).
            case "tools/call" -> error(id, INVALID_PARAMS, Texts.protocol().toolUnknown(),
                new RpcErrorDataPayload(McpTools.names()));
            // The method that arrived is the caller's and stays with it (DEC-0043 R19).
            default -> error(id, METHOD_NOT_FOUND, Texts.protocol().methodUnknown(), null);
        };
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
