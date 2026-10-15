/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.architecture;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every answer and refusal body is a record in {@code adapter.payload}; no
 * adapter builds a {@code Map} as an answer (C7.1).
 *
 * <p>Read from the compiled adapter classes: a method whose declared return
 * type mentions {@code java.util.Map}, at the top or inside a list, is a method
 * that hands a hand-built map towards the wire. A record may still carry a map
 * as one of its components, which is how the content of {@code fields} travels;
 * what this refuses is a map as the shape of the answer itself.
 *
 * <p>Synthetic methods are skipped: a lambda's body is compiled into one, and
 * its signature is the compiler's choice rather than the author's.
 */
class PayloadShapeTest {

    private static final String ADAPTER = "adapter";

    @Test
    void no_method_of_an_adapter_returns_a_map() {
        assertThat(offenders(adapterClasses()))
            .as("an answer built as a map is a shape nobody declared: its members appear "
                + "and disappear with the code that fills it, and null travels as a member. "
                + "Return a record from adapter.payload with explicit snake_case names (C7.1)")
            .isEmpty();
    }

    /** The red state, observed on every build. */
    @Test
    void the_check_reports_a_method_that_returns_a_map() {
        assertThat(offenders(List.of(Planted.class)))
            .as("RED STATE, observed: a method returning a map, and one returning a list of "
                + "maps, must both be reported; the one returning a record must not")
            .containsExactly(Planted.class.getName() + ".answer", Planted.class.getName()
                + ".listing");
    }

    /** What the detection is handed for its red state. */
    @SuppressWarnings("unused")
    static final class Planted {

        record Answer(String address) {
        }

        Map<String, Object> answer() {
            return Map.of();
        }

        List<Map<String, Object>> listing() {
            return List.of();
        }

        Answer shaped() {
            return new Answer("x");
        }
    }

    static List<String> offenders(List<Class<?>> classes) {
        List<String> offenders = new ArrayList<>();
        for (Class<?> type : classes) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge()) {
                    continue;
                }
                if (method.getGenericReturnType().getTypeName().contains(Map.class.getName())) {
                    offenders.add(type.getName() + "." + method.getName());
                }
            }
        }
        return offenders.stream().sorted().toList();
    }

    private static List<Class<?>> adapterClasses() {
        Path root = SourceTree.root("main");
        List<Class<?>> classes = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            if (!SourceTree.layerOf(root, file).startsWith(ADAPTER)) {
                continue;
            }
            classes.addAll(withNested(load(SourceTree.fqcn(root, file))));
        }
        assertThat(classes).as("the adapter classes must have been loaded").isNotEmpty();
        return classes;
    }

    private static List<Class<?>> withNested(Class<?> type) {
        List<Class<?>> all = new ArrayList<>(List.of(type));
        for (Class<?> nested : type.getDeclaredClasses()) {
            all.addAll(withNested(nested));
        }
        return all;
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, PayloadShapeTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(name + " is in the tree and not compiled", e);
        }
    }
}
