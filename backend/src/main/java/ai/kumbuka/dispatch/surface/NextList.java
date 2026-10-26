/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.HoldReason;
import ai.kumbuka.dispatch.domain.HolderState;
import ai.kumbuka.dispatch.domain.TaskState;
import ai.kumbuka.dispatch.domain.TaskVerb;
import ai.kumbuka.dispatch.domain.TaskView;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The calls open to a caller, with one sentence each, and what the task waits
 * for where none is (TAR-0004 section 7).
 *
 * <p>The list is the kernel's: {@link TaskView#next()} is {@code
 * Decision.openVerbs}, the transitions whose situational checks pass for this
 * caller, in table order, each with the sentence of its step in {@code
 * messages/next.properties}. This class names
 * them on the caller's surface and adds one call the table cannot list,
 * because it is not a transition: {@code read_text}, first, at the two points
 * of the main course where the text is what the caller needs next (concept
 * section 3.4) — for the holder right after a claim, and for the commissioner
 * of a delivered task, before it accepts.
 */
public final class NextList {

    private NextList() {
    }

    /** One open call: its name on the caller's surface, and what it does. */
    public record Step(VerbName name, String does) {

        /** The call as the wire carries it. */
        public String call() {
            return name.toString();
        }
    }

    /**
     * Steps as the wire carries them: {@code call} and {@code does}. Built
     * here rather than left to a JSON encoder, which would write the record's
     * components and not its wire form.
     */
    public static List<java.util.Map<String, String>> wire(List<Step> steps) {
        return steps.stream().map(step -> {
            java.util.Map<String, String> rendered = new java.util.LinkedHashMap<>();
            rendered.put("call", step.call());
            rendered.put("does", step.does());
            return rendered;
        }).toList();
    }

    /**
     * The calls open to {@code caller} on the task whose head is {@code view}.
     *
     * @param afterClaim whether this answers the claim that just made the
     *                   caller its holder
     */
    public static List<Step> of(Surface surface, TaskView view, Actor caller,
                                boolean afterClaim) {
        List<Step> steps = new ArrayList<>();
        if (afterClaim && view.holder() == HolderState.SELF) {
            steps.add(new Step(VerbName.of(ProcessVerb.READ_TEXT, surface),
                Texts.next().readTheCommission()));
        } else if (caller.isConsole() && view.state() == TaskState.DELIVERED) {
            steps.add(new Step(VerbName.of(ProcessVerb.READ_TEXT, surface),
                Texts.next().readTheAnswer()));
        }
        view.next().stream()
            .map(verb -> new Step(VerbName.of(ProcessVerb.of(verb), surface), does(verb)))
            .forEach(steps::add);
        return List.copyOf(steps);
    }

    /**
     * What the task waits for, where nothing is open to the caller; empty
     * otherwise.
     *
     * <p>A parked task -- on hold without a holder, after its third lapsed
     * lease -- waits for a person: only the web interface moves it out of the
     * hold, or the commissioner withdraws it.
     */
    public static Optional<String> waitingFor(TaskView view, List<Step> next, Instant now) {
        if (!next.isEmpty()) {
            return Optional.empty();
        }
        Notices notices = Texts.notices();
        Instant notBefore = view.notBefore();
        return Optional.of(switch (view.state()) {
            case DRAFT -> notices.waitingDraft();
            case OPEN -> notBefore != null && notBefore.isAfter(now)
                ? notices.waitingDeferred()
                : notices.waitingOpen();
            case ACTIVE -> notices.waitingActive();
            case ON_HOLD -> onHold(view, notices);
            case DELIVERED -> notices.waitingDelivered();
            case CLOSED -> notices.waitingClosed();
        });
    }

    private static String onHold(TaskView view, Notices notices) {
        if (view.holdReason() == HoldReason.QUESTION) {
            return notices.waitingQuestion();
        }
        if (view.holder() == HolderState.NOBODY) {
            return notices.waitingParked();
        }
        return notices.waitingResume();
    }

    /** What one transition does, as its step of {@code next} says it. */
    static String does(TaskVerb verb) {
        NextStepTexts next = Texts.next();
        return switch (verb) {
            case SEND -> next.send();
            case RECALL -> next.recall();
            case CLAIM -> next.claim();
            case CLAIM_NEXT -> next.claimNext();
            case RELEASE -> next.release();
            case DEFER -> next.defer();
            case RENEW -> next.renew();
            case ASK -> next.ask();
            case ANSWER -> next.answer();
            case HOLD -> next.hold();
            case RESUME -> next.resume();
            case DELIVER -> next.deliver();
            case REWORK -> next.rework();
            case ACCEPT -> next.accept();
            case REJECT -> next.reject();
            case FAIL -> next.fail();
            case WITHDRAW -> next.withdraw();
        };
    }
}
