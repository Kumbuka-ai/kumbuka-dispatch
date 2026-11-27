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
import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The holding of a task (REA-0010, catalogue "Transitions of a task"; TAR-0004
 * 2.5 and 2.8; data model A.5): {@code claim} takes an open task up and makes
 * the caller its holder, with a receipt and a lease; {@code renew} extends the
 * lease; {@code release} gives the task back; {@code defer} gives it back until
 * an instant.
 *
 * <p><strong>Every call decides under the lock of the task's row</strong>,
 * taken after the lock of its instance's row, the order every call on a task
 * takes, and refreshed under it (C11.1), in one transaction. A refusal writes
 * nothing.
 *
 * <p><strong>A lapse is derived, never written</strong> ({@link EffectiveState},
 * ADR-0002, C11.2). Every check here reads the task's stored projection, its
 * holdings and the youngest lease of the youngest open one, at the instant the
 * call began. A lapsed holding keeps its empty {@code ended_by_act_id} and the
 * task stays stored {@code active}; a {@code claim} on a task its lapse opened
 * writes its act and a holding beside the lapsed one.
 *
 * <p><strong>The holder is the identity that wrote the act {@code
 * claim}</strong> of the holding that runs (D17). A holder's call by another
 * identity is refused with {@code CLAIM_HELD}, by the former holder of a
 * lapsed holding with {@code LEASE_LAPSED}, and where nobody holds the task
 * with {@code CLAIM_REQUIRED}. The holder presents the youngest receipt of its
 * holding; the store holds receipts as their SHA-256 alone (C10.5), and the
 * receipt is in no row and no log. {@code renew} and {@code defer} carry the
 * conflict token, the time of the task's last act (D29); {@code claim} and
 * {@code release} carry none (DEC-0041 C1).
 *
 * <p>An idempotency key of {@code claim} belongs to its caller, holds for
 * twenty-four hours from its first use, and hangs on the act {@code claim}
 * (C10.4, D30). A repeat with the same arguments, while the caller holds the
 * task by the holding that claim opened under a running lease, answers the
 * task with a new receipt: one more row {@code receipt} on that holding, no
 * act, no lease, no change of state, and the earlier receipt no longer
 * matches. Where the caller holds it no longer, the repeat takes nothing and
 * is refused with the reason the situation gives; with other arguments, or on
 * another call, it is refused with {@code IDEMPOTENCY_KEY_REUSED}.
 *
 * <p>Nothing here checks the caller's permission: the call checked it before
 * it was made. Logs nothing (DEC-0051, C4).
 */
@ApplicationScoped
@TenantBound
public class HoldingService {

    /** How long a hold lasts where the call names no duration (REA-0010, parameters of the wave). */
    static final Duration DEFAULT_LEASE = Duration.ofMinutes(30);

    /** The first instant a lease may not reach: the store keeps none from the year 294276 on. */
    static final Instant LEASE_BOUND = Instant.parse("+294276-01-01T00:00:00Z");

    private final TaskRepository tasks;
    private final InstanceRepository instances;
    private final HoldingRepository holdings;
    private final ActRepository actRows;
    private final ActService acts;
    private final Clock clock;

    @Inject
    HoldingService(TaskRepository tasks, InstanceRepository instances, HoldingRepository holdings,
                   ActRepository actRows, ActService acts, Clock clock) {
        this.tasks = tasks;
        this.instances = instances;
        this.holdings = holdings;
        this.actRows = actRows;
        this.acts = acts;
        this.clock = clock;
    }

    /**
     * What a claim hands in.
     *
     * @param duration how long the hold lasts; absent for {@link #DEFAULT_LEASE}
     * @param key      the caller's idempotency key, or none
     */
    public record Claim(@Nullable Duration duration, IdempotencyKey key) {
    }

    /**
     * What a renew hands in.
     *
     * @param receipt       the receipt the holder presents
     * @param conflictToken the token of the caller's last answer; absent where
     *                      none arrived, which is refused
     * @param duration      how long the hold lasts from the act on; absent for
     *                      {@link #DEFAULT_LEASE}
     */
    public record Renew(String receipt, @Nullable String conflictToken,
                        @Nullable Duration duration) {
    }

    /**
     * What a release hands in.
     *
     * @param receipt the receipt the holder presents
     * @param remark  a remark kept as content of the act; absent where none arrived
     */
    public record Release(String receipt, @Nullable String remark) {
    }

