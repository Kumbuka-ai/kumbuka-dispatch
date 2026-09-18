package ai.kumbuka.dispatch.adapter.rest;

import ai.kumbuka.dispatch.adapter.payload.Payloads;
import ai.kumbuka.dispatch.surface.NextCalculator;
import ai.kumbuka.dispatch.surface.Participation;
import ai.kumbuka.dispatch.surface.RefusalCode;
import ai.kumbuka.dispatch.surface.ReasonMapping;
import ai.kumbuka.dispatch.surface.Refused;
import ai.kumbuka.dispatch.surface.Surface;
import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.surface.AddressParser;
import ai.kumbuka.dispatch.surface.CallerActor;
import ai.kumbuka.dispatch.surface.SurfaceException;
import ai.kumbuka.dispatch.surface.VerbSurface;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns the two families of typed refusal into HTTP, in the shape section 4
 * fixes.
 *
 * <h2>Same shape, different vocabulary</h2>
 *
 * The body is the refusal envelope the assistant surface answers with —
 * {@code reason}, {@code message}, {@code data} — because the shape is the
 * contract's and not a surface's. What differs is the vocabulary: the message
 * names the REST verb the caller made ({@code accept}, {@code close}) and
 * {@code data.next} lists REST verbs, because a caller on this surface told to
 * call {@code dispatch_accept_return} has been told to call something that
 * does not exist for it.
 *
 * <h2>The status is not a judgement made here</h2>
 *
 * For a surface refusal the status travels on the reason itself. For a domain
 * refusal it is decided by the table below — one entry per reason, no default
 * branch that swallows a new one. A {@code switch} over an enum with no
 * default is what makes an added reason a compile error rather than a silent
 * 500.
 *
 * <h2>Two statuses that look wrong and are not</h2>
 *
 * {@code SCOPE_UNRESOLVED} answers <strong>404</strong> rather than 403. The
 * directory answers for the bound subject only and existence in its answer IS
 * the permission, so a 403 would confirm that a scope exists to a caller who
 * may not see it — turning the error path into a scope enumerator.
 *
 * <p>{@code RATIFICATION_NOT_PERMITTED} answers <strong>403</strong> and not
 * 409. The transition is permitted from this state — just not to this caller —
 * and a caller that could not tell the two apart would read "not yet" where
 * the truth is "not you, ever", and would retry forever.
 */
@Provider
public class RefusalMapper implements ExceptionMapper<SurfaceException> {

    /**
     * The one place every surface refusal that reaches a caller passes through.
     *
     * <p><strong>DEBUG and not WARN, deliberately.</strong> A malformed address
     * arrives on every client typo, and a refusal log at WARN would make this
     * service's operational log writable by whoever calls it. What belongs at
     * WARN is a statement about the deployment, and those live where they are
     * decided.
     *
     * <p>The actor is absent, as everywhere in this service. Correlation runs
     * through a request id; a second aggregatable record of who was refused
     * what is how not-collecting-behavioural-data gets circumvented without
     * anybody deciding to.
     */
    private static final Logger LOG = Logger.getLogger(RefusalMapper.class);

    @Inject CallScope calling;

    @Override
    public Response toResponse(SurfaceException e) {
        LOG.debugf("surface refusal: %s -> %d", e.reason().name(), e.reason().status());

        String call = calling.call("this call");
        String address = calling.address() == null ? "the address given" : calling.address();

        Refused refused = switch (e.reason()) {
            case ADDRESS_MALFORMED, PAYLOAD_MALFORMED ->
                Refused.argumentInvalid(call, "address", address, e.getMessage());
            case CLAIM_DURATION_MALFORMED ->
                Refused.claimDurationInvalid(call, "the one given");
            case CONFLICT_TOKEN_MISSING ->
                Refused.conflictToken(RefusalCode.CONFLICT_TOKEN_MISSING, call, address);
            case CONFLICT_TOKEN_STALE ->
                Refused.conflictToken(RefusalCode.CONFLICT_TOKEN_STALE, call, address);
            // The acts this scheme withholds. They are real refusals here —
            // unlike on the assistant surface, where no process verb reaches
            // them — so they keep their own sentence and are told as a state
            // the surface does not offer rather than as a defect.
            case VERB_NOT_CARRIED, VERB_DEPTH_UNDECLARED, WITHDRAWAL_VIA_CONSOLE_ONLY,
                 WRITE_ON_TRUNCATED_ADDRESS ->
                Refused.argumentInvalid(call, "verb", call, e.getMessage());
        };

        Response.ResponseBuilder response = Response.status(e.reason().status())
            .type(MediaType.APPLICATION_JSON)
            .entity(envelope(refused));

        if (e.allow() != null) {
            // A 405 without Allow refuses without saying what would have
            // worked, which is the one thing the status is required to carry.
            response.header(HttpHeaders.ALLOW, e.allow());
        }
        return response.build();
    }

