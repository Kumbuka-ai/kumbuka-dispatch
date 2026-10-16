/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.surface.SurfaceFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static ai.kumbuka.dispatch.domain.TaskStage.C;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Two writers present the same conflict token: exactly one writes, and the
 * other is refused with {@code CONFLICT_TOKEN_STALE} and the task as it now
 * stands (C10.2, DEC-0041).
 *
 * <p>The comparison of the presented token and the write happen under the
 * row lock, refreshed under it (C11.1). The first case makes the two writers
 * overlap for real: the first holds the lock in an open transaction while the
 * second is started, and the database is asked whether the second waits on
 * the first ({@link LockWaits}) before the first commits. Without the lock the
 * second reads the committed row, finds its token current, and writes too.
 *
 * <p>The second case is the wire: the refusal carries {@code address}, {@code
 * conflict_token}, {@code fields} and {@code service} with status 412, the
 * token being the one the winner's answer handed out.
 *
 * <p>RED STATE, observed with the update reading the task without the lock
 * (a read, then a compare, then a write): the second writer wrote as well,
 * and the first case failed on the refusal it expected.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ConcurrentWriteIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final String SELECTOR = "sprint";

    @Inject TaskService tasks;
    @Inject TenantContext tenantContext;
    @Inject EntityManager em;
    @Inject TestIdentityAssociation identity;

    @Test
    void two_writers_with_one_token_exactly_one_writes() throws Exception {
        UUID tenant = UUID.randomUUID();
        DomainFixture.declareSelector(tenant, SCOPE, SELECTOR);
        TaskView draft;
        try (AutoCloseable ignored = tenantContext.bind(tenant)) {
            draft = tasks.create(SCOPE, SELECTOR, null,
                new TaskService.Draft("contested", "code", "the commission", null), C,
                IdempotencyKey.NONE);
        }
        String token = draft.conflictToken();
        ExchangeAddress address = draft.address();

        ExecutorService second = Executors.newSingleThreadExecutor();
        Future<TaskView> secondWrite;
        try (AutoCloseable ignored = tenantContext.bind(tenant)) {
            QuarkusTransaction.begin();
            try {
                tasks.update(SCOPE, address, new TaskService.Draft("first", null, null, null),
                    token, C);
                int holder = LockWaits.sessionOf(em);

                secondWrite = second.submit(() -> onItsOwnThread(tenant, () ->
                    tasks.update(SCOPE, address,
                        new TaskService.Draft("second", null, null, null), token, C)));

                assertThat(LockWaits.waitsOn(holder, secondWrite))
                    .as("the second writer met the first one's lock; otherwise this case "
                        + "shows nothing about the two overlapping")
                    .isTrue();
            } finally {
                QuarkusTransaction.commit();
            }
        }

        Throwable refused;
        try {
            refused = catchThrowable(() -> secondWrite.get(30, TimeUnit.SECONDS));
        } finally {
            second.shutdownNow();
        }
        assertThat(refused).as("the second writer must be refused, not write")
            .isInstanceOf(ExecutionException.class);
        assertThat(refused.getCause()).isInstanceOf(DispatchException.class);
        assertThat(((DispatchException) refused.getCause()).reason())
            .isEqualTo(DispatchException.Reason.CONFLICT_TOKEN_STALE);
        try (AutoCloseable ignored = tenantContext.bind(tenant)) {
            assertThat(tasks.read(SCOPE, address, C).title())
                .as("the first writer's change stands").isEqualTo("first");
        }
    }

    @Test
    void the_loser_is_told_the_task_as_it_now_stands_412() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
        String id = SurfaceFixture.idOf(SurfaceFixture.create(Map.of("title", "contested",
            "apparatus", "code")));
        String token = given().get(SurfaceFixture.item(id)).jsonPath()
            .getString("conflict_token");

        Response won = given().contentType(ContentType.JSON).header("If-Match", "\"" + token + "\"")
            .body(Map.of("fields", Map.of("title", "won"))).patch(SurfaceFixture.item(id));
        Response lost = given().contentType(ContentType.JSON).header("If-Match", "\"" + token + "\"")
            .body(Map.of("fields", Map.of("title", "lost"))).patch(SurfaceFixture.item(id));

        assertThat(won.statusCode()).as(won.asString()).isEqualTo(200);
        assertThat(lost.statusCode()).as(lost.asString()).isEqualTo(412);
        assertThat(lost.jsonPath().getString("reason")).isEqualTo("CONFLICT_TOKEN_STALE");
        assertThat(lost.jsonPath().getMap("data"))
            .containsKeys("address", "conflict_token", "fields", "service");
        assertThat(lost.jsonPath().getString("data.conflict_token"))
            .as("the token the winner's answer handed out")
            .isEqualTo(won.jsonPath().getString("conflict_token"));
        assertThat(lost.jsonPath().getString("data.address"))
            .isEqualTo(SurfaceFixture.address(id));
        assertThat(lost.jsonPath().getString("data.fields.title")).isEqualTo("won");
        assertThat(lost.jsonPath().getString("data.service")).isEqualTo("dispatch");
    }

    // -----------------------------------------------------------------------

    /**
     * Runs one call in a transaction of its own, on this thread, with a request
     * context and tenant binding of its own.
     */
    private <T> T onItsOwnThread(UUID tenant, java.util.concurrent.Callable<T> call)
            throws Exception {
        var request = Arc.container().requestContext();
        request.activate();
        try (AutoCloseable ignored = tenantContext.bind(tenant)) {
            return call.call();
        } finally {
            request.terminate();
        }
    }
}
