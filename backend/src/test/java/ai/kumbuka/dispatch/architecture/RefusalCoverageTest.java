/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.architecture;

import ai.kumbuka.dispatch.surface.RefusalCode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every code the service declares is raised by a test with a marked value
 * the refusal must not give back (C8.8).
 *
 * <p>The marked test raises each code on the wire with a value it then looks
 * for in the answer. A code it cannot raise there stands in {@link
 * #ELSEWHERE} with the test that raises it, and that test must name the code.
 * A declared code that no test raises is one nobody has seen answered: its
 * message, its data and its status are a claim. The register goes stale both
 * ways, and both are reported: a code the marked test raises is not also
 * registered elsewhere, and a registered test that names no such code is no
 * test of it.
 *
 * <p>To take this guard into another service, change the constants and the
 * import of the catalogue; the rule reads nothing else of the service.
 */
class RefusalCoverageTest {

    /** The test that raises every code it can with a marked value. */
    static final String MARKED = "contract/RefusalEchoesNoValueIT.java";

    /** The constant of the marked test that holds the marked value. */
    static final String MARKER = "MARKER";

    /** A code the marked test raises: the first argument of one of its two recorders. */
    static final Pattern RAISED = Pattern.compile(
        "\\b(?:mcp|recordRaised)\\(\\s*RefusalCode\\.([A-Z_]+)");

    /** The codes raised in another test, and that test. */
    static final Map<String, String> ELSEWHERE = Map.of(
        "UNEXPECTED_FAILURE", "surface/UnexpectedFailureIT.java");

    @Test
    void every_declared_code_is_raised_with_a_marked_value() {
        Path test = SourceTree.root("test").resolve(SourceTree.BASE.replace('.', '/'));
        String marked = SourceTree.code(test.resolve(MARKED));
        assertThat(marked).as("the marked test holds its marked value").contains(MARKER);
        assertThat(offences(declared(), raisedIn(marked), ELSEWHERE, test))
            .as("a declared code no test raises is one nobody has seen answered: its message, "
                + "data and status are a claim (C8.8). Raise it in %s with the marked value, "
                + "or register the test that raises it", MARKED)
            .isEmpty();
    }

    /** The red state, observed on every build. */
    @Test
    void the_check_reports_a_code_nobody_raises_and_a_register_gone_stale() {
        Path test = SourceTree.root("test").resolve(SourceTree.BASE.replace('.', '/'));
        Set<String> declared = new LinkedHashSet<>(declared());
        declared.add("PLANTED_NEVER_THROWN");
        Set<String> raised = raisedIn(SourceTree.code(test.resolve(MARKED)));
        Map<String, String> register = Map.of(
            "UNEXPECTED_FAILURE", "surface/UnexpectedFailureIT.java",
            "SCOPE_LOCKED", "surface/UnexpectedFailureIT.java");

        assertThat(offences(declared, raised, register, test))
            .as("RED STATE, observed: a planted code no path throws, and a register entry for "
                + "a code the marked test raises in a test that does not name it, must each "
                + "be reported")
            .containsExactlyInAnyOrder(
                "PLANTED_NEVER_THROWN is declared, the marked test names it as "
                    + "RefusalCode.PLANTED_NEVER_THROWN in no recorder call, and no other test "
                    + "is registered for it",
                "SCOPE_LOCKED is raised by the marked test and registered elsewhere too",
                "SCOPE_LOCKED is registered as raised by surface/UnexpectedFailureIT.java, "
                    + "which does not name it");
    }

    static List<String> offences(Set<String> declared, Set<String> raised,
                                 Map<String, String> register, Path test) {
        List<String> offences = new ArrayList<>();
        for (String code : declared) {
            if (!raised.contains(code) && !register.containsKey(code)) {
                offences.add(code + " is declared, the marked test names it as RefusalCode."
                    + code + " in no recorder call, and no other test is registered for it");
            }
        }
        register.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String code = entry.getKey();
            if (raised.contains(code)) {
                offences.add(code + " is raised by the marked test and registered elsewhere too");
            }
            Path file = test.resolve(entry.getValue());
            if (!Files.exists(file) || !SourceTree.code(file).contains(code)) {
                offences.add(code + " is registered as raised by " + entry.getValue()
                    + ", which does not name it");
            }
        });
        return offences;
    }

    private static Set<String> raisedIn(String code) {
        Set<String> raised = new LinkedHashSet<>();
        Matcher m = RAISED.matcher(code);
        while (m.find()) {
            raised.add(m.group(1));
        }
        return raised;
    }

    /** The service binding: the codes of the catalogue. */
    private static Set<String> declared() {
        Set<String> declared = new LinkedHashSet<>();
        Arrays.stream(RefusalCode.values()).forEach(code -> declared.add(code.name()));
        return declared;
    }
}
