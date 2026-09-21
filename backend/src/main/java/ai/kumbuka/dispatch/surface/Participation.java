package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.ExchangeStatus;

/**
 * How one caller takes part in one exchange.
 *
 * <p>Not a property of the caller and not a property of the exchange, but of
 * the pair: the same console identity is the commissioner of one exchange and
 * a bystander at another, and the same executor is the holder of the exchange
 * it took up and a candidate at the open one beside it. Section 6 of the
 * contract reads on exactly this pair, and a value that carried only the
 * caller's capacity could not answer it.
 *
 * <p><strong>Four values, because the contract has four.</strong> An earlier
 * shape had three and folded the candidate into the bystander, which put
 * {@code dispatch_decline} in the wrong place twice: offered to a bystander on
 * an {@code active} exchange, where the call is the holder's and is refused;
 * and withheld from a candidate on an {@code open} one, where it succeeds.
 * Section 2's distinction is not a finer name for the same thing — the
 * candidate is the part that can still take the work, and it is the only part
 * besides the holder that {@code dispatch_decline} admits.
 */
public enum Participation {

    /**
     * Gives and accepts the work. A console identity.
     *
     * <p>May correct, cancel, accept, curate, close a bracket and reply — the
     * whole commissioner column of section 6.
     */
    COMMISSIONER,

    /**
     * Holds this exchange through an effective claim.
     *
     * <p>The claim is judged as it EFFECTIVELY stands, never as the row reads:
     * a lapsed claim holds nothing, so an executor whose lease ran out is a
     * candidate again at an open exchange and a {@link #BYSTANDER} anywhere
     * else, and {@code next} tells it so.
     */
    HOLDER,

    /**
     * Could take this exchange up and has not: an executor, at an open
     * exchange.
     *
     * <p>Decided by the exchange's state and not by anything the caller
     * carries, which is why {@link #of} takes the status. An executor is a
     * candidate at every open exchange it may see, including one whose claim
     * it let lapse — the work is open again and it may take it again.
     */
    CANDIDATE,

    /**
     * Sees the exchange and takes no part in it.
     *
     * <p>An executor at an exchange that is not open and that it does not
     * hold. No call is open to it at all; {@code waiting_for} is the whole of
     * what an answer can tell it.
     */
    BYSTANDER;

    /**
     * The part an executor takes at an exchange in this state.
     *
     * <p>One place, because the candidate/bystander line is drawn by the
     * state alone and a second drawing of it would be a second permission
     * model. The commissioner and the holder are decided before this is
     * reached — both are properties of the caller against the exchange, not
     * of the exchange alone.
     */
    public static Participation of(ExchangeStatus status) {
        return status == ExchangeStatus.OPEN ? CANDIDATE : BYSTANDER;
    }

    /** The part as section 2 names it, for a refusal that states it. */
    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
