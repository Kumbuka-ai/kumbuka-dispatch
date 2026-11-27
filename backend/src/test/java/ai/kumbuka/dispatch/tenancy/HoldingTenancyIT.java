/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.tenancy;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.DispatchPermission;
import ai.kumbuka.dispatch.domain.HoldingService;
import ai.kumbuka.dispatch.domain.IdempotencyKey;
import ai.kumbuka.dispatch.domain.Provenance;
import ai.kumbuka.dispatch.domain.TaskService;
import ai.kumbuka.dispatch.domain.TaskView;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rows the calls that hold a task write are not readable across tenants,
 * by each of the two layers on its own, each with the red state observed on
 * every build (the form of {@code DraftTenancyIT}).
 *
 * <p>Both tenants create and send tasks under the same scope id and the same
 * selector name, through the kernel, and an executor claims one under a key and
 * releases it with a remark, and claims and defers another. So every table this
 * change writes holds a row of each: {@code holding}, {@code receipt}, {@code
 * lease}, {@code act}, {@code content}, {@code idempotency_key} and {@code
 * task}. Nothing but the tenant keeps the two apart.
 *
 * <ul>
 *   <li><b>The discriminator.</b> With the row-level security of the seven
 *       tables taken out of force, the ORM bound to tenant A reads A's rows
 *       of each and none of B's. The red state: in the same state, a statement
 *       past the ORM, under the service role bound to A, reads B's rows in
 *       every one of them.</li>
 *   <li><b>The row-level security, with the discriminator bypassed.</b> Raw
 *       JDBC under the service role bound to A reads none of B's rows in any
 *       of them. The red state: with their security taken out of force, the
 *       same statements read B's rows.</li>
 * </ul>
 *
 * <p>Every removal is made by the tables' owner and put back in a {@code
 * finally}, and the green state after it shows the removal was the cause.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class HoldingTenancyIT {

    /** Every table the calls that hold a task write, by the entity that maps it. */
    private static final Map<String, String> TABLES = new LinkedHashMap<>();

    static {
        TABLES.put("holding", "Holding");
        TABLES.put("receipt", "IssuedReceipt");
        TABLES.put("lease", "Lease");
        TABLES.put("act", "Act");
        TABLES.put("content", "Content");
        TABLES.put("idempotency_key", "IdempotencyRecord");
        TABLES.put("task", "Task");
    }

    private static final Actor EXECUTOR = new Actor("tenancy-executor",
        DispatchPermission.TASK_EXECUTE);
    private static final Provenance EXECUTING = new Provenance("tenancy-executor", null,
        DispatchPermission.TASK_EXECUTE, Provenance.Path.INTERFACE, null);

    @Inject TaskService tasks;
    @Inject HoldingService holdings;
    @Inject TenantContext tenantContext;
    @Inject EntityManager em;

    private final UUID scope = UUID.randomUUID();
    private UUID tenantA;
    private UUID tenantB;

    /** Fresh tenants, so no count depends on another test. */
    @BeforeEach
    void plant() throws Exception {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        holdAndGiveBack(tenantA);
        holdAndGiveBack(tenantB);
    }

    @Test
    void the_discriminator_alone_keeps_another_tenants_holding_rows_unread() throws Exception {
        try (Connection c = Db.asService()) {
            try {
                switchAll(c, "DISABLE");
                try (AutoCloseable ignored = tenantContext.bind(tenantA)) {
                    for (Map.Entry<String, String> table : TABLES.entrySet()) {
                        assertThat(throughTheOrm(table.getValue()))
                            .as("with no row-level security on %s, the ORM bound to A reads A's "
                                + "rows and none of B's", table.getKey())
                            .isEqualTo(rowsOf(table.getKey(), tenantA))
                            .isPositive();
                    }
                }

                Db.bindTenant(c, tenantA);
                for (String table : TABLES.keySet()) {
                    assertThat(count(c, table, tenantB))
                        .as("RED STATE, observed: in the same state, a statement past the "
                            + "discriminator under the service role bound to A reads B's rows "
                            + "of %s, so the count above was the discriminator", table)
                        .isPositive();
                }
                c.rollback();
            } finally {
                switchAll(c, "ENABLE");
            }
            Db.bindTenant(c, tenantA);
            assertThat(count(c, "holding", tenantB))
                .as("and restored: past the discriminator, the security holds again").isZero();
        }
    }

    @Test
    void the_row_level_security_alone_keeps_another_tenants_holding_rows_unread()
            throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            for (String table : TABLES.keySet()) {
                assertThat(count(c, table, tenantA))
                    .as("A reads its own rows of %s past the ORM", table).isPositive();
                assertThat(count(c, table, tenantB)).as("and none of B's").isZero();
            }
            c.rollback();

            try {
                switchAll(c, "DISABLE");
                Db.bindTenant(c, tenantA);
                for (String table : TABLES.keySet()) {
                    assertThat(count(c, table, tenantB))
                        .as("RED STATE, observed: with the security of %s out of force, the "
                            + "service role bound to A reads B's rows", table)
                        .isPositive();
                }
                c.rollback();
            } finally {
                switchAll(c, "ENABLE");
            }
            Db.bindTenant(c, tenantA);
            for (String table : TABLES.keySet()) {
                assertThat(count(c, table, tenantB))
                    .as("and restored: the isolation of %s is back", table).isZero();
            }
        }
    }

    // -----------------------------------------------------------------------

    /**
     * A version and two tasks sent; the first claimed under a key and released
     * with a remark, the second claimed and deferred, so that every table the
     * holding writes holds a row of the tenant.
     */
    private void holdAndGiveBack(UUID tenant) throws Exception {
        TenantTasks.plant(tenant, scope);
        try (AutoCloseable ignored = tenantContext.bind(tenant)) {
            TaskView released = sent("to be released");
            TaskView claimed = holdings.claim(EXECUTOR, released.address(), scope,
                new HoldingService.Claim(null, IdempotencyKey.of("tenancy-" + UUID.randomUUID())),
                EXECUTING);
            holdings.release(EXECUTOR, released.address(), scope,
                new HoldingService.Release(claimed.standing().receipt(), "a remark"), EXECUTING);

            TaskView deferred = sent("to be deferred");
            TaskView held = holdings.claim(EXECUTOR, deferred.address(), scope,
                new HoldingService.Claim(null, IdempotencyKey.NONE), EXECUTING);
            holdings.defer(EXECUTOR, deferred.address(), scope,
                new HoldingService.Defer(held.standing().receipt(), held.conflictToken(),
                    Instant.parse("2099-01-01T00:00:00Z"), "a remark"), EXECUTING);
        }
    }

    /** A task of the short form, created and sent under the bound tenant. */
    private TaskView sent(String title) {
        TaskView draft = tasks.create(TenantTasks.ACTOR, "probe", scope, TenantTasks.SELECTOR,
            TenantTasks.create(title, null), TenantTasks.PROVENANCE);
        return tasks.send(TenantTasks.ACTOR, draft.address(), scope, draft.conflictToken(),
            TenantTasks.PROVENANCE);
    }

    /** The rows of an entity in this test's scope the ORM reads under the bound tenant. */
    private long throughTheOrm(String entity) {
        return QuarkusTransaction.requiringNew().call(() -> em.createQuery(
                "SELECT count(x) FROM " + entity + " x WHERE x.scopeId = :scope", Long.class)
            .setParameter("scope", scope)
            .getSingleResult());
    }

    private static void switchAll(Connection measuring, String how) throws SQLException {
        for (String table : TABLES.keySet()) {
            Db.switchPolicyAsOwner(measuring,
                "ALTER TABLE dispatch." + table + " " + how + " ROW LEVEL SECURITY");
        }
    }

    /** The rows of one tenant in a table of this test's scope, as the store holds them. */
    private long rowsOf(String table, UUID tenant) throws SQLException {
        try (Connection c = Db.asAdmin()) {
            return count(c, table, tenant);
        }
    }

    /** The rows of one tenant in a table of this test's scope that the session sees. */
    private long count(Connection c, String table, UUID tenant) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT count(*) FROM dispatch." + table
                + " WHERE tenant_id = ? AND scope_id = ?")) {
            st.setObject(1, tenant);
            st.setObject(2, scope);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
