/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

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
 * <h2>The failure's class names, never its message</h2>
 *
 * The log line carries the reference, the call and the class names of the
 * exception chain -- and never the exception object, its message or an address
 * the call carried (DEC-0051, C13.2, C13.3, F-0383). A stack trace and an
 * exception's message carry whatever the failing code held, a caller's value
 * or a commission's text included, and a log shipper carries them out of the
 * container past a boundary built as a missing grant; an address is written by
 * the caller. The class names say what failed; the reference finds the line,
 * and the request id on it joins it to the call. So the failure is never
 * handed to the logger: {@link FailureCause} reads the class names off it, and
 * is made here and nowhere else, as {@link FailureReference} is.
 */
public final class UnexpectedFailures {

    /** How deep the chain of causes is followed: a cycle must not hang the log line. */
    private static final int CHAIN_LIMIT = 8;

    private UnexpectedFailures() {
    }

    /**
     * Records the failure and answers the caller with its reference.
     *
     * @param call  the call, or null for a request that reached no call; the
     *              call the declaration names, never a name a request carried
     * @param cause what went wrong, or null where the failure arrived as a
     *              typed refusal the surface cannot serve
     */
    public static Refusal refuse(@Nullable VerbName call, @Nullable Throwable cause) {
        FailureReference reference = new FailureReference(UUID.randomUUID());
        FailureCause chain = new FailureCause(cause);
        if (call == null) {
            PlatformLog.unexpectedFailureBeforeACall(reference, chain);
        } else {
            PlatformLog.unexpectedFailure(call, reference, chain);
        }
        return Refusal.unexpected(call, reference.toString());
    }

    /** The reference a caller is told to report, minted for one failure. */
    public static final class FailureReference {

        private final UUID reference;

        private FailureReference(UUID reference) {
            this.reference = reference;
        }

        @Override
        public String toString() {
            return reference.toString();
        }
    }

    /**
     * The class names of a failure and its causes, outermost first. Read off
     * the chain by class and nothing else: no message, no stack frame.
     */
    public static final class FailureCause {

        private final String chain;

        private FailureCause(@Nullable Throwable failure) {
            if (failure == null) {
                this.chain = "none";
                return;
            }
            List<String> names = new ArrayList<>();
            Throwable at = failure;
            while (at != null && names.size() < CHAIN_LIMIT) {
                names.add(at.getClass().getName());
                at = at.getCause() == at ? null : at.getCause();
            }
            this.chain = String.join(" <- ", names);
        }

        @Override
        public String toString() {
            return chain;
        }
    }
}
