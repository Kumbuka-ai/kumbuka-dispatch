/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

/**
 * Where a task lives inside its scope: {@code sprint/149.2}.
 *
 * <p>A record rather than a string, because every one of these parts is
 * checked somewhere and a string would have to be re-parsed at each of them.
 * The display form is a rendering of this, never the identity. An addendum has
 * no address: it hangs on the text it supplements.
 *
 * @param selector the declared bracket name
 * @param number   the bracket's number in its circle
 * @param sub      0 for the bracket root, 1..n for its children
 */
public record ExchangeAddress(String selector, int number, int sub) {

    public ExchangeAddress {
        if (selector == null || selector.isBlank()) {
            throw new DispatchException(DispatchException.Reason.SELECTOR_NOT_DECLARED,
                "an address must name a selector");
        }
    }

    public static ExchangeAddress bracket(String selector, int number) {
        return new ExchangeAddress(selector, number, 0);
    }

    public static ExchangeAddress child(String selector, int number, int sub) {
        return new ExchangeAddress(selector, number, sub);
    }

    /**
     * The scheme this address belongs to.
     *
     * <p>Here rather than at the surface, because the complete form is built
     * here and a constant used in one place and declared in another is a
     * constant that gets duplicated the first time somebody cannot find it.
     */
    public static final String SCHEME = "dispatch";

    /**
     * The complete address: {@code dispatch://<scope>/<selector>/<number>.<sub>}.
     *
     * <p><strong>The only form that leaves this service.</strong> {@link
     * #toString()} renders the short form, which stays inside it — never on
     * the wire and never in a log line. The distinction is the whole of
     * defect three: a caller that reads {@code satellite/26.2} out of an
     * answer and puts it into the next call is refused, and nothing in the
     * answer told it why.
     *
     * @param scopeSlug the scope as the caller names it, never the internal id
     */
    public String complete(String scopeSlug) {
        return SCHEME + "://" + scopeSlug + "/" + selector + "/" + number + "." + sub;
    }

    /**
     * The short form, {@code <selector>/<number>.<sub>}, for use inside this
     * service only.
     *
     * <p>Deliberately NOT the wire form. Every refusal message that names an
     * address builds it with {@link #complete}. The short form is what the
     * kernel has without a scope: it names the tasks in the offenders of a
     * refusal, which the adapter reads back into complete addresses before
     * answering; it spells an address in the canonical form of a filter, of
     * which a cursor's fingerprint is taken; it enters the digest an
     * idempotency key is checked against; and it names the task in the
     * message of a {@link DispatchException}. No log line carries it: it
     * holds the selector, a name the tenant chose, and a log line carries no
     * value a caller or tenant chose.
     */
    @Override
    public String toString() {
        return selector + "/" + number + "." + sub;
    }
}
