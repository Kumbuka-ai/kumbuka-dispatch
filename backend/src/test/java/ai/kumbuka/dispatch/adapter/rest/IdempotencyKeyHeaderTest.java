/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.rest;

import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.Surface;
import ai.kumbuka.dispatch.surface.VerbName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the REST surface reads the {@code Idempotency-Key} header (C10.4).
 *
 * <p>A header sent empty is an empty key, handed on to the form check that
 * refuses it as {@code ARGUMENT_INVALID}; it is never read as no key, which
 * would let the call run without the idempotency its caller asked for. The
 * refusal itself, on both surfaces and for every call that takes a key, is
 * observed on the wire in {@code IdempotencyIT}.
 */
class IdempotencyKeyHeaderTest {

    @Test
    void an_empty_header_is_an_empty_key_on_every_call_that_takes_one() {
        for (ProcessVerb verb : List.of(ProcessVerb.CREATE, ProcessVerb.ANNOTATE,
                ProcessVerb.CLAIM, ProcessVerb.CLAIM_NEXT)) {
            for (String sent : List.of("", " ")) {
                assertThat(TaskResource.key(VerbName.of(verb, Surface.REST), verb, List.of(sent)))
                    .as("RED STATE, observed: the empty Idempotency-Key header on %s was read "
                        + "as no key, and the call ran without the idempotency it asked for",
                        verb.verb())
                    .contains("");
            }
        }
    }

    @Test
    void a_sent_key_is_handed_on_and_no_header_is_no_key() {
        VerbName create = VerbName.of(ProcessVerb.CREATE, Surface.REST);
        assertThat(TaskResource.key(create, ProcessVerb.CREATE, List.of("create-once")))
            .contains("create-once");
        assertThat(TaskResource.key(create, ProcessVerb.CREATE, List.of()))
            .as("a request without the header sends no key")
            .isEqualTo(Optional.empty());
        assertThat(TaskResource.key(VerbName.of(ProcessVerb.READ, Surface.REST),
                ProcessVerb.READ, List.of("")))
            .as("a call that takes no key does not read the header")
            .isEqualTo(Optional.empty());
    }
}
