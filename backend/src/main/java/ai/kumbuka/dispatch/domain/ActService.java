/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.repository.ActRepository;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * The writer of acts: appends one act to an instance of the store line.
 *
 * <p>Every change of the control of an instance is an act, and every act is
 * written here. The act takes the next number of its instance's sequence,
 * handed out under a lock on the instance's row, so two writers on one
 * instance never take the same number and never leave a gap: the second waits
 * for the first and continues after it. The instance carries no counter; the
 * sequence is read off its acts under the lock.
 *
 * <p><strong>The time of an act rises strictly within its instance.</strong>
 * It is the service's clock's (C20.1), at microsecond precision, the precision
 * the store keeps; where that is not later than the time of the instance's
 * last act, it is that time and one microsecond. It is decided under the same
 * lock as the number. The time of an object's last act is its conflict token,
 * so a token never returns to an earlier value and two acts within one
 * microsecond still leave two tokens (C10.1, in the form the operator decided
 * on 2026-11-24: the time of the last act takes the place of an {@code
 * updated_at} the store does not hold).
 *
 * <p>Nothing here decides whether the act is admissible. The writer records
 * the kind, the state the act leads to and the provenance it is handed, the
 * permission among them, as it is handed; which acts a call may write, and who
 * may make the call, are the calls' to decide.
 *
 * <p>Logs nothing (DEC-0051, C4).
 */
@ApplicationScoped
@TenantBound
public class ActService {

    private final ActRepository acts;
    private final Clock clock;

    @Inject
    ActService(ActRepository acts, Clock clock) {
        this.acts = acts;
        this.clock = clock;
    }

    /**
     * Appends an act to an instance and answers it as written.
     *
     * @param scopeId    the scope the instance belongs to, as the directory
     *                   resolved it
     * @param instanceId the instance, by its surrogate
     * @param kind       what the act is
     * @param toState    the state the act leads the instance or its task to
     * @param taskId     the task of the instance the act is about; absent on an
     *                   act about the instance alone
     * @param provenance who made the call, how and by which permission
     * @throws DispatchException {@code NOT_FOUND} where the scope holds no such
     *                           instance for the bound tenant
     */
    @Transactional
    public Act write(UUID scopeId, long instanceId, Act.Kind kind, Act.State toState,
                     @Nullable Long taskId, Provenance provenance) {
        return write(scopeId, instanceId, kind, toState, taskId, provenance, null);
    }

    /**
     * Appends an act another act caused, and answers it as written: as {@link
     * #write(UUID, long, Act.Kind, Act.State, Long, Provenance)}, with the act
     * that caused it in its column.
     *
     * @param refersToActId the act of the same instance that caused this one;
     *                      absent on an act a call writes on its own account
     */
    @Transactional
    public Act write(UUID scopeId, long instanceId, Act.Kind kind, Act.State toState,
                     @Nullable Long taskId, Provenance provenance,
                     @Nullable Long refersToActId) {
        Instance instance = locked(scopeId, instanceId);
        return acts.append(Act.of(instance, acts.lastSequence(scopeId, instanceId) + 1, kind,
            toState, taskId, provenance, nextTime(scopeId, instanceId), refersToActId));
    }

    /**
     * Appends the act {@code defer} of a task, which gives it back open until
     * an instant, and answers it as written: as {@link #write(UUID, long,
     * Act.Kind, Act.State, Long, Provenance)}, with that instant in its column.
     *
     * @param until the instant before which the task is not taken up
     */
    @Transactional
    public Act writeDeferral(UUID scopeId, long instanceId, long taskId, Instant until,
                             Provenance provenance) {
        Instance instance = locked(scopeId, instanceId);
        return acts.append(Act.of(instance, acts.lastSequence(scopeId, instanceId) + 1,
            Act.Kind.DEFER, Act.State.OPEN, taskId, provenance, nextTime(scopeId, instanceId),
            null).until(until));
    }

    private Instance locked(UUID scopeId, long instanceId) {
        return acts.lockInstance(scopeId, instanceId)
            .orElseThrow(() -> new DispatchException(DispatchException.Reason.NOT_FOUND,
                "no instance is there to write an act to."));
    }

    /** The time of the next act of an instance, read under the instance's lock. */
    private Instant nextTime(UUID scopeId, long instanceId) {
        return timeAfter(clock.instant(), acts.lastAt(scopeId, instanceId).orElse(null));
    }

    /**
     * The time of the next act of an instance: the clock's, at the precision
     * the store keeps, and one microsecond after the last act where the clock
     * has not moved past it.
     *
     * @param last the time of the instance's last act; absent where it has none
     */
    static Instant timeAfter(Instant now, @Nullable Instant last) {
        Instant stored = now.truncatedTo(ChronoUnit.MICROS);
        if (last == null) {
            return stored;
        }
        Instant earliest = last.plus(1, ChronoUnit.MICROS);
        return earliest.isAfter(stored) ? earliest : stored;
    }
}
