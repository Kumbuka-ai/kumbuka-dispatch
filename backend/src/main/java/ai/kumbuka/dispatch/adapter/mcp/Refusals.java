package ai.kumbuka.dispatch.adapter.mcp;

import ai.kumbuka.dispatch.surface.NextCalculator;
import ai.kumbuka.dispatch.surface.Participation;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.RefusalCode;
import ai.kumbuka.dispatch.surface.ReasonMapping;
import ai.kumbuka.dispatch.surface.Refused;
import ai.kumbuka.dispatch.surface.Surface;
import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.surface.SurfaceException;
import ai.kumbuka.dispatch.surface.UnexpectedFailures;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the refusals of the layers below into the refusal of section 4.
 *
 * <p>This is where "no kernel name ever reaches a caller" is enforced. The
 * kernel's message says what the kernel refused, in the kernel's words and
 * from the kernel's point of view: measured on 2026-09-18, {@code satellite/26.2
 * is draft and cannot takeup; active is reachable only from [open]}. Every
 * clause of that is true and none of it helps — {@code takeup} is not a call
 * the caller made, {@code draft} is not a state this surface has, and "what
 * reaches active" is the way in where the caller needed the way out.
 *
 * <p>So the kernel's message is <strong>discarded</strong>, not decorated, and
 * that now holds for the argument faults too. Measured in review on 2026-09-19: the
 * {@code ARGUMENT_INVALID} and {@code ARGUMENT_MISSING} branches passed {@code
 * e.getMessage()} through as the pattern's {@code why}, and kernel sentences
 * carry short-form addresses — so the one refusal that takes free text was the
 * one path by which {@code sprint/26.1} reached a caller that the contract
 * promises only complete addresses to. Every {@code why} below is a sentence of
 * this file's own.
 */
final class Refusals {

    private Refusals() {
    }

    /** This adapter's surface. Named once, so no branch can answer for another. */
    private static final Surface SURFACE = Surface.MCP;

    /** The argument names this file hands to a refusal, once each. */
    private static final String ARG_FIELDS = "fields";
    private static final String ARG_ADDRESS = "address";

    /** What an address reads as when the call named none. */
    private static final String NO_ADDRESS = "the address given";