    /**
     * What a defer hands in.
     *
     * @param receipt       the receipt the holder presents
     * @param conflictToken the token of the caller's last answer; absent where
     *                      none arrived, which is refused
     * @param notBefore     the instant before which the task is not taken up
     * @param remark        a remark kept as content of the act; absent where none arrived
     */
    public record Defer(String receipt, @Nullable String conflictToken, Instant notBefore,
                        @Nullable String remark) {
    }

    /** The rows a call decides on, locked: the task's instance, then the task. */
    private record Locked(Instance instance, Task task) {
    }

    /**
     * Where a task stands at the instant of a check.
     *
     * @param state    its state as a reader is told it
     * @param youngest its youngest holding no act ended; absent where none stands
     * @param claimant the identity that claimed that holding; absent with it
     * @param leaseEnd the end of that holding's youngest lease; absent where it
     *                 has none, or no such holding stands
     */
    private record Situation(EffectiveState state, @Nullable Holding youngest,
                             @Nullable String claimant, @Nullable Instant leaseEnd) {

        /** Whether the actor holds the task under a running lease. */
        boolean heldBy(Actor actor) {
            return state.held() && actor.subject().equals(claimant);
        }
    }

    // ======================================================================
    // claim
    // ======================================================================

    /**
     * Takes an open task up: the act {@code claim}, a holding with its first
     * receipt and its first lease, and the task active, its deferral spent.
     * Answers the task with the receipt and the end of the lease.
     *
     * @throws DispatchException {@code NOT_FOUND}, {@code DEFERRAL_PENDING},
     *         {@code CLAIM_HELD}, {@code TRANSITION_NOT_PERMITTED}, {@code
     *         LEASE_END_INVALID}, {@code IDEMPOTENCY_KEY_REUSED}, or for a
     *         repeat the caller no longer holds {@code LEASE_LAPSED} or {@code
     *         CLAIM_REQUIRED}
     */
    @Transactional
    public TaskView claim(Actor actor, TaskAddress address, UUID scopeId, Claim claim,
                          Provenance provenance) {
        String digest = digest(address, claim);
        Locked locked = lock(scopeId, address);
        Task task = locked.task();
        Instant now = clock.instant();
        Optional<TaskView> repeat = repeat(actor, address, scopeId, task, claim.key(), digest, now);
        if (repeat.isPresent()) {
            return repeat.get();
        }
        Situation at = situation(scopeId, task, now);
        admit(actor, scopeId, address, task, at, now);

        Act act = acts.write(scopeId, locked.instance().id, Act.Kind.CLAIM, Act.State.ACTIVE,
            task.id(), provenance);
        Instant leaseEnd = leaseEnd(act.at(), claim.duration());
        Holding holding = tasks.insert(new Holding(scopeId, task.id(), act.id()));
        String receipt = Receipt.mint();
        tasks.insert(new IssuedReceipt(scopeId, holding.id(), receipt, act.at()));
        tasks.insert(new Lease(scopeId, holding.id(), act.id(), leaseEnd));
        task.claim();
        if (claim.key() instanceof IdempotencyKey.Given given) {
            tasks.deleteExpiredKeys(scopeId, actor.subject(), now.minus(TaskService.KEY_LIFE));
            if (!tasks.spendKey(IdempotencyRecord.ofClaim(scopeId, actor.subject(), given.value(),
                    act.id(), digest, now))) {
                throw reused();
            }
        }
        tasks.flush();
        return view(actor, scopeId, address, task, situation(scopeId, task, now), now, receipt);
    }

    /**
     * The answer of a repeat under a key the caller spent in the last
     * twenty-four hours: a new receipt on the holding the first claim opened,
     * while the caller holds the task by it. Empty where no such key stands.
     *
     * @throws DispatchException {@code IDEMPOTENCY_KEY_REUSED} where the key
     *         came with other arguments or on another call, and the refusal of
     *         a holder's call without a hold where the caller holds the task
     *         by that holding no longer
     */
    private Optional<TaskView> repeat(Actor actor, TaskAddress address, UUID scopeId, Task task,
                                      IdempotencyKey key, String digest, Instant now) {
        if (!(key instanceof IdempotencyKey.Given given)) {
            return Optional.empty();
        }
        Optional<IdempotencyRecord> spent = tasks.spentKey(scopeId, actor.subject(),
            given.value(), now.minus(TaskService.KEY_LIFE));
        if (spent.isEmpty()) {
            return Optional.empty();
        }
        Holding claimed = spent.filter(held -> held.sameArguments(digest))
            .flatMap(IdempotencyRecord::actId)
            .flatMap(act -> holdings.openedBy(scopeId, act))
            .orElseThrow(HoldingService::reused);
        Situation at = situation(scopeId, task, now);
        Holding youngest = at.youngest();
        if (!at.heldBy(actor) || youngest == null || !youngest.id().equals(claimed.id())) {
            throw unheld(actor, scopeId, address, task, at, now);
        }
        String receipt = Receipt.mint();
        tasks.insert(new IssuedReceipt(scopeId, claimed.id(), receipt,
            now.truncatedTo(ChronoUnit.MICROS)));
        return Optional.of(view(actor, scopeId, address, task, at, now, receipt));
    }

