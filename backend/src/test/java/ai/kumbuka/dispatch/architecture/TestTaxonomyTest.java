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
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Three kinds of test, told apart by the name's suffix (C22.1 to C22.3).
 *
 * <ul>
 *   <li>{@code *Test}: one class or a few plain classes. No container, no CDI,
 *       no Mockito.</li>
 *   <li>{@code *ModuleTest}: a component against mocked interfaces with
 *       Mockito. No container.</li>
 *   <li>{@code *IT}: the running service against a real database, by
 *       Failsafe.</li>
 * </ul>
 *
 * <p>The suffix decides which plugin runs a class and how long the build
 * waits for it, so a {@code *Test} that boots the service is a slow test in
 * the fast lane, and a mock in a {@code *Test} is a component test that claims
 * to test one class. Every class that holds a test method carries one of the
 * three suffixes.
 */
class TestTaxonomyTest {

    private static final Pattern TEST_METHOD = Pattern.compile(
        "@(Test|ParameterizedTest|RepeatedTest|TestFactory)\\b");
    private static final Pattern CONTAINER = Pattern.compile(
        "@(QuarkusTest|QuarkusIntegrationTest|QuarkusTestResource|TestProfile)\\b");
    private static final Pattern MOCKITO = Pattern.compile("\\borg\\.mockito\\b|@Mock\\b");
    private static final Pattern CDI = Pattern.compile("@Inject\\b");

    @Test
    void every_test_class_keeps_to_the_kind_its_name_says() {
        assertThat(offenders(SourceTree.root("test")))
            .as("the suffix says which plugin runs a class and what it may use (C22). Rename "
                + "a class that boots the service to *IT, one that mocks to *ModuleTest, or "
                + "take the container and the mocks out of it")
            .isEmpty();
    }

    /** The red state, observed on every build: one planted class per rule. */
    @Test
    void the_check_reports_each_kind_out_of_place() {
        assertThat(kindOffences("RouterTest",
                "import org.mockito.Mockito; class RouterTest { @Test void t() {} }"))
            .as("RED STATE, observed: Mockito in a *Test").containsExactly(
                "RouterTest uses Mockito; a *Test is plain (C22.1)");
        assertThat(kindOffences("ConformanceTest",
                "@QuarkusTest class ConformanceTest { @Inject X x; @Test void t() {} }"))
            .as("RED STATE, observed: a container and CDI in a *Test").containsExactly(
                "ConformanceTest boots the service; a *Test is plain (C22.1)",
                "ConformanceTest injects; a *Test is plain (C22.1)");
        assertThat(kindOffences("SurfaceModuleTest",
                "@QuarkusTest class SurfaceModuleTest { @Test void t() {} }"))
            .as("RED STATE, observed: a container in a *ModuleTest").containsExactly(
                "SurfaceModuleTest boots the service; a *ModuleTest has no container (C22.2)");
        assertThat(kindOffences("Probe", "class Probe { @Test void t() {} }"))
            .as("RED STATE, observed: a test class with none of the three suffixes")
            .containsExactly("Probe holds tests and is none of *Test, *ModuleTest, *IT");
        assertThat(kindOffences("FlowIT",
                "@QuarkusTest class FlowIT { @Inject X x; @Test void t() {} }"))
            .as("RED STATE, observed: the same in an *IT is admitted").isEmpty();
    }

    private static List<String> offenders(Path root) {
        List<String> offenders = new ArrayList<>();
        int tests = 0;
        for (Path file : SourceTree.files(root)) {
            String fqcn = SourceTree.fqcn(root, file);
            String code = withoutLiterals(SourceTree.code(file));
            if (TEST_METHOD.matcher(code).find()) {
                tests++;
            }
            offenders.addAll(kindOffences(fqcn.substring(fqcn.lastIndexOf('.') + 1), code));
        }
        assertThat(tests).as("test classes must have been found").isGreaterThan(20);
        return offenders;
    }

    /**
     * The code with its text blocks and string literals blanked: a guard that
     * plants a class as text, this one included, names annotations in strings
     * that are not annotations of the file.
     */
    static String withoutLiterals(String code) {
        String blanked = Pattern.compile("\"\"\".*?\"\"\"", Pattern.DOTALL).matcher(code)
            .replaceAll("\"\"");
        return blanked.replaceAll("\"(\\\\.|[^\"\\\\\\n])*\"", "\"\"");
    }

    static List<String> kindOffences(String name, String code) {
        if (!TEST_METHOD.matcher(code).find()) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        boolean container = CONTAINER.matcher(code).find();
        if (name.endsWith("IT")) {
            return found;
        }
        if (name.endsWith("ModuleTest")) {
            if (container) {
                found.add(name + " boots the service; a *ModuleTest has no container (C22.2)");
            }
            return found;
        }
        if (!name.endsWith("Test")) {
            found.add(name + " holds tests and is none of *Test, *ModuleTest, *IT");
            return found;
        }
        if (MOCKITO.matcher(code).find()) {
            found.add(name + " uses Mockito; a *Test is plain (C22.1)");
        }
        if (container) {
            found.add(name + " boots the service; a *Test is plain (C22.1)");
        }
        if (CDI.matcher(code).find()) {
            found.add(name + " injects; a *Test is plain (C22.1)");
        }
        return found;
    }
}
