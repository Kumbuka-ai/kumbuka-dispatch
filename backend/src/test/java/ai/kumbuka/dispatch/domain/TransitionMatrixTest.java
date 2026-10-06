package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.domain.Decision.Check;
import ai.kumbuka.dispatch.domain.DispatchException.Reason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance criterion 1: for every row of the transition table and every
 * relation of a caller to a task, the decision the target prescribes.
 *
 * <h2>Where the expectation comes from</h2>
 *
 * {@link #MATRIX} is written out, cell by cell, from TAR-0004 section 3 (the
 * table of transitions and the paragraphs under it) and section 5 (holding).
 * It is NOT read from {@link TaskVerb} or computed from any rule: a test that
 * derives its expectation with the same case distinction as the code stays
 * green under a misreading the two share. Every cell is a literal.
 *
 * <h2>The situations</h2>
 *
 * Eleven, covering every effective state and every attribute a row's
 * condition reads: {@code draft}; {@code open}; {@code deferred} (open, before
 * the instant a {@code defer} named); {@code active}; {@code lapsed} (the row
 * says active, the lease ended -- effectively open, H is the former holder);
 * {@code asked}, {@code waiting}, {@code blocked} (on_hold with question,
 * dependency, external; H holds); {@code parked} (the third lapse: on_hold
 * external without a holder, H the former holder); {@code delivered} (H
 * delivered and is kept as holder); {@code closed}.
 *
 * <h2>The callers</h2>
 *
 * C, the commissioner (the commissioning capacity). H, the executor that holds
 * or held the task; where nobody has held it, an executor like any other. K,
 * another executor, a candidate that never held it.
 *
 * <h2>The cells</h2>
 *
 * {@code ok} permitted. {@code S} refused at the state, {@code
 * TRANSITION_NOT_PERMITTED}. {@code SL} refused at the state, {@code
 * LEASE_LAPSED}: a former holder calling the holder's verb. {@code CD} refused
 * at the condition, the deferral ({@code DEFERRAL_PENDING}). {@code CT}
 * refused at the condition, the hold reason ({@code TRANSITION_NOT_PERMITTED}).
 * {@code RA} refused at the relation, the capacity ({@code ACTOR_UNKNOWN}).
 * {@code RC} refused at the relation, not the holder ({@code CLAIM_REQUIRED}).
 * {@code RL} refused at the relation, the former holder ({@code LEASE_LAPSED}).
 *
 * <p>Every call presents what checks 5 to 7 ask -- H's receipt, the current
 * conflict token, a valid payload -- and the task is a root without children,
 * so a cell is decided by checks 1 to 4 and a permitted cell is permitted
 * whole. Each row is permitted for its relation in at least one situation and
 * refused for each relation in at least one; a row is never permitted for a
 * relation the target does not name, and that refusal is in the matrix too.
 */
class TransitionMatrixTest {

    static final Actor C = new Actor("commissioner", Actor.Kind.CONSOLE);
    static final Actor H = new Actor("executor-h", Actor.Kind.EXECUTOR);
    static final Actor K = new Actor("executor-k", Actor.Kind.EXECUTOR);

    static final String RECEIPT = "receipt-of-h";
    static final String TOKEN = "2026-10-06T08:00:00.000001Z";
    static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");

