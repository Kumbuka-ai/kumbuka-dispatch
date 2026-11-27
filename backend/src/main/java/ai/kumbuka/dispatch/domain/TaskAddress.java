/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

/**
 * Where a task lives: {@code dispatch://<scope>/<selector>/<number>.<sub>},
 * the task of its sub in the instance of its number.
 *
 * @param instance the instance the task belongs to
 * @param sub      its place in the instance, from 1
 */
public record TaskAddress(InstanceAddress instance, int sub) {

    /** The complete address of the task. */
    public String complete() {
        return instance.task(sub);
    }

    /** Whether the task lies in the instance given. */
    public boolean in(InstanceAddress other) {
        return instance.equals(other);
    }
}