    static Payloads.RefusalEnvelope envelope(Refused refused) {
        return new Payloads.RefusalEnvelope(refused.code().name(), refused.getMessage(),
            refused.data());
    }

    /** The domain's refusals, on the same shape and the same discipline. */
    @Provider
    public static class Domain implements ExceptionMapper<DispatchException> {

        @Inject CallScope calling;
        @Inject VerbSurface verbs;
        @Inject CallerActor caller;

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
                .entity(envelope(dress(e)))
                .build();
        }

        /**
         * The kernel's refusal, worded for this caller on this surface.
         *
         * <p>The kernel's own message is discarded rather than decorated. It
         * names kernel transitions and states the way IN; the catalogue's
         * pattern names the caller's verb and the way OUT.
         */
        private Refused dress(DispatchException e) {
            String call = calling.call("this call");
            String address = calling.address();
            RefusalCode code = ReasonMapping.of(e.reason());

            if (code == RefusalCode.NOT_FOUND) {
                return Refused.notFound();
            }
            if (code == RefusalCode.UNEXPECTED_FAILURE) {
                return Refused.unexpected(call, named(address),
                    UUID.randomUUID().toString());
            }

            // The form faults, before any attempt to read a state. Several of
            // them arrive on a call that addresses no exchange — a listing with
            // a filter this scheme does not carry — where a state lookup finds
            // nothing and the refusal would fall through to something about an
            // exchange the caller never named.
            if (code == RefusalCode.ARGUMENT_UNKNOWN) {
                return Refused.argumentUnknown(call, subjectOf(e),
                    List.of("see " + AddressParser.SCHEME + "'s declaration"));
            }

            Situation situation = situationOf(address);
            if (code == RefusalCode.CHILDREN_NOT_FINISHED) {
                return children(e, call, named(address), situation);
            }
            if (situation == null) {
                // No readable exchange behind the refusal: a collection-level
                // act, or one the caller may not see. Both answer without a
                // state, and the second must, because a state would tell the
                // caller the exchange exists.
                return withoutState(code, call, named(address), e);
            }

            return switch (code) {
                case STATE_DOES_NOT_ALLOW -> Refused.ofState(call, situation.address(),
                    situation.state(), situation.terminal(), situation.next());
                case ROLE_DOES_NOT_ALLOW -> Refused.ofRole(call, situation.address(),
                    "commissioner", Participation.BYSTANDER, situation.state(),
                    situation.next());
                case NOT_THE_HOLDER -> Refused.notTheHolder(call, situation.address(),
                    "its claim lapses", "make that call on it", situation.state(),
                    situation.next());
                case RECEIPT_MISSING, RECEIPT_WRONG -> Refused.receipt(code, call,
                    situation.address(), situation.state(), situation.next());
                case NO_ANSWER_DELIVERED -> Refused.noAnswerDelivered(call,
                    situation.address(), situation.state(), situation.next());
                default -> withoutState(code, call, situation.address(), e);
            };
        }

