/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 * <p><strong>Where it lives.</strong> On the REST surface at {@code GET
 * /api/declaration} and nowhere else (C5.1), which the router reads to build
 * its surface (ADR-0039); it serves {@link #asMap()}. Per call it carries the identity {@code
 * dispatch_<verb>}, the effect class by the router's name, the arguments with
 * their names, types, requiredness, defaults and closed sets of values with a
 * description each, the shape of the answer, the reasons a refusal may name,
 * and the texts the call addresses to a caller. Per reason it carries the
 * templates of its messages and its remedy. The whole carries a version.
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
    public static final String SHAPE_VERSION = "3";

    /**
     * The most characters a call's description may carry (concept section
     * 3.5). A description over it fails the start of the service, and with it
     * every test that boots the service: the build.
     */
    public static final int DESCRIPTION_BUDGET = 500;

    /** The member that carries a text describing a call, an argument or a value. */
    private static final String DESCRIPTION = "description";

    private SurfaceDeclaration() {
    }

    /** The twenty-five calls, in the order of TAR-0004 section 3. */
    public static List<ProcessVerb> calls() {
        return List.of(ProcessVerb.values());
    }

    /** Every declared refusal, with its messages and its remedy. */
    public static List<RefusalCode.Declared> reasons() {
        return List.copyOf(RefusalCode.catalogue().values());
    }

    /**
     * The whole declaration as nested maps, ready for any JSON encoder, with
     * its version first.
     *
     * <p>Built rather than annotated. An annotated record would tie the shape
     * that travels between two services to whichever JSON library this one
     * happens to use, and the consumer of this declaration is a different
     * service with a different one.
     *
     * <p><strong>The version</strong> is the SHA-256 of the declaration's
     * canonical text ({@link CanonicalJson}), everything but the version
     * itself: names, types, sets, codes and texts. Equal declarations carry
     * equal versions on every start and host, so a consumer can tell when
     * what it serves changed and record which version it served (ADR-0039).
     */
    public static Map<String, Object> asMap() {
        Map<String, Object> content = content();
        Map<String, Object> declaration = new LinkedHashMap<>();
        declaration.put("version", CanonicalJson.version(content));
        declaration.putAll(content);
        return java.util.Collections.unmodifiableMap(declaration);
    }

    /** The version of the declaration this service serves. */
    public static String version() {
        return CanonicalJson.version(content());
    }

    private static Map<String, Object> content() {
        Map<String, Object> declaration = new LinkedHashMap<>();
        declaration.put("shape_version", SHAPE_VERSION);
        declaration.put("service", "dispatch");
        declaration.put("scheme", "dispatch");
        declaration.put("address_form", "dispatch://<scope>/<selector>/<number>.<sub>");
        declaration.put("technical_address_form", "dispatch://<uuid>");
        declaration.put("effect_classes", java.util.Arrays.stream(EffectClass.values())
            .map(Enum::name).toList());
        declaration.put("calls", callsAsMaps());
        declaration.put("reasons", reasonsAsMaps());
        return declaration;
    }

    private static List<Map<String, Object>> callsAsMaps() {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (ProcessVerb verb : ProcessVerb.values()) {
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("identity", verb.call());
            call.put("call", verb.call());
            call.put("rest_name", verb.verb());
            call.put("effect_class", verb.effectClass().name());
            call.put("transition", verb.isTransition());
            if (verb.role().isPresent()) {
                // Only a restricted call carries a role (C5.4).
                call.put("role", verb.roleName());
            }
            call.put(DESCRIPTION, verb.description());
            if (verb.isTransition()) {
                call.put("next_step", NextList.does(verb.transition().orElseThrow()));
            }
            call.put("arguments", argumentsAsMaps(verb));
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("shape", verb.answerShape().name());
            answer.put("members", verb.answerShape().members());
            call.put("answer", answer);
            call.put("refusals", verb.refusals().stream().map(Enum::name).toList());
            calls.add(call);
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
            declared.put("placement", argument.placement().name());
            if (!argument.required() && !Argument.UNSET.equals(argument.fallback())) {
                // In its own JSON type (C5.4); an argument whose fallback is
                // to stay unset declares none.
                declared.put("default", typed(argument));
            }
            declared.put(DESCRIPTION, argument.description());
            String itemPattern = argument.itemPattern();
            if (itemPattern != null) {
                declared.put("item_pattern", itemPattern);
            }
            if (!argument.values().isEmpty()) {
                declared.put("values", argument.valueNames());
                declared.put("value_descriptions", argument.values().stream()
                    .map(value -> {
                        Map<String, Object> described = new LinkedHashMap<>();
                        described.put("value", value.name());
                        described.put(DESCRIPTION, value.description());
                        return described;
                    })
                    .toList());
            }
            arguments.add(declared);
        }
        return List.copyOf(arguments);
    }

    /**
     * A fallback as a value of the argument's own type: a number, a flag or
     * text. Read only for an optional argument, which declares one.
     */
    private static Object typed(Argument argument) {
        String fallback = Objects.requireNonNull(argument.fallback());
        return switch (argument.type()) {
            case Argument.INTEGER -> Long.parseLong(fallback);
            case Argument.BOOLEAN -> Boolean.parseBoolean(fallback);
            default -> fallback;
        };
    }

    /** The reasons, each with its messages as their templates, and its remedy. */
    private static List<Map<String, Object>> reasonsAsMaps() {
        List<Map<String, Object>> reasons = new ArrayList<>();
        for (RefusalCode.Declared reason : reasons()) {
            Map<String, Object> declared = new LinkedHashMap<>();
            declared.put("reason", reason.code().name());
            declared.put("message_pattern", reason.messages().get(0).template());
            declared.put("messages", reason.messages().stream()
                .map(message -> {
                    Map<String, Object> declaredMessage = new LinkedHashMap<>();
                    declaredMessage.put("key", message.key());
                    declaredMessage.put("template", message.template());
                    return declaredMessage;
                })
                .toList());
            declared.put("remedy", reason.remedy());
            reasons.add(declared);
        }
        return List.copyOf(reasons);
    }

    /**
     * Refuses a declaration that cannot be served, at start-up.
     *
     * <p>Four checks, each a way the surface would be broken in production
     * without anybody noticing until a caller hit it: a duplicate call name
     * makes the tool list ambiguous; a call with no description is a call an
     * assistant with no skill cannot use; a description over {@link
     * #DESCRIPTION_BUDGET} is cut by a caller that does not read it whole; and
     * an argument with no description leaves {@code ARGUMENT_MISSING} with
     * nothing to say the value is. That every refusal code can be worded is
     * {@link RefusalCatalogueGuard}'s, which runs whether or not the assistant
     * surface is built.
     */
    public static void requireServable() {
        requireDistinctNames(Arrays.stream(ProcessVerb.values()).map(ProcessVerb::call).toList());
        for (ProcessVerb verb : ProcessVerb.values()) {
            requireDescribed(verb.call(), verb.description());
            requireWithinBudget(verb.call(), verb.description());
            requireDescribedArguments(verb.call(), verb.arguments());
        }
    }

    /**
     * Refuses two calls declared under one name.
     *
     * <p>Takes the names rather than the enum, for the reason {@link
     * #requireDescribedArguments} takes a list: the real declaration cannot be
     * made to carry a duplicate at runtime.
     */
    public static void requireDistinctNames(List<String> calls) {
        List<String> seen = new ArrayList<>();
        for (String call : calls) {
            if (seen.contains(call)) {
                throw new IllegalStateException(
                    "two calls are declared as '" + call + "'. A tool list is flat, "
                        + "so a duplicate name is a call one of whose declarations no "
                        + "caller can reach.");
            }
            seen.add(call);
        }
    }

    /** Refuses a call declared without a description; takes the text, as above. */
    public static void requireDescribed(String call, String description) {
        if (description == null || description.isBlank()) {
            throw new IllegalStateException(
                call + " is declared without a description. The description is "
                    + "the only thing a caller with no skill has to go on.");
        }
    }

    /**
     * Refuses a description over {@link #DESCRIPTION_BUDGET} characters.
     *
     * <p>Takes the name and the text rather than the constant, so the refusal
     * can be observed against a text over the budget; the real declaration is
     * a constant that cannot be made too long at runtime.
     */
    public static void requireWithinBudget(String call, String description) {
        if (description.length() > DESCRIPTION_BUDGET) {
            throw new IllegalStateException(
                call + "'s description has " + description.length() + " characters, over the "
                    + "budget of " + DESCRIPTION_BUDGET + ". What explains one argument stands "
                    + "at that argument.");
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
     * constant, for the reason {@link RefusalCatalogueGuard#requireComplete(Map)}
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
