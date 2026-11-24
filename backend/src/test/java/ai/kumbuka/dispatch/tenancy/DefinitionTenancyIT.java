/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.tenancy;

import ai.kumbuka.dispatch.domain.DefinitionFixture;
import ai.kumbuka.dispatch.domain.DefinitionService;
import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.repository.DefinitionRepository;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A definition of another tenant is not readable, by each of the two layers on
 * its own, each with the red state observed on every build (the form of
 * {@code ActTenancyIT}).
 *
 * <p>Both tenants plant a seeded, active version under the same scope id and
 * the same selector name, with a row in every table a read of a definition
 * reads; nothing but the tenant keeps the two apart. Tenant A's version is
 * retired where B's is active, so a read that crossed the tenant would find
 * B's active version where A has none.
 *
 * <ul>
 *   <li><b>The discriminator.</b> With the row-level security of every table
 *       a read of a definition reads taken out of force, the ORM bound to
 *       tenant A finds no active version: the tenant filter holds alone. The
 *       red state: in the same state, a statement past the ORM, under the
 *       same role and tenant, reads B's rows in every one of those tables.</li>
 *   <li><b>The row-level security, with the discriminator bypassed.</b> Raw
 *       JDBC under the service role bound to A reads none of B's rows in any
 *       of those tables. The red state: with their security taken out of
 *       force, the same statements read B's rows.</li>
 * </ul>
 *
 * <p>Every removal is made by the tables' owner and put back in a {@code
 * finally}, and the green state after it shows the removal was the cause.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class DefinitionTenancyIT {

    private static final String SELECTOR = "sprint";

    /** Every table a read of a definition reads. */
    private static final List<String> TABLES = List.of("selector", "definition_version",
        "activity", "outcome_label", "enumeration", "enumeration_value", "control_field",
        "contract_field");

    @Inject DefinitionRepository repository;
    @Inject DefinitionService definitions;
    @Inject TenantContext tenantContext;

    private final UUID scope = UUID.randomUUID();
    private UUID tenantA;
    private UUID tenantB;

    /** Fresh tenants, so no count depends on another test. */
    @BeforeEach
    void plant() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        plantIn(tenantA, "retired");
        plantIn(tenantB, "active");
    }

    // -----------------------------------------------------------------------
    // The discriminator, with the row-level security out of the way
    // -----------------------------------------------------------------------

    @Test
    void the_discriminator_alone_keeps_another_tenants_definition_unread() throws Exception {
        try (AutoCloseable ignored = tenantContext.bind(tenantA)) {
            assertThat(repository.seeded(scope, SELECTOR, 1))
                .as("A reads its own version by its number").isPresent();
            assertUnread();
        }

        try (Connection c = Db.asService()) {
            try {
                switchAll(c, "DISABLE");
                try (AutoCloseable ignored = tenantContext.bind(tenantA)) {
                    assertUnread();
                }

                Db.bindTenant(c, tenantA);
                for (String table : TABLES) {
                    assertThat(rowsOfB(c, table))
                        .as("RED STATE, observed: in the same state, a statement past the "
                            + "discriminator under the service role bound to A reads B's rows "
                            + "of %s, so the emptiness above was the discriminator", table)
                        .isPositive();
                }
                c.rollback();
            } finally {
                switchAll(c, "ENABLE");
            }
            Db.bindTenant(c, tenantA);
            assertThat(rowsOfB(c, "definition_version"))
                .as("and restored: past the discriminator, the security holds again")
                .isZero();
        }
    }

    // -----------------------------------------------------------------------
    // The row-level security, with the discriminator bypassed
    // -----------------------------------------------------------------------

    @Test
    void the_row_level_security_alone_keeps_another_tenants_definition_unread()
            throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            for (String table : TABLES) {
                assertThat(rowsOf(c, table, tenantA))
                    .as("A reads its own rows of %s past the ORM", table).isPositive();
                assertThat(rowsOfB(c, table)).as("and none of B's").isZero();
            }
            c.rollback();

            try {
                switchAll(c, "DISABLE");
                Db.bindTenant(c, tenantA);
                for (String table : TABLES) {
                    assertThat(rowsOfB(c, table))
                        .as("RED STATE, observed: with the security of %s out of force, the "
                            + "service role bound to A reads B's rows", table)
                        .isPositive();
                }
                c.rollback();
            } finally {
                switchAll(c, "ENABLE");
            }
            Db.bindTenant(c, tenantA);
            for (String table : TABLES) {
                assertThat(rowsOfB(c, table))
                    .as("and restored: the isolation of %s is back, so the red state was the "
                        + "removal", table)
                    .isZero();
            }
        }
    }

    // -----------------------------------------------------------------------

    /** Under A, through the ORM: no active version, since only B has one. */
    private void assertUnread() {
        assertThat(repository.seeded(scope, SELECTOR, null))
            .as("A reads no active version, though B's stands under the same scope and "
                + "selector name")
            .isEmpty();
        assertThatThrownBy(() -> definitions.read(scope, SELECTOR, null))
            .as("and the reader answers NOT_FOUND")
            .isInstanceOfSatisfying(DispatchException.class, refused ->
                assertThat(refused.reason()).isEqualTo(DispatchException.Reason.NOT_FOUND));
    }

    /** A seeded version 1 in the state given, with a row in every table a read reads. */
    private void plantIn(UUID tenant, String state) {
        DefinitionFixture plant = new DefinitionFixture(tenant, scope);
        long version = plant.version(plant.selector(SELECTOR), 1, state, true, false, false);
        long enumeration = plant.enumeration(version, "level", List.of("low", "high"));
        long field = plant.field(version, "level", "choice", enumeration);
        long activity = plant.activity(version, "review", false, false, false, "none", false);
        plant.activityLabel(activity, "done", 1);
        plant.resultField(version, activity, field, true);
    }

    private static void switchAll(Connection measuring, String how) throws SQLException {
        for (String table : TABLES) {
            Db.switchPolicyAsOwner(measuring,
                "ALTER TABLE dispatch." + table + " " + how + " ROW LEVEL SECURITY");
        }
    }

    private long rowsOfB(Connection c, String table) throws SQLException {
        return rowsOf(c, table, tenantB);
    }

    /** The rows of one tenant in a table of this test's scope that the session sees. */
    private long rowsOf(Connection c, String table, UUID tenant) throws SQLException {
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
