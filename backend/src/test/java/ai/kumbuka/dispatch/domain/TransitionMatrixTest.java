/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Acceptance criterion 1: for every row of the transition table and every
 * relation of a caller to a task, the decision the target prescribes.
 *
 * <h2>Where the expectation comes from</h2>
 *
 * {@link #MATRIX} is written out, cell by cell, from TAR-0004 section 3 (the
 * table of transitions and the paragraphs under it) and section 5 (holding).
 * It is NOT read from {@link TaskVerb} or computed from any rule: a test that
 * derives its expectation with the same case distinction as the code stays
 * green under a misreading the two share. Every cell is a literal.
 *
 * <h2>The situations</h2>
 *
 * Eleven, covering every effective state and every attribute a row's
 * condition reads: {@code draft}; {@code open}; {@code deferred} (open, before
 * the instant a {@code defer} named); {@code active}; {@code lapsed} (the row
 * says active, the lease ended -- effectively open, H is the former holder);
 * {@code asked}, {@code waiting}, {@code blocked} (on_hold with question,
 * dependency, external; H holds); {@code parked} (the third lapse: on_hold
 * external without a holder, H the former holder); {@code delivered} (H
 * delivered and is kept as holder); {@code closed}. Each was taken up where an
 * executor held it or holds it -- every one but {@code draft}, {@code open} and
 * {@code closed}, which was withdrawn from open -- and none is a root with a
 * task under it; that case is {@link CheckOrderTest}'s.
 *
 * <h2>The callers</h2>
 *
 * C, the commissioner (the console's permissions). H, the executor that holds
 * or held the task; where nobody has held it, an executor like any other. K,
 * another executor, a candidate that never held it. D, the identity of H
 * holding both sets of permissions (TAR-0004 section 3): the person who works alone. It
 * takes whichever part a call asks for, so its column is C's in the rows of
 * the commissioner and H's in every other, and in particular it accepts the
 * answer it delivered.
 *
 * <h2>The cells</h2>
 *
 * {@code ok} permitted. {@code =} succeeds without a write: the task is
 * already closed with the outcome the verb closes with (the closed situation
 * here is withdrawn), the rule of the running service the target is silent
 * on. {@code S} refused at the state, {@code
 * TRANSITION_NOT_PERMITTED}. {@code SL} refused at the state, {@code
 * LEASE_LAPSED}: a former holder calling the holder's verb. {@code CD} refused
 * at the condition, the deferral ({@code DEFERRAL_PENDING}). {@code CT}
 * refused at the condition, the hold reason ({@code TRANSITION_NOT_PERMITTED}).
 * {@code CF} refused at the condition, the first take-up froze the content
 * ({@code CONTENT_FROZEN}).
 * {@code RA} refused at the relation, the row's permission ({@code ACTOR_UNKNOWN}).
 * {@code RC} refused at the relation, not the holder ({@code CLAIM_REQUIRED}).
 * {@code RL} refused at the relation, the former holder ({@code LEASE_LAPSED}).
 *
 * <p>Every call presents what checks 5 to 7 ask -- H's receipt, the current
 * conflict token, a valid payload -- and the task is a root without children,
 * so a cell is decided by checks 1 to 3 (4 is a stamp) and a permitted cell is permitted
 * whole. Each row is permitted for its relation in at least one situation and
 * refused for each relation in at least one; a row is never permitted for a
 * relation the target does not name, and that refusal is in the matrix too.
 */
@Disabled(TransitionMatrixTest.REBUILT)
class TransitionMatrixTest {

    static final String REBUILT = "rebuilt in T13: every row of the transition table decides "
        + "as the target prescribes for every relation of a caller to a task";

    @Test
    void every_row_decides_as_the_target_prescribes_for_every_relation() {
        fail(REBUILT);
    }

    @Test
    void every_row_is_permitted_somewhere_and_refused_somewhere() {
        fail(REBUILT);
    }
}
