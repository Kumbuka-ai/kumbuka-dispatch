/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import ai.kumbuka.dispatch.domain.ExternalReference;
import ai.kumbuka.dispatch.domain.InstanceAddress;
import ai.kumbuka.dispatch.domain.InstanceService;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The form check of a call: its arguments closed against the declaration and
 * read into what the verb surface takes (C1.2).
 *
 * <p>Once for both adapters, which hand over the call's arguments as one map:
 * the assistant surface its tool arguments, the REST surface the path, the
 * query, the body and the {@code If-Match} header of the request. Every
 * refusal here is decided without knowing a scope and answers 400 before
 * anything else is read (C1.3): an argument the call does not declare, at the
 * top or under {@code fields}, a required one missing, a value outside its
 * form, and a scope, a selector or an address outside the address grammar. A
 * refusal names declared arguments only, never a value or a name the caller
 * sent (DEC-0043 R18, R19). What the version of an instance declares -- its
 * start contract, its outcome labels -- is checked by the kernel, once the
 * scope is resolved.
 *
 * <p>JSON {@code null} is an argument not sent, at every name and every depth
 * (DEC-0043 R17, C7.4).
 */
public final class CallForm {

    private static final String SCOPE = "scope";
    private static final String SELECTOR = "selector";
    private static final String VERSION = "version";
    private static final String ADDRESS = "address";

    private CallForm() {
    }

    /**
     * The arguments of {@code read_definition}, read.
     *
     * @param version the number of the version; absent for the active one
     */
    public record ReadDefinition(String scope, String selector, @Nullable Integer version) {
    }

    /** The arguments of {@code start}, read: where, and what the start hands in. */
    public record Start(String scope, String selector, InstanceService.Start start) {
    }

    /** The arguments of {@code conclude} or {@code withdraw}, read. */
    public record Close(ProcessVerb verb, InstanceAddress address,
                        InstanceService.Closing closing) {
    }

    /** The arguments of {@code reference_attach} or {@code reference_detach}, read. */
    public record Refer(InstanceAddress address, ExternalReference.Kind kind, String value) {
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

    /** Reads the arguments of {@code start}, or refuses their form. */
    public static Start start(Surface surface, Map<String, Object> given) {
        ProcessVerb verb = ProcessVerb.START;
        VerbName call = VerbName.of(verb, surface);
        Map<String, Object> present = closed(verb, call, given);
        String scope = name(verb, call, present, SCOPE, Form.SCOPE, AddressParser::scopeOf);
        String selector = name(verb, call, present, SELECTOR, Form.SELECTOR,
            AddressParser::selectorOf);
        Fields fields = fields(verb, call, present);
        return new Start(scope, selector, new InstanceService.Start(
            fields.text("title"), fields.optionalText("input"), fields.optionalText("trigger"),
            fields.list("items"), fields.list("references"), fields.object("values")));
    }

    /** Reads the arguments of {@code conclude} or {@code withdraw}, or refuses their form. */
    public static Close close(ProcessVerb verb, Surface surface, Map<String, Object> given) {
        VerbName call = VerbName.of(verb, surface);
        Map<String, Object> present = closed(verb, call, given);
        InstanceAddress address = address(verb, call, present);
        Fields fields = fields(verb, call, present);
        return new Close(verb, address, new InstanceService.Closing(
            optionalTop(verb, call, present, "conflict_token"),
            optionalTop(verb, call, present, "confirmation"),
            fields.text("text"), fields.optionalOneOf("verdict"),
            fields.optionalText("outcome"), fields.optionalText("translation_basis")));
    }

    /** Reads the arguments of {@code reference_attach} or {@code reference_detach}. */
    public static Refer refer(ProcessVerb verb, Surface surface, Map<String, Object> given) {
        VerbName call = VerbName.of(verb, surface);
        Map<String, Object> present = closed(verb, call, given);
        InstanceAddress address = address(verb, call, present);
        Fields fields = fields(verb, call, present);
        String kind = fields.oneOf("kind");
        return new Refer(address, ExternalReference.Kind.valueOf(kind.toUpperCase(Locale.ROOT)),
            fields.text("value"));
    }

    // ======================================================================
    // The top level
    // ======================================================================

