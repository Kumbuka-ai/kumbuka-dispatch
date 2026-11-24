/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.surface.InstanceWire;
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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule of the instance, rebuilt from the bracket rule of the old kernel
 * (TAR-0004 2.3, REA-0010 S5): a closed instance takes no further act. Each of
 * the four calls on its address -- {@code conclude}, {@code withdraw}, {@code
 * reference_attach} and {@code reference_detach} -- is refused on both
 * surfaces as {@code TRANSITION_REFUSED} 409 with {@code attempted}, the state
 * {@code closed} and no call open, and writes nothing. The codes, statuses and
 * members are written here from the contract (C8.7), not read from the
 * catalogue.
 *
 * <p>The cases of the old bracket that a later order rebuilds stand below,
 * deactivated with the order that rebuilds them: a task created under or
 * deleted from an instance belongs to {@code create} and {@code delete} (T4),
 * the addendum after delivery to {@code annotate} (T11). Their bodies are the
 * old kernel's, kept for that order.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class BracketRuleIT {

    private static final String T4 = "rebuilt in T4: a task is created and deleted only under "
        + "an instance whose state admits it";
    private static final String T11 = "rebuilt in T11: a delivered task takes no addendum to its "
        + "commission and names rework";

    private static final String PREFIX = "dispatch_";
    private static final String START = "start";
    private static final String CONCLUDE = "conclude";
    private static final String ATTACH = "reference_attach";
    private static final String CLOSED = "closed";
    private static final String ACTS = "SELECT count(*) FROM dispatch.act WHERE instance_id = ?";

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        PlatformFixture.grantDirectoryAccess();
        InstanceWire.as(identity, InstanceWire.CONSOLE);
    }

    @Test
    void a_closed_instance_refuses_each_call_on_its_address_on_both_surfaces() {
        Map<String, Object> started = InstanceWire.answer(InstanceWire.mcp(PREFIX + START,
            InstanceWire.startArguments(InstanceWire.plant(CLOSED).selector(),
                InstanceWire.minimalFields("an instance to close"))));
        String address = String.valueOf(started.get(InstanceWire.ADDRESS));
        Map<String, Object> closing = Map.of("text", "done");
        Map<String, Object> reference = Map.of("kind", "item", "value", "item-1");
        String current = InstanceWire.token(InstanceWire.answer(InstanceWire.mcp(
            PREFIX + ATTACH, Map.of(InstanceWire.ADDRESS, address, InstanceWire.FIELDS,
                reference))));

        assertThat(InstanceWire.answer(InstanceWire.mcp(PREFIX + CONCLUDE, Map.of(
                InstanceWire.ADDRESS, address, InstanceWire.TOKEN, current, InstanceWire.FIELDS,
                closing))).get(InstanceWire.FIELDS))
            .as("RED STATE, observed: the instance runs, and its first conclude closes it")
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry(InstanceWire.STATE, CLOSED);
        long acts = InstanceWire.single(ACTS, InstanceWire.instanceId(address));

        Map<String, Map<String, Object>> calls = new LinkedHashMap<>();
        calls.put(CONCLUDE, Map.of(InstanceWire.TOKEN, current, InstanceWire.FIELDS, closing));
        calls.put("withdraw", Map.of(InstanceWire.TOKEN, current, InstanceWire.FIELDS, closing));
        calls.put(ATTACH, Map.of(InstanceWire.FIELDS, Map.of("kind", "item", "value", "x")));
        calls.put("reference_detach", Map.of(InstanceWire.FIELDS, reference));
        for (Map.Entry<String, Map<String, Object>> call : calls.entrySet()) {
            Map<String, Object> arguments = new LinkedHashMap<>(call.getValue());
            arguments.put(InstanceWire.ADDRESS, address);
            Map<String, Object> overMcp = InstanceWire.refusal(InstanceWire.mcp(
                PREFIX + call.getKey(), arguments));
            assertClosed(overMcp, PREFIX + call.getKey());

            Map<String, Object> body = new LinkedHashMap<>(call.getValue());
            Object fence = body.remove(InstanceWire.TOKEN);
            Response overRest = InstanceWire.rest(address, call.getKey(), body,
                fence == null ? null : String.valueOf(fence));
            assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(409);
            assertClosed(InstanceWire.object(overRest, "$"), call.getKey());
        }
        assertThat(InstanceWire.single(ACTS, InstanceWire.instanceId(address)))
            .as("and nothing was written").isEqualTo(acts);
    }

    private static void assertClosed(Map<String, Object> refusal, String call) {
        assertThat(refusal.get(InstanceWire.REASON)).as(String.valueOf(refusal))
            .isEqualTo(InstanceWire.TRANSITION_REFUSED);
        assertThat(InstanceWire.data(refusal)).as("the call, the state, and no call open")
            .containsOnlyKeys(InstanceWire.ATTEMPTED, InstanceWire.STATE, InstanceWire.NEXT)
            .containsEntry(InstanceWire.ATTEMPTED, call)
            .containsEntry(InstanceWire.STATE, CLOSED)
            .containsEntry(InstanceWire.NEXT, List.of());
    }

    @Test
    @Disabled(T4)
    void a_child_under_a_draft_root_is_root_unsent_409() {
        String root = SurfaceFixture.address(SurfaceFixture.idOf(SurfaceFixture.create(Map.of(
            "title", "an unsent root", "apparatus", "code"))));

        Response overMcp = Mcp.call("dispatch_create", child(root));
        assertRefusal(Mcp.refusal(overMcp), "ROOT_UNSENT", List.of("attempted"));
        Response overRest = given().contentType(ContentType.JSON).body(childOverRest(root))
            .post(SurfaceFixture.collection());
        assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(409);
        assertRefusal(overRest.jsonPath().getMap("$"), "ROOT_UNSENT", List.of("attempted"));
        assertThat(childrenOf(root)).as("no child was created").isZero();
    }

    @Test
    @Disabled(T4)
    void a_child_under_a_closed_root_is_root_closed_409() {
        String root = SurfaceFixture.address(SurfaceFixture.open("a closed root", "code"));
        Mcp.answer(Mcp.call("dispatch_withdraw", Map.of("address", root,
            "conflict_token", token(root))));

        assertRefusal(Mcp.refusal(Mcp.call("dispatch_create", child(root))), "ROOT_CLOSED",
            List.of("attempted"));
        Response overRest = given().contentType(ContentType.JSON).body(childOverRest(root))
            .post(SurfaceFixture.collection());
        assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(409);
        assertRefusal(overRest.jsonPath().getMap("$"), "ROOT_CLOSED", List.of("attempted"));
        assertThat(childrenOf(root)).as("no child was created").isZero();
    }

    @Test
    @Disabled(T4)
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
    @Disabled(T11)
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

    /** The same child over REST, whose path names the scope and the bracket kind. */
    private static Map<String, Object> childOverRest(String root) {
        return Map.of("parent", root, "fields", Map.of("title", "a child", "apparatus", "code"));
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
