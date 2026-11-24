/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.domain.InstanceView;
import ai.kumbuka.dispatch.surface.NextList;
import ai.kumbuka.dispatch.surface.Refusal;
import ai.kumbuka.dispatch.surface.RefusalCode;
import ai.kumbuka.dispatch.surface.Surface;
import ai.kumbuka.dispatch.surface.UnexpectedFailures;
import ai.kumbuka.dispatch.surface.VerbName;

import java.util.Optional;

/**
 * A refusal of the domain or of the platform's directory under a call, as the
 * caller is told it. Both edges word it here, after they caught it (C8.2).
 *
 * <p>The domain's message is discarded; the caller reads a declared message.
 * Three causes are one {@code NOT_FOUND}, byte for byte: nothing at the
 * address, a scope the caller may not see, and a caller holding no permission
 * of this service at all, who sees no scope of it (status classes of C8.7,
 * REQ-0017). A scope of a kind this service does not serve is said as such;
 * so are a locked scope and one the caller may only read. A refusal of an act
 * on an instance carries what the domain hands with it: the instance as it
 * stands, the paths of the fields or parts concerned, or the unfinished tasks
 * with the confirmation handed out now. Every other reason is a defect of ours
 * under this call.
 */
public final class CallRefusals {

    /** The name this service goes by in a stale-token refusal (DEC-0041). */
    private static final String SERVICE = "dispatch";

    private CallRefusals() {
    }

    /**
     * A refusal of the domain, worded against the call it refused.
     *
     * @param surface the surface the caller came through, which names the
     *                calls a refusal lists as open
     */
    public static Refusal of(DispatchException refused, VerbName call, Surface surface) {
        return switch (refused.reason()) {
            case NOT_FOUND, SCOPE_UNRESOLVED, ACTOR_UNKNOWN -> Refusal.notFound();
            case SCOPE_KIND_UNSUPPORTED -> Refusal.scopeKindUnsupported(call);
            case SCOPE_LOCKED -> Refusal.scopeLocked(call);
            case SCOPE_READ_ONLY -> Refusal.scopeReadOnly(call);
            case INSTANCE_CLOSED -> standing(refused)
                .map(at -> Refusal.instanceClosed(call, situation(at, surface)))
                .orElseGet(() -> UnexpectedFailures.refuse(call, refused));
            case CONFLICT_TOKEN_MISSING -> Refusal.conflictTokenMissing(call);
            case CONFLICT_TOKEN_STALE -> standing(refused)
                .map(at -> Refusal.instanceTokenStale(call, situation(at, surface),
                    at.address().complete(), at.fields(), at.conflictToken(), SERVICE))
                .orElseGet(() -> UnexpectedFailures.refuse(call, refused));
            case CONFIRMATION_REQUIRED, CONFIRMATION_STALE -> refused.confirmation()
                .map(confirmation -> Refusal.tasksUnfinished(call, refused.offenders(),
                    confirmation,
                    refused.reason() == DispatchException.Reason.CONFIRMATION_STALE))
                .orElseGet(() -> UnexpectedFailures.refuse(call, refused));
            case CONTRACT_FIELD_MISSING -> contract(call, RefusalCode.Message.CONTRACT_FIELD_MISSING,
                refused);
            case CONTRACT_FIELD_UNDECLARED -> contract(call,
                RefusalCode.Message.CONTRACT_FIELD_UNDECLARED, refused);
            case CONTRACT_FIELD_MISTYPED -> contract(call,
                RefusalCode.Message.CONTRACT_FIELD_MISTYPED, refused);
            case CONTRACT_VALUE_UNENUMERATED -> contract(call,
                RefusalCode.Message.CONTRACT_VALUE_UNENUMERATED, refused);
            case RETURN_PART_MISSING -> contract(call, RefusalCode.Message.RETURN_PART_MISSING,
                refused);
            case RETURN_OUTCOME_UNDECLARED -> contract(call,
                RefusalCode.Message.RETURN_OUTCOME_UNDECLARED, refused);
            case REFERENCE_ABSENT -> contract(call, RefusalCode.Message.REFERENCE_ABSENT, refused);
            default -> UnexpectedFailures.refuse(call, refused);
        };
    }

    private static Optional<InstanceView> standing(DispatchException refused) {
        return refused.standing();
    }

    private static Refusal contract(VerbName call, RefusalCode.Message rule,
                                    DispatchException refused) {
        return Refusal.ofContract(call, rule, refused.offenders());
    }

    /** Where the instance stands, for the caller: its state and the calls open to it. */
    private static Refusal.Situation situation(InstanceView at, Surface surface) {
        return new Refusal.Situation(at.state(), NextList.of(at.open(), surface), null);
    }
}
