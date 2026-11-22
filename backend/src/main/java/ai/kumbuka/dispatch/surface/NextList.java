/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

/**
 * The calls open to a caller, with one sentence each (TAR-0004 section 7).
 *
 * <p>A step is what {@code next} carries in an answer and in a refusal: the
 * call by its name on the caller's surface, and what it does. Which calls are
 * open on an object is the kernel's to decide, and is built where the kernel
 * that decides it is bound; this class holds the shape of one step.
 */
public final class NextList {

    private NextList() {
    }

    /** One open call: its name on the caller's surface, and what it does. */
    public record Step(VerbName name, String does) {

        /** The call as the wire carries it. */
        public String call() {
            return name.toString();
        }
    }
}
