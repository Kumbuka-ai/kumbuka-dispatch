package ai.kumbuka.dispatch.surface;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The router over a kernel that is not there: what it refuses before anything
 * below it is called, and how it words what comes back.
 *
 * <p>Every refusal of an argument is decided before the verb surface is
 * reached, which {@code verifyNoInteractions} observes: nothing below the
 * router was called, so nothing could be written.
 */
class CallRouterTest {

    private static final Actor C = new Actor("commissioner", Actor.Kind.CONSOLE);
    private static final Actor H = new Actor("holder", Actor.Kind.EXECUTOR);
    private static final String ADDRESS = "dispatch://probe/sprint/7.0";

    private VerbSurface verbs;
    private CallRouter router;

    @BeforeEach
    void wire() {
        verbs = mock(VerbSurface.class);
        router = new CallRouter();
        router.verbs = verbs;
    }

    // =======================================================================
    // Refused before the kernel: nothing below is called
    // =======================================================================

    @Test
    void an_earlier_name_is_refused_as_unknown_on_both_surfaces() {
        for (String earlier : List.of("dispatch_commission", "dispatch_take",
                "dispatch_accept_return", "dispatch_decline", "dispatch_close_bracket")) {
            Refused refused = refusal(router.call(Surface.MCP, () -> C, earlier, Map.of()));
            assertThat(refused.code()).isEqualTo(RefusalCode.ARGUMENT_UNKNOWN);
            assertThat(refused.getMessage()).contains(earlier).contains("call");
        }
        for (String earlier : List.of("abandon", "block", "close", "consume", "takeup")) {
            Refused refused = refusal(router.call(Surface.REST, () -> C, earlier, Map.of()));
            assertThat(refused.code()).isEqualTo(RefusalCode.ARGUMENT_UNKNOWN);
        }
        verifyNoInteractions(verbs);
    }

    @Test
    void an_invented_argument_is_refused_by_name_at_either_level_for_every_call() {
        for (ProcessVerb call : ProcessVerb.values()) {
            Map<String, Object> top = new LinkedHashMap<>(complete(call));
            top.put("invented", "x");
            Refused atTop = refusal(router.call(Surface.MCP, () -> C, call.call(), top));
            assertThat(atTop.code()).as(call.call()).isEqualTo(RefusalCode.ARGUMENT_UNKNOWN);
            assertThat(atTop.getMessage()).as(call.call()).contains("invented");

            Map<String, Object> nested = new LinkedHashMap<>(complete(call));
            Map<String, Object> fields = new LinkedHashMap<>(fieldsOf(nested));
            fields.put("invented_field", "x");
            nested.put(CallArguments.FIELDS, fields);
            Refused below = refusal(router.call(Surface.MCP, () -> C, call.call(), nested));
            assertThat(below.code()).as(call.call()).isEqualTo(RefusalCode.ARGUMENT_UNKNOWN);
            assertThat(below.getMessage()).as(call.call())
                .contains(call.hasFields() ? "invented_field" : CallArguments.FIELDS);
        }
        verifyNoInteractions(verbs);
    }

    @Test
    void a_missing_mandatory_argument_is_refused_by_the_surface_for_every_call() {
        for (ProcessVerb call : ProcessVerb.values()) {
            for (Argument argument : call.arguments()) {
                if (!argument.required()) {
                    continue;
                }
                Map<String, Object> without = new LinkedHashMap<>(complete(call));
                if (argument.isField()) {
                    Map<String, Object> fields = new LinkedHashMap<>(fieldsOf(without));
                    fields.remove(argument.name());
                    without.put(CallArguments.FIELDS, fields);
                } else {
                    without.remove(argument.name());
                }
                Refused refused = refusal(router.call(Surface.MCP, () -> C, call.call(),
                    without));
                assertThat(refused.code())
                    .as("%s without %s", call.call(), argument.name())
                    .isEqualTo(RefusalCode.ARGUMENT_MISSING);
                assertThat(refused.getMessage()).contains(argument.name());
            }
        }
        verifyNoInteractions(verbs);
    }

    @Test
    void a_combination_the_kernel_would_meet_as_a_defect_is_refused_first() {
        Map<String, Object> ask = Map.of("address", ADDRESS, "receipt", "r",
            "fields", Map.of("question", "which?"));
        assertThat(refusal(router.call(Surface.MCP, () -> H, "dispatch_ask", ask)).code())
            .as("a question with no option and no free text could not be answered")
            .isEqualTo(RefusalCode.ARGUMENT_MISSING);
        Map<String, Object> both = Map.of("address", ADDRESS, "conflict_token", "t",
            "fields", Map.of("option", "a", "text", "b"));
        assertThat(refusal(router.call(Surface.MCP, () -> C, "dispatch_answer", both)).code())
            .isEqualTo(RefusalCode.ARGUMENT_INVALID);
        Map<String, Object> hold = Map.of("address", ADDRESS, "receipt", "r",
            "fields", Map.of("reason", "question"));
        assertThat(refusal(router.call(Surface.MCP, () -> H, "dispatch_hold", hold)).code())
            .as("a question pauses through ask")
            .isEqualTo(RefusalCode.ARGUMENT_INVALID);
        Map<String, Object> empty = Map.of("address", ADDRESS, "conflict_token", "t",
            "fields", Map.of());
        assertThat(refusal(router.call(Surface.MCP, () -> C, "dispatch_update", empty)).code())
            .isEqualTo(RefusalCode.ARGUMENT_MISSING);
        verifyNoInteractions(verbs);
    }

