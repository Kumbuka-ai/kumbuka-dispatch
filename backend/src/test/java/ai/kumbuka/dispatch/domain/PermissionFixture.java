/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The permissions the tests' callers hold, as the order of this step lists
 * them for the two realm roles: written here, never read from the source that
 * grants them, so that a test of the core does not take its caller from the
 * artefact under test.
 */
public final class PermissionFixture {

    /** What {@code dispatch-console} stands for. */
    public static final Set<DispatchPermission> CONSOLE = Set.copyOf(EnumSet.of(
        DispatchPermission.INSTANCE_START, DispatchPermission.INSTANCE_CONCLUDE,
        DispatchPermission.INSTANCE_WITHDRAW, DispatchPermission.TASK_COMMISSION,
        DispatchPermission.TASK_DECIDE, DispatchPermission.TASK_CONFIRM,
        DispatchPermission.TASK_WITHDRAW, DispatchPermission.TASK_TAG,
        DispatchPermission.INSTANCE_READ, DispatchPermission.INSTANCE_READ_METADATA,
        DispatchPermission.INSTANCE_READ_TEXT));

    /** What {@code dispatch-executor} stands for. */
    public static final Set<DispatchPermission> EXECUTOR = Set.copyOf(EnumSet.of(
        DispatchPermission.TASK_EXECUTE, DispatchPermission.TASK_TAG,
        DispatchPermission.INSTANCE_READ, DispatchPermission.INSTANCE_READ_METADATA,
        DispatchPermission.INSTANCE_READ_TEXT_AS_HOLDER));

    /** Both roles: the union. */
    public static final Set<DispatchPermission> BOTH = union();

    private PermissionFixture() {
    }

    private static Set<DispatchPermission> union() {
        Set<DispatchPermission> both = EnumSet.copyOf(CONSOLE);
        both.addAll(EXECUTOR);
        return Set.copyOf(both);
    }
}
