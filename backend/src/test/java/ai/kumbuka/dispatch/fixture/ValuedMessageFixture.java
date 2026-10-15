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
 * A planted violation for {@code MessageBundleTest}: a bundle whose message
 * carries its text in Java. Lives in the test tree and nowhere near the
 * surface; the guard must find it, or the guard is not looking.
 */
@MessageBundle("fixturevalued")
public interface ValuedMessageFixture {

    @Message("A text written in Java.")
    String written();
}
