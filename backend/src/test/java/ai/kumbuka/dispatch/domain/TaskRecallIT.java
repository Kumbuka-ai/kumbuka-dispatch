/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static ai.kumbuka.dispatch.domain.TaskStage.C;
import static ai.kumbuka.dispatch.domain.TaskStage.H;
import static ai.kumbuka.dispatch.domain.TaskStage.K;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The recall of a sent task up to its first take-up, the instant that take-up
 * leaves, and the addendum that is added only while the work runs (TAR-0004
 * section 3, DEC-0014, REQ-0035), against a running database under the
 * service role.
 *
 * <p>Every refusal is held to its reason and to the words that carry state,
 * cause and way out; every task is staged through the kernel, except a lapse
 * and the traces of a take-up by an image that did not record its instant,
 * which no call of this kernel writes and which are written as the
 * administrator.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskRecallIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final String SELECTOR = "sprint";

    @Inject TaskService tasks;
    @Inject TenantContext tenantContext;

    private AutoCloseable binding;
    private TaskStage stage;

    @BeforeEach
    void freshTenant() {
        UUID tenant = UUID.randomUUID();
        DomainFixture.declareSelector(tenant, SCOPE, SELECTOR);
        binding = tenantContext.bind(tenant);
        stage = new TaskStage(tasks, SCOPE);
    }

    @AfterEach
    void unbind() throws Exception {
        binding.close();
    }

    // =======================================================================
    // Criterion 1: recalled, changed, sent again; the old text is gone
    // =======================================================================

    @Test
    void an_open_task_never_taken_up_is_recalled_changed_and_sent_again() {
        TaskStage.Staged s = stage.open(SELECTOR);
        assertThat(tasks.read(SCOPE, s.address(), C).next())
            .as("recall is listed as open to the commissioner").contains(TaskVerb.RECALL);
        assertThat(tasks.query(SCOPE, SELECTOR, TaskFilter.none(), TaskStage.page(10), C)
                .tasks().get(0).next())
            .as("and a listing lists it alike").contains(TaskVerb.RECALL);

        TaskView recalled = recall(s, C);
        assertThat(recalled.state()).isEqualTo(TaskState.DRAFT);
        assertThat(TaskStage.row(s.identity()).state()).isEqualTo("draft");

        TaskView changed = tasks.update(SCOPE, s.address(),
            new TaskService.Draft("the changed title", null, "the changed commission", null),
            recalled.conflictToken(), C);
        tasks.act(SCOPE, s.address(), TaskVerb.SEND,
            TaskCall.by(C).withConflictToken(changed.conflictToken()));

        TaskView sent = tasks.read(SCOPE, s.address(), C);
        assertThat(sent.state()).isEqualTo(TaskState.OPEN);
        assertThat(sent.title()).isEqualTo("the changed title");
        assertThat(tasks.readText(SCOPE, s.address(), TextPart.DISPATCH, C).entries())
            .extracting(TaskTextView.Entry::text)
            .containsExactly("the changed commission");
        assertThat(TaskStage.texts(s.identity()))
            .as("the text sent first is kept nowhere (DEC-0014)")
            .containsExactly("dispatch=the changed commission");
    }

    // =======================================================================
    // Criterion 2: refused once taken up, on an occupied root, outside open
    // =======================================================================

    @Test
    void recall_after_claim_and_release_is_refused() {
        TaskStage.Staged s = stage.active(SELECTOR);
        tasks.act(SCOPE, s.address(), TaskVerb.RELEASE,
            TaskCall.by(H).withReceipt(s.receipt()));

        assertFrozen(s);
    }

    @Test
    void recall_after_claim_and_defer_is_refused_before_and_after_its_instant() {
        TaskStage.Staged s = stage.deferred(SELECTOR);
        assertFrozen(s);

        PlatformFixture.run("UPDATE dispatch.task SET not_before = now() - interval '1 minute' "
            + "WHERE uuid = '" + s.identity() + "'");
        assertThat(tasks.read(SCOPE, s.address(), C).state()).isEqualTo(TaskState.OPEN);
        assertFrozen(s);
    }

    @Test
    void recall_after_a_lapsed_lease_is_refused() {
        TaskStage.Staged s = stage.lapse(stage.active(SELECTOR));
        assertThat(tasks.read(SCOPE, s.address(), C).state()).isEqualTo(TaskState.OPEN);

        assertFrozen(s);
    }

    @Test
    void recall_of_a_root_with_a_task_under_it_is_refused_whatever_the_task_s_state() {
        TaskStage.Staged root = stage.open(SELECTOR);
        TaskView child = tasks.create(SCOPE, SELECTOR, root.address().number(),
            new TaskService.Draft("a child", "code", "the child's commission", null), C,
            IdempotencyKey.NONE);
        assertOccupied(root, "a draft child");

        tasks.act(SCOPE, child.address(), TaskVerb.SEND,
            TaskCall.by(C).withConflictToken(child.conflictToken()));
        assertOccupied(root, "an open child");

        TaskView sent = tasks.read(SCOPE, child.address(), C);
        tasks.act(SCOPE, child.address(), TaskVerb.WITHDRAW,
            TaskCall.by(C).withConflictToken(sent.conflictToken()));
        assertOccupied(root, "a closed child");

        assertThat(tasks.query(SCOPE, SELECTOR, TaskFilter.none(), TaskStage.page(10), C)
                .tasks().get(0).next())
            .as("a listing reads the bracket too, and lists no recall on the root")
            .doesNotContain(TaskVerb.RECALL);
    }

    @Test
    void a_child_never_taken_up_is_recalled_while_its_root_stays_open() {
        TaskStage.Staged root = stage.open(SELECTOR);
        TaskView child = tasks.create(SCOPE, SELECTOR, root.address().number(),
            new TaskService.Draft("a child", "code", "the child's commission", null), C,
            IdempotencyKey.NONE);
        tasks.act(SCOPE, child.address(), TaskVerb.SEND,
            TaskCall.by(C).withConflictToken(child.conflictToken()));
        TaskView sent = tasks.read(SCOPE, child.address(), C);

        TaskView recalled = tasks.act(SCOPE, child.address(), TaskVerb.RECALL,
            TaskCall.by(C).withConflictToken(sent.conflictToken()));
        assertThat(recalled.state()).isEqualTo(TaskState.DRAFT);
        assertThat(tasks.read(SCOPE, root.address(), C).state()).isEqualTo(TaskState.OPEN);
    }

    @Test
    void recall_of_a_task_that_is_not_open_is_refused_for_its_state() {
        for (String situation : List.of("draft", "active", "asked", "waiting", "delivered",
                "closed")) {
            TaskStage.Staged s = stage.stage(situation, SELECTOR);
            assertThatThrownBy(() -> recall(s, C))
                .as("recall in %s", situation)
                .isInstanceOfSatisfying(DispatchException.class, e -> {
                    assertThat(e.reason()).isEqualTo(
                        DispatchException.Reason.TRANSITION_NOT_PERMITTED);
                    assertThat(e.getMessage()).as("names the state and where it applies")
                        .contains(" is " + TaskStage.row(s.identity()).state())
                        .contains("recall applies only in [open]");
                });
        }
    }

    // =======================================================================
    // Criterion 3: the commissioner's call, under the current token
    // =======================================================================

    @Test
    void recall_by_an_identity_without_the_commissioning_capacity_is_refused() {
        TaskStage.Staged s = stage.open(SELECTOR);
        for (Actor executor : List.of(H, K)) {
            TaskHoldingIT.assertRefused(() -> recall(s, executor),
                DispatchException.Reason.ACTOR_UNKNOWN);
        }
        assertThat(tasks.read(SCOPE, s.address(), H).next()).doesNotContain(TaskVerb.RECALL);
        assertThat(TaskStage.row(s.identity()).state()).isEqualTo("open");
    }

    @Test
    void recall_under_a_stale_token_is_refused() {
        TaskStage.Staged s = stage.open(SELECTOR);
        String stale = stage.token(s);
        // A round trip through draft writes the task twice and moves its token.
        TaskView recalled = recall(s, C);
        tasks.act(SCOPE, s.address(), TaskVerb.SEND,
            TaskCall.by(C).withConflictToken(recalled.conflictToken()));

        TaskHoldingIT.assertRefused(() -> tasks.act(SCOPE, s.address(), TaskVerb.RECALL,
                TaskCall.by(C).withConflictToken(stale)),
            DispatchException.Reason.CONFLICT_TOKEN_STALE);
    }

    // =======================================================================
    // Criterion 4: the first take-up, stored once and left as it stands
    // =======================================================================

    @Test
    void the_first_take_up_is_stored_at_the_first_claim_and_never_changed() {
        TaskStage.Staged open = stage.open(SELECTOR);
        assertThat(TaskStage.row(open.identity()).firstTakenAt()).as("never taken up").isNull();

        TaskStage.Staged s = stage.claimedBy(open, H);
        Instant first = TaskStage.row(s.identity()).firstTakenAt();
        assertThat(first).as("stored at the first claim").isNotNull();

        tasks.act(SCOPE, s.address(), TaskVerb.RELEASE, TaskCall.by(H).withReceipt(s.receipt()));
        assertFirstTakeUp(s, first, "after release");

        s = stage.claimedBy(s, K);
        assertFirstTakeUp(s, first, "after a second claim");
        tasks.act(SCOPE, s.address(), TaskVerb.DEFER, TaskCall.by(K).withReceipt(s.receipt())
            .with(new TaskInput.Deferral(Instant.now().plusSeconds(3600), null)));
        assertFirstTakeUp(s, first, "after defer");

        PlatformFixture.run("UPDATE dispatch.task SET not_before = now() - interval '1 minute' "
            + "WHERE uuid = '" + s.identity() + "'");
        s = stage.lapse(stage.claimedBy(s, H));
        assertFirstTakeUp(s, first, "after a lapsed lease");

        s = stage.claimedBy(s, K);
        tasks.act(SCOPE, s.address(), TaskVerb.DELIVER, TaskCall.by(K).withReceipt(s.receipt())
            .with(new TaskInput.Delivery("the answer", null)));
        tasks.act(SCOPE, s.address(), TaskVerb.REWORK, TaskCall.by(C)
            .withConflictToken(stage.token(s)).with(new TaskInput.RequiredRemark("once more")));
        assertFirstTakeUp(s, first, "after rework");
    }

    @Test
    void a_stock_task_without_the_instant_is_taken_up_where_its_traces_say_so() {
        TaskStage.Staged lapses = stage.open(SELECTOR);
        PlatformFixture.run("UPDATE dispatch.task SET lapse_count = 1 WHERE uuid = '"
            + lapses.identity() + "'");
        assertFrozen(lapses);

        for (String type : List.of("return", "question", "answer", "remark")) {
            TaskStage.Staged traced = stage.open(SELECTOR);
            PlatformFixture.run("INSERT INTO dispatch.task_text (tenant_id, scope_id, task_id, "
                + "text_type, text, created_by) SELECT tenant_id, scope_id, id, '" + type
                + "', 'a trace', 'executor-h' FROM dispatch.task WHERE uuid = '"
                + traced.identity() + "'");
            assertThat(TaskStage.row(traced.identity()).firstTakenAt()).isNull();
            assertFrozen(traced);
        }

        // An addendum to the commission, as the kernel before this one attached
        // to an open task: the commissioner's, no trace of a take-up.
        TaskStage.Staged untouched = stage.open(SELECTOR);
        PlatformFixture.run("INSERT INTO dispatch.task_text (tenant_id, scope_id, task_id, "
            + "text_type, addendum_suffix, text, created_by) SELECT tenant_id, scope_id, id, "
            + "'dispatch', 'a', 'an addendum', 'commissioner' FROM dispatch.task WHERE uuid = '"
            + untouched.identity() + "'");
        assertThat(recall(untouched, C).state())
            .as("no lapse, and no text but the commission and its addenda: never taken up")
            .isEqualTo(TaskState.DRAFT);
    }

    // =======================================================================
    // Criterion 5: an addendum only while the work runs
    // =======================================================================

    @Test
    void annotate_is_refused_in_open_delivered_and_closed_for_every_part() {
        for (String situation : List.of("open", "delivered", "closed")) {
            for (TextType part : TextType.values()) {
                TaskStage.Staged s = stage.stage(situation, SELECTOR);
                Actor writer = part == TextType.DISPATCH || part == TextType.ANSWER ? C : H;
                List<String> before = TaskStage.texts(s.identity());
                assertThatThrownBy(() -> tasks.annotate(SCOPE, s.address(), part, "x", writer,
                        IdempotencyKey.NONE))
                    .as("%s on %s", part, situation)
                    .isInstanceOfSatisfying(DispatchException.class, e -> {
                        assertThat(e.reason()).isEqualTo(
                            DispatchException.Reason.TRANSITION_NOT_PERMITTED);
                        assertThat(e.getMessage()).contains(" is " + situation);
                    });
                assertThat(TaskStage.texts(s.identity())).as("nothing attached")
                    .isEqualTo(before);
            }
        }
    }

    @Test
    void the_refusal_names_recall_on_an_open_task_and_rework_on_a_delivered_one() {
        TaskStage.Staged open = stage.open(SELECTOR);
        assertThatThrownBy(() -> tasks.annotate(SCOPE, open.address(), TextType.DISPATCH, "x",
                C, IdempotencyKey.NONE))
            .hasMessageContaining("recall takes the commission back to draft");
        TaskStage.Staged delivered = stage.stage("delivered", SELECTOR);
        assertThatThrownBy(() -> tasks.annotate(SCOPE, delivered.address(), TextType.DISPATCH,
                "x", C, IdempotencyKey.NONE))
            .hasMessageContaining("Rework sends the answer back");
    }

    @Test
    void annotate_is_accepted_while_the_work_runs_active_and_on_hold() {
        TaskStage.Staged active = stage.active(SELECTOR);
        assertThat(note(active, TextType.DISPATCH, C).texts())
            .contains(new TaskView.TextHead(TextType.DISPATCH, "a"));

        for (String situation : List.of("asked", "waiting", "parked")) {
            TaskStage.Staged held = stage.stage(situation, SELECTOR);
            assertThat(note(held, TextType.DISPATCH, C).texts())
                .as("on_hold, %s", situation)
                .contains(new TaskView.TextHead(TextType.DISPATCH, "a"));
        }
        TaskStage.Staged asked = stage.stage("asked", SELECTOR);
        assertThat(note(asked, TextType.QUESTION, H).texts())
            .as("the holder adds to its own question")
            .contains(new TaskView.TextHead(TextType.QUESTION, "a"));
    }

    // -----------------------------------------------------------------------

    private TaskView recall(TaskStage.Staged s, Actor caller) {
        return tasks.act(SCOPE, s.address(), TaskVerb.RECALL,
            TaskCall.by(caller).withConflictToken(stage.token(s)));
    }

    private TaskView note(TaskStage.Staged s, TextType part, Actor writer) {
        return tasks.annotate(SCOPE, s.address(), part, "an addendum", writer,
            IdempotencyKey.NONE);
    }

    private void assertFrozen(TaskStage.Staged s) {
        assertThat(tasks.read(SCOPE, s.address(), C).next())
            .as("recall is not listed on a task taken up").doesNotContain(TaskVerb.RECALL);
        assertThatThrownBy(() -> recall(s, C))
            .isInstanceOfSatisfying(DispatchException.class, e -> {
                assertThat(e.reason()).isEqualTo(DispatchException.Reason.CONTENT_FROZEN);
                assertThat(e.getMessage())
                    .as("names state, cause and way out")
                    .contains(" is open and was taken up")
                    .contains("first take-up froze its content")
                    .contains("Withdraw the task");
            });
        assertThat(TaskStage.row(s.identity()).state()).as("nothing moved").isNotEqualTo("draft");
    }

    private void assertOccupied(TaskStage.Staged root, String what) {
        assertThat(tasks.read(SCOPE, root.address(), C).next())
            .as("recall is not listed on a root with %s", what).doesNotContain(TaskVerb.RECALL);
        assertThatThrownBy(() -> recall(root, C))
            .as("a root with %s", what)
            .isInstanceOfSatisfying(DispatchException.class, e -> {
                assertThat(e.reason()).isEqualTo(DispatchException.Reason.BRACKET_OCCUPIED);
                assertThat(e.getMessage())
                    .contains(" is open, a bracket root with tasks under it")
                    .contains("Withdraw the root with its bracket");
            });
    }

    private static void assertFirstTakeUp(TaskStage.Staged s, Instant first, String when) {
        assertThat(TaskStage.row(s.identity()).firstTakenAt()).as(when).isEqualTo(first);
    }
}
