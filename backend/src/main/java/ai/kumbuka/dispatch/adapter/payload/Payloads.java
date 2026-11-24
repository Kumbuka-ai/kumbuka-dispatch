/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import ai.kumbuka.dispatch.domain.DefinitionView;
import ai.kumbuka.dispatch.domain.InstanceView;
import ai.kumbuka.dispatch.domain.TaskView;
import ai.kumbuka.dispatch.surface.NextList;
import ai.kumbuka.dispatch.surface.Refusal;
import ai.kumbuka.dispatch.surface.Surface;

import java.util.List;

/**
 * Every answer and every refusal of the verb surface, as the record both
 * surfaces put on the wire (C7.1).
 *
 * <p>The answers of the calls are built here as each call is bound to the
 * kernel of the store line: {@code read_definition} answers a version, every
 * act on an instance answers the instance, a call on a task answers the task,
 * and {@code delete} the address the draft had. No answer names the identity
 * of an actor.
 */
public final class Payloads {

    private Payloads() {
    }

    /**
     * A refusal, in the envelope both surfaces answer it with (C8.4). A stale
     * conflict token carries the object's fields as the caller's read would
     * answer them (DEC-0041).
     */
    public static RefusalPayload refusal(Refusal refusal) {
        Refusal.Data data = refusal.data();
        if (data == null) {
            return new RefusalPayload(refusal.code().name(), refusal.message(), null);
        }
        List<NextStepPayload> next = data.next() == null ? null : next(data.next());
        return new RefusalPayload(refusal.code().name(), refusal.message(),
            new RefusalDataPayload(data.attempted(), data.address(), data.state(), data.current(), next,
                data.waitingFor(), data.offenders(), data.accepts(), data.children(),
                data.confirmation(), data.conflictToken(), data.service(), data.reference()));
    }

    /** The calls open to a caller, each with the sentence that says what it does. */
    public static List<NextStepPayload> next(List<NextList.Step> steps) {
        return steps.stream().map(step -> new NextStepPayload(step.call(), step.does())).toList();
    }

    /** A seeded definition version, as {@code read_definition} answers it. */
    public static DefinitionPayload definition(DefinitionView view) {
        return new DefinitionPayload(new DefinitionFieldsPayload(view.selector(), view.version(),
            view.state(), view.verdictRequired(), view.outcomeRequired(), view.outcomeLabels(),
            view.schema().stream()
                .map(field -> new SchemaFieldPayload(field.name(), field.type(),
                    field.enumeration()))
                .toList(),
            enumerations(view.enumerations()),
            view.activities().stream()
                .map(activity -> new ActivityPayload(activity.name(), activity.verdictRequired(),
                    activity.outcomeRequired(), activity.criteriaRequired(),
                    activity.outcomeLabels(), contract(activity.resultContract()),
                    activity.inputKind(), activity.mayAsk()))
                .toList(),
            contract(view.startContract()),
            enumerations(view.systemEnumerations())));
    }

    /**
     * An instance, as every act on it answers it, with the calls open to the
     * caller named on the surface the caller came through (C6.2).
     */
    public static InstancePayload instance(InstanceView view, Surface surface) {
        return new InstancePayload(view.address().complete(), view.fields(),
            view.conflictToken(), next(NextList.of(view.open(), surface)));
    }

    /**
     * A task, as {@code create}, {@code update} and {@code send} answer it,
     * with the calls open to the caller named on the surface the caller came
     * through (C6.2), and what it waits for where none is open.
     */
    public static TaskPayload task(TaskView view, Surface surface) {
        List<NextList.Step> next = NextList.ofTask(view.open(), surface);
        return new TaskPayload(view.address().complete(), view.fields(), view.conflictToken(),
            next(next), NextList.waitingFor(view, next));
    }

    /** The address a deleted draft had, as {@code delete} answers it. */
    public static AddressPayload deleted(TaskView view) {
        return new AddressPayload(view.address().complete());
    }

    private static List<EnumerationPayload> enumerations(List<DefinitionView.NamedValues> named) {
        return named.stream().map(e -> new EnumerationPayload(e.name(), e.values())).toList();
    }

    private static List<ContractFieldPayload> contract(List<DefinitionView.ContractEntry> rows) {
        return rows.stream().map(row -> new ContractFieldPayload(row.field(), row.required()))
            .toList();
    }
}
