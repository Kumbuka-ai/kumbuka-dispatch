/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A refusal is caught at the outermost edge only: the REST refusal mapper and
 * the MCP adapter (C8.2, ADR-0037).
 *
 * <p>A catch clause catches a refusal when it names one of the two refusal
 * types, or a type both of them extend: {@code RuntimeException}, {@code
 * Exception}, {@code Throwable}. The second half matters as much as the first,
 * because a broad catch takes a refusal along with everything else, and that
 * is exactly how a refusal used to be swallowed, re-dressed and answered from
 * a layer in the middle.
 *
 * <p>One package is exempt, and why: {@code tenancy} is an aspect that runs
 * around a call rather than inside one, imports nothing of its service (so it
 * cannot even name a refusal type), and its classes are a template shared
 * byte for byte between services. Its broad catches guard the binding of a
 * tenant, not a call.
 */
class RefusalCatchTest {

    /** The two files a refusal may be caught in, by simple name. */
    static final Set<String> EDGES = Set.of("RefusalMapper", "McpAdapter");

    /** The refusal types, and the types they share. */
    static final Set<String> CATCHING = Set.of(
        "DispatchException", "SurfaceException", "RuntimeException", "Exception", "Throwable");

    /** The aspect, exempt for the reason in the class comment. */
    private static final String EXEMPT_LAYER = "tenancy";

    private static final Pattern CATCH = Pattern.compile("catch\\s*\\(([^)]*)\\)");

    @Test
    void no_refusal_is_caught_outside_the_two_edges() {
        assertThat(offenders(SourceTree.root("main")))
            .as("a refusal caught in the middle is a refusal answered from the middle: "
                + "re-dressed, swallowed or continued past (ADR-0037). Let it travel to "
                + "RefusalMapper or McpAdapter, which map it to its envelope (C8.2)")
            .isEmpty();
    }

    /** The red state, observed on every build. */
    @Test
    void the_check_reports_a_refusal_caught_in_the_middle() {
        String middle = """
            class Router {
                Object call() {
                    try { return run(); }
                    catch (DispatchException refused) { return null; }
                    catch (RuntimeException failure) { return null; }
                    catch (java.io.IOException unrelated) { return null; }
                }
            }
            """;

        assertThat(caughtIn("surface", "Router", middle))
            .as("RED STATE, observed: a domain refusal and a broad catch outside the edges "
                + "must both be reported; an unrelated checked exception must not")
            .containsExactly("surface.Router catches DispatchException refused",
                "surface.Router catches RuntimeException failure");
        assertThat(caughtIn("adapter.rest", "RefusalMapper", middle))
            .as("RED STATE, observed: the same catches at an edge are admitted")
            .isEmpty();
    }

    private static List<String> offenders(Path root) {
        List<String> offenders = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            String fqcn = SourceTree.fqcn(root, file);
            offenders.addAll(caughtIn(SourceTree.layerOf(root, file),
                fqcn.substring(fqcn.lastIndexOf('.') + 1), SourceTree.code(file)));
        }
        return offenders;
    }

    static List<String> caughtIn(String layer, String name, String code) {
        if (EDGES.contains(name) || layer.equals(EXEMPT_LAYER)) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        Matcher m = CATCH.matcher(code);
        while (m.find()) {
            String clause = m.group(1).strip();
            String types = clause.substring(0, clause.lastIndexOf(' ')).strip();
            for (String type : types.split("\\|")) {
                String simple = type.strip().substring(type.strip().lastIndexOf('.') + 1);
                if (CATCHING.contains(simple)) {
                    found.add(layer + "." + name + " catches " + simple + " "
                        + clause.substring(clause.lastIndexOf(' ') + 1));
                }
            }
        }
        return found;
    }
}
