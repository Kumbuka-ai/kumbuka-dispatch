/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.architecture;

import ai.kumbuka.dispatch.fixture.ValuedMessageFixture;
import io.quarkus.qute.i18n.Message;
import io.quarkus.qute.i18n.MessageBundle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No text addressed to a caller lives in Java code (service-construction,
 * "texts addressed to a caller", rules 1 and 3).
 *
 * <p>Three checks. Every {@code @Message} of a bundle interface carries no
 * value, so its text is in the bundle file and the build fails where the file
 * lacks it. There is one set of texts: no {@code @Localized} bundle and no
 * localized bundle file. And no string of prose in the packages that speak to
 * a caller, {@code adapter} and {@code surface}, outside the two places such
 * a string reaches nobody: the message of an exception, which the surface
 * never sends -- a refusal is worded from its bundle and takes no string, and
 * every other exception is answered as {@code UNEXPECTED_FAILURE} with a text
 * of its own -- and a line of the log. Prose is three words or more; a name, a path or
 * a key is one.
 *
 * <p>Each check is seen to find a planted violation on every build: a bundle
 * in the test tree whose message carries its text, a localized bundle in a
 * planted source, and a planted statement that returns a sentence.
 */
class MessageBundleTest {

    private static final String BASE = "ai.kumbuka.dispatch.";

    /** The packages whose strings can reach a caller. */
    private static final List<String> SPEAKING = List.of("adapter", "surface");

    /** Three words or more: a sentence, or the start of one. */
    private static final int PROSE_WORDS = 3;

    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z'-]*");

    /** A statement that throws or builds an exception, or writes to the log. */
    private static final Pattern EXEMPT = Pattern.compile(
        "\\bthrow\\b|new\\s+\\w*(Exception|Error)\\s*\\(|\\bLOG\\s*\\.|\\bLogger\\b");

    @Test
    void every_message_of_a_bundle_carries_no_text_in_java() throws Exception {
        assertThat(bundles()).as("the guard finds the bundles it holds").hasSizeGreaterThan(5);
        List<String> valued = new ArrayList<>();
        for (Class<?> bundle : bundles()) {
            valued.addAll(valuedMessages(bundle));
        }
        assertThat(valued)
            .as("a @Message with a value is a text written in Java; leave the value out and "
                + "put the text in src/main/resources/messages/, where a missing one fails "
                + "the build")
            .isEmpty();

        assertThat(valuedMessages(ValuedMessageFixture.class))
            .as("RED STATE, observed: the planted bundle with a valued message is found")
            .containsExactly("ValuedMessageFixture#written");
    }

    @Test
    void there_is_one_set_of_texts_and_no_localized_bundle() {
        List<String> localized = new ArrayList<>();
        for (Path file : SourceTree.files(SourceTree.root("main"))) {
            if (isLocalized(SourceTree.code(file))) {
                localized.add(file.getFileName().toString());
            }
        }
        assertThat(localized).as("a @Localized bundle is a second set of texts").isEmpty();
        assertThat(localizedFiles())
            .as("a bundle file with a locale suffix is a second set of texts")
            .isEmpty();

        assertThat(isLocalized("@Localized(\"de\")\npublic interface Planted {}"))
            .as("RED STATE, observed: a planted @Localized bundle is found")
            .isTrue();
    }

    @Test
    void no_string_of_prose_in_the_speaking_packages_reaches_a_caller() {
        List<String> prose = new ArrayList<>();
        Path root = SourceTree.root("main");
        for (Path file : SourceTree.files(root)) {
            String layer = SourceTree.layerOf(root, file);
            if (SPEAKING.stream().noneMatch(p -> layer.equals(p) || layer.startsWith(p + "."))) {
                continue;
            }
            for (String found : proseIn(SourceTree.text(file))) {
                prose.add(SourceTree.fqcn(root, file).substring(BASE.length()) + ": " + found);
            }
        }
        assertThat(prose)
            .as("a sentence in Java reaches a caller in the platform's voice and escapes "
                + "every check the bundle files pass through; put it in a bundle")
            .isEmpty();

        assertThat(proseIn("class Planted {\n    String hint() {\n        return \"Do this "
                + "before \" + \"you go on.\";\n    }\n}"))
            .as("RED STATE, observed: a planted statement returning a sentence is found")
            .hasSize(1);
        assertThat(proseIn("class Planted {\n    void f() {\n        throw new "
                + "IllegalStateException(\"a defect, not a rule\");\n    }\n}"))
            .as("and the message of a defect is no text for a caller")
            .isEmpty();
    }

