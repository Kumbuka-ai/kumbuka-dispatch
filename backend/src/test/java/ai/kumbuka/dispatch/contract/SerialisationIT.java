/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.surface.Mcp;
import ai.kumbuka.dispatch.surface.SurfaceFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * How an answer is written (C7.2, C7.3): a member with no value is absent, and
 * an instant is ISO-8601 in UTC, to the microsecond at most.
 *
 * <p>A set of answers is collected over both surfaces — a created draft, a
 * claimed task with its lease, a deferred one, a page, a text, refusals — and
 * every node of every answer is walked: no member is {@code null}, and every
 * member that names an instant has the one form.
 *
 * <p>RED STATE, observed 2026-10-16 against this change before its serialiser
 * was applied last: Jackson's own module for {@code java.time} replaced it, and
 * a deferral written to the nanosecond came back to the nanosecond.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class SerialisationIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern INSTANT = Pattern.compile(
        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?Z");
    private static final List<String> INSTANT_MEMBERS = List.of("lease_expires_at",
        "not_before", "created_at");

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    void no_member_is_null_and_every_instant_has_the_one_form() throws Exception {
        List<String> answers = new ArrayList<>();
        Response created = SurfaceFixture.create(Map.of("title", "written", "apparatus", "code",
            "text", "the commission"));
        answers.add(created.asString());
        String id = SurfaceFixture.idOf(created);
        String address = SurfaceFixture.address(id);
        answers.add(given().get(SurfaceFixture.item(id)).asString());
        answers.add(Mcp.call("dispatch_send", Map.of("address", address,
            "conflict_token", token(address))).asString());

        SurfaceFixture.asExecutor(identity);
        Response claimed = Mcp.call("dispatch_claim", Map.of("address", address,
            "duration", "PT2H"));
        answers.add(claimed.asString());
        String receipt = String.valueOf(Mcp.answer(claimed).get("receipt"));
        answers.add(Mcp.call("dispatch_read_text", Map.of("address", address,
            "part", "dispatch")).asString());
        answers.add(Mcp.call("dispatch_defer", Map.of("address", address,
            "receipt", receipt, "fields", Map.of("not_before", "2099-01-01T00:00:00.123456789Z")))
            .asString());
        answers.add(given().queryParam("address", address).get(SurfaceFixture.collection())
            .asString());
        answers.add(Mcp.call("dispatch_read",
            Map.of("address", "dispatch://" + SurfaceFixture.SCOPE + "/sprint/99999.0")).asString());
        answers.add(Mcp.call("dispatch_renew", Map.of("address", address,
            "receipt", "not-the-receipt")).asString());

        List<String> offences = new ArrayList<>();
        for (String answer : answers) {
            walk(JSON.readTree(answer), "$", offences);
        }
        assertThat(offences).as("members with no value, and instants of another form").isEmpty();
        assertThat(answers).as("the lease and the deferral were written at all")
            .anySatisfy(a -> assertThat(a).contains("lease_expires_at"))
            .anySatisfy(a -> assertThat(a).contains("not_before"));
    }

    @Test
    void not_found_carries_no_data_member_at_all() {
        Response answer = given().get("/api/" + SurfaceFixture.SCOPE + "/sprint/99999.0");
        assertThat(answer.jsonPath().getMap("$")).containsOnlyKeys("reason", "message");
        assertThat(answer.asString()).doesNotContain("null");
    }

    private static void walk(JsonNode node, String path, List<String> offences) {
        if (node.isNull()) {
            offences.add(path + " is null");
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> members = node.fields();
            while (members.hasNext()) {
                Map.Entry<String, JsonNode> member = members.next();
                String at = path + "." + member.getKey();
                if (INSTANT_MEMBERS.contains(member.getKey()) && member.getValue().isTextual()
                        && !INSTANT.matcher(member.getValue().asText()).matches()) {
                    offences.add(at + " is " + member.getValue().asText());
                }
                walk(member.getValue(), at, offences);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), path + "[" + i + "]", offences);
            }
        }
    }

    private static String token(String address) {
        return String.valueOf(Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", address))).get("conflict_token"));
    }
}
