/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import java.util.Objects;

/**
 * A declared refusal reason as a text names it.
 *
 * <p>A placeholder type of DEC-0043 R18, built only from a declared {@link
 * RefusalCode}.
 */
public final class ReasonCode {

    private final RefusalCode code;

    private ReasonCode(RefusalCode code) {
        this.code = Objects.requireNonNull(code, "code");
    }

    public static ReasonCode of(RefusalCode code) {
        return new ReasonCode(code);
    }

    @Override
    public String toString() {
        return code.name();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ReasonCode that && code == that.code;
    }

    @Override
    public int hashCode() {
        return code.hashCode();
    }
}
