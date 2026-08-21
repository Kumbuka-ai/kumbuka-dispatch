package ai.kumbuka.dispatch.domain;

import java.util.Set;

/**
 * The one status an exchange carries. The handover has no status machine of
 * its own.
 *
 * <p>Six values are the ordinary path. Three more — {@link #REJECTED},
 * {@link #FAILED}, {@link #NEEDS_INPUT} — exist for the exchanges that never
 * reach an answer, and they are not a convenience. Without them an executor
 * who cannot deliver has two options: leave the exchange lying, or terminate
 * it. An exchange left lying is indistinguishable in the store from one being
 * worked, which is the same defect one level up as a row marked active with
 * nobody working it.
 *
 * <p>{@code REJECTED} and {@code FAILED} are kept apart because the
 * distinction is the useful one: a refusal says the commission was wrong, a
 * failure says the work was. Each is reachable from exactly one predecessor,
 * so the value also records how far the exchange got before it stopped.
 *
 * <p>{@code NEEDS_INPUT} is a pause and not an end — the holder keeps the
 * exchange and the commissioner is due — which is why it is deliberately not
 * terminal. Neither is {@link #RETURNED}: an answered but uncurated exchange
 * is not finished.
 */
public enum ExchangeStatus {

    /** Created, not committed to. Fully mutable and hard-deletable. */
    DRAFT("draft"),
    /** The dispatch is frozen and awaits an executor. */
    OPEN("open"),
    /** Taken up. A holder holds it. */
    ACTIVE("active"),
    /** The handover is ratified and frozen; the exchange awaits curation. */
    RETURNED("returned"),
    /** Terminal, administratively closed. */
    CLOSED("closed"),
    /** Terminal, curated forward into a named object. */
    CONSUMED("consumed"),
    /** Terminal. The executor refuses the commission. Reachable only from OPEN. */
    REJECTED("rejected"),
    /** Terminal. The executor took it up and could not complete it. Only from ACTIVE. */
    FAILED("failed"),
    /** Not terminal. The executor is blocked and requires the commissioner. */
    NEEDS_INPUT("needs_input");

    private static final Set<ExchangeStatus> TERMINAL =
        Set.of(CLOSED, CONSUMED, REJECTED, FAILED);

    private final String wireName;

    ExchangeStatus(String wireName) {
        this.wireName = wireName;
    }

    /** The value as it is stored. Lower case, and stable across renames of the constant. */
    public String wireName() {
        return wireName;
    }

    /**
     * Whether this status ends the exchange.
     *
     * <p>Read by the bracket's closability check: a bracket closes through the
     * termination of its {@code .0}, and that termination refuses while any
     * sibling is non-terminal.
     */
    public boolean terminal() {
        return TERMINAL.contains(this);
    }

    public static ExchangeStatus fromWireName(String value) {
        for (ExchangeStatus s : values()) {
            if (s.wireName.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown exchange status: " + value);
    }
}
