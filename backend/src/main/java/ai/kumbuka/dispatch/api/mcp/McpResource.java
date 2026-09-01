package ai.kumbuka.dispatch.api.mcp;

import ai.kumbuka.dispatch.api.AddressParser;
import ai.kumbuka.dispatch.api.CallerActor;
import ai.kumbuka.dispatch.api.SurfaceException;
import ai.kumbuka.dispatch.api.VerbSurface;
import ai.kumbuka.dispatch.api.payload.Payloads;
import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The MCP exposition of the verb surface: a projection that omits and adds
 * nothing.
 *
 * <p>It calls {@link VerbSurface} and reimplements no verb, so the two
 * expositions cannot drift on what an act does or in which order it checks.
 * What differs is only expression — JSON-RPC over one endpoint here, method
 * and path there.
 *
 * <h2>Why the address arrives complete, and the REST path does not</h2>
 *
 * MCP is JSON-RPC over a single endpoint: tool name and arguments necessarily
 * travel in the body, and there is no request line an address could travel in.
 * So the address arrives whole, scheme included, and this adapter validates it.
 * The REST adapter constructs the address from the path instead. The two are
 * asymmetric by nature rather than by accident, and neither is a round trip of
 * the other.
 *
 * <h2>The four verbs the scheme does not carry are not simply missing</h2>
 *
 * They are absent from {@code tools/list}, which is what "MCP omits" means.
 * But a call naming one of them answers the same typed category error the REST
 * surface answers, rather than "unknown tool". The difference matters: unknown
 * tool says the caller mistyped, and a category error says the act does not
 * exist in this scheme and names why. Only the second one stops a caller
 * looking for the right spelling.
 */
