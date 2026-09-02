package ai.kumbuka.dispatch.adapter.mcp;

import ai.kumbuka.dispatch.domain.QueryFilter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools the MCP exposition declares: the thirteen verbs, and nothing else.
 *
 * <p><strong>MCP omits and never adds.</strong> There is no tool here without
 * a verb behind it, and today there is no declared omission either — the list
 * is the whole verb set. If a reason to omit one ever appears, it is declared
 * in this file and in the specification both expositions probe against, never
 * left to be inferred from a shorter list.
 *
 * <p>The declaration is written out rather than derived from the REST routes.
 * The two expositions are conformance-probed against one specification and
 * neither is the source for the other — that is share-nothing applied to the
 * surface. A list generated from the routes would make the probe an assertion
 * that one copy equals itself.
 *
 * <h2>Two argument shapes, and the difference is not cosmetic</h2>
 *
 * A verb acting on an existing object takes a <strong>complete address</strong>
 * and nothing else identifies the target. A verb that does not act on an
 * existing object — {@code create} at a bracket — takes scope and selector as
 * separate arguments, because an address without an id part is reserved and
 * giving a three-part string the meaning "the objects of this scope" is what
 * the reservation forbids.
 */
public final class McpTools {

    private McpTools() {
    }

    /** One declared tool: its name, what it does, and what it takes. */
    public record Tool(String name, String description, Map<String, Object> inputSchema) {
    }

    /** The JSON Schema types this surface's arguments take. */
    private static final String STRING = "string";
    private static final String OBJECT = "object";

    /** The argument every verb acting on an existing object takes. */
    private static final String ARG_ADDRESS = "address";

    /** The two that address a collection rather than an object. */
    private static final String ARG_SCOPE = "scope";
    private static final String ARG_SELECTOR = "selector";

    private static final String SCOPE_DOC = "The scope name, a DNS label.";
    private static final String SELECTOR_DOC = "The declared bracket name.";

    /** The claim's lease, worded identically wherever a verb takes one. */
    private static final String DURATION_DOC =
        "How long the claim stands, as an ISO-8601 duration such as PT1H. There is no "
            + "default.";

    private static final String ADDRESS_DOC =
        "The complete address of the exchange: dispatch://<scope>/<selector>/<number>.<sub>, "
            + "with an optional single lower-case letter for an addendum.";

