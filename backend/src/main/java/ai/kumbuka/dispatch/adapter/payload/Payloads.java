package ai.kumbuka.dispatch.adapter.payload;

import ai.kumbuka.dispatch.domain.ExchangeView;
import ai.kumbuka.dispatch.domain.HolderState;
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

    /** The update behind a PATCH, or null when no body arrived. */
    public static VerbInput.Update update(UpdateRequest request) {
        return request == null ? null : new VerbInput.Update(
            request.title(), request.apparatus(), request.date(),
            request.draft(), request.receipt(), request.metadata());
    }

    /** The claim behind a takeup, or null when no body arrived. */
    public static VerbInput.Claim claim(ClaimRequest request) {
        return request == null ? null : new VerbInput.Claim(request.duration());
    }

    /** The metadata a send freezes, or null when no body arrived. */
    public static Map<String, Object> metadata(SendRequest request) {
        return request == null ? null : request.metadata();
    }

    /**
     * What a caller supplies to bring an exchange into being.
     *
     * <p>No number, and that is the point: numbers are allocated inside the
     * transaction that inserts the row, never accepted. A caller that could
     * supply one could also collide with one.
     *
     * <p>Metadata values are {@code Object} because a value may be a single
     * identifier or a list of them. The type is not open; the domain's
     * validator refuses anything else.
     */
    public record CreateRequest(
        String title,
        String apparatus,
        LocalDate date,
        Map<String, Object> metadata) {
    }

    /** What a caller supplies to attach an addendum to a frozen exchange. */
    public record AppendRequest(
        String title,
        String apparatus,
        LocalDate date) {
    }

    /**
     * A field write against an exchange, replaced wholesale.
     *
     * <p>One shape for both roles. Before send the write lands in the dispatch
     * role: {@code draft} in {@code body}, {@code metadata} in
     * {@code dispatch_metadata}, and {@code title}, {@code apparatus} and
     * {@code date} override the same-named fields. Non-null fields are the
     * ones that change; null fields are the ones that keep. After send the
     * write lands in the return role: {@code draft} in {@code return_body}
     * and {@code metadata} in {@code return_metadata}. {@code title},
     * {@code apparatus} and {@code date} are refused after send — a frozen
     * field is frozen.
     *
     * <p>The receipt travels in the body rather than in a header because it is
     * an argument of the act and not metadata about the request: the domain
     * refuses a write whose receipt does not match, and a value the domain
     * checks belongs where the domain's other arguments are. Before send there
     * is no holder and the receipt is ignored.
     */
    public record UpdateRequest(
        String title,
        String apparatus,
        LocalDate date,
        String draft,
        String receipt,
        Map<String, Object> metadata) {
    }

    /** Metadata frozen at the send gate, or nothing. */
    public record SendRequest(Map<String, Object> metadata) {
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
     * is what makes a withheld role a missing key rather than a nullable one.
     * A null role is a field a caller reads and finds empty; a missing role
     * is a field that was never offered. The first invites a later change to
     * populate it, the second cannot be read by accident.
     *
     * <p>The two role carriers ({@link #dispatchBody}, {@link #dispatchMetadata},
     * {@link #returnBody}, {@link #returnMetadata}) travel here under the same
     * visibility rule; the domain decides which are populated for this caller,
     * and this class does no second projection.
     *
     * <p>The {@link #conflictToken} travels in the response body because MCP
     * has no header the way REST has {@code ETag}, and a token carried only
     * in the ETag is a token half of the callers cannot see. REST still hands
     * out the same value in the ETag; that duplication is the price of one
     * source for both expositions. Absent for an addendum, which takes no
     * field write and has nothing for a token to protect.
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
        HolderState effectiveHolder,
        Instant claimExpiresAt,
        String dispatchBody,
        Map<String, Object> dispatchMetadata,
        String returnBody,
        Map<String, Object> returnMetadata,
        String conflictToken) {

        /**
         * Built from the view and from nothing else.
         *
         * <p>There is no factory here that takes an exchange. The projection
         * that decides whether each role travels lives in the domain, and a
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
                v.dispatchBody(),
                v.dispatchMetadata(),
                v.returnBody(),
                v.returnMetadata(),
                v.conflictToken());
        }
    }

    /**
     * The head fields of an exchange — everything the full {@link
     * ExchangeResponse} carries EXCEPT the two role carriers and their
     * metadata. What the callers of a transition, a listing, {@code create}
     * or an {@code append} answer see.
     *
     * <p>Whose shape this is, and why it exists as a second record.
     * A transition's answer is "the row is at this state now"; a listing's
     * answer is "here is what matches, decide which one you want to read".
     * Neither needs a body. Serialising the full shape here does not just
     * waste bytes — a caller reading through an LLM finds every response
     * eating the context window, and closing a bracket with several children
     * costs more context than the work it records. The measurement that
     * turned this into a hotfix: a {@code send} then a {@code close} on
     * {@code sprint/175.1} handed back ≈50 000 characters of dispatch text
     * to say "status is now closed".
     *
     * <p>Deliberately NOT a boolean flag on the full shape ("include_body")
     * and NOT a query parameter ({@code ?full=true}). Either would be
     * switched on the first time somebody wanted a body from a transition —
     * exactly the shape the hotfix exists to prevent. What a verb answers
     * with is a property of the verb, not of the call.
     *
     * <p><strong>The {@code conflictToken} still travels.</strong> Without
     * it, {@code update} demands a token that no verb hands out, and the
     * surface would be back where it was on the morning of 2026-09-07 —
     * a token gate no caller can pass. Every response the surface returns
     * carries it, compact or not.
     *
     * <p>The visibility rule (who may see a body at all) is untouched, on
     * purpose: no body travels here, so the projection has nothing to
     * decide about. The rule remains where it is decided — in
     * {@link ai.kumbuka.dispatch.domain.ExchangeView} — for the two verbs
     * that carry bodies, {@code read} and {@code update}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompactExchangeResponse(
        String address,
        String selector,
        int number,
        int sub,
        String title,
        String apparatus,
        LocalDate dispatchDate,
        String status,
        HolderState effectiveHolder,
        Instant claimExpiresAt,
        String conflictToken) {

        /** Built from the view; every carrier field is dropped by construction. */
        public static CompactExchangeResponse of(ExchangeView v) {
            return new CompactExchangeResponse(
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
                v.conflictToken());
        }
    }

    /**
     * What a listing answers with.
     *
     * <p>An object around the list rather than the bare array, so that
     * anything a listing later needs to say about itself — a continuation
     * token above all — is an added key rather than a changed shape.
     *
     * <p>The listing carries the COMPACT projection. A twenty-hit query with
     * ten-thousand-character bodies is not an answer to "what is open"; it
     * is a wall.
     */
    public record Listing(List<CompactExchangeResponse> exchanges) {

        /** The listing as it goes out, from the views the surface answered with. */
        public static Listing of(List<ExchangeView> views) {
            return new Listing(views.stream().map(CompactExchangeResponse::of).toList());
        }
    }

    /**
     * The claimed exchange and the receipt that proves the claim.
     *
     * <p>The receipt is returned here and nowhere else, and it is the only
     * copy — the service stores a hash. A caller that loses it has lost the
     * claim's proof and has to wait for the lease to lapse.
     *
     * <p>Carries the COMPACT exchange projection: a claim is a transition,
     * and every other transition answers compact. The receipt is the one
     * thing this response says that the compact shape does not.
     */
    public record ClaimResponse(CompactExchangeResponse exchange, String receipt) {
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
