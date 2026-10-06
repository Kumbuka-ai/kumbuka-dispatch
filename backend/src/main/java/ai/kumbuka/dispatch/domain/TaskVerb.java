package ai.kumbuka.dispatch.domain;

import java.util.EnumSet;
import java.util.Set;

import static ai.kumbuka.dispatch.domain.TaskState.ACTIVE;
import static ai.kumbuka.dispatch.domain.TaskState.CLOSED;
import static ai.kumbuka.dispatch.domain.TaskState.DELIVERED;
import static ai.kumbuka.dispatch.domain.TaskState.DRAFT;
import static ai.kumbuka.dispatch.domain.TaskState.ON_HOLD;
import static ai.kumbuka.dispatch.domain.TaskState.OPEN;

/**
 * The transitions of a task: one row per verb (TAR-0004 section 3, concept
 * section 2.2).
 *
 * <p>Each row is data and nothing else: the effective states it applies in,
 * the condition on an attribute, the caller's relation to the task, the lock
 * on acceptance, the proof it needs, the payload it takes, the state it moves
 * to with the attributes it sets, what happens to holder, lease and count, the
 * text row it inserts, and its sentence for {@code next}. {@link Decision#of}
 * reads the checks off a row and {@link TaskService} reads the effects; no
 * other place holds a rule of a transition.
 *
 * <p>The tool description of each call is not here. It is written with the
 * verb surface in step 4 of REA-0009; this step carries the sentence for
 * {@code next}.
 */
public enum TaskVerb {

    SEND("send", EnumSet.of(DRAFT), Condition.NONE, Relation.COMMISSIONER, Lock.NONE,
        Proof.CONFLICT_TOKEN, TaskPayload.Sending.class, new TaskPayload.Sending(null),
        OPEN, null, null, Holding.NONE, null,
        "Sends the draft: the task is frozen and open to executors."),

    CLAIM("claim", EnumSet.of(OPEN), Condition.DRAWABLE, Relation.CANDIDATE, Lock.NONE,
        Proof.NONE, TaskPayload.Lease.class, TaskPayload.Lease.standard(),
        ACTIVE, null, null, Holding.TAKE, null,
        "Takes up this task; you become its holder."),

    CLAIM_NEXT("claim_next", EnumSet.of(OPEN), Condition.DRAWABLE, Relation.CANDIDATE,
        Lock.NONE, Proof.NONE, TaskPayload.Lease.class, TaskPayload.Lease.standard(),
        ACTIVE, null, null, Holding.TAKE, null,
        "Takes up the next open task addressed to you, without naming one."),

    RELEASE("release", EnumSet.of(ACTIVE), Condition.NONE, Relation.HOLDER, Lock.NONE,
        Proof.RECEIPT, TaskPayload.Remark.class, new TaskPayload.Remark(null),
        OPEN, null, null, Holding.DROP, TextType.REMARK,
        "Gives the task back; it is open to be taken again."),

    DEFER("defer", EnumSet.of(ACTIVE), Condition.NONE, Relation.HOLDER, Lock.NONE,
        Proof.RECEIPT, TaskPayload.Deferral.class, null,
        OPEN, null, null, Holding.DROP, TextType.REMARK,
        "Gives the task back until an instant you name; nobody can take it before."),

    RENEW("renew", EnumSet.of(ACTIVE), Condition.NONE, Relation.HOLDER, Lock.NONE,
        Proof.RECEIPT, TaskPayload.Lease.class, TaskPayload.Lease.standard(),
        ACTIVE, null, null, Holding.LEASE, null,
        "Extends your hold."),

    ASK("ask", EnumSet.of(ACTIVE), Condition.NONE, Relation.HOLDER, Lock.NONE,
        Proof.RECEIPT, TaskPayload.Question.class, null,
        ON_HOLD, HoldReason.QUESTION, null, Holding.PAUSE, TextType.QUESTION,
        "Pauses the task and asks the commissioner a question."),

