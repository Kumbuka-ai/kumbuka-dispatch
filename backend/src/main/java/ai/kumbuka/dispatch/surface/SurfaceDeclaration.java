package ai.kumbuka.dispatch.surface;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The declaration of this service's assistant surface, in one machine-readable
 * value.
 *
 * <p><strong>What this is for.</strong> The router in front of these services
 * publishes its own tool list, its own descriptions and its own schemas, and
 * today it writes them a second time — which is how {@code additionalProperties:
 * true} came to stand on {@code create}'s body there while the service's own
 * schema was closed. The next commission has the router take this declaration
 * unchanged. So the shape has to be transportable: plain records, no framework
 * types, no JSON annotations, and a projection to nested maps that any JSON
 * encoder will write.
 *
 * <p><strong>Where it lives in the published artefact.</strong> In the library
 * jar this module already publishes beside its image
 * ({@code ai.kumbuka:kumbuka-dispatch}), as this class; and on the wire at
 * {@code GET /declaration}, which serves {@link #asMap()}. Two homes for one
 * value and not two values: the endpoint is a projection of the class, so a
 * consumer that cannot take a Java dependency reads the same declaration over
 * HTTP that a consumer who can reads directly.
 */
public final class SurfaceDeclaration {

    /**
     * The revision of the declaration's own shape.
     *
     * <p>Not the service version. A consumer that takes this declaration needs
     * to know when the SHAPE changed — a member added, a member's meaning
     * changed — and the service's version moves for reasons that have nothing
     * to do with it.
     */
    public static final String SHAPE_VERSION = "1";

    private SurfaceDeclaration() {
    }

    /** The fourteen calls, in the order of section 5. */
    public static List<ProcessVerb> calls() {
        return List.of(ProcessVerb.values());
    }

    /** Every declared refusal, with its pattern and its remedy. */
    public static List<ReasonCatalogue.Reason> reasons() {
        return ReasonCatalogue.declared();
    }

    /**
     * The whole declaration as nested maps, ready for any JSON encoder.
     *
     * <p>Built rather than annotated. An annotated record would tie the shape
     * that travels between two services to whichever JSON library this one
     * happens to use, and the consumer of this declaration is a different
     * service with a different one.
     */
    public static Map<String, Object> asMap() {
        Map<String, Object> declaration = new LinkedHashMap<>();
        declaration.put("shape_version", SHAPE_VERSION);
        declaration.put("service", "dispatch");
        declaration.put("scheme", "dispatch");
        declaration.put("address_form", "dispatch://<scope>/<selector>/<number>.<sub>");
        declaration.put("calls", callsAsMaps());
        declaration.put("reasons", reasonsAsMaps());
        return Map.copyOf(declaration);
    }

    private static List<Map<String, Object>> callsAsMaps() {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (ProcessVerb verb : ProcessVerb.values()) {
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("call", verb.call());
            call.put("description", verb.description());
            call.put("role", verb.role() == null
                ? "both"
                : verb.role().name().toLowerCase(java.util.Locale.ROOT));
            call.put("arguments", argumentsAsMaps(verb));
            calls.add(Map.copyOf(call));
        }
        return List.copyOf(calls);
    }

    private static List<Map<String, Object>> argumentsAsMaps(ProcessVerb verb) {
        List<Map<String, Object>> arguments = new ArrayList<>();
        for (Argument argument : verb.arguments()) {
            Map<String, Object> declared = new LinkedHashMap<>();
            declared.put("name", argument.name());
            declared.put("type", argument.type());
            declared.put("required", argument.required());
            declared.put("placement",
                argument.placement().name().toLowerCase(java.util.Locale.ROOT));
            declared.put("description", argument.description());
            arguments.add(Map.copyOf(declared));
        }
        return List.copyOf(arguments);
    }

    /**
     * The reasons, with their patterns read in this surface's vocabulary.
     *
     * <p>The catalogue holds the patterns surface-neutrally — a step where a
     * call name goes, per section 4.2 — because the same pattern words a
     * refusal on both surfaces. This declaration describes the ASSISTANT
     * surface, so it publishes them filled: a consumer reading {@code "the
     * holder delivers with {deliver}"} would have to know a convention nobody
     * published to make sense of it.
     */
    private static List<Map<String, Object>> reasonsAsMaps() {
        List<Map<String, Object>> reasons = new ArrayList<>();
        for (ReasonCatalogue.Reason reason : ReasonCatalogue.declared()) {
            Map<String, Object> declared = new LinkedHashMap<>();
            declared.put("reason", reason.code().name());
            declared.put("message_pattern",
                ReasonCatalogue.inVocabularyOf(reason.pattern(), Surface.MCP));
            declared.put("remedy",
                ReasonCatalogue.inVocabularyOf(reason.remedy(), Surface.MCP));
            reasons.add(Map.copyOf(declared));
        }
        return List.copyOf(reasons);
    }

    /**
     * Refuses a declaration that cannot be served, at start-up.
     *
     * <p>Four checks, and each one names a way the surface would be broken in
     * production without anybody noticing until a caller hit it: a reason with
     * no pattern cannot be worded, a duplicate call name makes the tool list
     * ambiguous, a call with no description is a call an assistant with no
     * skill cannot use, and an argument with no description leaves {@code
     * ARGUMENT_MISSING} with nothing to say the value is.
     *
     * <p>The fourth was named here and not implemented until 2026-09-19 —
     * the javadoc said three checks ran and two did. A description of a check
     * is not a check; {@code SurfaceDeclarationGuardTest} now observes each of
     * the four refusing.
     */
    public static void requireServable() {
        requireServable(ReasonCatalogue.byCode());
    }

    /**
     * The same, against a catalogue handed in.
     *
     * <p>Exists so the red probe for A8 can observe the GUARD rather than the
     * catalogue check it delegates to. The first version of that probe called
     * {@link ReasonCatalogue#requireComplete(Map)} directly, and stayed green
     * when the call was deleted from here — it was checking that the check
     * works, not that anything runs it. A guard nobody calls is exactly as much
     * use as no guard.
     */
    public static void requireServable(
            Map<RefusalCode, ReasonCatalogue.Reason> catalogue) {
        ReasonCatalogue.requireComplete(catalogue);

        List<String> seen = new ArrayList<>();
        for (ProcessVerb verb : ProcessVerb.values()) {
            if (seen.contains(verb.call())) {
                throw new IllegalStateException(
                    "two calls are declared as '" + verb.call() + "'. A tool list is flat, "
                        + "so a duplicate name is a call one of whose declarations no "
                        + "caller can reach.");
            }
            seen.add(verb.call());

            if (verb.description() == null || verb.description().isBlank()) {
                throw new IllegalStateException(
                    verb.call() + " is declared without a description. The description is "
                        + "the contract's normative text and is the only thing a caller "
                        + "with no skill has to go on.");
            }

            requireDescribedArguments(verb.call(), verb.arguments());
        }
    }

    /**
     * Refuses a call whose arguments cannot be explained to a caller.
     *
     * <p>An argument's description is not documentation: it is the {@code
     * what} of the {@code ARGUMENT_MISSING} pattern, so an argument declared
     * without one produces the refusal "dispatch_commission needs title: ."
     * — a sentence that stops exactly where the caller needed it to start.
     *
     * <p>A call with no arguments at all is refused for the neighbouring
     * reason: its input schema would be an empty closed object, and the check
     * that refuses an undeclared argument would have nothing to compare
     * against.
     *
     * <p>Takes the call's name and its arguments rather than the enum
     * constant, for the reason {@link ReasonCatalogue#requireComplete(Map)}
     * takes a map: the real declaration is a constant that cannot be made
     * broken at runtime, so a check that only ever saw it could never be
     * OBSERVED refusing. A guard nobody has watched fail is a description of a
     * guard.
     */
    public static void requireDescribedArguments(String call, List<Argument> arguments) {
        if (arguments.isEmpty()) {
            throw new IllegalStateException(
                call + " is declared with no arguments. Its input schema would be an "
                    + "empty closed object, and the check that names an undeclared "
                    + "argument would have no declaration to name instead.");
        }
        for (Argument argument : arguments) {
            if (argument.description() == null || argument.description().isBlank()) {
                throw new IllegalStateException(
                    call + " declares '" + argument.name() + "' without a description. "
                        + "The description is what an ARGUMENT_MISSING refusal says the "
                        + "value IS, and without it the refusal stops where the caller "
                        + "needed it to start.");
            }
        }
    }
}
