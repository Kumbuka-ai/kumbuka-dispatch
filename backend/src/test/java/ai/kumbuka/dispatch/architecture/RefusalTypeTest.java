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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exactly two exception types carry refusals: the domain's, with its enum of
 * reasons, and the surface's, for grammar and transport (C8.1).
 *
 * <p>Read as "exactly two exception types exist in the main tree", which is
 * the stronger statement and the checkable one: a third type cannot carry a
 * refusal if there is no third type. A class counts as an exception type when
 * it extends a class whose name ends in {@code Exception} or {@code Error}, or
 * {@code Throwable} itself.
 *
 * <p>The domain type is named after the service; that name is the one
 * service-specific constant here.
 */
class RefusalTypeTest {

    /** The two, as {@code <package>.<class>} below the service root. */
    static final List<String> ADMITTED = List.of(
        "domain.DispatchException", "surface.SurfaceException");

    private static final Pattern EXTENDS = Pattern.compile(
        "\\bclass\\s+(\\w+)\\s+extends\\s+([\\w.]+)");

    @Test
    void the_main_tree_has_exactly_the_two_refusal_types() {
        assertThat(exceptionTypes(SourceTree.root("main")))
            .as("a refusal is a domain refusal with a reason, or a surface refusal about "
                + "grammar and transport. A third type is a third place a refusal is "
                + "shaped and a third thing the edge must map (C8.1); fold it into one of "
                + "the two")
            .containsExactlyInAnyOrderElementsOf(ADMITTED);
    }

    /** The red state, observed on every build. */
    @Test
    void the_check_reports_a_third_exception_type() {
        List<String> found = new ArrayList<>();
        found.addAll(typesIn("domain", "public class DispatchException extends RuntimeException {}"));
        found.addAll(typesIn("surface", "public class SurfaceException extends RuntimeException {}"));
        found.addAll(typesIn("surface", "public class Refused extends RuntimeException {}"));
        found.addAll(typesIn("surface", "final class Fine extends java.lang.Object {}"));

        assertThat(found)
            .as("RED STATE, observed: a third exception type must be found by the "
                + "detection, and a class that is no exception must not be")
            .containsExactly("domain.DispatchException", "surface.SurfaceException",
                "surface.Refused");
        assertThat(found).as("RED STATE, observed: the three are not the admitted two")
            .isNotEqualTo(ADMITTED);
    }

    private static List<String> exceptionTypes(Path root) {
        List<String> types = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            types.addAll(typesIn(SourceTree.layerOf(root, file), SourceTree.code(file)));
        }
        return types;
    }

    static List<String> typesIn(String layer, String code) {
        List<String> types = new ArrayList<>();
        Matcher m = EXTENDS.matcher(code);
        while (m.find()) {
            String parent = m.group(2).substring(m.group(2).lastIndexOf('.') + 1);
            if (parent.endsWith("Exception") || parent.endsWith("Error")
                    || parent.equals("Throwable")) {
                types.add(layer + "." + m.group(1));
            }
        }
        return types;
    }
}