@Path("/mcp")
@Authenticated
@TenantBound
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class McpResource {

    /** The revision of the MCP protocol this adapter speaks. */
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private static final String JSONRPC = "2.0";
    private static final String KEY_ID = "id";
    private static final String KEY_NAME = "name";
    private static final String KEY_ADDRESS = "address";

    /** JSON-RPC's own codes. Protocol faults only — a refused verb is not one. */
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;

    @Inject VerbSurface verbs;
    @Inject CallerActor caller;

    @POST
    public Response rpc(Map<String, Object> request) {
        if (request == null || !JSONRPC.equals(request.get("jsonrpc"))) {
            return error(null, INVALID_PARAMS, "a JSON-RPC 2.0 envelope is required");
        }

        Object id = request.get(KEY_ID);
        String method = string(request, "method");

        // A notification carries no id and takes no answer. Answering one is
        // a protocol error on our side, not a courtesy.
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

    // ======================================================================
    // The three methods
    // ======================================================================

    private Map<String, Object> initialize() {
        return Map.of(
            "protocolVersion", PROTOCOL_VERSION,
            "capabilities", Map.of("tools", Map.of()),
            "serverInfo", Map.of("name", "kumbuka-dispatch", "version", "0.1.0"));
    }

    /** The declared tools, in the shape MCP asks for them. */
    private static List<Map<String, Object>> tools() {
        return McpTools.declared().stream()
            .map(t -> Map.<String, Object>of(
                KEY_NAME, t.name(),
                "description", t.description(),
                "inputSchema", t.inputSchema()))
            .toList();
    }

    /**
     * Runs one tool call.
     *
     * <p>A refused verb comes back as {@code isError} on a successful JSON-RPC
     * response, never as a JSON-RPC error. The distinction is the protocol's
     * and it is worth keeping: a JSON-RPC error says the call could not be
     * made, and every refusal in this service is a call that was made and
     * answered.
     */
    private Map<String, Object> call(Map<String, Object> params) {
        String tool = string(params, KEY_NAME);
        Map<String, Object> arguments = arguments(params, "arguments");

        try {
            return content(invoke(tool, arguments), false);
        } catch (SurfaceException e) {
            return content(new Payloads.Refusal(e.reason().name(), e.getMessage(), List.of()),
                true);
        } catch (DispatchException e) {
            return content(new Payloads.Refusal(e.reason().name(), e.getMessage(),
                e.offenders()), true);
        }
    }

    // ======================================================================
    // The verbs
    // ======================================================================

    private Object invoke(String tool, Map<String, Object> in) {
        Actor actor = caller.current();

        return switch (tool == null ? "" : tool) {
            case "create" -> create(actor, in);
            case "read" -> at(in, (s, l, i) -> verbs.read(actor, s, l, i)).exchange();
            case "update" -> update(actor, in);
            case "append" -> append(actor, in);
            case "send" -> send(actor, in);
            case "accept" -> at(in, (s, l, i) -> verbs.accept(actor, s, l, i)).exchange();
            case "claim" -> claim(actor, in);
            case "release" -> at(in, (s, l, i) -> verbs.release(actor, s, l, i)).exchange();
            case "abandon" -> at(in, (s, l, i) -> verbs.abandon(actor, s, l, i)).exchange();
            case "block" -> at(in, (s, l, i) -> verbs.block(actor, s, l, i)).exchange();
            case "resume" -> at(in, (s, l, i) -> verbs.resume(actor, s, l, i)).exchange();
            case "close" -> at(in, (s, l, i) -> verbs.close(actor, s, l, i)).exchange();
            case "consume" -> at(in, (s, l, i) -> verbs.consume(actor, s, l, i)).exchange();

            // Not in tools/list, and still answered by name: an unknown-tool
            // reply would send the caller looking for a spelling.
            case "query" -> uncarried(() -> verbs.query(actor,
                required(in, "scope"), required(in, "selector")));
            case "claim_next" -> uncarried(() -> verbs.claimNext(actor,
                required(in, "scope"), required(in, "selector")));
            case "withdraw" -> uncarried(() -> at(in,
                (s, l, i) -> { verbs.withdraw(actor, s, l, i); return null; }));
            case "validate" -> uncarried(() -> at(in,
                (s, l, i) -> { verbs.validate(actor, s, l, i); return null; }));

            default -> throw new SurfaceException(SurfaceException.Reason.PAYLOAD_MALFORMED,
                "'" + tool + "' is not a tool of this server. Its tools are the verbs of "
                    + "the dispatch scheme, and tools/list names them.");
        };
    }

    private Object create(Actor actor, Map<String, Object> in) {
        String scope = required(in, "scope");
        String selector = required(in, "selector");
        Payloads.CreateRequest body = new Payloads.CreateRequest(
            required(in, "title"), required(in, "apparatus"), date(in, "date"), null);

        String parent = optional(in, "parent");
        if (parent == null) {
            return verbs.create(actor, scope, selector, body).exchange();
        }

        AddressParser.Parts at = AddressParser.uri(parent);
        requireSameCollection(at, scope, selector);
        return verbs.createChild(actor, at.scope(), at.selector(), at.id(), body).exchange();
    }

    private Object update(Actor actor, Map<String, Object> in) {
        AddressParser.Parts at = AddressParser.uri(required(in, KEY_ADDRESS));
        Payloads.UpdateRequest body = new Payloads.UpdateRequest(
            required(in, "draft"), optional(in, "receipt"), metadata(in));
        return verbs.update(actor, at.scope(), at.selector(), at.id(),
            required(in, "conflict_token"), body).exchange();
    }

    private Object append(Actor actor, Map<String, Object> in) {
        AddressParser.Parts at = AddressParser.uri(required(in, KEY_ADDRESS));
        return verbs.append(actor, at.scope(), at.selector(), at.id(),
            new Payloads.AppendRequest(required(in, "title"), required(in, "apparatus"),
                date(in, "date"))).exchange();
    }

    private Object send(Actor actor, Map<String, Object> in) {
        AddressParser.Parts at = AddressParser.uri(required(in, KEY_ADDRESS));
        return verbs.send(actor, at.scope(), at.selector(), at.id(),
            new Payloads.SendRequest(metadata(in))).exchange();
    }

    private Object claim(Actor actor, Map<String, Object> in) {
        AddressParser.Parts at = AddressParser.uri(required(in, KEY_ADDRESS));
        VerbSurface.ClaimOutcome claimed = verbs.claim(actor, at.scope(), at.selector(),
            at.id(), new Payloads.ClaimRequest(required(in, "duration")));
        return new Payloads.ClaimResponse(claimed.result().exchange(), claimed.receipt());
    }

    /** A verb addressed at one exchange, with the address split once. */
    private VerbSurface.Result at(Map<String, Object> in, ItemVerb verb) {
        AddressParser.Parts parts = AddressParser.uri(required(in, KEY_ADDRESS));
        return verb.apply(parts.scope(), parts.selector(), parts.id());
    }

    @FunctionalInterface
    private interface ItemVerb {
        VerbSurface.Result apply(String scope, String selector, String id);
    }

    /**
     * The four the scheme does not carry. Each call below throws; this exists
     * so the switch has an expression and the compiler is not told a lie about
     * a value that cannot be produced.
     */
    private static Object uncarried(Runnable verb) {
        verb.run();
        throw new IllegalStateException("an uncarried verb returned instead of refusing");
    }

    // ======================================================================
    // Arguments
    // ======================================================================

    private static void requireSameCollection(AddressParser.Parts parent,
                                              String scope, String selector) {
        if (!parent.scope().equals(scope) || !parent.selector().equals(selector)) {
            throw new SurfaceException(SurfaceException.Reason.PAYLOAD_MALFORMED,
                "the parent address names " + parent.scope() + "/" + parent.selector()
                    + " and the arguments name " + scope + "/" + selector + ". A child "
                    + "numbers within its bracket, so the two cannot disagree — and "
                    + "silently preferring one of them would decide which by accident.");
        }
    }

    private static String required(Map<String, Object> in, String name) {
        String value = optional(in, name);
        if (value == null || value.isBlank()) {
            throw new SurfaceException(SurfaceException.Reason.PAYLOAD_MALFORMED,
                "the argument '" + name + "' is required and did not arrive.");
        }
        return value;
    }

    private static String optional(Map<String, Object> in, String name) {
        Object value = in.get(name);
        return value == null ? null : value.toString();
    }

    private static LocalDate date(Map<String, Object> in, String name) {
        String raw = required(in, name);
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new SurfaceException(SurfaceException.Reason.PAYLOAD_MALFORMED,
                "'" + raw + "' is not an ISO-8601 date. The form is YYYY-MM-DD.");
        }
    }

    /**
     * Metadata, with every value rendered as text.
     *
     * <p>The domain's metadata is string-to-string and validates what it
     * holds. Coercing here rather than refusing a number keeps the refusal in
     * one place: a value that must not be stored is refused by the validator
     * that knows why, not by a type mismatch in an adapter.
     */
    private static Map<String, String> metadata(Map<String, Object> in) {
        Object raw = in.get("metadata");
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, String> flattened = new LinkedHashMap<>();
        map.forEach((k, v) -> flattened.put(String.valueOf(k), v == null ? null : v.toString()));
        return flattened;
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

    /**
     * A tool result.
     *
     * <p>Both {@code content} and {@code structuredContent} carry the same
     * answer, because clients read one or the other and a surface that offered
     * only the structured half would be unreadable to half of them.
     */
    private static Map<String, Object> content(Object payload, boolean isError) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", String.valueOf(payload))));
        result.put("structuredContent", payload);
        result.put("isError", isError);
        return result;
    }

    private static Response result(Object id, Object payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", JSONRPC);
        envelope.put(KEY_ID, id);
        envelope.put("result", payload);
        return Response.ok(envelope).build();
    }

    private static Response error(Object id, int code, String message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", JSONRPC);
        envelope.put(KEY_ID, id);
        envelope.put("error", Map.of("code", code, "message", message));
        return Response.ok(envelope).build();
    }
}
