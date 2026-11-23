/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import ai.kumbuka.dispatch.surface.NextList;
import ai.kumbuka.dispatch.surface.Refusal;

import java.util.List;

/**
 * Every refusal of the verb surface, as the record both surfaces put on the
 * wire (C7.1).
 *
 * <p>The answers of the calls are built here as each call is bound to the
 * kernel of the store line; no call is bound yet. No answer names the
 * identity of an actor.
 */
public final class Payloads {

    private Payloads() {
    }

    /**
     * A refusal, in the envelope both surfaces answer it with (C8.4). A stale
     * conflict token carries the object's fields as the caller's read would
     * answer them (DEC-0041).
     */
    public static RefusalPayload refusal(Refusal refusal) {
        Refusal.Data data = refusal.data();
        if (data == null) {
            return new RefusalPayload(refusal.code().name(), refusal.message(), null);
        }
        List<NextStepPayload> next = data.next() == null ? null : next(data.next());
        return new RefusalPayload(refusal.code().name(), refusal.message(),
            new RefusalDataPayload(data.attempted(), data.address(), data.state(), data.current(), next,
                data.waitingFor(), data.offenders(), data.accepts(), data.children(),
                data.confirmation(), data.conflictToken(), data.service(), data.reference()));
    }

    /** The calls open to a caller, each with the sentence that says what it does. */
    public static List<NextStepPayload> next(List<NextList.Step> steps) {
        return steps.stream().map(step -> new NextStepPayload(step.call(), step.does())).toList();
    }
}
