/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.surface.Refusal;
import ai.kumbuka.dispatch.surface.UnexpectedFailures;
import ai.kumbuka.dispatch.surface.VerbName;

/**
 * A refusal of the domain or of the platform's directory under a call, as the
 * caller is told it. Both edges word it here, after they caught it (C8.2).
 *
 * <p>The domain's message is discarded; the caller reads a declared message.
 * Three causes are one {@code NOT_FOUND}, byte for byte: nothing at the
 * address, a scope the caller may not see, and a caller holding no permission
 * of this service at all, who sees no scope of it (status classes of C8.7,
 * REQ-0017). A scope of a kind this service does not serve is said as such.
 * Every other reason is a defect of ours under this call.
 */
public final class CallRefusals {

    private CallRefusals() {
    }

    /** A refusal of the domain, worded against the call it refused. */
    public static Refusal of(DispatchException refused, VerbName call) {
        return switch (refused.reason()) {
            case NOT_FOUND, SCOPE_UNRESOLVED, ACTOR_UNKNOWN -> Refusal.notFound();
            case SCOPE_KIND_UNSUPPORTED -> Refusal.scopeKindUnsupported(call);
            default -> UnexpectedFailures.refuse(call, refused);
        };
    }
}