    /** A kernel refusal, in the caller's vocabulary. */
    static Refused of(DispatchException e, ProcessVerb verb, Map<String, Object> arguments,
                      McpAdapter adapter) {
        RefusalCode code = ReasonMapping.of(e.reason());

        if (code == RefusalCode.NOT_FOUND) {
            return Refused.notFound();
        }
        if (code == RefusalCode.CHILDREN_NOT_FINISHED) {
            return children(e, verb, arguments, adapter);
        }
        if (code == RefusalCode.NOTHING_TO_TAKE) {
            String collection = adapter.collectionOf(arguments);
            return Refused.nothingToTake(SURFACE, verb.call(),
                collection == null ? "this bracket kind" : collection);
        }
        if (code == RefusalCode.CLAIM_DURATION_INVALID) {
            return Refused.claimDurationInvalid(SURFACE, verb.call(),
                String.valueOf(arguments.get("duration")));
        }
        if (code == RefusalCode.SELECTOR_UNKNOWN) {
            // The declared selectors, named. The contract's remedy is "use a
            // declared one" and the predecessor passed an empty list, so every
            // such refusal rendered "Declared: none" and told the caller to
            // pick from nothing.
            return Refused.selectorUnknown(SURFACE, verb.call(),
                String.valueOf(arguments.get("selector")),
                String.valueOf(arguments.get("scope")),
                adapter.declaredSelectorsIn(arguments));
        }
        if (code == RefusalCode.IDEMPOTENCY_KEY_REUSED) {
            return Refused.idempotencyKeyReused(SURFACE, verb.call(),
                String.valueOf(arguments.get("idempotency_key")),
                String.valueOf(arguments.get("scope")));
        }
        if (code == RefusalCode.UNEXPECTED_FAILURE) {
            return UnexpectedFailures.refuse(SURFACE, verb.call(), addressIn(arguments), e);
        }

        // The three about the scope, BEFORE any attempt to read a state. The
        // scope was resolved and the call never reached an exchange, so there
        // is no situation to dress — and a state lookup would answer nothing
        // and send all three to NOT_FOUND, telling a caller whose scope is
        // merely locked that its address is wrong.
        if (code == RefusalCode.SCOPE_KIND_UNSUPPORTED) {
            // The kind comes from the directory, which is the only layer that
            // read it. `offenders` carries it as a value for the pattern; it
            // does not reach `data`.
            return Refused.scopeKindUnsupported(SURFACE, verb.call(), scopeIn(arguments),
                e.offenders().isEmpty() ? "kind it does not serve" : e.offenders().get(0));
        }
        if (code == RefusalCode.SCOPE_READ_ONLY) {
            return Refused.scopeReadOnly(SURFACE, verb.call(), scopeIn(arguments));
        }
        if (code == RefusalCode.SCOPE_LOCKED) {
            return Refused.scopeLocked(SURFACE, verb.call(), scopeIn(arguments));
        }

        // The form faults, BEFORE any attempt to read a state. They carry none
        // — nothing was written — and several of them arrive on calls that
        // address no exchange at all, where a state lookup finds nothing and
        // the refusal would fall through to NOT_FOUND. That would tell a
        // caller whose metadata was refused that its scope does not exist.
        if (code == RefusalCode.ARGUMENT_INVALID) {
            return argumentInvalid(e, verb);
        }
        if (code == RefusalCode.ARGUMENT_MISSING) {
            return Refused.argumentMissing(SURFACE, verb.call(), ARG_FIELDS,
                "the values this call writes");
        }
        if (code == RefusalCode.ARGUMENT_UNKNOWN) {
            // The kernel names what it refused in its offenders list — the
            // unknown filter field above all — precisely so that a surface need
            // not parse it back out of a sentence. Where it named nothing, the
            // adapter looks among the arguments it was given.
            String named = e.offenders().isEmpty()
                ? firstUnknown(verb, arguments)
                : String.join(", ", e.offenders());
            return Refused.argumentUnknown(SURFACE, verb.call(), named,
                verb.argumentNames());
        }

        McpAdapter.Situation situation = adapter.situationOf(arguments);
        return dressed(code, verb, situation, arguments, e);
    }

    /**
     * An argument fault, worded here and never by the kernel.
     *
     * <p>The kernel's typed reason says which argument was wrong; the sentence
     * that explains it is this file's. Each branch names the argument the
     * caller actually sent, which is what makes the refusal correctable — the
     * predecessor answered every one of them with the name {@code fields}.
     */
    private static Refused argumentInvalid(DispatchException e, ProcessVerb verb) {
        return switch (e.reason()) {
            case CURATION_TARGET_SELF -> Refused.argumentInvalid(SURFACE, verb.call(),
                "into", "the exchange's own address",
                "a curation carries an answer forward INTO another exchange, so the "
                    + "target cannot be the exchange being curated");
            case METADATA_REFUSED -> Refused.argumentInvalid(SURFACE, verb.call(),
                "metadata", "the object given",
                "metadata carries the caller's own keys and takes no assertion and no "
                    + "URL carrying credentials");
            case ADDENDUM_MALFORMED -> Refused.argumentInvalid(SURFACE, verb.call(),
                ARG_ADDRESS, NO_ADDRESS,
                "a correction is attached to an exchange, and the address given names a "
                    + "correction rather than one");
            case ADDENDUM_SUFFIX_EXHAUSTED -> Refused.argumentInvalid(SURFACE, verb.call(),
                ARG_ADDRESS, NO_ADDRESS,
                "this exchange already carries every correction its address space "
                    + "admits");
            default -> Refused.argumentInvalid(SURFACE, verb.call(), ARG_FIELDS,
                "the values given", "one of them is not a value this call takes");
        };
    }

