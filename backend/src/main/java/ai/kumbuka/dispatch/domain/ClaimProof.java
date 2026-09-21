package ai.kumbuka.dispatch.domain;

/**
 * Whether the caller presented the receipt its take issued, as a type rather
 * than as a null.
 *
 * <p>{@code dispatch_decline} is the one call whose meaning turns on the
 * absence: a caller that never took the exchange up refuses a commission, and
 * a caller that holds it records a failure to complete and must prove it holds
 * it. The absence is therefore load-bearing, and it travelled into the domain
 * as {@code null} — a value that carries meaning and announces none, so every
 * reader of the parameter had to know the convention to read the call.
 *
 * <p>Sealed, so the domain's handling of it is a switch the compiler completes.
 * Two cases and no third: there is no "maybe", because the surface has either
 * received the argument or it has not.
 */
public sealed interface ClaimProof {

    /** The caller presented a receipt. Whether it is the right one is checked. */
    record Presented(String receipt) implements ClaimProof {
    }

    /** The caller presented none, and says by that it never took the exchange up. */
    record NotTakenUp() implements ClaimProof {
    }

    /** The one instance of the absent case; it carries nothing to distinguish. */
    ClaimProof NONE = new NotTakenUp();

    /**
     * The proof an argument carries, absent or present.
     *
     * <p>The one place a null becomes a type, at the surface boundary where
     * the null comes from — an argument that did not arrive. Below this point
     * the domain never sees one.
     */
    static ClaimProof of(String receipt) {
        return receipt == null || receipt.isBlank() ? NONE : new Presented(receipt);
    }

    /** Whether a receipt arrived at all. */
    default boolean isPresent() {
        return this instanceof Presented;
    }
}