    /**
     * The thirteen, in the order of the item process rather than
     * alphabetically: what brings an object into being, what reads and changes
     * it, what commits it, what assigns the work, and what ends it.
     */
    public static List<Tool> declared() {
        return List.of(
            new Tool("create",
                "Bring an exchange into being. Without a parent this opens a bracket; with "
                    + "one it adds a child to that bracket. The number is allocated "
                    + "transactionally and is never supplied by the caller.",
                schema(
                    required(ARG_SCOPE, STRING, SCOPE_DOC),
                    required(ARG_SELECTOR, STRING, SELECTOR_DOC),
                    required("title", STRING, "The exchange's title."),
                    required("apparatus", STRING, "The apparatus this exchange addresses."),
                    required("date", STRING, "The dispatch date, as ISO-8601 (YYYY-MM-DD)."),
                    optional("parent", STRING,
                        "The bracket root to add a child to, as a complete address. Omit to "
                            + "open a new bracket."))),

            new Tool("read",
                "One exchange by address. Writes nothing. The projection depends on the "
                    + "actor: an executing apparatus does not receive the body of an "
                    + "exchange it has not claimed, and the field is absent rather than "
                    + "empty.",
                schema(required(ARG_ADDRESS, STRING, ADDRESS_DOC))),

            new Tool("update",
                "Replace the handover draft. Carries a conflict token, which is the one "
                    + "handed out with the last read; a stale token is refused rather than "
                    + "overwritten.",
                schema(
                    required(ARG_ADDRESS, STRING, ADDRESS_DOC),
                    required("conflict_token", STRING,
                        "The token from the last read of this exchange."),
                    required("draft", STRING, "The handover text, replaced wholesale."),
                    optional("receipt", STRING,
                        "The receipt issued at claim. Required of an executing apparatus."),
                    optional("metadata", OBJECT, "Handover metadata."))),

            new Tool("append",
                "Attach an addendum to a frozen exchange. Additive and not removable "
                    + "afterwards, which is why it is not an update.",
                schema(
                    required(ARG_ADDRESS, STRING, ADDRESS_DOC),
                    required("title", STRING, "The addendum's title."),
                    required("apparatus", STRING, "The apparatus it addresses."),
                    required("date", STRING, "Its date, as ISO-8601 (YYYY-MM-DD)."))),

            new Tool("send",
                "The author commits their own content outward. Freezes the dispatch and "
                    + "opens it to an executor.",
                schema(
                    required(ARG_ADDRESS, STRING, ADDRESS_DOC),
                    optional("metadata", OBJECT,
                        "Dispatch metadata, frozen at the same gate."))),

            new Tool("accept",
                "A second party accepts what somebody else produced: the handover is "
                    + "ratified and frozen. Not callable by an executing apparatus — a "
                    + "permission in the core, not an omission in this adapter.",
                schema(required(ARG_ADDRESS, STRING, ADDRESS_DOC))),

            new Tool("claim",
                "Acquire a lease on a named exchange. The service mints the receipt; a "
                    + "caller-supplied holder is refused.",
                schema(
                    required(ARG_ADDRESS, STRING, ADDRESS_DOC),
                    required("duration", STRING, DURATION_DOC))),

            new Tool("release",
                "Give up a lease. The exchange returns to its pre-claim state and any "
                    + "unratified draft is discarded with it.",
                schema(required(ARG_ADDRESS, STRING, ADDRESS_DOC))),

            new Tool("abandon",
                "The executor does not deliver. Terminal. The prior state decides whether "
                    + "this is a refusal before takeup or a failure after it; the caller "
                    + "does not name it.",
                schema(required(ARG_ADDRESS, STRING, ADDRESS_DOC))),

            new Tool("block",
                "The executor is stuck and the commissioner is due. Not terminal, and the "
                    + "holder keeps the exchange.",
                schema(required(ARG_ADDRESS, STRING, ADDRESS_DOC))),

            new Tool("resume",
                "The commissioner has answered. Back to work.",
                schema(required(ARG_ADDRESS, STRING, ADDRESS_DOC))),

            new Tool("close",
                "Terminal, administrative. A bracket root refuses while a sibling is "
                    + "still running, and names the ones that are.",
                schema(required(ARG_ADDRESS, STRING, ADDRESS_DOC))),

            new Tool("consume",
                "Terminal, curated forward into a named object.",
                schema(required(ARG_ADDRESS, STRING, ADDRESS_DOC))),

            new Tool("query",
                "The exchanges of one selector, narrowed by the declared filters. Values "
                    + "within a filter are comma-separated and read as alternatives; "
                    + "separate filters are read together. There is no expression "
                    + "language, and a field this scheme does not filter on is refused "
                    + "rather than ignored. The projection is the same as read's: an "
                    + "executing apparatus does not receive the body of an exchange it has "
                    + "not claimed.",
                querySchema()),

            new Tool("claim_next",
                "Take up the next claimable exchange of a selector, in the order of the "
                    + "address space. Terminal exchanges and ones effectively held by "
                    + "somebody else are skipped; one whose claim has lapsed is claimable "
                    + "again. Exactly one exchange is drawn, atomically — two concurrent "
                    + "draws never receive the same one.",
                schema(
                    required(ARG_SCOPE, STRING, SCOPE_DOC),
                    required(ARG_SELECTOR, STRING, SELECTOR_DOC),
                    required("duration", STRING, DURATION_DOC))));
    }

    /**
     * The listing's arguments: the address of the collection, plus one
     * optional argument per declared filter field.
     *
     * <p>The fields are read from {@link QueryFilter.Field} rather than
     * written out here. A second list would be a second place the question
     * "what is filterable" is answered, and the two would drift the first time
     * a field was added — with the schema advertising something the domain
     * refuses, or the domain accepting something the schema hides.
     */
    private static Map<String, Object> querySchema() {
        List<Field> fields = new java.util.ArrayList<>();
        fields.add(required(ARG_SCOPE, STRING, SCOPE_DOC));
        fields.add(required(ARG_SELECTOR, STRING, SELECTOR_DOC));
        for (QueryFilter.Field field : QueryFilter.Field.values()) {
            fields.add(optional(field.wireName(), STRING,
                "Narrow by " + field.wireName() + ". Comma-separated values are read as "
                    + "alternatives."));
        }
        return schema(fields.toArray(Field[]::new));
    }

    // ----------------------------------------------------------------------
    // The schema shapes
    // ----------------------------------------------------------------------

    private record Field(String name, String type, String description, boolean required) {
    }

    private static Field required(String name, String type, String description) {
        return new Field(name, type, description, true);
    }

    private static Field optional(String name, String type, String description) {
        return new Field(name, type, description, false);
    }

    /**
     * A JSON Schema object, with {@code additionalProperties} closed.
     *
     * <p>Closed rather than open, deliberately: an argument this surface does
     * not know is one a caller believes in. Accepting and ignoring it is how a
     * client comes to depend on a field the server never read.
     */
    private static Map<String, Object> schema(Field... fields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> mandatory = new java.util.ArrayList<>();

        for (Field f : fields) {
            properties.put(f.name(), Map.of("type", f.type(), "description", f.description()));
            if (f.required()) {
                mandatory.add(f.name());
            }
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", OBJECT);
        schema.put("properties", properties);
        schema.put("required", List.copyOf(mandatory));
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }
}
