package ai.kumbuka.dispatch.adapter.mcp;

import ai.kumbuka.dispatch.surface.NextCalculator;
import ai.kumbuka.dispatch.surface.Participation;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.RefusalCode;
import ai.kumbuka.dispatch.surface.ReasonMapping;
import ai.kumbuka.dispatch.surface.Refused;
import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.surface.SurfaceException;

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
 * <p>So the kernel's message is <strong>discarded</strong>, not decorated. Only
 * its typed reason survives, mapped by {@link ReasonMapping}, and the sentence
 * is written fresh from the catalogue's pattern with the caller's own call name
 * and the state it can see.
 */
final class Refusals {

    private Refusals() {
    }

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
            return Refused.nothingToTake(verb.call(),
                collection == null ? "this bracket kind" : collection);
        }
        if (code == RefusalCode.CLAIM_DURATION_INVALID) {
            return Refused.claimDurationInvalid(verb.call(),
                String.valueOf(arguments.get("duration")));
        }
        if (code == RefusalCode.SELECTOR_UNKNOWN) {
            return Refused.selectorUnknown(verb.call(),
                String.valueOf(arguments.get("selector")),
                String.valueOf(arguments.get("scope")), List.of());
        }
        if (code == RefusalCode.UNEXPECTED_FAILURE) {
            return Refused.unexpected(verb.call(), addressIn(arguments),
                java.util.UUID.randomUUID().toString());
        }

        // The form faults, BEFORE any attempt to read a state. They carry none
        // — nothing was written — and several of them arrive on calls that
        // address no exchange at all, where a state lookup finds nothing and
        // the refusal would fall through to NOT_FOUND. That would tell a
        // caller whose metadata was refused that its scope does not exist.
        if (code == RefusalCode.ARGUMENT_INVALID) {
            return Refused.argumentInvalid(verb.call(), "fields", "the values given",
                e.getMessage());
        }
        if (code == RefusalCode.ARGUMENT_MISSING) {
            return Refused.argumentMissing(verb.call(), "fields", e.getMessage());
        }
        if (code == RefusalCode.ARGUMENT_UNKNOWN) {
            // The kernel names what it refused in its offenders list — the
            // unknown filter field above all — precisely so that a surface need
            // not parse it back out of a sentence. Where it named nothing, the
            // adapter looks among the arguments it was given.
            String named = e.offenders().isEmpty()
                ? firstUnknown(verb, arguments)
                : String.join(", ", e.offenders());
            return Refused.argumentUnknown(verb.call(), named, verb.argumentNames());
        }

        McpAdapter.Situation situation = adapter.situationOf(arguments);
        return dressed(code, verb, situation, arguments);
    }

    /** A surface refusal — grammar, tokens, and the acts this scheme withholds. */
    static Refused of(SurfaceException e, ProcessVerb verb, Map<String, Object> arguments,
                      McpAdapter adapter) {
        return switch (e.reason()) {
            // A malformed address is a malformed argument. It is NOT a
            // NOT_FOUND: nothing was looked up, and answering "nothing is
            // visible there" would tell a caller with a typo to go looking for
            // a permission problem.
            case ADDRESS_MALFORMED, PAYLOAD_MALFORMED -> Refused.argumentInvalid(verb.call(),
                "address", addressIn(arguments), e.getMessage());

            case CLAIM_DURATION_MALFORMED -> Refused.claimDurationInvalid(verb.call(),
                String.valueOf(arguments.get("duration")));

            case CONFLICT_TOKEN_MISSING -> Refused.conflictToken(
                RefusalCode.CONFLICT_TOKEN_MISSING, verb.call(), addressIn(arguments));
            case CONFLICT_TOKEN_STALE -> Refused.conflictToken(
                RefusalCode.CONFLICT_TOKEN_STALE, verb.call(), addressIn(arguments));

            // The three the generic scheme withholds. They are unreachable from
            // this surface — no process verb maps onto them — so reaching one
            // is a defect in the routing rather than a rule the caller broke.
            case VERB_NOT_CARRIED, VERB_DEPTH_UNDECLARED, WITHDRAWAL_VIA_CONSOLE_ONLY,
                 WRITE_ON_TRUNCATED_ADDRESS -> Refused.unexpected(verb.call(),
                addressIn(arguments), java.util.UUID.randomUUID().toString());
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
                                   Map<String, Object> arguments) {
        if (code == RefusalCode.ROLE_DOES_NOT_ALLOW && situation == null) {
            // A token carrying neither capacity can read nothing, so there is
            // no situation to dress — and NOT_FOUND would be the wrong answer
            // even though it is the safe-looking one. The caller is
            // authenticated and its call was refused for who it is, which is
            // something it can act on by presenting a different token;
            // "nothing is visible there" sends it looking for a typo.
            return Refused.ofRole(verb.call(), addressIn(arguments), "commissioner",
                Participation.BYSTANDER, "not visible to you", List.of());
        }
        if (situation == null) {
            return Refused.notFound();
        }

        List<NextCalculator.Step> next = situation.next();

        return switch (code) {
            case STATE_DOES_NOT_ALLOW -> Refused.ofState(verb.call(), situation.address(),
                situation.state(), situation.terminal(), next);

            case ROLE_DOES_NOT_ALLOW -> Refused.ofRole(verb.call(), situation.address(),
                verb.role() == null ? "commissioner"
                    : verb.role().name().toLowerCase(java.util.Locale.ROOT),
                Participation.BYSTANDER, situation.state(), next);

            case NOT_THE_HOLDER -> Refused.notTheHolder(verb.call(), situation.address(),
                "its claim lapses", describe(verb), situation.state(), next);

            case RECEIPT_MISSING, RECEIPT_WRONG -> Refused.receipt(code, verb.call(),
                situation.address(), situation.state(), next);

            case NO_ANSWER_DELIVERED -> Refused.noAnswerDelivered(verb.call(),
                situation.address(), situation.state(), next);

            case ARGUMENT_UNKNOWN -> Refused.argumentUnknown(verb.call(),
                firstUnknown(verb, arguments), verb.argumentNames());
            case ARGUMENT_MISSING -> Refused.argumentMissing(verb.call(), "fields",
                "the values this call writes");
            case ARGUMENT_INVALID -> Refused.argumentInvalid(verb.call(), "fields",
                "the values given", "the service refused one of them");

            // Every remaining code is raised directly, never mapped here. A
            // switch with no default is what makes that checkable.
            case NOT_FOUND, CHILDREN_NOT_FINISHED, NOTHING_TO_TAKE, CLAIM_DURATION_INVALID,
                 CONFLICT_TOKEN_MISSING, CONFLICT_TOKEN_STALE, SELECTOR_UNKNOWN,
                 UNEXPECTED_FAILURE -> Refused.unexpected(verb.call(), situation.address(),
                java.util.UUID.randomUUID().toString());
        };
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
                adapter.situationOf(Map.of("address", complete));

            Map<String, Object> named = new LinkedHashMap<>();
            named.put("address", child == null ? complete : child.address());
            named.put("state", child == null ? stateIn(shortForm) : child.state());
            named.put("next", child == null ? List.of() : child.next());
            offenders.add(Map.copyOf(named));
        }

        McpAdapter.Situation situation = adapter.situationOf(arguments);
        return Refused.childrenNotFinished(verb.call(), root, offenders,
            situation == null ? "unknown" : situation.state(),
            situation == null ? List.of() : situation.next());
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

    private static String addressIn(Map<String, Object> arguments) {
        Object address = arguments.get("address");
        return address == null ? "the address given" : String.valueOf(address);
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
            if (verb.argument(name) == null && !"fields".equals(name)) {
                return name;
            }
        }
        return "an argument of this call";
    }
}
