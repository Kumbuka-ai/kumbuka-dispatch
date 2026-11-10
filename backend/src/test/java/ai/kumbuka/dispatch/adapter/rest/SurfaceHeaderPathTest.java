/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.rest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which paths the {@code Kumbuka-Surface} filter reads the header on (C14.4,
 * C14.5, F-0429).
 *
 * <p>Every path of the REST surface reads it, the base path {@code /api}
 * itself included: a request there with a wrong header is refused with {@code
 * HEADER_INVALID} before anything else, as on every path below it. The filter
 * once tested for the prefix {@code api/} and so let {@code /api} through to
 * the route, where it was answered as a missing route. The wire half is
 * {@code SurfaceHeaderIT}.
 */
class SurfaceHeaderPathTest {

    @Test
    void every_path_of_the_rest_surface_reads_the_header_the_base_path_included() {
        for (String path : List.of("/api", "api", "/api/", "/api/declaration", "/api/whoami",
                "/api/probe", "/api/probe/sprint", "/api/probe/sprint/1.0",
                "/api/probe/sprint/1.0:claim")) {
            assertThat(SurfaceHeaderFilter.readsTheHeader(path))
                .as("RED STATE, observed on /api and api: the prefix api/ left the base "
                    + "path out; %s reads the header", path)
                .isTrue();
        }
    }

    @Test
    void no_path_outside_the_rest_surface_reads_it() {
        for (String path : List.of("/mcp", "/q/health", "/apiary", "/api-docs", "/", "")) {
            assertThat(SurfaceHeaderFilter.readsTheHeader(path))
                .as("%s is not a path of the REST surface", path)
                .isFalse();
        }
    }
}
