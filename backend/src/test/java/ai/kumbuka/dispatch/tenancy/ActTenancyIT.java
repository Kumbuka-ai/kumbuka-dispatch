/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.tenancy;

import ai.kumbuka.dispatch.domain.Act;
import ai.kumbuka.dispatch.domain.ActService;
import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.domain.DispatchPermission;
import ai.kumbuka.dispatch.domain.InstanceFixture;
import ai.kumbuka.dispatch.domain.Provenance;
import ai.kumbuka.dispatch.repository.ActRepository;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An act of another tenant is neither readable nor writable, by each of the
 * two layers on its own, each with the red state observed on every build.
 *
 * <p>The two tenants share the scope id and the selector name, so every
 * address of one also exists in the other: nothing but the tenant keeps the
 * acts apart.
 *
 * <ul>
 *   <li><b>The discriminator.</b> With the row-level security of {@code act}
 *       and {@code instance} taken out of force, the ORM bound to tenant A
 *       still neither reads B's act nor writes to B's instance: the tenant
 *       filter holds alone. The red state: in the same state, a statement
 *       past the ORM, under the same role and tenant, reads B's act and writes
 *       one of B's.</li>
 *   <li><b>The row-level security, with the discriminator bypassed.</b> Raw
 *       JDBC under the service role bound to A neither reads B's act nor
 *       writes one of B's. The red state: with the security of {@code act}
 *       taken out of force, the same statements read it and write one.</li>
 * </ul>
 *
 * <p>Every removal is made by the tables' owner and put back in a {@code
 * finally}, and the green state after it shows the removal was the cause.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ActTenancyIT {

    private static final String SELECTOR = "sprint";

    /** A foreign act, as a statement past the ORM writes it. */
    private static final String FOREIGN_ACT = "INSERT INTO dispatch.act (tenant_id, scope_id, "
        + "instance_id, sequence, kind, actor_subject, permission, path, at, to_state) "
        + "VALUES (?, ?, ?, 99, 'refer', 'probe', 'dispatch.instance.start', 'interface', now(), "
        + "'running')";

    @Inject ActService acts;
    @Inject ActRepository repository;
    @Inject TenantContext tenantContext;

    private final UUID scope = UUID.randomUUID();
    private UUID tenantA;
    private UUID tenantB;
    private InstanceFixture.Planted ofB;
    private long actOfA;
    private long actOfB;

    /** Fresh tenants with an instance and an act each, so no count depends on another test. */
    @BeforeEach
    void plant() throws Exception {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        InstanceFixture.Planted ofA = InstanceFixture.plant(tenantA, scope, SELECTOR);
        ofB = InstanceFixture.plant(tenantB, scope, SELECTOR);
        actOfA = startIn(tenantA, ofA);
        actOfB = startIn(tenantB, ofB);
    }

    // -----------------------------------------------------------------------
    // The discriminator, with the row-level security out of the way
    // -----------------------------------------------------------------------

    @Test
    void the_discriminator_alone_keeps_another_tenants_act_unread_and_unwritten()
            throws Exception {
        try (AutoCloseable ignored = tenantContext.bind(tenantA)) {
            assertThat(repository.find(scope, actOfA)).as("A reads its own act").isPresent();
            assertNeitherReadNorWritten();
        }

        try (Connection c = Db.asService()) {
            try {
                Db.switchPolicyAsOwner(c, "ALTER TABLE dispatch.act DISABLE ROW LEVEL SECURITY");
                Db.switchPolicyAsOwner(c,
                    "ALTER TABLE dispatch.instance DISABLE ROW LEVEL SECURITY");

                try (AutoCloseable ignored = tenantContext.bind(tenantA)) {
                    assertNeitherReadNorWritten();
                }

                Db.bindTenant(c, tenantA);
                assertThat(visible(c, actOfB))
                    .as("RED STATE, observed: in the same state, a statement past the "
                        + "discriminator under the service role bound to A reads B's act, so the "
                        + "emptiness above was the discriminator")
                    .isEqualTo(1);
                assertThat(writtenForB(c))
                    .as("RED STATE, observed: and writes an act of B, so the refusal above was "
                        + "the discriminator")
                    .isEqualTo(1);
                c.rollback();
            } finally {
                Db.switchPolicyAsOwner(c,
                    "ALTER TABLE dispatch.instance ENABLE ROW LEVEL SECURITY");
                Db.switchPolicyAsOwner(c, "ALTER TABLE dispatch.act ENABLE ROW LEVEL SECURITY");
            }
            Db.bindTenant(c, tenantA);
            assertThat(visible(c, actOfB))
                .as("and restored: past the discriminator, the security holds again")
                .isZero();
        }
    }

    // -----------------------------------------------------------------------
    // The row-level security, with the discriminator bypassed
    // -----------------------------------------------------------------------

    @Test
    void the_row_level_security_alone_keeps_another_tenants_act_unread_and_unwritten()
            throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            assertThat(visible(c, actOfA)).as("A reads its own act past the ORM").isEqualTo(1);
            assertThat(visible(c, actOfB)).as("and not B's").isZero();
            assertThatThrownBy(() -> writtenForB(c))
                .as("and writes no act of B: the policy's WITH CHECK refuses it")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("row-level security");
            c.rollback();

            try {
                Db.switchPolicyAsOwner(c, "ALTER TABLE dispatch.act DISABLE ROW LEVEL SECURITY");
                Db.bindTenant(c, tenantA);
                assertThat(visible(c, actOfB))
                    .as("RED STATE, observed: with the security of act out of force, the service "
                        + "role bound to A reads B's act")
                    .isEqualTo(1);
                assertThat(writtenForB(c))
                    .as("RED STATE, observed: and writes an act of B")
                    .isEqualTo(1);
                c.rollback();
            } finally {
                Db.switchPolicyAsOwner(c, "ALTER TABLE dispatch.act ENABLE ROW LEVEL SECURITY");
            }
            Db.bindTenant(c, tenantA);
            assertThat(visible(c, actOfB))
                .as("and restored: the isolation is back, so the red state was the removal")
                .isZero();
        }
    }

    // -----------------------------------------------------------------------

    /** Under A, through the ORM: B's act is not read, and B's instance takes no act. */
    private void assertNeitherReadNorWritten() {
        assertThat(repository.find(scope, actOfB))
            .as("A reads no act of B, though the scope and the surrogate are B's")
            .isEmpty();
        long instanceOfB = ofB.instance();
        Provenance provenance = provenance();
        assertThatThrownBy(() -> acts.write(scope, instanceOfB, Act.Kind.REFER,
                Act.State.RUNNING, null, provenance))
            .as("and writes no act to B's instance: for A there is no such instance")
            .isInstanceOfSatisfying(DispatchException.class, refused ->
                assertThat(refused.reason()).isEqualTo(DispatchException.Reason.NOT_FOUND));
        assertThat(InstanceFixture.actsOf(ofB.instance()))
            .as("and B's instance holds its one act still").isEqualTo(1);
    }

    private long startIn(UUID tenant, InstanceFixture.Planted planted) throws Exception {
        try (AutoCloseable ignored = tenantContext.bind(tenant)) {
            return acts.write(scope, planted.instance(), Act.Kind.START, Act.State.RUNNING, null,
                provenance()).id();
        }
    }

    private static Provenance provenance() {
        return new Provenance("delegate-of-ada", "ada", DispatchPermission.INSTANCE_START,
            Provenance.Path.INTERFACE, null);
    }

    /** The acts of this surrogate the session sees. */
    private static long visible(Connection c, long act) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(
                "SELECT count(*) FROM dispatch.act WHERE id = ?")) {
            st.setLong(1, act);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Writes an act of B to B's instance on this session, inside its open transaction. */
    private int writtenForB(Connection c) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(FOREIGN_ACT)) {
            st.setObject(1, tenantB);
            st.setObject(2, scope);
            st.setLong(3, ofB.instance());
            return st.executeUpdate();
        }
    }
}
