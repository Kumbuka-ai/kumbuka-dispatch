/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.platform.PlatformFixture;
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

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bracket rule and the rule on an addendum after delivery (TAR-0004
 * sections 2 and 3, concept section 2.5), on both surfaces.
 *
 * <p>A child is created only under a root that is neither in {@code draft}
 * nor {@code closed}: under a draft root {@code ROOT_UNSENT} 409, under a
 * closed one {@code ROOT_CLOSED} 409, each with {@code attempted} and nothing
 * more. A draft root under which any task stands is not deleted: {@code
 * CHILDREN_PRESENT} 409 with {@code attempted} and the complete address of
 * each child, and nothing is deleted. No call makes that bracket any more, so
 * the case plants the child directly in the database. A task in {@code
 * delivered} takes no addendum to its commission: {@code TRANSITION_REFUSED}
 * 409 whose {@code next} names {@code rework}; in {@code active} it still
 * does. The codes, statuses and members are written here from the target, not
 * read from the catalogue.
 *
 * <p>RED STATE, observed against the kernel before the rule: a child was
 * created under the draft root, the creation under the closed root was
 * refused as {@code TRANSITION_REFUSED}, the draft root was deleted with its
 * child left behind, and the addendum to the delivered task was attached.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class BracketRuleIT {

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    void a_child_under_a_draft_root_is_root_unsent_409() {
        String root = SurfaceFixture.address(SurfaceFixture.idOf(SurfaceFixture.create(Map.of(
            "title", "an unsent root", "apparatus", "code"))));

        Response overMcp = Mcp.call("dispatch_create", child(root));
        assertRefusal(Mcp.refusal(overMcp), "ROOT_UNSENT", List.of("attempted"));
        Response overRest = given().contentType(ContentType.JSON).body(child(root))
            .post(SurfaceFixture.collection());
        assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(409);
        assertRefusal(overRest.jsonPath().getMap("$"), "ROOT_UNSENT", List.of("attempted"));
        assertThat(childrenOf(root)).as("no child was created").isZero();
    }

    @Test
    void a_child_under_a_closed_root_is_root_closed_409() {
        String root = SurfaceFixture.address(SurfaceFixture.open("a closed root", "code"));
        Mcp.answer(Mcp.call("dispatch_withdraw", Map.of("address", root,
            "conflict_token", token(root))));

        assertRefusal(Mcp.refusal(Mcp.call("dispatch_create", child(root))), "ROOT_CLOSED",
            List.of("attempted"));
        Response overRest = given().contentType(ContentType.JSON).body(child(root))
            .post(SurfaceFixture.collection());
        assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(409);
        assertRefusal(overRest.jsonPath().getMap("$"), "ROOT_CLOSED", List.of("attempted"));
        assertThat(childrenOf(root)).as("no child was created").isZero();
    }

    @Test
    void a_draft_root_with_a_child_is_children_present_409_and_nothing_is_deleted() {
        String id = SurfaceFixture.idOf(SurfaceFixture.create(Map.of(
            "title", "a draft root", "apparatus", "code")));
        String root = SurfaceFixture.address(id);
        String child = plantChild(root);

        Map<String, Object> overMcp = Mcp.refusal(Mcp.call("dispatch_delete",
            Map.of("address", root, "conflict_token", token(root))));
        assertRefusal(overMcp, "CHILDREN_PRESENT", List.of("attempted", "children"));
        assertThat(data(overMcp).get("children")).as("complete addresses, each of them")
            .isEqualTo(List.of(child));

        Response overRest = given().header("If-Match", "\"" + token(root) + "\"")
            .delete(SurfaceFixture.item(id));
        assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(409);
        assertRefusal(overRest.jsonPath().getMap("$"), "CHILDREN_PRESENT",
            List.of("attempted", "children"));
        assertThat(overRest.jsonPath().getList("data.children", String.class))
            .containsExactly(child);

        assertThat(Mcp.field(Mcp.answer(Mcp.call("dispatch_read", Map.of("address", root))),
            "state")).as("the root stands").isEqualTo("draft");
        assertThat(childrenOf(root)).as("nothing was deleted along").isEqualTo(1);
    }

    @Test
    void a_delivered_task_takes_no_addendum_to_its_commission_and_names_rework() {
        String address = SurfaceFixture.address(SurfaceFixture.open("delivered", "code"));
        Map<String, Object> addendum = Map.of("address", address,
            "fields", Map.of("part", "dispatch", "text", "one more thing"));
        SurfaceFixture.asExecutor(identity);
        String receipt = String.valueOf(Mcp.answer(Mcp.call("dispatch_claim",
            Map.of("address", address))).get("receipt"));
        SurfaceFixture.asConsole(identity);
        Mcp.answer(Mcp.call("dispatch_annotate", addendum));

        SurfaceFixture.asExecutor(identity);
        Mcp.answer(Mcp.call("dispatch_deliver", Map.of("address", address, "receipt", receipt,
            "fields", Map.of("text", "the answer"))));
        SurfaceFixture.asConsole(identity);

        Map<String, Object> overMcp = Mcp.refusal(Mcp.call("dispatch_annotate", Map.of(
            "address", address, "fields", Map.of("part", "dispatch", "text", "too late"))));
        assertThat(overMcp.get("reason")).isEqualTo("TRANSITION_REFUSED");
        assertThat(data(overMcp).get("state")).isEqualTo("delivered");
        assertThat(Mcp.nextCalls(Map.of("next", data(overMcp).get("next"))))
            .as("rework takes the addendum's place").contains("dispatch_rework");

        Response overRest = given().contentType(ContentType.JSON)
            .body(Map.of("fields", Map.of("part", "dispatch", "text", "too late")))
            .post(SurfaceFixture.item(address.substring(address.lastIndexOf('/') + 1))
                + ":annotate");
        assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(409);
        assertThat(overRest.jsonPath().getString("reason")).isEqualTo("TRANSITION_REFUSED");
        assertThat(overRest.jsonPath().getList("data.next.call", String.class))
            .contains("rework");
        assertThat(Mcp.answer(Mcp.call("dispatch_read_text", Map.of("address", address,
                "part", "addenda"))).toString())
            .as("the addendum made while active stands, and only it")
            .contains("one more thing").doesNotContain("too late");
    }

    // -----------------------------------------------------------------------

    private static Map<String, Object> child(String root) {
        return Map.of("scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
            "parent", root, "fields", Map.of("title", "a child", "apparatus", "code"));
    }

    /** The task's current conflict token, as its read hands it out. */
    private static String token(String address) {
        return String.valueOf(Mcp.answer(Mcp.call("dispatch_read", Map.of("address", address)))
            .get("conflict_token"));
    }

    private static String identityOf(String address) {
        return Mcp.field(Mcp.answer(Mcp.call("dispatch_read", Map.of("address", address))),
            "identity").substring("dispatch://".length());
    }

    /**
     * Writes a child under a root as the administrator: the bracket no call
     * makes any more. Answers the child's complete address.
     */
    private static String plantChild(String root) {
        PlatformFixture.run("INSERT INTO dispatch.task (tenant_id, scope_id, selector_id, number, "
            + "sub, title, apparatus, updated_at) SELECT tenant_id, scope_id, selector_id, number, "
            + "1, 'a planted child', 'code', now() FROM dispatch.task WHERE uuid = '"
            + identityOf(root) + "'");
        return root.substring(0, root.length() - ".0".length()) + ".1";
    }

    private static int childrenOf(String root) {
        String number = root.substring(root.lastIndexOf('/') + 1, root.lastIndexOf('.'));
        Response listed = given().queryParam("bracket", number).get(SurfaceFixture.collection());
        return (int) listed.jsonPath().getList("objects.address", String.class).stream()
            .filter(a -> !a.equals(root)).count();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> refusal) {
        return (Map<String, Object>) refusal.get("data");
    }

    private static void assertRefusal(Map<String, Object> refusal, String code,
                                      List<String> members) {
        assertThat(refusal.get("reason")).as(String.valueOf(refusal)).isEqualTo(code);
        assertThat(data(refusal)).as("%s carries its members and no other", code)
            .containsOnlyKeys(members);
        assertThat(data(refusal).get("attempted")).as("the call, as the surface names it")
            .isNotNull();
    }
}
