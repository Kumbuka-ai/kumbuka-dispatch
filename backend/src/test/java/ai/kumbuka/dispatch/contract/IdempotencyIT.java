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

    // =======================================================================
    // C10.4 in two halves: the repeat that still has its first result, and
    // the one whose first result is gone
    // =======================================================================

    /**
     * The first half: the same key with the same arguments within 24 hours
     * answers the first result and writes nothing.
     */
    @Test
    void within_a_day_the_same_key_and_arguments_answer_the_first_result() {
        Map<String, Object> create = createUnder("c10-4-first-half", "kept and answered");
        String first = String.valueOf(Mcp.answer(Mcp.call("dispatch_create", create))
            .get("address"));
        age("c10-4-first-half", "23 hours 59 minutes");
        String before = Writes.snapshot();

        String again = String.valueOf(Mcp.answer(Mcp.call("dispatch_create", create))
            .get("address"));

        assertThat(again).as("the first result, still standing").isEqualTo(first);
        assertThat(Writes.snapshot()).as("the repeat wrote nothing").isEqualTo(before);
    }

    /**
     * The second half: once the draft the key produced is deleted, a repeat
     * under the key is refused and carries nothing out a second time.
     */
    @Test
    void a_repeat_after_the_draft_was_deleted_is_refused_and_creates_nothing() {
        Map<String, Object> create = createUnder("c10-4-second-half", "deleted, then repeated");
        Map<String, Object> first = Mcp.answer(Mcp.call("dispatch_create", create));
        Mcp.answer(Mcp.call("dispatch_delete", Map.of("address", first.get("address"),
            "conflict_token", first.get("conflict_token"))));
        String before = Writes.snapshot();

        Response again = Mcp.call("dispatch_create", create);

        assertThat(Mcp.reason(again))
            .as("RED STATE, observed: deleting the draft deleted the key with it, and the "
                + "repeat created a second draft as if it were the first call")
            .isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(Writes.snapshot()).as("the refused repeat wrote nothing").isEqualTo(before);
    }

    @Test
    void a_key_outside_one_to_255_printable_ascii_characters_is_argument_invalid() {
        for (String key : List.of("", "k".repeat(256), "zzschlüssel", "zz\u0007bell",
                "zz\ttab")) {
            String before = Writes.snapshot();
            Response refused = Mcp.call("dispatch_create",
                createUnder(key, "a key outside its form"));
            Map<String, Object> envelope = Mcp.refusal(refused);
            assertThat(envelope.get("reason"))
                .as("RED STATE, observed: a key of %d characters outside the form was taken",
                    key.length())
                .isEqualTo("ARGUMENT_INVALID");
            assertThat(String.valueOf(envelope.get("data"))).contains("idempotency_key");
            assertThat(refused.asString()).as("the refusal repeats no key")
                .doesNotContain("zzschl").doesNotContain("zz\\u0007").doesNotContain("kkkkkkkk");
            assertThat(Writes.snapshot()).as("and it wrote nothing").isEqualTo(before);
        }

        Response overRest = given().contentType(ContentType.JSON).header(KEY, "k".repeat(256))
            .body(Map.of("fields", Map.of("title", "a long key over rest", "apparatus", "code")))
            .post(SurfaceFixture.collection());
        assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(400);
        assertThat(overRest.jsonPath().getString("reason")).isEqualTo("ARGUMENT_INVALID");

        Mcp.answer(Mcp.call("dispatch_create",
            createUnder("~".repeat(255), "a key of 255 printable characters")));
        Mcp.answer(Mcp.call("dispatch_create",
            createUnder(" !", "a key of a space and a mark")));
    }

    /**
     * An empty key is a key outside its form, on every call that takes one and
     * on both surfaces: the empty {@code Idempotency-Key} header on REST and
     * the empty string as {@code idempotency_key} on MCP are each refused as
     * {@code ARGUMENT_INVALID} naming {@code idempotency_key}, and the call is
     * not carried out (C10.4). Read as no key, the call would run without the
     * idempotency its caller asked for.
     *
     * <p>RED STATE, observed in the run that built the C10.4 key form: the
     * empty header on REST was read as no key, and the create ran.
     */
    @Test
    void an_empty_key_is_argument_invalid_on_both_surfaces_for_every_call_that_takes_one() {
        String id = SurfaceFixture.open("keyed empty", "code");
        String address = SurfaceFixture.address(id);
        Map<String, Object> fields = Map.of("title", "an empty key", "apparatus", "code");
        Map<String, Object> addendum = Map.of("part", "dispatch", "text", "an empty key");

        String before = Writes.snapshot();
        assertEmptyKeyRefused(given().contentType(ContentType.JSON).header(KEY, "")
            .body(Map.of("fields", fields)).post(SurfaceFixture.collection()));
        assertEmptyKeyRefused(Mcp.call("dispatch_create", Map.of("scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR, "idempotency_key", "", "fields", fields)));
        assertEmptyKeyRefused(given().contentType(ContentType.JSON).header(KEY, "")
            .body(Map.of("fields", addendum)).post(SurfaceFixture.item(id) + ":annotate"));
        assertEmptyKeyRefused(Mcp.call("dispatch_annotate", Map.of("address", address,
            "idempotency_key", "", "fields", addendum)));

        SurfaceFixture.asExecutor(identity);
        assertEmptyKeyRefused(given().contentType(ContentType.JSON).header(KEY, "")
            .post(SurfaceFixture.item(id) + ":claim"));
        assertEmptyKeyRefused(Mcp.call("dispatch_claim", Map.of("address", address,
            "idempotency_key", "")));
        assertEmptyKeyRefused(given().contentType(ContentType.JSON).header(KEY, "")
            .body(Map.of("apparatus", List.of("code")))
            .post(SurfaceFixture.collection() + ":claim_next"));
        assertEmptyKeyRefused(Mcp.call("dispatch_claim_next", Map.of("scope",
            SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
            "apparatus", List.of("code"), "idempotency_key", "")));

        assertThat(Writes.snapshot()).as("no refused call was carried out").isEqualTo(before);
        assertThat(Mcp.field(Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", address))), "state")).as("the task was neither claimed nor drawn")
            .isEqualTo("open");
    }

    /** {@code ARGUMENT_INVALID} naming the key and nothing else, on either surface. */
    private static void assertEmptyKeyRefused(Response refused) {
        boolean overMcp = refused.jsonPath().get("jsonrpc") != null;
        String body = refused.asString();
        if (overMcp) {
            Map<String, Object> envelope = Mcp.refusal(refused);
            assertThat(envelope.get("reason")).as(body).isEqualTo("ARGUMENT_INVALID");
            assertThat(refused.jsonPath().getList("result.structuredContent.data.offenders",
                String.class)).as(body).containsExactly("idempotency_key");
            return;
        }
        assertThat(refused.statusCode()).as(body).isEqualTo(400);
        assertThat(refused.jsonPath().getString("reason"))
            .as("RED STATE, observed: the empty Idempotency-Key header was read as no key, "
                + "and the call ran: %s", body)
            .isEqualTo("ARGUMENT_INVALID");
        assertThat(refused.jsonPath().getList("data.offenders", String.class)).as(body)
            .containsExactly("idempotency_key");
    }

    /**
     * A key older than 24 hours counts as absent, and a caller's expired keys
     * are removed when that caller next writes a key, with no background job.
     * Another caller's expired key stays until that caller writes one.
     */
    @Test
    void a_callers_expired_keys_are_removed_when_it_next_writes_a_key() {
        Mcp.answer(Mcp.call("dispatch_create", createUnder("expire-mine", "mine, expired")));
        SurfaceFixture.asOtherConsole(identity);
        Mcp.answer(Mcp.call("dispatch_create", createUnder("expire-theirs", "theirs, expired")));
        SurfaceFixture.asConsole(identity);
        age("expire-mine", "24 hours 1 minute");
        age("expire-theirs", "24 hours 1 minute");
        assertThat(rowsOf("expire-mine")).as("nothing removes a key by time alone").isEqualTo(1);

        Mcp.answer(Mcp.call("dispatch_create", createUnder("expire-next", "the next key")));

        assertThat(rowsOf("expire-mine"))
            .as("RED STATE, observed: the expired key stayed in the ledger until the same key "
                + "came again")
            .isZero();
        assertThat(rowsOf("expire-theirs")).as("another caller's expired key stays").isEqualTo(1);
        assertThat(rowsOf("expire-next")).isEqualTo(1);
    }

    // -----------------------------------------------------------------------

    /** A create in the probe scope under a key. */
    private static Map<String, Object> createUnder(String key, String title) {
        return Map.of("scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
            "idempotency_key", key, "fields", Map.of("title", title, "apparatus", "code"));
    }

    /** The rows the ledger holds under a key, across callers and tenants. */
    private static long rowsOf(String key) {
        var config = org.eclipse.microprofile.config.ConfigProvider.getConfig();
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                config.getValue("test.db.url", String.class),
                config.getValue("test.db.admin.username", String.class),
                config.getValue("test.db.admin.password", String.class));
             java.sql.PreparedStatement s = c.prepareStatement(
                 "SELECT count(*) FROM dispatch.task_idempotency_key WHERE idempotency_key = ?")) {
            s.setString(1, key);
            try (java.sql.ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

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
