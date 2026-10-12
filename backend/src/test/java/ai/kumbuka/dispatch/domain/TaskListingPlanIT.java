/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.boundary.RecordedStatements;
import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static ai.kumbuka.dispatch.domain.TaskStage.C;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The planner walks the index of a listing's order and sorts nothing: asked
 * about the statement the service issues for a page, as the service role,
 * with the tenant bound, on a selector of several thousand tasks among others
 * of another selector and another tenant.
 *
 * <p>The statement is the one Hibernate prepares for the page, recorded by
 * {@link RecordedStatements} and handed to {@code EXPLAIN ANALYZE} unchanged
 * with the values the page binds, so the plan belongs to the statement that
 * runs and not to one written to resemble it.
 *
 * <p>Red probes, each observed 2026-10-12 against the running database:
 * <ul>
 *   <li>the selector named through a join on its name, as the page did before:
 *       with either index in place the planner sorts all 5000 tasks of the
 *       selector for a page of fifty;
 *   <li>V19's index, which ends on {@code id}, in place of V20's: the planner
 *       walks it up to {@code created_at} and sorts again past it, an
 *       incremental sort over the tasks of each instant. Kept as a test of its
 *       own below.
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskListingPlanIT {

    private static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final String SELECTOR = "sprint";

    /** Tasks per selector: enough that a sort costs the planner more than a walk. */
    private static final int TASKS = 5000;
    private static final int PAGE = 50;

    @Inject TaskService tasks;
    @Inject TenantContext tenantContext;

    private UUID tenant;
    private long selectorId;

    /** One tenant with two selectors of 5000 tasks each, and a neighbour tenant with as many. */
    @BeforeEach
    void stage() throws SQLException {
        PlatformFixture.grantDirectoryAccess();
        tenant = UUID.randomUUID();
        UUID neighbour = UUID.randomUUID();
        DomainFixture.declareSelector(tenant, SCOPE, SELECTOR);
        DomainFixture.declareSelector(tenant, SCOPE, "satellite");
        DomainFixture.declareSelector(neighbour, SCOPE, SELECTOR);
        seed(tenant, SELECTOR);
        seed(tenant, "satellite");
        seed(neighbour, SELECTOR);
        PlatformFixture.run("ANALYZE dispatch.task", "ANALYZE dispatch.selector");
        selectorId = selectorId(tenant, SELECTOR);
    }

    @Test
    void the_creation_order_walks_its_index_and_sorts_nothing() throws SQLException {
        String plan = explain(statementOf(new PageRequest(PAGE, SortKey.CREATED_AT,
            SortKey.Direction.ASC, Optional.empty())), tenant, SCOPE, selectorId, PAGE + 1);

        assertThat(plan).as(plan)
            .contains("Index Scan using idx_task_created_address")
            .doesNotContain("Sort");
    }

    @Test
    void a_continued_descending_creation_order_walks_its_index_backward() throws SQLException {
        PageRequest first = new PageRequest(PAGE, SortKey.CREATED_AT, SortKey.Direction.DESC,
            Optional.empty());
        String cursor;
        try (AutoCloseable bound = tenantContext.bind(tenant)) {
            cursor = tasks.query(SCOPE, SELECTOR, TaskFilter.none(), first, C).nextCursor()
                .orElseThrow();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String sql = statementOf(new PageRequest(PAGE, SortKey.CREATED_AT,
            SortKey.Direction.DESC, Optional.of(cursor)));

        String plan = explain(sql, tenant, SCOPE, selectorId,
            "2026-01-06T12:00:00Z", 2500, 0, PAGE + 1);

        assertThat(plan).as(plan)
            .contains("Index Scan Backward using idx_task_created_address")
            .doesNotContain("Sort");
    }

    @Test
    void the_address_order_walks_the_address_key_and_sorts_nothing() throws SQLException {
        String plan = explain(statementOf(new PageRequest(PAGE, SortKey.ID,
            SortKey.Direction.ASC, Optional.empty())), tenant, SCOPE, selectorId, PAGE + 1);

        assertThat(plan).as(plan)
            .contains("Index Scan using uq_task_address")
            .doesNotContain("Sort");
    }

    @Test
    void with_the_index_that_ends_on_id_the_creation_order_is_sorted() throws SQLException {
        String sql = statementOf(new PageRequest(PAGE, SortKey.CREATED_AT,
            SortKey.Direction.ASC, Optional.empty()));
        PlatformFixture.run("DROP INDEX dispatch.idx_task_created_address",
            "CREATE INDEX idx_task_created ON dispatch.task "
                + "(tenant_id, scope_id, selector_id, created_at, id)",
            "ANALYZE dispatch.task");
        try {
            String plan = explain(sql, tenant, SCOPE, selectorId, PAGE + 1);

            assertThat(plan).as("RED STATE, observed: " + plan)
                .contains("Sort")
                .doesNotContain("idx_task_created_address");
        } finally {
            PlatformFixture.run("DROP INDEX dispatch.idx_task_created",
                "CREATE INDEX idx_task_created_address ON dispatch.task "
                    + "(tenant_id, scope_id, selector_id, created_at, number, sub)",
                "ANALYZE dispatch.task");
        }
    }

    // ------------------------------------------------------------------

    /** The page statement the service prepares for a request, as Hibernate wrote it. */
    private String statementOf(PageRequest request) {
        RecordedStatements.clear();
        try (AutoCloseable bound = tenantContext.bind(tenant)) {
            tasks.query(SCOPE, SELECTOR, TaskFilter.none(), request, C);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return RecordedStatements.recorded().stream()
            .filter(sql -> sql.contains("from dispatch.task t1_0") && sql.contains("order by"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no page statement among "
                + RecordedStatements.recorded()));
    }

    /**
     * The plan of a statement as the service role runs it, with the tenant
     * bound and the values given for its parameters in their order.
     */
    private static String explain(String sql, Object... values) throws SQLException {
        StringBuilder numbered = new StringBuilder();
        int parameters = 0;
        for (char ch : sql.toCharArray()) {
            if (ch == '?') {
                numbered.append('$').append(++parameters);
            } else {
                numbered.append(ch);
            }
        }
        assertThat(parameters).as("the parameters of " + sql).isEqualTo(values.length);
        List<String> literals = new ArrayList<>();
        for (Object value : values) {
            literals.add(value instanceof Number ? value.toString() : "'" + value + "'");
        }
        try (Connection c = DriverManager.getConnection(config("test.db.url"),
                SubstrateDatabaseResource.SERVICE_ROLE, SubstrateDatabaseResource.SERVICE_PASSWORD);
             Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                s.execute("SELECT set_config('app.tenant_id', '" + values[0] + "', true)");
                s.execute("PREPARE page_probe AS " + numbered);
                List<String> plan = new ArrayList<>();
                try (ResultSet rs = s.executeQuery("EXPLAIN (ANALYZE, TIMING OFF, SUMMARY OFF) "
                        + "EXECUTE page_probe(" + String.join(", ", literals) + ")")) {
                    while (rs.next()) {
                        plan.add(rs.getString(1));
                    }
                }
                return String.join("\n", plan);
            } finally {
                c.rollback();
            }
        }
    }

    /** Tasks numbered 1 to {@link #TASKS}, created at scattered instants over twelve days. */
    private static void seed(UUID tenant, String selector) {
        PlatformFixture.run("INSERT INTO dispatch.task (tenant_id, scope_id, selector_id, "
            + "number, sub, title, apparatus, created_at) "
            + "SELECT tenant_id, scope_id, id, g, 0, 'probe', 'code', "
            + "timestamptz '2026-01-01' + (random() * 1000000)::int * interval '1 second' "
            + "FROM dispatch.selector, generate_series(1, " + TASKS + ") g "
            + "WHERE tenant_id = '" + tenant + "' AND scope_id = '" + SCOPE + "' "
            + "AND name = '" + selector + "'");
    }

    private static long selectorId(UUID tenant, String name) throws SQLException {
        try (Connection c = DriverManager.getConnection(config("test.db.url"),
                config("test.db.admin.username"), config("test.db.admin.password"));
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id FROM dispatch.selector WHERE tenant_id = '"
                 + tenant + "' AND scope_id = '" + SCOPE + "' AND name = '" + name + "'")) {
            assertThat(rs.next()).as("the selector " + name).isTrue();
            return rs.getLong(1);
        }
    }

    private static String config(String key) {
        return ConfigProvider.getConfig().getValue(key, String.class);
    }
}
