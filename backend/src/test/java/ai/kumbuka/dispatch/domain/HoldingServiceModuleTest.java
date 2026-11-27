/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.repository.ActRepository;
import ai.kumbuka.dispatch.repository.HoldingRepository;
import ai.kumbuka.dispatch.repository.InstanceRepository;
import ai.kumbuka.dispatch.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The decisions of the holding of a task, against mocked repositories: what a
 * claim writes and answers, when a deferral admits a claim, where a lease may
 * end, and why a holder's call is refused. The integration tests hold the
 * same against the store; this one holds the decisions on their own, with a
 * clock that stands still.
 *
 * <p>Every expectation is written here from the order: thirty minutes without
 * a duration, a deferral admits a claim from its instant on, a lease ends
 * before the year 294276, and a holder's call by another is held, by the
 * former holder after its lease ended lapsed, and where nobody holds the task
 * absent.
 */
class HoldingServiceModuleTest {

    private static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00.000001Z");
    private static final Instant ACT_AT = Instant.parse("2026-11-27T10:00:00.000002Z");
    private static final Instant TOKEN = Instant.parse("2026-11-27T09:00:00Z");
    private static final TaskAddress ADDRESS =
        new TaskAddress(new InstanceAddress("probe", "sprint", 4), 1);
    private static final Actor ADA = new Actor("ada", DispatchPermission.TASK_EXECUTE);
    private static final Actor BOB = new Actor("bob", DispatchPermission.TASK_EXECUTE);
    private static final Provenance PROVENANCE = new Provenance("ada", null,
        DispatchPermission.TASK_EXECUTE, Provenance.Path.INTERFACE, null);
    private static final String RECEIPT = "the-receipt-of-ada";

    private TaskRepository tasks;
    private HoldingRepository holdings;
    private ActRepository actRows;
    private ActService acts;
    private HoldingService service;
    private Task task;

    @BeforeEach
    void wire() {
        tasks = Mockito.mock(TaskRepository.class);
        InstanceRepository instances = Mockito.mock(InstanceRepository.class);
        holdings = Mockito.mock(HoldingRepository.class);
        actRows = Mockito.mock(ActRepository.class);
        acts = Mockito.mock(ActService.class);
        service = new HoldingService(tasks, instances, holdings, actRows, acts,
            Clock.fixed(NOW, ZoneOffset.UTC));
        Instance instance = Instance.started(SCOPE, 1L, 4L, 21L, "a title");
        instance.id = 77L;
        when(instances.lockInstance(SCOPE, "sprint", 4L)).thenReturn(Optional.of(instance));
        task = withId(Task.drafted(SCOPE, 77L, 1), 300L);
        task.release();
        when(tasks.lockTask(SCOPE, 77L, 1)).thenReturn(Optional.of(task));
        when(actRows.lastTaskAt(SCOPE, 300L)).thenReturn(Optional.of(TOKEN));
        when(holdings.holdings(SCOPE, 300L)).thenReturn(List.of());
    }

    // -----------------------------------------------------------------------
    // claim
    // -----------------------------------------------------------------------

    @Test
    void a_claim_writes_a_holding_its_receipt_hash_and_a_lease_of_thirty_minutes() {
        Act claim = act(9L, ACT_AT);
        when(acts.write(SCOPE, 77L, Act.Kind.CLAIM, Act.State.ACTIVE, 300L, PROVENANCE))
            .thenReturn(claim);
        Holding opened = withId(new Holding(SCOPE, 300L, 9L), 5L);
        when(tasks.insert(any(Holding.class))).thenReturn(opened);
        ArgumentCaptor<Object> written = ArgumentCaptor.forClass(Object.class);

        TaskView answered = service.claim(ADA, ADDRESS, SCOPE,
            new HoldingService.Claim(null, IdempotencyKey.NONE), PROVENANCE);

        verify(tasks, Mockito.times(3)).insert(written.capture());
        assertThat(written.getAllValues()).extracting(row -> row.getClass().getSimpleName())
            .containsExactly("Holding", "IssuedReceipt", "Lease");
        Lease lease = (Lease) written.getAllValues().get(2);
        assertThat(lease.expiresAt()).as("thirty minutes after the act")
            .isEqualTo(Instant.parse("2026-11-27T10:30:00.000002Z"));
        IssuedReceipt stored = (IssuedReceipt) written.getAllValues().get(1);
        String receipt = answered.standing().receipt();
        assertThat(receipt).isNotNull();
        assertThat(stored.matches(receipt)).as("the stored row is the receipt handed out").isTrue();
        assertThat(task.state()).isEqualTo("active");
        verify(tasks, never()).spendKey(any());
    }

    @Test
    void a_claim_under_a_key_spends_it_on_its_act_after_removing_the_callers_expired_ones() {
        claimWritten();
        when(tasks.spendKey(any())).thenReturn(true);

        service.claim(ADA, ADDRESS, SCOPE, new HoldingService.Claim(Duration.ofHours(2),
            IdempotencyKey.of("once")), PROVENANCE);

        verify(tasks).deleteExpiredKeys(SCOPE, "ada", NOW.minus(Duration.ofHours(24)));
        ArgumentCaptor<IdempotencyRecord> key = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(tasks).spendKey(key.capture());
        assertThat(key.getValue().actId()).as("the key hangs on the act claim").contains(9L);
        assertThat(key.getValue().taskId()).isEmpty();
    }

