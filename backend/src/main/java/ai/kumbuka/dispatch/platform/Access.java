package ai.kumbuka.dispatch.platform;

/**
 * What a call intends to do in the scope it names.
 *
 * <p>The read contract publishes a write right, and a write right is only
 * meaningful against a call that wants to write. So the intent travels into
 * the directory with the scope name rather than being inferred there: the
 * directory knows what the platform permits, and the verb knows what it is
 * about to do. Neither can answer alone.
 *
 * <p>Two values and not three. A verb that reads and then writes is a write
 * for this purpose — the refusal has to arrive before the first effect, and a
 * third value would be a place to put the ones nobody wanted to classify.
 */
public enum Access {

    /** The call only reads. Permitted in a read-only and in a locked scope. */
    READ,

    /**
     * The call writes, or takes a lease that will be written against.
     *
     * <p>A claim counts as a write even though the exchange's fields do not
     * change with it: the lease is state, it is stored, and a caller that
     * could take one in a scope it may not write to would hold a lease it can
     * never use — which reads to everyone else as the scope being in progress.
     */
    WRITE
}
