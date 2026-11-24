/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.repository.ActRepository;
import ai.kumbuka.dispatch.repository.DefinitionRepository;
import ai.kumbuka.dispatch.repository.InstanceRepository;
import ai.kumbuka.dispatch.repository.TaskRepository;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * The draft and the commission of a task against mocked repositories and a
 * mocked writer of acts: what each call writes, what it refuses before it
 * writes anything, and that a draft writes no act. That the rows stand in the
 * store as written, under the service role, is {@code TaskDraftIT}'s and
 * {@code TaskSendIT}'s.
 *
 * <p>Every expectation is written here from the order: the subs from 1, the
 * identifiers of the criteria as the service gives them, the token of a draft
 * as the instant of the clock, and the paths a refusal names.
 */
class TaskServiceModuleTest {

    private static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
    private static final String SLUG = "probe";
    private static final String SELECTOR = "sprint";
    private static final long VERSION = 21L;
    private static final long INSTANCE = 77L;
    private static final long ONLY = 31L;
    private static final Instant NOW = Instant.parse("2026-11-24T08:00:00.000001Z");
    private static final InstanceAddress PARENT = new InstanceAddress(SLUG, SELECTOR, 4);
    private static final TaskAddress FIRST = new TaskAddress(PARENT, 1);
    private static final Actor CONSOLE = new Actor("ada", DispatchPermission.TASK_COMMISSION);
    private static final Provenance PROVENANCE = new Provenance("ada", null,
        DispatchPermission.TASK_COMMISSION, Provenance.Path.INTERFACE, null);

    private TaskRepository tasks;
    private InstanceRepository instances;
    private DefinitionRepository definitions;
    private ActRepository actRows;
    private ActService acts;
    private InstanceService instanceService;
    private TaskService service;
    private Instance instance;
    private final List<Object> inserted = new ArrayList<>();
    private long nextId = 1000L;

