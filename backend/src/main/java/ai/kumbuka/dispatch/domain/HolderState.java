package ai.kumbuka.dispatch.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What the caller needs to know about who holds an exchange.
 *
 * <p>Three states, deliberately, and none of them names a person. The rule is
 * a standing invariant of the platform: a holder in a single lease is
 * harmless, a holder in a hundred query results is the beginning of a list of
 * who works on what, and per-member evaluation is structurally not to be
 * offered. Reduced to the answer a caller can act on: does anybody hold it,
 * and is that this caller.
 *
 * <p>The wire names are lower case and stable across renames of the constant,
 * for the same reason {@link ExchangeStatus} carries a {@code wireName}: the
 * value on the wire is a contract, and the Java identifier is a name for
 * readers of the code.
 */
public enum HolderState {

    /** No claim currently effective — free for takeup. */
    NOBODY("nobody"),
    /** The caller holds the exchange. */
    SELF("self"),
    /** Somebody else holds it. Who exactly is deliberately withheld. */
    OTHER("other");

    private final String wireName;

    HolderState(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    /**
     * The state as it presents to the caller behind {@code actor}, given the
     * effective holder subject at {@code now} (null when no claim stands).
     */
    public static HolderState of(String effectiveHolderSubject, Actor actor) {
        if (effectiveHolderSubject == null) {
            return NOBODY;
        }
        return effectiveHolderSubject.equals(actor.subject()) ? SELF : OTHER;
    }
}
