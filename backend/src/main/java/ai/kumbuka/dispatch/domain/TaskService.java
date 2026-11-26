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
import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The draft and the commission of a task (REA-0010, catalogues "Calls that
 * are not transitions" and "Transitions of a task"): {@code create} writes a
 * task in draft, {@code update} changes its draft, {@code delete} deletes it,
 * and {@code send} makes the draft the task's commission.
 *
 * <p><strong>A draft writes no act.</strong> {@code create}, {@code update}
 * and {@code delete} work on the draft workspace -- {@code draft}, its
 * criteria, its free fields and its texts -- and leave no trace (data model
 * A.7): the trace of a task begins with its first {@code send}. The conflict
 * token of a draft is its {@code changed_at}, written at every create and
 * update as the later of the clock and the previous value plus one
 * microsecond, under the lock of the draft's row; from the first send on it is
 * the time of the task's last act (D29).
 *
 * <p>{@code create} with a parent adds the task to a running instance, at the
 * sub its counter hands out under the lock of the instance's row, from 1
 * (D24). Without a parent it is the short form: it starts an instance as
 * {@code start} does, with the task's title as the instance's title, its
 * optional trigger and references and no field values, and adds the task as
 * its first. A task takes an activity of the version its instance is bound to:
 * the only one where the version declares one and the call names none (S5).
 *
 * <p>{@code send} writes, in one transaction and under the lock of the draft's
 * row, the act {@code send}, the commission with its criteria, the free fields
 * and the texts of the commission and its criteria as content, each anchored
 * at the act; it sets the task's projection from the commission, empties the
 * draft workspace and opens the task (DEC-0014). What the draft does not carry,
 * the commission does not carry either. {@code delete} removes the task and
 * its draft outright (DEC-0015).
 *
 * <p>An idempotency key of {@code create} belongs to its caller, holds for
 * twenty-four hours from its first use, and hangs on the task (C10.4, D30): a
 * repeat with the same key and the same arguments answers the task the first
 * call created and writes nothing, also after its send; with other arguments,
 * or once the draft is deleted, it is refused. A repeat is decided under the
 * lock the create itself takes first -- the instance's, or for the short form
 * the selector's. Two calls with the same arguments take the same lock, so the
 * second waits for the first and answers what it created. Two calls with
 * other arguments may take different locks and both find the key unspent;
 * then the second waits at the store's one key per caller and scope until the
 * first ends, and where the first wrote the key, the second is refused with
 * {@code IDEMPOTENCY_KEY_REUSED} and its transaction is rolled back, so it
 * leaves no row.
 *
 * <p>Nothing here checks the caller's permission: the call checked it before
 * it was made, and the provenance it hands in names it. The actor is read for
 * the calls the answer names as open, and as the owner of a key.
 *
 * <p>Logs nothing (DEC-0051, C4).
 */
@ApplicationScoped
@TenantBound
public class TaskService {

    /** How long a spent key answers the call it was spent on (C10.4). */
    static final Duration KEY_LIFE = Duration.ofHours(24);

    static final String ACTIVITY = "fields.activity";
    static final String CONCERNS = "fields.concerns";

    private final TaskRepository tasks;
    private final InstanceRepository instances;
    private final DefinitionRepository definitions;
    private final ActRepository actRows;
    private final ActService acts;
    private final InstanceService instanceService;
    private final Clock clock;

    @Inject
    TaskService(TaskRepository tasks, InstanceRepository instances,
                DefinitionRepository definitions, ActRepository actRows, ActService acts,
                InstanceService instanceService, Clock clock) {
        this.tasks = tasks;
        this.instances = instances;
        this.definitions = definitions;
        this.actRows = actRows;
        this.acts = acts;
        this.instanceService = instanceService;
        this.clock = clock;
    }

    /**
     * What a draft holds and a create hands in, besides where it goes.
     *
     * @param title      the task's title
     * @param apparatus  the role it is addressed to (D16); absent where it names none
     * @param activity   the name of its activity; absent where the call names none
     * @param concerns   a task of the same instance it concerns; absent where it
     *                   concerns none
     * @param criteria   the text of each criterion, in their order; the service
     *                   gives their identifiers
     * @param freeFields the caller's own document; absent where it carries none
     * @param text       the text of the commission; absent where it carries none
     */
    public record Drafting(String title, @Nullable String apparatus, @Nullable String activity,
                           @Nullable TaskAddress concerns, List<String> criteria,
                           @Nullable Map<String, Object> freeFields, @Nullable String text) {

        public Drafting {
            criteria = List.copyOf(criteria);
            freeFields = freeFields == null ? null
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(freeFields));
        }
    }

    /**
     * What a create hands in.
     *
     * @param parent     the running instance the task joins; absent for the short
     *                   form, which starts an instance with the task as its first
     * @param drafting   what the draft holds
     * @param trigger    the trigger reference of the instance the short form
     *                   starts; absent where it carries none, and always with a parent
     * @param references the references of the instance the short form starts;
     *                   empty with a parent
     * @param key        the caller's idempotency key, or none
     */
    public record Create(@Nullable InstanceAddress parent, Drafting drafting,
                         @Nullable String trigger, List<String> references, IdempotencyKey key) {

        public Create {
            references = List.copyOf(references);
        }
    }

    /** The fields of a draft an update may clear (C7.7); its title stays. */
    public enum Clearable {
        APPARATUS, ACTIVITY, CONCERNS, CRITERIA, FREE_FIELDS, TEXT
    }

    /**
     * What an update hands in: each field that changes, and those to clear.
     * A field neither given nor cleared stays as it is.
     *
     * @param conflictToken the token of the caller's last answer; absent where none
     *                      arrived, which is refused
     * @param title         the new title; absent where it stays
     * @param apparatus     the new role; absent where it stays or is cleared
     * @param activity      the new activity's name; absent where it stays or is cleared
     * @param concerns      the new task concerned; absent where it stays or is cleared
     * @param criteria      the new criteria, in their order; absent where they
     *                      stay or are cleared
     * @param freeFields    the new document; absent where it stays or is cleared
     * @param text          the new text of the commission; absent where it stays or
     *                      is cleared
     * @param clear         the fields to clear; never one that is given as well,
     *                      which the form check refuses
     */
    public record Update(@Nullable String conflictToken, @Nullable String title,
                         @Nullable String apparatus, @Nullable String activity,
                         @Nullable TaskAddress concerns, @Nullable List<String> criteria,
                         @Nullable Map<String, Object> freeFields, @Nullable String text,
                         Set<Clearable> clear) {

        public Update {
            criteria = criteria == null ? null : List.copyOf(criteria);
            clear = clear.isEmpty() ? Set.of() : java.util.EnumSet.copyOf(clear);
        }
    }

    // ======================================================================
    // create
    // ======================================================================

    /**
     * Creates a task as a draft: in the running instance its parent names, or
     * as the first task of an instance it starts.
     *
     * @param scope    the scope's name, for the address
     * @param scopeId  the scope as the directory resolved it
     * @param selector the selector of the instance
     * @throws DispatchException {@code NOT_FOUND}, {@code INSTANCE_CLOSED},
     *         {@code IDEMPOTENCY_KEY_REUSED}, {@code ACTIVITY_MISSING}, {@code
     *         ACTIVITY_UNDECLARED}, {@code CONCERNS_UNRELATED}, or a refusal of
     *         the start contract for the short form
     */
    @Transactional
    public TaskView create(Actor actor, String scope, UUID scopeId, String selector,
                           Create create, Provenance provenance) {
        String digest = digest(scope, selector, create);
        InstanceAddress parent = create.parent();
        if (parent != null) {
            Instance instance = instances.lockInstance(scopeId, parent.selector(), parent.number())
                .orElseThrow(TaskService::absent);
            Optional<TaskView> repeat = repeat(actor, scope, scopeId, selector, create.key(),
                digest);
            if (repeat.isPresent()) {
                return repeat.get();
            }
            if (!instance.running()) {
                throw new DispatchException(DispatchException.Reason.INSTANCE_CLOSED,
                    "a closed instance takes no further task.",
                    instanceService.standing(actor, parent, instance));
            }
            return draft(actor, scopeId, instance, parent, create, digest);
        }
        instances.lockSelector(scopeId, selector).orElseThrow(TaskService::absent);
        Optional<TaskView> repeat = repeat(actor, scope, scopeId, selector, create.key(), digest);
        if (repeat.isPresent()) {
            return repeat.get();
        }
        InstanceView started = instanceService.start(actor, scope, scopeId, selector,
            new InstanceService.Start(create.drafting().title(), null, create.trigger(),
                List.of(), create.references(), Map.of()), provenance);
        Instance instance = instances.lockInstance(scopeId, selector, started.address().number())
            .orElseThrow(() -> new IllegalStateException("an instance started is there"));
        return draft(actor, scopeId, instance, started.address(), create, digest);
    }

    /** The task in draft, at the next sub of its instance, with its workspace and its key. */
    private TaskView draft(Actor actor, UUID scopeId, Instance instance, InstanceAddress address,
                           Create create, String digest) {
        Drafting drafting = create.drafting();
        DefinitionRepository.Declared declared = boundTo(scopeId, instance);
        Long activity = drafting.activity() == null ? onlyActivity(declared)
            : activity(declared, drafting.activity());
        Long concerns = concerns(scopeId, instance, address, drafting.concerns(), null);

        int sub = instance.takeSub();
        Task task = tasks.insert(Task.drafted(scopeId, instance.id, sub));
        Draft draft = tasks.insert(new Draft(scopeId, task.id(), new Draft.Control(
            drafting.title(), activity, concerns, drafting.apparatus()),
            ActService.timeAfter(clock.instant(), null)));
        writeCriteria(scopeId, draft, drafting.criteria());
        writeFreeFields(scopeId, draft, drafting.freeFields());
        writeText(scopeId, draft, drafting.text());
        if (create.key() instanceof IdempotencyKey.Given given) {
            Instant now = clock.instant();
            tasks.deleteExpiredKeys(scopeId, actor.subject(), now.minus(KEY_LIFE));
            if (!tasks.spendKey(new IdempotencyRecord(scopeId, actor.subject(), given.value(),
                    task.id(), digest, now))) {
                throw reused();
            }
        }
        return view(actor, scopeId, new TaskAddress(address, sub), task, draft);
    }

    /**
     * The answer of a repeat under a key the caller spent in the last
     * twenty-four hours, which writes nothing; empty where no such key stands.
     *
     * @throws DispatchException {@code IDEMPOTENCY_KEY_REUSED} where the key
     *         came with other arguments, or the draft it created was deleted
     */
    private Optional<TaskView> repeat(Actor actor, String scope, UUID scopeId, String selector,
                                      IdempotencyKey key, String digest) {
        if (!(key instanceof IdempotencyKey.Given given)) {
            return Optional.empty();
        }
        Optional<IdempotencyRecord> spent = tasks.spentKey(scopeId, actor.subject(),
            given.value(), clock.instant().minus(KEY_LIFE));
        if (spent.isEmpty()) {
            return Optional.empty();
        }
        Task task = spent.filter(held -> held.sameArguments(digest))
            .flatMap(IdempotencyRecord::taskId)
            .flatMap(id -> tasks.task(scopeId, id))
            .orElseThrow(TaskService::reused);
        Instance instance = tasks.instance(scopeId, task.instanceId())
            .orElseThrow(() -> new IllegalStateException("a task belongs to an instance"));
        TaskAddress address = new TaskAddress(new InstanceAddress(scope, selector,
            instance.number()), task.sub());
        return Optional.of(view(actor, scopeId, address, task,
            tasks.draft(scopeId, task.id()).orElse(null)));
    }

    /** The refusal of a key spent on other arguments, or on a draft since deleted. */
    private static DispatchException reused() {
        return new DispatchException(DispatchException.Reason.IDEMPOTENCY_KEY_REUSED,
            "the key was spent on other arguments, or on a draft since deleted.");
    }

    // ======================================================================
    // update, delete, send
    // ======================================================================

    /**
     * Changes the draft of a task: each field given takes its value, each
     * field cleared is emptied, and every other stays. Moves the conflict token
     * on where anything changed, and leaves it where nothing did (C10.1).
     *
     * @throws DispatchException {@code NOT_FOUND}, {@code TRANSITION_NOT_PERMITTED}
     *         outside draft, {@code CONFLICT_TOKEN_MISSING}, {@code
     *         CONFLICT_TOKEN_STALE}, {@code ACTIVITY_UNDECLARED} or {@code
     *         CONCERNS_UNRELATED}
     */
    @Transactional
    public TaskView update(Actor actor, TaskAddress address, UUID scopeId, Update update) {
        Instance instance = located(scopeId, address);
        Task task = tasks.lockTask(scopeId, instance.id, address.sub())
            .orElseThrow(TaskService::absent);
        Draft draft = drafted(actor, scopeId, address, task);
        requireToken(actor, scopeId, address, task, draft, update.conflictToken());

        Draft.Control was = draft.control();
        Draft.Control control = new Draft.Control(
            update.title() == null ? was.title() : update.title(),
            updatedActivity(scopeId, instance, update, was),
            updatedConcerns(scopeId, instance, address, task, update, was),
            changed(update, Clearable.APPARATUS, update.apparatus(), was.addressedRole()));
        boolean changed = !control.equals(was);
        changed |= replaceCriteria(scopeId, draft, update.clear().contains(Clearable.CRITERIA)
            ? List.of() : update.criteria());
        changed |= replaceFreeFields(scopeId, draft,
            update.clear().contains(Clearable.FREE_FIELDS), update.freeFields());
        changed |= replaceText(scopeId, draft, update.clear().contains(Clearable.TEXT),
            update.text());
        if (changed) {
            draft.take(control);
            draft.changedAt(ActService.timeAfter(clock.instant(), draft.changedAt()));
            tasks.flush();
        }
        return view(actor, scopeId, address, task, draft);
    }

    /**
     * Deletes a task in draft outright, with its draft (DEC-0015). An
     * idempotency key that names it keeps its row and lets go of the task.
     *
     * @return the task as it stood before it was deleted, for its address
     * @throws DispatchException {@code NOT_FOUND}, {@code TRANSITION_NOT_PERMITTED}
     *         outside draft, {@code CONFLICT_TOKEN_MISSING} or {@code
     *         CONFLICT_TOKEN_STALE}
     */
    @Transactional
    public TaskView delete(Actor actor, TaskAddress address, UUID scopeId,
                           @Nullable String conflictToken) {
        Instance instance = located(scopeId, address);
        Task task = tasks.lockTask(scopeId, instance.id, address.sub())
            .orElseThrow(TaskService::absent);
        Draft draft = drafted(actor, scopeId, address, task);
        requireToken(actor, scopeId, address, task, draft, conflictToken);
        TaskView deleted = view(actor, scopeId, address, task, draft);
        tasks.deleteWorkspace(scopeId, draft);
        tasks.deleteTask(scopeId, task);
        return deleted;
    }

    /**
     * Sends a task in draft: its commission, from its draft, anchored at the
     * act {@code send}, and the task opens.
     *
     * @throws DispatchException {@code NOT_FOUND}, {@code TRANSITION_NOT_PERMITTED}
     *         outside draft, {@code CONFLICT_TOKEN_MISSING}, {@code
     *         CONFLICT_TOKEN_STALE} or {@code DRAFT_INCOMPLETE} for a draft
     *         without an activity
     */
    @Transactional
    public TaskView send(Actor actor, TaskAddress address, UUID scopeId,
                         @Nullable String conflictToken, Provenance provenance) {
        InstanceAddress at = address.instance();
        Instance instance = instances.lockInstance(scopeId, at.selector(), at.number())
            .orElseThrow(TaskService::absent);
        Task task = tasks.lockTask(scopeId, instance.id, address.sub())
            .orElseThrow(TaskService::absent);
        Draft draft = drafted(actor, scopeId, address, task);
        requireToken(actor, scopeId, address, task, draft, conflictToken);
        long activity = draft.activityId().orElseThrow(() -> new DispatchException(
            DispatchException.Reason.DRAFT_INCOMPLETE, "a commission names its activity.",
            view(actor, scopeId, address, task, draft)));

        Act act = acts.write(scopeId, instance.id, Act.Kind.SEND, Act.State.OPEN, task.id(),
            provenance);
        Commission commission = tasks.insert(new Commission(scopeId, act.id(), instance.id,
            draft, activity));
        List<DraftText> texts = tasks.texts(scopeId, draft.id());
        for (DraftCriterion drafted : tasks.criteria(scopeId, draft.id())) {
            CommissionCriterion criterion = tasks.insert(new CommissionCriterion(scopeId,
                commission.id(), drafted));
            tasks.insert(Content.criterion(scopeId, act.id(), criterion.id(),
                textOf(texts, drafted.id())));
        }
        texts.stream().filter(DraftText::ofTheCommission).findFirst().ifPresent(text ->
            tasks.insert(new Content(scopeId, act.id(), Content.Kind.COMMISSION, text.body())));
        tasks.freeFields(scopeId, draft.id()).ifPresent(free ->
            tasks.insert(new FreeFields(scopeId, act.id(), free.document())));
        task.send(commission);
        tasks.deleteWorkspace(scopeId, draft);
        tasks.flush();
        return view(actor, scopeId, address, task, null);
    }

    // ======================================================================

    /** The instance an address of a task names, unlocked; refused where the scope holds none. */
    private Instance located(UUID scopeId, TaskAddress address) {
        InstanceAddress at = address.instance();
        return tasks.instance(scopeId, at.selector(), at.number()).orElseThrow(TaskService::absent);
    }

    /** The draft of a task in draft, locked; refused where the task is in another state. */
    private Draft drafted(Actor actor, UUID scopeId, TaskAddress address, Task task) {
        if (!task.draft()) {
            throw new DispatchException(DispatchException.Reason.TRANSITION_NOT_PERMITTED,
                "the draft of a task is changed, deleted and sent while it is a draft.",
                view(actor, scopeId, address, task, null));
        }
        return tasks.lockDraft(scopeId, task.id())
            .orElseThrow(() -> new IllegalStateException("a task in draft has its draft"));
    }

    private void requireToken(Actor actor, UUID scopeId, TaskAddress address, Task task,
                              Draft draft, @Nullable String presented) {
        if (presented == null) {
            throw new DispatchException(DispatchException.Reason.CONFLICT_TOKEN_MISSING,
                "a draft is changed, deleted and sent under its conflict token.");
        }
        if (!draft.changedAt().toString().equals(presented)) {
            throw new DispatchException(DispatchException.Reason.CONFLICT_TOKEN_STALE,
                "the draft changed since the token was read.",
                view(actor, scopeId, address, task, draft));
        }
    }

    /** The value of a field after an update: cleared, given, or as it was. */
    private static <T> @Nullable T changed(Update update, Clearable field, @Nullable T given,
                                           @Nullable T was) {
        if (update.clear().contains(field)) {
            return null;
        }
        return given == null ? was : given;
    }

    /** The activity of a draft after an update: cleared, named, or as it was. */
    private @Nullable Long updatedActivity(UUID scopeId, Instance instance, Update update,
                                           Draft.Control was) {
        if (update.clear().contains(Clearable.ACTIVITY)) {
            return null;
        }
        if (update.activity() == null) {
            return was.activityId();
        }
        return activity(boundTo(scopeId, instance), update.activity());
    }

    /** The task a draft concerns after an update: cleared, named, or as it was. */
    private @Nullable Long updatedConcerns(UUID scopeId, Instance instance, TaskAddress address,
                                           Task task, Update update, Draft.Control was) {
        if (update.clear().contains(Clearable.CONCERNS)) {
            return null;
        }
        if (update.concerns() == null) {
            return was.concernsTaskId();
        }
        return concerns(scopeId, instance, address.instance(), update.concerns(), task.id());
    }

    private DefinitionRepository.Declared boundTo(UUID scopeId, Instance instance) {
        return definitions.bound(scopeId, instance.definitionVersionId())
            .orElseThrow(() -> new IllegalStateException("an instance is bound to a version"));
    }

    /**
     * The activity a task takes where the call names none: the only one of
     * the version; none where the version declares none.
     *
     * @throws DispatchException {@code ACTIVITY_MISSING} where it declares more than one
     */
    private static @Nullable Long onlyActivity(DefinitionRepository.Declared declared) {
        List<Activity> declaredActivities = declared.activities();
        if (declaredActivities.size() > 1) {
            throw new DispatchException(DispatchException.Reason.ACTIVITY_MISSING,
                "the version declares more than one activity, and the task names none.",
                List.of(ACTIVITY));
        }
        return declaredActivities.isEmpty() ? null : declaredActivities.get(0).id();
    }

    /** The activity of a name, of the version. */
    private static Long activity(DefinitionRepository.Declared declared, String name) {
        return declared.activities().stream()
            .filter(activity -> activity.name().equals(name))
            .findFirst()
            .map(Activity::id)
            .orElseThrow(() -> new DispatchException(
                DispatchException.Reason.ACTIVITY_UNDECLARED,
                "an activity is one the version of the instance declares.", List.of(ACTIVITY)));
    }

    /**
     * The task a draft concerns: another task of the same instance.
     *
     * @param self the task the draft belongs to, which it cannot concern;
     *             absent for a task not yet created
     */
    private @Nullable Long concerns(UUID scopeId, Instance instance, InstanceAddress at,
                                    @Nullable TaskAddress concerned, @Nullable Long self) {
        if (concerned == null) {
            return null;
        }
        return Optional.of(concerned)
            .filter(address -> address.in(at))
            .flatMap(address -> tasks.task(scopeId, instance.id, address.sub()))
            .map(Task::id)
            .filter(id -> !id.equals(self))
            .orElseThrow(() -> new DispatchException(DispatchException.Reason.CONCERNS_UNRELATED,
                "a draft concerns another task of its own instance.", List.of(CONCERNS)));
    }

    private void writeCriteria(UUID scopeId, Draft draft, List<String> criteria) {
        for (int i = 0; i < criteria.size(); i++) {
            DraftCriterion criterion = tasks.insert(new DraftCriterion(scopeId, draft.id(), i + 1));
            tasks.insert(DraftText.criterion(scopeId, draft.id(), criterion.id(), criteria.get(i)));
        }
    }

    private void writeFreeFields(UUID scopeId, Draft draft,
                                 @Nullable Map<String, Object> document) {
        if (document != null) {
            tasks.insert(new DraftFreeFields(scopeId, draft.id(), document));
        }
    }

    private void writeText(UUID scopeId, Draft draft, @Nullable String text) {
        if (text != null) {
            tasks.insert(DraftText.commission(scopeId, draft.id(), text));
        }
    }

    /**
     * Puts the criteria given in place of the draft's, where they differ.
     *
     * @param criteria the criteria after the update; absent where they stay,
     *                 empty where they are cleared
     * @return whether they changed
     */
    private boolean replaceCriteria(UUID scopeId, Draft draft, @Nullable List<String> criteria) {
        if (criteria == null) {
            return false;
        }
        List<DraftText> texts = tasks.texts(scopeId, draft.id());
        List<String> standing = tasks.criteria(scopeId, draft.id()).stream()
            .map(criterion -> textOf(texts, criterion.id()))
            .toList();
        if (standing.equals(criteria)) {
            return false;
        }
        tasks.deleteCriteria(scopeId, draft.id());
        writeCriteria(scopeId, draft, criteria);
        return true;
    }

    /**
     * Clears the draft's document, or puts the one given in its place, where
     * that changes it.
     *
     * @param document the document given; absent where it stays or is cleared
     * @return whether it changed
     */
    private boolean replaceFreeFields(UUID scopeId, Draft draft, boolean clear,
                                      @Nullable Map<String, Object> document) {
        if (!clear && document == null) {
            return false;
        }
        Optional<Map<String, Object>> standing = tasks.freeFields(scopeId, draft.id())
            .map(DraftFreeFields::document);
        if (clear ? standing.isEmpty() : standing.filter(was -> same(was, document)).isPresent()) {
            return false;
        }
        tasks.deleteFreeFields(scopeId, draft.id());
        writeFreeFields(scopeId, draft, clear ? null : document);
        return true;
    }

    /**
     * Clears the text of the draft's commission, or puts the one given in its
     * place, where that changes it.
     *
     * @param text the text given; absent where it stays or is cleared
     * @return whether it changed
     */
    private boolean replaceText(UUID scopeId, Draft draft, boolean clear, @Nullable String text) {
        if (!clear && text == null) {
            return false;
        }
        Optional<String> standing = tasks.texts(scopeId, draft.id()).stream()
            .filter(DraftText::ofTheCommission).findFirst().map(DraftText::body);
        if (clear ? standing.isEmpty() : standing.filter(was -> was.equals(text)).isPresent()) {
            return false;
        }
        tasks.deleteCommissionText(scopeId, draft.id());
        writeText(scopeId, draft, clear ? null : text);
        return true;
    }

    private static String textOf(List<DraftText> texts, long criterionId) {
        return texts.stream()
            .filter(text -> text.draftCriterionId().filter(id -> id == criterionId).isPresent())
            .findFirst()
            .map(DraftText::body)
            .orElseThrow(() -> new IllegalStateException("a criterion of a draft has its text"));
    }

    /**
     * The task as it stands now, with its conflict token and the calls open to
     * the actor: the time of its draft's last change while it has a draft,
     * otherwise the time of its last act.
     */
    private TaskView view(Actor actor, UUID scopeId, TaskAddress address, Task task,
                          @Nullable Draft draft) {
        String token = draft != null ? draft.changedAt().toString()
            : actRows.lastTaskAt(scopeId, task.id()).map(Object::toString).orElse("");
        return new TaskView(task.id(), address, task.state(), task.resolution().orElse(null),
            token, TaskCall.openTo(actor, task.draft()));
    }

    /**
     * The digest of a create's arguments, in a canonical form, that an
     * idempotency key is held against: every argument in its order, an
     * absent one as absent, and the caller's document with its keys sorted.
     */
    static String digest(String scope, String selector, Create create) {
        Drafting drafting = create.drafting();
        List<String> values = new java.util.ArrayList<>();
        values.add("create");
        values.add(scope);
        values.add(selector);
        values.add(create.parent() == null ? null : create.parent().complete());
        values.add(drafting.title());
        values.add(drafting.apparatus());
        values.add(drafting.activity());
        values.add(drafting.concerns() == null ? null : drafting.concerns().complete());
        values.add("criteria " + drafting.criteria().size());
        values.addAll(drafting.criteria());
        values.add(drafting.freeFields() == null ? null : canonical(drafting.freeFields()));
        values.add(drafting.text());
        values.add(create.trigger());
        values.add("references " + create.references().size());
        values.addAll(create.references());
        return IdempotencyService.digestOf(values);
    }

    /**
     * A value of the caller's document in one form whatever the order of its
     * keys: an object with its keys sorted, a list in its order, every leaf
     * with its type. It is written by hand for its numbers, compared as exact
     * decimals without trailing zeros: Jackson with sorted keys keeps the scale
     * of a decimal, so 2.50 and 2.5 would differ, and RFC 8785 writes every
     * number as a double, so 9007199254740993 and 9007199254740992 would be one.
     */
    static String canonical(@Nullable Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder object = new StringBuilder("{");
            map.entrySet().stream()
                .sorted(java.util.Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .forEach(entry -> object.append(quoted(String.valueOf(entry.getKey())))
                    .append(':').append(canonical(entry.getValue())).append(','));
            return object.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder array = new StringBuilder("[");
            list.forEach(element -> array.append(canonical(element)).append(','));
            return array.append(']').toString();
        }
        if (value instanceof String text) {
            return quoted(text);
        }
        if (value instanceof Number number) {
            return "n" + new java.math.BigDecimal(number.toString()).stripTrailingZeros()
                .toPlainString();
        }
        return String.valueOf(value);
    }

    private static String quoted(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static DispatchException absent() {
        return new DispatchException(DispatchException.Reason.NOT_FOUND,
            "no task, instance or version is there at that address.");
    }

    /** Whether two values of the caller's document are one. */
    static boolean same(@Nullable Object a, @Nullable Object b) {
        return Objects.equals(canonical(a), canonical(b));
    }
}