    @Test
    void a_key_another_transaction_spent_first_is_refused_as_reused() {
        claimWritten();
        when(tasks.spendKey(any())).thenReturn(false);

        assertRefused(() -> service.claim(ADA, ADDRESS, SCOPE, new HoldingService.Claim(null,
            IdempotencyKey.of("raced")), PROVENANCE), DispatchException.Reason.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void a_deferral_refuses_a_claim_before_its_instant_and_admits_one_from_it_on() {
        task.defer(NOW.plusNanos(1000));
        assertRefused(() -> service.claim(ADA, ADDRESS, SCOPE,
                new HoldingService.Claim(null, IdempotencyKey.NONE), PROVENANCE),
            DispatchException.Reason.DEFERRAL_PENDING);
        verify(acts, never()).write(any(), anyLong(), any(), any(), any(), any());

        task.defer(NOW);
        claimWritten();
        service.claim(ADA, ADDRESS, SCOPE, new HoldingService.Claim(null, IdempotencyKey.NONE),
            PROVENANCE);
        assertThat(task.notBefore()).as("at its instant the claim is admitted, and spends it")
            .isEmpty();
    }

    @Test
    void a_claim_of_a_task_another_holds_is_held_and_of_a_draft_a_transition() {
        holdBy("bob", NOW.plusSeconds(60));
        task.claim();
        assertRefused(() -> service.claim(ADA, ADDRESS, SCOPE,
                new HoldingService.Claim(null, IdempotencyKey.NONE), PROVENANCE),
            DispatchException.Reason.CLAIM_HELD);

        Task draft = withId(Task.drafted(SCOPE, 77L, 1), 300L);
        when(tasks.lockTask(SCOPE, 77L, 1)).thenReturn(Optional.of(draft));
        assertRefused(() -> service.claim(ADA, ADDRESS, SCOPE,
                new HoldingService.Claim(null, IdempotencyKey.NONE), PROVENANCE),
            DispatchException.Reason.TRANSITION_NOT_PERMITTED);
        verify(acts, never()).write(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void a_lease_ends_before_the_year_294276() {
        Instant late = Instant.parse("+294275-12-31T23:00:00Z");

        assertThat(HoldingService.leaseEnd(ACT_AT, null))
            .isEqualTo(Instant.parse("2026-11-27T10:30:00.000002Z"));
        assertThat(HoldingService.leaseEnd(late, Duration.ofMinutes(59)))
            .isEqualTo(Instant.parse("+294275-12-31T23:59:00Z"));
        assertRefused(() -> HoldingService.leaseEnd(late, Duration.ofHours(1)),
            DispatchException.Reason.LEASE_END_INVALID);
        assertRefused(() -> HoldingService.leaseEnd(late, Duration.ofSeconds(Long.MAX_VALUE)),
            DispatchException.Reason.LEASE_END_INVALID);
    }

    @Test
    void the_digest_of_a_claim_tells_its_task_and_its_duration_apart() {
        HoldingService.Claim plain = new HoldingService.Claim(null, IdempotencyKey.of("k"));
        String digest = HoldingService.digest(ADDRESS, plain);

        assertThat(HoldingService.digest(ADDRESS, new HoldingService.Claim(null,
            IdempotencyKey.of("other")))).as("the key is no argument").isEqualTo(digest);
        assertThat(HoldingService.digest(ADDRESS, new HoldingService.Claim(
            Duration.ofMinutes(30), IdempotencyKey.of("k")))).as("a duration named is another")
            .isNotEqualTo(digest);
        assertThat(HoldingService.digest(new TaskAddress(ADDRESS.instance(), 2), plain))
            .isNotEqualTo(digest);
    }

    // -----------------------------------------------------------------------
    // The holder's calls
    // -----------------------------------------------------------------------

    @Test
    void release_ends_the_holding_opens_the_task_and_keeps_the_remark() {
        Holding held = holdBy("ada", NOW.plusSeconds(60));
        task.claim();
        Act release = act(11L, ACT_AT);
        when(acts.write(SCOPE, 77L, Act.Kind.RELEASE, Act.State.OPEN, 300L, PROVENANCE))
            .thenReturn(release);
        ArgumentCaptor<Object> written = ArgumentCaptor.forClass(Object.class);

        TaskView released = service.release(ADA, ADDRESS, SCOPE,
            new HoldingService.Release(RECEIPT, "a remark"), PROVENANCE);

        assertThat(held.open()).as("the act release ended the holding").isFalse();
        assertThat(task.state()).isEqualTo("open");
        verify(tasks).insert(written.capture());
        assertThat(written.getValue()).isInstanceOf(Content.class);
        assertThat(released.standing().leaseExpiresAt()).isNull();
    }

    @Test
    void renew_writes_a_lease_from_its_act_and_defer_the_instant_on_act_and_task() {
        holdBy("ada", NOW.plusSeconds(60));
        task.claim();
        Act renew = act(12L, ACT_AT);
        when(acts.write(SCOPE, 77L, Act.Kind.RENEW, Act.State.ACTIVE, 300L, PROVENANCE))
            .thenReturn(renew);
        ArgumentCaptor<Object> written = ArgumentCaptor.forClass(Object.class);

        service.renew(ADA, ADDRESS, SCOPE, new HoldingService.Renew(RECEIPT, TOKEN.toString(),
            Duration.ofHours(1)), PROVENANCE);
        verify(tasks).insert(written.capture());
        assertThat(((Lease) written.getValue()).expiresAt())
            .isEqualTo(Instant.parse("2026-11-27T11:00:00.000002Z"));

        Instant until = Instant.parse("2099-01-01T00:00:00Z");
        Act defer = act(13L, ACT_AT);
        when(acts.writeDeferral(SCOPE, 77L, 300L, until, PROVENANCE)).thenReturn(defer);
        TaskView deferred = service.defer(ADA, ADDRESS, SCOPE, new HoldingService.Defer(RECEIPT,
            TOKEN.toString(), until, null), PROVENANCE);
        assertThat(task.notBefore()).contains(until);
        assertThat(deferred.standing().deferralPending()).isTrue();
        assertThat(deferred.open()).as("a deferred task offers no claim yet").isEmpty();
    }

    @Test
    void a_holders_call_is_refused_for_its_receipt_its_token_and_whoever_holds_the_task() {
        holdBy("ada", NOW.plusSeconds(60));
        task.claim();
        assertRefused(() -> renew(ADA, "another-receipt", TOKEN.toString()),
            DispatchException.Reason.RECEIPT_MISMATCH);
        assertRefused(() -> renew(ADA, RECEIPT, "2001-01-01T00:00:00Z"),
            DispatchException.Reason.CONFLICT_TOKEN_STALE);
        assertRefused(() -> renew(ADA, RECEIPT, null),
            DispatchException.Reason.CONFLICT_TOKEN_MISSING);
        assertRefused(() -> renew(BOB, RECEIPT, TOKEN.toString()),
            DispatchException.Reason.CLAIM_HELD);

        holdBy("ada", NOW);
        assertRefused(() -> renew(ADA, RECEIPT, TOKEN.toString()),
            DispatchException.Reason.LEASE_LAPSED);
        assertRefused(() -> renew(BOB, RECEIPT, TOKEN.toString()),
            DispatchException.Reason.CLAIM_REQUIRED);

        when(holdings.holdings(SCOPE, 300L)).thenReturn(List.of());
        task.release();
        assertRefused(() -> service.release(ADA, ADDRESS, SCOPE,
                new HoldingService.Release(RECEIPT, null), PROVENANCE),
            DispatchException.Reason.CLAIM_REQUIRED);
        verify(acts, never()).write(any(), anyLong(), any(), any(), any(), any());
        verify(tasks, never()).insert(any());
    }

    // -----------------------------------------------------------------------

    /** An open holding of the task by a claimant, with its lease ending then and the receipt. */
    private Holding holdBy(String claimant, Instant leaseEnd) {
        Holding holding = withId(new Holding(SCOPE, 300L, 9L), 5L);
        when(holdings.holdings(SCOPE, 300L)).thenReturn(List.of(holding));
        when(holdings.youngestLease(SCOPE, 5L))
            .thenReturn(Optional.of(new Lease(SCOPE, 5L, 9L, leaseEnd)));
        when(holdings.youngestReceipt(eq(SCOPE), eq(5L)))
            .thenReturn(Optional.of(new IssuedReceipt(SCOPE, 5L, RECEIPT, NOW)));
        Act claim = Mockito.mock(Act.class);
        when(claim.actorSubject()).thenReturn(claimant);
        when(actRows.find(SCOPE, 9L)).thenReturn(Optional.of(claim));
        return holding;
    }

    /** The act claim the writer answers, and the holding the insert answers. */
    private void claimWritten() {
        Act claim = act(9L, ACT_AT);
        when(acts.write(SCOPE, 77L, Act.Kind.CLAIM, Act.State.ACTIVE, 300L, PROVENANCE))
            .thenReturn(claim);
        Holding opened = withId(new Holding(SCOPE, 300L, 9L), 5L);
        when(tasks.insert(any(Holding.class))).thenReturn(opened);
    }

    private TaskView renew(Actor actor, String receipt, String token) {
        return service.renew(actor, ADDRESS, SCOPE, new HoldingService.Renew(receipt, token, null),
            PROVENANCE);
    }

    private static Act act(long id, Instant at) {
        Act act = Mockito.mock(Act.class);
        when(act.id()).thenReturn(id);
        when(act.at()).thenReturn(at);
        return act;
    }

    private static void assertRefused(Runnable call, DispatchException.Reason reason) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DispatchException.class,
            refused -> assertThat(refused.reason()).isEqualTo(reason));
    }

    /** A row with the surrogate the store would have drawn for it. */
    private static <T> T withId(T row, long id) {
        try {
            Field field = RuntimeRow.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(row, id);
            return row;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
