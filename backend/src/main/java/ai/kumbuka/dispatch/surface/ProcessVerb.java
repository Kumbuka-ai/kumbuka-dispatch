package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.TaskVerb;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The twenty-five calls of the verb surface: the sixteen transitions and the
 * nine calls that are not transitions (TAR-0004 section 3), with the arguments
 * of concept section 3.2 and the descriptions of section 3.5.
 *
 * <p><strong>This is the single source of the surface.</strong> The MCP tool
 * list, the input schemas, the closure of every call's arguments at both
 * levels, the {@code next} names and the {@code ARGUMENT_UNKNOWN} refusal all
 * read from here; the REST routes are written by hand and a test holds them to
 * this list. The shape is a plain value type with no framework and no adapter
 * import, because the router takes the same declaration.
 *
 * <p>A transition names its row of {@link TaskVerb}, which decides it. The
 * nine others name none; the kernel's call of the same name decides them.
 *
 * <h2>Descriptions</h2>
 *
 * Each is at most {@link SurfaceDeclaration#DESCRIPTION_BUDGET} characters,
 * and what explains one argument stands at that argument. The descriptions of
 * {@code dispatch_claim} and {@code dispatch_claim_next} and of the argument
 * {@code apparatus} are concept section 3.5 word for word; the sentence about
 * the text is the same in both.
 */
public enum ProcessVerb {

    // ======================================================================
    // The sixteen transitions, in the order of TAR-0004 section 3
    // ======================================================================

    SEND("send", TaskVerb.SEND, Participation.COMMISSIONER,
        "Sends your draft: the task is frozen and becomes open, waiting for an executor to "
            + "take it up. Moves the task from draft to open (not final). Call as the "
            + "commissioner, with the conflict token of your last read.\n\n"
            + "After this the commission's text cannot change; add to it with "
            + "dispatch_annotate. Withdraw it with dispatch_withdraw.",
        List.of(address(), conflictToken())),

    CLAIM("claim", TaskVerb.CLAIM, Participation.CANDIDATE,
        "Takes up one open task and makes you its holder. Moves the task from open to "
            + "active (not final). Call as an executor, naming the task by its address.\n\n"
            + "Returns a receipt and the end of your hold. Keep the receipt: every later "
            + "call on this task needs it.\n\n"
            + Shared.THE_TEXT_IS_NOT_IN_THE_ANSWER + "\n\n"
            + "Your hold lasts 30 minutes unless you state a duration; extend it with "
            + "dispatch_renew.",
        List.of(address(), duration(), idempotencyKey(
            "a key of your own: repeating the claim with it while you hold the task "
                + "returns the same task and a new receipt, and takes nothing up again"))),

    CLAIM_NEXT("claim_next", TaskVerb.CLAIM_NEXT, Participation.CANDIDATE,
        "Takes up the next open task addressed to you, without naming one; use "
            + "dispatch_claim when you know the address. Moves the task from open to active "
            + "(not final). Call as an executor with scope, selector and apparatus.\n\n"
            + "Returns the address, a receipt and the end of your hold. Keep the receipt: "
            + "every later call on this task needs it.\n\n"
            + Shared.THE_TEXT_IS_NOT_IN_THE_ANSWER + "\n\n"
            + "If nothing matches, nothing is taken.",
        List.of(scope(), selector(),
            Argument.topList(Shared.APPARATUS, true, ApparatusPatterns.CHARACTER_RULE,
                "Which apparatus you draw for. Every task is addressed to an apparatus: the "
                    + "kind of executor it is meant for, such as \"code\" or \"review\". Give "
                    + "one or more patterns, matched as alternatives. \"*\" stands for any run "
                    + "of characters, so \"agent-*\" matches \"agent-backend\". Case-sensitive. "
                    + "A pattern of only \"*\" is refused. dispatch_query lists open tasks with "
                    + "their apparatus."),
            duration(),
            idempotencyKey("a key of your own: repeating the draw with it while you hold the "
                + "task it drew returns that task and a new receipt, and draws nothing"))),

    RELEASE("release", TaskVerb.RELEASE, Participation.HOLDER,
        "Gives a task you hold back: it is open again for any executor. Moves the task from "
            + "active to open (not final). Call as its holder, with your receipt. Use it when "
            + "you lose control over the run.\n\n"
            + "To give it back until a later instant use dispatch_defer; if the work cannot "
            + "be done, dispatch_fail.",
        List.of(address(), receipt(), remark(false))),

    DEFER("defer", TaskVerb.DEFER, Participation.HOLDER,
        "Gives a task you hold back until an instant you name; nobody can take it up before "
            + "then. Moves the task from active to open (not final). Call as its holder, with "
            + "your receipt. Use it for a technical abort that a later attempt may "
            + "overcome.\n\n"
            + "dispatch_read and dispatch_query show the instant.",
        List.of(address(), receipt(),
            Argument.field("not_before", Argument.STRING, true,
                "the instant before which nobody can take the task up, as ISO-8601 such as "
                    + "2026-10-07T09:00:00Z"),
            remark(false))),

    RENEW("renew", TaskVerb.RENEW, Participation.HOLDER,
        "Extends your hold on a task you are working on. The task stays active (not final). "
            + "Call as its holder, with your receipt.\n\n"
            + "The answer carries the new end of your hold.",
        List.of(address(), receipt(), duration())),

    ASK("ask", TaskVerb.ASK, Participation.HOLDER,
        "Pauses a task you hold and asks the commissioner a question, with answer options, "
            + "free text admitted, or both. Moves the task from active to on_hold (not "
            + "final). Call as its holder, with your receipt.\n\n"
            + "You keep the task. The commissioner's dispatch_answer makes it active again "
            + "with a fresh 30-minute hold; read the answer with dispatch_read_text, part "
            + "\"thread\".",
        List.of(address(), receipt(),
            Argument.field("question", Argument.STRING, true, "what you cannot decide"),
            Argument.field("options", Argument.ARRAY, false,
                "the answers the commissioner can choose from"),
            Argument.field("free_text", Argument.BOOLEAN, false,
                "whether the commissioner may answer in free text; false if omitted, and "
                    + "then options are needed"))),

    ANSWER("answer", TaskVerb.ANSWER, Participation.COMMISSIONER,
        "Answers the question the holder asked: name one of its options, or give text where "
            + "free text is admitted. Moves the task from on_hold to active (not final); the "
            + "holder continues with a fresh 30-minute hold. Call as the commissioner, with "
            + "the conflict token of your last read.\n\n"
            + "Read the question first with dispatch_read_text, part \"thread\".",
        List.of(address(), conflictToken(),
            Argument.field("option", Argument.STRING, false,
                "one of the question's options; give this or text"),
            Argument.field("text", Argument.STRING, false,
                "a free-text answer, where the question admits one; give this or option"))),

    HOLD("hold", TaskVerb.HOLD, Participation.HOLDER,
        "Pauses a task you hold while it waits on a dependency or on something external. "
            + "Moves the task from active to on_hold (not final). Call as its holder, with "
            + "your receipt. You keep the task; the hold does not run out while it is "
            + "paused.\n\n"
            + "Continue with dispatch_resume.",
        List.of(address(), receipt(),
            Argument.oneOf("reason", Argument.Placement.FIELDS, true,
                List.of("dependency", "external"), "why the task waits"),
            remark(false))),

    RESUME("resume", TaskVerb.RESUME, Participation.HOLDER,
        "Continues a task you paused with dispatch_hold. Moves the task from on_hold to "
            + "active (not final). Call as its holder, with your receipt.\n\n"
            + "A question you asked is continued by the commissioner's dispatch_answer, not "
            + "by this call.",
        List.of(address(), receipt(), duration())),

    DELIVER("deliver", TaskVerb.DELIVER, Participation.HOLDER,
        "Delivers your answer, its text and your metadata in one act. Moves the task from "
            + "active to delivered (not final). Call as its holder, with your receipt.\n\n"
            + "You keep the task while the commissioner reviews it. It closes when accepted, "
            + "or comes back to you with a remark through dispatch_rework.",
        List.of(address(), receipt(),
            Argument.field("text", Argument.STRING, true, "your answer"),
            metadata("your own keys on the answer"))),

    REWORK("rework", TaskVerb.REWORK, Participation.COMMISSIONER,
        "Sends a delivered answer back to its holder with a remark saying what to change. "
            + "Moves the task from delivered to active (not final); the holder continues with "
            + "a fresh 30-minute hold. Call as the commissioner, with the conflict token of "
            + "your last read.\n\n"
            + "Read the answer first with dispatch_read_text, part \"return\".",
        List.of(address(), conflictToken(), remark(true))),

    ACCEPT("accept", TaskVerb.ACCEPT, Participation.COMMISSIONER,
        "Accepts the delivered answer and closes the task. Moves the task from delivered to "
            + "closed, outcome accepted (final). Call as the commissioner, with the conflict "
            + "token of your last read; the identity that delivered cannot accept.\n\n"
            + "Read the answer first with dispatch_read_text, part \"return\". On a bracket "
            + "root with unfinished children, repeat with the confirmation the refusal hands "
            + "out.",
        List.of(address(), conflictToken(), confirmation())),

    REJECT("reject", TaskVerb.REJECT, Participation.CANDIDATE,
        "Declines a commission you were offered, with a remark saying why. Moves the task "
            + "from open to closed, outcome rejected (final). Call as an executor.",
        List.of(address(), remark(true))),

    FAIL("fail", TaskVerb.FAIL, Participation.HOLDER,
        "Closes a task you hold as failed, with a remark saying why. Moves the task from "
            + "active or on_hold to closed, outcome failed (final). Call as its holder, with "
            + "your receipt. For a technical abort use dispatch_defer; to hand the task back, "
            + "dispatch_release.\n\n"
            + "On a bracket root with unfinished children, repeat with the confirmation the "
            + "refusal hands out.",
        List.of(address(), receipt(), confirmation(), remark(true))),

    WITHDRAW("withdraw", TaskVerb.WITHDRAW, Participation.COMMISSIONER,
        "Withdraws a commission that is no longer wanted. Moves the task from open, active, "
            + "on_hold or delivered to closed, outcome withdrawn (final). Call as the "
            + "commissioner, with the conflict token of your last read.\n\n"
            + "On a bracket root with unfinished children the first call is refused and names "
            + "them; repeat it with the confirmation it hands out to withdraw them and the "
            + "root together.",
        List.of(address(), conflictToken(), confirmation(), remark(false))),

    // ======================================================================
    // The nine calls that are not transitions
    // ======================================================================

    CREATE("create", null, Participation.COMMISSIONER,
        "Creates a task as a draft, for you to complete and send. Without parent it opens a "
            + "new bracket and the task is its root; with parent it adds a child to that "
            + "bracket. The service allocates the number. Call as a commissioner; you become "
            + "the task's commissioner.\n\n"
            + "Nobody is offered the draft until you send it with dispatch_send. Change it "
            + "with dispatch_update, or discard it with dispatch_delete.",
        List.of(scope(), selector(),
            Argument.top("parent", Argument.STRING, false,
                "the complete address of a bracket root, to add a child to that bracket"),
            idempotencyKey("a key of your own, so a retried create does not create twice"),
            Argument.field("title", Argument.STRING, true, "the task's title"),
            Argument.field(Shared.APPARATUS, Argument.STRING, true,
                "the kind of executor the task is addressed to, such as \"code\""),
            Argument.field("text", Argument.STRING, false, "the commission's text"),
            metadata("your own keys on the task"))),

    UPDATE("update", null, Participation.COMMISSIONER,
        "Changes your draft: its title, apparatus, text or metadata; only what you give "
            + "changes. Call as the commissioner, with the conflict token of your last read. "
            + "The task stays a draft.\n\n"
            + "A sent task cannot be changed; add to its text with dispatch_annotate.",
        List.of(address(), conflictToken(),
            Argument.field("title", Argument.STRING, false, "the new title"),
            Argument.field(Shared.APPARATUS, Argument.STRING, false, "the new apparatus"),
            Argument.field("text", Argument.STRING, false, "the new text of the commission"),
            metadata("your own keys on the task, replacing the ones it has"))),

    DELETE("delete", null, Participation.COMMISSIONER,
        "Deletes your draft outright; it leaves no trace. Call as the commissioner, with the "
            + "conflict token of your last read. Only a draft can be deleted; a sent task is "
            + "withdrawn with dispatch_withdraw.\n\n"
            + "The answer carries the address the draft had, and nothing else.",
        List.of(address(), conflictToken())),

    READ("read", null, null,
        "Reads the head of one task: its state and attributes, whether you, someone else or "
            + "nobody holds it, its metadata, which texts it has, and the calls open to you "
            + "with what each does, or what the task waits for.\n\n"
            + "The answer does NOT contain the task's text; read that with "
            + "dispatch_read_text.",
        List.of(address())),

    READ_TEXT("read_text", null, null,
        "Reads one part of a task's text: \"dispatch\" (the commission), \"return\" (the valid "
            + "answer), \"thread\" (questions, answers, remarks and earlier answers, in order) "
            + "or \"addenda\". One part per call.\n\n"
            + "The commissioner reads every part in every state; an executor reads only a "
            + "task it holds. For \"dispatch\" and \"return\" the answer says how many addenda "
            + "the text has.",
        List.of(address(),
            Argument.oneOf("part", Argument.Placement.TOP, true,
                List.of("dispatch", "return", "thread", "addenda"), "the part to read"))),

    QUERY("query", null, null,
        "Lists the tasks of one bracket kind in a scope, without their text: each with its "
            + "state, attributes and the calls open to you. Narrow by state, apparatus, "
            + "bracket or address; comma-separated values are alternatives, and an undeclared "
            + "filter is refused.\n\n"
            + "The list follows the address order, stops at the page bound and says whether "
            + "it was cut.",
        List.of(scope(), selector(),
            Argument.top("state", Argument.STRING, false,
                "states to list, comma-separated: draft, open, active, on_hold, delivered, "
                    + "closed"),
            Argument.top(Shared.APPARATUS, Argument.STRING, false,
                "apparatus patterns, comma-separated; \"*\" stands for any run of characters"),
            Argument.top("bracket", Argument.STRING, false, "bracket numbers, comma-separated"),
            Argument.top("address", Argument.STRING, false,
                "complete addresses of tasks of this bracket kind, comma-separated"),
            Argument.top("limit", Argument.INTEGER, false,
                "the page bound: at most this many tasks; 100 if omitted"))),

    ANNOTATE("annotate", null, null,
        "Adds an addendum to a text of a sent task: a supplement with its own time and "
            + "author that cannot be removed. Name the part it supplements. Call as the "
            + "identity that wrote that text; the task's state does not change.\n\n"
            + "Read addenda with dispatch_read_text, part \"addenda\".",
        List.of(address(),
            idempotencyKey("a key of your own: repeating the addendum with it attaches nothing "
                + "a second time and returns the task"),
            Argument.field("text", Argument.STRING, true, "the addendum's text"),
            Argument.oneOf("part", Argument.Placement.FIELDS, true,
                List.of("dispatch", "return", "question", "answer", "remark"),
                "the text it supplements"))),

    RELATE("relate", null, Participation.COMMISSIONER,
        "Records the object a closed task was curated into: another task you can see, in any "
            + "scope and bracket kind, never the task itself. Call as the commissioner, with "
            + "the conflict token of your last read. The task stays closed.\n\n"
            + "Remove the relation with dispatch_unrelate.",
        List.of(address(), conflictToken(),
            Argument.field("curated_in", Argument.STRING, true,
                "the complete address of the task this one was curated into"))),

    UNRELATE("unrelate", null, Participation.COMMISSIONER,
        "Removes the record of the object a closed task was curated into. Call as the "
            + "commissioner, with the conflict token of your last read. The task stays "
            + "closed.",
        List.of(address(), conflictToken()));

    /** The prefix every call carries on the assistant surface. */
    public static final String PREFIX = "dispatch_";

    /**
     * Texts the constants share. A holder of its own, because an enum constant
     * cannot name a static field of its own type that is declared after it.
     */
    private static final class Shared {

        /** The sentence about the text, word for word the same in both claim descriptions. */
        static final String APPARATUS = "apparatus";

        static final String THE_TEXT_IS_NOT_IN_THE_ANSWER =
            "The answer does NOT contain the task's text. Read it next with dispatch_read_text, "
                + "part \"dispatch\", before you start working.";

        private Shared() {
        }
    }

    // ======================================================================
    // The arguments several calls share: one definition each, because they
    // ARE one argument and mean the same wherever they appear.
    // ======================================================================

    private static Argument scope() {
        return Argument.top("scope", Argument.STRING, true, "the scope's name, a DNS label");
    }

    private static Argument selector() {
        return Argument.top("selector", Argument.STRING, true,
            "the declared bracket kind, such as \"sprint\"");
    }

    private static Argument address() {
        return Argument.top("address", Argument.STRING, true,
            "the complete address of the task, dispatch://<scope>/<selector>/<number>.<sub>");
    }

    private static Argument conflictToken() {
        return Argument.top("conflict_token", Argument.STRING, true,
            "the conflict token handed out with your last read of this task");
    }

    private static Argument receipt() {
        return Argument.top("receipt", Argument.STRING, true,
            "the receipt your claim handed out for this task");
    }

    private static Argument duration() {
        return Argument.top("duration", Argument.STRING, false,
            "how long your hold lasts, as an ISO-8601 duration such as PT2H; 30 minutes if "
                + "omitted");
    }

    private static Argument confirmation() {
        return Argument.top("confirmation", Argument.STRING, false,
            "the confirmation a refusal handed out for closing a bracket root with unfinished "
                + "children");
    }

    private static Argument idempotencyKey(String description) {
        return Argument.top("idempotency_key", Argument.STRING, false, description);
    }

    private static Argument remark(boolean required) {
        return Argument.field("remark", Argument.STRING, required,
            required ? "why, in a sentence the other side can act on"
                : "a remark kept with the task");
    }

    private static Argument metadata(String description) {
        return Argument.field("metadata", Argument.OBJECT, false,
            description + ": each value a string or a list of strings");
    }

    private final String verb;
    private final TaskVerb transition;
    private final Participation role;
    private final String description;
    private final List<Argument> arguments;

    ProcessVerb(String verb, TaskVerb transition, Participation role, String description,
                List<Argument> arguments) {
        this.verb = verb;
        this.transition = transition;
        this.role = role;
        this.description = description;
        this.arguments = List.copyOf(arguments);
    }

    /** The verb as TAR-0004 section 3 names it, without a prefix: the name on REST. */
    public String verb() {
        return verb;
    }

    /** The name on the assistant surface: the verb, prefixed {@code dispatch_}. */
    public String call() {
        return PREFIX + verb;
    }

    /** The name this call goes by on one surface. */
    public String on(Surface surface) {
        return surface == Surface.MCP ? call() : verb;
    }

    /** The row of the transition table that decides this call, or null for the nine others. */
    public TaskVerb transition() {
        return transition;
    }

    public boolean isTransition() {
        return transition != null;
    }

    /**
     * The part that makes this call, or null where it depends on the task:
     * reading is open to whoever may see a task, an addendum to whoever wrote
     * the text it supplements.
     */
    public Participation role() {
        return role;
    }

    public String description() {
        return description;
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

    /** The declared argument of this name at one level, or null. */
    public Argument argument(String name, Argument.Placement placement) {
        return arguments.stream()
            .filter(a -> a.name().equals(name) && a.placement() == placement)
            .findFirst()
            .orElse(null);
    }

    /** Every declared top-level name, {@code fields} included where the call writes. */
    public List<String> topNames() {
        List<String> names = new java.util.ArrayList<>(
            topArguments().stream().map(Argument::name).toList());
        if (hasFields()) {
            names.add("fields");
        }
        return List.copyOf(names);
    }

    /** The call this transition row is declared as. */
    public static ProcessVerb of(TaskVerb transition) {
        return Arrays.stream(values())
            .filter(v -> v.transition == transition)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "no call is declared for the transition " + transition));
    }

    /** The call of this name on one surface, or null when no call goes by it. */
    public static ProcessVerb byName(Surface surface, String name) {
        for (ProcessVerb call : values()) {
            if (call.on(surface).equals(name)) {
                return call;
            }
        }
        return null;
    }

    /** Every call name on one surface, in declaration order. */
    public static List<String> names(Surface surface) {
        return Arrays.stream(values()).map(v -> v.on(surface)).toList();
    }

    /** The role as the declaration publishes it. */
    public String roleName() {
        return role == null ? "any" : role.name().toLowerCase(Locale.ROOT);
    }
}