    @BeforeEach
    void wire() {
        tasks = Mockito.mock(TaskRepository.class);
        instances = Mockito.mock(InstanceRepository.class);
        definitions = Mockito.mock(DefinitionRepository.class);
        actRows = Mockito.mock(ActRepository.class);
        acts = Mockito.mock(ActService.class);
        instanceService = Mockito.mock(InstanceService.class);
        service = new TaskService(tasks, instances, definitions, actRows, acts, instanceService,
            Clock.fixed(NOW, ZoneOffset.UTC));
        instance = Instance.started(SCOPE, 1L, 4L, VERSION, "a title");
        instance.id = INSTANCE;
        when(instances.lockInstance(SCOPE, SELECTOR, 4L)).thenReturn(Optional.of(instance));
        when(tasks.instance(SCOPE, SELECTOR, 4L)).thenReturn(Optional.of(instance));
        when(tasks.spentKey(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(tasks.insert(any())).thenAnswer(call -> {
            Object row = call.getArgument(0);
            inserted.add(row);
            if (row instanceof RuntimeRow written) {
                withId(written, nextId++);
            }
            return row;
        });
        declare(activity(ONLY, "review"));
    }

    // -----------------------------------------------------------------------
    // create
    // -----------------------------------------------------------------------

    @Test
    void a_create_with_parent_writes_the_task_and_its_workspace_and_no_act() {
        Map<String, Object> free = new LinkedHashMap<>();
        free.put("pr", List.of("https://example.org/pr/1"));

        TaskView view = service.create(CONSOLE, SLUG, SCOPE, SELECTOR, create(new TaskService
            .Drafting("a title", "code", null, null, List.of("first", "second"), free,
                "the text"), IdempotencyKey.NONE), PROVENANCE);

        assertThat(view.address().complete()).as("the first task of the instance is its .1 (D24)")
            .isEqualTo("dispatch://probe/sprint/4.1");
        assertThat(List.of(view.state(), view.conflictToken()))
            .as("a draft, whose token is the instant of the clock it was written at")
            .containsExactly("draft", NOW.toString());
        assertThat(view.open()).as("the calls of a draft are open to the console")
            .containsExactly(TaskCall.UPDATE, TaskCall.DELETE, TaskCall.SEND);
        assertThat(inserted).extracting(row -> row.getClass().getSimpleName())
            .as("the task, its draft, two criteria each with its text, the free fields and the "
                + "text of the commission, in that order")
            .containsExactly("Task", "Draft", "DraftCriterion", "DraftText", "DraftCriterion",
                "DraftText", "DraftFreeFields", "DraftText");
        Draft draft = (Draft) inserted.get(1);
        assertThat(List.of(draft.title(), draft.addressedRole().orElseThrow(),
                draft.activityId().orElseThrow()))
            .as("with the version's only activity, taken without being named")
            .containsExactly("a title", "code", ONLY);
        assertThat(inserted).filteredOn(DraftCriterion.class::isInstance)
            .extracting(row -> ((DraftCriterion) row).identifier())
            .as("the service gives the identifiers").containsExactly("c1", "c2");
        verify(acts, never()).write(any(), anyLong(), any(), any(), any(), any());
        verify(acts, never()).write(any(), anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void two_creates_in_one_instance_take_consecutive_subs() {
        TaskService.Create create = create(drafting(), IdempotencyKey.NONE);
        TaskView first = service.create(CONSOLE, SLUG, SCOPE, SELECTOR, create, PROVENANCE);
        TaskView second = service.create(CONSOLE, SLUG, SCOPE, SELECTOR, create, PROVENANCE);
        assertThat(List.of(first.address().sub(), second.address().sub())).containsExactly(1, 2);
    }

    @Test
    void a_key_is_written_after_the_callers_expired_keys_are_deleted() {
        service.create(CONSOLE, SLUG, SCOPE, SELECTOR,
            create(drafting(), IdempotencyKey.of("once")), PROVENANCE);

        InOrder order = Mockito.inOrder(tasks);
        order.verify(tasks).spentKey(SCOPE, "ada", "once", NOW.minus(TaskService.KEY_LIFE));
        order.verify(tasks).deleteExpiredKeys(SCOPE, "ada", NOW.minus(TaskService.KEY_LIFE));
        order.verify(tasks).insert(any(IdempotencyRecord.class));
        assertThat(inserted).filteredOn(IdempotencyRecord.class::isInstance).singleElement()
            .satisfies(row -> assertThat(((IdempotencyRecord) row).sameArguments(
                TaskService.digest(SLUG, SELECTOR, create(drafting(), IdempotencyKey.of("once")))))
                .as("the row carries the digest of the call's arguments").isTrue());
    }

    @Test
    void a_repeat_with_the_same_arguments_answers_the_task_and_writes_nothing() {
        TaskService.Create create = create(drafting(), IdempotencyKey.of("once"));
        Task task = withId(Task.drafted(SCOPE, INSTANCE, 3), 900L);
        IdempotencyRecord spent = new IdempotencyRecord(SCOPE, "ada", "once", 900L,
            TaskService.digest(SLUG, SELECTOR, create), NOW);
        when(tasks.spentKey(eq(SCOPE), eq("ada"), eq("once"), any())).thenReturn(Optional.of(spent));
        when(tasks.task(SCOPE, 900L)).thenReturn(Optional.of(task));
        when(tasks.instance(SCOPE, INSTANCE)).thenReturn(Optional.of(instance));
        when(tasks.draft(SCOPE, 900L)).thenReturn(Optional.of(draft(900L, null)));

        TaskView view = service.create(CONSOLE, SLUG, SCOPE, SELECTOR, create, PROVENANCE);

        assertThat(view.address().complete()).isEqualTo("dispatch://probe/sprint/4.3");
        assertThat(inserted).as("the repeat wrote nothing").isEmpty();
    }

    @Test
    void a_repeat_with_other_arguments_is_refused_as_reused() {
        IdempotencyRecord spent = new IdempotencyRecord(SCOPE, "ada", "once", 900L,
            TaskService.digest(SLUG, SELECTOR, create(drafting(), IdempotencyKey.of("once"))), NOW);
        when(tasks.spentKey(eq(SCOPE), eq("ada"), eq("once"), any())).thenReturn(Optional.of(spent));

        assertThatThrownBy(() -> service.create(CONSOLE, SLUG, SCOPE, SELECTOR, create(
                new TaskService.Drafting("another title", null, null, null, List.of(), null, null),
                IdempotencyKey.of("once")), PROVENANCE))
            .isInstanceOfSatisfying(DispatchException.class, refused -> assertThat(
                refused.reason()).isEqualTo(DispatchException.Reason.IDEMPOTENCY_KEY_REUSED));
        assertThat(inserted).isEmpty();
    }

    @Test
    void the_activity_of_a_version_of_two_is_named_and_one_it_declares() {
        declare(activity(ONLY, "review"), activity(32L, "build"));
        assertRefused(() -> service.create(CONSOLE, SLUG, SCOPE, SELECTOR,
                create(drafting(), IdempotencyKey.NONE), PROVENANCE),
            DispatchException.Reason.ACTIVITY_MISSING, List.of("fields.activity"));
        assertRefused(() -> service.create(CONSOLE, SLUG, SCOPE, SELECTOR, create(
                new TaskService.Drafting("a title", null, "deploy", null, List.of(), null, null),
                IdempotencyKey.NONE), PROVENANCE),
            DispatchException.Reason.ACTIVITY_UNDECLARED, List.of("fields.activity"));
        assertThat(inserted).as("nothing was written for either").isEmpty();

        service.create(CONSOLE, SLUG, SCOPE, SELECTOR, create(new TaskService.Drafting("a title",
            null, "build", null, List.of(), null, null), IdempotencyKey.NONE), PROVENANCE);
        assertThat(((Draft) inserted.get(1)).activityId()).as("RED STATE, observed: named, it is "
            + "taken").contains(32L);
    }

    @Test
    void a_version_without_activities_leaves_the_draft_without_one() {
        declare();
        service.create(CONSOLE, SLUG, SCOPE, SELECTOR, create(drafting(), IdempotencyKey.NONE),
            PROVENANCE);
        assertThat(((Draft) inserted.get(1)).activityId()).isEmpty();
    }

    @Test
    void a_closed_parent_takes_no_task() {
        instance.close(Instance.Resolution.CONCLUDED);
        assertThatThrownBy(() -> service.create(CONSOLE, SLUG, SCOPE, SELECTOR,
                create(drafting(), IdempotencyKey.NONE), PROVENANCE))
            .isInstanceOfSatisfying(DispatchException.class, refused -> assertThat(
                refused.reason()).isEqualTo(DispatchException.Reason.INSTANCE_CLOSED));
        assertThat(inserted).isEmpty();
    }

    @Test
    void a_draft_concerns_a_task_of_its_own_instance_alone() {
        TaskAddress elsewhere = new TaskAddress(new InstanceAddress(SLUG, SELECTOR, 5), 1);
        assertRefused(() -> service.create(CONSOLE, SLUG, SCOPE, SELECTOR, create(
                new TaskService.Drafting("a title", null, null, elsewhere, List.of(), null, null),
                IdempotencyKey.NONE), PROVENANCE),
            DispatchException.Reason.CONCERNS_UNRELATED, List.of("fields.concerns"));
        when(tasks.task(SCOPE, INSTANCE, 1)).thenReturn(Optional.of(
            withId(Task.drafted(SCOPE, INSTANCE, 1), 500L)));
        service.create(CONSOLE, SLUG, SCOPE, SELECTOR, create(new TaskService.Drafting("a title",
            null, null, FIRST, List.of(), null, null), IdempotencyKey.NONE), PROVENANCE);
        assertThat(((Draft) inserted.get(1)).concernsTaskId())
            .as("RED STATE, observed: a task of the same instance is taken").contains(500L);
    }

    @Test
    void the_short_form_starts_an_instance_titled_as_the_task_and_adds_the_task_first() {
        Selector counter = Mockito.mock(Selector.class);
        when(instances.lockSelector(SCOPE, SELECTOR)).thenReturn(Optional.of(counter));
        when(instanceService.start(eq(CONSOLE), eq(SLUG), eq(SCOPE), eq(SELECTOR), any(),
                eq(PROVENANCE)))
            .thenReturn(new InstanceView(INSTANCE, PARENT, 1, "a title", "running", null,
                NOW.toString(), List.of()));

        TaskView view = service.create(CONSOLE, SLUG, SCOPE, SELECTOR, new TaskService.Create(
            null, drafting(), "trigger-1", List.of("ref-1"), IdempotencyKey.NONE), PROVENANCE);

        assertThat(view.address().complete()).isEqualTo("dispatch://probe/sprint/4.1");
        verify(instanceService).start(CONSOLE, SLUG, SCOPE, SELECTOR, new InstanceService.Start(
            "a title", null, "trigger-1", List.of(), List.of("ref-1"), Map.of()), PROVENANCE);
    }

    // -----------------------------------------------------------------------
    // update
    // -----------------------------------------------------------------------

    @Test
    void an_update_is_fenced_by_the_token_of_the_draft() {
        Task task = draftAt(1);
        Draft draft = draft(task.id(), ONLY);
        when(tasks.lockDraft(SCOPE, task.id())).thenReturn(Optional.of(draft));

        assertRefused(() -> service.update(CONSOLE, FIRST, SCOPE, update(null, "new", Set.of())),
            DispatchException.Reason.CONFLICT_TOKEN_MISSING, List.of());
        assertThatThrownBy(() -> service.update(CONSOLE, FIRST, SCOPE,
                update("2026-01-01T00:00:00Z", "new", Set.of())))
            .isInstanceOfSatisfying(DispatchException.class, refused -> {
                assertThat(refused.reason())
                    .isEqualTo(DispatchException.Reason.CONFLICT_TOKEN_STALE);
                assertThat(refused.task()).as("with the task as it stands")
                    .hasValueSatisfying(at -> assertThat(at.conflictToken())
                        .isEqualTo(NOW.toString()));
            });
        assertThat(draft.title()).as("and nothing changed").isEqualTo("a title");

        TaskView updated = service.update(CONSOLE, FIRST, SCOPE,
            update(NOW.toString(), "new", Set.of()));
        assertThat(draft.title()).as("RED STATE, observed: under the current token it changes")
            .isEqualTo("new");
        assertThat(updated.conflictToken()).as("and its token moves on one microsecond")
            .isEqualTo(NOW.plusNanos(1000).toString());
    }

    @Test
    void an_update_that_changes_nothing_keeps_the_token() {
        Task task = draftAt(1);
        Draft draft = draft(task.id(), ONLY);
        when(tasks.lockDraft(SCOPE, task.id())).thenReturn(Optional.of(draft));

        TaskView same = service.update(CONSOLE, FIRST, SCOPE,
            update(NOW.toString(), "a title", Set.of()));

        assertThat(same.conflictToken()).isEqualTo(NOW.toString());
        verify(tasks, never()).flush();
    }

    @Test
    void clear_empties_exactly_the_fields_it_names() {
        Task task = draftAt(1);
        Draft draft = new Draft(SCOPE, task.id(), new Draft.Control("a title", ONLY, null, "code"),
            NOW);
        withId(draft, 600L);
        when(tasks.lockDraft(SCOPE, task.id())).thenReturn(Optional.of(draft));
        when(tasks.texts(SCOPE, 600L)).thenReturn(List.of(DraftText.commission(SCOPE, 600L,
            "the text")));
        when(tasks.freeFields(SCOPE, 600L)).thenReturn(Optional.of(new DraftFreeFields(SCOPE,
            600L, Map.of("k", "v"))));

        service.update(CONSOLE, FIRST, SCOPE, update(NOW.toString(), null,
            Set.of(TaskService.Clearable.APPARATUS, TaskService.Clearable.TEXT)));

        assertThat(draft.addressedRole()).as("the role is cleared").isEmpty();
        assertThat(draft.activityId()).as("the activity stays").contains(ONLY);
        verify(tasks).deleteCommissionText(SCOPE, 600L);
        verify(tasks, never()).deleteFreeFields(SCOPE, 600L);
        verify(tasks, never()).deleteCriteria(SCOPE, 600L);
    }

    @Test
    void outside_draft_update_delete_and_send_are_refused_with_the_task() {
        Task task = draftAt(1);
        task.send(new Commission(SCOPE, 1L, INSTANCE, draft(task.id(), ONLY), ONLY));
        when(actRows.lastTaskAt(SCOPE, task.id())).thenReturn(Optional.of(NOW));

        for (Runnable call : List.<Runnable>of(
                () -> service.update(CONSOLE, FIRST, SCOPE, update(NOW.toString(), "x", Set.of())),
                () -> service.delete(CONSOLE, FIRST, SCOPE, NOW.toString()),
                () -> service.send(CONSOLE, FIRST, SCOPE, NOW.toString(), PROVENANCE))) {
            assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(DispatchException.class, refused -> {
                    assertThat(refused.reason())
                        .isEqualTo(DispatchException.Reason.TRANSITION_NOT_PERMITTED);
                    assertThat(refused.task()).hasValueSatisfying(at -> {
                        assertThat(at.state()).isEqualTo("open");
                        assertThat(at.open()).as("nothing of a draft is open on it").isEmpty();
                    });
                });
        }
        verify(tasks, never()).deleteTask(any(), any());
    }

    // -----------------------------------------------------------------------
    // delete and send
    // -----------------------------------------------------------------------

    @Test
    void a_delete_empties_the_workspace_then_deletes_the_task() {
        Task task = draftAt(1);
        Draft draft = draft(task.id(), ONLY);
        when(tasks.lockDraft(SCOPE, task.id())).thenReturn(Optional.of(draft));

        TaskView deleted = service.delete(CONSOLE, FIRST, SCOPE, NOW.toString());

        assertThat(deleted.address()).isEqualTo(FIRST);
        InOrder order = Mockito.inOrder(tasks);
        order.verify(tasks).deleteWorkspace(SCOPE, draft);
        order.verify(tasks).deleteTask(SCOPE, task);
    }

    @Test
    void a_send_writes_one_act_and_the_commission_from_the_draft() {
        Task task = draftAt(1);
        Draft draft = new Draft(SCOPE, task.id(), new Draft.Control("a title", ONLY, null, "code"),
            NOW);
        withId(draft, 600L);
        when(tasks.lockDraft(SCOPE, task.id())).thenReturn(Optional.of(draft));
        DraftCriterion criterion = withId(new DraftCriterion(SCOPE, 600L, 1), 610L);
        when(tasks.criteria(SCOPE, 600L)).thenReturn(List.of(criterion));
        when(tasks.texts(SCOPE, 600L)).thenReturn(List.of(
            DraftText.criterion(SCOPE, 600L, 610L, "the criterion"),
            DraftText.commission(SCOPE, 600L, "the text")));
        when(tasks.freeFields(SCOPE, 600L)).thenReturn(Optional.of(new DraftFreeFields(SCOPE,
            600L, Map.of("k", "v"))));
        Act act = Mockito.mock(Act.class);
        when(act.id()).thenReturn(800L);
        when(acts.write(SCOPE, INSTANCE, Act.Kind.SEND, Act.State.OPEN, task.id(), PROVENANCE))
            .thenReturn(act);
        when(actRows.lastTaskAt(SCOPE, task.id())).thenReturn(Optional.of(NOW.plusSeconds(1)));

        TaskView sent = service.send(CONSOLE, FIRST, SCOPE, NOW.toString(), PROVENANCE);

        assertThat(inserted).extracting(row -> row.getClass().getSimpleName())
            .containsExactly("Commission", "CommissionCriterion", "Content", "Content",
                "FreeFields");
        Commission commission = (Commission) inserted.get(0);
        assertThat(List.of(commission.title(), commission.activityId(),
                commission.addressedRole().orElseThrow()))
            .containsExactly("a title", ONLY, "code");
        assertThat(List.of(sent.state(), sent.conflictToken()))
            .as("open, under the time of its last act (D29)")
            .containsExactly("open", NOW.plusSeconds(1).toString());
        assertThat(task.title()).as("the projection takes the commission").contains("a title");
        verify(tasks).deleteWorkspace(SCOPE, draft);
    }

    @Test
    void a_draft_without_an_activity_is_not_sent() {
        Task task = draftAt(1);
        when(tasks.lockDraft(SCOPE, task.id())).thenReturn(Optional.of(draft(task.id(), null)));

        assertThatThrownBy(() -> service.send(CONSOLE, FIRST, SCOPE, NOW.toString(), PROVENANCE))
            .isInstanceOfSatisfying(DispatchException.class, refused -> {
                assertThat(refused.reason()).isEqualTo(DispatchException.Reason.DRAFT_INCOMPLETE);
                assertThat(refused.task()).isPresent();
            });
        verify(acts, never()).write(any(), anyLong(), any(), any(), any(), any());
        assertThat(inserted).isEmpty();
    }

    // -----------------------------------------------------------------------
    // the digest
    // -----------------------------------------------------------------------

    @Test
    void the_digest_holds_the_free_fields_in_one_form_whatever_their_order() {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("a", 1);
        one.put("b", List.of("x", 2.50));
        Map<String, Object> other = new LinkedHashMap<>();
        other.put("b", List.of("x", 2.5));
        other.put("a", 1L);
        assertThat(TaskService.canonical(one)).isEqualTo(TaskService.canonical(other));
        assertThat(TaskService.same(one, Map.of("a", 2))).as("RED STATE, observed: another value "
            + "is another document").isFalse();
        assertThat(TaskService.digest(SLUG, SELECTOR, create(new TaskService.Drafting("t", null,
                null, null, List.of("ab", "c"), null, null), IdempotencyKey.NONE)))
            .as("the criteria are counted, so their texts cannot shift into each other")
            .isNotEqualTo(TaskService.digest(SLUG, SELECTOR, create(new TaskService.Drafting("t",
                null, null, null, List.of("a", "bc"), null, null), IdempotencyKey.NONE)));
    }

    // -----------------------------------------------------------------------

    private void declare(Activity... declared) {
        DefinitionVersion version = Mockito.mock(DefinitionVersion.class);
        when(definitions.bound(SCOPE, VERSION)).thenReturn(Optional.of(
            new DefinitionRepository.Declared(version, List.of(declared), List.of(), List.of(),
                List.of(), List.of(), List.of())));
    }

    private static Activity activity(long id, String name) {
        Activity activity = Mockito.mock(Activity.class);
        when(activity.id()).thenReturn(id);
        when(activity.name()).thenReturn(name);
        return activity;
    }

    private static TaskService.Drafting drafting() {
        return new TaskService.Drafting("a title", null, null, null, List.of(), null, null);
    }

    private static TaskService.Create create(TaskService.Drafting drafting, IdempotencyKey key) {
        return new TaskService.Create(PARENT, drafting, null, List.of(), key);
    }

    private static TaskService.Update update(@Nullable String token, @Nullable String title,
                                             Set<TaskService.Clearable> clear) {
        return new TaskService.Update(token, title, null, null, null, null, null, null, clear);
    }

    /** A task in draft at a sub of the instance, locked as the store would answer it. */
    private Task draftAt(int sub) {
        Task task = withId(Task.drafted(SCOPE, INSTANCE, sub), 500L + sub);
        when(tasks.lockTask(SCOPE, INSTANCE, sub)).thenReturn(Optional.of(task));
        return task;
    }

    private static Draft draft(long taskId, @Nullable Long activity) {
        return withId(new Draft(SCOPE, taskId, new Draft.Control("a title", activity, null, null),
            NOW), 600L);
    }

    private static void assertRefused(Runnable call, DispatchException.Reason reason,
                                      List<String> offenders) {
        assertThatThrownBy(call::run)
            .isInstanceOfSatisfying(DispatchException.class, refused -> {
                assertThat(refused.reason()).isEqualTo(reason);
                assertThat(refused.offenders()).isEqualTo(offenders);
            });
    }

    /** The surrogate the store would draw at the insert. */
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