    /** A surface refusal — grammar, tokens, and the acts this scheme withholds. */
    static Refused of(SurfaceException e, ProcessVerb verb, Map<String, Object> arguments,
                      McpAdapter adapter) {
        return switch (e.reason()) {
            // A malformed address is a malformed argument. It is NOT a
            // NOT_FOUND: nothing was looked up, and answering "nothing is
            // visible there" would tell a caller with a typo to go looking for
            // a permission problem.
            case ADDRESS_MALFORMED, PAYLOAD_MALFORMED -> Refused.argumentInvalid(SURFACE,
                verb.call(), ARG_ADDRESS, addressIn(arguments),
                "an address is written dispatch://<scope>/<selector>/<number>.<sub>");

            case CLAIM_DURATION_MALFORMED -> Refused.claimDurationInvalid(SURFACE,
                verb.call(), String.valueOf(arguments.get("duration")));

            case CONFLICT_TOKEN_MISSING -> Refused.conflictToken(SURFACE,
                RefusalCode.CONFLICT_TOKEN_MISSING, verb.call(), addressIn(arguments));
            case CONFLICT_TOKEN_STALE -> Refused.conflictToken(SURFACE,
                RefusalCode.CONFLICT_TOKEN_STALE, verb.call(), addressIn(arguments));

            // A verb that exists on this surface and not at this depth. Its own
            // code since the contract declared one: the predecessor pressed
            // ARGUMENT_INVALID into service and sent a caller correcting an
            // address that was right.
            case VERB_DEPTH_UNDECLARED, WRITE_ON_TRUNCATED_ADDRESS,
                 CALL_NOT_AT_THIS_ADDRESS -> notAtThisAddress(verb, arguments, adapter);

            // The two the generic scheme withholds. They are unreachable from
            // this surface — no process verb maps onto them — so reaching one
            // is a defect in the routing rather than a rule the caller broke.
            case VERB_NOT_CARRIED, WITHDRAWAL_VIA_CONSOLE_ONLY -> UnexpectedFailures.refuse(
                SURFACE, verb.call(), addressIn(arguments), e);
        };
    }

    /** The call is real here and does not apply at the address given. */
    private static Refused notAtThisAddress(ProcessVerb verb, Map<String, Object> arguments,
                                            McpAdapter adapter) {
        McpAdapter.Situation situation = adapter.situationOf(arguments);
        return Refused.callNotAtThisAddress(SURFACE, verb.call(), addressIn(arguments),
            appliesTo(verb),
            situation == null ? "not visible to you" : situation.state(),
            situation == null ? List.of() : situation.next());
    }

    /** What each verb addresses, for the refusal that says it is elsewhere. */
    private static String appliesTo(ProcessVerb verb) {
        return switch (verb) {
            case CLOSE_BRACKET -> "a bracket root, the .0 of its bracket";
            case QUERY, TAKE_NEXT -> "a bracket kind in a scope, not a single exchange";
            default -> "one exchange, at its complete address";
        };
    }

