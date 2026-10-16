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
 * The current time comes from one injected {@code java.time.Clock} (C20.1).
 *
 * <p>Three shapes read the time past it, and each is refused in main code:
 * a {@code now()} with no clock handed in, {@code System.currentTimeMillis()},
 * and a system clock built anywhere but the one producer that hands the
 * service its clock. {@code Instant.now(clock)} is the admitted form: the
 * clock it reads is the injected one.
 *
 * <p>The database half of the rule (no trigger or default sets a time the
 * application reads back as a token) is not read here; it moves with the
 * conflict token into the application.
 */
class ClockUsageTest {

    /** The one class that may build the system clock: the CDI producer. */
    static final String PRODUCER = "ServiceClock";

    private static final Pattern NOW_WITHOUT_CLOCK = Pattern.compile(
        "\\b(Instant|LocalDateTime|LocalDate|LocalTime|OffsetDateTime|ZonedDateTime)"
            + "\\s*\\.\\s*now\\s*\\(\\s*\\)");
    private static final Pattern MILLIS = Pattern.compile(
        "\\bSystem\\s*\\.\\s*currentTimeMillis\\s*\\(");
    private static final Pattern SYSTEM_CLOCK = Pattern.compile(
        "\\bClock\\s*\\.\\s*(systemUTC|systemDefaultZone|system)\\s*\\(");

    @Test
    void no_main_class_reads_the_time_past_the_injected_clock() {
        assertThat(offenders(SourceTree.root("main")))
            .as("a time read past the clock cannot be fixed by a test and drifts from the "
                + "time every other part of the call reads. Inject java.time.Clock and read "
                + "it (C20.1); only %s builds the system clock", PRODUCER)
            .isEmpty();
    }

    /** The red state, observed on every build. */
    @Test
    void the_check_reports_each_shape_that_reads_past_the_clock() {
        String planted = """
            class Lease {
                Instant a = Instant.now();
                long b = System.currentTimeMillis();
                Clock c = Clock.systemUTC();
                Instant d = Instant.now(clock);
            }
            """;

        assertThat(readsIn("Lease", planted))
            .as("RED STATE, observed: now() without a clock, the millisecond counter and a "
                + "system clock outside the producer must each be reported; now(clock) must "
                + "not")
            .containsExactly("Lease: Instant.now()", "Lease: System.currentTimeMillis(",
                "Lease: Clock.systemUTC(");
        assertThat(readsIn(PRODUCER, "class P { Clock c = Clock.systemUTC(); }"))
            .as("RED STATE, observed: the producer itself is admitted")
            .isEmpty();
    }

    private static List<String> offenders(Path root) {
        List<String> offenders = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            String fqcn = SourceTree.fqcn(root, file);
            offenders.addAll(readsIn(fqcn.substring(fqcn.lastIndexOf('.') + 1),
                SourceTree.code(file)));
        }
        return offenders;
    }

    static List<String> readsIn(String name, String code) {
        List<String> found = new ArrayList<>();
        collect(found, name, NOW_WITHOUT_CLOCK.matcher(code));
        collect(found, name, MILLIS.matcher(code));
        if (!name.equals(PRODUCER)) {
            collect(found, name, SYSTEM_CLOCK.matcher(code));
        }
        return found;
    }

    private static void collect(List<String> found, String name, Matcher m) {
        while (m.find()) {
            found.add(name + ": " + m.group().replaceAll("\\s+", ""));
        }
    }
}