    /**
     * The arguments that arrived with a value, after refusing a name the call
     * does not declare. The refusal lists what the call accepts and carries
     * none of the names that arrived. {@code fields} is a name of every call
     * that writes.
     */
    static Map<String, Object> closed(ProcessVerb verb, VerbName call, Map<String, Object> given) {
        Map<String, Object> present = new LinkedHashMap<>();
        for (Map.Entry<String, Object> argument : given.entrySet()) {
            if (argument.getValue() == null) {
                continue;
            }
            boolean declared = verb.argument(argument.getKey()).isPresent()
                || verb.hasFields() && Argument.FIELDS.equals(argument.getKey());
            if (!declared) {
                List<ArgumentPath> accepts = new ArrayList<>(ArgumentPath.of(verb.topArguments()));
                if (verb.hasFields()) {
                    accepts.add(ArgumentPath.FIELDS);
                }
                throw new SurfaceException(Refusal.argumentUnknown(call, false, accepts));
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

    /** The complete address of an instance, in the grammar of its address. */
    private static InstanceAddress address(ProcessVerb verb, VerbName call,
                                           Map<String, Object> present) {
        Argument argument = verb.declared(ADDRESS);
        ArgumentPath path = ArgumentPath.of(argument);
        Object value = present.get(ADDRESS);
        if (value == null) {
            throw new SurfaceException(Refusal.argumentMissing(call, path));
        }
        if (!(value instanceof String text)) {
            throw new SurfaceException(Refusal.argumentInvalid(call, path, Form.STRING));
        }
        return AddressParser.instanceOf(text).orElseThrow(() ->
            new SurfaceException(Refusal.argumentInvalid(call, path, Form.INSTANCE_ADDRESS)));
    }

    /** An optional string at the top level; absent where none arrived. */
    private static @Nullable String optionalTop(ProcessVerb verb, VerbName call,
                                                Map<String, Object> present, String name) {
        Object value = present.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new SurfaceException(Refusal.argumentInvalid(call,
                ArgumentPath.of(verb.declared(name)), Form.STRING));
        }
        return text;
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

    // ======================================================================
    // Under fields
    // ======================================================================

    /**
     * The members of {@code fields} that arrived with a value, after refusing
     * a {@code fields} that is no object and a member the call does not
     * declare there. An absent {@code fields} is an empty one, so that a
     * required member of it is refused as missing.
     */
    private static Fields fields(ProcessVerb verb, VerbName call, Map<String, Object> present) {
        Object raw = present.get(Argument.FIELDS);
        Map<String, Object> members = new LinkedHashMap<>();
        if (raw != null) {
            if (!(raw instanceof Map<?, ?> map)) {
                throw new SurfaceException(Refusal.argumentInvalid(call, ArgumentPath.FIELDS,
                    Form.FIELDS_OBJECT));
            }
            for (Map.Entry<?, ?> member : map.entrySet()) {
                if (member.getValue() == null) {
                    continue;
                }
                String name = String.valueOf(member.getKey());
                if (verb.field(name).isEmpty()) {
                    throw new SurfaceException(Refusal.argumentUnknown(call, true,
                        ArgumentPath.of(verb.fieldArguments())));
                }
                members.put(name, member.getValue());
            }
        }
        return new Fields(verb, call, members);
    }

    /** The members of {@code fields} of one call, read one by one in their forms. */
    private record Fields(ProcessVerb verb, VerbName call, Map<String, Object> members) {

        /** A required string. */
        String text(String name) {
            ArgumentPath path = ArgumentPath.of(verb.declaredField(name));
            String text = optionalText(name);
            if (text == null) {
                throw new SurfaceException(Refusal.argumentMissing(call, path));
            }
            return text;
        }

        /** An optional string; absent where none arrived. */
        @Nullable String optionalText(String name) {
            Object value = members.get(name);
            if (value == null) {
                return null;
            }
            if (!(value instanceof String text)) {
                throw new SurfaceException(Refusal.argumentInvalid(call,
                    ArgumentPath.of(verb.declaredField(name)), Form.STRING));
            }
            return text;
        }

        /** A required value of a closed set. */
        String oneOf(String name) {
            String value = optionalOneOf(name);
            if (value == null) {
                throw new SurfaceException(Refusal.argumentMissing(call,
                    ArgumentPath.of(verb.declaredField(name))));
            }
            return value;
        }

        /** An optional value of a closed set; absent where none arrived. */
        @Nullable String optionalOneOf(String name) {
            Argument argument = verb.declaredField(name);
            String value = optionalText(name);
            if (value != null && !argument.valueNames().contains(value)) {
                throw new SurfaceException(Refusal.argumentInvalid(call,
                    ArgumentPath.of(argument), Form.ONE_OF));
            }
            return value;
        }

        /** A list of strings, each obeying the argument's pattern; empty where none arrived. */
        List<String> list(String name) {
            Argument argument = verb.declaredField(name);
            Object value = members.get(name);
            if (value == null) {
                return List.of();
            }
            if (!(value instanceof List<?> list)) {
                throw new SurfaceException(Refusal.argumentInvalid(call,
                    ArgumentPath.of(argument), Form.LIST_OF_STRINGS));
            }
            Pattern element = Pattern.compile(String.valueOf(argument.itemPattern()));
            List<String> read = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                if (!(list.get(i) instanceof String text) || !element.matcher(text).find()) {
                    throw new SurfaceException(Refusal.argumentInvalid(call,
                        ArgumentPath.of(argument, i), Form.LIST_OF_STRINGS));
                }
                read.add(text);
            }
            return List.copyOf(read);
        }

        /** An object of the caller's own keys; empty where none arrived. */
        Map<String, Object> object(String name) {
            Object value = members.get(name);
            if (value == null) {
                return Map.of();
            }
            if (!(value instanceof Map<?, ?> map)) {
                throw new SurfaceException(Refusal.argumentInvalid(call,
                    ArgumentPath.of(verb.declaredField(name)), Form.OBJECT));
            }
            Map<String, Object> read = new LinkedHashMap<>();
            map.forEach((key, member) -> read.put(String.valueOf(key), member));
            return read;
        }
    }
}