    /**
     * Admits a claim on a task as it stands: open, and past the instant of a
     * deferral it carries.
     */
    private void admit(Actor actor, UUID scopeId, TaskAddress address, Task task, Situation at,
                       Instant now) {
        String state = at.state().state();
        if (Task.OPEN.equals(state)) {
            if (task.notBefore().filter(now::isBefore).isPresent()) {
                throw new DispatchException(DispatchException.Reason.DEFERRAL_PENDING,
                    "the task is deferred until an instant that has not come.",
                    view(actor, scopeId, address, task, at, now, null));
            }
            return;
        }
        if (at.state().held() && !at.heldBy(actor)) {
            throw new DispatchException(DispatchException.Reason.CLAIM_HELD,
                "another identity holds the task.", view(actor, scopeId, address, task, at, now,
                null));
        }
        throw new DispatchException(DispatchException.Reason.TRANSITION_NOT_PERMITTED,
            "a claim takes up an open task.", view(actor, scopeId, address, task, at, now, null));
    }

    // ======================================================================
    // renew, release, defer
    // ======================================================================

    /**
     * Extends the holder's lease: the act {@code renew} and a lease from its
     * time on. The task stays active.
     *
     * @throws DispatchException {@code NOT_FOUND}, the refusals of a holder's
     *         call, {@code CONFLICT_TOKEN_MISSING}, {@code CONFLICT_TOKEN_STALE}
     *         or {@code LEASE_END_INVALID}
     */
    @Transactional
    public TaskView renew(Actor actor, TaskAddress address, UUID scopeId, Renew renew,
                          Provenance provenance) {
        Locked locked = lock(scopeId, address);
        Task task = locked.task();
        Instant now = clock.instant();
        Situation at = situation(scopeId, task, now);
        Holding holding = holderOf(actor, scopeId, address, task, at, now, renew.receipt());
        requireToken(actor, scopeId, address, task, at, now, renew.conflictToken());

        Act act = acts.write(scopeId, locked.instance().id, Act.Kind.RENEW, Act.State.ACTIVE,
            task.id(), provenance);
        tasks.insert(new Lease(scopeId, holding.id(), act.id(),
            leaseEnd(act.at(), renew.duration())));
        tasks.flush();
        return view(actor, scopeId, address, task, situation(scopeId, task, now), now, null);
    }

    /**
     * Gives the task back: the act {@code release}, which ends the holding,
     * with the remark as its content; the task is open.
     *
     * @throws DispatchException {@code NOT_FOUND} or the refusals of a holder's call
     */
    @Transactional
    public TaskView release(Actor actor, TaskAddress address, UUID scopeId, Release release,
                            Provenance provenance) {
        Locked locked = lock(scopeId, address);
        Task task = locked.task();
        Instant now = clock.instant();
        Situation at = situation(scopeId, task, now);
        Holding holding = holderOf(actor, scopeId, address, task, at, now, release.receipt());

        Act act = acts.write(scopeId, locked.instance().id, Act.Kind.RELEASE, Act.State.OPEN,
            task.id(), provenance);
        holding.end(act.id());
        task.release();
        remark(scopeId, act, release.remark());
        tasks.flush();
        return view(actor, scopeId, address, task, situation(scopeId, task, now), now, null);
    }

