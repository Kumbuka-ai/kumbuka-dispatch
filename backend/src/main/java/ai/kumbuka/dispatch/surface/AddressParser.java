/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.ExchangeAddress;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the path parts of {@code {scope}/{selector}/{id}} into an address, and
 * refuses everything that is not one.
 *
 * <h2>Validation precedes parsing</h2>
 *
 * The production is applied to the raw string before anything is taken apart.
 * That order is the whole design: a parser that splits first and validates the
 * pieces afterwards has already decided what the pieces are, and its refusal
 * then describes the split rather than the input. Here a form error is a typed
 * rejection with nothing resolved, and a well-formed address of a thing that is
 * not there is a not-found from the store. <strong>The two classes never
 * merge.</strong> They are produced in different places by different code, and
 * this class is structurally unable to produce the second one — it never looks
 * anything up.
 *
 * <h2>What is tolerated and what is not</h2>
 *
 * What does not change identity is tolerated; what changes it is rejected. A
 * trailing slash changes nothing about which object is addressed, because the
 * occupied parts decide that, so it is tolerated on the way in and never
 * generated on the way out. Upper case is rejected and <strong>never
 * folded</strong>: folding would make two distinct strings resolve to one
 * identity, and an identity statement must not arise from leniency.
 *
 * <h2>Why the scope is a DNS label</h2>
 *
 * The address is a URI with the scope in the authority position, so its
 * grammar is the authority's and not a free slug. Checking it here rather than
 * at the directory keeps a malformed scope in stage 1, where the answer leaks
 * nothing — the directory's refusal necessarily reveals that a lookup happened.
 */
public final class AddressParser {

    /** The scheme of this service's addresses. */
    public static final String SCHEME = "dispatch";

    /** The names a scope cannot take: those of the REST surface's literal paths (C3.2). */
    public static final java.util.Set<String> RESERVED_SCOPES =
        java.util.Set.of("declaration", "whoami");

    private static final Pattern SCOPE = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

    private static final Pattern SELECTOR = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?");

    private static final Pattern ID = Pattern.compile("(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})");

    private AddressParser() {
    }

    /** The parts of a complete address, each checked by its own production. */
    public record Parts(String scope, String selector, String id) {
    }

    // ======================================================================
    // Reading, without refusing: the caller decides what a refusal names
    // ======================================================================

    /** The scope, where the text is a DNS label and no reserved name; empty otherwise. */
    public static Optional<String> scopeOf(String raw) {
        return present(raw) && SCOPE.matcher(raw).matches() && !RESERVED_SCOPES.contains(raw)
            ? Optional.of(raw) : Optional.empty();
    }

    /** The bracket kind, where the text has a declared name's shape; empty otherwise. */
    public static Optional<String> selectorOf(String raw) {
        return present(raw) && SELECTOR.matcher(raw).matches()
            ? Optional.of(raw) : Optional.empty();
    }

    /** The task a bracket kind and an id name, where both are in the grammar. */
    public static Optional<ExchangeAddress> itemOf(String rawSelector, String rawId) {
        if (selectorOf(rawSelector).isEmpty() || !present(rawId)) {
            return Optional.empty();
        }
        Matcher m = ID.matcher(rawId);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new ExchangeAddress(rawSelector, Integer.parseInt(m.group(1)),
            Integer.parseInt(m.group(2))));
    }

    /**
     * The parts of a complete address {@code dispatch://<scope>/<selector>/<id>},
     * where every part is in the grammar; empty otherwise. A trailing slash
     * changes nothing about what is addressed and is tolerated.
     */
    public static Optional<Parts> partsOf(String raw) {
        String prefix = SCHEME + "://";
        if (!present(raw) || !raw.startsWith(prefix)) {
            return Optional.empty();
        }
        String rest = raw.substring(prefix.length());
        if (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        String[] parts = rest.split("/", -1);
        if (parts.length != 3 || scopeOf(parts[0]).isEmpty()
                || itemOf(parts[1], parts[2]).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Parts(parts[0], parts[1], parts[2]));
    }

    // ======================================================================
    // Reading, refusing what is not an address
    // ======================================================================

    /**
     * The parts of a complete address, or a refusal: {@code ADDRESS_MALFORMED}
     * naming the address argument, and nothing of what arrived.
     */
    public static Parts uri(String raw) {
        return partsOf(raw).orElseThrow(AddressParser::malformed);
    }

    /** The task a bracket kind and an id name, or the same refusal. */
    public static ExchangeAddress item(String rawSelector, String rawId) {
        return itemOf(rawSelector, rawId).orElseThrow(AddressParser::malformed);
    }

    // ======================================================================
    // Writing
    // ======================================================================

    public static String render(ExchangeAddress address) {
        return address.number() + "." + address.sub();
    }

    public static String completeCollection(String scopeSlug, String selector) {
        return SCHEME + "://" + scopeSlug + "/" + selector;
    }

    private static boolean present(String raw) {
        return raw != null && !raw.isBlank();
    }

    private static SurfaceException malformed() {
        return new SurfaceException(Refusal.requestInvalid(
            ArgumentPath.of(ProcessVerb.READ.declared("address", Argument.Placement.TOP))));
    }
}