    ANSWER("answer", EnumSet.of(ON_HOLD), Condition.QUESTION_PENDING, Relation.COMMISSIONER,
        Lock.NONE, Proof.CONFLICT_TOKEN, TaskPayload.Reply.class, null,
        ACTIVE, null, null, Holding.RESTART, TextType.ANSWER,
        "Answers the executor's question; the work continues."),

    HOLD("hold", EnumSet.of(ACTIVE), Condition.NONE, Relation.HOLDER, Lock.NONE,
        Proof.RECEIPT, TaskPayload.Pause.class, null,
        ON_HOLD, null, null, Holding.PAUSE, TextType.REMARK,
        "Pauses the task while it waits on a dependency or on something external."),

    RESUME("resume", EnumSet.of(ON_HOLD), Condition.PAUSED_BY_HOLDER, Relation.HOLDER,
        Lock.NONE, Proof.RECEIPT, TaskPayload.Lease.class, TaskPayload.Lease.standard(),
        ACTIVE, null, null, Holding.LEASE, null,
        "Continues the paused task."),

    DELIVER("deliver", EnumSet.of(ACTIVE), Condition.NONE, Relation.HOLDER, Lock.NONE,
        Proof.RECEIPT, TaskPayload.Delivery.class, null,
        DELIVERED, null, null, Holding.PAUSE, TextType.RETURN,
        "Delivers the answer and its metadata for acceptance."),

    REWORK("rework", EnumSet.of(DELIVERED), Condition.NONE, Relation.COMMISSIONER, Lock.NONE,
        Proof.CONFLICT_TOKEN, TaskPayload.RequiredRemark.class, null,
        ACTIVE, null, null, Holding.RESTART, TextType.REMARK,
        "Sends the delivered answer back to its holder with a remark."),

    ACCEPT("accept", EnumSet.of(DELIVERED), Condition.NONE, Relation.COMMISSIONER,
        Lock.NOT_THE_DELIVERER, Proof.CONFLICT_TOKEN, TaskPayload.Nothing.class,
        TaskPayload.NONE, CLOSED, null, Outcome.ACCEPTED, Holding.DROP, null,
        "Accepts the delivered answer and closes the task."),

    REJECT("reject", EnumSet.of(OPEN), Condition.NONE, Relation.CANDIDATE, Lock.NONE,
        Proof.NONE, TaskPayload.RequiredRemark.class, null,
        CLOSED, null, Outcome.REJECTED, Holding.DROP, TextType.REMARK,
        "Declines the commission and closes the task."),

    FAIL("fail", EnumSet.of(ACTIVE, ON_HOLD), Condition.NONE, Relation.HOLDER, Lock.NONE,
        Proof.RECEIPT, TaskPayload.RequiredRemark.class, null,
        CLOSED, null, Outcome.FAILED, Holding.DROP, TextType.REMARK,
        "Closes the task as failed, with a remark."),

    WITHDRAW("withdraw", EnumSet.of(OPEN, ACTIVE, ON_HOLD, DELIVERED), Condition.NONE,
        Relation.COMMISSIONER, Lock.NONE, Proof.CONFLICT_TOKEN, TaskPayload.Remark.class,
        new TaskPayload.Remark(null), CLOSED, null, Outcome.WITHDRAWN, Holding.DROP, TextType.REMARK,
        "Withdraws the commission and closes the task.");

    /** The condition on an attribute, beyond the state (check 2). */
    public enum Condition {
        NONE,
        /** {@code not_before} absent or reached. */
        DRAWABLE,
        /** The pause is a question. */
        QUESTION_PENDING,
        /** The pause is the holder's own: a dependency or something external. */
        PAUSED_BY_HOLDER
    }

    /** The relation the caller must have to the task (check 3). */
    public enum Relation {
        /** Holds the commissioning capacity and may see the task. */
        COMMISSIONER,
        /** Holds the task through a running lease, or holds it paused or delivered. */
        HOLDER,
        /** An executor that could take the task up. */
        CANDIDATE
    }

