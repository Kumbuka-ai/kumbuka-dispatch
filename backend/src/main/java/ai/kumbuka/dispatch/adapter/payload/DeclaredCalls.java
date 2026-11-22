/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * The declared calls, one method each, and the one permission each checks
 * about its caller (DEC-0052): the method makes its call only for a caller
 * holding it, and checks nothing else about the caller.
 *
 * <p>One method per call rather than one for all, because the permission is
 * the call's: a single method would check one for every call or none. Both
 * adapters make every call here; it is the part of the adapter both surfaces
 * share, as the form check is (C1.2, C27.1).
 *
 * <p><strong>No call is declared yet.</strong> The service is rebuilt on the
 * store line, and a call's method stands here from the change that binds the
 * call to the new kernel, with the permission its declaration names.
 */
@ApplicationScoped
public class DeclaredCalls {
}
