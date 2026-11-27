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
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static ai.kumbuka.dispatch.domain.HoldingFixture.EXECUTOR;
import static ai.kumbuka.dispatch.domain.HoldingFixture.OTHER;
import static ai.kumbuka.dispatch.domain.HoldingFixture.executing;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A claim repeated under its idempotency key (TAR-0004 2.8, C10.4, D30).
 *
 * <p>While the caller holds the task by the holding the first claim opened,
 * under a running lease, the repeat answers the same task with a new receipt:
 * one more row {@code receipt} on that holding, and no act, no lease, no
 * change of state. The youngest receipt holds, so the earlier one is refused
 * at the next holder's call. Where the caller holds the task no longer, the
 * repeat takes nothing and is refused with the reason the situation gives;
 * with other arguments, or on another call, it is refused as a reuse of the
 * key.
 *
 * <p>The red state is observed on every build: before the repeat, the youngest
 * receipt of the holding is the first receipt's hash, so without the row the
 * repeat writes the earlier receipt would still match.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskClaimRepeatIT {

    @Inject HoldingService holdings;
    @Inject TaskService tasks;
    @Inject InstanceService instances;

    @Test
    void a_repeated_claim_answers_the_same_task_with_a_new_receipt_and_moves_nothing() {
        TaskAddress task = HoldingFixture.sent(instances, tasks, "repeat");
        String key = "repeat-" + InstanceWire.suffix();
        TaskView first = claim(EXECUTOR, task, key, null);
        assertThat(youngestHash(task))
            .as("RED STATE, observed: before the repeat the youngest receipt is the first one, "
                + "so without a new row the earlier receipt would keep matching")
            .isEqualTo(sha256(first.standing().receipt()));
        Map<String, Long> before = DefinitionFixture.rowCounts();
        Map<String, Object> row = HoldingFixture.taskRow(task);

        TaskView again = claim(EXECUTOR, task, key, null);

        assertThat(again.address()).isEqualTo(first.address());
        assertThat(again.standing().receipt()).as("a new receipt")
            .isNotEqualTo(first.standing().receipt());
        assertThat(again.standing().leaseExpiresAt()).as("the same lease")
            .isEqualTo(first.standing().leaseExpiresAt());
        assertThat(again.conflictToken()).as("no act, so the same token")
            .isEqualTo(first.conflictToken());
        Map<String, Long> after = DefinitionFixture.rowCounts();
        assertThat(after.get("receipt")).as("one row receipt more").isEqualTo(
            before.get("receipt") + 1);
        after.remove("receipt");
        before.remove("receipt");
        assertThat(after).as("and nothing else: no act, no lease, no holding, no key")
            .isEqualTo(before);
        assertThat(HoldingFixture.taskRow(task)).isEqualTo(row);
        assertThat(InstanceWire.single("SELECT count(DISTINCT r.holding_id) FROM dispatch.receipt r "
                + "JOIN dispatch.holding h ON h.id = r.holding_id WHERE h.task_id = ?",
                HoldingFixture.taskId(task)))
            .as("both receipts on the one holding").isEqualTo(1);

        assertThatThrownBy(() -> holdings.renew(EXECUTOR, task, InstanceWire.SCOPE,
                new HoldingService.Renew(first.standing().receipt(), again.conflictToken(), null),
                executing(EXECUTOR)))
            .as("the earlier receipt no longer matches")
            .isInstanceOfSatisfying(DispatchException.class, refused -> assertThat(
                refused.reason()).isEqualTo(DispatchException.Reason.RECEIPT_MISMATCH));
        assertThat(holdings.renew(EXECUTOR, task, InstanceWire.SCOPE,
                new HoldingService.Renew(again.standing().receipt(), again.conflictToken(), null),
                executing(EXECUTOR)).state())
            .as("and the new one does").isEqualTo("active");
    }

    @Test
    void a_repeat_after_the_hold_ended_is_refused_and_takes_nothing() {
        TaskAddress task = HoldingFixture.sent(instances, tasks, "repeat-released");
        String key = "released-" + InstanceWire.suffix();
        TaskView first = claim(EXECUTOR, task, key, null);
        holdings.release(EXECUTOR, task, InstanceWire.SCOPE,
            new HoldingService.Release(first.standing().receipt(), null), executing(EXECUTOR));
        Map<String, Long> before = DefinitionFixture.rowCounts();

        assertRefused(() -> claim(EXECUTOR, task, key, null),
            DispatchException.Reason.CLAIM_REQUIRED);
        HoldingFixture.claim(holdings, OTHER, task);
        Map<String, Long> held = DefinitionFixture.rowCounts();
        assertRefused(() -> claim(EXECUTOR, task, key, null), DispatchException.Reason.CLAIM_HELD);

        assertThat(before.get("holding") + 1).as("only the other's claim took the task")
            .isEqualTo(held.get("holding"));
        assertThat(DefinitionFixture.rowCounts()).as("the refused repeats wrote nothing")
            .isEqualTo(held);
    }

    @Test
    void a_repeat_after_the_lease_lapsed_names_the_lapse_and_takes_nothing() {
        TaskAddress task = HoldingFixture.sent(instances, tasks, "repeat-lapsed");
        String key = "lapsed-" + InstanceWire.suffix();
        claim(EXECUTOR, task, key, null);
        HoldingFixture.lapse(task);
        Map<String, Long> before = DefinitionFixture.rowCounts();

        assertRefused(() -> claim(EXECUTOR, task, key, null),
            DispatchException.Reason.LEASE_LAPSED);

        assertThat(DefinitionFixture.rowCounts()).isEqualTo(before);
    }

    @Test
    void the_same_key_with_other_arguments_or_on_another_call_is_refused() {
        TaskAddress task = HoldingFixture.sent(instances, tasks, "repeat-other");
        String key = "other-" + InstanceWire.suffix();
        claim(EXECUTOR, task, key, null);
        Map<String, Long> before = DefinitionFixture.rowCounts();

        assertRefused(() -> claim(EXECUTOR, task, key, Duration.ofHours(1)),
            DispatchException.Reason.IDEMPOTENCY_KEY_REUSED);
        TaskAddress elsewhere = HoldingFixture.sent(instances, tasks, "repeat-elsewhere");
        Map<String, Long> planted = DefinitionFixture.rowCounts();
        assertRefused(() -> claim(EXECUTOR, elsewhere, key, null),
            DispatchException.Reason.IDEMPOTENCY_KEY_REUSED);
        assertThat(planted.get("receipt")).isEqualTo(before.get("receipt"));
        assertThat(DefinitionFixture.rowCounts()).as("no refused repeat wrote anything")
            .isEqualTo(planted);

        String createKey = "create-" + InstanceWire.suffix();
        String selector = ai.kumbuka.dispatch.surface.TaskWire.plant("repeat-create", "review")
            .selector();
        tasks.create(HoldingFixture.COMMISSIONER, InstanceWire.SLUG, InstanceWire.SCOPE, selector,
            new TaskService.Create(null, new TaskService.Drafting("keyed", null, null, null,
                List.of(), null, null), null, List.of(), IdempotencyKey.of(createKey)),
            HoldingFixture.provenance(HoldingFixture.COMMISSIONER,
                DispatchPermission.TASK_COMMISSION));
        TaskAddress open = HoldingFixture.sent(instances, tasks, "repeat-across");
        Actor both = new Actor(HoldingFixture.COMMISSIONER.subject(),
            DispatchPermission.TASK_EXECUTE);
        assertRefused(() -> holdings.claim(both, open, InstanceWire.SCOPE,
                new HoldingService.Claim(null, IdempotencyKey.of(createKey)), executing(both)),
            DispatchException.Reason.IDEMPOTENCY_KEY_REUSED);
    }

    // -----------------------------------------------------------------------

    private TaskView claim(Actor actor, TaskAddress task, String key, Duration duration) {
        return holdings.claim(actor, task, InstanceWire.SCOPE,
            new HoldingService.Claim(duration, IdempotencyKey.of(key)), executing(actor));
    }

    private static void assertRefused(Runnable call, DispatchException.Reason reason) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(DispatchException.class,
            refused -> assertThat(refused.reason()).isEqualTo(reason));
    }

    /** The hash of the youngest receipt of the task, as the store holds it. */
    private static String youngestHash(TaskAddress task) {
        return String.valueOf(InstanceWire.rows("SELECT r.receipt_hash FROM dispatch.receipt r "
            + "JOIN dispatch.holding h ON h.id = r.holding_id WHERE h.task_id = ? "
            + "ORDER BY r.id DESC LIMIT 1", HoldingFixture.taskId(task)).get(0)
            .get("receipt_hash"));
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
