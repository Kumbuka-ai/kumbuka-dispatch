/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.architecture;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tenancy template is one set of classes, byte-identical across the
 * services after the service name (C19.1).
 *
 * <p>The expectation is a list of checksums held here, taken from this
 * service's classes, which are the reference: another service copies this
 * class unchanged and goes red where its copy drifted. What is summed is the
 * class as written, with three things taken out first because they are
 * allowed to differ: a leading comment block (the licence header names the
 * repository's licence and year), the service's name in its three spellings,
 * and the line ending.
 *
 * <p>A change to the template is a change in every service at once. It is made
 * here, the list below is updated with it, and the copies follow.
 */
class TenancyTemplateDriftTest {

    /** The service's name; the only service-specific value here. */
    private static final String SERVICE = "dispatch";

    /** The template of C19.1 and the checksum of each class, normalised. */
    static final Map<String, String> TEMPLATE = new LinkedHashMap<>();

    static {
        TEMPLATE.put("TenantBound",
            "d6ca7943dd2c3e0c666e1892978de0f87f5982dff0d4d4789a6414df60a3fac7");
        TEMPLATE.put("TenantBindingInterceptor",
            "85ff04214e5548d0de2087622385f0912d59641309e5b4288b7373f78801e448");
        TEMPLATE.put("TenantDatabaseBinding",
            "9c233f784981926aedfd0f9a53c6c30e7bd6925aff0f1d1a10abe91a07831826");
        TEMPLATE.put("TenantMigrationCallback",
            "79e038ac4ce8afca7d1ddc720ba0d9b1de4b0a157f7b8e6b8c9ef07f97f44774");
        TEMPLATE.put("TenancyConfigurationGuard",
            "640be782731a79da13207e0ee38020fc0e6bdde0ca778663ade21fbf74c0f357");
        TEMPLATE.put("TenantRequestFilter",
            "dbbf93be5716f163e6324ebfdafb5f436d2b37dc0772cf68bfd9a49741c941bb");
    }

    private static final Pattern LEADING_COMMENT = Pattern.compile("\\A\\s*/\\*.*?\\*/\\s*",
        Pattern.DOTALL);

    @Test
    void every_template_class_matches_the_reference() throws Exception {
        Path tenancy = SourceTree.root("main")
            .resolve(SourceTree.BASE.replace('.', '/')).resolve("tenancy");
        Map<String, String> found = new LinkedHashMap<>();
        for (String name : TEMPLATE.keySet()) {
            Path file = tenancy.resolve(name + ".java");
            found.put(name, Files.isRegularFile(file) ? checksum(Files.readString(file)) : "absent");
        }
        assertThat(drifted(found))
            .as("the tenancy classes carry the private-memory and tenant-isolation "
                + "guarantees, and they are one template across the services (C19.1). A copy "
                + "that drifted carries a guarantee nobody reviewed for it. Align the class "
                + "with the reference, or change the template everywhere and this list with it")
            .isEmpty();
    }

    /** The red state, observed on every build: one byte changed in one class. */
    @Test
    void the_check_reports_a_class_that_drifted() throws Exception {
        Path file = SourceTree.root("main").resolve(SourceTree.BASE.replace('.', '/'))
            .resolve("tenancy/TenantBound.java");
        Map<String, String> found = new LinkedHashMap<>();
        for (String name : TEMPLATE.keySet()) {
            found.put(name, TEMPLATE.get(name));
        }
        found.put("TenantBound", checksum(Files.readString(file) + " "));

        assertThat(drifted(found))
            .as("RED STATE, observed: a template class one character away from the "
                + "reference must be reported by name")
            .containsExactly("TenantBound");
        assertThat(checksum("/* licence 2026 */\npackage ai.kumbuka." + SERVICE + ".tenancy;"))
            .as("RED STATE, observed: the header and the service name are taken out before "
                + "summing, so two services' copies of one class sum alike")
            .isEqualTo(checksum("package ai.kumbuka.memory.tenancy;".replace("memory", SERVICE)));
    }

    static List<String> drifted(Map<String, String> found) {
        List<String> drifted = new ArrayList<>();
        TEMPLATE.forEach((name, expected) -> {
            if (!expected.equals(found.get(name))) {
                drifted.add(name);
            }
        });
        return drifted;
    }

    static String checksum(String text) throws NoSuchAlgorithmException {
        String normalised = LEADING_COMMENT.matcher(text.replace("\r\n", "\n")).replaceFirst("")
            .replace(SERVICE.toUpperCase(java.util.Locale.ROOT), "<SERVICE>")
            .replace(Character.toUpperCase(SERVICE.charAt(0)) + SERVICE.substring(1), "<Service>")
            .replace(SERVICE, "<service>");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(normalised.getBytes(StandardCharsets.UTF_8)));
    }
}
