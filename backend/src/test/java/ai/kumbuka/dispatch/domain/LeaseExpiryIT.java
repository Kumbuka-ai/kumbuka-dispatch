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
 * A lease expires lazily, on read; nothing writes an expiry back (C11.2).
 *
 * <p>A lease is let run out as the clock would, with a write of its end into
 * the past. Every read after that answers the task as open again, and the row
 * stays exactly as the claim left it: still {@code active}, still holding its
 * holder, its lease end and its conflict token. The lapse becomes a write only
 * when a call that takes the task up decides on it.
 *
 * <p>RED STATE, observed with a read planted to write the lapse back: after the
 * read the row held neither its holder nor its lease end nor its receipt.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(LeaseExpiryIT.REBUILT)
class LeaseExpiryIT {

    static final String REBUILT = "rebuilt in T5: a lease expires lazily on read and the lapse "
        + "is written only when a take-up decides on it";

    @Test
    void a_lapsed_lease_is_read_as_open_and_nothing_is_written_back() {
        fail(REBUILT);
    }

    @Test
    void the_lapse_becomes_a_write_only_when_a_take_up_decides_on_it() {
        fail(REBUILT);
    }
}
