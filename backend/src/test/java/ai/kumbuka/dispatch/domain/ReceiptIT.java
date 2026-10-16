/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A receipt is 32 random bytes, base64url without padding, and is stored only
 * as its SHA-256 (C10.5).
 *
 * <p>The expectation is written here from the rule: the form of the text, the
 * length of what it decodes to, and the digest computed in this test, not by
 * the service's own hash. That no column holds the receipt itself is read off
 * every row the claim could have written, as the administrator sees them.
 *
 * <p>RED STATE, observed with the stored form planted as the receipt itself:
 * the digest case failed on the stored value, and the receipt was found in
 * the task's row.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ReceiptIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final String SELECTOR = "sprint";

    @Inject TaskService tasks;
    @Inject TenantContext tenantContext;

    private UUID tenant;
    private AutoCloseable binding;
    private TaskStage stage;

    @BeforeEach
    void freshTenant() {
        tenant = UUID.randomUUID();
        DomainFixture.declareSelector(tenant, SCOPE, SELECTOR);
        binding = tenantContext.bind(tenant);
        stage = new TaskStage(tasks, SCOPE);
    }

    @AfterEach
    void unbind() throws Exception {
        binding.close();
    }

    @Test
    void a_receipt_is_thirty_two_random_bytes_in_base64url_without_padding() {
        String first = stage.active(SELECTOR).receipt();
        String second = stage.active(SELECTOR).receipt();

        for (String receipt : new String[] {first, second}) {
            assertThat(receipt).as("base64url, no padding: 43 characters for 32 bytes")
                .matches("[A-Za-z0-9_-]{43}");
            assertThat(Base64.getUrlDecoder().decode(receipt)).hasSize(32);
        }
        assertThat(second).as("each claim its own").isNotEqualTo(first);
    }

    @Test
    void the_store_holds_the_sha256_of_the_receipt_and_never_the_receipt() throws Exception {
        TaskStage.Staged held = stage.active(SELECTOR);
        String receipt = held.receipt();

        assertThat(TaskStage.row(held.identity()).receiptHash())
            .as("the SHA-256 of the receipt's text, hex")
            .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(receipt.getBytes(StandardCharsets.UTF_8))));
        for (String table : new String[] {"task", "task_text", "task_idempotency_key"}) {
            assertThat(TaskStage.value("SELECT count(*) FROM dispatch." + table + " r "
                    + "WHERE r.tenant_id = '" + tenant + "' AND row_to_json(r)::text LIKE '%"
                    + receipt + "%'"))
                .as("no row of %s holds the receipt", table).isEqualTo("0");
        }
    }
}
