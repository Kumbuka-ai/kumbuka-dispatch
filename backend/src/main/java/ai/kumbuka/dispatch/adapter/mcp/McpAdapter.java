package ai.kumbuka.dispatch.adapter.mcp;

import ai.kumbuka.dispatch.adapter.payload.Answers;
import ai.kumbuka.dispatch.adapter.payload.Payloads;
import ai.kumbuka.dispatch.surface.NextCalculator;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.Refused;
import ai.kumbuka.dispatch.surface.Surface;
import ai.kumbuka.dispatch.surface.SurfaceDeclaration;
import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.domain.ExchangeAddress;
import ai.kumbuka.dispatch.surface.AddressParser;
import ai.kumbuka.dispatch.surface.CallerActor;
import ai.kumbuka.dispatch.surface.SurfaceException;
import ai.kumbuka.dispatch.surface.VerbInput;
import ai.kumbuka.dispatch.surface.VerbSurface;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The assistant surface: the fourteen process verbs of section 5, and nothing
 * else.
 *
 * <p>The fifteen generic verbs are gone from here. That is the whole change and
 * it is deliberate that no transition period carries both: a caller reading a
 * tool list cannot be expected to work out which of two overlapping vocabularies
 * is the one meant for it, and the pair that would confuse it most is precisely
 * the pair that does the same thing under two names. The generic verbs are
 * still the complete surface — over REST, where they always were.
 *
 * <h2>What this adapter is responsible for, and what it is not</h2>
 *
 * It checks the form of a call against the declaration, routes it to one verb
 * of {@link VerbSurface}, and dresses the answer or the refusal in the shape
 * sections 3 and 4 fix. It composes nothing: every compound act is one call of
 * the domain, which makes it atomic there rather than here.
 *
 * <p>It is also where {@code next} takes MCP's vocabulary. The computation is
 * shared; the names are this surface's. A caller told to call {@code accept}
 * has been told to call something it cannot reach.
 */
