/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every text that tells a caller what to do is worded positively, as the act
 * to perform (DEC-0043 R21; service-construction, "texts addressed to a
 * caller", rule 4).
 *
 * <p>The lint reads the files of the instructing texts -- the remedies, the
 * steps of {@code next}, and the descriptions of the calls, the arguments and
 * the values -- and refuses the words R21 names, as whole words and in any
 * case: not, n't, never, no, none, nothing, nobody, neither, nor, cannot,
 * without, avoid, unless, instead of and refrain. It keeps no list of
 * exceptions. The messages of refusals and the notices may state a finding in
 * the negative and are not read here.
 *
 * <p>The lint is seen to refuse a planted remedy that says what to avoid on
 * every build.
 *
 * <p><strong>The patterns are the binding form for every service that copies
 * this lint.</strong> The phrase is matched as {@code \binstead\s+of\b}, so a
 * line break or a second space between its words does not hide it, and the
 * contraction as {@code n't\b}, so it is found at the end of any word, "don't"
 * and "isn't" alike. A copy keeps both as they are written in {@link
 * #REFUSED} and adds no list of exceptions.
 */
class PositivePhrasingLintTest {

    /** The bundle files of the instructing texts. */
    private static final List<String> INSTRUCTING = List.of(
        "remedy", "next", "tool", "argument", "value");

    /** The words of DEC-0043 R21, whole and in any case; "n't" ends a word. */
    private static final Pattern REFUSED = Pattern.compile(
        "\\b(not|never|no|none|nothing|nobody|neither|nor|cannot|without|avoid|unless|"
            + "refrain)\\b|n't\\b|\\binstead\\s+of\\b",
        Pattern.CASE_INSENSITIVE);

    @Test
    void every_instructing_text_is_worded_as_the_act_to_perform() {
        List<String> negative = new ArrayList<>();
        int texts = 0;
        for (String bundle : INSTRUCTING) {
            Properties file = read(bundle);
            assertThat(file).as("%s holds texts", bundle).isNotEmpty();
            texts += file.size();
            for (String key : file.stringPropertyNames()) {
                negative.addAll(refused(bundle + "." + key, file.getProperty(key)));
            }
        }
        assertThat(texts).as("the lint reads every instructing text").isGreaterThan(100);
        assertThat(negative)
            .as("a prohibition names the act it forbids, and a model takes the act up; "
                + "word the text as the act to perform, and a fact as what is there")
            .isEmpty();

        assertThat(refused("remedy.planted", "Do not repeat the call; avoid the old key."))
            .as("RED STATE, observed: a planted remedy that says what to avoid is refused")
            .containsExactly("remedy.planted: not", "remedy.planted: avoid");
        assertThat(refused("next.planted", "Don't wait, instead of reading it."))
            .as("and so are a contraction and the phrase")
            .hasSize(2);
        assertThat(refused("tool.planted", "Notes the knot in a node."))
            .as("whole words only: a word that contains one is no prohibition")
            .isEmpty();
    }

    // -----------------------------------------------------------------------

    private static List<String> refused(String where, String text) {
        List<String> found = new ArrayList<>();
        Matcher match = REFUSED.matcher(text);
        while (match.find()) {
            found.add(where + ": " + match.group());
        }
        return found;
    }

    private static Properties read(String bundle) {
        Path file = Paths.get("src/main/resources/messages", bundle + ".properties");
        if (!Files.exists(file)) {
            file = Paths.get("backend/src/main/resources/messages", bundle + ".properties");
        }
        assertThat(Files.exists(file)).as("the bundle file %s is found", file).isTrue();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Properties texts = new Properties();
            texts.load(in);
            return texts;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
