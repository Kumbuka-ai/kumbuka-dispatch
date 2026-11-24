/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.tenancy;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.DefinitionFixture;
import ai.kumbuka.dispatch.domain.DispatchPermission;
import ai.kumbuka.dispatch.domain.Instance;
import ai.kumbuka.dispatch.domain.InstanceService;
import ai.kumbuka.dispatch.domain.InstanceView;
import ai.kumbuka.dispatch.domain.Provenance;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rows the acts on an instance write are not readable across tenants, by
 * each of the two layers on its own, each with the red state observed on
 * every build (the form of {@code DefinitionTenancyIT}).
 *
 * <p>Both tenants start and conclude an instance under the same scope id and
 * the same selector name, through the kernel, so every table this change
 * writes holds a row of each: {@code instance}, {@code instance_return},
 * {@code external_reference}, {@code field_value}, {@code
 * instance_field_value} and {@code content}. Nothing but the tenant keeps the
 * two apart.
 *
 * <ul>
 *   <li><b>The discriminator.</b> With the row-level security of the six
 *       tables taken out of force, the ORM bound to tenant A reads A's rows
 *       of each and none of B's: the tenant filter holds alone. The red
 *       state: in the same state, a statement past the ORM, under the service
 *       role bound to A, reads B's rows in every one of them.</li>
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
class InstanceTenancyIT {

    private static final String SELECTOR = "sprint";

    /** Every table this change writes, by the entity that maps it. */
    private static final Map<String, String> TABLES = new LinkedHashMap<>();

    static {
        TABLES.put("instance", "Instance");
        TABLES.put("instance_return", "InstanceReturn");
        TABLES.put("external_reference", "ExternalReference");
        TABLES.put("field_value", "FieldValue");
        TABLES.put("instance_field_value", "InstanceFieldValue");
        TABLES.put("content", "Content");
    }

    private static final Actor ACTOR = new Actor("tenancy-probe",
        DispatchPermission.INSTANCE_START, DispatchPermission.INSTANCE_CONCLUDE);

    @Inject InstanceService instances;
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
        startAndConclude(tenantA);
        startAndConclude(tenantB);
    }

    @Test
    void the_discriminator_alone_keeps_another_tenants_instance_rows_unread() throws Exception {
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
            assertThat(count(c, "instance", tenantB))
                .as("and restored: past the discriminator, the security holds again").isZero();
        }
    }

    @Test
    void the_row_level_security_alone_keeps_another_tenants_instance_rows_unread()
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

    /** A version, and an instance started with every row a start writes and concluded. */
    private void startAndConclude(UUID tenant) throws Exception {
        DefinitionFixture plant = new DefinitionFixture(tenant, scope);
        long version = plant.version(plant.selector(SELECTOR), 1, "active", true, false, false);
        long summary = plant.field(version, "summary", "text", null);
        plant.startField(version, summary, true);
        plant.versionLabel(version, "approved", 1);
        try (AutoCloseable ignored = tenantContext.bind(tenant)) {
            InstanceView started = instances.start(ACTOR, "probe", scope, SELECTOR,
                new InstanceService.Start("a title", "an input", "a trigger", List.of("an item"),
                    List.of("a reference"), Map.of("summary", "a summary")),
                provenance(DispatchPermission.INSTANCE_START));
            instances.close(ACTOR, started.address(), scope, Instance.Resolution.CONCLUDED,
                new InstanceService.Closing(started.conflictToken(), null, "a return", "green",
                    "approved", "a basis"),
                provenance(DispatchPermission.INSTANCE_CONCLUDE));
        }
    }

    private static Provenance provenance(DispatchPermission permission) {
        return new Provenance("tenancy-probe", null, permission, Provenance.Path.INTERFACE, null);
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