@Path("/mcp")
@Authenticated
@TenantBound
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class McpAdapter {

    /**
     * What this logger may say: call name, address, typed reason. Never a
     * title, a text, metadata, a token or a receipt — the operator boundary is
     * built as a missing GRANT, and a log shipper carrying a commission's text
     * out of the container walks around it.
     */
    private static final Logger LOG = Logger.getLogger(McpAdapter.class);

    /** The revision of the MCP protocol this adapter speaks. */
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private static final String JSONRPC = "2.0";
    private static final String KEY_JSONRPC = "jsonrpc";
    private static final String KEY_ID = "id";
    private static final String KEY_NAME = "name";

    private static final String ARG_SCOPE = "scope";
    private static final String ARG_SELECTOR = "selector";
    private static final String ARG_ADDRESS = "address";
    private static final String ARG_DURATION = "duration";
    private static final String ARG_RECEIPT = "receipt";
    private static final String ARG_CONFLICT_TOKEN = "conflict_token";

    /** JSON-RPC's own codes. Protocol faults only — a refused verb is not one. */
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;

    @Inject VerbSurface verbs;
    @Inject CallerActor caller;

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
     * The declaration, as the machine-readable artefact.
     *
     * <p>Served here rather than from a static file so that what is published
     * is what the running service holds. A file in the jar can be stale
     * against the code beside it; this cannot.
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

    // ======================================================================
    // One call
    // ======================================================================

    /**
     * Runs one tool call, and answers a refusal as a refusal.
     *
     * <p>A refused verb comes back as {@code isError} on a successful JSON-RPC
     * response, never as a JSON-RPC error. The protocol's distinction is worth
     * keeping: a JSON-RPC error says the call could not be made, and every
     * refusal in this service is a call that was made and answered.
     *
     * <p>The three catch clauses are not three shapes. They are three sources
     * — this adapter's own form checks, the surface's, and the kernel's — and
     * all of them leave through {@link #refusal}, so a caller cannot tell from
     * the shape which layer said no. It has no business knowing.
     */
    private Map<String, Object> call(Map<String, Object> params) {
        String tool = string(params, KEY_NAME);
        Map<String, Object> arguments = arguments(params, "arguments");

        ProcessVerb verb = ProcessVerb.byCall(tool);
        if (verb == null) {
            // Through `refusal` like every other refusal. Handing the exception
            // itself to `content` would serialise a Java object where a caller
            // expects the envelope, and the reason — the one part a caller
            // matches on — would not be on the wire at all.
            return content(refusal(Refused.argumentUnknown(String.valueOf(tool),
                String.valueOf(tool), ProcessVerb.byCallNames())), true);
        }

        try {
            return content(invoke(verb, new CallArguments(verb, arguments)), false);
        } catch (Refused e) {
            return content(refusal(e), true);
        } catch (SurfaceException e) {
            return content(refusal(Refusals.of(e, verb, arguments, this)), true);
        } catch (DispatchException e) {
            return content(refusal(Refusals.of(e, verb, arguments, this)), true);
        } catch (RuntimeException e) {
            // Ours, not the caller's. The reference is what makes the report
            // actionable; the exception itself never reaches the caller,
            // because a stack trace is both unreadable and a disclosure.
            String reference = UUID.randomUUID().toString();
            LOG.errorf(e, "unexpected failure on %s, reference %s", verb.call(), reference);
            return content(refusal(Refused.unexpected(verb.call(),
                addressOrCollection(arguments), reference)), true);
        }
    }

    private static Payloads.RefusalEnvelope refusal(Refused refused) {
        return new Payloads.RefusalEnvelope(refused.code().name(), refused.getMessage(),
            refused.data());
    }

    // ======================================================================
    // The fourteen
    // ======================================================================

    private Object invoke(ProcessVerb verb, CallArguments in) {
        Actor actor = caller.current();

        return switch (verb) {
            case COMMISSION -> commission(actor, in);
            case ADD_CORRECTION -> addCorrection(actor, in);
            case ACCEPT_RETURN -> at(in, (s, l, i) -> verbs.acceptReturn(actor, s, l, i));
            case CURATE_RETURN -> curateReturn(actor, in);
            case REPLY_TO_EXECUTOR -> replyToExecutor(actor, in);
            case CANCEL -> cancel(actor, in);
            case CLOSE_BRACKET -> at(in, (s, l, i) -> verbs.closeBracket(actor, s, l, i));
            case TAKE -> take(actor, in);
            case TAKE_NEXT -> takeNext(actor, in);
            case DELIVER_RETURN -> deliverReturn(actor, in);
            case ASK_COMMISSIONER -> askCommissioner(actor, in);
            case DECLINE -> decline(actor, in);
            case READ -> full(atResult(in, (s, l, i) -> verbs.read(actor, s, l, i)));
            case QUERY -> query(actor, in);
        };
    }

    private Object commission(Actor actor, CallArguments in) {
        String scope = in.requiredTop(ARG_SCOPE);
        String selector = in.requiredTop(ARG_SELECTOR);

        ExchangeAddress parent = null;
        String rawParent = in.optionalTop("parent");
        if (rawParent != null) {
            AddressParser.Parts at = AddressParser.uri(rawParent);
            requireSameCollection(at, scope, selector, ProcessVerb.COMMISSION);
            parent = AddressParser.item(at.selector(), at.id());
        }

        VerbInput.Commission body = new VerbInput.Commission(
            in.requiredField("title"),
            in.requiredField("apparatus"),
            in.requiredField("text"),
            in.optionalDateField("date", LocalDate.now()),
            in.metadataField());

        return compact(verbs.commission(actor, scope, selector, parent, body));
    }

    /**
     * Attaches a correction, text included, in one call.
     *
     * <p>The answer is the CORRECTED exchange rather than the addendum. A
     * correction has no standing of its own — it is not independently drawable
     * and it closes with what it corrects — so answering with its address would
     * hand the caller an address that {@code dispatch_read} refuses.
     */
    private Object addCorrection(Actor actor, CallArguments in) {
        AddressParser.Parts at = AddressParser.uri(in.requiredTop(ARG_ADDRESS));
        return compact(verbs.addCorrection(actor, at.scope(), at.selector(), at.id(),
            in.requiredField("title"), in.requiredField("text")));
    }

    private Object curateReturn(Actor actor, CallArguments in) {
        AddressParser.Parts at = AddressParser.uri(in.requiredTop(ARG_ADDRESS));
        AddressParser.Parts into = AddressParser.uri(in.requiredField("into"));
        requireSameCollection(into, at.scope(), at.selector(), ProcessVerb.CURATE_RETURN);

        return compact(verbs.curateReturn(actor, at.scope(), at.selector(), at.id(),
            AddressParser.item(into.selector(), into.id())));
    }

    private Object replyToExecutor(Actor actor, CallArguments in) {
        AddressParser.Parts at = AddressParser.uri(in.requiredTop(ARG_ADDRESS));
        return compact(verbs.replyToExecutor(actor, at.scope(), at.selector(), at.id(),
            in.requiredTop(ARG_CONFLICT_TOKEN), in.requiredField("message")));
    }

    private Object cancel(Actor actor, CallArguments in) {
        AddressParser.Parts at = AddressParser.uri(in.requiredTop(ARG_ADDRESS));
        return compact(verbs.cancel(actor, at.scope(), at.selector(), at.id(),
            in.requiredTop(ARG_CONFLICT_TOKEN), in.requiredField("reason")));
    }

    private Object take(Actor actor, CallArguments in) {
        AddressParser.Parts at = AddressParser.uri(in.requiredTop(ARG_ADDRESS));
        VerbSurface.ClaimOutcome claimed = verbs.claim(actor, at.scope(), at.selector(),
            at.id(), new VerbInput.Claim(in.requiredTop(ARG_DURATION)));
        return withReceipt(claimed);
    }

    private Object takeNext(Actor actor, CallArguments in) {
        VerbSurface.ClaimOutcome claimed = verbs.claimNext(actor,
            in.requiredTop(ARG_SCOPE), in.requiredTop(ARG_SELECTOR),
            new VerbInput.Claim(in.requiredTop(ARG_DURATION)));
        return withReceipt(claimed);
    }

    private Object deliverReturn(Actor actor, CallArguments in) {
        AddressParser.Parts at = AddressParser.uri(in.requiredTop(ARG_ADDRESS));
        return compact(verbs.deliverReturn(actor, at.scope(), at.selector(), at.id(),
            in.requiredTop(ARG_RECEIPT), in.requiredField("text")));
    }

    private Object askCommissioner(Actor actor, CallArguments in) {
        AddressParser.Parts at = AddressParser.uri(in.requiredTop(ARG_ADDRESS));
        return compact(verbs.askCommissioner(actor, at.scope(), at.selector(), at.id(),
            in.requiredTop(ARG_RECEIPT), in.requiredField("question")));
    }

    private Object decline(Actor actor, CallArguments in) {
        AddressParser.Parts at = AddressParser.uri(in.requiredTop(ARG_ADDRESS));
        return compact(verbs.decline(actor, at.scope(), at.selector(), at.id(),
            in.optionalTop(ARG_RECEIPT), in.requiredField("reason")));
    }

    private Object query(Actor actor, CallArguments in) {
        VerbSurface.Listing listing = verbs.query(actor, in.requiredTop(ARG_SCOPE),
            in.requiredTop(ARG_SELECTOR), in.filters());
        return Answers.listing(listing.exchanges(), Surface.MCP);
    }

    // ======================================================================
    // Answers
    // ======================================================================

    private static Payloads.Answer full(VerbSurface.Result result) {
        return Answers.full(result, Surface.MCP);
    }

    private static Payloads.Answer compact(VerbSurface.Result result) {
        return Answers.compact(result, Surface.MCP);
    }

    /**
     * The claim's answer: the exchange, plus the receipt.
     *
     * <p>The receipt is a member beside the answer rather than a field of the
     * exchange, because it is not a property of the exchange — it is this
     * caller's proof, issued once, and the service keeps only a hash. A field
     * on the exchange would be a field every later read would have to withhold.
     */
    private static Map<String, Object> withReceipt(VerbSurface.ClaimOutcome claimed) {
        Map<String, Object> answer = new LinkedHashMap<>();
        Payloads.Answer exchange = compact(claimed.result());
        answer.put("address", exchange.address());
        answer.put("fields", exchange.fields());
        answer.put("conflict_token", exchange.conflictToken());
        answer.put("next", exchange.next());
        if (exchange.waitingFor() != null) {
            answer.put("waiting_for", exchange.waitingFor());
        }
        answer.put("receipt", claimed.receipt());
        return Map.copyOf(answer);
    }

    /** A verb addressed at one exchange, with the address split once. */
    private VerbSurface.Result atResult(CallArguments in, ItemVerb verb) {
        AddressParser.Parts parts = AddressParser.uri(in.requiredTop(ARG_ADDRESS));
        return verb.apply(parts.scope(), parts.selector(), parts.id());
    }

    private Object at(CallArguments in, ItemVerb verb) {
        return compact(atResult(in, verb));
    }

    @FunctionalInterface
    private interface ItemVerb {
        VerbSurface.Result apply(String scope, String selector, String id);
    }

    // ======================================================================
    // Dressing a refusal: reading the state, so the way OUT can be named
    // ======================================================================

    /**
     * Reads the exchange a refused call was addressed at, for its state and its
     * {@code next}.
     *
     * <p>A second read, after the first act was refused and rolled back. That
     * is a real cost and it is paid deliberately: a refusal that cannot say
     * what the caller CAN do is the refusal this whole contract exists to
     * replace, and the state at the moment of refusal is the only honest
     * source for it.
     *
     * <p>Returns null when the exchange cannot be read — which is not a
     * failure: it means the refusal was about something the caller cannot see,
     * and a refusal about an invisible exchange carries no state by design.
     */
    Situation situationOf(Map<String, Object> arguments) {
        Object raw = arguments.get(ARG_ADDRESS);
        if (raw == null) {
            return null;
        }
        try {
            AddressParser.Parts at = AddressParser.uri(String.valueOf(raw));
            VerbSurface.Result result =
                verbs.read(caller.current(), at.scope(), at.selector(), at.id());
            return new Situation(result.exchange().address(),
                result.exchange().status().wireName(),
                result.exchange().status().terminal(),
                result.next(Surface.MCP));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** What a refusal needs to know about the exchange it is refusing on. */
    record Situation(String address, String state, boolean terminal,
                     List<NextCalculator.Step> next) {
    }

    String collectionOf(Map<String, Object> arguments) {
        Object scope = arguments.get(ARG_SCOPE);
        Object selector = arguments.get(ARG_SELECTOR);
        if (scope == null || selector == null) {
            return null;
        }
        return AddressParser.completeCollection(String.valueOf(scope),
            String.valueOf(selector));
    }

    private String addressOrCollection(Map<String, Object> arguments) {
        Object address = arguments.get(ARG_ADDRESS);
        if (address != null) {
            return String.valueOf(address);
        }
        String collection = collectionOf(arguments);
        return collection == null ? "the address given" : collection;
    }

    // ======================================================================
    // Arguments
    // ======================================================================

    private static void requireSameCollection(AddressParser.Parts given, String scope,
                                              String selector, ProcessVerb verb) {
        if (!given.scope().equals(scope) || !given.selector().equals(selector)) {
            throw Refused.argumentInvalid(verb.call(), "parent",
                given.scope() + "/" + given.selector(),
                "it names a different bracket kind than the call does, and a child numbers "
                    + "within its own bracket");
        }
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

    private static Map<String, Object> content(Object payload, boolean isError) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", String.valueOf(payload))));
        result.put("structuredContent", payload);
        result.put("isError", isError);
        return result;
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
