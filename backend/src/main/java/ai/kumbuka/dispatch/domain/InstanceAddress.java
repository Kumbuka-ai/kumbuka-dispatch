/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

/**
 * Where an instance lives: {@code dispatch://<scope>/<selector>/<number>}, and
 * below it its tasks, {@code .../<number>.<sub>}.
 *
 * <p>The complete form is the only one that leaves this service. The scope is
 * the name the caller gave and the directory resolved, never the internal id.
 *
 * @param scope    the scope's name
 * @param selector the selector the instance is numbered under
 * @param number   the instance's number within its selector
 */
public record InstanceAddress(String scope, String selector, long number) {

    /** The complete address of the instance. */
    public String complete() {
        return ExchangeAddress.SCHEME + "://" + scope + "/" + selector + "/" + number;
    }

    /** The complete address of a task of the instance, by its sub. */
    public String task(int sub) {
        return complete() + "." + sub;
    }
}
