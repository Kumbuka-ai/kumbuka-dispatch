package ai.kumbuka.dispatch.surface;

import java.util.List;

/**
 * The fourteen calls of the assistant surface, as section 5 of the contract
 * writes them.
 *
 * <p><strong>The descriptions are normative and are copied, not composed.</strong>
 * The conformance probe takes its expected values from the contract document
 * and not from this enum, so a wording improved here and not there turns the
 * probe red — which is the only arrangement under which "the description is
 * the contract's" means anything. Reword the contract first.
 *
 * <p><strong>This is the single source.</strong> The MCP tool list, the input
 * schemas, the closed-schema check, the {@code next} lists and the {@code
 * ARGUMENT_UNKNOWN} refusal all read from here. The router takes the same
 * declaration unchanged in a later commission, which is why the shape is a
 * plain value type with no Quarkus, no JSON annotations and no adapter
 * imports: the thing that travels between two services cannot depend on the
 * framework either of them happens to run.
 *
 * <h2>Why process verbs rather than the generic ones</h2>
 *
 * The generic verbs name transitions of the kernel — {@code send}, {@code
 * ratify}, {@code takeup}. An assistant reading a tool list learns from them
 * what the machine does, not what it can do next. Measured on 2026-09-18: an
 * assistant with no skill called {@code create}, then {@code claim}, and was
 * told its exchange "is draft and cannot takeup" — a true sentence from which
 * the missing step ({@code send}) could not be inferred. A process verb has no
 * such gap because it has no intermediate state to be in: {@code
 * dispatch_commission} is create-write-send in one transaction, and there is
 * nothing after it for the caller to have forgotten.
 */
public enum ProcessVerb {

    // ======================================================================
    // 5.1 Commissioner
    // ======================================================================

    COMMISSION("dispatch_commission",
        "Give a piece of work to someone. Creates the exchange and freezes it in one step: "
            + "once commissioned, the text cannot be changed, only corrected with "
            + "dispatch_add_correction. Without `parent` it opens a new bracket and the "
            + "exchange becomes its root; with `parent` it adds a child to that bracket. "
            + "The service allocates the number. Afterwards the exchange is open and waits "
            + "for an executor to take it up with dispatch_take.",
        Participation.COMMISSIONER,
        List.of(
            Argument.top("scope", "string", true, "the scope name, a DNS label"),
            Argument.top("selector", "string", true, "the declared bracket kind"),
            Argument.top("parent", "string", false,
                "the complete address of a bracket root, to add a child to it"),
            Argument.top("idempotency_key", "string", false,
                "a key of your own, so a retried call does not commission twice"),
            Argument.field("title", "string", true, "the exchange's title"),
            Argument.field("apparatus", "string", true, "who the work is addressed to"),
            Argument.field("text", "string", true, "the body of the commission"),
            Argument.field("date", "string", false,
                "the dispatch date as YYYY-MM-DD; today if omitted"),
            Argument.field("metadata", "object", false, "your own keys on the commission"))),

    ADD_CORRECTION("dispatch_add_correction",
        "Attach a correction to a commissioned exchange whose text is frozen. The "
            + "correction is shown with the exchange, cannot be removed, and closes "
            + "together with it. Not possible once the exchange is finished.",
        Participation.COMMISSIONER,
        List.of(
            Argument.top("address", "string", true, "the complete address of the exchange"),
            Argument.top("idempotency_key", "string", false,
                "a key of your own, so a retried call does not correct twice"),
            Argument.field("title", "string", true, "the correction's title"),
            Argument.field("text", "string", true, "what the correction says"))),

    ACCEPT_RETURN("dispatch_accept_return",
        "Accept the answer the executor delivered and finish the exchange. The answer is "
            + "frozen and the exchange becomes closed. Use dispatch_curate_return instead "
            + "if the answer is to be carried forward into another object, and "
            + "dispatch_reply_to_executor if it needs rework.",
        Participation.COMMISSIONER,
        List.of(Argument.top("address", "string", true,
            "the complete address of the exchange"))),

