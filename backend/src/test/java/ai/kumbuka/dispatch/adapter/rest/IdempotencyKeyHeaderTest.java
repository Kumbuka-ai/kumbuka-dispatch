/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.rest;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * How the REST surface reads the {@code Idempotency-Key} header (C10.4).
 *
 * <p>A header sent empty is an empty key, handed on to the form check that
 * refuses it as {@code ARGUMENT_INVALID}; it is never read as no key, which
 * would let the call run without the idempotency its caller asked for. The
 * refusal itself, on both surfaces and for every call that takes a key, is
 * observed on the wire in {@code IdempotencyIT}.
 */
@Disabled(IdempotencyKeyHeaderTest.REBUILT)
class IdempotencyKeyHeaderTest {

    static final String REBUILT = "rebuilt in T4: the REST surface reads an empty Idempotency-Key "
        + "header as an empty key and a missing one as no key";

    @Test
    void an_empty_header_is_an_empty_key_on_every_call_that_takes_one() {
        fail(REBUILT);
    }

    @Test
    void a_sent_key_is_handed_on_and_no_header_is_no_key() {
        fail(REBUILT);
    }
}