    /**
     * The refusals that carry a state: the ones the caller can act on.
     *
     * <p>Where the exchange could not be re-read — because the caller cannot
     * see it — the refusal falls back to {@code NOT_FOUND}. That is not a
     * downgrade: a caller that may not see the exchange must not learn its
     * state from the refusal it gets for acting on it.
     */
    private static Refused dressed(RefusalCode code, ProcessVerb verb,
                                   McpAdapter.Situation situation,
                                   Map<String, Object> arguments, DispatchException e) {
        if (code == RefusalCode.ROLE_DOES_NOT_ALLOW && situation == null) {
            // A token carrying neither capacity can read nothing, so there is
            // no situation to dress — and NOT_FOUND would be the wrong answer
            // even though it is the safe-looking one. The caller is
            // authenticated and its call was refused for who it is, which is
            // something it can act on by presenting a different token;
            // "nothing is visible there" sends it looking for a typo.
            return Refused.ofRole(SURFACE, verb.call(), addressIn(arguments), "commissioner",
                Participation.BYSTANDER, "not visible to you", List.of());
        }
        if (situation == null) {
            return Refused.notFound();
        }

        List<NextCalculator.Step> next = situation.next();

        return switch (code) {
            case STATE_DOES_NOT_ALLOW -> Refused.ofState(SURFACE, verb.call(),
                situation.address(), situation.state(), situation.terminal(), next);

            // The caller's ACTUAL part, from the same computation `next` uses.
            // The predecessor hardcoded BYSTANDER and told a holder it took no
            // part in the exchange it was holding.
            case ROLE_DOES_NOT_ALLOW -> Refused.ofRole(SURFACE, verb.call(),
                situation.address(), requiredPart(verb), situation.participation(),
                situation.state(), next);

            case NOT_THE_HOLDER -> Refused.notTheHolder(SURFACE, verb.call(),
                situation.address(), lapsesAt(situation), describe(verb),
                situation.state(), next);

            case RECEIPT_MISSING, RECEIPT_WRONG -> Refused.receipt(SURFACE, code,
                verb.call(), situation.address(), situation.state(), next);

            case NO_ANSWER_DELIVERED -> Refused.noAnswerDelivered(SURFACE, verb.call(),
                situation.address(), situation.state(), next);

            case ARGUMENT_UNKNOWN -> Refused.argumentUnknown(SURFACE, verb.call(),
                firstUnknown(verb, arguments), verb.argumentNames());
            case ARGUMENT_MISSING -> Refused.argumentMissing(SURFACE, verb.call(), ARG_FIELDS,
                "the values this call writes");
            case ARGUMENT_INVALID -> argumentInvalid(e, verb);

            // Every remaining code is raised directly, never mapped here. A
            // switch with no default is what makes that checkable.
            case NOT_FOUND, CHILDREN_NOT_FINISHED, NOTHING_TO_TAKE, CLAIM_DURATION_INVALID,
                 CONFLICT_TOKEN_MISSING, CONFLICT_TOKEN_STALE, SELECTOR_UNKNOWN,
                 IDEMPOTENCY_KEY_REUSED, CALL_NOT_AT_THIS_ADDRESS,
                 SCOPE_KIND_UNSUPPORTED, SCOPE_READ_ONLY, SCOPE_LOCKED,
                 UNEXPECTED_FAILURE -> UnexpectedFailures.refuse(SURFACE, verb.call(),
                situation.address(), e);
        };
    }

    /** The part section 5 reserves this call for. */
    private static String requiredPart(ProcessVerb verb) {
        return verb.role() == null
            ? "commissioner"
            : verb.role().wireName();
    }

    /**
     * When the claim lapses, as an ISO-8601 instant.
     *
     * <p>The contract's pattern names a time and this is the value for it. The
     * predecessor wrote "its claim lapses", which is the sentence with its one
     * piece of information removed — a caller cannot decide whether to wait or
     * to do something else without knowing how long the wait is.
     */
    private static String lapsesAt(McpAdapter.Situation situation) {
        Instant expiry = situation.claimExpiresAt();
        return expiry == null ? "its claim lapses" : expiry.toString();
    }

    /**
     * The blocking children, each with its complete address, its state and the
     * call that would finish it.
     *
     * <p>The kernel names them in short form because that is what its own
     * messages and logs use. Each is re-read here, through the surface, so its
     * address is complete and its {@code next} is this caller's — which is the
     * whole point of naming them at all: a caller that is told which children
     * block it and what would unblock each one can act without a second
     * listing.
     */
    private static Refused children(DispatchException e, ProcessVerb verb,
                                    Map<String, Object> arguments, McpAdapter adapter) {
        String root = addressIn(arguments);
        List<Map<String, Object>> offenders = new ArrayList<>();

        for (String shortForm : e.offenders()) {
            // "selector/number.sub (state)" — the kernel's own rendering.
            String bare = shortForm.contains(" ")
                ? shortForm.substring(0, shortForm.indexOf(' '))
                : shortForm;
            String complete = sameScopeAs(root, bare);

            McpAdapter.Situation child =
                adapter.situationOf(Map.of(ARG_ADDRESS, complete));

            Map<String, Object> named = new LinkedHashMap<>();
            named.put(ARG_ADDRESS, child == null ? complete : child.address());
            named.put("state", child == null ? stateIn(shortForm) : child.state());
            named.put("next", child == null ? List.of() : child.next());
            offenders.add(Map.copyOf(named));
        }

        McpAdapter.Situation situation = adapter.situationOf(arguments);
        return Refused.childrenNotFinished(SURFACE, verb.call(), root, ending(verb),
            offenders, situation == null ? "unknown" : situation.state(),
            situation == null ? List.of() : situation.next());
    }