    /** The expected decision per verb, situation and caller. Literal; see the class note. */
    static final String MATRIX = """
            send       | draft     | ok | RA | RA
            send       | open      | S  | S  | S
            send       | deferred  | S  | S  | S
            send       | active    | S  | S  | S
            send       | lapsed    | S  | S  | S
            send       | asked     | S  | S  | S
            send       | waiting   | S  | S  | S
            send       | blocked   | S  | S  | S
            send       | parked    | S  | S  | S
            send       | delivered | S  | S  | S
            send       | closed    | S  | S  | S
            claim      | draft     | S  | S  | S
            claim      | open      | RA | ok | ok
            claim      | deferred  | CD | CD | CD
            claim      | active    | S  | S  | S
            claim      | lapsed    | RA | ok | ok
            claim      | asked     | S  | S  | S
            claim      | waiting   | S  | S  | S
            claim      | blocked   | S  | S  | S
            claim      | parked    | S  | S  | S
            claim      | delivered | S  | S  | S
            claim      | closed    | S  | S  | S
            claim_next | draft     | S  | S  | S
            claim_next | open      | RA | ok | ok
            claim_next | deferred  | CD | CD | CD
            claim_next | active    | S  | S  | S
            claim_next | lapsed    | RA | ok | ok
            claim_next | asked     | S  | S  | S
            claim_next | waiting   | S  | S  | S
            claim_next | blocked   | S  | S  | S
            claim_next | parked    | S  | S  | S
            claim_next | delivered | S  | S  | S
            claim_next | closed    | S  | S  | S
            release    | draft     | S  | S  | S
            release    | open      | S  | S  | S
            release    | deferred  | S  | S  | S
            release    | active    | RC | ok | RC
            release    | lapsed    | S  | SL | S
            release    | asked     | S  | S  | S
            release    | waiting   | S  | S  | S
            release    | blocked   | S  | S  | S
            release    | parked    | S  | SL | S
            release    | delivered | S  | S  | S
            release    | closed    | S  | S  | S
            defer      | draft     | S  | S  | S
            defer      | open      | S  | S  | S
            defer      | deferred  | S  | S  | S
            defer      | active    | RC | ok | RC
            defer      | lapsed    | S  | SL | S
            defer      | asked     | S  | S  | S
            defer      | waiting   | S  | S  | S
            defer      | blocked   | S  | S  | S
            defer      | parked    | S  | SL | S
            defer      | delivered | S  | S  | S
            defer      | closed    | S  | S  | S
            renew      | draft     | S  | S  | S
            renew      | open      | S  | S  | S
            renew      | deferred  | S  | S  | S
            renew      | active    | RC | ok | RC
            renew      | lapsed    | S  | SL | S
            renew      | asked     | S  | S  | S
            renew      | waiting   | S  | S  | S
            renew      | blocked   | S  | S  | S
            renew      | parked    | S  | SL | S
            renew      | delivered | S  | S  | S
            renew      | closed    | S  | S  | S
            ask        | draft     | S  | S  | S
            ask        | open      | S  | S  | S
            ask        | deferred  | S  | S  | S
            ask        | active    | RC | ok | RC
            ask        | lapsed    | S  | SL | S
            ask        | asked     | S  | S  | S
            ask        | waiting   | S  | S  | S
            ask        | blocked   | S  | S  | S
            ask        | parked    | S  | SL | S
            ask        | delivered | S  | S  | S
            ask        | closed    | S  | S  | S
            answer     | draft     | S  | S  | S
            answer     | open      | S  | S  | S
            answer     | deferred  | S  | S  | S
            answer     | active    | S  | S  | S
            answer     | lapsed    | S  | S  | S
            answer     | asked     | ok | RA | RA
            answer     | waiting   | CT | CT | CT
            answer     | blocked   | CT | CT | CT
            answer     | parked    | CT | CT | CT
            answer     | delivered | S  | S  | S
            answer     | closed    | S  | S  | S
            hold       | draft     | S  | S  | S
            hold       | open      | S  | S  | S
            hold       | deferred  | S  | S  | S
            hold       | active    | RC | ok | RC
            hold       | lapsed    | S  | SL | S
            hold       | asked     | S  | S  | S
            hold       | waiting   | S  | S  | S
            hold       | blocked   | S  | S  | S
            hold       | parked    | S  | SL | S
            hold       | delivered | S  | S  | S
            hold       | closed    | S  | S  | S
            resume     | draft     | S  | S  | S
            resume     | open      | S  | S  | S
            resume     | deferred  | S  | S  | S
            resume     | active    | S  | S  | S
            resume     | lapsed    | S  | SL | S
            resume     | asked     | CT | CT | CT
            resume     | waiting   | RC | ok | RC
            resume     | blocked   | RC | ok | RC
            resume     | parked    | RC | RL | RC
            resume     | delivered | S  | S  | S
            resume     | closed    | S  | S  | S
            deliver    | draft     | S  | S  | S
            deliver    | open      | S  | S  | S
            deliver    | deferred  | S  | S  | S
            deliver    | active    | RC | ok | RC
            deliver    | lapsed    | S  | SL | S
            deliver    | asked     | S  | S  | S
            deliver    | waiting   | S  | S  | S
            deliver    | blocked   | S  | S  | S
            deliver    | parked    | S  | SL | S
            deliver    | delivered | S  | S  | S
            deliver    | closed    | S  | S  | S
            rework     | draft     | S  | S  | S
            rework     | open      | S  | S  | S
            rework     | deferred  | S  | S  | S
            rework     | active    | S  | S  | S
            rework     | lapsed    | S  | S  | S
            rework     | asked     | S  | S  | S
            rework     | waiting   | S  | S  | S
            rework     | blocked   | S  | S  | S
            rework     | parked    | S  | S  | S
            rework     | delivered | ok | RA | RA
            rework     | closed    | S  | S  | S
            accept     | draft     | S  | S  | S
            accept     | open      | S  | S  | S
            accept     | deferred  | S  | S  | S
            accept     | active    | S  | S  | S
            accept     | lapsed    | S  | S  | S
            accept     | asked     | S  | S  | S
            accept     | waiting   | S  | S  | S
            accept     | blocked   | S  | S  | S
            accept     | parked    | S  | S  | S
            accept     | delivered | ok | RA | RA
            accept     | closed    | S  | S  | S
            reject     | draft     | S  | S  | S
            reject     | open      | RA | ok | ok
            reject     | deferred  | RA | ok | ok
            reject     | active    | S  | S  | S
            reject     | lapsed    | RA | ok | ok
            reject     | asked     | S  | S  | S
            reject     | waiting   | S  | S  | S
            reject     | blocked   | S  | S  | S
            reject     | parked    | S  | S  | S
            reject     | delivered | S  | S  | S
            reject     | closed    | S  | S  | S
            fail       | draft     | S  | S  | S
            fail       | open      | S  | S  | S
            fail       | deferred  | S  | S  | S
            fail       | active    | RC | ok | RC
            fail       | lapsed    | S  | SL | S
            fail       | asked     | RC | ok | RC
            fail       | waiting   | RC | ok | RC
            fail       | blocked   | RC | ok | RC
            fail       | parked    | RC | RL | RC
            fail       | delivered | S  | S  | S
            fail       | closed    | S  | S  | S
            withdraw   | draft     | S  | S  | S
            withdraw   | open      | ok | RA | RA
            withdraw   | deferred  | ok | RA | RA
            withdraw   | active    | ok | RA | RA
            withdraw   | lapsed    | ok | RA | RA
            withdraw   | asked     | ok | RA | RA
            withdraw   | waiting   | ok | RA | RA
            withdraw   | blocked   | ok | RA | RA
            withdraw   | parked    | ok | RA | RA
            withdraw   | delivered | ok | RA | RA
            withdraw   | closed    | S  | S  | S
            """;

