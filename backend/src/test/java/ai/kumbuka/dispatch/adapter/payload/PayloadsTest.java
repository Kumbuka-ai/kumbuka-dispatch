/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * An answer as the service writes it: an instant to the microsecond, as the
 * database holds it, and a member with no value absent (C7.2, C7.3).
 *
 * <p>Measured on the CI runner, 2026-10-06: the answer to a claim carried the
 * lease end from the clock, {@code …42.538822988Z}, while every later read
 * carried the stored {@code …42.538823Z}, so a repeated claim looked as if it
 * had moved the lease. A clock with microsecond resolution, as on macOS, hides
 * the difference; this case does not depend on the clock. The rendering is the
 * serialiser's now, and the serialiser is what this writes with.
 */
@Disabled(PayloadsTest.REBUILT)
class PayloadsTest {

    static final String REBUILT = "rebuilt in T13: an answer writes an instant to the microsecond "
        + "and leaves a member with no value absent";

    @Test
    void an_instant_is_written_to_the_microsecond_as_it_is_stored() {
        fail(REBUILT);
    }

    @Test
    void a_member_with_no_value_is_absent() {
        fail(REBUILT);
    }
}
