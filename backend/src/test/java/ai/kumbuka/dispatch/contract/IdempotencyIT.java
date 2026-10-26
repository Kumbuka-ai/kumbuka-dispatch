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
import ai.kumbuka.dispatch.surface.Writes;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The calls that take an idempotency key, on the surface: {@code create},
 * {@code claim}, {@code claim_next} and {@code annotate} (C10.4).
 *
 * <p>The key travels as the transport of each surface carries it: on MCP the
 * top-level argument {@code idempotency_key}, on REST the header {@code
 * Idempotency-Key} and nowhere else. A REST request that carries the key in
 * its body or its query is refused as {@code ARGUMENT_UNKNOWN}, naming what
 * the call accepts there and not the key. The ledger holds a key for 24 hours.
 *
 * <p>A repeated create creates nothing and answers the first task. A
 * repeated claim answers the same task with a new receipt, with which the next
 * write succeeds, while the earlier receipt is refused; a repeated draw draws
 * nothing second (on the wire). A
 * repeated addendum attaches nothing second and answers the task, on both
 * surfaces: an addendum cannot be removed, so a duplicate would be permanent.
 *
 * <p>Red probe, observed: with the key not handed on to the kernel, the
 * repeated addendum attaches a second time. RED STATE, observed against the
 * tree that read the key from the REST body: the header was not read, so the
 * repeated creation, claim, draw and addendum over REST each did their work a
 * second time, and the key in the body was taken instead of refused.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class IdempotencyIT {

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    void a_repeated_create_creates_nothing_and_answers_the_first_task() {
        Map<String, Object> create = Map.of("scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR, "idempotency_key", "create-once",
            "fields", Map.of("title", "created once", "apparatus", "code"));
        String first = String.valueOf(Mcp.answer(Mcp.call("dispatch_create", create))
            .get("address"));
        String before = Writes.snapshot();

        String again = String.valueOf(Mcp.answer(Mcp.call("dispatch_create", create))
            .get("address"));

        assertThat(again).isEqualTo(first);
        assertThat(Writes.snapshot()).as("the repeat wrote nothing").isEqualTo(before);
    }

    @Test
    void a_repeated_claim_answers_the_same_task_and_a_new_receipt() {
        String address = SurfaceFixture.address(SurfaceFixture.open("claimed twice", "code"));
        SurfaceFixture.asExecutor(identity);
        Map<String, Object> claim = Map.of("address", address,
            "idempotency_key", "claim-once");

        Map<String, Object> first = Mcp.answer(Mcp.call("dispatch_claim", claim));
        Map<String, Object> again = Mcp.answer(Mcp.call("dispatch_claim", claim));

        assertThat(again.get("address")).isEqualTo(first.get("address"));
        assertThat(again.get("receipt")).isNotEqualTo(first.get("receipt"));
        assertThat(Mcp.field(again, "lease_expires_at"))
            .as("the hold is not taken again: its end stays")
            .isEqualTo(Mcp.field(first, "lease_expires_at"));
        assertThat(Mcp.reason(Mcp.call("dispatch_renew", Map.of("address", address,
            "receipt", first.get("receipt")))))
            .as("the earlier receipt no longer holds")
            .isEqualTo("RECEIPT_WRONG");
        Mcp.answer(Mcp.call("dispatch_renew", Map.of("address", address,
            "receipt", again.get("receipt"))));
    }

    @Test
    void a_repeated_addendum_attaches_nothing_second_on_either_surface() {
        String address = SurfaceFixture.address(SurfaceFixture.open("annotated once", "code"));
        // An addendum is added while the work runs: an executor takes the task up first.
        SurfaceFixture.asExecutor(identity);
        Mcp.answer(Mcp.call("dispatch_claim", Map.of("address", address)));
        SurfaceFixture.asConsole(identity);
        Map<String, Object> annotate = Map.of("address", address,
            "idempotency_key", "annotate-once",
            "fields", Map.of("part", "dispatch", "text", "the correction"));

        Mcp.answer(Mcp.call("dispatch_annotate", annotate));
        Map<String, Object> again = Mcp.answer(Mcp.call("dispatch_annotate", annotate));
        String id = address.substring(address.lastIndexOf('/') + 1);
        Response overRest = given().contentType(ContentType.JSON)
            .header(KEY, "annotate-once")
            .body(Map.of("fields", Map.of("part", "dispatch", "text", "the correction")))
            .post(SurfaceFixture.item(id) + ":annotate");

        assertThat(again.get("address")).as("the repeat answers the task").isEqualTo(address);
        assertThat(overRest.statusCode()).isEqualTo(200);
        assertThat(Mcp.answer(Mcp.call("dispatch_read_text", Map.of("address", address,
                "part", "addenda"))).toString())
            .as("one addendum, however often the call was repeated")
            .containsOnlyOnce("the correction");
        assertThat(Mcp.reason(Mcp.call("dispatch_annotate", Map.of("address", address,
                "idempotency_key", "annotate-once",
                "fields", Map.of("part", "dispatch", "text", "another correction")))))
            .as("the same key with another text")
            .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void a_repeated_draw_draws_nothing_second() {
        SurfaceFixture.open("drawn once", "idem");
        String other = SurfaceFixture.address(SurfaceFixture.open("left open", "idem"));
        SurfaceFixture.asExecutor(identity);
        Map<String, Object> draw = Map.of("scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR, "apparatus", List.of("idem"),
            "idempotency_key", "draw-once");

        Map<String, Object> first = Mcp.answer(Mcp.call("dispatch_claim_next", draw));
        Map<String, Object> again = Mcp.answer(Mcp.call("dispatch_claim_next", draw));

        assertThat(again.get("address")).isEqualTo(first.get("address"));
        assertThat(Mcp.field(Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", other))), "state")).isEqualTo("open");
    }

    // =======================================================================
    // REST: the header, and nowhere else
    // =======================================================================

    @Test
    void a_repeated_create_over_rest_under_the_header_answers_the_first_task() {
        Map<String, Object> body = Map.of("fields", Map.of("title", "created once over rest",
            "apparatus", "code"));
        Response first = given().contentType(ContentType.JSON).header(KEY, "rest-create-once")
            .body(body).post(SurfaceFixture.collection());
        String before = Writes.snapshot();

        Response again = given().contentType(ContentType.JSON).header(KEY, "rest-create-once")
            .body(body).post(SurfaceFixture.collection());

        assertThat(first.statusCode()).as(first.asString()).isEqualTo(201);
        assertThat(again.jsonPath().getString("address"))
            .isEqualTo(first.jsonPath().getString("address"));
        assertThat(Writes.snapshot()).as("the repeat wrote nothing").isEqualTo(before);
    }

    @Test
    void a_repeated_claim_and_draw_over_rest_under_the_header_take_nothing_second() {
        String id = SurfaceFixture.open("claimed twice over rest", "code");
        SurfaceFixture.open("drawn once over rest", "rest-idem");
        String other = SurfaceFixture.address(SurfaceFixture.open("left open", "rest-idem"));
        SurfaceFixture.asExecutor(identity);

        Response first = given().contentType(ContentType.JSON).header(KEY, "rest-claim-once")
            .post(SurfaceFixture.item(id) + ":claim");
        Response again = given().contentType(ContentType.JSON).header(KEY, "rest-claim-once")
            .post(SurfaceFixture.item(id) + ":claim");
        assertThat(again.statusCode()).as(again.asString()).isEqualTo(200);
        assertThat(again.jsonPath().getString("receipt"))
            .isNotEqualTo(first.jsonPath().getString("receipt"));
        assertThat(again.jsonPath().getString("fields.lease_expires_at"))
            .as("the hold is not taken again: its end stays")
            .isEqualTo(first.jsonPath().getString("fields.lease_expires_at"));

        Map<String, Object> draw = Map.of("apparatus", List.of("rest-idem"));
        Response drawn = given().contentType(ContentType.JSON).header(KEY, "rest-draw-once")
            .body(draw).post(SurfaceFixture.collection() + ":claim_next");
        Response redrawn = given().contentType(ContentType.JSON).header(KEY, "rest-draw-once")
            .body(draw).post(SurfaceFixture.collection() + ":claim_next");
        assertThat(redrawn.jsonPath().getString("address"))
            .isEqualTo(drawn.jsonPath().getString("address"));
        assertThat(Mcp.field(Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", other))), "state")).isEqualTo("open");
    }

    @Test
    void the_key_in_a_rest_body_or_query_is_an_argument_unknown_there() {
        Response inBody = given().contentType(ContentType.JSON)
            .body(Map.of("idempotency_key", "zz-in-the-body",
                "fields", Map.of("title", "keyed in the body", "apparatus", "code")))
            .post(SurfaceFixture.collection());
        assertUnknownWithoutTheKey(inBody);

        Response inQuery = given().contentType(ContentType.JSON)
            .queryParam("idempotency_key", "zz-in-the-query")
            .body(Map.of("fields", Map.of("title", "keyed in the query", "apparatus", "code")))
            .post(SurfaceFixture.collection());
        assertUnknownWithoutTheKey(inQuery);

        String id = SurfaceFixture.open("keyed in the body", "code");
        SurfaceFixture.asExecutor(identity);
        assertUnknownWithoutTheKey(given().contentType(ContentType.JSON)
            .body(Map.of("idempotency_key", "zz-in-the-body"))
            .post(SurfaceFixture.item(id) + ":claim"));
    }

    @Test
    void the_ledger_holds_a_key_for_twenty_four_hours() {
        Map<String, Object> body = Map.of("fields", Map.of("title", "kept a day",
            "apparatus", "code"));
        Response first = given().contentType(ContentType.JSON).header(KEY, "rest-a-day")
            .body(body).post(SurfaceFixture.collection());
        age("rest-a-day", "23 hours 59 minutes");
        Response withinTheDay = given().contentType(ContentType.JSON).header(KEY, "rest-a-day")
            .body(body).post(SurfaceFixture.collection());
        assertThat(withinTheDay.jsonPath().getString("address"))
            .as("a key spent less than a day ago still answers the first call")
            .isEqualTo(first.jsonPath().getString("address"));

        age("rest-a-day", "24 hours 1 minute");
        Response pastTheDay = given().contentType(ContentType.JSON).header(KEY, "rest-a-day")
            .body(body).post(SurfaceFixture.collection());
        assertThat(pastTheDay.statusCode()).as(pastTheDay.asString()).isEqualTo(201);
        assertThat(pastTheDay.jsonPath().getString("address"))
            .as("a key spent more than a day ago is spent anew")
            .isNotEqualTo(first.jsonPath().getString("address"));
    }

    // -----------------------------------------------------------------------

    /** The header that carries the key on REST. */
    private static final String KEY = "Idempotency-Key";

    private static void assertUnknownWithoutTheKey(Response refused) {
        assertThat(refused.statusCode()).as(refused.asString()).isEqualTo(400);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(refused.jsonPath().getList("data.accepts", String.class))
            .as("what the call accepts in the body and the query, which the key is not")
            .isNotEmpty()
            .doesNotContain("idempotency_key");
        assertThat(refused.asString()).doesNotContain("zz-in-the");
    }

    /** Moves the first use of a spent key into the past, as the clock would. */
    private static void age(String key, String by) {
        ai.kumbuka.dispatch.platform.PlatformFixture.run("UPDATE dispatch.task_idempotency_key "
            + "SET first_seen_at = now() - interval '" + by + "' WHERE idempotency_key = '"
            + key + "'");
    }
}
