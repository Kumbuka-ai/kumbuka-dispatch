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
import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The acts on an instance (REA-0010, catalogue "Acts on an instance"): {@code
 * start} creates one, {@code conclude} and {@code withdraw} close it with its
 * return, and {@code reference_attach} and {@code reference_detach} set and
 * end a reference of a running one.
 *
 * <p>Every act is written by the writer of acts, under the lock of the
 * instance's row, and every act on an existing instance decides on the row as
 * it stands under that lock: a closed instance takes none of them. A closing
 * compares the conflict token it is handed with the instance's under the same
 * lock (C10.2); the token is the time of the instance's last act about no
 * task (operator, 2026-11-24).
 *
 * <p>Closing an instance with unfinished tasks is refused first, and hands out
 * a confirmation computed from the set of those tasks; the same call with that
 * confirmation writes the closing act, then for each unfinished task a {@code
 * withdraw} that refers to the closing act and closes the task as withdrawn,
 * and then closes the instance, all in one transaction and under the
 * permission of the closing call (TAR-0004 2.3; the operator's answer of
 * 2026-11-24). The closing act comes first because the store refers to it by
 * key. No closed instance holds an unfinished task.
 *
 * <p>Nothing here checks the caller's permission: the call checked it before
 * it was made, and the provenance it hands in names it. The actor is read for
 * the calls the answer names as open.
 *
 * <p>Logs nothing (DEC-0051, C4).
 */
@ApplicationScoped
@TenantBound
public class InstanceService {

    private final InstanceRepository instances;
    private final DefinitionRepository definitions;
    private final ActRepository actRows;
    private final ActService acts;

    @Inject
    InstanceService(InstanceRepository instances, DefinitionRepository definitions,
                    ActRepository actRows, ActService acts) {
        this.instances = instances;
        this.definitions = definitions;
        this.actRows = actRows;
        this.acts = acts;
    }

    /**
     * What a start hands in.
     *
     * @param title      the instance's title, never changed (D19)
     * @param input      the input text; absent where the start carries none
     * @param trigger    the trigger reference, stored and never resolved (D4);
     *                   absent where the start carries none
     * @param items      the worklist items the instance serves (D12)
     * @param references the other objects outside the service it serves (D12)
     * @param values     the field values of the start contract, by field name
     */
    public record Start(String title, @Nullable String input, @Nullable String trigger,
                        List<String> items, List<String> references,
                        Map<String, Object> values) {

        public Start {
            items = List.copyOf(items);
            references = List.copyOf(references);
            values = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
        }
    }

    /**
     * What a closing hands in.
     *
     * @param conflictToken    the token of the caller's last read; absent where
     *                         none arrived, which is refused
     * @param confirmation     the confirmation a refusal handed out; absent
     *                         where none arrived
     * @param text             the text of the return
     * @param verdict          the verdict; absent where none is given
     * @param outcome          the outcome label; absent where none is given
     * @param translationBasis the translation basis of the label; absent where
     *                         none is given
     */
    public record Closing(@Nullable String conflictToken, @Nullable String confirmation,
                         String text, @Nullable String verdict, @Nullable String outcome,
                         @Nullable String translationBasis) {
    }

    /**
     * Starts an instance of a selector, bound to the version in force: its
     * title, its start act with the input text, its trigger, its references
     * and its field values, and the projection of those values, in one
     * transaction.
     *
     * @param scope    the scope's name, for the address
     * @param scopeId  the scope as the directory resolved it
     * @throws DispatchException {@code NOT_FOUND} where the selector has no
     *         active seeded version; a refusal of the start contract
     */
    @Transactional
    public InstanceView start(Actor actor, String scope, UUID scopeId, String selector,
                              Start start, Provenance provenance) {
        DefinitionRepository.Declared declared = definitions.seeded(scopeId, selector, null)
            .orElseThrow(InstanceService::absent);
        List<ContractCheck.Checked> values = ContractCheck.startValues(declared, start.values());

        Selector counter = instances.lockSelector(scopeId, selector)
            .orElseThrow(InstanceService::absent);
        long number = counter.takeNumber();
        Instance instance = instances.insert(Instance.started(scopeId, declared.version()
            .selectorId(), number, declared.version().id(), start.title()));

        Act act = acts.write(scopeId, instance.id, Act.Kind.START, Act.State.RUNNING, null,
            provenance);
        if (start.input() != null) {
            instances.insert(new Content(scopeId, act.id(), Content.Kind.INSTANCE_INPUT,
                start.input()));
        }
        if (start.trigger() != null) {
            refer(scopeId, instance, ExternalReference.Kind.TRIGGER, start.trigger(), act);
        }
        for (String item : new LinkedHashSet<>(start.items())) {
            refer(scopeId, instance, ExternalReference.Kind.ITEM, item, act);
        }
        for (String reference : new LinkedHashSet<>(start.references())) {
            refer(scopeId, instance, ExternalReference.Kind.REFERENCE, reference, act);
        }
        for (ContractCheck.Checked checked : values) {
            FieldValue written = instances.insert(new FieldValue(scopeId, act.id(),
                checked.field(), checked.value()));
            instances.insert(new InstanceFieldValue(scopeId, instance.id, written));
        }
        return view(actor, new InstanceAddress(scope, selector, number), instance,
            declared.version().version());
    }

