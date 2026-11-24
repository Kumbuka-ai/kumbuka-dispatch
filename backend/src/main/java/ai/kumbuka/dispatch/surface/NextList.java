/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.InstanceCall;
import ai.kumbuka.dispatch.domain.TaskCall;
import ai.kumbuka.dispatch.domain.TaskView;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The calls open to a caller, with one sentence each (TAR-0004 section 7).
 *
 * <p>A step is what {@code next} carries in an answer and in a refusal: the
 * call by its name on the caller's surface, and what it does. Which calls are
 * open on an object is the kernel's to decide; this class names them in the
 * vocabulary of the caller's surface.
 */
public final class NextList {

    private NextList() {
    }

    /** The calls the kernel names as open on an instance, as one surface names them. */
    public static List<Step> of(List<InstanceCall> open, Surface surface) {
        NextStepTexts texts = Texts.next();
        return open.stream().map(call -> switch (call) {
            case CONCLUDE -> new Step(VerbName.of(ProcessVerb.CONCLUDE, surface),
                texts.conclude());
            case WITHDRAW -> new Step(VerbName.of(ProcessVerb.WITHDRAW, surface),
                texts.withdrawInstance());
            case REFERENCE_ATTACH -> new Step(VerbName.of(ProcessVerb.REFERENCE_ATTACH, surface),
                texts.referenceAttach());
            case REFERENCE_DETACH -> new Step(VerbName.of(ProcessVerb.REFERENCE_DETACH, surface),
                texts.referenceDetach());
        }).toList();
    }

    /** The calls the kernel names as open on a task, as one surface names them. */
    public static List<Step> ofTask(List<TaskCall> open, Surface surface) {
        NextStepTexts texts = Texts.next();
        return open.stream().map(call -> switch (call) {
            case UPDATE -> new Step(VerbName.of(ProcessVerb.UPDATE, surface), texts.update());
            case DELETE -> new Step(VerbName.of(ProcessVerb.DELETE, surface), texts.delete());
            case SEND -> new Step(VerbName.of(ProcessVerb.SEND, surface), texts.send());
        }).toList();
    }

    /**
     * What a task waits for where no call is open to the caller on it and it
     * is unfinished (C6.1); absent where a call is open or the task is closed.
     */
    public static @Nullable String waitingFor(TaskView task, List<Step> next) {
        if (!next.isEmpty() || task.closed()) {
            return null;
        }
        return "draft".equals(task.state())
            ? Texts.notices().waitingDraft() : Texts.notices().waitingOpen();
    }

    /** Where a task stands, for a refusal about it: its state, the calls open, what it waits for. */
    public static Refusal.Situation situation(TaskView task, Surface surface) {
        List<Step> next = ofTask(task.open(), surface);
        return new Refusal.Situation(task.state(), next, waitingFor(task, next));
    }

    /** One open call: its name on the caller's surface, and what it does. */
    public record Step(VerbName name, String does) {

        /** The call as the wire carries it. */
        public String call() {
            return name.toString();
        }
    }
}
