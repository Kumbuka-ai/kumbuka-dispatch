/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.domain.DefinitionFixture;
import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.surface.InstanceWire;
import ai.kumbuka.dispatch.surface.TaskWire;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The idempotency key of {@code create}, on the surface (C10.4, REA-0010 D30,
 * data model "Idempotency keys").
 *
 * <p>The key travels as the transport of each surface carries it: on MCP the
 * top-level argument {@code idempotency_key}, on REST the header {@code
 * Idempotency-Key} and nowhere else. A REST request that carries the key in
 * its body or its query is refused as {@code ARGUMENT_UNKNOWN}, naming what
 * the call accepts there and not the key. A key belongs to its caller, is 1 to
 * 255 printable ASCII characters, and holds for 24 hours from its first use.
 *
 * <p>A repeat with the same key and the same arguments answers the task the
 * first created and writes nothing, also after its send; with other arguments,
 * or once the draft is deleted, it is refused with {@code
 * IDEMPOTENCY_KEY_REUSED} and writes nothing. A key older than 24 hours counts
 * as absent; the expired keys of a caller are deleted when that caller next
 * writes a key, and the same key is then spent anew. {@code claim} and {@code
 * annotate} take a key with the orders that bind them.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class IdempotencyIT {

    /** The header that carries the key on REST. */
    private static final String KEY = "Idempotency-Key";
    private static final String KEY_ARGUMENT = "idempotency_key";
    private static final String REUSED = "IDEMPOTENCY_KEY_REUSED";

    @Inject TestIdentityAssociation identity;

    private TaskWire.Planted planted;
    private String instance;

    @BeforeEach
    void stage() {
        PlatformFixture.grantDirectoryAccess();
        InstanceWire.as(identity, InstanceWire.CONSOLE);
        planted = TaskWire.plant("keys", "review");
        instance = TaskWire.started(planted.selector());
    }

    @Test
    void a_repeated_create_creates_nothing_and_answers_the_first_task() {
        Map<String, Object> create = createUnder(fresh("create-once"), "created once");
        String first = address(InstanceWire.answer(InstanceWire.mcp(TaskWire.CREATE, create)));
        Map<String, Long> before = DefinitionFixture.rowCounts();

        String again = address(InstanceWire.answer(InstanceWire.mcp(TaskWire.CREATE, create)));

        assertThat(again).isEqualTo(first);
        assertThat(DefinitionFixture.rowCounts()).as("the repeat wrote nothing").isEqualTo(before);
    }

    @Test
    void a_repeat_with_other_arguments_is_refused_and_creates_nothing() {
        String key = fresh("other-arguments");
        InstanceWire.answer(InstanceWire.mcp(TaskWire.CREATE, createUnder(key, "the first")));
        Map<String, Long> before = DefinitionFixture.rowCounts();

        Response again = InstanceWire.mcp(TaskWire.CREATE, createUnder(key, "zz-another-title"));

        assertThat(InstanceWire.refusal(again).get("reason")).isEqualTo(REUSED);
        assertThat(again.asString()).as("the refusal repeats nothing of the call")
            .doesNotContain("zz-another-title").doesNotContain(key);
        assertThat(DefinitionFixture.rowCounts()).as("and it wrote nothing").isEqualTo(before);
        assertThat(InstanceWire.mcp(TaskWire.CREATE, createUnder(fresh("other-key"),
                "zz-another-title")).jsonPath().getBoolean("result.isError"))
            .as("RED STATE, observed: the same arguments under another key are taken")
            .isFalse();
    }

    @Test
    void a_repeat_after_the_draft_was_deleted_is_refused_and_creates_nothing() {
        Map<String, Object> create = createUnder(fresh("deleted"), "deleted, then repeated");
        Map<String, Object> first = InstanceWire.answer(InstanceWire.mcp(TaskWire.CREATE, create));
        InstanceWire.answer(InstanceWire.mcp(TaskWire.DELETE, TaskWire.on(first, Map.of())));
        Map<String, Long> before = DefinitionFixture.rowCounts();

        Response again = InstanceWire.mcp(TaskWire.CREATE, create);

        assertThat(InstanceWire.refusal(again).get("reason"))
            .as("RED STATE, observed in the old kernel: deleting the draft deleted the key with "
                + "it, and the repeat created a second draft as if it were the first call")
            .isEqualTo(REUSED);
        assertThat(DefinitionFixture.rowCounts()).as("the refused repeat wrote nothing")
            .isEqualTo(before);
    }

    @Test
    void a_repeat_after_the_send_answers_the_task_sent() {
        Map<String, Object> create = createUnder(fresh("sent"), "sent, then repeated");
        Map<String, Object> first = InstanceWire.answer(InstanceWire.mcp(TaskWire.CREATE, create));
        Map<String, Object> sent = InstanceWire.answer(InstanceWire.mcp(TaskWire.SEND,
            TaskWire.on(first, Map.of())));
        Map<String, Long> before = DefinitionFixture.rowCounts();

        Map<String, Object> again = InstanceWire.answer(InstanceWire.mcp(TaskWire.CREATE, create));

        assertThat(address(again)).isEqualTo(address(first));
        assertThat(field(again, "state")).as("the task as it stands, sent").isEqualTo("open");
        assertThat(again.get(InstanceWire.TOKEN)).as("under the token of its last act")
            .isEqualTo(sent.get(InstanceWire.TOKEN));
        assertThat(DefinitionFixture.rowCounts()).as("and the repeat wrote nothing")
            .isEqualTo(before);
    }

    // =======================================================================
    // REST: the header, and nowhere else
    // =======================================================================

    @Test
    void a_repeated_create_over_rest_under_the_header_answers_the_first_task() {
        String key = fresh("rest-once");
        Response first = restCreate(key, "created once over rest");
        Map<String, Long> before = DefinitionFixture.rowCounts();

        Response again = restCreate(key, "created once over rest");

        assertThat(first.statusCode()).as(first.asString()).isEqualTo(201);
        assertThat(again.jsonPath().getString("address"))
            .isEqualTo(first.jsonPath().getString("address"));
        assertThat(DefinitionFixture.rowCounts()).as("the repeat wrote nothing").isEqualTo(before);
    }

    @Test
    void the_key_in_a_rest_body_or_query_is_an_argument_unknown_there() {
        Map<String, Object> body = new LinkedHashMap<>(restBody("keyed in the body"));
        body.put(KEY_ARGUMENT, "zz-in-the-body");
        assertUnknownWithoutTheKey(given().contentType(ContentType.JSON).body(body)
            .post(collection()));

        assertUnknownWithoutTheKey(given().contentType(ContentType.JSON)
            .queryParam(KEY_ARGUMENT, "zz-in-the-query").body(restBody("keyed in the query"))
            .post(collection()));

        assertThat(given().contentType(ContentType.JSON).header(KEY, fresh("in-the-header"))
                .body(restBody("keyed in the header")).post(collection()).statusCode())
            .as("RED STATE, observed: the same key in the header is taken").isEqualTo(201);
    }

    // =======================================================================
    // Twenty-four hours
    // =======================================================================

    @Test
    void the_ledger_holds_a_key_for_twenty_four_hours_and_then_spends_it_anew() {
        String key = fresh("a-day");
        Response first = restCreate(key, "kept a day");
        age(key, "23 hours 59 minutes");
        Response withinTheDay = restCreate(key, "kept a day");
        assertThat(withinTheDay.jsonPath().getString("address"))
            .as("a key spent less than a day ago still answers the first call")
            .isEqualTo(first.jsonPath().getString("address"));

        age(key, "24 hours 1 minute");
        Response pastTheDay = restCreate(key, "kept a day");

        assertThat(pastTheDay.statusCode()).as(pastTheDay.asString()).isEqualTo(201);
        assertThat(pastTheDay.jsonPath().getString("address"))
            .as("a key spent more than a day ago is spent anew: the same key, usable again")
            .isNotEqualTo(first.jsonPath().getString("address"));
        assertThat(rowsOf(key)).as("and the ledger holds it once, for the new task").isEqualTo(1);
    }

    /**
     * A key older than 24 hours counts as absent, and a caller's expired keys
     * are deleted when that caller next writes a key, with no background job.
     * Another caller's expired key stays until that caller writes one.
     */
    @Test
    void a_callers_expired_keys_are_deleted_when_it_next_writes_a_key() {
        String mine = fresh("expire-mine");
        String theirs = fresh("expire-theirs");
        InstanceWire.answer(InstanceWire.mcp(TaskWire.CREATE, createUnder(mine, "mine, expired")));
        PlatformFixture.run("INSERT INTO dispatch.idempotency_key (tenant_id, scope_id, "
            + "caller_subject, idempotency_key, argument_digest, created_at) VALUES ('"
            + InstanceWire.TENANT + "', '" + InstanceWire.SCOPE + "', 'someone-else', '" + theirs
            + "', 'a digest', now())");
        age(mine, "24 hours 1 minute");
        age(theirs, "24 hours 1 minute");
        assertThat(rowsOf(mine))
            .as("RED STATE, observed: nothing removes a key by time alone").isEqualTo(1);

        String next = fresh("expire-next");
        InstanceWire.answer(InstanceWire.mcp(TaskWire.CREATE, createUnder(next, "the next key")));

        assertThat(rowsOf(mine)).as("the caller's expired key is gone").isZero();
        assertThat(rowsOf(theirs)).as("another caller's expired key stays").isEqualTo(1);
        assertThat(rowsOf(next)).isEqualTo(1);
    }

    // =======================================================================
    // The form of a key
    // =======================================================================

    @Test
    void a_key_outside_one_to_255_printable_ascii_characters_is_argument_invalid() {
        for (String key : List.of("", "k".repeat(256), "zzschlüssel", "zz\u0007bell",
                "zz\ttab")) {
            Map<String, Long> before = DefinitionFixture.rowCounts();
            Response refused = InstanceWire.mcp(TaskWire.CREATE,
                createUnder(key, "a key outside its form"));
            Map<String, Object> envelope = InstanceWire.refusal(refused);
            assertThat(envelope.get("reason"))
                .as("a key of %d characters outside the form", key.length())
                .isEqualTo("ARGUMENT_INVALID");
            assertThat(InstanceWire.data(envelope).get("offenders"))
                .isEqualTo(List.of(KEY_ARGUMENT));
            assertThat(refused.asString()).as("the refusal repeats no key")
                .doesNotContain("zzschl").doesNotContain("zz\\u0007").doesNotContain("kkkkkkkk");
            assertThat(DefinitionFixture.rowCounts()).as("and it wrote nothing").isEqualTo(before);
        }

        Response overRest = restCreate("k".repeat(256), "a long key over rest");
        assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(400);
        assertThat(overRest.jsonPath().getString("reason")).isEqualTo("ARGUMENT_INVALID");

        assertThat(InstanceWire.mcp(TaskWire.CREATE, createUnder("~".repeat(254)
                + InstanceWire.suffix().charAt(0), "a key of 255 characters")).jsonPath()
                .getBoolean("result.isError"))
            .as("RED STATE, observed: a key of 255 printable characters is taken").isFalse();
        assertThat(InstanceWire.mcp(TaskWire.CREATE, createUnder(" !" + InstanceWire.suffix(),
                "a key of a space and a mark")).jsonPath().getBoolean("result.isError"))
            .as("and so is one of a space and a mark").isFalse();
    }

    /**
     * An empty key is a key outside its form on both surfaces: the empty
     * {@code Idempotency-Key} header on REST and the empty string as {@code
     * idempotency_key} on MCP are each refused as {@code ARGUMENT_INVALID}
     * naming {@code idempotency_key}, and the call is not carried out (C10.4).
     * Read as no key, the call would run without the idempotency its caller
     * asked for.
     */
    @Test
    void an_empty_key_is_argument_invalid_on_both_surfaces() {
        Map<String, Long> before = DefinitionFixture.rowCounts();

        Response overRest = restCreate("", "an empty key");
        assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(400);
        assertThat(overRest.jsonPath().getString("reason"))
            .as("RED STATE, observed in the old kernel: the empty header was read as no key, "
                + "and the create ran")
            .isEqualTo("ARGUMENT_INVALID");
        assertThat(overRest.jsonPath().getList("data.offenders", String.class))
            .containsExactly(KEY_ARGUMENT);

        Response overMcp = InstanceWire.mcp(TaskWire.CREATE, createUnder("", "an empty key"));
        assertThat(InstanceWire.refusal(overMcp).get("reason")).isEqualTo("ARGUMENT_INVALID");
        assertThat(DefinitionFixture.rowCounts()).as("no refused call was carried out")
            .isEqualTo(before);
    }

    @Test
    @Disabled("rebuilt in T5: a repeated claim answers the same task and a new receipt, on both "
        + "surfaces, and an empty key on claim is refused")
    void a_repeated_claim_answers_the_same_task_and_a_new_receipt() {
        fail("rebuilt in T5");
    }

    @Test
    @Disabled("rebuilt in T11: a repeated addendum attaches nothing second on either surface, "
        + "and an empty key on annotate is refused")
    void a_repeated_addendum_attaches_nothing_second_on_either_surface() {
        fail("rebuilt in T11");
    }

    // -----------------------------------------------------------------------

    /** A key of this test alone, so that no two runs share one. */
    private static String fresh(String prefix) {
        return prefix + "-" + InstanceWire.suffix();
    }

    /** A create in the planted instance under a key, on the assistant surface. */
    private Map<String, Object> createUnder(String key, String title) {
        Map<String, Object> arguments = TaskWire.create(planted.selector(), instance,
            Map.of("title", title));
        arguments.put(KEY_ARGUMENT, key);
        return arguments;
    }

    private Map<String, Object> restBody(String title) {
        return Map.of("parent", instance, InstanceWire.FIELDS, Map.of("title", title));
    }

    private Response restCreate(String key, String title) {
        return given().contentType(ContentType.JSON).header(KEY, key).body(restBody(title))
            .post(collection());
    }

    private String collection() {
        return "/api/" + InstanceWire.SLUG + "/" + planted.selector();
    }

    private static String address(Map<String, Object> answer) {
        return String.valueOf(answer.get(InstanceWire.ADDRESS));
    }

    @SuppressWarnings("unchecked")
    private static Object field(Map<String, Object> answer, String name) {
        return ((Map<String, Object>) answer.get(InstanceWire.FIELDS)).get(name);
    }

    /** The rows the ledger holds under a key, across callers and tenants. */
    private static long rowsOf(String key) {
        return InstanceWire.single("SELECT count(*) FROM dispatch.idempotency_key "
            + "WHERE idempotency_key = ?", key);
    }

    private static void assertUnknownWithoutTheKey(Response refused) {
        assertThat(refused.statusCode()).as(refused.asString()).isEqualTo(400);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(refused.jsonPath().getList("data.accepts", String.class))
            .as("what the call accepts in the body, which the key is not")
            .isNotEmpty()
            .doesNotContain(KEY_ARGUMENT);
        assertThat(refused.asString()).doesNotContain("zz-in-the");
    }

    /** Moves the first use of a spent key into the past, as the clock would. */
    private static void age(String key, String by) {
        PlatformFixture.run("UPDATE dispatch.idempotency_key SET created_at = now() - interval '"
            + by + "' WHERE idempotency_key = '" + key + "'");
    }
}