    /**
     * Gives the task back until an instant: the act {@code defer} with that
     * instant, which ends the holding, with the remark as its content; the
     * task is open and not taken up before the instant. An instant not after
     * the act leaves the task to be taken up at once.
     *
     * @throws DispatchException {@code NOT_FOUND}, the refusals of a holder's
     *         call, {@code CONFLICT_TOKEN_MISSING} or {@code CONFLICT_TOKEN_STALE}
     */
    @Transactional
    public TaskView defer(Actor actor, TaskAddress address, UUID scopeId, Defer defer,
                          Provenance provenance) {
        Locked locked = lock(scopeId, address);
        Task task = locked.task();
        Instant now = clock.instant();
        Situation at = situation(scopeId, task, now);
        Holding holding = holderOf(actor, scopeId, address, task, at, now, defer.receipt());
        requireToken(actor, scopeId, address, task, at, now, defer.conflictToken());

        Act act = acts.writeDeferral(scopeId, locked.instance().id, task.id(), defer.notBefore(),
            provenance);
        holding.end(act.id());
        task.defer(defer.notBefore());
        remark(scopeId, act, defer.remark());
        tasks.flush();
        return view(actor, scopeId, address, task, situation(scopeId, task, now), now, null);
    }

    // ======================================================================
    // Where the task stands
    // ======================================================================

    /**
     * A task as a reader is told it now, without a lock: for a refusal or a
     * read that names its state. Empty where the scope holds no such task.
     */
    @Transactional
    public Optional<TaskView> standing(Actor actor, TaskAddress address, UUID scopeId) {
        InstanceAddress at = address.instance();
        Instant now = clock.instant();
        return tasks.instance(scopeId, at.selector(), at.number())
            .flatMap(instance -> tasks.task(scopeId, instance.id, address.sub()))
            .map(task -> view(actor, scopeId, address, task, situation(scopeId, task, now), now,
                null));
    }

    /** The instance and the task an address names, locked in that order and refreshed. */
    private Locked lock(UUID scopeId, TaskAddress address) {
        InstanceAddress at = address.instance();
        Instance instance = instances.lockInstance(scopeId, at.selector(), at.number())
            .orElseThrow(HoldingService::absent);
        Task task = tasks.lockTask(scopeId, instance.id, address.sub())
            .orElseThrow(HoldingService::absent);
        return new Locked(instance, task);
    }

    /** Where a task stands at an instant: its effective state and its youngest open holding. */
    private Situation situation(UUID scopeId, Task task, Instant now) {
        List<Holding> all = holdings.holdings(scopeId, task.id());
        Holding youngest = null;
        for (Holding holding : all) {
            if (holding.open()) {
                youngest = holding;
            }
        }
        Instant leaseEnd = null;
        String claimant = null;
        if (youngest != null) {
            leaseEnd = holdings.youngestLease(scopeId, youngest.id()).map(Lease::expiresAt)
                .orElse(null);
            claimant = actRows.find(scopeId, youngest.claimActId()).map(Act::actorSubject)
                .orElse(null);
        }
        List<EffectiveState.Line> lines = new ArrayList<>();
        for (Holding holding : all) {
            lines.add(new EffectiveState.Line(!holding.open(), holding == youngest ? leaseEnd
                : null));
        }
        EffectiveState state = EffectiveState.of(task.state(), task.holdReason().orElse(null),
            task.parked(), lines, now);
        return new Situation(state, youngest, claimant, leaseEnd);
    }

    /**
     * The holding by which the caller holds the task, once the receipt it
     * presents is the youngest of that holding.
     *
     * @throws DispatchException {@code CLAIM_HELD}, {@code LEASE_LAPSED},
     *         {@code CLAIM_REQUIRED} or {@code RECEIPT_MISMATCH}
     */
    private Holding holderOf(Actor actor, UUID scopeId, TaskAddress address, Task task,
                             Situation at, Instant now, String receipt) {
        Holding youngest = at.youngest();
        if (!at.heldBy(actor) || youngest == null) {
            throw unheld(actor, scopeId, address, task, at, now);
        }
        boolean matches = holdings.youngestReceipt(scopeId, youngest.id())
            .filter(issued -> issued.matches(receipt))
            .isPresent();
        if (!matches) {
            throw new DispatchException(DispatchException.Reason.RECEIPT_MISMATCH,
                "the receipt is not the youngest one of the holding.",
                view(actor, scopeId, address, task, at, now, null));
        }
        return youngest;
    }

