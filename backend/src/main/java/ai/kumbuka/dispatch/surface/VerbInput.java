package ai.kumbuka.dispatch.surface;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

/**
 * What the verb surface accepts, in a form that names no protocol.
 *
 * <p>These shapes exist because the surface must not import the adapter's wire
 * types. It did, until this was written, and the adapter payloads imported the
 * surface's refusal in return — an import cycle between a layer and the layer
 * it is supposed to be independent of. The cycle was not a lapse of care: the
 * surface was carved out of the adapter package and kept the adapter's types
 * on the way out.
 *
 * <p>They are deliberately NOT the wire shapes renamed. A wire shape answers
 * to a published contract and changes when that contract does; these answer to
 * the verbs. Today most fields coincide, and that is fine — what matters is
 * that a change to one is not automatically a change to the other.
 *
 * <h2>Why the null check is not here</h2>
 *
 * A missing body is refused by the surface, after the scope has been resolved,
 * because the ratified check order answers "not found" for a scope the caller
 * may not see before it says anything about the body. So an adapter hands over
 * {@code null} rather than refusing early, and every record here is nullable at
 * the call site by design.
 */
public final class VerbInput {

    private VerbInput() {
    }

    /**
     * What brings an exchange into being.
     *
     * <p>No number and no actor. Numbers are allocated inside the transaction
     * that inserts the row and authorship is derived from the token; a field
     * for either would be a field the server has to ignore.
     */
    public record Draft(
        String title,
        String apparatus,
        LocalDate date,
        Map<String, String> metadata) {
    }

    /** What attaches an addendum to an exchange that has already been frozen. */
    public record Addendum(
        String title,
        String apparatus,
        LocalDate date) {
    }

    /** A handover draft, replaced wholesale, with the receipt that authorises it. */
    public record Handover(
        String draft,
        String receipt,
        Map<String, String> metadata) {
    }

    /**
     * How long a claim should stand, still in its text form.
     *
     * <p>The text is parsed here and not at the adapter, so that a malformed
     * duration is refused in the same position in the check order as every
     * other body fault. Parsing it one layer up would move that refusal in
     * front of the scope resolution, and the caller would learn that a scope
     * they may not see exists by the shape of the error they got.
     */
    public record Claim(String duration) {

        /** @throws SurfaceException when the value is absent or not a duration */
        public Duration parsed() {
            if (duration == null || duration.isBlank()) {
                throw new SurfaceException(SurfaceException.Reason.PAYLOAD_MALFORMED,
                    "a claim names how long it stands, as an ISO-8601 duration such as "
                        + "'PT1H'. There is no default: a lease length is a policy, and one "
                        + "invented here would be a policy nobody ratified.");
            }
            try {
                return Duration.parse(duration);
            } catch (java.time.format.DateTimeParseException e) {
                throw new SurfaceException(SurfaceException.Reason.PAYLOAD_MALFORMED,
                    "'" + duration + "' is not an ISO-8601 duration. 'PT1H', 'PT30M', 'P1D'.");
            }
        }
    }
}
