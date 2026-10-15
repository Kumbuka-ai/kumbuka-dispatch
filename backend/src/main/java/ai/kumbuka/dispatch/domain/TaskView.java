/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The head of one task: its process, never its text (TAR-0004 section 6).
 *
 * <p>There is no component here that could carry a text of the task -- not an
 * empty one, none. The texts that exist are listed by type and suffix in
 * {@link #texts}; their content comes only from {@code read_text}. That is
 * what makes "no transition and no writing call returns a text" a property of
 * the type every such call returns.
 *
 * <p>The holder is reported as nobody, self or other, never as an identity,
 * and the end of the lease only to the holder.
 *
 * @param identity        the durable identity; the technical address
 * @param address         where the task lives in its scope
 * @param state           the effective state
 * @param holdReason      set exactly in {@code on_hold}
 * @param outcome         set exactly in {@code closed}
 * @param holder          whether nobody, the caller or another holds the task
 * @param leaseExpiresAt  the end of the lease, for the holder only
 * @param notBefore       the instant a {@code defer} named
 * @param questionOptions the pending question's options and whether free text is admitted
 * @param lapseCount      the lapsed leases recorded so far
 * @param title           the commission's title
 * @param apparatus       the apparatus the task is addressed to
 * @param dispatchMetadata the commissioner's metadata
 * @param returnMetadata  the executor's metadata
 * @param conflictToken   the token a fenced call presents
 * @param curatedIn       the complete address of the object the task was curated
 *                        into, when the caller may see its scope
 * @param texts           the texts that exist, by type and suffix
 * @param next            the transitions open to the caller
 */
public record TaskView(
    UUID identity,
    ExchangeAddress address,
    TaskState state,
    HoldReason holdReason,
    Outcome outcome,
    HolderState holder,
    Instant leaseExpiresAt,
    Instant notBefore,
    Map<String, Object> questionOptions,
    int lapseCount,
    String title,
    String apparatus,
    Map<String, Object> dispatchMetadata,
    Map<String, Object> returnMetadata,
    String conflictToken,
    String curatedIn,
    List<TextHead> texts,
    List<TaskVerb> next) {

    /** One text that exists, without its content. */
    public record TextHead(TextType type, String addendumSuffix) {

        /** One text that exists, as a head lists it: its type, and its suffix where it has one. */
        Map<String, Object> fields() {
            Map<String, Object> rendered = new LinkedHashMap<>();
            rendered.put("type", type.wireName());
            if (addendumSuffix != null) {
                rendered.put("suffix", addendumSuffix);
            }
            return rendered;
        }
    }

    public TaskView {
        texts = List.copyOf(texts);
        next = List.copyOf(next);
    }

    /**
     * The head for {@code caller}, as the task stands in {@code situation}.
     *
     * @param texts the texts that exist, by type and suffix, in the order written
     */
    /**
     * The task's fields as an answer carries them (C7.1): lean for a writing
     * call — the state with its attributes and, for the holder, the end of
     * the lease — and the full head for a read or a listing, which adds the
     * title, the apparatus, both metadata, the technical address as {@code
     * identity}, who holds the task, and the texts that exist by type and
     * suffix. The one mapping of this object type to the wire; an absent
     * value is left out, and an instant stays an instant for the serialiser to
     * write (C7.3).
     */
    public Map<String, Object> fields(boolean head) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("state", state.wireName());
        putIfPresent(fields, "hold_reason", holdReason == null ? null : holdReason.wireName());
        putIfPresent(fields, "outcome", outcome == null ? null : outcome.wireName());
        putIfPresent(fields, "not_before", notBefore);
        putIfPresent(fields, "lease_expires_at", leaseExpiresAt);
        if (!head) {
            return fields;
        }
        fields.put("title", title);
        fields.put("apparatus", apparatus);
        fields.put("identity", ExchangeAddress.SCHEME + "://" + identity);
        fields.put("holder", holder.wireName());
        fields.put("lapse_count", lapseCount);
        if (holdReason == HoldReason.QUESTION && questionOptions != null) {
            fields.put("question", questionOptions);
        }
        putIfPresent(fields, "dispatch_metadata", dispatchMetadata);
        putIfPresent(fields, "return_metadata", returnMetadata);
        putIfPresent(fields, "curated_in", curatedIn);
        fields.put("texts", texts.stream().map(TextHead::fields).toList());
        return fields;
    }

    private static void putIfPresent(Map<String, Object> fields, String name, Object value) {
        if (value != null) {
            fields.put(name, value);
        }
    }

    static TaskView of(Task task, TaskSituation situation, Actor caller, List<TextHead> texts,
                       String curatedIn) {
        HolderState holder = HolderState.of(situation.holder(), caller);
        return new TaskView(task.uuid, task.address(), situation.state(),
            situation.holdReason(), task.outcome(), holder,
            holder == HolderState.SELF ? task.leaseExpiresAt() : null,
            task.notBefore(), task.questionOptions(), task.lapseCount(), task.title,
            task.apparatus, task.dispatchMetadata, task.returnMetadata(),
            task.conflictToken(), curatedIn,
            texts,
            Decision.openVerbs(situation, caller));
    }
}
