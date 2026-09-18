package ai.kumbuka.dispatch.surface;

/**
 * How one caller takes part in one exchange.
 *
 * <p>Not a property of the caller and not a property of the exchange, but of
 * the pair: the same console identity is the commissioner of one exchange and
 * a bystander at another, and the same executor is the holder of the exchange
 * it took up and a bystander at the one beside it. Section 6 of the contract
 * reads on exactly this pair, and a value that carried only the caller's
 * capacity could not answer it.
 *
 * <p>Three values and not four. "Commissioner of this exchange" and "console
 * identity" are the same thing today, because the service stores no
 * commissioner subject per exchange — {@code created_by} is written but the
 * roles are decided from the realm role, and narrowing it to the creating
 * subject would be a permission change nobody ratified. That is a finding of
 * this build and is reported rather than quietly decided: when the exchange
 * carries its commissioner, {@link #COMMISSIONER} narrows and this enum does
 * not change.
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
     * {@link #BYSTANDER} again and {@code next} tells it so.
     */
    HOLDER,

    /**
     * Takes no part in this exchange yet.
     *
     * <p>An executor that has not taken it up, or one that holds a different
     * exchange. The only call open to it is {@code dispatch_take}, and only
     * while the exchange is open.
     */
    BYSTANDER
}
