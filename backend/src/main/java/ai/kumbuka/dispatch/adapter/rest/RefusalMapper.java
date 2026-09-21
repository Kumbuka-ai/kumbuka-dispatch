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
import ai.kumbuka.dispatch.surface.UnexpectedFailures;
import ai.kumbuka.dispatch.surface.VerbSurface;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * What a value reads as where this surface cannot name it.
     *
     * <p>Two of them and they say different things. {@code THE_ONE_GIVEN}
     * stands where the caller supplied a value this mapper cannot read back
     * out of the request; {@code NOT_VISIBLE} stands where the exchange itself
     * could not be re-read, and is deliberately not a state — telling a caller
     * that may not see an exchange what state it is in is the leak section 4.3
     * exists to close.
     */
    static final String THE_ONE_GIVEN = "the one given";
    static final String NOT_VISIBLE = "not visible to you";

    @Inject CallScope calling;

    @Override
    public Response toResponse(SurfaceException e) {
        LOG.debugf("surface refusal: %s -> %d", e.reason().name(), e.reason().status());

        String call = calling.call("this call");
        String address = calling.address() == null ? "the address given" : calling.address();

        Refused refused = switch (e.reason()) {
            // Every `why` below is this file's own sentence. The surface's
            // messages name their own vocabulary and can carry a short-form
            // address, and section 4.4 makes ARGUMENT_INVALID's `why` "a
            // sentence of the pattern's own, never a kernel sentence".
            case ADDRESS_MALFORMED, PAYLOAD_MALFORMED ->
                Refused.argumentInvalid(Surface.REST, call, "address", address,
                    "an address names a selector, a number and a sub-position under a "
                        + "scope this caller may see");
            case CLAIM_DURATION_MALFORMED ->
                Refused.claimDurationInvalid(Surface.REST, call, THE_ONE_GIVEN);
            case CONFLICT_TOKEN_MISSING ->
                Refused.conflictToken(Surface.REST, RefusalCode.CONFLICT_TOKEN_MISSING,
                    call, address);
            case CONFLICT_TOKEN_STALE ->
                Refused.conflictToken(Surface.REST, RefusalCode.CONFLICT_TOKEN_STALE,
                    call, address);
            // A verb at an address depth it does not have now carries the code
            // the contract declares for it, rather than borrowing the one for
            // a bad argument — the address was fine and the pairing was not.
            case VERB_DEPTH_UNDECLARED, WRITE_ON_TRUNCATED_ADDRESS,
                 CALL_NOT_AT_THIS_ADDRESS ->
                Refused.callNotAtThisAddress(Surface.REST, call, address,
                    "an address depth this verb declares", "not read", List.of());
            // The two this scheme withholds. Real refusals here, unlike on the
            // assistant surface where no verb reaches them.
            case WITHDRAWAL_VIA_CONSOLE_ONLY ->
                Refused.argumentInvalid(Surface.REST, call, "verb", call,
                    "withdrawal is a ratchet and is restorable only through the console, "
                        + "so the act has an address and this scheme is not it");
            case VERB_NOT_CARRIED ->
                Refused.argumentInvalid(Surface.REST, call, "verb", call,
                    "this scheme does not carry it, and a verb it does not carry is "
                        + "refused rather than quietly absent");
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
                return UnexpectedFailures.refuse(Surface.REST, call, named(address), e);
            }

            // The form faults, before any attempt to read a state. Several of
            // them arrive on a call that addresses no exchange — a listing with
            // a filter this scheme does not carry — where a state lookup finds
            // nothing and the refusal would fall through to something about an
            // exchange the caller never named.
            if (code == RefusalCode.ARGUMENT_UNKNOWN) {
                return Refused.argumentUnknown(Surface.REST, call, subjectOf(e),
                    List.of("see " + AddressParser.SCHEME + "'s declaration"));
            }
            if (code == RefusalCode.SELECTOR_UNKNOWN) {
                // Named on this surface too. The predecessor put the words
                // "the one given" where the selector goes, "this scope" where
                // the scope goes and an empty list where the remedy goes —
                // three values removed from one sentence, and the sentence
                // still ends by telling the caller to use a declared one.
                return Refused.selectorUnknown(Surface.REST, call,
                    calling.selector(THE_ONE_GIVEN), calling.scope("this scope"),
                    declaredSelectors());
            }
            if (code == RefusalCode.IDEMPOTENCY_KEY_REUSED) {
                return Refused.idempotencyKeyReused(Surface.REST, call, THE_ONE_GIVEN,
                    calling.scope("this scope"));
            }

            // The three about the scope, before any attempt to read a state.
            // The call never reached an exchange — it was stopped at stage 2 —
            // so there is nothing to look up, and a lookup that found nothing
            // would answer NOT_FOUND and tell a caller whose scope is merely
            // locked to go looking for a typo.
            if (code == RefusalCode.SCOPE_KIND_UNSUPPORTED) {
                return Refused.scopeKindUnsupported(Surface.REST, call,
                    calling.scope("the scope named"),
                    e.offenders().isEmpty() ? "kind it does not serve" : e.offenders().get(0));
            }
            if (code == RefusalCode.SCOPE_READ_ONLY) {
                return Refused.scopeReadOnly(Surface.REST, call,
                    calling.scope("the scope named"));
            }
            if (code == RefusalCode.SCOPE_LOCKED) {
                return Refused.scopeLocked(Surface.REST, call,
                    calling.scope("the scope named"));
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
                case STATE_DOES_NOT_ALLOW -> Refused.ofState(Surface.REST, call,
                    situation.address(), situation.state(), situation.terminal(),
                    situation.next());
                case ROLE_DOES_NOT_ALLOW -> Refused.ofRole(Surface.REST, call,
                    situation.address(), "commissioner", situation.participation(),
                    situation.state(), situation.next());
                case NOT_THE_HOLDER -> Refused.notTheHolder(Surface.REST, call,
                    situation.address(), lapsesAt(situation), "make that call on it",
                    situation.state(), situation.next());
                case RECEIPT_MISSING, RECEIPT_WRONG -> Refused.receipt(Surface.REST, code,
                    call, situation.address(), situation.state(), situation.next());
                case NO_ANSWER_DELIVERED -> Refused.noAnswerDelivered(Surface.REST, call,
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
                case ROLE_DOES_NOT_ALLOW -> Refused.ofRole(Surface.REST, call, address,
                    "commissioner", Participation.BYSTANDER, NOT_VISIBLE, List.of());
                case NOT_THE_HOLDER -> Refused.notTheHolder(Surface.REST, call, address,
                    "its claim lapses", "make that call on it", NOT_VISIBLE, List.of());
                case RECEIPT_MISSING, RECEIPT_WRONG -> Refused.receipt(Surface.REST, code,
                    call, address, NOT_VISIBLE, List.of());
                case STATE_DOES_NOT_ALLOW -> Refused.ofState(Surface.REST, call, address,
                    NOT_VISIBLE, false, List.of());
                case NO_ANSWER_DELIVERED -> Refused.noAnswerDelivered(Surface.REST, call,
                    address, NOT_VISIBLE, List.of());
                case NOTHING_TO_TAKE -> Refused.nothingToTake(Surface.REST, call, address);
                case CLAIM_DURATION_INVALID -> Refused.claimDurationInvalid(Surface.REST,
                    call, "the duration given");
                case ARGUMENT_UNKNOWN -> Refused.argumentUnknown(Surface.REST, call,
                    THE_ONE_GIVEN, List.of("see the service's declaration"));
                case ARGUMENT_MISSING -> Refused.argumentMissing(Surface.REST, call,
                    "a required value", "a value this call cannot run without");
                case ARGUMENT_INVALID -> argumentInvalid(call, e);
                default -> UnexpectedFailures.refuse(Surface.REST, call, address, e);
            };
        }

        /**
         * An argument fault, worded here and never by the kernel.
         *
         * <p>The mirror of the assistant surface's branch and it exists for
         * the same measured reason: the kernel's sentences carry short-form
         * addresses, and this is the one refusal whose pattern takes free
         * text. Passing {@code e.getMessage()} through was the path by which
         * {@code sprint/26.1} reached a caller the contract promises only
         * complete addresses to.
         */
        private static Refused argumentInvalid(String call, DispatchException e) {
            return switch (e.reason()) {
                case CURATION_TARGET_SELF -> Refused.argumentInvalid(Surface.REST, call,
                    "into", "the exchange's own address",
                    "a curation carries an answer forward INTO another exchange, so the "
                        + "target cannot be the exchange being curated");
                case METADATA_REFUSED -> Refused.argumentInvalid(Surface.REST, call,
                    "metadata", "the object given",
                    "metadata carries the caller's own keys and takes no assertion and "
                        + "no URL carrying credentials");
                default -> Refused.argumentInvalid(Surface.REST, call, "a value given",
                    "the one sent", "it is not a value this call takes");
            };
        }

        /**
         * When the claim lapses, as an ISO-8601 instant.
         *
         * <p>The contract's pattern names a time; the predecessor wrote the
         * phrase "its claim lapses" on both surfaces, which is the sentence
         * with its one piece of information removed.
         */
        private static String lapsesAt(Situation situation) {
            Instant expiry = situation.claimExpiresAt();
            return expiry == null ? "its claim lapses" : expiry.toString();
        }

        /** The bracket kinds this scope declares, or none it may know about. */
        private List<String> declaredSelectors() {
            String scope = calling.scope(null);
            if (scope == null) {
                return List.of();
            }
            try {
                return verbs.declaredSelectors(caller.current(), scope);
            } catch (RuntimeException e) {
                // A scope the caller may not see declares nothing it may know
                // about, which is section 4.3's rule reaching one level in.
                return List.of();
            }
        }

        /**
         * The blocking children, re-read so each carries a complete address
         * AND the calls that would finish it.
         *
         * <p>Each child is re-read here, exactly as the assistant surface
         * re-reads them. The predecessor wrote an empty list into every
         * offender's {@code next} on this surface, so the remedy section 4.4
         * names for this refusal — "the calls in each offender's next" —
         * pointed at nothing, on the one surface where a caller has no other
         * way to find out what is blocking it.
         */
        private Refused children(DispatchException e, String call, String root,
                                 Situation situation) {
            List<Map<String, Object>> offenders = new ArrayList<>();
            for (String shortForm : e.offenders()) {
                String bare = shortForm.contains(" ")
                    ? shortForm.substring(0, shortForm.indexOf(' '))
                    : shortForm;
                String complete = completeSiblingOf(root, bare);
                Situation child = situationOf(complete);

                Map<String, Object> named = new LinkedHashMap<>();
                named.put("address", child == null ? complete : child.address());
                named.put("state", child == null ? stateIn(shortForm) : child.state());
                named.put("next", child == null ? List.of() : child.next());
                offenders.add(Map.copyOf(named));
            }
            return Refused.childrenNotFinished(Surface.REST, call, root, ending(call),
                offenders,
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
                    result.participation(),
                    result.exchange().claimExpiresAt(),
                    result.next(Surface.REST));
            } catch (RuntimeException e) {
                return null;
            }
        }

        private record Situation(String address, String state, boolean terminal,
                                 Participation participation,
                                 Instant claimExpiresAt,
                                 List<NextCalculator.Step> next) {
        }

        /**
         * Which ending the caller attempted, for the contract's
         * {@code <closed / cancelled>}.
         *
         * <p>On the generic surface both endings are the same verb — {@code
         * close} — so the sentence says "closed" and does not guess at an
         * intent this surface has no way to carry.
         */
        private static String ending(String call) {
            return call.contains("cancel") ? "cancelled" : "closed";
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
                ? THE_ONE_GIVEN
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
                // SCOPE_KIND_UNSUPPORTED is vocabulary in exactly this sense:
                // the scope is real and visible, and an exchange in a scope of
                // that kind is not part of the offering. Nothing the caller
                // presents changes it.
                case SELECTOR_NOT_DECLARED, SELECTOR_WITHDRAWN, ADDENDUM_NOT_DRAWABLE,
                     METADATA_REFUSED, FILTER_FIELD_UNKNOWN, FILTER_VALUE_REFUSED,
                     CURATION_TARGET_SELF, SCOPE_KIND_UNSUPPORTED -> 422;

                // The write right, and the lock. SCOPE_READ_ONLY is a 403 and
                // not a 404 (ADR-0011 exception, ratified by the operator on
                // 2026-09-21): the read contract has already told this caller
                // the scope is visible, so withholding it now would hide
                // nothing and would send somebody looking for a typo in an
                // address that is correct. SCOPE_LOCKED is a 409 and not a
                // 403, because it is the scope's own state rather than a
                // judgement about the caller — the same token gets through
                // once the lock is lifted.
                case SCOPE_READ_ONLY -> 403;
                case SCOPE_LOCKED -> 409;

                // The key was already spent on a different call. 409: the
                // caller can resolve it, by choosing another key.
                case IDEMPOTENCY_KEY_REUSED -> 409;

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
                     NOTHING_TO_TAKE, IDEMPOTENCY_KEY_REUSED -> 409;
                // The verb is real and does not apply here. 405 and not 404:
                // the address resolved, and a 404 would send the caller
                // looking for the object rather than for the right call.
                case CALL_NOT_AT_THIS_ADDRESS -> 405;
                case CONFLICT_TOKEN_MISSING -> 428;
                case CONFLICT_TOKEN_STALE -> 412;
                case SELECTOR_UNKNOWN, SCOPE_KIND_UNSUPPORTED -> 422;
                // The pair the kernel switch above states its reasoning for.
                // Written out here too rather than derived: this switch answers
                // for the surface's codes and the other for the kernel's, and
                // one deriving from the other is how the two would come to
                // disagree on a code only one of them can raise.
                case SCOPE_READ_ONLY -> 403;
                case SCOPE_LOCKED -> 409;
                case UNEXPECTED_FAILURE -> 500;
            };
        }
    }
}
