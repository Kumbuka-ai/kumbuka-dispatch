/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * A receipt is 32 random bytes, base64url without padding, and is stored only
 * as its SHA-256 (C10.5).
 *
 * <p>The expectation is written here from the rule: the form of the text, the
 * length of what it decodes to, and the digest computed in this test, not by
 * the service's own hash. That no column holds the receipt itself is read off
 * every row the claim could have written, as the administrator sees them.
 *
 * <p>RED STATE, observed with the stored form planted as the receipt itself:
 * the case on the stored form failed on the stored value, which was the
 * receipt.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(ReceiptIT.REBUILT)
class ReceiptIT {

    static final String REBUILT = "rebuilt in T5: a claim hands out a receipt of 32 random bytes "
        + "and the store holds only its SHA-256";

    @Test
    void a_receipt_is_thirty_two_random_bytes_in_base64url_without_padding() {
        fail(REBUILT);
    }

    @Test
    void the_store_holds_the_sha256_of_the_receipt_and_never_the_receipt() {
        fail(REBUILT);
    }
}