    @Test
    void every_row_decides_as_the_target_prescribes_for_every_relation() {
        List<String> wrong = new ArrayList<>();
        int cells = 0;
        for (String line : MATRIX.strip().split("\n")) {
            String[] cell = line.split("\\|");
            TaskVerb verb = verb(cell[0].strip());
            Situation situation = situation(cell[1].strip());
            Map<Actor, String> expected = new LinkedHashMap<>();
            expected.put(C, cell[2].strip());
            expected.put(H, cell[3].strip());
            expected.put(K, cell[4].strip());
            for (Map.Entry<Actor, String> e : expected.entrySet()) {
                cells++;
                String got = code(Decision.of(verb, situation, validCall(verb, e.getKey())));
                if (!got.equals(e.getValue())) {
                    wrong.add(verb.wireName() + " in " + cell[1].strip() + " by "
                        + e.getKey().subject() + ": expected " + e.getValue() + ", got " + got);
                }
                boolean open = Decision.open(verb, situation, e.getKey());
                if (open != "ok".equals(e.getValue())) {
                    wrong.add(verb.wireName() + " in " + cell[1].strip() + " by "
                        + e.getKey().subject() + ": listed as open = " + open);
                }
            }
        }
        assertThat(cells)
            .as("sixteen verbs, eleven situations, three callers")
            .isEqualTo(16 * 11 * 3);
        assertThat(wrong)
            .as("a cell where the kernel decides other than TAR-0004 sections 3 and 5 "
                + "prescribe, or where the list of open calls disagrees with the decision")
            .isEmpty();
    }

    @Test
    void every_row_is_permitted_somewhere_and_refused_somewhere() {
        for (TaskVerb verb : TaskVerb.values()) {
            List<String> lines = MATRIX.strip().lines()
                .filter(l -> l.split("\\|")[0].strip().equals(verb.wireName()))
                .toList();
            assertThat(lines).as("the matrix has a block for %s", verb).hasSize(11);
            assertThat(lines).as("%s is permitted in some situation", verb)
                .anyMatch(l -> l.contains("ok"));
            for (int column = 2; column <= 4; column++) {
                int c = column;
                assertThat(lines).as("%s is refused to caller column %d somewhere", verb, c)
                    .anyMatch(l -> !l.split("\\|")[c].strip().equals("ok"));
            }
        }
    }

    // -----------------------------------------------------------------------

