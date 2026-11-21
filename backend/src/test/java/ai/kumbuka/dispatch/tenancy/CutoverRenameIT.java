/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.tenancy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static ai.kumbuka.dispatch.tenancy.CutoverStock.LEGACY;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.NOT_REFUSED;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.OLD_SCHEMA;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.RENAME;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.REVERSE;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.SCOPE_A;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.SCOPE_B;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.TENANT_A;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rename of the cut-over (migration/cutover-rename.sql) and its way back
 * (migration/cutover-rename-reverse.sql), each on a database of its own with
 * the old line applied, run by the role that owns the schema.
 *
 * <p>What the rename and the way back must leave is compared with the catalog
 * and the rows as they stood before the rename: every relation, column,
 * constraint, index, policy, trigger, function, owner and privilege, and the
 * digest of every table.
 */
class CutoverRenameIT {

    private static final String MARK = "cutover";

    private static MigrationHarness harness;

    @BeforeAll
    static void start() throws SQLException {
        harness = MigrationHarness.start();
        harness.createMigrator(CutoverStock.MIGRATOR, CutoverStock.MIGRATOR_PASSWORD,
            "CREATEROLE NOSUPERUSER NOBYPASSRLS");
    }

    @AfterAll
    static void stop() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void the_rename_and_its_way_back_leave_the_stock_and_the_schema_as_they_were()
            throws SQLException {
        String url = CutoverStock.oldStore(harness, "rename_way_back");
        List<String> catalogueBefore;
        Map<String, String> rowsBefore;
        try (Connection admin = harness.adminConnection(url)) {
            CutoverStock.stage(admin, TENANT_A, SCOPE_A, "a");
            CutoverStock.stage(admin, TENANT_B, SCOPE_B, "b");
            catalogueBefore = CutoverStock.catalogue(admin, OLD_SCHEMA);
            rowsBefore = CutoverStock.rows(admin, OLD_SCHEMA);
        }

        String renamed;
        String renamedAgain;
        try (Connection owner = CutoverStock.asMigrator(url)) {
            renamed = CutoverStock.outcome(owner, RENAME);
            renamedAgain = CutoverStock.outcome(owner, RENAME);
        }

        try (Connection admin = harness.adminConnection(url)) {
            List<String> catalogueRenamed = CutoverStock.catalogue(admin, LEGACY);
            Map<String, String> rowsRenamed = new TreeMap<>(CutoverStock.rows(admin, LEGACY));

            assertThat(renamed).as("the rename ran").isEqualTo(NOT_REFUSED);
            assertThat(CutoverStock.scalar(admin, "SELECT to_regnamespace('dispatch') IS NULL"))
                .as("no schema dispatch stands after the rename").isEqualTo("t");
            assertThat(catalogueRenamed.stream().filter(fact -> fact.contains(MARK)).toList())
                .as("the mark is a table cutover with the one column opened_at")
                .anyMatch(fact -> fact.startsWith("relation cutover kind=r"))
                .anyMatch(fact -> fact.startsWith("column cutover.opened_at timestamp with time zone"))
                .hasSize(2);
            assertThat(CutoverStock.scalar(admin, "SELECT count(*) || ' ' || count(opened_at) "
                    + "FROM dispatch_legacy.cutover"))
                .as("holding exactly one row whose opened_at is empty").isEqualTo("1 0");
            assertThat(catalogueRenamed.stream().filter(fact -> !fact.contains(MARK))
                    .map(fact -> fact.replace(LEGACY + ".", OLD_SCHEMA + ".")).toList())
                .as("beside the mark, the renamed schema carries every relation, column, "
                    + "constraint, index, policy, trigger, function, owner and privilege of the "
                    + "old store")
                .isEqualTo(catalogueBefore);
            rowsRenamed.remove(MARK);
            assertThat(rowsRenamed).as("and every row of the stock").isEqualTo(rowsBefore);
        }

        assertThat(renamedAgain)
            .as("a second rename is refused: dispatch_legacy stands already")
            .isEqualTo("cutover rename refused: the schema dispatch_legacy exists already");

        String backBesideANewStore;
        String backAfterOpening;
        String back;
        try (Connection owner = CutoverStock.asMigrator(url)) {
            CutoverStock.exec(owner, "CREATE SCHEMA dispatch");
            backBesideANewStore = CutoverStock.outcome(owner, REVERSE);
            CutoverStock.exec(owner, "DROP SCHEMA dispatch");

            CutoverStock.exec(owner, "UPDATE dispatch_legacy.cutover SET opened_at = now()");
            backAfterOpening = CutoverStock.outcome(owner, REVERSE);
            CutoverStock.exec(owner, "UPDATE dispatch_legacy.cutover SET opened_at = NULL");

            back = CutoverStock.outcome(owner, REVERSE);
        }

        assertThat(backBesideANewStore)
            .as("the way back is refused while a schema dispatch stands")
            .isEqualTo("cutover way back refused: a schema dispatch exists; drop the new store "
                + "first");
        assertThat(backAfterOpening)
            .as("and after the opening")
            .startsWith("cutover way back refused: the new store was opened at ");
        assertThat(back)
            .as("RED STATE, observed: once the new schema is gone and the mark is not opened, "
                + "the same way back runs")
            .isEqualTo(NOT_REFUSED);

        try (Connection admin = harness.adminConnection(url)) {
            assertThat(CutoverStock.catalogue(admin, OLD_SCHEMA))
                .as("after the way back the schema is as it was before the rename, the table "
                    + "cutover gone")
                .isEqualTo(catalogueBefore);
            assertThat(CutoverStock.rows(admin, OLD_SCHEMA))
                .as("and so is every row").isEqualTo(rowsBefore);
            assertThat(CutoverStock.scalar(admin, "SELECT to_regnamespace('dispatch_legacy') "
                    + "IS NULL AND to_regclass('dispatch.cutover') IS NULL"))
                .as("no dispatch_legacy and no table cutover remain").isEqualTo("t");
        }
    }

    @Test
    void the_rename_refuses_an_old_store_short_of_version_25() throws SQLException {
        String url = harness.freshDatabase("rename_short", CutoverStock.MIGRATOR);
        harness.migrateTo(url, CutoverStock.MIGRATOR, CutoverStock.MIGRATOR_PASSWORD, "24",
            new TenantMigrationCallback());

        String refused;
        try (Connection owner = CutoverStock.asMigrator(url)) {
            refused = CutoverStock.outcome(owner, RENAME);
        }
        try (Connection admin = harness.adminConnection(url)) {
            assertThat(refused)
                .as("the rename refuses an old store that stands at 24")
                .isEqualTo("cutover rename refused: the old store stands at version 24, "
                    + "expected 25");
            assertThat(CutoverStock.scalar(admin, "SELECT to_regnamespace('dispatch') IS NOT NULL "
                    + "AND to_regnamespace('dispatch_legacy') IS NULL"))
                .as("and renames nothing").isEqualTo("t");
        }

        harness.migrate(url, CutoverStock.MIGRATOR, CutoverStock.MIGRATOR_PASSWORD,
            new TenantMigrationCallback());
        try (Connection owner = CutoverStock.asMigrator(url)) {
            assertThat(CutoverStock.outcome(owner, RENAME))
                .as("RED STATE, observed: at 25 the same rename runs")
                .isEqualTo(NOT_REFUSED);
        }
    }
}
