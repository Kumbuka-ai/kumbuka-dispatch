/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.surface.InstanceWire;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A receipt is 32 random bytes, base64url without padding, and is stored only
 * as its SHA-256 (C10.5).
 *
 * <p>The expectation is written here from the rule: the form of the text, the
 * length of what it decodes to, and the digest computed in this test, not by
 * the service's own hash. That no column holds the receipt itself is read off
 * every text column of every table of the store, as the administrator sees
 * them.
 *
 * <p>The red state is observed on every build: the sweep that finds no
 * receipt finds its stored form, so a receipt planted as its own stored form
 * would be found by it in {@code receipt.receipt_hash}, and the comparison
 * with the digest computed here would fail on the stored value.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ReceiptIT {

    @Inject HoldingService holdings;
    @Inject TaskService tasks;
    @Inject InstanceService instances;

    @Test
    void a_receipt_is_thirty_two_random_bytes_in_base64url_without_padding() {
        String first = HoldingFixture.claim(holdings, HoldingFixture.EXECUTOR,
            HoldingFixture.sent(instances, tasks, "receipt-form")).standing().receipt();
        String second = HoldingFixture.claim(holdings, HoldingFixture.EXECUTOR,
            HoldingFixture.sent(instances, tasks, "receipt-form")).standing().receipt();

        for (String receipt : List.of(first, second)) {
            assertThat(receipt).as("base64url without padding").matches("[A-Za-z0-9_-]{43}");
            assertThat(Base64.getUrlDecoder().decode(receipt)).as("32 bytes").hasSize(32);
        }
        assertThat(first).as("two claims, two receipts").isNotEqualTo(second);
    }

    @Test
    void the_store_holds_the_sha256_of_the_receipt_and_never_the_receipt() {
        TaskAddress task = HoldingFixture.sent(instances, tasks, "receipt-stored");
        String receipt = HoldingFixture.claim(holdings, HoldingFixture.EXECUTOR, task).standing()
            .receipt();

        List<Map<String, Object>> stored = InstanceWire.rows("SELECT r.receipt_hash FROM "
            + "dispatch.receipt r JOIN dispatch.holding h ON h.id = r.holding_id "
            + "WHERE h.task_id = ?", HoldingFixture.taskId(task));
        assertThat(stored).extracting(row -> row.get("receipt_hash"))
            .as("the hex SHA-256 of the receipt, computed here")
            .containsExactly(sha256(receipt));
        List<Map<String, Object>> columns = InstanceWire.rows("SELECT table_name, column_name "
            + "FROM information_schema.columns WHERE table_schema = 'dispatch' "
            + "AND data_type IN ('text', 'character varying', 'jsonb')");
        assertThat(columns)
            .as("RED STATE, observed: the same sweep finds the stored form of the receipt in "
                + "receipt.receipt_hash, so it finds a value the store holds, and would find "
                + "the receipt were it stored")
            .anyMatch(column -> holds(column, sha256(receipt)));
        assertThat(columns).as("no text column of the store holds the receipt itself")
            .noneMatch(column -> holds(column, receipt));
    }

    /** Whether a text column of the store holds the value anywhere in it. */
    private static boolean holds(Map<String, Object> column, String value) {
        return InstanceWire.single("SELECT count(*) FROM dispatch.\"" + column.get("table_name")
            + "\" WHERE position(? in CAST(\"" + column.get("column_name") + "\" AS text)) > 0",
            value) > 0;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
