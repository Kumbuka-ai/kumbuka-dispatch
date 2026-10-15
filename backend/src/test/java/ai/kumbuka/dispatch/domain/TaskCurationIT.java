/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.repository.ScopeAccessRepository;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ai.kumbuka.dispatch.domain.TaskStage.C;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rendering of {@code curated_in} in a head: the target's complete
 * address, and only where the caller may see the target's scope.
 *
 * <p>The rule of the running service, carried unchanged: the slug comes from
 * the subject-filtered directory view. The verb surface binds the caller's
 * subject in the transaction that calls the kernel; this test does the same,
 * on the tenant and subject the substrate publishes a directory for.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskCurationIT {

    static final UUID SCOPE = UUID.fromString(SubstrateDatabaseResource.SCOPE_ID);
    static final UUID TENANT = UUID.fromString(SubstrateDatabaseResource.TENANT_ID);

    /** Its own selector, so this test's numbers are its own in the shared tenant. */
    static final String SELECTOR = "curation-probe";

    /** The listing's own selector, for the same reason. */
    static final String LISTED = "curation-listing";

    @Inject TaskService tasks;
    @Inject ScopeAccessRepository scopes;
    @Inject TenantContext tenantContext;

    @Test
    void a_curation_target_is_named_to_a_caller_who_sees_its_scope_and_to_no_other()
            throws Exception {
        PlatformFixture.grantDirectoryAccess();
        DomainFixture.declareSelector(TENANT, SCOPE, SELECTOR);
        try (AutoCloseable ignored = tenantContext.bind(TENANT)) {
            TaskStage stage = new TaskStage(tasks, SCOPE);
            TaskStage.Staged target = stage.open(SELECTOR);
            TaskStage.Staged closed = stage.closed(SELECTOR);
            tasks.relate(SCOPE, closed.address(), SCOPE, target.address(), stage.token(closed), C);

            assertThat(readAs(SubstrateDatabaseResource.PROBE_SUBJECT, closed).curatedIn())
                .isEqualTo(target.address().complete(SubstrateDatabaseResource.PROBE_SCOPE_SLUG));
            assertThat(readAs("no-member-of-this-tenant", closed).curatedIn())
                .as("a caller the directory shows no scope to is not told the address")
                .isNull();
        }
    }

    /**
     * A listing names the curation targets of a page as a read names each:
     * read for the page in one statement, under the same subject filter.
     */
    @Test
    void a_listing_names_curation_targets_as_a_read_does_and_to_no_other_caller()
            throws Exception {
        PlatformFixture.grantDirectoryAccess();
        DomainFixture.declareSelector(TENANT, SCOPE, LISTED);
        try (AutoCloseable ignored = tenantContext.bind(TENANT)) {
            TaskStage stage = new TaskStage(tasks, SCOPE);
            TaskStage.Staged target = stage.open(LISTED);
            List<ExchangeAddress> curated = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                TaskStage.Staged closed = stage.closed(LISTED);
                tasks.relate(SCOPE, closed.address(), SCOPE, target.address(),
                    stage.token(closed), C);
                curated.add(closed.address());
            }
            String complete = target.address().complete(SubstrateDatabaseResource.PROBE_SCOPE_SLUG);

            Map<ExchangeAddress, String> seen = listAs(SubstrateDatabaseResource.PROBE_SUBJECT);
            assertThat(curated).allSatisfy(address ->
                assertThat(seen.get(address)).as("curated_in of %s", address).isEqualTo(complete));
            assertThat(seen.get(target.address())).as("the target itself is curated nowhere")
                .isNull();
            assertThat(listAs("no-member-of-this-tenant").values())
                .as("a caller the directory shows no scope to is told no address")
                .allSatisfy(address -> assertThat(address).isNull());
        }
    }

    /** Every head of the listing's one page, by address, with its curation target. */
    private Map<ExchangeAddress, String> listAs(String subject) {
        return QuarkusTransaction.requiringNew().call(() -> {
            scopes.bindSubject(subject);
            Map<ExchangeAddress, String> byAddress = new java.util.HashMap<>();
            tasks.query(SCOPE, LISTED, TaskFilter.none(), TaskStage.page(200), C).tasks()
                .forEach(head -> byAddress.put(head.address(), head.curatedIn()));
            return byAddress;
        });
    }

    private TaskView readAs(String subject, TaskStage.Staged s) {
        return QuarkusTransaction.requiringNew().call(() -> {
            scopes.bindSubject(subject);
            return tasks.read(SCOPE, s.address(), C);
        });
    }
}
