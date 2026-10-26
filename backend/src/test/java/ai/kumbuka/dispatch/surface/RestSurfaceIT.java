/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The REST surface over the wire: the same calls under the same names, the
 * HTTP expression of each, and the closure of every call.
 *
 * <p>Criterion 3 on REST: an invented argument in the body, under {@code
 * fields} or in the query is refused by name and writes nothing. Criterion 6
 * on REST: a call under an earlier name is refused as unknown.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class RestSurfaceIT {

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    void the_main_course_over_rest() {
        Response created = SurfaceFixture.create(Map.of("title", "over rest",
            "apparatus", "code", "text", "MARK-REST the commission"));
        assertThat(created.statusCode()).isEqualTo(201);
        String id = SurfaceFixture.idOf(created);
        assertThat(created.header("Location")).endsWith(SurfaceFixture.item(id));
        assertThat(created.header("ETag")).isNotBlank();
        assertThat(created.header("ETag"))
            .as("the conflict token in the body is the one in the ETag")
            .isEqualTo("\"" + created.jsonPath().getString("conflict_token") + "\"");
        assertThat(created.asString()).doesNotContain("MARK-REST");

        Response updated = given().contentType(ContentType.JSON)
            .header("If-Match", created.header("ETag"))
            .body(Map.of("fields", Map.of("title", "over rest, renamed")))
            .patch(SurfaceFixture.item(id));
        assertThat(updated.statusCode()).isEqualTo(200);
        Response sent = given().header("If-Match", updated.header("ETag"))
            .post(SurfaceFixture.item(id) + ":send");
        assertThat(sent.jsonPath().getString("fields.state")).isEqualTo("open");

        SurfaceFixture.asExecutor(identity);
        Response claimed = given().contentType(ContentType.JSON)
            .body(Map.of("duration", "PT1H")).post(SurfaceFixture.item(id) + ":claim");
        assertThat(claimed.statusCode()).isEqualTo(200);
        assertThat(claimed.jsonPath().getString("next[0].call")).isEqualTo("read_text");
        String receipt = claimed.jsonPath().getString("receipt");
        Response text = given().get(SurfaceFixture.item(id) + ":read_text?part=dispatch");
        assertThat(text.jsonPath().getString("texts[0].text"))
            .isEqualTo("MARK-REST the commission");
        Response delivered = given().contentType(ContentType.JSON)
            .body(Map.of("receipt", receipt, "fields", Map.of("text", "MARK-REST answer")))
            .post(SurfaceFixture.item(id) + ":deliver");
        assertThat(delivered.jsonPath().getString("fields.state")).isEqualTo("delivered");
        assertThat(delivered.asString()).doesNotContain("MARK-REST");

        SurfaceFixture.asConsole(identity);
        Response head = given().get(SurfaceFixture.item(id));
        assertThat(head.jsonPath().getString("fields.title")).isEqualTo("over rest, renamed");
        assertThat(head.jsonPath().getList("next.call")).startsWith("read_text");
        Response accepted = given().header("If-Match", head.header("ETag"))
            .post(SurfaceFixture.item(id) + ":accept");
        assertThat(accepted.jsonPath().getString("fields.outcome")).isEqualTo("accepted");
    }

    @Test
    void a_draft_is_deleted_and_leaves_nothing() {
        Response created = SurfaceFixture.create(Map.of("title", "to delete",
            "apparatus", "code"));
        String id = SurfaceFixture.idOf(created);

        Response deleted = given().header("If-Match", created.header("ETag"))
            .delete(SurfaceFixture.item(id));

        assertThat(deleted.statusCode()).isEqualTo(200);
        assertThat(deleted.jsonPath().getMap("")).containsOnlyKeys("address");
        given().get(SurfaceFixture.item(id)).then().statusCode(404);
    }

    @Test
    void an_invented_argument_is_refused_by_name_wherever_it_arrives_and_writes_nothing() {
        String id = SurfaceFixture.open("closed calls", "code");
        String before = Writes.snapshot();

        Response inBody = given().contentType(ContentType.JSON)
            .body(Map.of("fields", Map.of("title", "t", "apparatus", "code"), "invented", 1))
            .post(SurfaceFixture.collection());
        Response inFields = given().contentType(ContentType.JSON)
            .body(Map.of("fields", Map.of("title", "t", "apparatus", "code", "date", "x")))
            .post(SurfaceFixture.collection());
        Response inQuery = given().post(SurfaceFixture.item(id) + ":withdraw?invented=1");
        Response onAWrite = given().contentType(ContentType.JSON)
            .body(Map.of("remark", "a remark at the wrong level"))
            .post(SurfaceFixture.item(id) + ":reject");
        Response notJson = given().contentType(ContentType.JSON)
            .body("{\"fields\": {\"title\": ").post(SurfaceFixture.collection());
        assertThat(List.of(notJson.statusCode(), notJson.jsonPath().getString("reason")))
            .as("a body that is no JSON object is a malformed payload (C8.7)")
            .isEqualTo(List.of(400, "PAYLOAD_MALFORMED"));

        for (Response refused : List.of(inBody, inFields, inQuery, onAWrite)) {
            assertThat(refused.statusCode()).isEqualTo(400);
            assertThat(refused.jsonPath().getString("reason")).isEqualTo("ARGUMENT_UNKNOWN");
        }
        assertThat(inBody.asString()).doesNotContain("invented");
        assertThat(inFields.asString()).doesNotContain("date");
        assertThat(inQuery.asString()).doesNotContain("invented");
        assertThat(onAWrite.jsonPath().getList("data.accepts", String.class))
            .as("remark is written, so it belongs under fields, which reject declares")
            .containsExactly("address", "fields");
        assertThat(inFields.jsonPath().getList("data.accepts", String.class))
            .containsExactly("fields.title", "fields.apparatus", "fields.text",
                "fields.metadata");
        assertThat(Writes.snapshot()).as("none of them wrote").isEqualTo(before);
    }

    @Test
    void an_undeclared_filter_is_refused() {
        Response refused = given().get(SurfaceFixture.collection() + "?holder=self");
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(refused.asString()).doesNotContain("holder");
        assertThat(refused.jsonPath().getList("data.accepts", String.class))
            .contains("state", "apparatus", "bracket", "address");
    }

    @Test
    void every_level_with_an_undeclared_argument_is_answered_with_what_it_accepts() {
        Response atTop = given().get(SurfaceFixture.collection() + "?status=open&holder=self");
        assertThat(atTop.statusCode()).isEqualTo(400);
        assertThat(atTop.jsonPath().getString("reason")).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(atTop.jsonPath().getString("message"))
            .as("the declared arguments, and neither of the names that arrived")
            .isEqualTo("query takes these arguments: scope, selector, state, apparatus, "
                + "bracket, address, page_size, after, sort, direction.");
        assertThat(atTop.asString()).doesNotContain("status").doesNotContain("holder");

        String before = Writes.snapshot();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", "two made-up fields");
        fields.put("apparatus", "code");
        fields.put("colour", "red");
        fields.put("weight", "1");
        Response below = given().contentType(ContentType.JSON)
            .body(Map.of("fields", fields)).post(SurfaceFixture.collection());
        assertThat(below.statusCode()).isEqualTo(400);
        assertThat(below.jsonPath().getString("reason")).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(below.jsonPath().getString("message"))
            .isEqualTo("create takes these arguments under fields: fields.title, "
                + "fields.apparatus, fields.text, fields.metadata.");
        assertThat(Writes.snapshot()).as("the create wrote nothing").isEqualTo(before);

        Response topFirst = given().contentType(ContentType.JSON)
            .body(Map.of("fields", Map.of("title", "t", "apparatus", "code", "colour", "red")))
            .post(SurfaceFixture.collection() + "?stray=x");
        assertThat(topFirst.statusCode()).isEqualTo(400);
        assertThat(topFirst.jsonPath().getString("reason")).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(topFirst.jsonPath().getString("message"))
            .as("the top level is refused first, and alone")
            .startsWith("create takes these arguments: ")
            .doesNotContain("stray").doesNotContain("colour");
        assertThat(Writes.snapshot()).as("nor did the one with both").isEqualTo(before);
    }

    @Test
    void a_page_size_outside_its_bounds_is_refused_by_name_and_the_bounds_are_taken() {
        for (String outside : List.of("0", "-1", "201", "4294967297", "-2147483649", "abc",
                "7.5")) {
            Response refused = given().get(SurfaceFixture.collection() + "?page_size=" + outside);
            assertThat(refused.statusCode()).as(outside).isEqualTo(400);
            assertThat(refused.jsonPath().getString("reason")).as(outside)
                .isEqualTo("PAGE_SIZE_REJECTED");
            assertThat(refused.jsonPath().getString("message")).as(outside)
                .startsWith("page_size is not valid for ")
                .endsWith("it is a whole number from 1 to 200.");
            assertThat(refused.jsonPath().getList("data.offenders", String.class)).as(outside)
                .containsExactly("page_size");
        }
        for (String bound : List.of("1", "200")) {
            assertThat(given().get(SurfaceFixture.collection() + "?page_size=" + bound)
                .statusCode()).as("%s is taken", bound).isEqualTo(200);
        }
        Response earlier = given().get(SurfaceFixture.collection() + "?limit=10");
        assertThat(earlier.jsonPath().getString("reason"))
            .as("the earlier page bound is not an argument any more")
            .isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(earlier.statusCode()).isEqualTo(400);
        assertThat(earlier.jsonPath().getList("data.accepts", String.class))
            .contains("page_size").doesNotContain("limit");

        Response sideways = given().get(SurfaceFixture.collection() + "?direction=sideways");
        assertThat(sideways.statusCode()).isEqualTo(400);
        assertThat(sideways.jsonPath().getString("reason")).isEqualTo("ARGUMENT_INVALID");
        assertThat(sideways.jsonPath().getList("data.offenders", String.class))
            .containsExactly("direction");
    }

    @Test
    void a_listing_pages_over_rest_by_its_cursor_and_refuses_a_sort_it_does_not_declare() {
        for (int i = 0; i < 3; i++) {
            SurfaceFixture.open("paged " + i, "code");
        }
        List<String> seen = new java.util.ArrayList<>();
        String after = null;
        int pages = 0;
        do {
            Response page = given().get(SurfaceFixture.collection() + "?page_size=1"
                + (after == null ? "" : "&after=" + after));
            assertThat(page.statusCode()).isEqualTo(200);
            seen.addAll(page.jsonPath().getList("objects.address", String.class));
            after = page.jsonPath().getString("next_cursor");
            assertThat(++pages).as("the walk ends").isLessThan(1000);
        } while (after != null);
        assertThat(seen).doesNotHaveDuplicates().hasSize(pages);

        Response sorted = given().get(SurfaceFixture.collection() + "?sort=title");
        assertThat(sorted.statusCode()).isEqualTo(400);
        assertThat(sorted.jsonPath().getString("reason")).isEqualTo("SORT_UNDECLARED");
        assertThat(sorted.jsonPath().getString("message")).doesNotContain("title")
            .contains("id or created_at");
        assertThat(sorted.jsonPath().getList("data.offenders", String.class))
            .containsExactly("sort");
    }

    @Test
    void a_call_under_an_earlier_name_is_refused_as_unknown() {
        String id = SurfaceFixture.open("old names", "code");
        String before = Writes.snapshot();
        for (String earlier : List.of("abandon", "block", "close", "consume", "takeup",
                "ratify", "revert")) {
            Response refused = given().contentType(ContentType.JSON)
                .post(SurfaceFixture.item(id) + ":" + earlier);
            assertThat(refused.statusCode()).as(earlier).isEqualTo(405);
            assertThat(refused.jsonPath().getString("reason")).as(earlier)
                .isEqualTo("VERB_NOT_CARRIED");
            assertThat(refused.asString()).as(earlier).doesNotContain(":" + earlier)
                .doesNotContain("\"" + earlier + "\"");
            assertThat(refused.jsonPath().getList("data.accepts", String.class)).as(earlier)
                .as("the verbs a task's address carries (C3.3)")
                .hasSize(23).contains("send", "recall", "accept").doesNotContain("create", "query");
        }
        assertThat(Writes.snapshot()).isEqualTo(before);
    }

    @Test
    void a_conflict_token_given_twice_is_refused() {
        String id = SurfaceFixture.open("twice", "code");
        Response refused = given().contentType(ContentType.JSON)
            .header("If-Match", "\"a\"")
            .body(Map.of("conflict_token", "b"))
            .post(SurfaceFixture.item(id) + ":withdraw");
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("ARGUMENT_INVALID");
    }
}
