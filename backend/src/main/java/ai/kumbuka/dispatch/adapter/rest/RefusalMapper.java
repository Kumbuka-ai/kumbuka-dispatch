package ai.kumbuka.dispatch.adapter.rest;

import ai.kumbuka.dispatch.adapter.payload.Answers;
import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.surface.ReasonMapping;
import ai.kumbuka.dispatch.surface.RefusalCode;
import ai.kumbuka.dispatch.surface.Refused;
import ai.kumbuka.dispatch.surface.SurfaceException;
import ai.kumbuka.dispatch.surface.UnexpectedFailures;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * The HTTP status of every refusal, and the refusals that arise outside a call.
 *
 * <p>A call's refusal is built by {@code CallRouter} and answered by {@link
 * TaskResource} with the status this table gives its reason: one entry per
 * reason, a {@code switch} with no default, so an added reason is a compile
 * error rather than a silent 500.
 *
 * <p>What arrives here as an exception was raised before a call was made —
 * the tenant binding of the request, a resource other than the verb surface —
 * and is answered in the same envelope with what can be said without a call.
 */
@Provider
public class RefusalMapper implements ExceptionMapper<Refused> {

    private static final Logger LOG = Logger.getLogger(RefusalMapper.class);

    @Override
    public Response toResponse(Refused refused) {
        return answer(refused);
    }

    /** The status of one reason on this surface. */
    public static int statusOf(RefusalCode code) {
        return switch (code) {
            case ARGUMENT_UNKNOWN, ARGUMENT_MISSING, ARGUMENT_INVALID,
                 CLAIM_DURATION_INVALID -> 400;
            case ROLE_DOES_NOT_ALLOW, NOT_THE_HOLDER, RECEIPT_WRONG, SCOPE_READ_ONLY -> 403;
            case NOT_FOUND -> 404;
            case CALL_NOT_AT_THIS_ADDRESS -> 405;
            case STATE_DOES_NOT_ALLOW, CHILDREN_NOT_FINISHED, DEFERRAL_PENDING,
                 NOTHING_TO_TAKE, IDEMPOTENCY_KEY_REUSED, SCOPE_LOCKED -> 409;
            case CONFLICT_TOKEN_STALE -> 412;
            case SELECTOR_UNKNOWN, SCOPE_KIND_UNSUPPORTED -> 422;
            case UNEXPECTED_FAILURE -> 500;
        };
    }

    private static Response answer(Refused refused) {
        return Response.status(statusOf(refused.code()))
            .type(MediaType.APPLICATION_JSON)
            .entity(Answers.envelope(refused))
            .build();
    }

    /** A kernel refusal raised outside a call: the not-found class, or ours. */
    @Provider
    public static class Domain implements ExceptionMapper<DispatchException> {

        @Override
        public Response toResponse(DispatchException e) {
            RefusalCode code = ReasonMapping.of(e.reason());
            LOG.debugf("refusal outside a call: %s", e.reason());
            return answer(code == RefusalCode.NOT_FOUND
                ? Refused.notFound()
                : UnexpectedFailures.refuse("this request", "the address given", e));
        }
    }

    /** A malformed address raised outside a call. */
    @Provider
    public static class Form implements ExceptionMapper<SurfaceException> {

        @Override
        public Response toResponse(SurfaceException e) {
            return answer(Refused.argumentInvalid("this request", "address",
                "the address given", e.getMessage()));
        }
    }
}
