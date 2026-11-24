/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.InstanceCall;

import java.util.List;

/**
 * The calls open to a caller, with one sentence each (TAR-0004 section 7).
 *
 * <p>A step is what {@code next} carries in an answer and in a refusal: the
 * call by its name on the caller's surface, and what it does. Which calls are
 * open on an object is the kernel's to decide; this class names them in the
 * vocabulary of the caller's surface.
 */
public final class NextList {

    private NextList() {
    }

    /** The calls the kernel names as open on an instance, as one surface names them. */
    public static List<Step> of(List<InstanceCall> open, Surface surface) {
        NextStepTexts texts = Texts.next();
        return open.stream().map(call -> switch (call) {
            case CONCLUDE -> new Step(VerbName.of(ProcessVerb.CONCLUDE, surface),
                texts.conclude());
            case WITHDRAW -> new Step(VerbName.of(ProcessVerb.WITHDRAW, surface),
                texts.withdrawInstance());
            case REFERENCE_ATTACH -> new Step(VerbName.of(ProcessVerb.REFERENCE_ATTACH, surface),
                texts.referenceAttach());
            case REFERENCE_DETACH -> new Step(VerbName.of(ProcessVerb.REFERENCE_DETACH, surface),
                texts.referenceDetach());
        }).toList();
    }

    /** One open call: its name on the caller's surface, and what it does. */
    public record Step(VerbName name, String does) {

        /** The call as the wire carries it. */
        public String call() {
            return name.toString();
        }
    }
}
