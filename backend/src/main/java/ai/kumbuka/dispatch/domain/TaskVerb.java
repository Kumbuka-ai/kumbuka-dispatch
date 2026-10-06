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

    SEND("send",
        when(EnumSet.of(DRAFT), Condition.NONE, Relation.COMMISSIONER, Lock.NONE,
            Proof.CONFLICT_TOKEN),
        takes(TaskInput.Sending.class, new TaskInput.Sending(null)),
        moves(OPEN, null, null, Holding.NONE, null),
        "Sends the draft: the task is frozen and open to executors."),

    CLAIM("claim",
        when(EnumSet.of(OPEN), Condition.DRAWABLE, Relation.CANDIDATE, Lock.NONE,
            Proof.NONE),
        takes(TaskInput.Lease.class, TaskInput.Lease.standard()),
        moves(ACTIVE, null, null, Holding.TAKE, null),
        "Takes up this task; you become its holder."),

    CLAIM_NEXT("claim_next",
        when(EnumSet.of(OPEN), Condition.DRAWABLE, Relation.CANDIDATE, Lock.NONE,
            Proof.NONE),
        takes(TaskInput.Lease.class, TaskInput.Lease.standard()),
        moves(ACTIVE, null, null, Holding.TAKE, null),
        "Takes up the next open task addressed to you, without naming one."),

    RELEASE("release",
        when(EnumSet.of(ACTIVE), Condition.NONE, Relation.HOLDER, Lock.NONE,
            Proof.RECEIPT),
        takes(TaskInput.Remark.class, new TaskInput.Remark(null)),
        moves(OPEN, null, null, Holding.DROP, TextType.REMARK),
        "Gives the task back; it is open to be taken again."),

    DEFER("defer",
        when(EnumSet.of(ACTIVE), Condition.NONE, Relation.HOLDER, Lock.NONE,
            Proof.RECEIPT),
        takes(TaskInput.Deferral.class, null),
        moves(OPEN, null, null, Holding.DROP, TextType.REMARK),
        "Gives the task back until an instant you name; nobody can take it before."),

    RENEW("renew",
        when(EnumSet.of(ACTIVE), Condition.NONE, Relation.HOLDER, Lock.NONE,
            Proof.RECEIPT),
        takes(TaskInput.Lease.class, TaskInput.Lease.standard()),
        moves(ACTIVE, null, null, Holding.LEASE, null),
        "Extends your hold."),

    ASK("ask",
        when(EnumSet.of(ACTIVE), Condition.NONE, Relation.HOLDER, Lock.NONE,
            Proof.RECEIPT),
        takes(TaskInput.Question.class, null),
        moves(ON_HOLD, HoldReason.QUESTION, null, Holding.PAUSE, TextType.QUESTION),
        "Pauses the task and asks the commissioner a question."),

    ANSWER("answer",
        when(EnumSet.of(ON_HOLD), Condition.QUESTION_PENDING, Relation.COMMISSIONER, Lock.NONE,
            Proof.CONFLICT_TOKEN),
        takes(TaskInput.Reply.class, null),
        moves(ACTIVE, null, null, Holding.RESTART, TextType.ANSWER),
        "Answers the executor's question; the work continues."),

    HOLD("hold",
        when(EnumSet.of(ACTIVE), Condition.NONE, Relation.HOLDER, Lock.NONE,
            Proof.RECEIPT),
        takes(TaskInput.Pause.class, null),
        moves(ON_HOLD, null, null, Holding.PAUSE, TextType.REMARK),
        "Pauses the task while it waits on a dependency or on something external."),

    RESUME("resume",
        when(EnumSet.of(ON_HOLD), Condition.PAUSED_BY_HOLDER, Relation.HOLDER, Lock.NONE,
            Proof.RECEIPT),
        takes(TaskInput.Lease.class, TaskInput.Lease.standard()),
        moves(ACTIVE, null, null, Holding.LEASE, null),
        "Continues the paused task."),

    DELIVER("deliver",
        when(EnumSet.of(ACTIVE), Condition.NONE, Relation.HOLDER, Lock.NONE,
            Proof.RECEIPT),
        takes(TaskInput.Delivery.class, null),
        moves(DELIVERED, null, null, Holding.PAUSE, TextType.RETURN),
        "Delivers the answer and its metadata for acceptance."),

    REWORK("rework",
        when(EnumSet.of(DELIVERED), Condition.NONE, Relation.COMMISSIONER, Lock.NONE,
            Proof.CONFLICT_TOKEN),
        takes(TaskInput.RequiredRemark.class, null),
        moves(ACTIVE, null, null, Holding.RESTART, TextType.REMARK),
        "Sends the delivered answer back to its holder with a remark."),

    ACCEPT("accept",
        when(EnumSet.of(DELIVERED), Condition.NONE, Relation.COMMISSIONER, Lock.NOT_THE_DELIVERER,
            Proof.CONFLICT_TOKEN),
        takes(TaskInput.Nothing.class, TaskInput.NONE),
        moves(CLOSED, null, Outcome.ACCEPTED, Holding.DROP, null),
        "Accepts the delivered answer and closes the task."),

    REJECT("reject",
        when(EnumSet.of(OPEN), Condition.NONE, Relation.CANDIDATE, Lock.NONE,
            Proof.NONE),
        takes(TaskInput.RequiredRemark.class, null),
        moves(CLOSED, null, Outcome.REJECTED, Holding.DROP, TextType.REMARK),
        "Declines the commission and closes the task."),

    FAIL("fail",
        when(EnumSet.of(ACTIVE, ON_HOLD), Condition.NONE, Relation.HOLDER, Lock.NONE,
            Proof.RECEIPT),
        takes(TaskInput.RequiredRemark.class, null),
        moves(CLOSED, null, Outcome.FAILED, Holding.DROP, TextType.REMARK),
        "Closes the task as failed, with a remark."),

    WITHDRAW("withdraw",
        when(EnumSet.of(OPEN, ACTIVE, ON_HOLD, DELIVERED), Condition.NONE, Relation.COMMISSIONER, Lock.NONE,
            Proof.CONFLICT_TOKEN),
        takes(TaskInput.Remark.class, new TaskInput.Remark(null)),
        moves(CLOSED, null, Outcome.WITHDRAWN, Holding.DROP, TextType.REMARK),
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

    /** Checks 1 to 5 of a row: where it applies, who calls, what proves the call. */
    record When(Set<TaskState> states, Condition condition, Relation relation, Lock lock,
                Proof proof) {
    }

    /** Check 7 of a row: the payload shape, and what stands in for an absent one. */
    record Takes(Class<? extends TaskInput> shape, TaskInput fallback) {
    }

    /** The effect of a row: target state, attributes set, holding, text row. */
    record Moves(TaskState target, HoldReason holdReason, Outcome outcome, Holding holding,
                 TextType text) {
    }

    private static When when(Set<TaskState> states, Condition condition, Relation relation,
                             Lock lock, Proof proof) {
        return new When(Set.copyOf(states), condition, relation, lock, proof);
    }

    private static Takes takes(Class<? extends TaskInput> shape, TaskInput fallback) {
        return new Takes(shape, fallback);
    }

    private static Moves moves(TaskState target, HoldReason holdReason, Outcome outcome,
                               Holding holding, TextType text) {
        return new Moves(target, holdReason, outcome, holding, text);
    }

    private final String wireName;
    private final When when;
    private final Takes takes;
    private final Moves moves;
    private final String nextSentence;

    TaskVerb(String wireName, When when, Takes takes, Moves moves, String nextSentence) {
        this.wireName = wireName;
        this.when = when;
        this.takes = takes;
        this.moves = moves;
        this.nextSentence = nextSentence;
    }

    /** The verb as the surface names it, without the {@code dispatch_} prefix. */
    public String wireName() {
        return wireName;
    }

    /** The effective states the row applies in (check 1). */
    public Set<TaskState> states() {
        return when.states();
    }

    public Condition condition() {
        return when.condition();
    }

    public Relation relation() {
        return when.relation();
    }

    public Lock lock() {
        return when.lock();
    }

    public Proof proof() {
        return when.proof();
    }

    public TaskState target() {
        return moves.target();
    }

    /** The hold reason the row sets itself, or null where the payload or nothing sets it. */
    public HoldReason holdReason() {
        return moves.holdReason();
    }

    /** The outcome the row closes with, or null where it does not close. */
    public Outcome outcome() {
        return moves.outcome();
    }

    public Holding holding() {
        return moves.holding();
    }

    /** The text row the verb inserts, or null. */
    public TextType text() {
        return moves.text();
    }

    /** The sentence {@code next} carries for this verb. */
    public String nextSentence() {
        return nextSentence;
    }

    /** Whether the verb would close the task: on a bracket root it needs a confirmation. */
    public boolean closes() {
        return moves.target() == CLOSED;
    }

    /**
     * The payload the call carries, with the row's fallback for an absent one.
     *
     * @throws IllegalArgumentException when the payload is of another shape,
     *         or a mandatory one is absent -- a defect of the caller of the
     *         kernel, which the verb surface refuses by name before it gets here
     */
    TaskInput payloadOf(TaskCall call) {
        TaskInput given = call.payload();
        if (given instanceof TaskInput.Nothing && takes.fallback() != null) {
            return takes.fallback();
        }
        if (!takes.shape().isInstance(given)) {
            throw new IllegalArgumentException(wireName + " takes a " + takes.shape().getSimpleName()
                + ", not a " + given.getClass().getSimpleName());
        }
        return given;
    }
}