    /**
     * Closes a running instance with its return: {@code concluded} or {@code
     * withdrawn}, by a closing act of that kind.
     *
     * @throws DispatchException {@code NOT_FOUND}, {@code INSTANCE_CLOSED},
     *         {@code CONFLICT_TOKEN_MISSING}, {@code CONFLICT_TOKEN_STALE}, a
     *         refusal of the return, {@code CONFIRMATION_REQUIRED} or {@code
     *         CONFIRMATION_STALE}
     */
    @Transactional
    public InstanceView close(Actor actor, InstanceAddress address, UUID scopeId,
                              Instance.Resolution how, Closing closing,
                              Provenance provenance) {
        Instance instance = running(actor, address, scopeId);
        DefinitionRepository.Declared declared = definitions.bound(scopeId,
                instance.definitionVersionId())
            .orElseThrow(() -> new IllegalStateException("an instance is bound to a version"));
        int version = declared.version().version();
        requireToken(actor, address, instance, version, closing.conflictToken());
        ContractCheck.Return checked = ContractCheck.closing(declared, closing.verdict(),
            closing.outcome(), closing.translationBasis());

        Act.Kind kind = how == Instance.Resolution.CONCLUDED ? Act.Kind.CONCLUDE
            : Act.Kind.WITHDRAW;
        List<Task> unfinished = instances.lockUnfinishedTasks(scopeId, instance.id);
        requireConfirmed(kind, address, instance, unfinished, closing.confirmation());

        Act closed = acts.write(scopeId, instance.id, kind, Act.State.CLOSED, null, provenance);
        for (Task task : unfinished) {
            acts.write(scopeId, instance.id, Act.Kind.WITHDRAW, Act.State.CLOSED, task.id(),
                provenance, closed.id());
            task.withdraw();
        }
        instances.insert(new InstanceReturn(scopeId, closed.id(), checked.verdict(),
            checked.outcomeLabelId()));
        instances.insert(new Content(scopeId, closed.id(), Content.Kind.INSTANCE_RETURN,
            closing.text()));
        if (closing.translationBasis() != null) {
            instances.insert(new Content(scopeId, closed.id(),
                Content.Kind.INSTANCE_TRANSLATION_BASIS, closing.translationBasis()));
        }
        instance.close(how);
        instances.flush();
        return view(actor, address, instance, version);
    }

    /**
     * Sets a reference of a running instance by an act of the kind {@code
     * refer}. A reference the instance already carries is set once: the call
     * writes nothing and answers the instance.
     *
     * @throws DispatchException {@code NOT_FOUND} or {@code INSTANCE_CLOSED}
     */
    @Transactional
    public InstanceView attach(Actor actor, InstanceAddress address, UUID scopeId,
                               ExternalReference.Kind kind, String value,
                               Provenance provenance) {
        Instance instance = running(actor, address, scopeId);
        if (instances.currentReference(scopeId, instance.id, kind, value).isEmpty()) {
            Act act = acts.write(scopeId, instance.id, Act.Kind.REFER, Act.State.RUNNING, null,
                provenance);
            refer(scopeId, instance, kind, value, act);
        }
        return view(actor, address, instance, versionOf(scopeId, instance));
    }

