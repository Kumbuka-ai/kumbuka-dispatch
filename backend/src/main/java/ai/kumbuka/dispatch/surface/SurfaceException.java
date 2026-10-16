/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * A refusal the surface decides: grammar and transport (C8.1).
 *
 * <p>One of the two exception types that carry a refusal; the other is the
 * domain's. This one says something about the <em>call</em> — an address that
 * does not parse, an argument the call does not declare, a body that is no
 * object, a verb not carried at this address — and is decided before a task
 * is looked for. It carries the refusal as the caller is told it, so the edge
 * that catches it maps it and adds nothing.
 *
 * <p>Raised by the form checks of the adapters and by the address grammar,
 * caught only by {@code RefusalMapper} and {@code McpAdapter} (C8.2).
 */
public class SurfaceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Refusal refusal;
    /** The methods of the address for a 405; absent on every other refusal. */
    private final @Nullable String allow;

    public SurfaceException(Refusal refusal) {
        this(refusal, null);
    }

    /**
     * A refusal of a method or a verb at an address, with the methods the
     * address answers to, for the {@code Allow} header of a 405.
     */
    public SurfaceException(Refusal refusal, @Nullable String allow) {
        super(refusal.code().name(), null, false, false);
        this.refusal = refusal;
        this.allow = allow;
    }

    public Refusal refusal() {
        return refusal;
    }

    /** The methods the address answers to; empty where the refusal is not a 405. */
    public Optional<String> allow() {
        return Optional.ofNullable(allow);
    }
}