        /**
         * The form faults and the collection-level refusals: no state travels.
         *
         * <p>{@code ROLE_DOES_NOT_ALLOW} is here as well as in the dressed
         * switch, and that is not duplication. A role refusal can arrive with
         * no readable exchange behind it — a token carrying neither capacity
         * cannot read anything, so {@code situationOf} finds nothing — and
         * falling through to the default would tell that caller its call was a
         * DEFECT. It is not: it is the one refusal the caller can act on by
         * presenting a different token.
         */
        private static Refused withoutState(RefusalCode code, String call, String address,
                                            DispatchException e) {
            return switch (code) {
                case ROLE_DOES_NOT_ALLOW -> Refused.ofRole(call, address, "commissioner",
                    Participation.BYSTANDER, "not visible to you", List.of());
                case NOT_THE_HOLDER -> Refused.notTheHolder(call, address,
                    "its claim lapses", "make that call on it", "not visible to you",
                    List.of());
                case RECEIPT_MISSING, RECEIPT_WRONG -> Refused.receipt(code, call, address,
                    "not visible to you", List.of());
                case STATE_DOES_NOT_ALLOW -> Refused.ofState(call, address,
                    "not visible to you", false, List.of());
                case NO_ANSWER_DELIVERED -> Refused.noAnswerDelivered(call, address,
                    "not visible to you", List.of());
                case NOTHING_TO_TAKE -> Refused.nothingToTake(call, address);
                case CLAIM_DURATION_INVALID -> Refused.claimDurationInvalid(call,
                    "the duration given");
                case SELECTOR_UNKNOWN -> Refused.selectorUnknown(call, "the one given",
                    "this scope", List.of());
                case ARGUMENT_UNKNOWN -> Refused.argumentUnknown(call, "the one given",
                    List.of("see the service's declaration"));
                case ARGUMENT_MISSING -> Refused.argumentMissing(call, "a required value",
                    e.getMessage());
                case ARGUMENT_INVALID -> Refused.argumentInvalid(call, "a value given",
                    "the one sent", e.getMessage());
                default -> Refused.unexpected(call, address, UUID.randomUUID().toString());
            };
        }

        /** The blocking children, re-read so each carries a complete address. */
        private Refused children(DispatchException e, String call, String root,
                                 Situation situation) {
            List<Map<String, Object>> offenders = new ArrayList<>();
            for (String shortForm : e.offenders()) {
                String bare = shortForm.contains(" ")
                    ? shortForm.substring(0, shortForm.indexOf(' '))
                    : shortForm;

                Map<String, Object> named = new LinkedHashMap<>();
                named.put("address", completeSiblingOf(root, bare));
                named.put("state", stateIn(shortForm));
                named.put("next", List.of());
                offenders.add(Map.copyOf(named));
            }
            return Refused.childrenNotFinished(call, root, offenders,
                situation == null ? "unknown" : situation.state(),
                situation == null ? List.of() : situation.next());
        }

        /**
         * Re-reads the exchange a refused call was addressed at.
         *
         * <p>A second read, after the first act was refused and rolled back.
         * Paid deliberately: a refusal that cannot say what the caller CAN do
         * is the refusal this contract exists to replace.
         */
        private Situation situationOf(String completeAddress) {
            if (completeAddress == null) {
                return null;
            }
            try {
                AddressParser.Parts at = AddressParser.uri(completeAddress);
                VerbSurface.Result result =
                    verbs.read(caller.current(), at.scope(), at.selector(), at.id());
                return new Situation(result.exchange().address(),
                    result.exchange().status().wireName(),
                    result.exchange().status().terminal(),
                    result.next(Surface.REST));
            } catch (RuntimeException e) {
                return null;
            }
        }

        private record Situation(String address, String state, boolean terminal,
                                 List<NextCalculator.Step> next) {
        }

        private static String named(String address) {
            return address == null ? "the address given" : address;
        }

        /**
         * What the kernel's refusal was ABOUT, where it named it.
         *
         * <p>The kernel carries the objects a refusal is about in its offenders
         * list — the unknown filter field, the unfinished children — precisely
         * so that a surface does not have to parse them back out of a sentence.
         * Reading it here is what lets {@code ARGUMENT_UNKNOWN} name the
         * argument, which the contract's pattern requires and which a caller
         * cannot correct without.
         */
        private static String subjectOf(DispatchException e) {
            return e.offenders().isEmpty()
                ? "the one given"
                : String.join(", ", e.offenders());
        }

