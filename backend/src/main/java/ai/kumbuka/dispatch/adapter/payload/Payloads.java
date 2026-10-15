/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import ai.kumbuka.dispatch.domain.TaskTextView;
import ai.kumbuka.dispatch.surface.NextList;
import ai.kumbuka.dispatch.surface.Refusal;

import java.util.List;
import java.util.Map;

/**
 * Every answer and every refusal of the verb surface, as the record both
 * surfaces put on the wire (C7.1).
 *
 * <h2>Two sizes of a task (concept section 3.3)</h2>
 *
 * A writing call answers lean: the address, the state with its attributes, the
 * conflict token, the end of the lease for the holder, and {@code next}; a
 * claim adds the receipt. {@code read} and {@code query} answer the full head.
 * The fields of either come from the one mapping of the task in the domain.
 *
 * <p>No answer here carries a text of a task but {@link #text}: the head the
 * answers are built from has no component that could hold one. No answer names
 * the identity of an actor.
 */
public final class Payloads {

    private Payloads() {
    }

    /** The record of one outcome of a call. */
    public static Object of(CallBinding.Outcome outcome) {
        return switch (outcome) {
            case CallBinding.Answered answered -> task(answered);
            case CallBinding.Listed listed -> new PagePayload(
                listed.heads().stream().map(Payloads::task).toList(), listed.nextCursor());
            case CallBinding.TextRead read -> text(read);
            case CallBinding.Deleted deleted -> new DeletedPayload(deleted.address());
        };
    }

    /** One task: lean for a writing call, the full head for a read or a listing. */
    public static TaskPayload task(CallBinding.Answered answered) {
        return new TaskPayload(answered.address(), answered.view().fields(answered.head()),
            answered.view().conflictToken(), next(answered.next()), answered.waitingFor(),
            answered.receipt());
    }

    /** One part of a task's text: the only answer that carries one. */
    private static TextPayload text(CallBinding.TextRead read) {
        TaskTextView text = read.text();
        return new TextPayload(read.address(), text.part().wireName(),
            text.entries().stream()
                .map(entry -> new TextEntryPayload(entry.type().wireName(),
                    entry.addendumSuffix(), entry.text(), entry.createdAt()))
                .toList(),
            text.addenda());
    }

    /**
     * A refusal, in the envelope both surfaces answer it with (C8.4). A stale
     * conflict token carries the task's fields as the caller's read would
     * answer them, the full head (DEC-0041).
     */
    public static RefusalPayload refusal(Refusal refusal) {
        Refusal.Data data = refusal.data();
        if (data == null) {
            return new RefusalPayload(refusal.code().name(), refusal.message(), null);
        }
        Map<String, Object> fields = data.current() == null ? null : data.current().fields(true);
        List<NextStepPayload> next = data.next() == null ? null : next(data.next());
        List<ChildPayload> children = data.children() == null ? null : data.children().stream()
            .map(child -> new ChildPayload(child.address(), child.state(), next(child.next())))
            .toList();
        return new RefusalPayload(refusal.code().name(), refusal.message(),
            new RefusalDataPayload(data.attempted(), data.address(), data.state(), fields, next,
                data.waitingFor(), data.offenders(), data.accepts(), children,
                data.confirmation(), data.conflictToken(), data.service(), data.reference()));
    }

    /** The calls open to a caller, each with the sentence that says what it does. */
    public static List<NextStepPayload> next(List<NextList.Step> steps) {
        return steps.stream().map(step -> new NextStepPayload(step.call(), step.does())).toList();
    }
}