    CURATE_RETURN("dispatch_curate_return",
        "Accept the delivered answer and finish the exchange by carrying it forward into "
            + "another exchange you can see, for example the record of the bracket it "
            + "belongs to. The target is stored with the exchange.",
        Participation.COMMISSIONER,
        List.of(
            Argument.top("address", "string", true, "the complete address of the exchange"),
            Argument.field("into", "string", true,
                "the complete address of the exchange the answer is carried into: any "
                    + "exchange of this service you can see, in any scope and bracket "
                    + "kind, other than this one"))),

    REPLY_TO_EXECUTOR("dispatch_reply_to_executor",
        "Answer the executor: either a question it asked, or a delivered answer that needs "
            + "rework. The message is stored with the exchange and the holder continues "
            + "working.",
        Participation.COMMISSIONER,
        List.of(
            Argument.top("address", "string", true, "the complete address of the exchange"),
            Argument.top("conflict_token", "string", true,
                "the token handed out with the last read of this exchange"),
            Argument.field("message", "string", true, "what you are telling the executor"))),

    CANCEL("dispatch_cancel",
        "Withdraw a commission that is no longer wanted. The exchange is closed without an "
            + "accepted answer. On a bracket root this ends the bracket without a record, "
            + "and is refused while any exchange of the bracket is unfinished.",
        Participation.COMMISSIONER,
        List.of(
            Argument.top("address", "string", true, "the complete address of the exchange"),
            Argument.top("conflict_token", "string", true,
                "the token handed out with the last read of this exchange"),
            Argument.field("reason", "string", true, "why the commission is withdrawn"))),

    CLOSE_BRACKET("dispatch_close_bracket",
        "Finish a bracket: accept the record delivered on its root and close it. Refused "
            + "while any exchange of the bracket is unfinished; the refusal names each one "
            + "with its complete address and the call that would finish it.",
        Participation.COMMISSIONER,
        List.of(Argument.top("address", "string", true,
            "the complete address of the bracket root"))),

    // ======================================================================
    // 5.2 Executor
    // ======================================================================

    TAKE("dispatch_take",
        "Take up an open exchange to work on it. Returns a receipt that later calls on "
            + "this exchange need; keep it. The claim lasts for `duration`.",
        Participation.CANDIDATE,
        List.of(
            Argument.top("address", "string", true, "the complete address of the exchange"),
            Argument.top("duration", "string", true,
                "how long the claim stands, as an ISO-8601 duration such as PT2H"))),

    TAKE_NEXT("dispatch_take_next",
        "Take up the next open exchange of a bracket kind, in address order. Returns the "
            + "exchange and the receipt.",
        Participation.CANDIDATE,
        List.of(
            Argument.top("scope", "string", true, "the scope name, a DNS label"),
            Argument.top("selector", "string", true, "the declared bracket kind"),
            Argument.top("duration", "string", true,
                "how long the claim stands, as an ISO-8601 duration such as PT2H"))),

    DELIVER_RETURN("dispatch_deliver_return",
        "Deliver your answer to the commissioner. The answer is stored and the exchange "
            + "waits for the commissioner to accept it, curate it or send it back. You "
            + "keep the exchange meanwhile.",
        Participation.HOLDER,
        List.of(
            Argument.top("address", "string", true, "the complete address of the exchange"),
            Argument.top("receipt", "string", true,
                "the receipt dispatch_take issued for this exchange"),
            Argument.field("text", "string", true, "your answer"))),

    ASK_COMMISSIONER("dispatch_ask_commissioner",
        "Stop and ask the commissioner something you cannot decide. The question is stored "
            + "with the exchange; you keep it until the commissioner replies.",
        Participation.HOLDER,
        List.of(
            Argument.top("address", "string", true, "the complete address of the exchange"),
            Argument.top("receipt", "string", true,
                "the receipt dispatch_take issued for this exchange"),
            Argument.field("question", "string", true, "what you cannot decide"))),

