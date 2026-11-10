/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tags of a task over the assistant surface (sprint/210, tag system v1):
 * {@code dispatch_tag_attach}, {@code dispatch_tag_detach}, the field {@code
 * tags} of a read and a listing, and the filter {@code tag}.
 *
 * <p>The vocabulary is staged in {@code platform.tag} as the platform declares
 * it, and the service reads it through {@code platform.tag_access} only. Every
 * token is fresh per test, so no test reads what another declared or attached.
 *
 * <p>A tag is a relation of the task and no field of it: the tests hold the
 * conflict token and the change stamp of the row to that, and show the calls
 * open in a sent and a closed task, where no other write reaches.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskTagIT {

    private static final String ATTACH_CALL = "dispatch_tag_attach";
    private static final String DETACH_CALL = "dispatch_tag_detach";
    private static final String TAGS_FIELD = "tags";

    @Inject TestIdentityAssociation identity;

    /** A fresh prefix per test, so every token this test declares is its own. */
    private String run;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
        run = "it" + UUID.randomUUID().toString().substring(0, 8);
    }

    // =======================================================================
    // Attach, detach, read
    // =======================================================================

    @Test
    void attach_answers_the_address_and_every_tag_and_read_lists_them_in_order() {
        String task = SurfaceFixture.address(SurfaceFixture.open("tagged", "code"));
        String router = declare("component:router");
        String alpha = declare("alpha");

        assertThat(tags(Mcp.answer(read(task)))).as("without a tag, an empty list").isEmpty();

        Map<String, Object> first = Mcp.answer(attach(task, router));
        assertThat(first.get("address")).isEqualTo(task);
        assertThat(tags(first)).containsExactly(router);

        Map<String, Object> second = Mcp.answer(attach(task, alpha));
        assertThat(tags(second)).as("the answer lists every tag the task carries, ascending")
            .containsExactly(alpha, router);
        assertThat(tags(Mcp.answer(read(task)))).as("and so does a read")
            .containsExactly(alpha, router);
    }

    @Test
    void attaching_a_tag_the_task_carries_and_detaching_one_it_lacks_change_nothing() {
        String task = SurfaceFixture.address(SurfaceFixture.open("no-op", "code"));
        String tag = declare("component:noop");
        Mcp.answer(attach(task, tag));
        String before = Writes.snapshot();

        Map<String, Object> again = Mcp.answer(attach(task, tag));
        assertThat(tags(again)).as("attached once, listed once").containsExactly(tag);
        assertThat(Writes.snapshot()).as("a second attachment writes nothing").isEqualTo(before);

        Map<String, Object> detached = Mcp.answer(detach(task, tag));
        assertThat(tags(detached)).isEmpty();
        String afterDetach = Writes.snapshot();
        Map<String, Object> twice = Mcp.answer(detach(task, tag));
        assertThat(tags(twice)).as("a tag the task lacks detaches as a success").isEmpty();
        assertThat(Writes.snapshot()).as("and writes nothing").isEqualTo(afterDetach);
    }

    @Test
    void attaching_leaves_the_conflict_token_and_the_change_stamp_of_the_task_as_they_are() {
        String task = SurfaceFixture.address(SurfaceFixture.open("untouched", "code"));
        String tag = declare("component:token");
        Map<String, Object> before = Mcp.answer(read(task));
        String stampBefore = updatedAt(before);

        Map<String, Object> attached = Mcp.answer(attach(task, tag));
        assertThat(attached.get("conflict_token")).as("the token the answer carries")
            .isEqualTo(before.get("conflict_token"));
        Map<String, Object> after = Mcp.answer(read(task));
        assertThat(after.get("conflict_token")).as("the token a read gives afterwards")
            .isEqualTo(before.get("conflict_token"));
        assertThat(updatedAt(after)).as("updated_at of the row").isEqualTo(stampBefore);

        Mcp.answer(detach(task, tag));
        assertThat(Mcp.answer(read(task)).get("conflict_token")).as("and after a detach")
            .isEqualTo(before.get("conflict_token"));
        assertThat(updatedAt(Mcp.answer(read(task)))).isEqualTo(stampBefore);
    }

    @Test
    void a_sent_and_a_closed_task_take_a_tag_and_keep_state_text_and_token() {
        String sent = SurfaceFixture.address(SurfaceFixture.open("sent", "code"));
        String closed = SurfaceFixture.address(SurfaceFixture.open("closed", "code"));
        Map<String, Object> open = Mcp.answer(read(closed));
        Mcp.answer(Mcp.call("dispatch_withdraw", Map.of("address", closed,
            "conflict_token", open.get("conflict_token"))));
        String tag = declare("phase:any");

        for (String task : List.of(sent, closed)) {
            Map<String, Object> before = Mcp.answer(read(task));
            String text = textOf(task);

            Map<String, Object> attached = Mcp.answer(attach(task, tag));
            assertThat(tags(attached)).as("%s takes the tag", task).containsExactly(tag);

            Map<String, Object> after = Mcp.answer(read(task));
            assertThat(Mcp.field(after, "state")).as("%s keeps its state", task)
                .isEqualTo(Mcp.field(before, "state"));
            assertThat(Mcp.field(after, "outcome")).isEqualTo(Mcp.field(before, "outcome"));
            assertThat(after.get("conflict_token")).as("%s keeps its token", task)
                .isEqualTo(before.get("conflict_token"));
            assertThat(textOf(task)).as("%s keeps its text", task).isEqualTo(text);
            assertThat(Mcp.answer(detach(task, tag))).as("and a detach is open there too")
                .containsKey("address");
        }
        assertThat(Mcp.field(Mcp.answer(read(sent)), "state")).isEqualTo("open");
        assertThat(Mcp.field(Mcp.answer(read(closed)), "state")).isEqualTo("closed");
    }

    @Test
    void whoever_may_write_in_the_scope_attaches_whatever_part_it_takes() {
        String task = SurfaceFixture.address(SurfaceFixture.open("by an executor", "code"));
        String tag = declare("component:anyone");
        SurfaceFixture.asExecutor(identity);
        assertThat(tags(Mcp.answer(attach(task, tag))))
            .as("an executor that neither commissioned nor holds the task attaches")
            .containsExactly(tag);
        assertThat(tags(Mcp.answer(detach(task, tag)))).isEmpty();
    }

    // =======================================================================
    // Refusals
    // =======================================================================

    @Test
    void an_unknown_token_is_refused_naming_the_tag_and_never_the_token() {
        String task = SurfaceFixture.address(SurfaceFixture.open("unknown", "code"));
        String token = run + "-undeclared";
        String before = Writes.snapshot();

        for (String call : List.of(ATTACH_CALL, DETACH_CALL)) {
            Map<String, Object> refusal = Mcp.refusal(Mcp.call(call, arguments(task, token)));
            assertThat(refusal.get("reason")).as(call).isEqualTo("ARGUMENT_INVALID");
            assertThat(data(refusal)).as(call).containsEntry("offenders", List.of("fields.tag"));
            assertThat(String.valueOf(refusal.get("message"))).as(call)
                .startsWith("fields.tag is not valid for " + call)
                .doesNotContain(token);
            assertThat(String.valueOf(refusal.get("data"))).as(call).doesNotContain(token);
        }
        assertThat(Writes.snapshot()).as("nothing was written").isEqualTo(before);
    }

    @Test
    void a_withdrawn_tag_is_refused_on_attach_and_detaches_from_a_task_that_carries_it() {
        String task = SurfaceFixture.address(SurfaceFixture.open("withdrawn", "code"));
        String tag = declare("component:retiring");
        Mcp.answer(attach(task, tag));
        withdraw(tag);

        assertThat(tags(Mcp.answer(read(task)))).as("a withdrawn tag stays readable")
            .containsExactly(tag);
        String other = SurfaceFixture.address(SurfaceFixture.open("withdrawn other", "code"));
        Map<String, Object> refusal = Mcp.refusal(attach(other, tag));
        assertThat(refusal.get("reason")).isEqualTo("ARGUMENT_INVALID");
        assertThat(data(refusal)).containsEntry("offenders", List.of("fields.tag"));
        assertThat(String.valueOf(refusal.get("message"))).as("the message says it is withdrawn")
            .contains("withdrawn");
        assertThat(tags(Mcp.answer(read(other)))).isEmpty();

        assertThat(tags(Mcp.answer(detach(task, tag)))).as("detaching it is open").isEmpty();
    }

    @Test
    void without_the_write_right_in_the_scope_both_calls_are_refused_as_read_only() {
        String task = SurfaceFixture.address(SurfaceFixture.open("muted", "code"));
        String tag = declare("component:muted");
        Mcp.answer(attach(task, tag));
        SurfaceFixture.asMutedConsole(identity);

        for (String call : List.of(ATTACH_CALL, DETACH_CALL)) {
            assertThat(Mcp.reason(Mcp.call(call, arguments(task, tag)))).as(call)
                .isEqualTo("SCOPE_READ_ONLY");
        }
        SurfaceFixture.asConsole(identity);
        assertThat(tags(Mcp.answer(read(task)))).as("the tag stays").containsExactly(tag);
    }

    @Test
    void a_token_of_another_scope_or_another_tenant_cannot_be_attached() {
        String task = SurfaceFixture.address(SurfaceFixture.open("elsewhere", "code"));
        String global = run + "-global";
        declareIn(SubstrateDatabaseResource.TENANT_ID, SubstrateDatabaseResource.GLOBAL_SCOPE_ID,
            global, "declared");
        String foreign = run + "-foreign";
        declareIn(UUID.randomUUID().toString(), SubstrateDatabaseResource.SCOPE_ID, foreign,
            "declared");

        for (String token : List.of(global, foreign)) {
            Map<String, Object> refusal = Mcp.refusal(attach(task, token));
            assertThat(refusal.get("reason")).as(token).isEqualTo("ARGUMENT_INVALID");
            assertThat(data(refusal)).containsEntry("offenders", List.of("fields.tag"));
        }
        assertThat(tags(Mcp.answer(read(task)))).isEmpty();
    }

    // =======================================================================
    // The filter
    // =======================================================================

    @Test
    void the_filter_tag_lists_the_tasks_that_carry_it_and_combines_with_state() {
        String tag = declare("component:listed");
        String open = SurfaceFixture.open("listed open", "code");
        String closed = SurfaceFixture.open("listed closed", "code");
        String untagged = SurfaceFixture.open("listed untagged", "code");
        Mcp.answer(attach(SurfaceFixture.address(open), tag));
        Mcp.answer(attach(SurfaceFixture.address(closed), tag));
        Map<String, Object> toClose = Mcp.answer(read(SurfaceFixture.address(closed)));
        Mcp.answer(Mcp.call("dispatch_withdraw", Map.of("address",
            SurfaceFixture.address(closed), "conflict_token", toClose.get("conflict_token"))));

        List<Map<String, Object>> tagged = objects(query(Map.of("tag", tag)));
        assertThat(tagged).extracting(t -> t.get("address"))
            .containsExactlyInAnyOrder(SurfaceFixture.address(open),
                SurfaceFixture.address(closed))
            .doesNotContain(SurfaceFixture.address(untagged));
        assertThat(tagged).as("each row carries its tags")
            .allSatisfy(t -> assertThat(tags(t)).containsExactly(tag));

        assertThat(objects(query(Map.of("tag", tag, "state", "open"))))
            .as("tag and state combine as AND").extracting(t -> t.get("address"))
            .containsExactly(SurfaceFixture.address(open));
        assertThat(objects(query(Map.of("tag", tag, "state", "closed"))))
            .extracting(t -> t.get("address"))
            .containsExactly(SurfaceFixture.address(closed));

        assertThat(objects(query(Map.of("state", "open")))).as("without the filter, every row "
                + "of a listing carries tags").allSatisfy(t -> assertThat(t.get("fields"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsKey(TAGS_FIELD));
    }

    @Test
    void an_unknown_token_lists_an_empty_page_and_a_list_of_tokens_is_refused() {
        Map<String, Object> empty = Mcp.answer(query(Map.of("tag", run + "-nowhere")));
        assertThat(objects(empty)).as("an unknown token is an empty page, no refusal").isEmpty();
        assertThat(empty).doesNotContainKey("next_cursor");

        Map<String, Object> refusal = Mcp.refusal(query(Map.of("tag", "alpha,beta")));
        assertThat(refusal.get("reason")).as("the filter takes one token, no list")
            .isEqualTo("ARGUMENT_INVALID");
        assertThat(data(refusal)).containsEntry("offenders", List.of("tag"));
    }

    @Test
    void the_tags_of_another_tenant_are_seen_neither_in_a_read_nor_through_the_filter() {
        String id = SurfaceFixture.open("tenant a", "code");
        String task = SurfaceFixture.address(id);
        String own = declare("tenant:own");
        Mcp.answer(attach(task, own));

        // Tenant B: the same scope, the same selector, a task at the same
        // address, a tag of its own attached to it. Planted by the
        // administrator, as the platform and another deployment would.
        String tenantB = UUID.randomUUID().toString();
        String foreign = run + "-tenant-b";
        String foreignTag = declareIn(tenantB, SubstrateDatabaseResource.SCOPE_ID, foreign,
            "declared");
        String[] numberSub = id.split("\\.");
        PlatformFixture.run(
            "INSERT INTO dispatch.selector (tenant_id, scope_id, name, next_number) VALUES ('"
                + tenantB + "', '" + SubstrateDatabaseResource.SCOPE_ID + "', '"
                + SurfaceFixture.SELECTOR + "', 1000)",
            "INSERT INTO dispatch.task (tenant_id, scope_id, selector_id, number, sub, title, "
                + "apparatus, updated_at) SELECT '" + tenantB + "', '"
                + SubstrateDatabaseResource.SCOPE_ID + "', s.id, " + numberSub[0] + ", "
                + numberSub[1] + ", 'tenant b', 'code', now() "
                + "FROM dispatch.selector s WHERE s.tenant_id = '" + tenantB + "'",
            "INSERT INTO dispatch.task_tag (tenant_id, task_id, tag_id, attached_by) "
                + "SELECT t.tenant_id, t.id, '" + foreignTag + "', 'tenant-b' "
                + "FROM dispatch.task t WHERE t.tenant_id = '" + tenantB + "'",
            // And B's relation names A's tag too: only the tenant keeps them apart.
            "INSERT INTO dispatch.task_tag (tenant_id, task_id, tag_id, attached_by) "
                + "SELECT t.tenant_id, t.id, g.id, 'tenant-b' FROM dispatch.task t, platform.tag g "
                + "WHERE t.tenant_id = '" + tenantB + "' AND g.token = '" + own + "'");

        assertThat(tags(Mcp.answer(read(task)))).as("the read of A's task at the same address")
            .containsExactly(own);
        assertThat(objects(query(Map.of("tag", foreign))))
            .as("B's token lists nothing in A").isEmpty();
        assertThat(objects(query(Map.of("tag", own)))).extracting(t -> t.get("address"))
            .as("A's token lists A's task once, and nothing of B")
            .containsExactly(task);
        assertThat(Mcp.refusal(attach(task, foreign)).get("reason"))
            .as("B's token is no token of A's scope").isEqualTo("ARGUMENT_INVALID");
    }

    // -----------------------------------------------------------------------

    /** Declares a tag in the probe's own scope and tenant; answers its token. */
    private String declare(String name) {
        String token = run + "-" + name;
        declareIn(SubstrateDatabaseResource.TENANT_ID, SubstrateDatabaseResource.SCOPE_ID, token,
            "declared");
        return token;
    }

    /** Declares a tag as the platform does, as the administrator; answers its id. */
    private static String declareIn(String tenant, String scope, String token, String status) {
        String id = UUID.randomUUID().toString();
        PlatformFixture.run("INSERT INTO platform.tag (id, tenant_id, scope_id, token, status, "
            + "created_by) VALUES ('" + id + "', '" + tenant + "', '" + scope + "', '" + token
            + "', '" + status + "', 'platform-fixture')");
        return id;
    }

    private static void withdraw(String token) {
        PlatformFixture.run("UPDATE platform.tag SET status = 'withdrawn', withdrawn_at = now() "
            + "WHERE token = '" + token + "'");
    }

    private static Map<String, Object> arguments(String address, String token) {
        return Map.of("address", address, "fields", Map.of("tag", token));
    }

    private static Response attach(String address, String token) {
        return Mcp.call(ATTACH_CALL, arguments(address, token));
    }

    private static Response detach(String address, String token) {
        return Mcp.call(DETACH_CALL, arguments(address, token));
    }

    private static Response read(String address) {
        return Mcp.call("dispatch_read", Map.of("address", address));
    }

    private static Response query(Map<String, Object> filters) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("scope", SurfaceFixture.SCOPE);
        call.put("selector", SurfaceFixture.SELECTOR);
        call.put("page_size", 200);
        call.putAll(filters);
        return Mcp.call("dispatch_query", call);
    }

    private static String textOf(String address) {
        return String.valueOf(Mcp.answer(Mcp.call("dispatch_read_text",
            Map.of("address", address, "part", "dispatch"))).get("texts"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> tags(Map<String, Object> answer) {
        return (List<String>) ((Map<String, Object>) answer.get("fields")).get(TAGS_FIELD);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Response listing) {
        return objects(Mcp.answer(listing));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Map<String, Object> listing) {
        return (List<Map<String, Object>>) listing.get("objects");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> refusal) {
        return (Map<String, Object>) refusal.get("data");
    }

    /** The stored change stamp of a task, by its technical address, read as the administrator. */
    private static String updatedAt(Map<String, Object> head) {
        String uuid = Mcp.field(head, "identity").substring("dispatch://".length());
        var config = ConfigProvider.getConfig();
        try (Connection c = DriverManager.getConnection(
                config.getValue("test.db.url", String.class),
                config.getValue("test.db.admin.username", String.class),
                config.getValue("test.db.admin.password", String.class));
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT updated_at::text FROM dispatch.task "
                 + "WHERE uuid = '" + uuid + "'")) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
