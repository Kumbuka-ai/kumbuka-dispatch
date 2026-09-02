package ai.kumbuka.dispatch.adapter.rest;

import ai.kumbuka.dispatch.surface.SurfaceException;

import ai.kumbuka.dispatch.adapter.payload.Payloads;
import ai.kumbuka.dispatch.domain.DispatchException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Turns the two families of typed refusal into HTTP, and keeps them apart.
 *
 * <h2>The status is not a judgement made here</h2>
 *
 * For a surface refusal the status travels on the reason itself. For a domain
 * refusal it is decided by the table below — one entry per reason, no default
 * branch that swallows a new one. A {@code switch} over an enum with no
 * default is what makes an added reason a compile error rather than a silent
 * 500, and this is exactly the place where a silent 500 would be indefensible:
 * the reasons are the published contract of the surface.
 *
 * <h2>Two statuses that look wrong and are not</h2>
 *
 * {@code SCOPE_UNRESOLVED} answers <strong>404</strong> rather than 403. The
 * directory answers for the bound subject only and existence in its answer IS
 * the permission, so a 403 would confirm that a scope exists to a caller who
 * may not see it — turning the error path into a scope enumerator. This is the
 * standing rule "404 and never 403", applied where it was always meant to
 * apply.
 *
 * <p>{@code RATIFICATION_NOT_PERMITTED} answers <strong>403</strong> and not
 * 409. The transition is permitted from this state — just not to this caller —
 * and a caller that could not tell the two apart would read "not yet" where the
 * truth is "not you, ever", and would retry forever.
 */
@Provider
public class RefusalMapper implements ExceptionMapper<SurfaceException> {

    /**
     * The one place every refusal that reaches a caller passes through.
     *
     * <p>Logging here rather than at the twenty-eight throw sites is not
     * tidiness: twenty-eight sites are twenty-eight chances to do it
     * differently, and the one that gets forgotten is the one somebody needed.
     *
     * <p><strong>DEBUG and not WARN, deliberately.</strong> A malformed
     * address arrives on every client typo, and a refusal log at WARN would
     * make this service's operational log writable by whoever calls it —
     * flood the surface with broken addresses and you fill the log. What
     * belongs at WARN is a statement about the deployment, and those already
     * live where they are decided: the domain warns on a refused
     * ratification, a refused selector and an unresolved scope, and
     * {@link CallerActor} warns on a token whose realm roles are wrong. This
     * line is the trace inside a verb, which is what DEBUG is for here.
     *
     * <p>The actor is absent, as everywhere in this service. Correlation runs
     * through a request id; a second aggregatable record of who was refused
     * what is how not-collecting-behavioural-data gets circumvented without
     * anybody deciding to.
     */
    private static final Logger LOG = Logger.getLogger(RefusalMapper.class);

    /**
     * Mapped per exception type rather than over {@code RuntimeException}.
     *
     * <p>A mapper registered for the supertype is chosen for every runtime
     * exception the framework raises too — the 405 a wrong method produces,
     * the 415 a missing content type produces — and re-throwing them from
     * inside a mapper turns each into a 500. The framework's own refusals are
     * part of this surface's contract, so they must reach the caller as
     * themselves.
     */
    @Override
    public Response toResponse(SurfaceException e) {
        LOG.debugf("surface refusal: %s -> %d", e.reason().name(), e.reason().status());

        Response.ResponseBuilder response = Response.status(e.reason().status())
            .type(MediaType.APPLICATION_JSON)
            .entity(Payloads.Refusal.of(e.reason().name(), e.getMessage()));

        if (e.allow() != null) {
            // A 405 without Allow refuses without saying what would have
            // worked, which is the one thing the status is required to carry.
            response.header(HttpHeaders.ALLOW, e.allow());
        }
        return response.build();
    }

    /** The domain's refusals, on the same shape and the same discipline. */
    @Provider
    public static class Domain implements ExceptionMapper<DispatchException> {

        @Override
        public Response toResponse(DispatchException e) {
            int status = statusOf(e.reason());

            // A 5xx is ours, not the caller's, and no retry of theirs fixes
            // it. That is the one refusal class this surface raises to ERROR:
            // everything else is a caller being told no, which is the surface
            // working.
            if (status >= 500) {
                LOG.errorf("domain refusal answered %d: %s", status, e.reason().name());
            } else {
                LOG.debugf("domain refusal: %s -> %d", e.reason().name(), status);
            }

            return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(new Payloads.Refusal(e.reason().name(), e.getMessage(), e.offenders()))
                .build();
        }

        /**
         * One status per domain reason.
         *
         * <p>No {@code default}. A reason added to the domain must be given a
         * status here, and the compiler is what asks for it — the alternative is a
         * new refusal quietly becoming a 500 in a deployment nobody is watching.
         */
        private static int statusOf(DispatchException.Reason reason) {
            return switch (reason) {
                // The call is malformed, and no scope had to be known to say so.
                case ADDENDUM_MALFORMED, NUMBER_NOT_ACCEPTED, HOLDER_NOT_ACCEPTED,
                     CLAIM_DURATION_NOT_POSITIVE -> 400;

                // Not this caller. Ever, or with this proof.
                case RATIFICATION_NOT_PERMITTED, RECEIPT_MISMATCH -> 403;
                case ACTOR_UNKNOWN -> 403;

                // Nothing there — or nothing this subject may know is there.
                case NOT_FOUND, SCOPE_UNRESOLVED -> 404;

                // The object is real and its state says no. NOTHING_TO_CLAIM is
                // the same shape with a set in place of the object: the selector
                // is there and nothing in it is free right now, which a caller
                // waits on rather than re-addresses. Never 404 — that would say
                // the selector is missing and send it looking for a typo.
                case TRANSITION_NOT_PERMITTED, FROZEN, SIBLINGS_NON_TERMINAL, SELECTOR_IN_USE,
                     ADDENDUM_SUFFIX_EXHAUSTED, CLAIM_REQUIRED, HANDOVER_ALREADY_RATIFIED,
                     NOTHING_TO_CLAIM -> 409;

                // Vocabulary: well-formed, addressed at something this scope does
                // not have, or carrying content the scope refuses. A filter field
                // this scheme does not carry and a value a field cannot take are
                // both vocabulary — the call parses, and it names something that
                // is not part of the offering.
                case SELECTOR_NOT_DECLARED, SELECTOR_WITHDRAWN, ADDENDUM_NOT_DRAWABLE,
                     METADATA_REFUSED, FILTER_FIELD_UNKNOWN, FILTER_VALUE_REFUSED -> 422;

                // Ours, not the caller's: the session contract was not bound, and
                // no retry of theirs will fix it.
                case SESSION_NOT_BOUND -> 500;
            };
        }
    }

}
