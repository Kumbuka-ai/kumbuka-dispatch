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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * How the REST surface reads the {@code Idempotency-Key} header (C10.4).
 *
 * <p>A header sent empty is an empty key, handed on to the form check that
 * refuses it as {@code ARGUMENT_INVALID}; it is never read as no key, which
 * would let the call run without the idempotency its caller asked for. The
 * refusal itself, on both surfaces, is observed on the wire in {@code
 * IdempotencyIT}. {@code create} is the one call of this service that takes a
 * key so far; {@code claim} and {@code annotate} take one with the orders that
 * bind them.
 */
class IdempotencyKeyHeaderTest {

    private static final VerbName CREATE = VerbName.of(ProcessVerb.CREATE, Surface.REST);

    @Test
    void an_empty_header_is_an_empty_key_on_create() {
        for (String sent : List.of("", " ")) {
            assertThat(ProcessResource.key(CREATE, ProcessVerb.CREATE, List.of(sent)))
                .as("RED STATE, observed: the empty Idempotency-Key header on create was read "
                    + "as no key, and the call ran without the idempotency it asked for")
                .contains("");
        }
    }

    @Test
    void a_sent_key_is_handed_on_and_no_header_is_no_key() {
        assertThat(ProcessResource.key(CREATE, ProcessVerb.CREATE, List.of("create-once")))
            .contains("create-once");
        assertThat(ProcessResource.key(CREATE, ProcessVerb.CREATE, List.of()))
            .as("a request without the header sends no key")
            .isEqualTo(Optional.empty());
        assertThat(ProcessResource.key(CREATE, ProcessVerb.CREATE, null))
            .as("nor does one whose headers name it not at all")
            .isEqualTo(Optional.empty());
        assertThat(ProcessResource.key(VerbName.of(ProcessVerb.SEND, Surface.REST),
                ProcessVerb.SEND, List.of("")))
            .as("a call that takes no key does not read the header")
            .isEqualTo(Optional.empty());
    }

    @Test
    @Disabled("rebuilt in T5: the REST surface reads an empty Idempotency-Key header on claim "
        + "as an empty key")
    void an_empty_header_is_an_empty_key_on_claim() {
        fail("rebuilt in T5");
    }

    @Test
    @Disabled("rebuilt in T11: the REST surface reads an empty Idempotency-Key header on "
        + "annotate as an empty key")
    void an_empty_header_is_an_empty_key_on_annotate() {
        fail("rebuilt in T11");
    }
}
