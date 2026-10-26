/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import io.quarkus.qute.i18n.Message;
import io.quarkus.qute.i18n.MessageBundle;

/**
 * What each step of {@code next} does: one sentence per transition, and
 * the steps the surface adds itself. Worded as the act (DEC-0043 R21).
 */
@MessageBundle("next")
public interface NextStepTexts {

    @Message
    String send();

    @Message
    String recall();

    @Message
    String claim();

    @Message
    String claimNext();

    @Message
    String release();

    @Message
    String defer();

    @Message
    String renew();

    @Message
    String ask();

    @Message
    String answer();

    @Message
    String hold();

    @Message
    String resume();

    @Message
    String deliver();

    @Message
    String rework();

    @Message
    String accept();

    @Message
    String reject();

    @Message
    String fail();

    @Message
    String withdraw();

    @Message
    String readTheCommission();

    @Message
    String readTheAnswer();

    @Message
    String listAfterAnEmptyDraw();
}
