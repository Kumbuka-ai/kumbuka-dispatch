/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.rest;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.Surface;
import jakarta.enterprise.context.RequestScoped;

import java.util.Map;

/**
 * The call a REST request became, for the refusal mapper.
 *
 * <p>A refusal raised under a call reaches {@link RefusalMapper} as an
 * exception and nothing else; the call it refuses — which verb, on which
 * surface's vocabulary, by whom, at which address — is what the refusal is
 * worded from. {@link TaskResource} records it here as the call is read, and
 * the mapper reads it back. A request that never became a call leaves it
 * empty, and its refusal says so.
 */
@RequestScoped
public class CallContext {

    private Surface surface;
    private ProcessVerb verb;
    private Actor actor;
    private Map<String, Object> given = Map.of();

    void call(Surface on, ProcessVerb call, Map<String, Object> arguments) {
        this.surface = on;
        this.verb = call;
        this.given = arguments;
    }

    void caller(Actor who) {
        this.actor = who;
    }

    /** Whether the request became a call at all. */
    boolean made() {
        return verb != null;
    }

    Surface surface() {
        return surface;
    }

    ProcessVerb verb() {
        return verb;
    }

    /** Who calls, or null where the token carried no usable capacity. */
    Actor actor() {
        return actor;
    }

    Map<String, Object> given() {
        return given;
    }
}
