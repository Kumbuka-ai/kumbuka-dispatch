/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.architecture;

import ai.kumbuka.dispatch.surface.ProcessVerb;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tool is named {@code <service>_<verb>} and that name is the declaration's
 * identity of the call (C4.2, DEC-0043 R1).
 *
 * <p>The rule is stated over three names per call: the identity the
 * declaration publishes, the name the call carries on REST, and the tool name
 * the assistant surface lists. It holds when the tool name equals the identity
 * and the identity is the scheme, an underscore and the REST name. A tool list
 * of bare verbs, the shape measured in a sibling service, breaks it twice.
 *
 * <p>That the tool list on the wire is the declaration is {@code McpProtocolIT}'s
 * to observe; this reads the names the declaration holds, which needs no
 * container. The one service-specific part is {@link #declared()}.
 */
class ToolNameTest {

    /** The scheme of this service, as its addresses and tools carry it. */
    private static final String SCHEME = "dispatch";

    /** A verb as the platform writes one: lower case, words joined by underscores. */
    private static final Pattern VERB = Pattern.compile("[a-z]+(_[a-z]+)*");

    /** One call's three names. */
    record Names(String identity, String restName, String toolName) {
    }

    @Test
    void every_tool_is_named_by_the_identity_of_its_call() {
        assertThat(offenders(SCHEME, declared()))
            .as("a tool is named <service>_<verb>, and that name is the identity the "
                + "declaration publishes for the call (DEC-0043 R1). A router that takes the "
                + "declaration names the tool by the identity; a tool list that differs is a "
                + "second vocabulary for one call")
            .isEmpty();
    }

    /** The red state, observed on every build: bare verbs and a foreign prefix. */
    @Test
    void the_check_reports_a_bare_verb_and_a_foreign_prefix() {
        List<Names> planted = List.of(
            new Names(SCHEME + "_send", "send", "send"),
            new Names("other_claim", "claim", "other_claim"),
            new Names(SCHEME + "_read", "read", SCHEME + "_read"));

        assertThat(offenders(SCHEME, planted))
            .as("RED STATE, observed: a tool listed by its bare verb, and an identity under "
                + "another scheme, must each be reported; the conforming call must not")
            .containsExactly(
                "send: tool name 'send' is not the identity '" + SCHEME + "_send'",
                "claim: identity 'other_claim' is not '" + SCHEME + "_claim'");
    }

    static List<String> offenders(String scheme, List<Names> calls) {
        List<String> offenders = new ArrayList<>();
        for (Names call : calls) {
            String expected = scheme + "_" + call.restName();
            if (!VERB.matcher(call.restName()).matches()) {
                offenders.add(call.restName() + ": REST name is not a verb");
            } else if (!call.identity().equals(expected)) {
                offenders.add(call.restName() + ": identity '" + call.identity()
                    + "' is not '" + expected + "'");
            } else if (!call.toolName().equals(call.identity())) {
                offenders.add(call.restName() + ": tool name '" + call.toolName()
                    + "' is not the identity '" + call.identity() + "'");
            }
        }
        return offenders;
    }

    /** The service binding: the three names of every declared call. */
    private static List<Names> declared() {
        List<Names> names = Arrays.stream(ProcessVerb.values())
            .map(verb -> new Names(verb.call(), verb.verb(), verb.call()))
            .toList();
        assertThat(names).as("the declaration must have been read").isNotEmpty();
        return names;
    }
}
