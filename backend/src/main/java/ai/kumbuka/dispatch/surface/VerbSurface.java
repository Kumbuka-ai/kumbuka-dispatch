/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The one entry of both surfaces into the kernel (C1.1).
 *
 * <p>The adapter reads a call, checks its form against the declaration and
 * calls one method of this class, which resolves the scope the caller names
 * and makes exactly one call of the kernel in one transaction (C1.4). Nothing
 * here decides whether an act is permitted, and nothing here composes two
 * kernel calls into one act.
 *
 * <p><strong>No call is offered yet.</strong> The service is rebuilt on the
 * store line, and each call enters this class in the same change that declares
 * it in {@link ProcessVerb}; a call is never offered before it is bound here.
 */
@ApplicationScoped
@TenantBound
public class VerbSurface {
}
