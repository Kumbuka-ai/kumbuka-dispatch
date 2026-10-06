package ai.kumbuka.dispatch.adapter.mcp;

import ai.kumbuka.dispatch.adapter.payload.Answers;
import ai.kumbuka.dispatch.surface.CallRouter;
import ai.kumbuka.dispatch.surface.CallerActor;
import ai.kumbuka.dispatch.surface.Surface;
import ai.kumbuka.dispatch.surface.SurfaceDeclaration;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The assistant surface: the twenty-five calls of TAR-0004 section 3 as MCP
 * tools, and nothing else.
 *
 * <p>This class speaks JSON-RPC and nothing more. {@code tools/list} is the
 * declaration's projection ({@link McpTools}); {@code tools/call} hands the
 * tool's name and arguments to {@link CallRouter}, which the REST surface uses
 * the same way, and dresses what comes back. A refused call answers as {@code
 * isError} on a successful JSON-RPC response, never as a JSON-RPC error: every
 * refusal is a call that was made and answered.
 *
 * <p>Present only where the build says so: {@code kumbuka.local-mcp.enabled},
 * on unless set to {@code false}. Off, neither {@code /mcp} nor {@code
 * /mcp/declaration} exists; a deployment whose callers come through the
 * router over REST carries no assistant surface of its own.
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

    private static final String JSONRPC = "2.0";
    private static final String KEY_JSONRPC = "jsonrpc";
    private static final String KEY_ID = "id";
    private static final String KEY_NAME = "name";

    /** JSON-RPC's own codes. Protocol faults only — a refused call is not one. */
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;

    @Inject CallRouter router;
    @Inject CallerActor caller;
    @Inject ObjectMapper json;

    @POST
    public Response rpc(Map<String, Object> request) {
        if (request == null || !JSONRPC.equals(request.get(KEY_JSONRPC))) {
            return error(null, INVALID_PARAMS, "a JSON-RPC 2.0 envelope is required");
        }

        Object id = request.get(KEY_ID);
        String method = string(request, "method");

        // A notification carries no id and takes no answer.
        if (id == null) {
            return Response.accepted().build();
        }

        return switch (method == null ? "" : method) {
            case "initialize" -> result(id, initialize());
            case "tools/list" -> result(id, Map.of("tools", tools()));
            case "tools/call" -> result(id, call(arguments(request, "params")));
            default -> error(id, METHOD_NOT_FOUND,
                "'" + method + "' is not a method of this server. It speaks initialize, "
                    + "tools/list and tools/call.");
        };
    }

    /**
     * The declaration, as the machine-readable artefact: served from the
     * running service, so what is published is what it holds.
     */
    @GET
    @Path("/declaration")
    public Map<String, Object> declaration() {
        return SurfaceDeclaration.asMap();
    }

    private Map<String, Object> initialize() {
        return Map.of(
            "protocolVersion", PROTOCOL_VERSION,
            "capabilities", Map.of("tools", Map.of()),
            "serverInfo", Map.of("name", "kumbuka-dispatch", "version", "0.5.0"));
    }

    private static List<Map<String, Object>> tools() {
        return McpTools.declared().stream()
            .map(t -> Map.<String, Object>of(
                KEY_NAME, t.name(),
                "description", t.description(),
                "inputSchema", t.inputSchema()))
            .toList();
    }

    /** One tool call: the router decides, this dresses its answer as MCP content. */
    private Map<String, Object> call(Map<String, Object> params) {
        CallRouter.Outcome outcome = router.call(Surface.MCP, caller::current,
            string(params, KEY_NAME), arguments(params, "arguments"));
        return content(Answers.of(outcome), outcome instanceof CallRouter.Refusal);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> arguments(Map<String, Object> envelope, String key) {
        Object value = envelope == null ? null : envelope.get(key);
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static String string(Map<String, Object> envelope, String key) {
        Object value = envelope == null ? null : envelope.get(key);
        return value == null ? null : value.toString();
    }

    // ======================================================================
    // The JSON-RPC envelope
    // ======================================================================

    /** The answer twice: as structured content, and as its JSON text for clients that read only text. */
    private Map<String, Object> content(Object payload, boolean isError) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", asJson(payload))));
        result.put("structuredContent", payload);
        result.put("isError", isError);
        return result;
    }

    private String asJson(Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("an answer could not be written as JSON", e);
        }
    }

    private static Response result(Object id, Object payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(KEY_JSONRPC, JSONRPC);
        envelope.put(KEY_ID, id);
        envelope.put("result", payload);
        return Response.ok(envelope).build();
    }

    private static Response error(Object id, int code, String message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(KEY_JSONRPC, JSONRPC);
        envelope.put(KEY_ID, id);
        envelope.put("error", Map.of("code", code, "message", message));
        return Response.ok(envelope).build();
    }
}
