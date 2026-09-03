package ai.kumbuka.dispatch.adapter.payload;

import ai.kumbuka.dispatch.domain.ExchangeView;
import ai.kumbuka.dispatch.surface.VerbInput;
import com.fasterxml.jackson.annotation.JsonInclude;

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

    // ----------------------------------------------------------------------
    // Wire shape to verb input
    // ----------------------------------------------------------------------
    //
    // The translation runs in this direction and only in this direction. The
    // adapter knows the surface; the surface does not know the adapter, which
    // is what keeps the two out of the import cycle they were in. Every one of
    // these passes null through untouched: a missing body is refused by the
    // surface, after the scope has been resolved, and refusing it here would
    // move that answer in front of a check the check order puts first.

    /** The draft behind a create, or null when no body arrived. */
    public static VerbInput.Draft draft(CreateRequest request) {
        return request == null ? null : new VerbInput.Draft(
            request.title(), request.apparatus(), request.date(), request.metadata());
    }

    /** The addendum behind an append, or null when no body arrived. */
    public static VerbInput.Addendum addendum(AppendRequest request) {
        return request == null ? null : new VerbInput.Addendum(
            request.title(), request.apparatus(), request.date());
    }

    /** The handover behind an update, or null when no body arrived. */
    public static VerbInput.Handover handover(UpdateRequest request) {
        return request == null ? null : new VerbInput.Handover(
            request.draft(), request.receipt(), request.metadata());
    }

    /** The claim behind a takeup, or null when no body arrived. */
    public static VerbInput.Claim claim(ClaimRequest request) {
        return request == null ? null : new VerbInput.Claim(request.duration());
    }

    /** The metadata a send freezes, or null when no body arrived. */
    public static Map<String, String> metadata(SendRequest request) {
        return request == null ? null : request.metadata();
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
     *
     * <p>The text is carried and not parsed. Parsing it here would refuse a
     * malformed duration before the scope has been resolved, which is a
     * different answer than the ratified check order gives; the surface parses
     * it in its own position instead.
     */
    public record ClaimRequest(String duration) {
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
     * What a listing answers with.
     *
     * <p>An object around the list rather than the bare array, so that
     * anything a listing later needs to say about itself — a continuation
     * token above all — is an added key rather than a changed shape.
     *
     * <p>This used to be the surface's own record, serialised directly. It is
     * a wire shape and belongs here; what the surface answers with is the list
     * of views, and the key name is unchanged so no caller can tell.
     */
    public record Listing(List<ExchangeResponse> exchanges) {

        /** The listing as it goes out, from the views the surface answered with. */
        public static Listing of(List<ExchangeView> views) {
            return new Listing(views.stream().map(ExchangeResponse::of).toList());
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