    /**
     * Ends a reference of a running instance by an act of the kind {@code
     * unrefer}, which stands in the reference's column; the reference stays.
     *
     * @throws DispatchException {@code NOT_FOUND}, {@code INSTANCE_CLOSED} or
     *         {@code REFERENCE_ABSENT} where the instance carries no such
     *         reference, or carries it no longer
     */
    @Transactional
    public InstanceView detach(Actor actor, InstanceAddress address, UUID scopeId,
                               ExternalReference.Kind kind, String value,
                               Provenance provenance) {
        Instance instance = running(actor, address, scopeId);
        ExternalReference reference = instances.currentReference(scopeId, instance.id, kind,
                value)
            .orElseThrow(() -> new DispatchException(DispatchException.Reason.REFERENCE_ABSENT,
                "the instance carries no such reference.", List.of("fields.value")));
        Act act = acts.write(scopeId, instance.id, Act.Kind.UNREFER, Act.State.RUNNING, null,
            provenance);
        reference.endBy(act.id());
        instances.flush();
        return view(actor, address, instance, versionOf(scopeId, instance));
    }

    // ======================================================================

    /** The instance at an address, locked, where it runs; refused where it is closed. */
    private Instance running(Actor actor, InstanceAddress address, UUID scopeId) {
        Instance instance = instances.lockInstance(scopeId, address.selector(), address.number())
            .orElseThrow(InstanceService::absent);
        if (!instance.running()) {
            throw new DispatchException(DispatchException.Reason.INSTANCE_CLOSED,
                "a closed instance takes no further act.",
                view(actor, address, instance, versionOf(scopeId, instance)));
        }
        return instance;
    }

    private void requireToken(Actor actor, InstanceAddress address, Instance instance,
                              int version, @Nullable String presented) {
        if (presented == null) {
            throw new DispatchException(DispatchException.Reason.CONFLICT_TOKEN_MISSING,
                "a closing is fenced by the conflict token.");
        }
        InstanceView standing = view(actor, address, instance, version);
        if (!standing.conflictToken().equals(presented)) {
            throw new DispatchException(DispatchException.Reason.CONFLICT_TOKEN_STALE,
                "the instance changed since the token was read.", standing);
        }
    }

    private static void requireConfirmed(Act.Kind kind, InstanceAddress address,
                                         Instance instance, List<Task> unfinished,
                                         @Nullable String presented) {
        if (unfinished.isEmpty()) {
            return;
        }
        String expected = Confirmation.of(kind, instance.id,
            unfinished.stream().map(Task::id).toList());
        if (expected.equals(presented)) {
            return;
        }
        List<String> children = unfinished.stream().map(task -> address.task(task.sub()))
            .toList();
        throw new DispatchException(presented == null
                ? DispatchException.Reason.CONFIRMATION_REQUIRED
                : DispatchException.Reason.CONFIRMATION_STALE,
            "the instance has unfinished tasks.", children, expected);
    }

    private void refer(UUID scopeId, Instance instance, ExternalReference.Kind kind,
                       String value, Act act) {
        instances.insert(new ExternalReference(scopeId, instance.id, kind, value, act.id()));
    }

    /**
     * The instance as it stands now, for a refusal of a call on a task of it
     * that names the instance: a task created under a closed instance.
     */
    InstanceView standing(Actor actor, InstanceAddress address, Instance instance) {
        return view(actor, address, instance, versionOf(instance.scopeId, instance));
    }

    private int versionOf(UUID scopeId, Instance instance) {
        return definitions.version(scopeId, instance.definitionVersionId())
            .map(DefinitionVersion::version)
            .orElseThrow(() -> new IllegalStateException("an instance is bound to a version"));
    }

    /** The instance as it stands now, with its conflict token and the calls open to the actor. */
    private InstanceView view(Actor actor, InstanceAddress address, Instance instance,
                              int version) {
        String token = actRows.lastInstanceAt(instance.scopeId, instance.id)
            .map(Object::toString)
            .orElse("");
        return new InstanceView(instance.id, address, version, instance.title().orElse(null),
            instance.state(), instance.resolution().orElse(null), token,
            InstanceCall.openTo(actor, instance.running()));
    }

    private static DispatchException absent() {
        return new DispatchException(DispatchException.Reason.NOT_FOUND,
            "no instance or version is there at that address.");
    }
}
