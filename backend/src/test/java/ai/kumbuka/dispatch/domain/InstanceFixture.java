/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import org.eclipse.microprofile.config.ConfigProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Plants what an act is written to, in the store line, by SQL: a selector, a
 * version of its definition, an activity of that version, and an instance of
 * the selector under that version.
 *
 * <p>Planted rather than made through a call, because no call that makes them
 * is bound yet; the writer of acts is what is under test, and it takes an
 * instance as given. Runs as the container's superuser, which also passes the
 * row-level security: the rows have to land under the tenant the test names.
 * Reads an act back the same way, so a test sees the row as the store holds
 * it and not as the mapping answers it.
 */
public final class InstanceFixture {

    private InstanceFixture() {
    }

    /** The planted chain, by the surrogates the store drew. */
    public record Planted(UUID tenant, UUID scope, long selector, long version, long activity,
                          long instance) {
    }

    /** A selector of the name given, a definition version 1, an activity and instance number 1. */
    public static Planted plant(UUID tenant, UUID scope, String selectorName) {
        try (Connection c = admin()) {
            long selector = insert(c, "INSERT INTO dispatch.selector (tenant_id, scope_id, name) "
                + "VALUES (?, ?, ?) RETURNING id", tenant, scope, selectorName);
            long version = insert(c, "INSERT INTO dispatch.definition_version "
                + "(tenant_id, scope_id, selector_id, version, verdict_required, outcome_required, "
                + "state) VALUES (?, ?, ?, 1, false, false, 'active') RETURNING id",
                tenant, scope, selector);
            long activity = insert(c, "INSERT INTO dispatch.activity "
                + "(tenant_id, scope_id, definition_version_id, name, verdict_required, "
                + "outcome_required, criteria_required, input_kind, may_ask, starts_excluded) "
                + "VALUES (?, ?, ?, 'review', false, false, false, 'none', false, false) "
                + "RETURNING id", tenant, scope, version);
            long instance = insert(c, "INSERT INTO dispatch.instance "
                + "(tenant_id, scope_id, selector_id, number, definition_version_id, title) "
                + "VALUES (?, ?, ?, 1, ?, 'planted') RETURNING id",
                tenant, scope, selector, version);
            return new Planted(tenant, scope, selector, version, activity, instance);
        } catch (SQLException e) {
            throw new IllegalStateException("the chain could not be planted", e);
        }
    }

    /** Every column of an act as the store holds it, by column name; empty where there is none. */
    public static Map<String, Object> actRow(long actId) {
        Map<String, Object> row = new LinkedHashMap<>();
        try (Connection c = admin();
             PreparedStatement st = c.prepareStatement("SELECT * FROM dispatch.act WHERE id = ?")) {
            st.setLong(1, actId);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnName(i), rs.getObject(i));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the act could not be read back", e);
        }
        return row;
    }

    /** The number of acts of an instance as the store holds them. */
    public static long actsOf(long instance) {
        try (Connection c = admin();
             PreparedStatement st = c.prepareStatement(
                 "SELECT count(*) FROM dispatch.act WHERE instance_id = ?")) {
            st.setLong(1, instance);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the acts could not be counted", e);
        }
    }

    /** The container's superuser, which the service never uses. */
    public static Connection admin() throws SQLException {
        var config = ConfigProvider.getConfig();
        return DriverManager.getConnection(config.getValue("test.db.url", String.class),
            config.getValue("test.db.admin.username", String.class),
            config.getValue("test.db.admin.password", String.class));
    }

    private static long insert(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                st.setObject(i + 1, values[i]);
            }
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
