/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.fixture;

import io.quarkus.qute.i18n.Message;
import io.quarkus.qute.i18n.MessageBundle;

/**
 * A planted violation for {@code MessagePlaceholderTypeTest}: a placeholder
 * of type String, which can carry anything a caller sent.
 */
@MessageBundle("fixturestring")
public interface StringPlaceholderFixture {

    @Message("{value}")
    String echo(String value);
}