    /** The lock between executor and acceptor (check 4). */
    public enum Lock {
        NONE,
        /** Never the identity that delivered, whatever capacity it carries. */
        NOT_THE_DELIVERER
    }

    /** What fences the call (check 5). */
    public enum Proof {
        NONE,
        /** The receipt of the holder: every write by an executor carries it. */
        RECEIPT,
        /** The conflict token of the caller's last read. */
        CONFLICT_TOKEN
    }

    /** What happens to holder, lease and count. */
    public enum Holding {
        /** Nothing. */
        NONE,
        /** A new holder with a new receipt and lease; a lapse on the row is recorded. */
        TAKE,
        /** A new lease of the stated length; the holder stays. */
        LEASE,
        /** The lease ends and the holder stays. */
        PAUSE,
        /** A lease of the default length, which the caller does not set. */
        RESTART,
        /** Holder, receipt and lease are dropped. */
        DROP
    }

    private final String wireName;
    private final Set<TaskState> states;
    private final Condition condition;
    private final Relation relation;
    private final Lock lock;
    private final Proof proof;
    private final Class<? extends TaskPayload> payload;
    private final TaskPayload fallback;
    private final TaskState target;
    private final HoldReason holdReason;
    private final Outcome outcome;
    private final Holding holding;
    private final TextType text;
    private final String nextSentence;

    TaskVerb(String wireName, Set<TaskState> states, Condition condition, Relation relation,
             Lock lock, Proof proof, Class<? extends TaskPayload> payload, TaskPayload fallback,
             TaskState target, HoldReason holdReason, Outcome outcome, Holding holding,
             TextType text, String nextSentence) {
        this.wireName = wireName;
        this.states = Set.copyOf(states);
        this.condition = condition;
        this.relation = relation;
        this.lock = lock;
        this.proof = proof;
        this.payload = payload;
        this.fallback = fallback;
        this.target = target;
        this.holdReason = holdReason;
        this.outcome = outcome;
        this.holding = holding;
        this.text = text;
        this.nextSentence = nextSentence;
    }

    /** The verb as the surface names it, without the {@code dispatch_} prefix. */
    public String wireName() {
        return wireName;
    }

    /** The effective states the row applies in (check 1). */
    public Set<TaskState> states() {
        return states;
    }

    public Condition condition() {
        return condition;
    }

    public Relation relation() {
        return relation;
    }

    public Lock lock() {
        return lock;
    }

    public Proof proof() {
        return proof;
    }

    public TaskState target() {
        return target;
    }

    /** The hold reason the row sets itself, or null where the payload or nothing sets it. */
    public HoldReason holdReason() {
        return holdReason;
    }

    /** The outcome the row closes with, or null where it does not close. */
    public Outcome outcome() {
        return outcome;
    }

    public Holding holding() {
        return holding;
    }

    /** The text row the verb inserts, or null. */
    public TextType text() {
        return text;
    }

    /** The sentence {@code next} carries for this verb. */
    public String nextSentence() {
        return nextSentence;
    }

    /** Whether the verb would close the task: on a bracket root it needs a confirmation. */
    public boolean closes() {
        return target == CLOSED;
    }

    /**
     * The payload the call carries, with the row's fallback for an absent one.
     *
     * @throws IllegalArgumentException when the payload is of another shape,
     *         or a mandatory one is absent -- a defect of the caller of the
     *         kernel, which the verb surface refuses by name before it gets here
     */
    TaskPayload payloadOf(TaskCall call) {
        TaskPayload given = call.payload();
        if (given instanceof TaskPayload.Nothing && fallback != null) {
            return fallback;
        }
        if (!payload.isInstance(given)) {
            throw new IllegalArgumentException(wireName + " takes a " + payload.getSimpleName()
                + ", not a " + given.getClass().getSimpleName());
        }
        return given;
    }
}