    /** The decision as a matrix code. */
    static String code(Decision decision) {
        if (decision instanceof Decision.Permitted) {
            return "ok";
        }
        Decision.Refused r = (Decision.Refused) decision;
        return switch (r.check()) {
            case STATE -> r.reason() == Reason.LEASE_LAPSED ? "SL"
                : r.reason() == Reason.TRANSITION_NOT_PERMITTED ? "S" : "S?" + r.reason();
            case CONDITION -> r.reason() == Reason.DEFERRAL_PENDING ? "CD"
                : r.reason() == Reason.TRANSITION_NOT_PERMITTED ? "CT" : "C?" + r.reason();
            case RELATION -> switch (r.reason()) {
                case ACTOR_UNKNOWN -> "RA";
                case CLAIM_REQUIRED -> "RC";
                case LEASE_LAPSED -> "RL";
                default -> "R?" + r.reason();
            };
            case LOCK -> r.reason() == Reason.RATIFICATION_NOT_PERMITTED ? "L" : "L?" + r.reason();
            case PROOF, CONFIRMATION, PAYLOAD -> r.check() + ":" + r.reason();
        };
    }

    static TaskVerb verb(String wireName) {
        for (TaskVerb v : TaskVerb.values()) {
            if (v.wireName().equals(wireName)) {
                return v;
            }
        }
        throw new IllegalArgumentException(wireName);
    }

    /** A call presenting everything checks 5 to 7 ask for. */
    static TaskCall validCall(TaskVerb verb, Actor caller) {
        return TaskCall.by(caller).withReceipt(RECEIPT).withConflictToken(TOKEN)
            .with(validPayload(verb));
    }

    static TaskPayload validPayload(TaskVerb verb) {
        return switch (verb) {
            case SEND, CLAIM, CLAIM_NEXT, RELEASE, RENEW, RESUME, ACCEPT, WITHDRAW ->
                TaskPayload.NONE;
            case DEFER -> new TaskPayload.Deferral(NOW.plusSeconds(3600), null);
            case ASK -> new TaskPayload.Question("which one?", List.of("yes", "no"), true);
            case ANSWER -> new TaskPayload.Reply("yes", null);
            case HOLD -> new TaskPayload.Pause(HoldReason.DEPENDENCY, null);
            case DELIVER -> new TaskPayload.Delivery("the answer", null);
            case REWORK -> new TaskPayload.RequiredRemark("once more");
            case REJECT -> new TaskPayload.RequiredRemark("not ours");
            case FAIL -> new TaskPayload.RequiredRemark("it broke");
        };
    }

    /** One of the eleven situations, built directly: what the decision reads. */
    static Situation situation(String name) {
        return switch (name) {
            case "draft" -> of(TaskState.DRAFT, null, null, null, false, null);
            case "open" -> of(TaskState.OPEN, null, null, null, false, null);
            case "deferred" -> of(TaskState.OPEN, null, null, null, false, NOW.plusSeconds(600));
            case "active" -> of(TaskState.ACTIVE, null, H, H, false, null);
            case "lapsed" -> of(TaskState.OPEN, null, null, H, true, null);
            case "asked" -> of(TaskState.ON_HOLD, HoldReason.QUESTION, H, H, false, null);
            case "waiting" -> of(TaskState.ON_HOLD, HoldReason.DEPENDENCY, H, H, false, null);
            case "blocked" -> of(TaskState.ON_HOLD, HoldReason.EXTERNAL, H, H, false, null);
            case "parked" -> of(TaskState.ON_HOLD, HoldReason.EXTERNAL, null, H, true, null);
            case "delivered" -> of(TaskState.DELIVERED, null, H, H, false, null);
            case "closed" -> of(TaskState.CLOSED, null, null, null, false, null);
            default -> throw new IllegalArgumentException(name);
        };
    }

    static Situation of(TaskState state, HoldReason reason, Actor holder, Actor stored,
                        boolean lapsed, Instant notBefore) {
        return new Situation(UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
            ExchangeAddress.bracket("sprint", 1), state, reason,
            holder == null ? null : holder.subject(),
            stored == null ? null : stored.subject(), lapsed,
            stored == null ? null : Receipt.hash(RECEIPT), TOKEN, notBefore,
            reason == HoldReason.QUESTION
                ? Map.of("options", List.of("yes", "no"), "free_text", false)
                : null,
            true, List.of(), NOW);
    }

    /** The check a refusal names; used by the order tests beside this class. */
    static Check checkOf(Decision decision) {
        return decision instanceof Decision.Refused r ? r.check() : null;
    }
}