    /**
     * The refusal of a holder's call by a caller who does not hold the task:
     * another holds it, the caller's own lease lapsed, or nobody holds it.
     */
    private DispatchException unheld(Actor actor, UUID scopeId, TaskAddress address, Task task,
                                     Situation at, Instant now) {
        TaskView standing = view(actor, scopeId, address, task, at, now, null);
        if (at.state().held()) {
            return new DispatchException(DispatchException.Reason.CLAIM_HELD,
                "another identity holds the task.", standing);
        }
        if (at.state().youngest() == EffectiveState.Youngest.LAPSED
                && actor.subject().equals(at.claimant())) {
            return new DispatchException(DispatchException.Reason.LEASE_LAPSED,
                "the caller's lease on the task lapsed.", standing);
        }
        return new DispatchException(DispatchException.Reason.CLAIM_REQUIRED,
            "nobody holds the task.", standing);
    }

    /** Refuses a missing token, and one that is not the time of the task's last act (D29). */
    private void requireToken(Actor actor, UUID scopeId, TaskAddress address, Task task,
                              Situation at, Instant now, @Nullable String presented) {
        if (presented == null) {
            throw new DispatchException(DispatchException.Reason.CONFLICT_TOKEN_MISSING,
                "a holder renews and defers under the task's conflict token.");
        }
        if (!token(scopeId, task).equals(presented)) {
            throw new DispatchException(DispatchException.Reason.CONFLICT_TOKEN_STALE,
                "the task changed since the token was read.",
                view(actor, scopeId, address, task, at, now, null));
        }
    }

    /**
     * The task as a reader is told it, with its token, the calls open to the
     * actor, the attributes of its state, and for the holder the end of the
     * lease.
     *
     * @param receipt the receipt handed out now; absent on every answer but a claim's
     */
    private TaskView view(Actor actor, UUID scopeId, TaskAddress address, Task task, Situation at,
                          Instant now, @Nullable String receipt) {
        EffectiveState state = at.state();
        boolean holder = at.heldBy(actor);
        Instant notBefore = Task.OPEN.equals(state.state()) ? task.notBefore().orElse(null) : null;
        boolean pending = notBefore != null && now.isBefore(notBefore);
        TaskView.Standing standing = new TaskView.Standing(state.holdReason(), state.parked(),
            notBefore, pending, holder ? at.leaseEnd() : null, receipt);
        return new TaskView(task.id(), address, state.state(), task.resolution().orElse(null),
            token(scopeId, task), TaskCall.openTo(actor, state.state(), holder, pending),
            standing);
    }

    /** The task's conflict token: the time of its last act (D29). */
    private String token(UUID scopeId, Task task) {
        return actRows.lastTaskAt(scopeId, task.id()).map(Object::toString).orElse("");
    }

    /**
     * The end of a lease an act grants: the time of the act plus the duration,
     * thirty minutes where none was named.
     *
     * @throws DispatchException {@code LEASE_END_INVALID} where it would reach
     *         the year 294276
     */
    static Instant leaseEnd(Instant at, @Nullable Duration duration) {
        Duration lasting = duration == null ? DEFAULT_LEASE : duration;
        Instant end;
        try {
            end = at.plus(lasting);
        } catch (DateTimeException | ArithmeticException beyond) {
            throw leaseEndInvalid();
        }
        if (!end.isBefore(LEASE_BOUND)) {
            throw leaseEndInvalid();
        }
        return end;
    }

    private void remark(UUID scopeId, Act act, @Nullable String remark) {
        if (remark != null) {
            tasks.insert(new Content(scopeId, act.id(), Content.Kind.REMARK, remark));
        }
    }

    /**
     * The digest of a claim's arguments that an idempotency key is held
     * against: the call, the task's address and the duration as named, an
     * absent one as absent.
     */
    static String digest(TaskAddress address, Claim claim) {
        List<String> values = new ArrayList<>();
        values.add("claim");
        values.add(address.complete());
        values.add(claim.duration() == null ? null : String.valueOf(claim.duration()));
        return IdempotencyService.digestOf(values);
    }

    private static DispatchException leaseEndInvalid() {
        return new DispatchException(DispatchException.Reason.LEASE_END_INVALID,
            "a lease ends before the year 294276.", List.of("duration"));
    }

    private static DispatchException reused() {
        return new DispatchException(DispatchException.Reason.IDEMPOTENCY_KEY_REUSED,
            "the key was spent on another call, or on other arguments.");
    }

    private static DispatchException absent() {
        return new DispatchException(DispatchException.Reason.NOT_FOUND,
            "no task is there at that address.");
    }
}
