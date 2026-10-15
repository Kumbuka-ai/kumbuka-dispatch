/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.architecture;

import ai.kumbuka.dispatch.fixture.MintableNameFixture;
import ai.kumbuka.dispatch.fixture.StringPlaceholderFixture;
import ai.kumbuka.dispatch.surface.ArgumentName;
import ai.kumbuka.dispatch.surface.ArgumentPath;
import ai.kumbuka.dispatch.surface.Bound;
import ai.kumbuka.dispatch.surface.ReasonCode;
import ai.kumbuka.dispatch.surface.VerbName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A placeholder of a text takes only the platform's vocabulary
 * (service-construction, "texts addressed to a caller", rule 2; DEC-0043
 * R18).
 *
 * <p>Two checks, by reflection. Every parameter of every method of every
 * bundle interface has one of the types {@link ArgumentName}, {@link
 * ArgumentPath}, {@link VerbName}, {@link ReasonCode} and {@link Bound}, or is
 * a list of one of them; never a {@code String}, which could carry anything a
 * caller sent. And each of those types is built only from the declaration:
 * none has a public constructor, and no public static method builds one from
 * a string, so a name that arrived undeclared cannot become one. An enum's
 * own {@code valueOf} is the exception that is none: it yields a declared
 * constant or nothing.
 *
 * <p>Each check is seen to find a planted violation on every build: a bundle
 * with a {@code String} placeholder and a name type with a public string
 * constructor, both in the test tree.
 */
class MessagePlaceholderTypeTest {

    private static final Set<Class<?>> PLATFORM_VOCABULARY = Set.of(
        ArgumentName.class, ArgumentPath.class, VerbName.class, ReasonCode.class, Bound.class);

    @Test
    void every_placeholder_takes_only_the_platform_s_vocabulary() throws Exception {
        List<Class<?>> bundles = MessageBundleTest.bundles();
        assertThat(bundles).as("the guard finds the bundles it holds").hasSizeGreaterThan(5);
        List<String> offending = new ArrayList<>();
        int placeholders = 0;
        for (Class<?> bundle : bundles) {
            offending.addAll(offendingPlaceholders(bundle));
            for (Method method : bundle.getDeclaredMethods()) {
                placeholders += method.getParameterCount();
            }
        }
        assertThat(placeholders).as("the bundles have placeholders to check").isPositive();
        assertThat(offending)
            .as("a placeholder of another type can carry the caller's input or the tenant's "
                + "vocabulary into the platform's voice")
            .isEmpty();

        assertThat(offendingPlaceholders(StringPlaceholderFixture.class))
            .as("RED STATE, observed: the planted String placeholder is found")
            .containsExactly("StringPlaceholderFixture#echo(String)");
    }

    @Test
    void every_placeholder_type_is_built_from_the_declaration_alone() {
        List<String> mintable = new ArrayList<>();
        for (Class<?> type : PLATFORM_VOCABULARY) {
            mintable.addAll(mintableFromAString(type));
        }
        assertThat(mintable)
            .as("a public way from a string to a placeholder type is a way for a name that "
                + "arrived undeclared into a message")
            .isEmpty();

        assertThat(mintableFromAString(MintableNameFixture.class))
            .as("RED STATE, observed: the planted public string constructor is found")
            .containsExactly("MintableNameFixture(String)");
    }

    // -----------------------------------------------------------------------

    private static List<String> offendingPlaceholders(Class<?> bundle) {
        List<String> offending = new ArrayList<>();
        for (Method method : bundle.getDeclaredMethods()) {
            if (method.getReturnType() != String.class) {
                offending.add(bundle.getSimpleName() + "#" + method.getName() + " returns "
                    + method.getReturnType().getSimpleName());
            }
            for (Type parameter : method.getGenericParameterTypes()) {
                if (!isPlatformVocabulary(parameter)) {
                    offending.add(bundle.getSimpleName() + "#" + method.getName() + "("
                        + simpleName(parameter) + ")");
                }
            }
        }
        return offending;
    }

    private static boolean isPlatformVocabulary(Type parameter) {
        if (parameter instanceof Class<?> type) {
            return PLATFORM_VOCABULARY.contains(type);
        }
        if (parameter instanceof ParameterizedType list && list.getRawType() == List.class) {
            Type element = list.getActualTypeArguments()[0];
            return element instanceof Class<?> type && PLATFORM_VOCABULARY.contains(type);
        }
        return false;
    }

    private static String simpleName(Type type) {
        return type instanceof Class<?> c ? c.getSimpleName() : type.getTypeName();
    }

    private static List<String> mintableFromAString(Class<?> type) {
        List<String> ways = new ArrayList<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (Modifier.isPublic(constructor.getModifiers())) {
                ways.add(type.getSimpleName() + "(" + String.join(", ",
                    java.util.Arrays.stream(constructor.getParameterTypes())
                        .map(Class::getSimpleName).toList()) + ")");
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            boolean factory = Modifier.isStatic(method.getModifiers())
                && Modifier.isPublic(method.getModifiers())
                && method.getReturnType() == type;
            boolean enumValueOf = type.isEnum() && method.getName().equals("valueOf");
            boolean fromText = java.util.Arrays.stream(method.getParameterTypes())
                .anyMatch(p -> p == String.class || p == CharSequence.class
                    || p == Object.class);
            if (factory && fromText && !enumValueOf) {
                ways.add(type.getSimpleName() + "." + method.getName());
            }
        }
        return ways;
    }
}
