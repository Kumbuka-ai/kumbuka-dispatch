/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import ai.kumbuka.dispatch.surface.AddressParser;
import ai.kumbuka.dispatch.surface.Argument;
import ai.kumbuka.dispatch.surface.ArgumentPath;
import ai.kumbuka.dispatch.surface.Form;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.Refusal;
import ai.kumbuka.dispatch.surface.Surface;
import ai.kumbuka.dispatch.surface.SurfaceException;
import ai.kumbuka.dispatch.surface.VerbName;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The form check of a call: its arguments closed against the declaration and
 * read into what the verb surface takes (C1.2).
 *
 * <p>Once for both adapters, which hand over the call's arguments as one map:
 * the assistant surface its tool arguments, the REST surface the path and the
 * query of the request. Every refusal here is decided without knowing a scope
 * and answers 400 before anything else is read (C1.3): an argument the call
 * does not declare, a required one missing, a value outside its form, and a
 * scope or a selector outside the address grammar. A refusal names declared
 * arguments only, never a value or a name the caller sent (DEC-0043 R18, R19).
 *
 * <p>JSON {@code null} is an argument not sent, at every name (DEC-0043 R17).
 */
public final class CallForm {

    private static final String SCOPE = "scope";
    private static final String SELECTOR = "selector";
    private static final String VERSION = "version";

    private CallForm() {
    }

    /**
     * The arguments of {@code read_definition}, read.
     *
     * @param version the number of the version; absent for the active one
     */
    public record ReadDefinition(String scope, String selector, @Nullable Integer version) {
    }

    /** Reads the arguments of {@code read_definition}, or refuses their form. */
    public static ReadDefinition readDefinition(Surface surface, Map<String, Object> given) {
        ProcessVerb verb = ProcessVerb.READ_DEFINITION;
        VerbName call = VerbName.of(verb, surface);
        Map<String, Object> present = closed(verb, call, given);
        String scope = name(verb, call, present, SCOPE, Form.SCOPE, AddressParser::scopeOf);
        String selector = name(verb, call, present, SELECTOR, Form.SELECTOR,
            AddressParser::selectorOf);
        return new ReadDefinition(scope, selector,
            ordinal(verb, call, present.get(VERSION)).orElse(null));
    }

    /**
     * The arguments that arrived with a value, after refusing a name the call
     * does not declare. The refusal lists what the call accepts and carries
     * none of the names that arrived.
     */
    static Map<String, Object> closed(ProcessVerb verb, VerbName call, Map<String, Object> given) {
        Map<String, Object> present = new LinkedHashMap<>();
        for (Map.Entry<String, Object> argument : given.entrySet()) {
            if (argument.getValue() == null) {
                continue;
            }
            if (verb.argument(argument.getKey()).isEmpty()) {
                throw new SurfaceException(Refusal.argumentUnknown(call, false,
                    ArgumentPath.of(verb.topArguments())));
            }
            present.put(argument.getKey(), argument.getValue());
        }
        return present;
    }

    /**
     * A required name in the address grammar: missing, it is refused as
     * missing; not a string, as not a string; outside the grammar, as outside
     * the address (C8.7, {@code ADDRESS_MALFORMED} naming the argument).
     */
    private static String name(ProcessVerb verb, VerbName call, Map<String, Object> present,
                               String name, Form grammar,
                               Function<String, Optional<String>> parse) {
        Argument argument = verb.declared(name);
        ArgumentPath path = ArgumentPath.of(argument);
        Object value = present.get(name);
        if (value == null) {
            throw new SurfaceException(Refusal.argumentMissing(call, path));
        }
        if (!(value instanceof String text)) {
            throw new SurfaceException(Refusal.argumentInvalid(call, path, Form.STRING));
        }
        return parse.apply(text).orElseThrow(() ->
            new SurfaceException(Refusal.argumentInvalid(call, path, grammar)));
    }

    /**
     * The number of a version: absent where none was sent, otherwise a whole
     * number from 1 to the largest the store holds, and refused as one where
     * it is anything else.
     */
    private static Optional<Integer> ordinal(ProcessVerb verb, VerbName call,
                                             @Nullable Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if ((value instanceof Integer || value instanceof Long)
                && value instanceof Number number
                && number.longValue() >= 1 && number.longValue() <= Integer.MAX_VALUE) {
            return Optional.of(number.intValue());
        }
        throw new SurfaceException(Refusal.argumentInvalid(call,
            ArgumentPath.of(verb.declared(VERSION)), Form.WHOLE_NUMBER_FROM_ONE));
    }
}
