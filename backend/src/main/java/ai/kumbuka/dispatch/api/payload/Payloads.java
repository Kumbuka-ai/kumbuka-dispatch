package ai.kumbuka.dispatch.api.payload;

import ai.kumbuka.dispatch.api.SurfaceException;
import ai.kumbuka.dispatch.domain.ExchangeView;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The wire shapes of the verb surface.
 *
 * <p>They are records in one file rather than a package of classes because
 * they are one contract: the payload contracts behind the verbs are the named
 * gap in the specification, and scattering a provisional shape across a dozen
 * files makes it look more settled than it is. When the shapes are specified,
 * this file is what gets replaced.
 *
 * <p><strong>No shape here carries an actor.</strong> Authorship is derived
 * from the token and never accepted from a caller, so a field for it would be
 * a field the server has to ignore — and a field the server ignores is one a
 * client will eventually rely on.
 */
public final class Payloads {

    private Payloads() {
    }

    /**
     * What a caller supplies to bring an exchange into being.
     *
     * <p>No number, and that is the point: numbers are allocated inside the
     * transaction that inserts the row, never accepted. A caller that could
     * supply one could also collide with one.
     */
    public record CreateRequest(
        String title,
        String apparatus,
        LocalDate date,
        Map<String, String> metadata) {
    }

    /** What a caller supplies to attach an addendum to a frozen exchange. */
    public record AppendRequest(
        String title,
        String apparatus,
        LocalDate date) {
    }

    /**
     * A handover draft, replaced wholesale.
     *
     * <p>The receipt travels in the body rather than in a header because it is
     * an argument of the act and not metadata about the request: the domain
     * refuses a write whose receipt does not match, and a value the domain
     * checks belongs where the domain's other arguments are.
     */
    public record UpdateRequest(
        String draft,
        String receipt,
        Map<String, String> metadata) {
    }

    /** Metadata frozen at the send gate, or nothing. */
    public record SendRequest(Map<String, String> metadata) {
    }

    /**
     * How long the claim should stand, as an ISO-8601 duration.
     *
     * <p>Required rather than defaulted. A default lease length is a policy,
     * and a policy invented at the adapter is one nobody ratified.
     */
    public record ClaimRequest(String duration) {

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

    /**
     * What a caller sees of an exchange.
     *
     * <p><strong>Absent fields are absent, not empty.</strong> {@code NON_NULL}
     * is what makes the withheld body a missing key rather than
     * {@code "body": null} — and the difference is the whole guarantee. A null
     * body is a field a caller reads and finds empty; a missing body is a
     * field that was never offered. The first invites a later change to
     * populate it, the second cannot be read by accident.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExchangeResponse(
        String address,
        String selector,
        int number,
        int sub,
        String title,
        String apparatus,
        LocalDate dispatchDate,
        String status,
        String effectiveHolder,
        Instant claimExpiresAt,
        String body) {

        /**
         * Built from the view and from nothing else.
         *
         * <p>There is no factory here that takes an exchange. The projection
         * that decides whether the body travels lives in the domain, and a
         * second construction path would be a second place for it to be
         * decided — which is how a bolt becomes a convention.
         */
        public static ExchangeResponse of(ExchangeView v) {
            return new ExchangeResponse(
                v.address(),
                v.selector(),
                v.number(),
                v.sub(),
                v.title(),
                v.apparatus(),
                v.dispatchDate(),
                v.status().wireName(),
                v.effectiveHolder(),
                v.claimExpiresAt(),
                v.body());
        }
    }

    /**
     * The claimed exchange and the receipt that proves the claim.
     *
     * <p>The receipt is returned here and nowhere else, and it is the only
     * copy — the service stores a hash. A caller that loses it has lost the
     * claim's proof and has to wait for the lease to lapse.
     */
    public record ClaimResponse(ExchangeResponse exchange, String receipt) {
    }

    /**
     * A refusal, in the shape a caller can act on.
     *
     * <p>The reason is the stable part and the message is for a human. A
     * caller matching on prose breaks when somebody improves the wording,
     * which is why every refusal in this service carries a typed reason and
     * why that reason travels on the wire.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Refusal(String reason, String message, List<String> offenders) {

        public static Refusal of(String reason, String message) {
            return new Refusal(reason, message, List.of());
        }
    }
}
