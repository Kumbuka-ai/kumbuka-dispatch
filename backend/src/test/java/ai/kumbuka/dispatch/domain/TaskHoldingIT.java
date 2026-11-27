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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static ai.kumbuka.dispatch.domain.HoldingFixture.EXECUTOR;
import static ai.kumbuka.dispatch.domain.HoldingFixture.OTHER;
import static ai.kumbuka.dispatch.domain.HoldingFixture.executing;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The holding of a task against a running database under the service role
 * (TAR-0004 2.5 and 2.8, data model A.5): what a claim writes, the lease and
 * its renewal, the release and the deferral, the lapse that writes nothing,
 * the count of lapses and the parking after the third. Every expectation is
 * written here from the order: the rows are read by SQL as the store holds
 * them, never through the mapping under test; a lapse is a lease end written
 * into the past by the administrator, as the clock would let it run out.
 *
 * <p>The cases of this class that a later order binds stay deactivated with
 * that order: the answer that restarts a hold (T8), the way out of a parked
 * hold by withdraw (T6), and acceptance and rework (T9).
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskHoldingIT {

    @Inject HoldingService holdings;
    @Inject TaskService tasks;
    @Inject InstanceService instances;

    // -----------------------------------------------------------------------
    // claim
    // -----------------------------------------------------------------------

    @Test
    void a_claim_writes_one_act_one_holding_one_receipt_and_one_lease_and_makes_the_task_active() {
        TaskAddress task = HoldingFixture.sent(instances, tasks, "claim-rows");

        TaskView claimed = HoldingFixture.claim(holdings, EXECUTOR, task);

        List<Map<String, Object>> acts = InstanceWire.rows("SELECT kind, actor_subject, "
            + "permission, to_state FROM dispatch.act WHERE task_id = ? AND kind = 'claim'",
            HoldingFixture.taskId(task));
        assertThat(acts).as("one act claim, by the caller, under dispatch.task.execute")
            .containsExactly(Map.of("kind", "claim", "actor_subject", "executor-one",
                "permission", "dispatch.task.execute", "to_state", "active"));
        assertThat(List.of(HoldingFixture.rows("holding", task),
                HoldingFixture.rows("receipt", task), HoldingFixture.rows("lease", task)))
            .as("one holding, one receipt, one lease").containsExactly(1L, 1L, 1L);
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.holding h JOIN "
                + "dispatch.act a ON a.id = h.claim_act_id WHERE h.task_id = ? "
                + "AND a.kind = 'claim' AND h.ended_by_act_id IS NULL",
                HoldingFixture.taskId(task)))
            .as("the holding hangs on the act claim and is open").isEqualTo(1);
        assertThat(HoldingFixture.taskRow(task).get("state")).isEqualTo("active");
        assertThat(claimed.state()).isEqualTo("active");
        assertThat(claimed.standing().receipt()).as("the answer carries the receipt").isNotBlank();
        assertThat(claimed.open()).as("the holder's calls are open to the holder")
            .containsExactly(TaskCall.RENEW, TaskCall.RELEASE, TaskCall.DEFER);
    }

    @Test
    void a_claim_without_a_duration_holds_thirty_minutes_and_a_stated_one_holds_as_stated() {
        TaskAddress plain = HoldingFixture.sent(instances, tasks, "claim-default");
        TaskAddress stated = HoldingFixture.sent(instances, tasks, "claim-stated");

        TaskView byDefault = HoldingFixture.claim(holdings, EXECUTOR, plain);
        TaskView twoHours = holdings.claim(EXECUTOR, stated, InstanceWire.SCOPE,
            new HoldingService.Claim(Duration.ofHours(2), IdempotencyKey.NONE),
            executing(EXECUTOR));

        assertThat(leaseAfterAct(plain, "claim")).as("thirty minutes after the act")
            .isEqualTo(Duration.ofMinutes(30));
        assertThat(leaseAfterAct(stated, "claim")).as("as stated").isEqualTo(Duration.ofHours(2));
        assertThat(byDefault.standing().leaseExpiresAt())
            .as("and the answer carries the end the store holds")
            .isEqualTo(leaseEnd(plain));
        assertThat(twoHours.standing().leaseExpiresAt()).isEqualTo(leaseEnd(stated));
    }

    @Test
    void a_lease_reaching_the_year_294276_is_refused_and_writes_nothing() {
        TaskAddress task = HoldingFixture.sent(instances, tasks, "claim-bound");
        Map<String, Long> before = DefinitionFixture.rowCounts();

        assertThatThrownBy(() -> holdings.claim(EXECUTOR, task, InstanceWire.SCOPE,
                new HoldingService.Claim(Duration.ofDays(300_000L * 366), IdempotencyKey.NONE),
                executing(EXECUTOR)))
            .isInstanceOfSatisfying(DispatchException.class, refused -> assertThat(
                refused.reason()).isEqualTo(DispatchException.Reason.LEASE_END_INVALID));
        assertThat(DefinitionFixture.rowCounts()).as("the refused claim wrote nothing")
            .isEqualTo(before);
        assertThat(HoldingFixture.taskRow(task).get("state")).isEqualTo("open");
    }

    @Test
    void a_task_another_holds_is_refused_as_held_and_a_draft_as_a_transition() {
        TaskAddress task = HoldingFixture.sent(instances, tasks, "claim-held");
        HoldingFixture.claim(holdings, EXECUTOR, task);
        Map<String, Long> before = DefinitionFixture.rowCounts();

        assertThatThrownBy(() -> HoldingFixture.claim(holdings, OTHER, task))
            .isInstanceOfSatisfying(DispatchException.class, refused -> {
                assertThat(refused.reason()).isEqualTo(DispatchException.Reason.CLAIM_HELD);
                assertThat(refused.task().orElseThrow().state()).isEqualTo("active");
            });
        assertThatThrownBy(() -> HoldingFixture.claim(holdings, EXECUTOR, task))
            .as("the holder's own second claim is no take-up of an open task")
            .isInstanceOfSatisfying(DispatchException.class, refused -> assertThat(
                refused.reason()).isEqualTo(DispatchException.Reason.TRANSITION_NOT_PERMITTED));
        assertThat(DefinitionFixture.rowCounts()).as("neither refusal wrote anything")
            .isEqualTo(before);
    }

    // -----------------------------------------------------------------------
    // renew
    // -----------------------------------------------------------------------

    @Test
    void renew_extends_with_and_without_a_duration_and_writes_its_act_and_a_lease() {
        TaskAddress task = HoldingFixture.sent(instances, tasks, "renew");
        TaskView claimed = HoldingFixture.claim(holdings, EXECUTOR, task);
        String receipt = claimed.standing().receipt();

        TaskView renewed = holdings.renew(EXECUTOR, task, InstanceWire.SCOPE,
            new HoldingService.Renew(receipt, claimed.conflictToken(), Duration.ofHours(3)),
            executing(EXECUTOR));
        assertThat(leaseAfterAct(task, "renew")).isEqualTo(Duration.ofHours(3));

        holdings.renew(EXECUTOR, task, InstanceWire.SCOPE,
            new HoldingService.Renew(receipt, renewed.conflictToken(), null), executing(EXECUTOR));
        assertThat(leaseAfterAct(task, "renew")).as("without a duration, thirty minutes")
            .isEqualTo(Duration.ofMinutes(30));
        assertThat(List.of(HoldingFixture.rows("lease", task), HoldingFixture.rows("holding",
                task), HoldingFixture.rows("receipt", task)))
            .as("a lease per act on the one holding, and no further receipt")
            .containsExactly(3L, 1L, 1L);
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.act WHERE task_id = ? "
                + "AND kind = 'renew' AND to_state = 'active'", HoldingFixture.taskId(task)))
            .isEqualTo(2);
        assertThat(HoldingFixture.taskRow(task).get("state")).isEqualTo("active");
    }

    @Test
    void a_renew_without_its_receipt_by_another_after_the_lapse_or_under_a_stale_token_is_refused() {
        TaskAddress task = HoldingFixture.sent(instances, tasks, "renew-refused");
        TaskView claimed = HoldingFixture.claim(holdings, EXECUTOR, task);
        String receipt = claimed.standing().receipt();
        String token = claimed.conflictToken();
        Map<String, Long> before = DefinitionFixture.rowCounts();

        assertRefused(() -> renew(EXECUTOR, task, "zz-a-receipt-of-nobody", token),
            DispatchException.Reason.RECEIPT_MISMATCH);
        assertRefused(() -> renew(OTHER, task, receipt, token),
            DispatchException.Reason.CLAIM_HELD);
        assertRefused(() -> renew(EXECUTOR, task, receipt, "2001-01-01T00:00:00Z"),
            DispatchException.Reason.CONFLICT_TOKEN_STALE);
        assertRefused(() -> renew(EXECUTOR, task, receipt, null),
            DispatchException.Reason.CONFLICT_TOKEN_MISSING);
        HoldingFixture.lapse(task);
        Map<String, Long> lapsed = DefinitionFixture.rowCounts();
        assertRefused(() -> renew(EXECUTOR, task, receipt, token),
            DispatchException.Reason.LEASE_LAPSED);

        assertThat(lapsed).as("no refusal wrote anything").isEqualTo(before)
            .isEqualTo(DefinitionFixture.rowCounts());
    }

    // -----------------------------------------------------------------------
    // release, defer
    // -----------------------------------------------------------------------

    @Test
    void release_gives_the_task_back_with_its_remark_and_another_takes_it_up() {
        TaskAddress task = HoldingFixture.sent(instances, tasks, "release");
        TaskView claimed = HoldingFixture.claim(holdings, EXECUTOR, task);

        TaskView released = holdings.release(EXECUTOR, task, InstanceWire.SCOPE,
            new HoldingService.Release(claimed.standing().receipt(), "zz-the-run-broke-off"),
            executing(EXECUTOR));

        long act = InstanceWire.single("SELECT id FROM dispatch.act WHERE task_id = ? "
            + "AND kind = 'release' AND to_state = 'open'", HoldingFixture.taskId(task));
        assertThat(HoldingFixture.holdingRows(task)).extracting(row -> row.get("ended_by_act_id"))
            .as("the act release ended the holding").containsExactly(act);
        assertThat(HoldingFixture.taskRow(task).get("state")).isEqualTo("open");
        assertThat(InstanceWire.rows("SELECT kind, body FROM dispatch.content WHERE act_id = ?",
                act))
            .as("the remark is the content of the act")
            .containsExactly(Map.of("kind", "remark", "body", "zz-the-run-broke-off"));
        assertThat(released.state()).isEqualTo("open");
        assertThat(released.standing().leaseExpiresAt()).as("no lease for one who holds nothing")
            .isNull();

        TaskView again = HoldingFixture.claim(holdings, OTHER, task);
        assertThat(again.state()).as("another takes it up").isEqualTo("active");
        assertThat(HoldingFixture.rows("holding", task)).isEqualTo(2);
    }

    @Test
    void release_without_a_remark_writes_no_content() {
        TaskAddress task = HoldingFixture.sent(instances, tasks, "release-plain");
        TaskView claimed = HoldingFixture.claim(holdings, EXECUTOR, task);

        holdings.release(EXECUTOR, task, InstanceWire.SCOPE,
            new HoldingService.Release(claimed.standing().receipt(), null), executing(EXECUTOR));

        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.content c JOIN dispatch.act a "
                + "ON a.id = c.act_id WHERE a.task_id = ? AND a.kind = 'release'",
                HoldingFixture.taskId(task)))
            .isZero();
    }

    @Test
    void a_deferred_task_is_not_claimed_before_its_instant_and_is_from_it_on() {
        TaskAddress task = HoldingFixture.sent(instances, tasks, "defer");
        TaskView claimed = HoldingFixture.claim(holdings, EXECUTOR, task);
        Instant until = Instant.parse("2099-01-01T00:00:00Z");

        TaskView deferred = holdings.defer(EXECUTOR, task, InstanceWire.SCOPE,
            new HoldingService.Defer(claimed.standing().receipt(), claimed.conflictToken(), until,
                "zz-retry-later"), executing(EXECUTOR));

        long act = InstanceWire.single("SELECT id FROM dispatch.act WHERE task_id = ? "
            + "AND kind = 'defer' AND to_state = 'open'", HoldingFixture.taskId(task));
        assertThat(InstanceWire.rows("SELECT not_before FROM dispatch.act WHERE id = ?", act)
                .get(0).get("not_before"))
            .as("the act carries the instant").isEqualTo(Timestamp.from(until));
        assertThat(HoldingFixture.taskRow(task))
            .as("the task is open with the instant")
            .containsEntry("state", "open").containsEntry("not_before", Timestamp.from(until));
        assertThat(HoldingFixture.holdingRows(task)).extracting(row -> row.get("ended_by_act_id"))
            .containsExactly(act);
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.content WHERE act_id = ? "
            + "AND kind = 'remark'", act)).isEqualTo(1);
        assertThat(deferred.standing().deferralPending()).isTrue();

        Map<String, Long> before = DefinitionFixture.rowCounts();
        assertRefused(() -> HoldingFixture.claim(holdings, OTHER, task),
            DispatchException.Reason.DEFERRAL_PENDING);
        assertThat(DefinitionFixture.rowCounts()).as("the refused claim wrote nothing")
            .isEqualTo(before);

        ai.kumbuka.dispatch.platform.PlatformFixture.run("UPDATE dispatch.task SET not_before = "
            + "now() - interval '1 second' WHERE id = " + HoldingFixture.taskId(task));
        TaskView taken = HoldingFixture.claim(holdings, OTHER, task);
        assertThat(taken.state()).as("from the instant on it is taken up").isEqualTo("active");
        assertThat(HoldingFixture.taskRow(task).get("not_before")).as("and its deferral is spent")
            .isNull();
    }

    @Test
    void a_deferral_to_an_instant_not_after_the_act_leaves_the_task_to_be_taken_up_at_once() {
        TaskAddress task = HoldingFixture.sent(instances, tasks, "defer-past");
        TaskView claimed = HoldingFixture.claim(holdings, EXECUTOR, task);

        TaskView deferred = holdings.defer(EXECUTOR, task, InstanceWire.SCOPE,
            new HoldingService.Defer(claimed.standing().receipt(), claimed.conflictToken(),
                Instant.parse("2001-01-01T00:00:00Z"), null), executing(EXECUTOR));

        assertThat(deferred.standing().deferralPending()).isFalse();
        assertThat(HoldingFixture.claim(holdings, OTHER, task).state()).isEqualTo("active");
    }

    // -----------------------------------------------------------------------
    // lapses
    // -----------------------------------------------------------------------

    @Test
    void a_lapse_writes_nothing_and_the_former_holder_is_refused_as_lapsed() {
        TaskAddress task = HoldingFixture.sent(instances, tasks, "lapse");
        TaskView claimed = HoldingFixture.claim(holdings, EXECUTOR, task);
        HoldingFixture.lapse(task);
        Map<String, Long> before = DefinitionFixture.rowCounts();

        assertRefused(() -> holdings.release(EXECUTOR, task, InstanceWire.SCOPE,
                new HoldingService.Release(claimed.standing().receipt(), null),
                executing(EXECUTOR)),
            DispatchException.Reason.LEASE_LAPSED);
        assertRefused(() -> holdings.defer(EXECUTOR, task, InstanceWire.SCOPE,
                new HoldingService.Defer(claimed.standing().receipt(), claimed.conflictToken(),
                    Instant.parse("2099-01-01T00:00:00Z"), null), executing(EXECUTOR)),
            DispatchException.Reason.LEASE_LAPSED);

        assertThat(DefinitionFixture.rowCounts()).isEqualTo(before);
        assertThat(HoldingFixture.taskRow(task).get("state"))
            .as("no refusal wrote the lapse back").isEqualTo("active");
        assertThat(HoldingFixture.holdingRows(task)).extracting(row -> row.get("ended_by_act_id"))
            .containsOnlyNulls();
    }

    @Test
    void the_second_lapse_still_opens_the_task_and_a_claim_after_it_opens_a_third_holding() {
        TaskAddress task = HoldingFixture.sent(instances, tasks, "second-lapse");
        HoldingFixture.claim(holdings, EXECUTOR, task);
        HoldingFixture.lapse(task);
        HoldingFixture.claim(holdings, OTHER, task);
        HoldingFixture.lapse(task);

        TaskView third = HoldingFixture.claim(holdings, EXECUTOR, task);

        assertThat(third.state()).isEqualTo("active");
        assertThat(HoldingFixture.holdingRows(task))
            .as("three holdings side by side, the two lapsed ones without an ending act")
            .hasSize(3)
            .extracting(row -> row.get("ended_by_act_id")).containsOnlyNulls();
    }

    @Test
    void the_third_lapse_parks_the_task_and_a_claim_is_refused() {
        TaskAddress task = HoldingFixture.sent(instances, tasks, "third-lapse");
        for (Actor executor : List.of(EXECUTOR, OTHER, EXECUTOR)) {
            HoldingFixture.claim(holdings, executor, task);
            HoldingFixture.lapse(task);
        }
        Map<String, Long> before = DefinitionFixture.rowCounts();

        TaskView parked = holdings.standing(OTHER, task, InstanceWire.SCOPE).orElseThrow();
        assertThat(List.of(parked.state(), String.valueOf(parked.standing().holdReason()),
                parked.standing().parked()))
            .as("on hold for external, parked, without a holder")
            .containsExactly("on_hold", "external", true);
        assertThat(parked.open()).isEmpty();
        assertRefused(() -> HoldingFixture.claim(holdings, OTHER, task),
            DispatchException.Reason.TRANSITION_NOT_PERMITTED);

        assertThat(DefinitionFixture.rowCounts()).isEqualTo(before);
        assertThat(HoldingFixture.taskRow(task))
            .as("the parking is derived and never written")
            .containsEntry("state", "active").containsEntry("parked", false);
    }

    @Test
    @Disabled("rebuilt in T6: a parked task leaves its hold by the withdraw of the task")
    void only_withdraw_takes_a_parked_task_out() {
        fail("rebuilt in T6");
    }

    @Test
    @Disabled("rebuilt in T8: an answer restarts thirty minutes, whatever the claim said")
    void an_answer_restarts_thirty_minutes_whatever_the_claim_said() {
        fail("rebuilt in T8");
    }

    @Test
    @Disabled("rebuilt in T9: the identity that delivered accepts, holding both capacities, and "
        + "the row is marked")
    void the_identity_that_delivered_accepts_holding_both_capacities_and_the_row_is_marked() {
        fail("rebuilt in T9");
    }

    @Test
    @Disabled("rebuilt in T9: an acceptance by another commissioner leaves the row unmarked")
    void an_acceptance_by_another_commissioner_leaves_the_row_unmarked() {
        fail("rebuilt in T9");
    }

    @Test
    @Disabled("rebuilt in T9: rework sends the answer back and the second delivery is the valid "
        + "one")
    void rework_sends_the_answer_back_and_the_second_delivery_is_the_valid_one() {
        fail("rebuilt in T9");
    }

    // -----------------------------------------------------------------------

    private TaskView renew(Actor actor, TaskAddress task, String receipt, String token) {
        return holdings.renew(actor, task, InstanceWire.SCOPE,
            new HoldingService.Renew(receipt, token, null), executing(actor));
    }

    private static void assertRefused(Runnable call, DispatchException.Reason reason) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DispatchException.class,
            refused -> assertThat(refused.reason()).isEqualTo(reason));
    }

    /** How long after its act the youngest lease of the task's acts of a kind ends. */
    private static Duration leaseAfterAct(TaskAddress task, String kind) {
        Map<String, Object> row = InstanceWire.rows("SELECT a.at, l.expires_at FROM dispatch.lease l "
            + "JOIN dispatch.act a ON a.id = l.act_id WHERE a.task_id = ? AND a.kind = ? "
            + "ORDER BY l.id DESC LIMIT 1", HoldingFixture.taskId(task), kind).get(0);
        return Duration.between(((Timestamp) row.get("at")).toInstant(),
            ((Timestamp) row.get("expires_at")).toInstant());
    }

    /** The end of the youngest lease of the task, as the store holds it. */
    private static Instant leaseEnd(TaskAddress task) {
        return ((Timestamp) InstanceWire.rows("SELECT l.expires_at FROM dispatch.lease l JOIN "
            + "dispatch.holding h ON h.id = l.holding_id WHERE h.task_id = ? "
            + "ORDER BY l.id DESC LIMIT 1", HoldingFixture.taskId(task)).get(0)
            .get("expires_at")).toInstant();
    }
}
