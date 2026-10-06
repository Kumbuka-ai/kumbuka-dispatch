package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.surface.Mcp;
import ai.kumbuka.dispatch.surface.SurfaceFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The main course of a task over the assistant surface, from create to
 * accept, and what each answer carries.
 *
 * <p>Criterion 4: no answer of a transition and no answer of a writing call
 * carries a text of the task, and an executor that does not hold a task gets
 * no text of it. Every text written here carries a marker, and every answer
 * but {@code dispatch_read_text}'s is searched for all of them, in its
 * structured content and in its text content alike.
 *
 * <p>Red probes, observed: a transition's answer that carried the commission
 * text turns {@link #the_main_course_carries_text_only_in_read_text} red; and
 * with {@code dispatch_read_text} not first in {@code next} after a claim,
 * {@link #after_a_claim_next_names_read_text_first} turns red.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ProcessSurfaceIT {

    private static final String COMMISSION = "MARK-COMMISSION the work to do";
    private static final String REVISED = "MARK-REVISED the work, revised";
    private static final String QUESTION = "MARK-QUESTION which one?";
    private static final String ANSWER = "MARK-ANSWER the delivered answer";
    private static final String ADDENDUM = "MARK-ADDENDUM one more thing";
    private static final List<String> MARKERS = List.of("MARK-COMMISSION", "MARK-REVISED",
        "MARK-QUESTION", "MARK-ANSWER", "MARK-ADDENDUM", "MARK-REMARK");

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    void the_main_course_carries_text_only_in_read_text() {
        List<Response> answers = new ArrayList<>();

        // The commissioner creates, revises and sends.
        Response created = call(answers, "dispatch_create", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
            "fields", Map.of("title", "the main course", "apparatus", "code",
                "text", COMMISSION)));
        String address = String.valueOf(Mcp.answer(created).get("address"));
        Response updated = call(answers, "dispatch_update", Map.of("address", address,
            "conflict_token", token(created), "fields", Map.of("text", REVISED)));
        Response sent = call(answers, "dispatch_send", Map.of("address", address,
            "conflict_token", token(updated)));
        assertThat(Mcp.field(Mcp.answer(sent), "state")).isEqualTo("open");

        // An executor that does not hold the task reads no text of it.
        SurfaceFixture.asExecutor(identity);
        Response notHeld = Mcp.call("dispatch_read_text", Map.of("address", address,
            "part", "dispatch"));
        assertThat(Mcp.reason(notHeld)).isEqualTo("NOT_THE_HOLDER");
        answers.add(notHeld);

        // It claims, reads the commission, asks.
        Response claimed = call(answers, "dispatch_claim", Map.of("address", address));
        String receipt = String.valueOf(Mcp.answer(claimed).get("receipt"));
        Map<String, Object> text = Mcp.answer(Mcp.call("dispatch_read_text", Map.of(
            "address", address, "part", "dispatch")));
        assertThat(texts(text)).as("the holder reads the revised commission")
            .containsExactly(REVISED);
        call(answers, "dispatch_ask", Map.of("address", address, "receipt", receipt,
            "fields", Map.of("question", QUESTION, "options", List.of("left", "right"))));

        // The commissioner answers; the holder delivers.
        SurfaceFixture.asConsole(identity);
        Response asked = call(answers, "dispatch_read", Map.of("address", address));
        assertThat(Mcp.field(Mcp.answer(asked), "hold_reason")).isEqualTo("question");
        call(answers, "dispatch_answer", Map.of("address", address,
            "conflict_token", token(asked), "fields", Map.of("option", "left")));

        SurfaceFixture.asExecutor(identity);
        Map<String, Object> thread = Mcp.answer(Mcp.call("dispatch_read_text", Map.of(
            "address", address, "part", "thread")));
        assertThat(texts(thread)).containsExactly(QUESTION, "left");
        call(answers, "dispatch_deliver", Map.of("address", address, "receipt", receipt,
            "fields", Map.of("text", ANSWER, "metadata", Map.of("pr", "17"))));
        call(answers, "dispatch_annotate", Map.of("address", address,
            "fields", Map.of("part", "return", "text", ADDENDUM)));

        // The commissioner reads the head, then the answer, and accepts.
        SurfaceFixture.asConsole(identity);
        Response head = call(answers, "dispatch_read", Map.of("address", address));
        Map<String, Object> delivered = Mcp.answer(head);
        assertThat(Mcp.nextCalls(delivered))
            .as("for the commissioner of a delivered task, read_text stands first, before "
                + "accept")
            .startsWith("dispatch_read_text").contains("dispatch_accept");
        Map<String, Object> answerText = Mcp.answer(Mcp.call("dispatch_read_text", Map.of(
            "address", address, "part", "return")));
        assertThat(texts(answerText)).containsExactly(ANSWER);
        assertThat(answerText.get("addenda")).as("the addenda are counted, not carried")
            .isEqualTo(1);
        call(answers, "dispatch_query", Map.of("scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR, "address", address));
        Response accepted = call(answers, "dispatch_accept", Map.of("address", address,
            "conflict_token", token(head)));
        assertThat(Mcp.field(Mcp.answer(accepted), "state")).isEqualTo("closed");
        assertThat(Mcp.field(Mcp.answer(accepted), "outcome")).isEqualTo("accepted");

        for (Response answer : answers) {
            String body = answer.asString();
            assertThat(MARKERS.stream().filter(body::contains).toList())
                .as("no answer but read_text's carries a text of the task: %s", body)
                .isEmpty();
        }
    }

    @Test
    void after_a_claim_next_names_read_text_first() {
        String address = SurfaceFixture.address(SurfaceFixture.open("a task to claim", "code"));
        SurfaceFixture.asExecutor(identity);

        Map<String, Object> claimed = Mcp.answer(Mcp.call("dispatch_claim",
            Map.of("address", address)));

        @SuppressWarnings("unchecked")
        Map<String, Object> first = ((List<Map<String, Object>>) claimed.get("next")).get(0);
        assertThat(first.get("call")).isEqualTo(TargetSurface.FIRST_NEXT_CALL);
        assertThat(first.get("does")).isEqualTo(TargetSurface.FIRST_NEXT_DOES);
        assertThat(Mcp.field(claimed, "lease_expires_at"))
            .as("the answer to the holder carries the end of the lease").isNotNull();
        assertThat(claimed.get("receipt")).isNotNull();
    }

    @Test
    void a_transition_answers_lean_and_a_read_the_full_head() {
        String id = SurfaceFixture.open("a task to read", "code");
        String address = SurfaceFixture.address(id);

        Map<String, Object> head = Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", address)));
        assertThat(Mcp.field(head, "title")).isEqualTo("a task to read");
        assertThat(Mcp.field(head, "apparatus")).isEqualTo("code");
        assertThat(Mcp.field(head, "identity")).startsWith("dispatch://");
        assertThat(Mcp.field(head, "holder")).isEqualTo("nobody");
        assertThat(Mcp.field(head, "texts")).contains("dispatch");

        SurfaceFixture.asExecutor(identity);
        Map<String, Object> lean = Mcp.answer(Mcp.call("dispatch_claim",
            Map.of("address", address)));
        assertThat(lean.keySet())
            .containsExactlyInAnyOrder("address", "fields", "conflict_token", "next", "receipt");
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) lean.get("fields");
        assertThat(fields.keySet())
            .as("the state with its attributes, and the end of the lease for the holder")
            .containsExactlyInAnyOrder("state", "lease_expires_at");
    }

    @Test
    void delete_answers_the_address_and_nothing_else() {
        Response created = Mcp.call("dispatch_create", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
            "fields", Map.of("title", "a draft to delete", "apparatus", "code")));
        String address = String.valueOf(Mcp.answer(created).get("address"));

        Map<String, Object> deleted = Mcp.answer(Mcp.call("dispatch_delete", Map.of(
            "address", address, "conflict_token", token(created))));

        assertThat(deleted).containsOnlyKeys("address");
        assertThat(Mcp.reason(Mcp.call("dispatch_read", Map.of("address", address))))
            .as("and it leaves no trace").isEqualTo("NOT_FOUND");
    }

    @Test
    void a_question_a_hold_and_a_rework_take_their_courses() {
        String address = SurfaceFixture.address(SurfaceFixture.open("the long way", "code"));
        SurfaceFixture.asExecutor(identity);
        String receipt = String.valueOf(Mcp.answer(Mcp.call("dispatch_claim",
            Map.of("address", address, "duration", "PT2H"))).get("receipt"));

        Map<String, Object> held = Mcp.answer(Mcp.call("dispatch_hold", Map.of(
            "address", address, "receipt", receipt,
            "fields", Map.of("reason", "dependency", "remark", "MARK-REMARK waiting"))));
        assertThat(Mcp.field(held, "hold_reason")).isEqualTo("dependency");
        assertThat(Mcp.nextCalls(held)).contains("dispatch_resume", "dispatch_fail");
        Mcp.answer(Mcp.call("dispatch_resume", Map.of("address", address,
            "receipt", receipt)));
        Mcp.answer(Mcp.call("dispatch_renew", Map.of("address", address,
            "receipt", receipt, "duration", "PT3H")));
        Mcp.answer(Mcp.call("dispatch_deliver", Map.of("address", address,
            "receipt", receipt, "fields", Map.of("text", "a first answer"))));

        SurfaceFixture.asConsole(identity);
        Response read = Mcp.call("dispatch_read", Map.of("address", address));
        Map<String, Object> reworked = Mcp.answer(Mcp.call("dispatch_rework", Map.of(
            "address", address, "conflict_token", token(read),
            "fields", Map.of("remark", "MARK-REMARK not yet"))));
        assertThat(Mcp.field(reworked, "state")).isEqualTo("active");
        assertThat(Mcp.nextCalls(reworked)).contains("dispatch_withdraw");
    }

    @Test
    void a_closed_task_is_related_to_what_it_was_curated_into_and_back() {
        String target = SurfaceFixture.address(SurfaceFixture.open("the record", "code"));
        String done = SurfaceFixture.address(SurfaceFixture.open("curated", "code"));
        Mcp.answer(Mcp.call("dispatch_withdraw", Map.of("address", done,
            "conflict_token", token(Mcp.call("dispatch_read", Map.of("address", done))))));

        Response self = Mcp.call("dispatch_relate", Map.of("address", done,
            "conflict_token", token(Mcp.call("dispatch_read", Map.of("address", done))),
            "fields", Map.of("curated_in", done)));
        assertThat(Mcp.reason(self)).as("never the task itself").isEqualTo("ARGUMENT_INVALID");

        Mcp.answer(Mcp.call("dispatch_relate", Map.of("address", done,
            "conflict_token", token(Mcp.call("dispatch_read", Map.of("address", done))),
            "fields", Map.of("curated_in", target))));
        Map<String, Object> related = Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", done)));
        assertThat(Mcp.field(related, "curated_in")).isEqualTo(target);
        assertThat(Mcp.field(related, "state")).as("the task stays closed").isEqualTo("closed");

        Mcp.answer(Mcp.call("dispatch_unrelate", Map.of("address", done,
            "conflict_token", related.get("conflict_token"))));
        assertThat(Mcp.field(Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", done))), "curated_in")).isNull();
    }

    @Test
    void an_executor_rejects_an_offered_task_and_fails_a_held_one() {
        String offered = SurfaceFixture.address(SurfaceFixture.open("offered", "code"));
        String held = SurfaceFixture.address(SurfaceFixture.open("held", "code"));
        SurfaceFixture.asExecutor(identity);

        Map<String, Object> rejected = Mcp.answer(Mcp.call("dispatch_reject", Map.of(
            "address", offered, "fields", Map.of("remark", "MARK-REMARK not for me"))));
        assertThat(Mcp.field(rejected, "outcome")).isEqualTo("rejected");

        String receipt = String.valueOf(Mcp.answer(Mcp.call("dispatch_claim",
            Map.of("address", held))).get("receipt"));
        Map<String, Object> failed = Mcp.answer(Mcp.call("dispatch_fail", Map.of(
            "address", held, "receipt", receipt,
            "fields", Map.of("remark", "MARK-REMARK cannot be done"))));
        assertThat(Mcp.field(failed, "outcome")).isEqualTo("failed");
        assertThat(failed.toString()).doesNotContain("MARK-REMARK");
    }

    // -----------------------------------------------------------------------

    private static Response call(List<Response> into, String tool, Map<String, Object> args) {
        Response response = Mcp.call(tool, args);
        Mcp.answer(response);
        into.add(response);
        return response;
    }

    private static String token(Response answer) {
        return String.valueOf(Mcp.answer(answer).get("conflict_token"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> texts(Map<String, Object> read) {
        return ((List<Map<String, Object>>) read.get("texts")).stream()
            .map(t -> String.valueOf(t.get("text")))
            .toList();
    }
}
