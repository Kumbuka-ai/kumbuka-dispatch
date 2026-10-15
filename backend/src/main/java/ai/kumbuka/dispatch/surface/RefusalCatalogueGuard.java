/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Refuses the start of a service whose refusal catalogue misses a message or
 * a remedy for a code (C8.5).
 *
 * <p>That is the mechanism behind "a code not in the catalogue cannot be
 * returned": not a rule somebody keeps, but a service that does not come up.
 * A code with no message cannot be worded and would escape as an unexpected
 * failure the first time it is raised; a code with no remedy is a refusal
 * nobody could act on.
 */
@ApplicationScoped
public class RefusalCatalogueGuard {

    private static final Logger LOG = Logger.getLogger(RefusalCatalogueGuard.class);

    void onStart(@Observes StartupEvent event) {
        requireComplete(RefusalCode.catalogue());
        LOG.infof("refusal catalogue complete: %d codes", RefusalCode.values().length);
    }

    /**
     * Refuses a catalogue that does not cover every code with a message and a
     * remedy. Takes the catalogue, so that a test can hand it one that does
     * not: the real catalogue cannot be made incomplete at runtime.
     */
    public static void requireComplete(Map<RefusalCode, RefusalCode.Declared> catalogue) {
        List<String> undeclared = Arrays.stream(RefusalCode.values())
            .filter(code -> !catalogue.containsKey(code))
            .map(Enum::name)
            .toList();
        if (!undeclared.isEmpty()) {
            throw new IllegalStateException("the refusal catalogue does not declare "
                + undeclared + ". A code not in the catalogue cannot be returned, and the "
                + "service refuses to start rather than raise one it cannot word.");
        }
        for (RefusalCode.Declared declared : catalogue.values()) {
            if (declared.messages().isEmpty()
                    || declared.remedy() == null || declared.remedy().isBlank()) {
                throw new IllegalStateException(declared.code() + " is declared without a "
                    + "message or without a remedy. A refusal that cannot say what happened, "
                    + "or what to do about it, is not a declared refusal.");
            }
        }
    }
}
