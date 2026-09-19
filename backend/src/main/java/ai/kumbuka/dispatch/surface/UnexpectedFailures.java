package ai.kumbuka.dispatch.surface;

import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * The one place an {@code UNEXPECTED_FAILURE} is minted, so that its reference
 * is always in the log.
 *
 * <p>The refusal tells the caller "Report reference {@code <ref>}", and section
 * 4.4 makes that a promise about the service and not only about the message:
 * the reference "is written to the service's log with the failure, on every
 * path that raises it". Measured in review on 2026-09-19 it was not — six throw sites
 * minted a fresh UUID each and one of them logged it, so five references in
 * five different refusals identified nothing at all, and a caller that did what
 * the message asked would have been asking about a number nobody could find.
 *
 * <p>So the mint and the log statement are one act and cannot be separated by
 * anybody adding the seventh throw site. {@link Refused#unexpected} stays
 * public because the probes need to build the envelope without raising a
 * failure; {@code UnmintedReferenceTest} is what keeps production code out
 * of it.
 *
 * <p>What goes in the log is the reference, the call, the address and the
 * cause. Not the caller, not the arguments: this service's logging convention
 * holds here too, and a failure report is not a licence to write a
 * commission's text where a log shipper can carry it out of the container.
 */
public final class UnexpectedFailures {

    private static final Logger LOG = Logger.getLogger(UnexpectedFailures.class);

    private UnexpectedFailures() {
    }

    /**
     * Records the failure and answers the caller with its reference.
     *
     * @param cause what actually went wrong, or null where the failure arrived
     *              as a typed refusal the surface cannot serve. Logged, never
     *              sent: a stack trace is both unreadable to a caller and a
     *              disclosure.
     */
    public static Refused refuse(Surface surface, String call, String address,
                                 Throwable cause) {
        String reference = UUID.randomUUID().toString();
        if (cause == null) {
            LOG.errorf("unexpected failure on %s at %s, reference %s", call, address,
                reference);
        } else {
            LOG.errorf(cause, "unexpected failure on %s at %s, reference %s", call, address,
                reference);
        }
        return Refused.unexpected(surface, call, address, reference);
    }
}
