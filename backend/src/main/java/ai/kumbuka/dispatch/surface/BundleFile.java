/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * The raw templates of one message bundle, as its file holds them.
 *
 * <p>For the declaration, which publishes a message as the pattern it is
 * rendered from, placeholders and all, so that a consumer can tell what a
 * message may carry. Rendering goes through the bundle and never through
 * this: the file is the one place the text is written.
 */
public final class BundleFile {

    private BundleFile() {
    }

    /** The template of {@code key} in the bundle {@code name}; a defect where there is none. */
    public static String template(String name, String key) {
        String template = read(name).getProperty(key);
        if (template == null) {
            throw new IllegalStateException(name + " holds no template " + key);
        }
        return template;
    }

    /** Every template of the bundle {@code name}, by key. */
    public static Properties read(String name) {
        String resource = "messages/" + name + ".properties";
        try (InputStream in = BundleFile.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is not on the classpath");
            }
            Properties templates = new Properties();
            templates.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return templates;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
