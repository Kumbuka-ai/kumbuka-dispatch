package ai.kumbuka.dispatch.contract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The surface the target and the concept document fix, as fixed values.
 *
 * <p>Written by hand from {@code platform-specs}: the twenty-five calls from
 * TAR-0004 section 3, and what each takes from the table in section 3.2 of
 * {@code concept-dispatch-store-kernel-and-surface.md}. Never read from the
 * declaration: a test whose expectation comes from the thing it checks
 * asserts that one copy equals itself.
 *
 * <p>Where the table leaves a name open, the name the service chose stands
 * here, marked: the filters of {@code query} ("filters" in the table; the
 * concept's section 5 names state, apparatus pattern, bracket and a list of
 * addresses) and its page bound ("page bound"; {@code limit}).
 */
public final class TargetSurface {

    private TargetSurface() {
    }

    /** The sixteen transitions of TAR-0004 section 3, in its order. */
    public static final List<String> TRANSITIONS = List.of(
        "send", "claim", "claim_next", "release", "defer", "renew", "ask", "answer",
        "hold", "resume", "deliver", "rework", "accept", "reject", "fail", "withdraw");

    /** The nine calls that are not transitions, TAR-0004 section 3, in its order. */
    public static final List<String> OTHERS = List.of(
        "create", "update", "delete", "read", "read_text", "query", "annotate", "relate",
        "unrelate");

    /** Every call on REST: the names of section 3. */
    public static final List<String> REST = concat(TRANSITIONS, OTHERS);

    /** Every call on the assistant surface: {@code dispatch_<verb>}. */
    public static final List<String> MCP = REST.stream().map(v -> "dispatch_" + v).toList();

    /** One call's arguments: what stands at the top level, and what under {@code fields}. */
    public record Arguments(List<String> top, List<String> fields) {
    }

    /**
     * Concept section 3.2, row by row. "address" stands for the address,
     * "conflict_token", "receipt", "duration", "idempotency_key" and
     * "confirmation" for the transport artefacts of section 3.1.
     */
    public static final Map<String, Arguments> ARGUMENTS = arguments();

    private static Map<String, Arguments> arguments() {
        Map<String, Arguments> table = new LinkedHashMap<>();
        table.put("create", new Arguments(
            List.of("scope", "selector", "parent", "idempotency_key"),
            List.of("title", "apparatus", "text", "metadata")));
        table.put("update", new Arguments(List.of("address", "conflict_token"),
            List.of("title", "apparatus", "text", "metadata")));
        table.put("delete", new Arguments(List.of("address", "conflict_token"), List.of()));
        table.put("send", new Arguments(List.of("address", "conflict_token"), List.of()));
        table.put("claim", new Arguments(
            List.of("address", "duration", "idempotency_key"), List.of()));
        table.put("claim_next", new Arguments(
            List.of("scope", "selector", "apparatus", "duration", "idempotency_key"),
            List.of()));
        table.put("release", new Arguments(List.of("address", "receipt"), List.of("remark")));
        table.put("defer", new Arguments(List.of("address", "receipt"),
            List.of("not_before", "remark")));
        table.put("renew", new Arguments(List.of("address", "receipt", "duration"),
            List.of()));
        table.put("ask", new Arguments(List.of("address", "receipt"),
            List.of("question", "options", "free_text")));
        table.put("answer", new Arguments(List.of("address", "conflict_token"),
            List.of("option", "text")));
        table.put("hold", new Arguments(List.of("address", "receipt"),
            List.of("reason", "remark")));
        table.put("resume", new Arguments(List.of("address", "receipt", "duration"),
            List.of()));
        table.put("deliver", new Arguments(List.of("address", "receipt"),
            List.of("text", "metadata")));
        table.put("rework", new Arguments(List.of("address", "conflict_token"),
            List.of("remark")));
        table.put("accept", new Arguments(
            List.of("address", "conflict_token", "confirmation"), List.of()));
        table.put("reject", new Arguments(List.of("address"), List.of("remark")));
        table.put("fail", new Arguments(List.of("address", "receipt", "confirmation"),
            List.of("remark")));
        table.put("withdraw", new Arguments(
            List.of("address", "conflict_token", "confirmation"), List.of("remark")));
        table.put("read", new Arguments(List.of("address"), List.of()));
        table.put("read_text", new Arguments(List.of("address", "part"), List.of()));
        // "filters, page bound": the names are the service's (see the class comment).
        table.put("query", new Arguments(
            List.of("scope", "selector", "state", "apparatus", "bracket", "address", "limit"),
            List.of()));
        table.put("annotate", new Arguments(List.of("address"), List.of("text", "part")));
        table.put("relate", new Arguments(List.of("address", "conflict_token"),
            List.of("curated_in")));
        table.put("unrelate", new Arguments(List.of("address", "conflict_token"), List.of()));
        return Map.copyOf(table);
    }

    /** The arguments {@code remark} is mandatory on (concept section 3.2). */
    public static final List<String> REMARK_REQUIRED = List.of("fail", "reject", "rework");

    /** Mandatory on {@code claim_next} (concept section 3.2). */
    public static final List<String> CLAIM_NEXT_REQUIRED = List.of("scope", "selector",
        "apparatus");

    /**
     * Concept section 3.5, word for word. The line breaks of the document's
     * code blocks are its layout; a paragraph is a blank line.
     */
    public static final String CLAIM_DESCRIPTION =
        "Takes up one open task and makes you its holder. Moves the task from open to "
            + "active (not final). Call as an executor, naming the task by its address.\n\n"
            + "Returns a receipt and the end of your hold. Keep the receipt: every later "
            + "call on this task needs it.\n\n"
            + "The answer does NOT contain the task's text. Read it next with "
            + "dispatch_read_text, part \"dispatch\", before you start working.\n\n"
            + "Your hold lasts 30 minutes unless you state a duration; extend it with "
            + "dispatch_renew.";

    public static final String CLAIM_NEXT_DESCRIPTION =
        "Takes up the next open task addressed to you, without naming one; use "
            + "dispatch_claim when you know the address. Moves the task from open to active "
            + "(not final). Call as an executor with scope, selector and apparatus.\n\n"
            + "Returns the address, a receipt and the end of your hold. Keep the receipt: "
            + "every later call on this task needs it.\n\n"
            + "The answer does NOT contain the task's text. Read it next with "
            + "dispatch_read_text, part \"dispatch\", before you start working.\n\n"
            + "If nothing matches, nothing is taken.";

    public static final String APPARATUS_ARGUMENT =
        "Which apparatus you draw for. Every task is addressed to an apparatus: the kind of "
            + "executor it is meant for, such as \"code\" or \"review\". Give one or more "
            + "patterns, matched as alternatives. \"*\" stands for any run of characters, so "
            + "\"agent-*\" matches \"agent-backend\". Case-sensitive. A pattern of only \"*\" "
            + "is refused. dispatch_query lists open tasks with their apparatus.";

    /** The sentence about the text, the same in both descriptions. */
    public static final String THE_TEXT_SENTENCE =
        "The answer does NOT contain the task's text. Read it next with dispatch_read_text, "
            + "part \"dispatch\", before you start working.";

    /** The first entry of {@code next} after a claim. */
    public static final String FIRST_NEXT_CALL = "dispatch_read_text";
    public static final String FIRST_NEXT_DOES =
        "Reads the commission. Do this first: the claim did not return it.";

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new java.util.ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }
}
