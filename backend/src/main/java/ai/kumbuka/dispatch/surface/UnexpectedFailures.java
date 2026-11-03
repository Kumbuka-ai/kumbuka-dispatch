/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The one place an {@code UNEXPECTED_FAILURE} is minted, so that its reference
 * is always in the log (C8.3).
 *
 * <p>The refusal tells the caller to report the reference in {@code
 * data.reference}, and that is a promise about the service: the reference is
 * written to the log with the failure, on every path that raises it. So the
 * mint and the log line are one act, and nobody adding a throw site can
 * separate them.
 *
 * <p>The log line carries the reference, the call and the class names of the
 * exception chain — and never the exception object, its message or an address
 * the call carried (DEC-0051, C13.2, C13.3, F-0383). A stack trace and an
 * exception's message carry whatever the failing code held, a caller's value
 * or a commission's text included, and a log shipper carries them out of the
 * container past a boundary built as a missing grant; an address is written by
 * the caller. The class names say what failed; the reference finds the line,
 * and the request id on it joins it to the call.
 */
public final class UnexpectedFailures {

    private static final Logger LOG = Logger.getLogger(UnexpectedFailures.class);

    /** How deep the chain of causes is followed: a cycle must not hang the log line. */
    private static final int CHAIN_LIMIT = 8;

    private UnexpectedFailures() {
    }

    /**
     * Records the failure and answers the caller with its reference.
     *
     * @param call  the call, or null for a request that reached no call
     * @param cause what went wrong, or null where the failure arrived as a
     *              typed refusal the surface cannot serve
     */
    public static Refusal refuse(@Nullable VerbName call, Throwable cause) {
        String named = call == null ? "this request" : call.toString();
        String reference = UUID.randomUUID().toString();
        String chain = classNames(cause);
        LOG.errorf("unexpected failure on %s, reference %s, cause %s", named, reference, chain);
        return Refusal.unexpected(call, reference);
    }

    /** The class names of an exception and its causes, outermost first. */
    static String classNames(Throwable cause) {
        if (cause == null) {
            return "none";
        }
        List<String> names = new ArrayList<>();
        Throwable at = cause;
        while (at != null && names.size() < CHAIN_LIMIT) {
            names.add(at.getClass().getName());
            at = at.getCause() == at ? null : at.getCause();
        }
        return String.join(" <- ", names);
    }
}
