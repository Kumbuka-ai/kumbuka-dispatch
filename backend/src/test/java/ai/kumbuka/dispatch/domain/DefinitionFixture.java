/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import org.jspecify.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Plants a definition in the store line by SQL, row by row: a selector, its
 * versions, and what a version declares -- enumerations with their values,
 * control fields, activities, outcome labels and contract rows.
 *
 * <p>Planted rather than made through a call, because versions enter the store
 * only as seeds and no call writes one. Runs as the container's superuser,
 * which also passes the row-level security: the rows land under the tenant
 * the fixture is made for. The planting is the expectation of a test that
 * reads a version, so every value a test checks is one written here.
 */
public final class DefinitionFixture {

    private final UUID tenant;
    private final UUID scope;

    /** Plants under one tenant, in one scope. */
    public DefinitionFixture(UUID tenant, UUID scope) {
        this.tenant = tenant;
        this.scope = scope;
    }

    /** A selector of this name. */
    public long selector(String name) {
        return insert("INSERT INTO dispatch.selector (tenant_id, scope_id, name) "
            + "VALUES (?, ?, ?) RETURNING id", name);
    }

    /** A version of a selector, its state and seed mark, and what its return needs. */
    public long version(long selector, int number, String state, boolean seed,
                        boolean verdictRequired, boolean outcomeRequired) {
        return insert("INSERT INTO dispatch.definition_version (tenant_id, scope_id, "
                + "selector_id, version, verdict_required, outcome_required, state, seed) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
            selector, number, verdictRequired, outcomeRequired, state, seed);
    }

    /**
     * An enumeration of a version with its values, in the order given, which
     * is the order of their ordinal. The values are inserted last first, so
     * that an answer in the order of insertion is not the order of the ordinal.
     */
    public long enumeration(long version, String name, List<String> values) {
        long enumeration = insert("INSERT INTO dispatch.enumeration (tenant_id, scope_id, "
            + "definition_version_id, name) VALUES (?, ?, ?, ?) RETURNING id", version, name);
        for (int ordinal = values.size(); ordinal >= 1; ordinal--) {
            insert("INSERT INTO dispatch.enumeration_value (tenant_id, scope_id, "
                    + "enumeration_id, value, ordinal) VALUES (?, ?, ?, ?, ?) RETURNING id",
                enumeration, values.get(ordinal - 1), ordinal);
        }
        return enumeration;
    }

    /**
     * A control field of a version's schema.
     *
     * @param enumeration the enumeration of a choice field; absent for every other type
     */
    public long field(long version, String name, String type, @Nullable Long enumeration) {
        return insert("INSERT INTO dispatch.control_field (tenant_id, scope_id, "
                + "definition_version_id, name, type, enumeration_id) "
                + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
            version, name, type, enumeration);
    }

    /** An activity of a version, what a delivery of its tasks needs, and its input. */
    public long activity(long version, String name, boolean verdictRequired,
                         boolean outcomeRequired, boolean criteriaRequired, String inputKind,
                         boolean mayAsk) {
        return insert("INSERT INTO dispatch.activity (tenant_id, scope_id, "
                + "definition_version_id, name, verdict_required, outcome_required, "
                + "criteria_required, input_kind, may_ask, starts_excluded) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, false) RETURNING id",
            version, name, verdictRequired, outcomeRequired, criteriaRequired, inputKind,
            mayAsk);
    }

    /** An outcome label of a whole version, at its place. */
    public void versionLabel(long version, String label, int ordinal) {
        insert("INSERT INTO dispatch.outcome_label (tenant_id, scope_id, definition_version_id, "
            + "label, ordinal) VALUES (?, ?, ?, ?, ?) RETURNING id", version, label, ordinal);
    }

    /** An outcome label of one activity, at its place. */
    public void activityLabel(long activity, String label, int ordinal) {
        insert("INSERT INTO dispatch.outcome_label (tenant_id, scope_id, activity_id, label, "
            + "ordinal) VALUES (?, ?, ?, ?, ?) RETURNING id", activity, label, ordinal);
    }

    /** A field of the start contract of a version. */
    public void startField(long version, long field, boolean required) {
        insert("INSERT INTO dispatch.contract_field (tenant_id, scope_id, definition_version_id, "
                + "control_field_id, required) VALUES (?, ?, ?, ?, ?) RETURNING id",
            version, field, required);
    }

    /** A field of the result contract of an activity of a version. */
    public void resultField(long version, long activity, long field, boolean required) {
        insert("INSERT INTO dispatch.contract_field (tenant_id, scope_id, definition_version_id, "
                + "activity_id, control_field_id, required) VALUES (?, ?, ?, ?, ?, ?) "
                + "RETURNING id",
            version, activity, field, required);
    }

    /** Sets the seed mark of a planted version, as the copy of the cut-over would. */
    public static void markSeed(long version, boolean seed) {
        try (Connection c = InstanceFixture.admin();
             PreparedStatement st = c.prepareStatement(
                 "UPDATE dispatch.definition_version SET seed = ? WHERE id = ?")) {
            st.setBoolean(1, seed);
            st.setLong(2, version);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("the seed mark could not be set", e);
        }
    }

    /**
     * The rows of every table of the schema, by table, as the store holds
     * them: what a test compares before and after a call that must write
     * nothing. The history of the migrations is left out; nothing a call does
     * writes there.
     */
    public static Map<String, Long> rowCounts() {
        Map<String, Long> counts = new TreeMap<>();
        try (Connection c = InstanceFixture.admin()) {
            List<String> tables = new ArrayList<>();
            try (PreparedStatement st = c.prepareStatement("SELECT table_name "
                    + "FROM information_schema.tables WHERE table_schema = 'dispatch' "
                    + "AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'");
                 ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String table : tables) {
                try (PreparedStatement st = c.prepareStatement(
                        "SELECT count(*) FROM dispatch." + table);
                     ResultSet rs = st.executeQuery()) {
                    rs.next();
                    counts.put(table, rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the rows could not be counted", e);
        }
        return counts;
    }

    /** One insert under this fixture's tenant and scope; answers the surrogate it drew. */
    private long insert(String sql, @Nullable Object... values) {
        try (Connection c = InstanceFixture.admin();
             PreparedStatement st = c.prepareStatement(sql)) {
            st.setObject(1, tenant);
            st.setObject(2, scope);
            for (int i = 0; i < values.length; i++) {
                st.setObject(i + 3, values[i]);
            }
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("a row of the definition could not be planted", e);
        }
    }
}