    /**
     * Which ending the caller attempted, for the contract's
     * {@code <closed / cancelled>}.
     *
     * <p>The refusal is raised for two different acts and describing one as
     * the other tells the caller about a call it did not make.
     */
    private static String ending(ProcessVerb verb) {
        return verb == ProcessVerb.CANCEL ? "cancelled" : "closed";
    }

    /**
     * A sibling's complete address, built from the root's.
     *
     * <p>A child is in the same scope as its bracket root by construction, so
     * the scope comes from the address the caller gave. Re-deriving it any
     * other way would be inventing a scope for an object the caller already
     * addressed.
     */
    private static String sameScopeAs(String rootAddress, String shortForm) {
        int scopeStart = rootAddress.indexOf("://");
        if (scopeStart < 0) {
            return shortForm;
        }
        int scopeEnd = rootAddress.indexOf('/', scopeStart + 3);
        if (scopeEnd < 0) {
            return shortForm;
        }
        return rootAddress.substring(0, scopeEnd + 1) + shortForm;
    }

    private static String stateIn(String shortForm) {
        int open = shortForm.indexOf('(');
        int close = shortForm.indexOf(')');
        return open >= 0 && close > open ? shortForm.substring(open + 1, close) : "unfinished";
    }

    /** What a verb does, for the refusal that says only the holder can do it. */
    private static String describe(ProcessVerb verb) {
        return switch (verb) {
            case DELIVER_RETURN -> "deliver an answer on it";
            case ASK_COMMISSIONER -> "ask a question on it";
            case DECLINE -> "decline it";
            default -> "make that call on it";
        };
    }

    /**
     * The scope the caller named, as it named it.
     *
     * <p>From the arguments and never from the directory: a refusal names the
     * scope back in the caller's own spelling, and the id the directory holds
     * is not something this surface puts on the wire. Calls at a complete
     * address carry it inside {@code address}; collection-level calls carry it
     * as {@code scope}.
     */
    private static String scopeIn(Map<String, Object> arguments) {
        Object named = arguments.get("scope");
        if (named != null) {
            return String.valueOf(named);
        }
        Object address = arguments.get(ARG_ADDRESS);
        if (address == null) {
            return "the scope named";
        }
        String raw = String.valueOf(address);
        int start = raw.indexOf("://");
        if (start < 0) {
            return "the scope named";
        }
        int end = raw.indexOf('/', start + 3);
        return end < 0 ? raw.substring(start + 3) : raw.substring(start + 3, end);
    }

    private static String addressIn(Map<String, Object> arguments) {
        Object address = arguments.get(ARG_ADDRESS);
        return address == null ? NO_ADDRESS : String.valueOf(address);
    }

    /**
     * The first argument the call does not declare.
     *
     * <p>Reached only when the kernel raised an unknown-argument reason of its
     * own — a filter field, above all. The adapter's own check has already run
     * and found nothing, so the name is looked for among what the kernel would
     * have rejected rather than among the declaration.
     */
    private static String firstUnknown(ProcessVerb verb, Map<String, Object> arguments) {
        for (String name : arguments.keySet()) {
            if (verb.argument(name) == null && !ARG_FIELDS.equals(name)) {
                return name;
            }
        }
        return "an argument of this call";
    }
}
