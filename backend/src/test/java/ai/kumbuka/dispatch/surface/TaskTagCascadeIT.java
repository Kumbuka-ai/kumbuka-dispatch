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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleting a draft takes its tag relations along (V24, sprint/210).
 *
 * <p>V24 promises that the delete of a draft, the one path that removes a task
 * row, removes the draft's rows in {@code dispatch.task_tag} through the
 * cascade of {@code fk_task_tag_task_tenant}, so that the image before V24,
 * which knows nothing of the relation, deletes a tagged draft unchanged. This
 * test observes that promise: the draft is created, tagged and deleted over
 * the assistant surface, under the service's runtime role, and the relation
 * is then read as the administrator, who sees past row-level security.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskTagCascadeIT {

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    void deleting_a_tagged_draft_removes_its_tag_relations_and_leaves_those_of_other_tasks() {
        String token = "it" + UUID.randomUUID().toString().substring(0, 8) + "-component:cascade";
        PlatformFixture.run("INSERT INTO platform.tag (tenant_id, scope_id, token, created_by) "
            + "VALUES ('" + SubstrateDatabaseResource.TENANT_ID + "', '"
            + SubstrateDatabaseResource.SCOPE_ID + "', '" + token + "', 'platform-fixture')");

        Map<String, Object> created = Mcp.answer(Mcp.call("dispatch_create", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
            "fields", Map.of("title", "a tagged draft to delete", "apparatus", "code"))));
        String draft = String.valueOf(created.get("address"));
        String kept = SurfaceFixture.address(SurfaceFixture.open("a tagged task to keep", "code"));
        Mcp.answer(attach(draft, token));
        Mcp.answer(attach(kept, token));

        long draftId = taskId(draft);
        long keptId = taskId(kept);
        assertThat(relations(draftId)).as("the draft carries the tag before the delete")
            .isEqualTo(1);

        Map<String, Object> deleted = Mcp.answer(Mcp.call("dispatch_delete", Map.of(
            "address", draft, "conflict_token", created.get("conflict_token"))));
        assertThat(deleted.get("address")).isEqualTo(draft);
        assertThat(Mcp.reason(Mcp.call("dispatch_read", Map.of("address", draft))))
            .as("the draft is gone").isEqualTo("NOT_FOUND");

        assertThat(relations(draftId)).as("no row of dispatch.task_tag names the deleted draft")
            .isZero();
        assertThat(relations(keptId)).as("the relation of the other task stands").isEqualTo(1);
    }

    private static Response attach(String address, String token) {
        return Mcp.call("dispatch_tag_attach",
            Map.of("address", address, "fields", Map.of("tag", token)));
    }

    /** The surrogate of a task, by the technical address a read answers, as the administrator. */
    private static long taskId(String address) {
        String uuid = Mcp.field(Mcp.answer(Mcp.call("dispatch_read", Map.of("address", address))),
            "identity").substring("dispatch://".length());
        return single("SELECT id FROM dispatch.task WHERE uuid = '" + uuid + "'");
    }

    /** The rows of dispatch.task_tag for one task, as the administrator. */
    private static long relations(long taskId) {
        return single("SELECT count(*) FROM dispatch.task_tag WHERE task_id = " + taskId);
    }

    /** The one number a query answers, read as the administrator. */
    private static long single(String query) {
        var config = ConfigProvider.getConfig();
        try (Connection c = DriverManager.getConnection(
                config.getValue("test.db.url", String.class),
                config.getValue("test.db.admin.username", String.class),
                config.getValue("test.db.admin.password", String.class));
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(query)) {
            if (!rs.next()) {
                throw new IllegalStateException("no row for " + query);
            }
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
