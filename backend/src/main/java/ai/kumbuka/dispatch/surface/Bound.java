/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.PageRequest;

/**
 * A declared bound on a value, as a text names it.
 *
 * <p>A placeholder type of DEC-0043 R18. An enum, so the set of bounds a text
 * can name is the set declared here, each taken from the constant the service
 * enforces; a number a caller sent has no way to become one.
 */
public enum Bound {

    /** The most tasks one page of a listing holds (ADR-0049). */
    PAGE_SIZE_MAX(PageRequest.MAX_PAGE_SIZE),

    /** The tasks one page holds when the caller names no size (ADR-0049). */
    PAGE_SIZE_DEFAULT(PageRequest.DEFAULT_PAGE_SIZE),

    /** The most characters a call's description carries (concept section 3.5). */
    DESCRIPTION_BUDGET(SurfaceDeclaration.DESCRIPTION_BUDGET);

    private final int value;

    Bound(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
