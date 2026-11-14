/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

/**
 * The declared calls, one method each: what each adapter implements to make
 * them, and where each adapter checks the one permission a call names, with
 * {@code @PermissionsAllowed} on its implementation of the method.
 *
 * <p>One method per call rather than one for all, because the permission is
 * the call's: a single method would check one for every call or none.
 * {@link CallBinding#through} picks the method of a prepared call; each
 * method makes the call it is named for, and nothing else.
 */
public interface DeclaredCalls {

    CallBinding.Outcome send(CallBinding.Prepared call);

    CallBinding.Outcome recall(CallBinding.Prepared call);

    CallBinding.Outcome claim(CallBinding.Prepared call);

    CallBinding.Outcome release(CallBinding.Prepared call);

    CallBinding.Outcome defer(CallBinding.Prepared call);

    CallBinding.Outcome renew(CallBinding.Prepared call);

    CallBinding.Outcome ask(CallBinding.Prepared call);

    CallBinding.Outcome answer(CallBinding.Prepared call);

    CallBinding.Outcome hold(CallBinding.Prepared call);

    CallBinding.Outcome resume(CallBinding.Prepared call);

    CallBinding.Outcome deliver(CallBinding.Prepared call);

    CallBinding.Outcome rework(CallBinding.Prepared call);

    CallBinding.Outcome accept(CallBinding.Prepared call);

    CallBinding.Outcome reject(CallBinding.Prepared call);

    CallBinding.Outcome fail(CallBinding.Prepared call);

    CallBinding.Outcome withdraw(CallBinding.Prepared call);

    CallBinding.Outcome create(CallBinding.Prepared call);

    CallBinding.Outcome update(CallBinding.Prepared call);

    CallBinding.Outcome delete(CallBinding.Prepared call);

    CallBinding.Outcome read(CallBinding.Prepared call);

    CallBinding.Outcome readText(CallBinding.Prepared call);

    CallBinding.Outcome query(CallBinding.Prepared call);

    CallBinding.Outcome annotate(CallBinding.Prepared call);

    CallBinding.Outcome relate(CallBinding.Prepared call);

    CallBinding.Outcome unrelate(CallBinding.Prepared call);

    CallBinding.Outcome tagAttach(CallBinding.Prepared call);

    CallBinding.Outcome tagDetach(CallBinding.Prepared call);
}
