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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The codes and statuses of the contract's table are the same in every
 * service; a service adds a code only by declaring it (C8.7).
 *
 * <p>The table is read from a verbatim copy in the test tree,
 * {@code contract/shared-code-table.md}, so the expectation does not come from
 * the catalogue it checks. Three statements:
 *
 * <ol>
 *   <li>every code of the table this service carries has the table's
 *       status;</li>
 *   <li>a code of the table this service does not carry is listed in
 *       {@link #NOT_CARRIED}, with the reason it has no occasion for it;</li>
 *   <li>every code this service carries is in the table, is one of its
 *       state codes (the row "per declaration", 409), or is declared in
 *       {@link #SERVICE_CODES} with its status.</li>
 * </ol>
 *
 * <p>The three constants and {@link #carried()} are the service binding; the
 * rule reads none of the service's words.
 */
class SharedCodeTableTest {

    /** The codes of the table this service has no occasion for, and why. */
    static final Map<String, String> NOT_CARRIED = Map.of(
        "ADDRESS_TRUNCATED", "a collection takes create, query and the draw; every other "
            + "call on it is a verb not carried there (VERB_NOT_CARRIED)",
        "VALUE_UNDECLARED", "no argument takes a value from a scope's vocabulary; every "
            + "fixed set is declared, and a value outside it is ARGUMENT_INVALID",
        "PREDICATE_UNKNOWN", "the filters are declared arguments, so an undeclared one is "
            + "ARGUMENT_UNKNOWN",
        "ALREADY_EXISTS", "the service allocates every address it creates");

    /** The codes the row "transition refused by state, per declaration" stands for. */
    static final List<String> STATE_CODES = List.of("STATE_DOES_NOT_ALLOW", "LEASE_LAPSED");

    /** The codes this service declares beyond the table, with their statuses. */
    static final Map<String, Integer> SERVICE_CODES = Map.of(
        "ROLE_DOES_NOT_ALLOW", 403,
        "SCOPE_READ_ONLY", 403,
        "SCOPE_LOCKED", 409,
        "SCOPE_KIND_UNSUPPORTED", 422,
        "CHILDREN_NOT_FINISHED", 409,
        "DEFERRAL_PENDING", 409,
        "IDEMPOTENCY_KEY_REUSED", 409);

    private static final int STATE_STATUS = 409;

    private static final Pattern ROW = Pattern.compile(
        "^\\|[^|]*\\|\\s*`([A-Z_]+)`\\s*\\|\\s*(\\d{3})\\s*\\|", Pattern.MULTILINE);

    @Test
    void the_service_speaks_the_shared_table() throws IOException {
        assertThat(offences(table(), carried()))
            .as("one condition answers with one code and one status in every service, so a "
                + "caller that learned one service can read the next (C8.7). Align the code "
                + "and its status with the table, or declare a code the table does not have")
            .isEmpty();
    }

    /** The red state, observed on every build. */
    @Test
    void the_check_reports_a_wrong_status_an_undeclared_code_and_a_missing_one()
            throws IOException {
        Map<String, Integer> planted = new LinkedHashMap<>();
        table().forEach(planted::put);
        NOT_CARRIED.keySet().forEach(planted::remove);
        planted.putAll(SERVICE_CODES);
        STATE_CODES.forEach(code -> planted.put(code, STATE_STATUS));
        planted.put("RECEIPT_WRONG", 403);
        planted.put("NOTHING_TO_TAKE", 409);
        planted.remove("DRAW_EMPTY");

        assertThat(offences(table(), planted))
            .as("RED STATE, observed: a status that differs from the table, a code the "
                + "table does not have and nobody declared, and a code of the table nobody "
                + "carries must each be reported")
            .containsExactlyInAnyOrder(
                "RECEIPT_WRONG answers 403; the table says 409",
                "DRAW_EMPTY is in the table and not carried, and NOT_CARRIED does not say why",
                "NOTHING_TO_TAKE is neither in the table nor declared as a code of the service");
    }

    static List<String> offences(Map<String, Integer> table, Map<String, Integer> carried) {
        List<String> offences = new ArrayList<>();
        table.forEach((code, status) -> {
            Integer actual = carried.get(code);
            if (actual == null) {
                if (!NOT_CARRIED.containsKey(code)) {
                    offences.add(code + " is in the table and not carried, and NOT_CARRIED "
                        + "does not say why");
                }
            } else if (!actual.equals(status)) {
                offences.add(code + " answers " + actual + "; the table says " + status);
            }
        });
        carried.forEach((code, status) -> {
            if (table.containsKey(code)) {
                return;
            }
            if (STATE_CODES.contains(code)) {
                if (status != STATE_STATUS) {
                    offences.add(code + " is a state code and answers " + status
                        + "; the table says " + STATE_STATUS);
                }
            } else if (SERVICE_CODES.containsKey(code)) {
                if (!SERVICE_CODES.get(code).equals(status)) {
                    offences.add(code + " answers " + status + "; declared as "
                        + SERVICE_CODES.get(code));
                }
            } else {
                offences.add(code + " is neither in the table nor declared as a code of the "
                    + "service");
            }
        });
        return offences;
    }

    /** The copy of the table: code to status, in its order. */
    static Map<String, Integer> table() throws IOException {
        try (InputStream in = SharedCodeTableTest.class.getClassLoader()
                .getResourceAsStream("contract/shared-code-table.md")) {
            assertThat(in).as("the copy of the table must be on the test classpath").isNotNull();
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Integer> rows = new LinkedHashMap<>();
            Matcher m = ROW.matcher(text);
            while (m.find()) {
                rows.put(m.group(1), Integer.parseInt(m.group(2)));
            }
            assertThat(rows).as("the table's rows must have been read").hasSizeGreaterThan(20);
            return rows;
        }
    }

    /** The service binding: every code the service carries, with its status. */
    private static Map<String, Integer> carried() {
        Map<String, Integer> carried = new LinkedHashMap<>();
        Arrays.stream(RefusalCode.values())
            .forEach(code -> carried.put(code.name(), code.status()));
        return carried;
    }
}
