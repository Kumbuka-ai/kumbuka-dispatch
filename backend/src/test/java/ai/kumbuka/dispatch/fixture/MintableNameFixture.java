/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.fixture;

/**
 * A planted violation for {@code MessagePlaceholderTypeTest}: a name type that
 * a request could mint, through a public constructor that takes a string.
 */
public final class MintableNameFixture {

    private final String name;

    public MintableNameFixture(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
