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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A lease expires lazily, on read; nothing writes an expiry back (C11.2,
 * ADR-0002).
 *
 * <p>A lease is let run out as the clock would, with a write of its end into
 * the past. Every read after that answers the task as open without a holder,
 * and the rows stay exactly as the claim left them: the task stored {@code
 * active}, the holding without an ending act, its lease and its receipt. Not
 * even the next claim writes the lapse: it writes its own act and a holding
 * beside the lapsed one, which keeps its empty {@code ended_by_act_id}.
 *
 * <p>The red state is observed on every build: the state a read answers is
 * not the state the row holds, so a read that wrote what it answers back
 * would change the row, and the comparison of every row before and after the
 * reads would report it.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class LeaseExpiryIT {

    @Inject HoldingService holdings;
    @Inject TaskService tasks;
    @Inject InstanceService instances;

    @Test
    void a_lapsed_lease_is_read_as_open_without_a_holder_and_nothing_is_written_back() {
        TaskAddress task = HoldingFixture.sent(instances, tasks, "expiry-read");
        TaskView claimed = HoldingFixture.claim(holdings, HoldingFixture.EXECUTOR, task);
        HoldingFixture.lapse(task);
        List<Map<String, Object>> before = snapshot(task);

        TaskView byTheHolder = holdings.standing(HoldingFixture.EXECUTOR, task,
            InstanceWire.SCOPE).orElseThrow();
        TaskView byAnother = holdings.standing(HoldingFixture.OTHER, task, InstanceWire.SCOPE)
            .orElseThrow();
        assertThatThrownBy(() -> holdings.release(HoldingFixture.EXECUTOR, task,
                InstanceWire.SCOPE, new HoldingService.Release(claimed.standing().receipt(), null),
                HoldingFixture.executing(HoldingFixture.EXECUTOR)))
            .as("the former holder's call is refused as lapsed")
            .isInstanceOfSatisfying(DispatchException.class, refused -> assertThat(
                refused.reason()).isEqualTo(DispatchException.Reason.LEASE_LAPSED));

        assertThat(List.of(byTheHolder.state(), byAnother.state()))
            .as("open, whoever reads").containsExactly("open", "open");
        assertThat(byTheHolder.standing().leaseExpiresAt())
            .as("without a holder: the former holder holds no lease").isNull();
        assertThat(byAnother.open()).as("open to be taken up").containsExactly(TaskCall.CLAIM);
        assertThat(HoldingFixture.taskRow(task).get("state"))
            .as("RED STATE, observed: the row holds another state than the read answers, so a "
                + "read writing its answer back would change the row")
            .isEqualTo("active");
        assertThat(snapshot(task)).as("and no row changed").isEqualTo(before);
    }

    @Test
    void the_next_claim_writes_no_lapse_and_opens_a_holding_beside_the_lapsed_one() {
        TaskAddress task = HoldingFixture.sent(instances, tasks, "expiry-claim");
        HoldingFixture.claim(holdings, HoldingFixture.EXECUTOR, task);
        HoldingFixture.lapse(task);
        Map<String, Object> lapsed = HoldingFixture.holdingRows(task).get(0);

        HoldingFixture.claim(holdings, HoldingFixture.OTHER, task);

        List<Map<String, Object>> after = HoldingFixture.holdingRows(task);
        assertThat(after).as("the lapsed holding as it was, and a new one beside it")
            .hasSize(2).first().isEqualTo(lapsed);
        assertThat(after.get(0).get("ended_by_act_id")).as("no act ended the lapsed holding")
            .isNull();
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.act WHERE task_id = ? "
                + "AND kind NOT IN ('send', 'claim')", HoldingFixture.taskId(task)))
            .as("no act but the two claims was written: a lapse is no act").isZero();
    }

    /** Every row the holding of the task stands on, as the store holds them. */
    private static List<Map<String, Object>> snapshot(TaskAddress task) {
        long id = HoldingFixture.taskId(task);
        return InstanceWire.rows("""
            SELECT 'task' AS r, t.id, t.state, CAST(t.not_before AS text) AS at, t.parked::text AS x
              FROM dispatch.task t WHERE t.id = ?
            UNION ALL
            SELECT 'holding', h.id, CAST(h.ended_by_act_id AS text), NULL, NULL
              FROM dispatch.holding h WHERE h.task_id = ?
            UNION ALL
            SELECT 'lease', l.id, NULL, CAST(l.expires_at AS text), NULL
              FROM dispatch.lease l JOIN dispatch.holding h ON h.id = l.holding_id
             WHERE h.task_id = ?
            UNION ALL
            SELECT 'receipt', r.id, r.receipt_hash, NULL, NULL
              FROM dispatch.receipt r JOIN dispatch.holding h ON h.id = r.holding_id
             WHERE h.task_id = ?
            UNION ALL
            SELECT 'act', a.id, a.kind, CAST(a.at AS text), NULL
              FROM dispatch.act a WHERE a.task_id = ?
            ORDER BY 1, 2
            """, id, id, id, id, id);
    }
}
