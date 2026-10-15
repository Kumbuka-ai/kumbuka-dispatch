/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The refusal catalogue's start-up guard, observed refusing (C8.5).
 *
 * <p>"A code not in the catalogue cannot be returned" is a statement about a
 * service that does not come up, and a guard nobody has watched refuse is a
 * description of one. Each way a catalogue can be incomplete is handed to the
 * guard here and the refusal read: a code missing, a code with no message, a
 * code with no remedy.
 *
 * <p>The catalogue is assembled here from the codes and their messages, with
 * a remedy written in; the real remedies are texts of a message bundle, read
 * through the running service, and the guard runs on its every start.
 */
class RefusalCatalogueGuardTest {

    @Test
    void every_code_has_a_message_to_be_worded_with() {
        assertThat(Arrays.stream(RefusalCode.values())
                .filter(code -> code.messages().isEmpty()).toList())
            .as("a code with no message cannot be worded, and would escape as an "
                + "unexpected failure the first time it is raised")
            .isEmpty();
    }

    @Test
    void a_catalogue_of_every_code_with_its_messages_and_a_remedy_passes() {
        RefusalCatalogueGuard.requireComplete(complete());
    }

    @Test
    void a_code_missing_from_the_catalogue_refuses_the_start() {
        Map<RefusalCode, RefusalCode.Declared> incomplete = complete();
        incomplete.remove(RefusalCode.CLAIM_HELD);

        assertThatThrownBy(() -> RefusalCatalogueGuard.requireComplete(incomplete))
            .as("RED STATE, observed: a catalogue missing one code refuses the start, and "
                + "names the code it is missing")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CLAIM_HELD");
    }

    @Test
    void a_code_with_no_message_refuses_the_start() {
        Map<RefusalCode, RefusalCode.Declared> blanked = complete();
        blanked.put(RefusalCode.NOT_FOUND,
            new RefusalCode.Declared(RefusalCode.NOT_FOUND, List.of(), "check the address"));

        assertThatThrownBy(() -> RefusalCatalogueGuard.requireComplete(blanked))
            .as("RED STATE, observed: a code declared with no message refuses the start — "
                + "a refusal nobody could word is not a declared refusal")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("NOT_FOUND");
    }

    @Test
    void a_code_with_no_remedy_refuses_the_start() {
        Map<RefusalCode, RefusalCode.Declared> blanked = complete();
        blanked.put(RefusalCode.DRAW_EMPTY, new RefusalCode.Declared(RefusalCode.DRAW_EMPTY,
            RefusalCode.DRAW_EMPTY.messages(), " "));

        assertThatThrownBy(() -> RefusalCatalogueGuard.requireComplete(blanked))
            .as("RED STATE, observed: a code with a blank remedy refuses the start — a "
                + "refusal nobody could act on is not a declared refusal")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DRAW_EMPTY");
    }

    /** Every code with its messages and a remedy written here. */
    private static Map<RefusalCode, RefusalCode.Declared> complete() {
        Map<RefusalCode, RefusalCode.Declared> catalogue = new LinkedHashMap<>();
        for (RefusalCode code : RefusalCode.values()) {
            catalogue.put(code, new RefusalCode.Declared(code, code.messages(), "act on it"));
        }
        return catalogue;
    }
}
