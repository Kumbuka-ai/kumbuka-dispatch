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
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An addendum to a sent commission is visible to whoever reads the task, in
 * the answer as a caller receives it, without guessing an address.
 *
 * <p>The head of {@code read} and of every entry of {@code query} lists the
 * texts that exist, by type and suffix; {@code read_text} of the commission
 * carries the commission alone and counts its addenda; {@code read_text} of
 * the addenda carries them. Each of the four is asserted for the
 * commissioner and for the holder after a claim, on both surfaces, and on
 * the MCP surface in the structured content and in the text content alike:
 * a client may read either.
 *
 * <p>The head was not pinned at the level of the answer before: the domain
 * keeps the suffix, and nothing failed when the wire dropped it — which is
 * how an executor once worked through a commission without the addendum
 * that changed it.
 *
 * <p>Red probe, observed: with the suffix left out of {@code
 * Answers.textHead}, both tests turn red on the head of {@code read}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class AddendumVisibilityIT {

    private static final String COMMISSION = "the commission, before its addendum";
    private static final String ADDENDUM = "the addendum to the commission";

    /** The texts a head must list: the commission, and its first addendum. */
    private static final List<Map<String, Object>> HEAD_TEXTS = List.of(
        Map.of("type", "dispatch"),
        Map.of("type", "dispatch", "suffix", "a"));

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    void over_mcp_the_addendum_is_listed_in_the_head_and_read_in_its_part() {
        Map<String, Object> created = mcp("dispatch_create", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
            "fields", Map.of("title", "an annotated commission", "apparatus", "code",
                "text", COMMISSION)));
        String address = String.valueOf(created.get("address"));
        mcp("dispatch_send", Map.of("address", address,
            "conflict_token", created.get("conflict_token")));
        mcp("dispatch_annotate", Map.of("address", address,
            "fields", Map.of("part", "dispatch", "text", ADDENDUM)));

        assertVisibleOverMcp(address, "the commissioner");

        SurfaceFixture.asExecutor(identity);
        mcp("dispatch_claim", Map.of("address", address));
        assertVisibleOverMcp(address, "the holder");
    }

    @Test
    void over_rest_the_addendum_is_listed_in_the_head_and_read_in_its_part() {
        String id = SurfaceFixture.open("an annotated commission over rest", "code");
        Response annotated = given().contentType(ContentType.JSON)
            .body(Map.of("fields", Map.of("part", "dispatch", "text", ADDENDUM)))
            .post(SurfaceFixture.item(id) + ":annotate");
        assertThat(annotated.statusCode()).as("annotate: %s", annotated.asString())
            .isEqualTo(200);

        assertVisibleOverRest(id, "the commissioner");

        SurfaceFixture.asExecutor(identity);
        given().contentType(ContentType.JSON).body(Map.of("duration", "PT1H"))
            .post(SurfaceFixture.item(id) + ":claim").then().statusCode(200);
        assertVisibleOverRest(id, "the holder");
    }

    // -----------------------------------------------------------------------

    private void assertVisibleOverMcp(String address, String who) {
        Map<String, Object> head = mcp("dispatch_read", Map.of("address", address));
        assertThat(texts(fields(head))).as("%s: the texts in the head of dispatch_read", who)
            .containsExactlyInAnyOrderElementsOf(HEAD_TEXTS);

        Map<String, Object> listed = mcp("dispatch_query", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
            "address", address));
        assertThat(texts(fields(onlyTask(listed, address))))
            .as("%s: the texts in this task's entry of dispatch_query", who)
            .containsExactlyInAnyOrderElementsOf(HEAD_TEXTS);

        assertCommissionPart(mcp("dispatch_read_text", Map.of("address", address,
            "part", "dispatch")), who);
        assertAddendaPart(mcp("dispatch_read_text", Map.of("address", address,
            "part", "addenda")), who);
    }

    private void assertVisibleOverRest(String id, String who) {
        Map<String, Object> head = rest(SurfaceFixture.item(id));
        assertThat(texts(fields(head))).as("%s: the texts in the head of read", who)
            .containsExactlyInAnyOrderElementsOf(HEAD_TEXTS);

        String address = String.valueOf(head.get("address"));
        Map<String, Object> listed = rest(SurfaceFixture.collection() + "?address=" + address);
        assertThat(texts(fields(onlyTask(listed, address))))
            .as("%s: the texts in this task's entry of query", who)
            .containsExactlyInAnyOrderElementsOf(HEAD_TEXTS);

        assertCommissionPart(rest(SurfaceFixture.item(id) + ":read_text?part=dispatch"), who);
        assertAddendaPart(rest(SurfaceFixture.item(id) + ":read_text?part=addenda"), who);
    }

    private static void assertCommissionPart(Map<String, Object> part, String who) {
        assertThat(texts(part)).as("%s: read_text of the commission carries it alone", who)
            .hasSize(1);
        Map<String, Object> commission = texts(part).get(0);
        assertThat(commission.get("type")).isEqualTo("dispatch");
        assertThat(commission).as("%s: the commission itself carries no suffix", who)
            .doesNotContainKey("suffix");
        assertThat(part.get("addenda")).as("%s: and counts its one addendum", who)
            .isEqualTo(1);
    }

    private static void assertAddendaPart(Map<String, Object> part, String who) {
        assertThat(texts(part)).as("%s: read_text of the addenda", who).hasSize(1);
        Map<String, Object> addendum = texts(part).get(0);
        assertThat(addendum.get("suffix")).as("%s: the addendum's suffix", who).isEqualTo("a");
        assertThat(addendum.get("text")).as("%s: the addendum's text", who).isEqualTo(ADDENDUM);
    }

    /**
     * A call of the assistant surface that must succeed, answered as its
     * structured content, after asserting that the text content carries the
     * same value.
     */
    private static Map<String, Object> mcp(String tool, Map<String, Object> arguments) {
        Response response = Mcp.call(tool, arguments);
        Map<String, Object> structured = Mcp.answer(response);
        String text = response.jsonPath().getString("result.content[0].text");
        assertThat(JsonPath.from(text).getMap("").toString())
            .as("%s: the text content is the structured content", tool)
            .isEqualTo(structured.toString());
        return structured;
    }

    /** A GET of the REST surface that must succeed, answered as its body. */
    private static Map<String, Object> rest(String path) {
        Response response = given().get(path);
        assertThat(response.statusCode()).as("GET %s: %s", path, response.asString())
            .isEqualTo(200);
        return response.jsonPath().getMap("");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlyTask(Map<String, Object> listing, String address) {
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) listing.get("objects");
        assertThat(tasks).extracting(t -> t.get("address"))
            .as("the query narrowed to this task").containsExactly(address);
        return tasks.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fields(Map<String, Object> answer) {
        return (Map<String, Object>) answer.get("fields");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> texts(Map<String, Object> holder) {
        return (List<Map<String, Object>>) holder.get("texts");
    }
}
