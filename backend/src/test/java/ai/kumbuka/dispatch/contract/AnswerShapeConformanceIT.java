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
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every answer about an object, and every page, has the shape the contract
 * fixes (C6.1 to C6.4, C9.2), on both surfaces.
 *
 * <ul>
 *   <li>An answer about one object carries {@code address}, {@code fields},
 *       {@code conflict_token} and {@code next} as {@code [{call, does}]},
 *       and {@code waiting_for} where {@code next} is empty.</li>
 *   <li>{@code next} names calls as the surface that answers names them.</li>
 *   <li>A claim adds {@code receipt}.</li>
 *   <li>Every address an answer carries is complete, and {@code read} takes
 *       it unchanged.</li>
 *   <li>A page is exactly {@code {objects, next_cursor}}.</li>
 * </ul>
 *
 * <p>The member names are written here from the contract; the answers are
 * read off the wire.
 *
 * <p>RED STATE, observed 2026-10-16 against the tree of the guards' commit, with
 * this class copied in: the refusal of a stale conflict token carried no
 * address, so the addresses an answer carries were one short.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class AnswerShapeConformanceIT {

    private static final List<String> OBJECT = List.of("address", "fields", "conflict_token",
        "next");

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    void an_answer_about_one_object_carries_the_contract_s_members_on_both_surfaces() {
        String id = SurfaceFixture.open("shaped", "code");
        String address = SurfaceFixture.address(id);

        Map<String, Object> overMcp = Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", address)));
        Map<String, Object> overRest = given().get(SurfaceFixture.item(id)).jsonPath().getMap("$");

        for (Map<String, Object> answer : List.of(overMcp, overRest)) {
            assertObject(answer);
            assertThat(answer.get("address")).isEqualTo(address);
        }
        assertThat(Mcp.nextCalls(overMcp)).as("MCP names its tools (C6.2)")
            .allSatisfy(call -> assertThat(call).startsWith("dispatch_"));
        assertThat(Mcp.nextCalls(overRest)).as("REST names its verbs (C6.2)")
            .allSatisfy(call -> assertThat(call).doesNotStartWith("dispatch_"));
    }

    @Test
    void a_claim_adds_its_receipt_and_an_answer_with_nothing_open_says_what_it_waits_for() {
        String address = SurfaceFixture.address(SurfaceFixture.open("claimed", "code"));
        SurfaceFixture.asExecutor(identity);
        Map<String, Object> claimed = Mcp.answer(Mcp.call("dispatch_claim",
            Map.of("address", address)));
        assertObject(claimed);
        assertThat(claimed).containsKey("receipt");
        assertThat(String.valueOf(claimed.get("receipt"))).isNotBlank();

        SurfaceFixture.asOtherExecutor(identity);
        Map<String, Object> held = Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", address)));
        assertThat((List<?>) held.get("next")).as("nothing is open to another executor").isEmpty();
        assertThat(held).as("so the answer says what the task waits for (C6.1)")
            .containsKey("waiting_for");
        assertThat(held).doesNotContainKey("receipt");
    }

    @Test
    void a_page_is_exactly_its_objects_and_its_cursor() {
        String both = SurfaceFixture.address(SurfaceFixture.open("one", "code")) + ","
            + SurfaceFixture.address(SurfaceFixture.open("two", "code"));
        Map<String, Object> first = Mcp.answer(Mcp.call("dispatch_query", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
            "address", both, "page_size", 1)));
        assertThat(first).as("C9.2").containsOnlyKeys("objects", "next_cursor");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> objects = (List<Map<String, Object>>) first.get("objects");
        objects.forEach(AnswerShapeConformanceIT::assertObject);

        Response last = given().queryParam("address", both).queryParam("page_size", 2)
            .get(SurfaceFixture.collection());
        assertThat(last.jsonPath().getMap("$")).as("the last page carries no cursor")
            .containsOnlyKeys("objects");
    }

    @Test
    void every_address_an_answer_carries_is_taken_by_read_unchanged() {
        String root = SurfaceFixture.address(SurfaceFixture.open("a root", "code"));
        Mcp.answer(Mcp.call("dispatch_create", Map.of("scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR, "parent", root,
            "fields", Map.of("title", "a child", "apparatus", "code"))));
        Map<String, Object> refusal = Mcp.refusal(Mcp.call("dispatch_withdraw", Map.of(
            "address", root, "conflict_token", token(root))));
        Map<String, Object> stale = Mcp.refusal(Mcp.call("dispatch_withdraw", Map.of(
            "address", root, "conflict_token", "stale")));

        List<String> addresses = new ArrayList<>();
        addresses.add(root);
        addresses.addAll(addressesIn(refusal));
        addresses.addAll(addressesIn(stale));
        assertThat(addresses).as("the root, its child, and the stale token's task")
            .hasSizeGreaterThanOrEqualTo(3);
        for (String address : addresses) {
            assertThat(address).as("complete (C6.4)").startsWith("dispatch://");
            assertThat(Mcp.call("dispatch_read", Map.of("address", address))
                .jsonPath().getBoolean("result.isError"))
                .as("read takes %s unchanged", address).isFalse();
        }
        SurfaceFixture.asExecutor(identity);
        Response overRest = given().contentType(ContentType.JSON)
            .post(NotFoundIdentityIT.path(root) + ":release");
        assertThat(overRest.jsonPath().getString("data.attempted")).isEqualTo("release");
    }

    /** The members of an answer about one object (C6.1). */
    @SuppressWarnings("unchecked")
    static void assertObject(Map<String, Object> answer) {
        assertThat(answer).as("C6.1").containsKeys(OBJECT.toArray(String[]::new));
        assertThat(answer.get("fields")).isInstanceOf(Map.class);
        for (Map<String, Object> step : (List<Map<String, Object>>) answer.get("next")) {
            assertThat(step).as("a step of next is {call, does}").containsOnlyKeys("call", "does");
        }
        if (((List<?>) answer.get("next")).isEmpty()) {
            assertThat(answer).as("where next is empty, waiting_for says why")
                .containsKey("waiting_for");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> addressesIn(Map<String, Object> refusal) {
        Map<String, Object> data = (Map<String, Object>) refusal.get("data");
        List<String> found = new ArrayList<>();
        if (data.get("address") != null) {
            found.add(String.valueOf(data.get("address")));
        }
        if (data.get("children") instanceof List<?> children) {
            for (Object child : children) {
                found.add(String.valueOf(child));
            }
        }
        return found;
    }

    private static String token(String address) {
        return String.valueOf(Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", address))).get("conflict_token"));
    }
}