    DECLINE("dispatch_decline",
        "Decline the work. On an open exchange, any executor who could take it up may "
            + "decline it, and the commission is refused. On an exchange you hold, it "
            + "records that you could not complete it, and needs your receipt. The reason "
            + "is stored. Final.",
        Participation.CANDIDATE,
        List.of(
            Argument.top("address", "string", true, "the complete address of the exchange"),
            Argument.top("receipt", "string", false,
                "the receipt dispatch_take issued, if you took the exchange up"),
            Argument.field("reason", "string", true, "why you are declining"))),

    // ======================================================================
    // 5.3 Both roles
    // ======================================================================

    READ("dispatch_read",
        "Read one exchange with what you may see of it, the calls open to you from its "
            + "state, and who it is waiting for.",
        null,
        List.of(Argument.top("address", "string", true,
            "the complete address of the exchange"))),

    QUERY("dispatch_query",
        "List the exchanges of one bracket kind in a scope, narrowed by filters. Each "
            + "entry carries its complete address, its state and the calls open to you.",
        null,
        List.of(
            Argument.top("scope", "string", true, "the scope name, a DNS label"),
            Argument.top("selector", "string", true, "the declared bracket kind")));

    private final String call;
    private final String description;
    private final Participation role;
    private final List<Argument> arguments;

    ProcessVerb(String call, String description, Participation role,
                List<Argument> arguments) {
        this.call = call;
        this.description = description;
        this.role = role;
        this.arguments = arguments;
    }

    /** The name a caller uses. Always prefixed, because a tool list is flat. */
    public String call() {
        return call;
    }

    /** Section 5's text, verbatim. */
    public String description() {
        return description;
    }

    /**
     * The part that may make this call, or null where every part may.
     *
     * <p>{@link Participation#CANDIDATE} on the two takes and on {@code
     * dispatch_decline} is section 2's own part and not a looser {@code
     * HOLDER}: a candidate is an executor at an exchange that is still open,
     * which is exactly the precondition those three carry on the open side.
     * {@code dispatch_decline} additionally admits the holder, on an {@code
     * active} exchange and with its receipt — one call over two parts, which
     * {@link NextCalculator} reads from the state rather than from this field.
     */
    public Participation role() {
        return role;
    }

    public List<Argument> arguments() {
        return arguments;
    }

    /** The arguments that travel at the top level. */
    public List<Argument> topArguments() {
        return arguments.stream().filter(a -> !a.isField()).toList();
    }

    /** The arguments that travel under {@code fields}. */
    public List<Argument> fieldArguments() {
        return arguments.stream().filter(Argument::isField).toList();
    }

    /** Whether this call carries a {@code fields} object at all. */
    public boolean hasFields() {
        return !fieldArguments().isEmpty();
    }

    /** The declared argument of this name, at either level, or null. */
    public Argument argument(String name) {
        return arguments.stream()
            .filter(a -> a.name().equals(name))
            .findFirst()
            .orElse(null);
    }

    /** Every declared argument name, for the {@code ARGUMENT_UNKNOWN} message. */
    public List<String> argumentNames() {
        return arguments.stream().map(Argument::name).toList();
    }

    /**
     * Every call name this surface offers.
     *
     * <p>Used by the refusal a caller gets when it names a tool that does not
     * exist. The contract has no {@code TOOL_UNKNOWN} reason, and inventing one
     * would break "a reason not in the table cannot be returned"; {@code
     * ARGUMENT_UNKNOWN} with the whole list is the honest reading — the caller
     * named something the surface does not have, and here is what it does.
     */
    public static List<String> byCallNames() {
        return java.util.Arrays.stream(values()).map(ProcessVerb::call).toList();
    }

    /** The verb of this call name, or null when no call goes by it. */
    public static ProcessVerb byCall(String call) {
        for (ProcessVerb verb : values()) {
            if (verb.call.equals(call)) {
                return verb;
            }
        }
        return null;
    }
}