        private static String completeSiblingOf(String root, String shortForm) {
            int scopeStart = root.indexOf("://");
            if (scopeStart < 0) {
                return shortForm;
            }
            int scopeEnd = root.indexOf('/', scopeStart + 3);
            return scopeEnd < 0 ? shortForm : root.substring(0, scopeEnd + 1) + shortForm;
        }

        private static String stateIn(String shortForm) {
            int open = shortForm.indexOf('(');
            int close = shortForm.indexOf(')');
            return open >= 0 && close > open
                ? shortForm.substring(open + 1, close)
                : "unfinished";
        }

        /**
         * One status per domain reason.
         *
         * <p>No {@code default}. A reason added to the domain must be given a
         * status here, and the compiler is what asks for it — the alternative
         * is a new refusal quietly becoming a 500 in a deployment nobody is
         * watching.
         */
        private static int statusOf(DispatchException.Reason reason) {
            return switch (reason) {
                // The call is malformed, and no scope had to be known to say so.
                case ADDENDUM_MALFORMED, NUMBER_NOT_ACCEPTED, HOLDER_NOT_ACCEPTED,
                     CLAIM_DURATION_NOT_POSITIVE,
                     UPDATE_EMPTY, RETURN_DRAFT_REQUIRED -> 400;

                // Not this caller. Ever, or with this proof.
                case RATIFICATION_NOT_PERMITTED, RECEIPT_MISMATCH, RECEIPT_ABSENT -> 403;
                case ACTOR_UNKNOWN -> 403;

                // Nothing there — or nothing this subject may know is there.
                case NOT_FOUND, SCOPE_UNRESOLVED -> 404;

                // The object is real and its state says no. NOTHING_TO_CLAIM is
                // the same shape with a set in place of the object.
                case TRANSITION_NOT_PERMITTED, FROZEN, SIBLINGS_NON_TERMINAL, SELECTOR_IN_USE,
                     ADDENDUM_SUFFIX_EXHAUSTED, CLAIM_REQUIRED, RETURN_ALREADY_RATIFIED,
                     NOTHING_TO_CLAIM, RETURN_ABSENT -> 409;

                // Vocabulary: well-formed, addressed at something this scope
                // does not have, or carrying content the scope refuses.
                case SELECTOR_NOT_DECLARED, SELECTOR_WITHDRAWN, ADDENDUM_NOT_DRAWABLE,
                     METADATA_REFUSED, FILTER_FIELD_UNKNOWN, FILTER_VALUE_REFUSED -> 422;

                // Ours, not the caller's: the session contract was not bound.
                case SESSION_NOT_BOUND -> 500;
            };
        }
    }

    /**
     * The refusals raised at the surface as {@link Refused} already — the two
     * adapters' own form checks.
     *
     * <p>Registered so that a {@code Refused} escaping a REST path is answered
     * in its own shape rather than as a 500. It carries no status of its own,
     * because the code decides it: a form fault is 400, a state fault 409, a
     * role fault 403.
     */
    @Provider
    public static class Declared implements ExceptionMapper<Refused> {

        @Override
        public Response toResponse(Refused e) {
            return Response.status(statusOf(e.code()))
                .type(MediaType.APPLICATION_JSON)
                .entity(envelope(e))
                .build();
        }

        private static int statusOf(RefusalCode code) {
            return switch (code) {
                case ARGUMENT_UNKNOWN, ARGUMENT_MISSING, ARGUMENT_INVALID,
                     CLAIM_DURATION_INVALID -> 400;
                case ROLE_DOES_NOT_ALLOW, NOT_THE_HOLDER, RECEIPT_MISSING,
                     RECEIPT_WRONG -> 403;
                case NOT_FOUND -> 404;
                case STATE_DOES_NOT_ALLOW, CHILDREN_NOT_FINISHED, NO_ANSWER_DELIVERED,
                     NOTHING_TO_TAKE -> 409;
                case CONFLICT_TOKEN_MISSING -> 428;
                case CONFLICT_TOKEN_STALE -> 412;
                case SELECTOR_UNKNOWN -> 422;
                case UNEXPECTED_FAILURE -> 500;
            };
        }
    }
}
