/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import ai.kumbuka.dispatch.contract.TargetSurface;
import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.domain.ExchangeAddress;
import ai.kumbuka.dispatch.domain.HolderState;
import ai.kumbuka.dispatch.domain.IdempotencyKey;
import ai.kumbuka.dispatch.domain.TaskCall;
import ai.kumbuka.dispatch.domain.TaskState;
import ai.kumbuka.dispatch.domain.TaskVerb;
import ai.kumbuka.dispatch.domain.TaskView;
import ai.kumbuka.dispatch.surface.Argument;
import ai.kumbuka.dispatch.surface.NextList;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.Refusal;
import ai.kumbuka.dispatch.surface.RefusalCode;
import ai.kumbuka.dispatch.surface.Surface;
import ai.kumbuka.dispatch.surface.SurfaceException;
import ai.kumbuka.dispatch.surface.UnexpectedFailures;
import ai.kumbuka.dispatch.surface.VerbName;
import ai.kumbuka.dispatch.surface.VerbSurface;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The adapter's half of a call over a verb surface that is not there: what
 * the form check refuses before anything below it is called, how one checked
 * call becomes one call of the verb surface, and how a kernel refusal is told.
 *
 * <p>Every refusal of an argument is decided before the verb surface is
 * reached, which {@code verifyNoInteractions} observes: nothing below the
 * adapter was called, so nothing could be written.
 *
 * <p>{@link #made} takes the path an adapter takes: the form check, the one
 * call, and at the edge the refusal caught and told. The texts a call answers
 * with come from the message bundles, which the running application builds,
 * so this runs with it.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class CallBindingIT {

    private static final Actor C = new Actor("commissioner", Actor.Kind.CONSOLE);
    private static final Actor H = new Actor("holder", Actor.Kind.EXECUTOR);
    private static final String ADDRESS = "dispatch://probe/sprint/7.0";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T09:00:00Z"),
        ZoneOffset.UTC);

    private VerbSurface verbs;

    @BeforeEach
    void wire() {
        verbs = mock(VerbSurface.class);
    }

    // =======================================================================
    // Refused before the verb surface: nothing below is called
    // =======================================================================

    @Test
    void an_invented_argument_is_refused_by_name_at_either_level_for_every_call() {
        for (ProcessVerb call : ProcessVerb.values()) {
            Map<String, Object> top = new LinkedHashMap<>(complete(call));
            top.put("invented", "x");
            Refusal atTop = refusal(made(Surface.MCP, C, call, top));
            assertThat(atTop.code()).as(call.call()).isEqualTo(RefusalCode.ARGUMENT_UNKNOWN);
            assertThat(atTop.message() + atTop.data()).as(call.call())
                .doesNotContain("invented");
            assertThat(atTop.data().accepts()).as(call.call()).isNotEmpty();

            Map<String, Object> nested = new LinkedHashMap<>(complete(call));
            Map<String, Object> fields = new LinkedHashMap<>(fieldsOf(nested));
            fields.put("invented_field", "x");
            nested.put(CallPayload.FIELDS, fields);
            Refusal below = refusal(made(Surface.MCP, C, call, nested));
            assertThat(below.code()).as(call.call()).isEqualTo(RefusalCode.ARGUMENT_UNKNOWN);
            assertThat(below.message() + below.data()).as(call.call())
                .doesNotContain("invented_field");
        }
        verifyNoInteractions(verbs);
    }

    @Test
    void a_missing_mandatory_argument_is_refused_by_the_surface_for_every_call() {
        for (ProcessVerb call : ProcessVerb.values()) {
            for (Argument argument : call.arguments()) {
                if (!argument.required() || argument.name().equals("conflict_token")) {
                    continue;
                }
                Map<String, Object> without = new LinkedHashMap<>(complete(call));
                if (argument.isField()) {
                    Map<String, Object> fields = new LinkedHashMap<>(fieldsOf(without));
                    fields.remove(argument.name());
                    without.put(CallPayload.FIELDS, fields);
                } else {
                    without.remove(argument.name());
                }
                Refusal refused = refusal(made(Surface.MCP, C, call, without));
                assertThat(refused.code())
                    .as("%s without %s", call.call(), argument.name())
                    .isEqualTo(RefusalCode.ARGUMENT_MISSING);
                assertThat(refused.message()).contains(argument.name());
            }
        }
        verifyNoInteractions(verbs);
    }

    /**
     * The conflict token is the verb surface's to handle (C1.1): a call
     * without one reaches it, after the scope has resolved, and the kernel
     * refuses it as {@code CONFLICT_TOKEN_MISSING} where the caller may see the
     * task. Here the verb surface is a mock, so what is observed is that the
     * call reached it with no token.
     */
    @Test
    void a_call_without_its_conflict_token_reaches_the_verb_surface() {
        when(verbs.update(eq(C), any(), any(), isNull())).thenThrow(new DispatchException(
            DispatchException.Reason.CONFLICT_TOKEN_MISSING, "kernel words"));

        Refusal refused = refusal(made(Surface.MCP, C, ProcessVerb.UPDATE,
            Map.of("address", ADDRESS, "fields", Map.of("title", "t"))));

        verify(verbs).update(eq(C), any(), any(), isNull());
        assertThat(refused.code()).isEqualTo(RefusalCode.CONFLICT_TOKEN_MISSING);
        assertThat(refused.code().status()).isEqualTo(428);
        assertThat(refused.data().attempted()).isEqualTo("dispatch_update");
    }

    @Test
    void an_address_whose_number_does_not_fit_is_refused_by_name_on_both_surfaces() {
        for (String past : List.of("dispatch://probe/sprint/2147483648.0",
                "dispatch://probe/sprint/7.99999999999")) {
            for (Surface surface : Surface.values()) {
                Refusal refused = refusal(made(surface, C, ProcessVerb.READ,
                    Map.of("address", past)));
                assertThat(refused.code()).as(past).isEqualTo(RefusalCode.ADDRESS_MALFORMED);
                assertThat(refused.message()).startsWith("address is not valid for ")
                    .doesNotContain(past);
                assertThat(refused.data().offenders()).containsExactly("address");
            }
        }
        verifyNoInteractions(verbs);
    }

    @Test
    void a_lease_or_a_deferral_past_what_a_task_stores_is_refused_by_name() {
        Refusal lease = refusal(made(Surface.MCP, H, ProcessVerb.CLAIM,
            Map.of("address", ADDRESS, "duration", "PT3000000000H")));
        assertThat(lease.code()).isEqualTo(RefusalCode.ARGUMENT_INVALID);
        assertThat(lease.message()).startsWith("duration is not valid for dispatch_claim")
            .doesNotContain("PT3000000000H");
        for (String notBefore : List.of("+1000000000-01-01T00:00:00Z", "-5000-01-01T00:00:00Z")) {
            Refusal deferral = refusal(made(Surface.MCP, H, ProcessVerb.DEFER,
                Map.of("address", ADDRESS, "receipt", "r",
                    "fields", Map.of("not_before", notBefore))));
            assertThat(deferral.code()).as(notBefore).isEqualTo(RefusalCode.ARGUMENT_INVALID);
            assertThat(deferral.message()).startsWith("fields.not_before is not valid for ")
                .doesNotContain(notBefore);
        }
        verifyNoInteractions(verbs);
    }

    /**
     * A lease of no length or of a negative one is a form fault the adapter
     * refuses before the scope is resolved, by the argument that carried it.
     */
    @Test
    void a_lease_of_no_length_or_a_negative_one_is_refused_by_name() {
        for (String duration : List.of("PT0S", "-PT1H", "PT-5M")) {
            Refusal refused = refusal(made(Surface.MCP, H, ProcessVerb.CLAIM,
                Map.of("address", ADDRESS, "duration", duration)));
            assertThat(refused.code()).as(duration).isEqualTo(RefusalCode.ARGUMENT_INVALID);
            assertThat(refused.data().offenders()).as(duration).containsExactly("duration");
            assertThat(refused.message() + refused.data()).as(duration)
                .doesNotContain(duration);
        }
        verifyNoInteractions(verbs);
    }

    @Test
    void a_combination_the_kernel_would_meet_as_a_defect_is_refused_first() {
        Map<String, Object> ask = Map.of("address", ADDRESS, "receipt", "r",
            "fields", Map.of("question", "which?"));
        assertThat(refusal(made(Surface.MCP, H, ProcessVerb.ASK, ask)).code())
            .as("a question with no option and no free text could not be answered")
            .isEqualTo(RefusalCode.ARGUMENT_MISSING);
        Map<String, Object> both = Map.of("address", ADDRESS, "conflict_token", "t",
            "fields", Map.of("option", "a", "text", "b"));
        assertThat(refusal(made(Surface.MCP, C, ProcessVerb.ANSWER, both)).code())
            .isEqualTo(RefusalCode.ARGUMENT_INVALID);
        Map<String, Object> hold = Map.of("address", ADDRESS, "receipt", "r",
            "fields", Map.of("reason", "question"));
        assertThat(refusal(made(Surface.MCP, H, ProcessVerb.HOLD, hold)).code())
            .as("a question pauses through ask")
            .isEqualTo(RefusalCode.ARGUMENT_INVALID);
        Map<String, Object> empty = Map.of("address", ADDRESS, "conflict_token", "t",
            "fields", Map.of());
        assertThat(refusal(made(Surface.MCP, C, ProcessVerb.UPDATE, empty)).code())
            .isEqualTo(RefusalCode.ARGUMENT_MISSING);
        verifyNoInteractions(verbs);
    }

    @Test
    void an_undeclared_filter_is_refused() {
        Map<String, Object> query = Map.of("scope", "probe", "selector", "sprint",
            "holder", "self");
        Refusal refused = refusal(made(Surface.MCP, C, ProcessVerb.QUERY, query));
        assertThat(refused.code()).isEqualTo(RefusalCode.ARGUMENT_UNKNOWN);
        assertThat(refused.message() + refused.data()).doesNotContain("holder");
        verifyNoInteractions(verbs);
    }

    // =======================================================================
    // What comes back
    // =======================================================================

    @Test
    void after_a_claim_read_text_stands_first_in_next_with_the_concept_s_sentence() {
        TaskView held = view(TaskState.ACTIVE, HolderState.SELF,
            List.of(TaskVerb.RELEASE, TaskVerb.DELIVER));
        when(verbs.claim(any(), any(TaskCall.class), any(IdempotencyKey.class)))
            .thenReturn(new VerbSurface.Claimed(new VerbSurface.Result("probe", held), "rcpt"));

        CallBinding.Answered answered = (CallBinding.Answered) made(Surface.MCP, H,
            ProcessVerb.CLAIM, Map.of("address", ADDRESS));

        assertThat(answered.next().get(0).call()).isEqualTo(TargetSurface.FIRST_NEXT_CALL);
        assertThat(answered.next().get(0).does()).isEqualTo(TargetSurface.FIRST_NEXT_DOES);
        assertThat(answered.next().subList(1, 3).stream().map(NextList.Step::call).toList())
            .containsExactly("dispatch_release", "dispatch_deliver");
        assertThat(answered.receipt()).isEqualTo("rcpt");
    }

    @Test
    void the_commissioner_of_a_delivered_task_reads_the_answer_before_accepting() {
        TaskView delivered = view(TaskState.DELIVERED, HolderState.OTHER,
            List.of(TaskVerb.REWORK, TaskVerb.ACCEPT, TaskVerb.WITHDRAW));
        when(verbs.read(eq(C), any())).thenReturn(new VerbSurface.Result("probe", delivered));

        CallBinding.Answered answered = (CallBinding.Answered) made(Surface.REST, C,
            ProcessVerb.READ, Map.of("address", ADDRESS));

        assertThat(answered.next().stream().map(NextList.Step::call).toList())
            .containsExactly("read_text", "rework", "accept", "withdraw");
    }

    @Test
    void where_nothing_is_open_the_answer_says_what_the_task_waits_for() {
        TaskView held = view(TaskState.ACTIVE, HolderState.OTHER, List.of());
        when(verbs.read(eq(C), any())).thenReturn(new VerbSurface.Result("probe", held));

        CallBinding.Answered answered = (CallBinding.Answered) made(Surface.MCP, C,
            ProcessVerb.READ, Map.of("address", ADDRESS));

        assertThat(answered.next()).isEmpty();
        assertThat(answered.waitingFor()).isEqualTo("its holder");
    }

    /**
     * A stale token is told with the task as it stands (DEC-0041, C8.7): its
     * address, its fields, its current token and the service that holds it.
     */
    @Test
    void a_stale_conflict_token_is_refused_with_the_task_as_it_stands() {
        TaskView open = view(TaskState.OPEN, HolderState.NOBODY, List.of(TaskVerb.WITHDRAW));
        when(verbs.act(any(), eq(TaskVerb.WITHDRAW), any())).thenThrow(new DispatchException(
            DispatchException.Reason.CONFLICT_TOKEN_STALE, "kernel words"));

        Refusal refused = refusal(made(Surface.MCP, C, ProcessVerb.WITHDRAW,
            Map.of("address", ADDRESS, "conflict_token", "stale"),
            complete -> Optional.of(new VerbSurface.Result("probe", open))));

        assertThat(refused.code()).isEqualTo(RefusalCode.CONFLICT_TOKEN_STALE);
        assertThat(refused.data().conflictToken()).isEqualTo("the-current-token");
        assertThat(refused.data().address()).isEqualTo(ADDRESS);
        assertThat(refused.data().current()).isEqualTo(open);
        assertThat(refused.data().service()).isEqualTo("dispatch");
        assertThat(refused.message()).contains("data.conflict_token")
            .doesNotContain("the-current-token").doesNotContain("kernel words");
        assertThat(refused.data().state()).isEqualTo("open");
    }

    @Test
    void a_claim_before_a_deferral_has_passed_is_refused_as_deferral_pending() {
        TaskView deferred = view(TaskState.OPEN, HolderState.NOBODY, List.of());
        when(verbs.claim(any(), any(TaskCall.class), any(IdempotencyKey.class))).thenThrow(
            new DispatchException(DispatchException.Reason.DEFERRAL_PENDING, "kernel words"));

        Refusal refused = refusal(made(Surface.MCP, H, ProcessVerb.CLAIM,
            Map.of("address", ADDRESS),
            complete -> Optional.of(new VerbSurface.Result("probe", deferred))));

        assertThat(refused.code()).isEqualTo(RefusalCode.DEFERRAL_PENDING);
    }

    /**
     * The holder's call by a caller who holds nothing is told by who holds the
     * task: another executor ({@code CLAIM_HELD}), or nobody, because nobody
     * does or the caller's own lease ended ({@code CLAIM_ABSENT}); each 409
     * and each with the attempted call, the state and the calls open, and
     * nothing the task waits for (C8.6, C8.7).
     */
    @Test
    void a_holder_s_call_by_a_caller_who_holds_nothing_is_told_by_who_holds_the_task() {
        for (Object[] row : new Object[][] {
                {HolderState.OTHER, DispatchException.Reason.CLAIM_REQUIRED,
                    RefusalCode.CLAIM_HELD},
                {HolderState.NOBODY, DispatchException.Reason.CLAIM_REQUIRED,
                    RefusalCode.CLAIM_ABSENT},
                {HolderState.NOBODY, DispatchException.Reason.LEASE_LAPSED,
                    RefusalCode.CLAIM_ABSENT}}) {
            TaskView task = view(TaskState.ACTIVE, (HolderState) row[0], List.of());
            when(verbs.act(any(), eq(TaskVerb.RELEASE), any())).thenThrow(
                new DispatchException((DispatchException.Reason) row[1], "kernel words"));

            Refusal refused = refusal(made(Surface.MCP, H, ProcessVerb.RELEASE,
                Map.of("address", ADDRESS, "receipt", "r"),
                complete -> Optional.of(new VerbSurface.Result("probe", task))));

            assertThat(refused.code()).as("%s", row[0]).isEqualTo(row[2]);
            assertThat(refused.code().status()).isEqualTo(409);
            assertThat(refused.data().attempted()).isEqualTo("dispatch_release");
            assertThat(refused.data().state()).isEqualTo("active");
            assertThat(refused.data().next()).isNotNull();
            assertThat(refused.data().waitingFor()).isNull();
        }
    }

    /**
     * The one code no caller can raise on the wire, held to DEC-0042 here: a
     * kernel that fails with the caller's value in its own message, under a
     * call that carries it, answers with neither. The wire half is {@code
     * UnexpectedFailureIT}.
     */
    @Test
    void an_unexpected_failure_gives_back_no_value_the_call_carried() {
        String marker = "zzecho7319";
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("scope", "probe");
        arguments.put("selector", "sprint");
        arguments.put("fields", Map.of("title", marker, "apparatus", "code"));

        Refusal refused = UnexpectedFailures.refuse(
            VerbName.of(ProcessVerb.CREATE, Surface.MCP), DomainRefusals.whereIn(arguments),
            new IllegalStateException("broke on " + marker));

        assertThat(refused.code()).isEqualTo(RefusalCode.UNEXPECTED_FAILURE);
        assertThat(refused.message()).doesNotContain(marker);
        assertThat(String.valueOf(refused.data())).doesNotContain(marker);
        assertThat(refused.data().reference()).isNotBlank();
    }

    // =======================================================================

    /** One call as an adapter makes it, with no task to read for a refusal. */
    private Object made(Surface surface, Actor actor, ProcessVerb verb,
                        Map<String, Object> arguments) {
        return made(surface, actor, verb, arguments, complete -> Optional.empty());
    }

    /** One call as an adapter makes it: the form check, the call, the refusal told. */
    private Object made(Surface surface, Actor actor, ProcessVerb verb,
                        Map<String, Object> arguments, DomainRefusals.Reader read) {
        try {
            return CallBinding.make(verbs, surface, actor,
                new CallPayload(verb, surface, arguments), CLOCK);
        } catch (SurfaceException refused) {
            return refused.refusal();
        } catch (DispatchException refused) {
            return DomainRefusals.of(refused, surface, actor, verb, arguments, read, CLOCK);
        }
    }

    /** A complete, valid argument map for one call. */
    private static Map<String, Object> complete(ProcessVerb call) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Argument argument : call.arguments()) {
            if (!argument.required()) {
                continue;
            }
            Object value = switch (argument.type()) {
                case Argument.ARRAY -> List.of("code");
                case Argument.BOOLEAN -> Boolean.TRUE;
                case Argument.INTEGER -> 10;
                case Argument.OBJECT -> Map.of();
                default -> argument.values().isEmpty()
                    ? valueFor(argument.name())
                    : argument.valueNames().get(0);
            };
            (argument.isField() ? fields : arguments).put(argument.name(), value);
        }
        if (!fields.isEmpty()) {
            arguments.put(CallPayload.FIELDS, fields);
        }
        return arguments;
    }

    private static String valueFor(String name) {
        return switch (name) {
            case "address", "curated_in" -> ADDRESS;
            case "scope" -> "probe";
            case "selector" -> "sprint";
            case "not_before" -> "2026-10-07T09:00:00Z";
            default -> "a " + name;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldsOf(Map<String, Object> arguments) {
        Object fields = arguments.get(CallPayload.FIELDS);
        return fields instanceof Map ? (Map<String, Object>) fields : Map.of();
    }

    private static Refusal refusal(Object outcome) {
        assertThat(outcome).isInstanceOf(Refusal.class);
        return (Refusal) outcome;
    }

    private static TaskView view(TaskState state, HolderState holder, List<TaskVerb> next) {
        return new TaskView(UUID.randomUUID(), new ExchangeAddress("sprint", 7, 0),
            state, null, null, null, holder, holder == HolderState.SELF ? CLOCK.instant() : null, null,
            null, 0, "a title", "code", null, null, "the-current-token", null, List.of(), next);
    }
}
