/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.tenancy;

import org.eclipse.microprofile.config.ConfigProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Raw JDBC under a named role, for probes that need to see what a role can
 * actually do.
 *
 * <p>The probes deliberately go around the ORM. Layer 1 — the Hibernate
 * tenant filter — rewrites every query it routes, so a statement that goes
 * through it can never demonstrate what layer 2 does on its own. Raw SQL is
 * the only way to ask the database directly, and asking the database directly
 * is the whole point of a policy that exists because raw SQL is possible.
 */
final class Db {

    private Db() {
    }

    /** The container superuser. Stages fixtures; never what the service uses. */
    static Connection asAdmin() throws SQLException {
        return connect(config("test.db.admin.username"), config("test.db.admin.password"));
    }

    /** CREATEROLE and nothing more — the role the migration set runs under. */
    static Connection asMigrator() throws SQLException {
        return connect(SubstrateDatabaseResource.MIGRATOR_ROLE,
            SubstrateDatabaseResource.MIGRATOR_PASSWORD);
    }

    static Connection asService() throws SQLException {
        return connect(SubstrateDatabaseResource.SERVICE_ROLE,
            SubstrateDatabaseResource.SERVICE_PASSWORD);
    }

    static Connection asProvider() throws SQLException {
        return connect(SubstrateDatabaseResource.PROVIDER_ROLE,
            SubstrateDatabaseResource.PROVIDER_PASSWORD);
    }

    private static Connection connect(String user, String password) throws SQLException {
        Connection c = DriverManager.getConnection(config("test.db.url"), user, password);
        c.setAutoCommit(false);
        return c;
    }

    private static String config(String key) {
        return ConfigProvider.getConfig().getValue(key, String.class);
    }

    /**
     * Bind, or deliberately fail to bind, the tenant GUC on this connection.
     * A null tenant resets it, which is how the fail-closed half of the
     * probes reaches the state a forgotten binding would produce.
     */
    static void bindTenant(Connection c, UUID tenant) throws SQLException {
        try (Statement s = c.createStatement()) {
            if (tenant == null) {
                s.execute("RESET app.tenant_id");
            } else {
                s.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
            }
        }
    }

    static long countTasks(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM dispatch.task")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /**
     * Switches row-level security on a table, under the role that may: its
     * OWNER, which is the migrator and no longer the service.
     *
     * <p>The probes that stage a red state by turning a policy off used to
     * issue the statement on their own connection, because the service role
     * owned its tables and an owner may {@code DISABLE} or {@code NO FORCE}
     * them. It owns nothing now — enumerated grants carry no such right, and
     * the statement is refused with {@code 42501}. That refusal is a property
     * worth having and it has its own assertion; what it must not do is take
     * the isolation measurements down with it, because those are about
     * whether the LAYERS work and not about who may reconfigure them.
     *
     * <p>So the switch moves to the owner and the measurement stays where it
     * was. Both connections are separate on purpose: a probe that measured
     * under the same role that could reconfigure the policy would be measuring
     * the wrong role.
     *
     * <p>The measuring connection is passed in and released first. Switching a
     * policy takes ACCESS EXCLUSIVE on the table, and a probe that has just
     * read it is holding ACCESS SHARE — from a second connection that is not a
     * deadlock but a hang, and it would hang in the {@code finally} that puts
     * the policy back, where it is hardest to read. Releasing here rather than
     * at each call site is deliberate: there is no call site that may forget.
     */
    static void switchPolicyAsOwner(Connection measuring, String ddl) throws SQLException {
        measuring.rollback();
        try (Connection c = asMigrator()) {
            exec(c, ddl);
            c.commit();
        }
    }

    /**
     * Insert a task directly, bypassing the ORM, under whatever tenant
     * the setting currently names. Used to plant rows a later read must or
     * must not see.
     *
     * <p>Going around the ORM is the point: layer 1 rewrites every statement
     * it builds, so a row planted through it could never demonstrate what
     * layer 2 does on its own.
     *
     * <p>The chain a task of the store line hangs on is inserted for each
     * task on the fly -- a selector, a version of its definition and an
     * instance -- because each is a foreign key of the next, and a row
     * planted without them is refused by the reference: the probe would never
     * reach the policy behaviour it is here to measure. The scope is a random
     * UUID (this is a tenancy probe, not a scope probe) and every planted task
     * gets its own; two rows never sharing a scope keeps the probe honest to
     * what it measures. The title stands in the task's projection.
     */
    static long insertTask(Connection c, UUID tenant, String title) throws SQLException {
        UUID scopeId = UUID.randomUUID();
        long selector = returning(c, """
            INSERT INTO dispatch.selector (tenant_id, scope_id, name)
            VALUES (?::uuid, ?::uuid, 'sprint') RETURNING id
            """, tenant, scopeId);
        long version = returning(c, """
            INSERT INTO dispatch.definition_version (tenant_id, scope_id, selector_id, version,
                verdict_required, outcome_required, state)
            VALUES (?::uuid, ?::uuid, %d, 1, false, false, 'active') RETURNING id
            """.formatted(selector), tenant, scopeId);
        long instance = returning(c, """
            INSERT INTO dispatch.instance (tenant_id, scope_id, selector_id, number,
                definition_version_id)
            VALUES (?::uuid, ?::uuid, %d, 1, %d) RETURNING id
            """.formatted(selector, version), tenant, scopeId);
        try (var st = c.prepareStatement("""
                INSERT INTO dispatch.task (tenant_id, scope_id, instance_id, sub, title)
                VALUES (?::uuid, ?::uuid, ?, 1, ?)
                RETURNING id
                """)) {
            st.setString(1, tenant.toString());
            st.setString(2, scopeId.toString());
            st.setLong(3, instance);
            st.setString(4, title);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** One insert under a tenant and a scope; answers the surrogate it drew. */
    private static long returning(Connection c, String sql, UUID tenant, UUID scopeId)
            throws SQLException {
        try (var st = c.prepareStatement(sql)) {
            st.setString(1, tenant.toString());
            st.setString(2, scopeId.toString());
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
