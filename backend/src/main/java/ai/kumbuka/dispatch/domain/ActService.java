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
 * <p>Nothing here decides whether the act is admissible. The writer records
 * the kind, the state the act leads to and the provenance it is handed, the
 * permission among them, as it is handed; which acts a call may write, and who
 * may make the call, are the calls' to decide. The time is the service's
 * clock's (C20.1).
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
        Instance instance = acts.lockInstance(scopeId, instanceId)
            .orElseThrow(() -> new DispatchException(DispatchException.Reason.NOT_FOUND,
                "no instance is there to write an act to."));
        int next = acts.lastSequence(scopeId, instanceId) + 1;
        return acts.append(Act.of(instance, next, kind, toState, taskId, provenance,
            clock.instant()));
    }
}