    // -----------------------------------------------------------------------

    /** Every interface of the main tree annotated as a message bundle. */
    static List<Class<?>> bundles() throws ClassNotFoundException {
        List<Class<?>> bundles = new ArrayList<>();
        Path root = SourceTree.root("main");
        for (Path file : SourceTree.files(root)) {
            if (SourceTree.code(file).contains("@MessageBundle")) {
                Class<?> type = Class.forName(SourceTree.fqcn(root, file));
                if (type.isAnnotationPresent(MessageBundle.class)) {
                    bundles.add(type);
                }
            }
        }
        return bundles;
    }

    private static List<String> valuedMessages(Class<?> bundle) {
        List<String> valued = new ArrayList<>();
        for (Method method : bundle.getDeclaredMethods()) {
            Message message = method.getAnnotation(Message.class);
            if (message != null && (!Message.DEFAULT_VALUE.equals(message.value())
                    || !Message.DEFAULT_VALUE.equals(message.defaultValue()))) {
                valued.add(bundle.getSimpleName() + "#" + method.getName());
            }
        }
        return valued;
    }

    private static boolean isLocalized(String code) {
        return code.contains("@Localized");
    }

    private static List<String> localizedFiles() {
        Path messages = Files.isDirectory(Paths.get("src/main/resources/messages"))
            ? Paths.get("src/main/resources/messages")
            : Paths.get("backend/src/main/resources/messages");
        assertThat(Files.isDirectory(messages)).as("the bundle files are found").isTrue();
        try (Stream<Path> files = Files.list(messages)) {
            return files.map(f -> f.getFileName().toString())
                .filter(name -> name.matches(".*_[a-z]{2}([_-][A-Za-z]{2})?\\.properties"))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The statements of a source that carry prose in their string literals,
     * outside an exception and a line of the log.
     *
     * <p>A small lexer rather than a pattern: it reads comments, strings,
     * characters and text blocks as what they are, so an address such as
     * {@code "dispatch://"} is a string and not the start of a comment, and a
     * semicolon in a string ends no statement.
     */
    static List<String> proseIn(String source) {
        List<String> found = new ArrayList<>();
        StringBuilder statement = new StringBuilder();
        List<String> literals = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? source.length() : end;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                literals.add(source.substring(i + 3, end < 0 ? source.length() : end));
                statement.append("\"\"");
                i = end < 0 ? source.length() : end + 3;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                StringBuilder literal = new StringBuilder();
                while (j < source.length() && source.charAt(j) != c) {
                    if (source.charAt(j) == '\\') {
                        j++;
                    }
                    if (j < source.length()) {
                        literal.append(source.charAt(j));
                    }
                    j++;
                }
                if (c == '"') {
                    literals.add(literal.toString());
                }
                statement.append(c).append(c);
                i = j + 1;
            } else if (c == ';' || c == '{' || c == '}') {
                judge(statement.toString(), literals, found);
                statement.setLength(0);
                literals.clear();
                i++;
            } else {
                statement.append(c);
                i++;
            }
        }
        judge(statement.toString(), literals, found);
        return found;
    }

    private static void judge(String statement, List<String> literals, List<String> found) {
        if (literals.isEmpty() || EXEMPT.matcher(statement).find()
                || statement.strip().startsWith("@")) {
            return;
        }
        String joined = String.join("", literals);
        Matcher words = WORD.matcher(joined);
        int count = 0;
        while (words.find()) {
            count++;
        }
        if (count >= PROSE_WORDS && joined.contains(" ")) {
            found.add(joined.length() > 80 ? joined.substring(0, 80) + "..." : joined);
        }
    }
}
