/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.architecture;

import ai.kumbuka.dispatch.adapter.payload.RefusalDataPayload;
import ai.kumbuka.dispatch.surface.RefusalCode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * service, a service adds a code only by declaring it, and a refusal carries
 * no member of {@code data} outside the contract's list (C8.7).
 *
 * <p>Both tables are read from a verbatim copy in the test tree,
 * {@code contract/shared-code-table.md}, so the expectation does not come from
 * the catalogue it checks. Four statements:
 *
 * <ol>
 *   <li>every code of the table this service carries has the table's
 *       status;</li>
 *   <li>a code of the table this service does not carry is listed in
 *       {@link #NOT_CARRIED}, with the reason it has no occasion for it;</li>
 *   <li>every code this service carries is in the table or is declared in
 *       {@link #SERVICE_CODES} with its status;</li>
 *   <li>every member a refusal's {@code data} can carry on the wire is in the
 *       list of members, and so is every member a row of the code table
 *       names.</li>
 * </ol>
 *
 * <p>The two constants, {@link #carried()} and {@link #members()} are the
 * service binding; the rule reads none of the service's words.
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

    /** The codes this service declares beyond the table, with their statuses. */
    static final Map<String, Integer> SERVICE_CODES = Map.of(
        "SCOPE_LOCKED", 409,
        "SCOPE_KIND_UNSUPPORTED", 422,
        "CHILDREN_NOT_FINISHED", 409,
        "DEFERRAL_PENDING", 409,
        "IDEMPOTENCY_KEY_REUSED", 409);

    private static final Pattern ROW = Pattern.compile(
        "^\\|[^|]*\\|\\s*`([A-Z_]+)`\\s*\\|\\s*(\\d{3})\\s*\\|([^|\\n]*)\\|",
        Pattern.MULTILINE);

    private static final Pattern MEMBER = Pattern.compile(
        "^\\|\\s*`([a-z_]+)`\\s*\\|", Pattern.MULTILINE);

    private static final Pattern NAMED = Pattern.compile("`([a-z_]+)`");

    private static final String MEMBERS_HEADING = "## Members of `data`";

    @Test
    void the_service_speaks_the_shared_table() throws IOException {
        assertThat(offences(table(), carried()))
            .as("one condition answers with one code and one status in every service, so a "
                + "caller that learned one service can read the next (C8.7). Align the code "
                + "and its status with the table, or declare a code the table does not have")
            .isEmpty();
    }

    @Test
    void a_refusal_carries_no_member_outside_the_list() throws IOException {
        assertThat(memberOffences(memberList(), members(), rowMembers()))
            .as("each member of data means one thing on every service, and a refusal carries "
                + "no other (C8.7). Name what the caller acts on with a member of the list")
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
        planted.put("RECEIPT_WRONG", 403);
        planted.put("NOTHING_TO_TAKE", 409);
        planted.remove("DRAW_EMPTY");
        planted.remove("CLAIM_ABSENT");
        planted.put("LEASE_LAPSED", 409);
        planted.put("ROLE_INSUFFICIENT", 409);

        assertThat(offences(table(), planted))
            .as("RED STATE, observed: a status that differs from the table, a code the "
                + "table does not have and nobody declared, and a code of the table nobody "
                + "carries must each be reported")
            .containsExactlyInAnyOrder(
                "RECEIPT_WRONG answers 403; the table says 409",
                "ROLE_INSUFFICIENT answers 409; the table says 403",
                "DRAW_EMPTY is in the table and not carried, and NOT_CARRIED does not say why",
                "CLAIM_ABSENT is in the table and not carried, and NOT_CARRIED does not say why",
                "NOTHING_TO_TAKE is neither in the table nor declared as a code of the service",
                "LEASE_LAPSED is neither in the table nor declared as a code of the service");
    }

    /** The red state of the members, observed on every build. */
    @Test
    void the_check_reports_a_member_outside_the_list() throws IOException {
        List<String> planted = new ArrayList<>(members());
        planted.add("holder");
        Map<String, List<String>> rows = new LinkedHashMap<>(rowMembers());
        rows.put("CLAIM_ABSENT", List.of("attempted", "lease"));

        assertThat(memberOffences(memberList(), planted, rows))
            .as("RED STATE, observed: a member on the wire and a member a row names, neither "
                + "in the list, must each be reported")
            .containsExactlyInAnyOrder(
                "data carries holder, which is not in the list of members",
                "the row of CLAIM_ABSENT names lease, which is not in the list of members");
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
            if (SERVICE_CODES.containsKey(code)) {
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

    static List<String> memberOffences(List<String> list, List<String> onTheWire,
                                       Map<String, List<String>> rows) {
        List<String> offences = new ArrayList<>();
        onTheWire.stream().filter(member -> !list.contains(member)).forEach(member ->
            offences.add("data carries " + member + ", which is not in the list of members"));
        rows.forEach((code, named) -> named.stream().filter(member -> !list.contains(member))
            .forEach(member -> offences.add("the row of " + code + " names " + member
                + ", which is not in the list of members")));
        return offences;
    }

    /** The copy of the code table: code to status, in its order. */
    static Map<String, Integer> table() throws IOException {
        Map<String, Integer> rows = new LinkedHashMap<>();
        Matcher m = ROW.matcher(codeTable());
        while (m.find()) {
            rows.put(m.group(1), Integer.parseInt(m.group(2)));
        }
        assertThat(rows).as("the table's rows must have been read").hasSizeGreaterThan(20);
        return rows;
    }

    /** The copy of the code table: code to the members of data its row names. */
    static Map<String, List<String>> rowMembers() throws IOException {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        Matcher m = ROW.matcher(codeTable());
        while (m.find()) {
            List<String> named = new ArrayList<>();
            Matcher member = NAMED.matcher(m.group(3));
            while (member.find()) {
                named.add(member.group(1));
            }
            rows.put(m.group(1), named);
        }
        return rows;
    }

    /** The copy of the list of members, in its order. */
    static List<String> memberList() throws IOException {
        String text = copy();
        int at = text.indexOf(MEMBERS_HEADING);
        assertThat(at).as("the copy must hold the list of members").isNotNegative();
        List<String> members = new ArrayList<>();
        Matcher m = MEMBER.matcher(text.substring(at));
        while (m.find()) {
            members.add(m.group(1));
        }
        assertThat(members).as("the list's rows must have been read").hasSizeGreaterThan(10);
        return members;
    }

    private static String codeTable() throws IOException {
        String text = copy();
        int at = text.indexOf(MEMBERS_HEADING);
        return at < 0 ? text : text.substring(0, at);
    }

    private static String copy() throws IOException {
        try (InputStream in = SharedCodeTableTest.class.getClassLoader()
                .getResourceAsStream("contract/shared-code-table.md")) {
            assertThat(in).as("the copy of the table must be on the test classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The service binding: every code the service carries, with its status. */
    private static Map<String, Integer> carried() {
        Map<String, Integer> carried = new LinkedHashMap<>();
        Arrays.stream(RefusalCode.values())
            .forEach(code -> carried.put(code.name(), code.status()));
        return carried;
    }

    /**
     * The service binding: every member a refusal's data has on the wire. A
     * plain mapper writes the members with no value too, so an empty payload
     * names them all, under the names the wire carries.
     */
    private static List<String> members() throws IOException {
        int components = RefusalDataPayload.class.getRecordComponents().length;
        Object[] none = new Object[components];
        Class<?>[] types = Arrays.stream(RefusalDataPayload.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
        try {
            RefusalDataPayload empty = RefusalDataPayload.class.getDeclaredConstructor(types)
                .newInstance(none);
            List<String> members = new ArrayList<>();
            new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(empty))
                .fieldNames().forEachRemaining(members::add);
            assertThat(members).as("every component is a member").hasSize(components);
            return members;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the payload of data cannot be built empty", e);
        }
    }
}
