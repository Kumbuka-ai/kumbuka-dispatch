package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static ai.kumbuka.dispatch.domain.TaskStage.C;
import static ai.kumbuka.dispatch.domain.TaskStage.H;
import static ai.kumbuka.dispatch.domain.TaskStage.K;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance criterion 2, against a running database under the service role:
 * over every reachable situation, every call the kernel lists as open to a
 * caller succeeds, and every call it does not list is refused for the state
 * or for the caller's entitlement.
 *
 * <p>Each attempt runs on a fresh task staged into the situation through the
 * kernel, so one attempt never decides on what another wrote. Each presents
 * what a well-formed request presents -- H's receipt, the current conflict
 * token, a valid payload -- so a refusal that is about the request rather
 * than the situation shows up as a failure here.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskLifecycleIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");

    /** The refusals that say "not in this state" or "not this caller". */
    static final Set<DispatchException.Reason> STATE_OR_ENTITLEMENT = EnumSet.of(
        DispatchException.Reason.TRANSITION_NOT_PERMITTED,
        DispatchException.Reason.LEASE_LAPSED,
        DispatchException.Reason.DEFERRAL_PENDING,
        DispatchException.Reason.ACTOR_UNKNOWN,
        DispatchException.Reason.CLAIM_REQUIRED,
        DispatchException.Reason.RATIFICATION_NOT_PERMITTED);

    /** The effective state each staged situation must read as. Literal. */
    static final Map<String, TaskState> READS_AS = Map.ofEntries(
        Map.entry("draft", TaskState.DRAFT), Map.entry("open", TaskState.OPEN),
        Map.entry("deferred", TaskState.OPEN), Map.entry("active", TaskState.ACTIVE),
        Map.entry("lapsed", TaskState.OPEN), Map.entry("asked", TaskState.ON_HOLD),
        Map.entry("waiting", TaskState.ON_HOLD), Map.entry("blocked", TaskState.ON_HOLD),
        Map.entry("parked", TaskState.ON_HOLD), Map.entry("delivered", TaskState.DELIVERED),
        Map.entry("closed", TaskState.CLOSED));

    @Inject TaskService tasks;
    @Inject TenantContext tenantContext;

    private UUID tenant;
    private AutoCloseable binding;
    private TaskStage stage;
    private int selectors;

    @BeforeEach
    void freshTenant() {
        tenant = UUID.randomUUID();
        DomainFixture.declareSelector(tenant, SCOPE, "sprint");
        binding = tenantContext.bind(tenant);
        stage = new TaskStage(tasks, SCOPE);
    }

    @AfterEach
    void unbind() throws Exception {
        binding.close();
    }

    @Test
    void every_listed_call_succeeds_and_every_other_is_refused_for_state_or_entitlement() {
        List<String> wrong = new ArrayList<>();
        int attempts = 0;
        for (String situation : TaskStage.SITUATIONS) {
            for (Actor caller : List.of(C, H, K)) {
                TaskStage.Staged probe = stage.stage(situation, "sprint");
                TaskView head = tasks.read(SCOPE, probe.address(), caller);
                assertThat(head.state()).as("%s is reachable and reads as such", situation)
                    .isEqualTo(READS_AS.get(situation));
                List<TaskVerb> open = head.next();

                for (TaskVerb verb : TaskVerb.values()) {
                    attempts++;
                    String selector = verb == TaskVerb.CLAIM_NEXT ? freshSelector() : "sprint";
                    String outcome = attempt(verb, stage.stage(situation, selector), caller);
                    String where = verb.wireName() + " in " + situation + " by "
                        + caller.subject();
                    if (open.contains(verb) && !"ok".equals(outcome)) {
                        wrong.add(where + " is listed as open and was refused: " + outcome);
                    }
                    if (!open.contains(verb) && !refusedForStateOrEntitlement(verb, outcome)) {
                        wrong.add(where + " is not listed and ended: " + outcome);
                    }
                }
            }
        }
        assertThat(attempts).isEqualTo(11 * 3 * 16);
        assertThat(wrong)
            .as("next must be the list of calls that succeed: one that fails is a list that "
                + "misleads, and one that succeeds unlisted is a call nobody was told of")
            .isEmpty();
    }

    /**
     * A refusal of the state or the entitlement. A draw that finds nothing
     * drawable is the state statement of a set: {@code claim_next} names no
     * task, so where the one task is not open there is nothing to draw.
     */
    private static boolean refusedForStateOrEntitlement(TaskVerb verb, String outcome) {
        if (verb == TaskVerb.CLAIM_NEXT && outcome.equals("NOTHING_TO_CLAIM")) {
            return true;
        }
        return STATE_OR_ENTITLEMENT.stream().anyMatch(r -> r.name().equals(outcome));
    }

    /** One well-formed call of {@code verb} on a staged task; "ok" or the reason. */
    private String attempt(TaskVerb verb, TaskStage.Staged s, Actor caller) {
        TaskCall call = TaskCall.by(caller).withReceipt(s.receipt())
            .withConflictToken(stage.token(s)).with(payload(verb));
        try {
            switch (verb) {
                case CLAIM -> tasks.claim(SCOPE, s.address(), call);
                case CLAIM_NEXT -> {
                    TaskClaim drawn = tasks.claimNext(SCOPE, s.selector(), List.of("code"), call);
                    if (!drawn.task().address().equals(s.address())) {
                        return "drew " + drawn.task().address();
                    }
                }
                default -> tasks.act(SCOPE, s.address(), verb, call);
            }
            return "ok";
        } catch (DispatchException refused) {
            return refused.reason().name();
        }
    }

    private static TaskPayload payload(TaskVerb verb) {
        return verb == TaskVerb.DEFER
            ? new TaskPayload.Deferral(Instant.now().plusSeconds(3600), null)
            : TransitionMatrixTest.validPayload(verb);
    }

    /** A selector of its own, so a draw can only find the one task staged under it. */
    private String freshSelector() {
        String name = "draw-" + (++selectors);
        DomainFixture.declareSelector(tenant, SCOPE, name);
        return name;
    }
}