    @Test
    void an_undeclared_filter_is_refused() {
        Map<String, Object> query = Map.of("scope", "probe", "selector", "sprint",
            "holder", "self");
        Refused refused = refusal(router.call(Surface.MCP, () -> C, "dispatch_query", query));
        assertThat(refused.code()).isEqualTo(RefusalCode.ARGUMENT_UNKNOWN);
        assertThat(refused.getMessage()).contains("holder");
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

        CallRouter.Answered answered = (CallRouter.Answered) router.call(Surface.MCP,
            () -> H, "dispatch_claim", Map.of("address", ADDRESS));

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

        CallRouter.Answered answered = (CallRouter.Answered) router.call(Surface.REST,
            () -> C, "read", Map.of("address", ADDRESS));

        assertThat(answered.next().stream().map(NextList.Step::call).toList())
            .containsExactly("read_text", "rework", "accept", "withdraw");
    }

    @Test
    void where_nothing_is_open_the_answer_says_what_the_task_waits_for() {
        TaskView held = view(TaskState.ACTIVE, HolderState.OTHER, List.of());
        when(verbs.read(eq(C), any())).thenReturn(new VerbSurface.Result("probe", held));

        CallRouter.Answered answered = (CallRouter.Answered) router.call(Surface.MCP,
            () -> C, "dispatch_read", Map.of("address", ADDRESS));

        assertThat(answered.next()).isEmpty();
        assertThat(answered.waitingFor()).isEqualTo("its holder");
    }

    @Test
    void a_stale_conflict_token_is_refused_with_the_current_one() {
        TaskView open = view(TaskState.OPEN, HolderState.NOBODY, List.of(TaskVerb.WITHDRAW));
        when(verbs.act(any(), eq(TaskVerb.WITHDRAW), any())).thenThrow(new DispatchException(
            DispatchException.Reason.CONFLICT_TOKEN_STALE, "kernel words"));
        when(verbs.read(eq(C), any())).thenReturn(new VerbSurface.Result("probe", open));

        Refused refused = refusal(router.call(Surface.MCP, () -> C, "dispatch_withdraw",
            Map.of("address", ADDRESS, "conflict_token", "stale")));

        assertThat(refused.code()).isEqualTo(RefusalCode.CONFLICT_TOKEN_STALE);
        assertThat(refused.data()).containsEntry(Refused.CONFLICT_TOKEN, "the-current-token");
        assertThat(refused.getMessage()).contains("the-current-token")
            .doesNotContain("kernel words");
        assertThat(refused.data()).containsEntry(Refused.STATE, "open");
    }

    @Test
    void a_claim_before_a_deferral_has_passed_is_refused_as_deferral_pending() {
        TaskView deferred = view(TaskState.OPEN, HolderState.NOBODY, List.of());
        when(verbs.claim(any(), any(TaskCall.class), any(IdempotencyKey.class))).thenThrow(
            new DispatchException(DispatchException.Reason.DEFERRAL_PENDING, "kernel words"));
        when(verbs.read(eq(H), any())).thenReturn(new VerbSurface.Result("probe", deferred));

        Refused refused = refusal(router.call(Surface.MCP, () -> H, "dispatch_claim",
            Map.of("address", ADDRESS)));

        assertThat(refused.code()).isEqualTo(RefusalCode.DEFERRAL_PENDING);
    }

    // =======================================================================

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
                    : argument.values().get(0).equals("question")
                        ? argument.values().get(1) : argument.values().get(0);
            };
            (argument.isField() ? fields : arguments).put(argument.name(), value);
        }
        if (!fields.isEmpty()) {
            arguments.put(CallArguments.FIELDS, fields);
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
        Object fields = arguments.get(CallArguments.FIELDS);
        return fields instanceof Map ? (Map<String, Object>) fields : Map.of();
    }

    private static Refused refusal(CallRouter.Outcome outcome) {
        assertThat(outcome).isInstanceOf(CallRouter.Refusal.class);
        return ((CallRouter.Refusal) outcome).refused();
    }

    private static TaskView view(TaskState state, HolderState holder, List<TaskVerb> next) {
        return new TaskView(UUID.randomUUID(), new ExchangeAddress("sprint", 7, 0),
            state, null, null, holder, holder == HolderState.SELF ? Instant.now() : null, null,
            null, 0, "a title", "code", null, null, "the-current-token", null, List.of(), next);
    }
}
