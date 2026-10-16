/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import ai.kumbuka.dispatch.surface.Refusal;
import ai.kumbuka.dispatch.surface.SurfaceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.ThrowableAssert;

import java.util.Map;

import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A refusal as the wire carries it, for the tests that read one without a
 * request: written by the service's own serialiser, so a member with no value
 * is absent here exactly as it is in an answer.
 */
public final class Wire {

    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        new SerialisationCustomizer().customize(JSON);
    }

    private Wire() {
    }

    /** The refusal's {@code data} as the wire carries it; empty where it carries none. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> data(Refusal refusal) {
        RefusalDataPayload data = Payloads.refusal(refusal).data();
        return data == null ? Map.of() : JSON.convertValue(data, Map.class);
    }

    /** The refusal a piece of code raises at the surface; fails where it raises none. */
    public static Refusal refused(ThrowableAssert.ThrowingCallable call) {
        SurfaceException raised = catchThrowableOfType(SurfaceException.class, call);
        if (raised == null) {
            throw new AssertionError("the call was expected to be refused, and was not");
        }
        return raised.refusal();
    }
}
