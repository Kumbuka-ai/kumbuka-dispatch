package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ai.kumbuka.dispatch.domain.TaskHoldingIT.assertRefused;
import static ai.kumbuka.dispatch.domain.TaskStage.C;
import static ai.kumbuka.dispatch.domain.TaskStage.H;
import static ai.kumbuka.dispatch.domain.TaskStage.K;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The nine calls that are not transitions (TAR-0004 section 3), and the
 * separation of process and text (section 6, acceptance criterion 6),
 * against a running database under the service role.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskCallsIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final String SELECTOR = "sprint";

    /** Part of every text this test writes, so an answer carrying one is found. */
    static final String MARK = "TEXT-MARK-";

    @Inject TaskService tasks;
    @Inject TenantContext tenantContext;

    private AutoCloseable binding;
    private TaskStage stage;

    /** A second scope of the tenant, which has no row in the directory view. */
    static final UUID OTHER_SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000011");

    @BeforeEach
    void freshTenant() {
        // The head renders a curation target from its scope's slug, read from
        // the directory view; without the grant that read is a permission error.
        PlatformFixture.grantDirectoryAccess();
        UUID tenant = UUID.randomUUID();
        DomainFixture.declareSelector(tenant, SCOPE, SELECTOR);
        DomainFixture.declareSelector(tenant, OTHER_SCOPE, SELECTOR);
        binding = tenantContext.bind(tenant);
        stage = new TaskStage(tasks, SCOPE);
    }

    @AfterEach
    void unbind() throws Exception {
        binding.close();
    }

    // =======================================================================
    // create, update, delete
    // =======================================================================

    @Test
    void create_begins_in_draft_numbers_the_bracket_and_numbers_children_within_it() {
        TaskView root = create(null, "the root");
        TaskView second = create(null, "another root");
        TaskView child = create(root.address().number(), "a child");

        assertThat(root.state()).isEqualTo(TaskState.DRAFT);
        assertThat(root.address()).isEqualTo(ExchangeAddress.bracket(SELECTOR, 1));
        assertThat(second.address()).isEqualTo(ExchangeAddress.bracket(SELECTOR, 2));
        assertThat(child.address()).isEqualTo(ExchangeAddress.child(SELECTOR, 1, 1));
        assertRefused(() -> tasks.create(SCOPE, SELECTOR, null,
                new TaskService.Draft("t", "code", null, null), K, IdempotencyKey.NONE),
            DispatchException.Reason.ACTOR_UNKNOWN);
    }

    @Test
    void a_repeat_under_one_key_creates_nothing_and_the_key_on_another_call_is_refused() {
        IdempotencyKey key = IdempotencyKey.of("once");
        TaskService.Draft draft = new TaskService.Draft("t", "code", MARK + "x", null);
        TaskView first = tasks.create(SCOPE, SELECTOR, null, draft, C, key);
        TaskView repeat = tasks.create(SCOPE, SELECTOR, null, draft, C, key);
        assertThat(repeat.address()).isEqualTo(first.address());
        assertThat(tasks.query(SCOPE, SELECTOR, TaskFilter.none(), 10, C).tasks()).hasSize(1);

        assertRefused(() -> tasks.create(SCOPE, SELECTOR, null,
                new TaskService.Draft("other", "code", null, null), C, key),
            DispatchException.Reason.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void update_changes_a_draft_and_is_refused_empty_and_once_sent() {
        TaskView d = create(null, "first title");
        TaskView changed = tasks.update(SCOPE, d.address(),
            new TaskService.Draft("second title", null, MARK + "rewritten", null),
            d.conflictToken(), C);
        assertThat(changed.title()).isEqualTo("second title");
        assertThat(TaskStage.texts(d.identity())).containsExactly("dispatch=" + MARK + "rewritten");

        assertRefused(() -> tasks.update(SCOPE, d.address(),
                new TaskService.Draft(null, null, null, null), changed.conflictToken(), C),
            DispatchException.Reason.UPDATE_EMPTY);
        assertRefused(() -> tasks.update(SCOPE, d.address(),
                new TaskService.Draft("x", null, null, null), d.conflictToken(), C),
            DispatchException.Reason.CONFLICT_TOKEN_STALE);

        tasks.act(SCOPE, d.address(), TaskVerb.SEND,
            TaskCall.by(C).withConflictToken(changed.conflictToken()));
        String token = tasks.read(SCOPE, d.address(), C).conflictToken();
        assertRefused(() -> tasks.update(SCOPE, d.address(),
                new TaskService.Draft("third", null, null, null), token, C),
            DispatchException.Reason.TRANSITION_NOT_PERMITTED);
    }

    @Test
    void delete_removes_a_draft_outright_and_is_refused_once_sent() {
        TaskView d = tasks.create(SCOPE, SELECTOR, null,
            new TaskService.Draft("gone", "code", MARK + "x", null), C, IdempotencyKey.of("k"));
        ExchangeAddress answer = tasks.delete(SCOPE, d.address(), d.conflictToken(), C);
        assertThat(answer).isEqualTo(d.address());
        assertThat(TaskStage.row(d.identity())).as("no marker is left").isNull();
        assertThat(TaskStage.texts(d.identity())).isEmpty();

        TaskStage.Staged sent = stage.open(SELECTOR);
        assertRefused(() -> tasks.delete(SCOPE, sent.address(), stage.token(sent), C),
            DispatchException.Reason.TRANSITION_NOT_PERMITTED);
    }

    // =======================================================================
    // Criterion 6 -- no text outside read_text, and none for a non-holder
    // =======================================================================

    @Test
    void no_transition_and_no_writing_call_answers_with_a_text() {
        List<Object> answers = new ArrayList<>();
        TaskView d = tasks.create(SCOPE, SELECTOR, null,
            new TaskService.Draft("t", "code", MARK + "commission", null), C, IdempotencyKey.NONE);
        answers.add(d);
        answers.add(tasks.update(SCOPE, d.address(),
            new TaskService.Draft(null, null, MARK + "commission 2", null), d.conflictToken(), C));
        TaskStage.Staged s = new TaskStage.Staged(d.address(), d.identity(), SELECTOR, null);
        answers.add(tasks.act(SCOPE, d.address(), TaskVerb.SEND,
            TaskCall.by(C).withConflictToken(stage.token(s))));
        TaskClaim claim = tasks.claim(SCOPE, d.address(), TaskCall.by(H));
        answers.add(claim);
        answers.add(tasks.act(SCOPE, d.address(), TaskVerb.ASK, TaskCall.by(H)
            .withReceipt(claim.receipt())
            .with(new TaskPayload.Question(MARK + "question", List.of(), true))));
        answers.add(tasks.act(SCOPE, d.address(), TaskVerb.ANSWER, TaskCall.by(C)
            .withConflictToken(stage.token(s)).with(new TaskPayload.Reply(null, MARK + "reply"))));
        answers.add(tasks.act(SCOPE, d.address(), TaskVerb.DELIVER, TaskCall.by(H)
            .withReceipt(claim.receipt()).with(new TaskPayload.Delivery(MARK + "answer", null))));
        answers.add(tasks.annotate(SCOPE, d.address(), TextType.RETURN, MARK + "addendum", H));
        answers.add(tasks.act(SCOPE, d.address(), TaskVerb.REWORK, TaskCall.by(C)
            .withConflictToken(stage.token(s)).with(new TaskPayload.RequiredRemark(MARK + "remark"))));
        answers.add(tasks.read(SCOPE, d.address(), C));
        answers.add(tasks.query(SCOPE, SELECTOR, TaskFilter.none(), 10, C));

        assertThat(answers).allSatisfy(answer -> assertThat(answer.toString())
            .as("an answer that is not read_text carries no text of the task")
            .doesNotContain(MARK));
        assertThat(TaskStage.texts(d.identity()))
            .as("while every one of those texts was written")
            .hasSize(6);
    }

    @Test
    void read_text_gives_the_commissioner_every_part_and_an_executor_only_what_it_holds() {
        TaskStage.Staged s = stage.active(SELECTOR);

        assertThat(tasks.readText(SCOPE, s.address(), TextPart.DISPATCH, H).entries())
            .extracting(TaskTextView.Entry::text).containsExactly("the commission");
        assertThat(tasks.readText(SCOPE, s.address(), TextPart.DISPATCH, C).entries())
            .hasSize(1);
        assertRefused(() -> tasks.readText(SCOPE, s.address(), TextPart.DISPATCH, K),
            DispatchException.Reason.CLAIM_REQUIRED);

        stage.lapse(s);
        assertRefused(() -> tasks.readText(SCOPE, s.address(), TextPart.DISPATCH, H),
            DispatchException.Reason.LEASE_LAPSED);
        assertThat(tasks.readText(SCOPE, s.address(), TextPart.DISPATCH, C).entries())
            .as("the commissioner reads its own work in every state").hasSize(1);
    }

    @Test
    void read_text_answers_one_part_and_counts_the_addenda_of_it() {
        TaskStage.Staged s = stage.open(SELECTOR);
        tasks.annotate(SCOPE, s.address(), TextType.DISPATCH, "a correction", C);
        tasks.annotate(SCOPE, s.address(), TextType.DISPATCH, "another", C);

        TaskTextView dispatch = tasks.readText(SCOPE, s.address(), TextPart.DISPATCH, C);
        assertThat(dispatch.entries()).extracting(TaskTextView.Entry::text)
            .containsExactly("the commission");
        assertThat(dispatch.addenda()).isEqualTo(2);
        TaskTextView addenda = tasks.readText(SCOPE, s.address(), TextPart.ADDENDA, C);
        assertThat(addenda.entries()).extracting(TaskTextView.Entry::addendumSuffix)
            .containsExactly("a", "b");
        assertThat(tasks.read(SCOPE, s.address(), C).texts()).hasSize(3);
    }

    // =======================================================================
    // annotate
    // =======================================================================

    @Test
    void annotate_is_the_writer_of_the_text_and_never_on_a_draft() {
        TaskView d = create(null, "draft");
        assertRefused(() -> tasks.annotate(SCOPE, d.address(), TextType.DISPATCH, "x", C),
            DispatchException.Reason.TRANSITION_NOT_PERMITTED);

        TaskStage.Staged s = stage.stage("closed", SELECTOR);
        assertRefused(() -> tasks.annotate(SCOPE, s.address(), TextType.DISPATCH, "x", K),
            DispatchException.Reason.ACTOR_UNKNOWN);
        assertRefused(() -> tasks.annotate(SCOPE, s.address(), TextType.RETURN, "x", C),
            DispatchException.Reason.NOT_FOUND);
        assertRefused(() -> tasks.annotate(SCOPE, s.address(), TextType.DISPATCH, " ", C),
            DispatchException.Reason.ADDENDUM_TEXT_MISSING);
        assertThat(tasks.annotate(SCOPE, s.address(), TextType.DISPATCH, "after the end", C)
                .texts())
            .as("in every state after draft, closed included")
            .contains(new TaskView.TextHead(TextType.DISPATCH, "a"));
    }

    // =======================================================================
    // query
    // =======================================================================

    @Test
    void query_filters_on_the_effective_state_apparatus_bracket_and_address() {
        TaskStage.Staged open = stage.open(SELECTOR);
        TaskStage.Staged lapsed = stage.lapse(stage.active(SELECTOR));
        TaskStage.Staged active = stage.active(SELECTOR);
        create(null, "a draft");

        assertThat(addresses(Map.of("state", "open")))
            .as("a lapsed lease is listed as open")
            .containsExactly(open.address(), lapsed.address());
        assertThat(addresses(Map.of("state", "active"))).containsExactly(active.address());
        assertThat(addresses(Map.of("apparatus", "co*", "bracket", "2")))
            .containsExactly(lapsed.address());
        assertThat(addresses(Map.of("address", "sprint/1.0,sprint/3.0")))
            .containsExactly(open.address(), active.address());

        assertRefused(() -> TaskFilter.of(Map.of("status", "open")),
            DispatchException.Reason.FILTER_FIELD_UNKNOWN);
        assertRefused(() -> TaskFilter.of(Map.of("state", "needs_input")),
            DispatchException.Reason.FILTER_VALUE_REFUSED);
    }

    @Test
    void query_cuts_at_the_page_bound_and_says_so() {
        create(null, "one");
        create(null, "two");
        create(null, "three");
        TaskListing page = tasks.query(SCOPE, SELECTOR, TaskFilter.none(), 2, C);
        assertThat(page.tasks()).hasSize(2);
        assertThat(page.cut()).isTrue();
        assertThat(tasks.query(SCOPE, SELECTOR, TaskFilter.none(), 3, C).cut()).isFalse();
    }

    // =======================================================================
    // relate, unrelate
    // =======================================================================

    @Test
    void relate_records_a_curation_on_a_closed_task_and_unrelate_removes_it() {
        TaskStage.Staged target = stage.open(SELECTOR);
        TaskStage.Staged open = stage.open(SELECTOR);
        assertRefused(() -> tasks.relate(SCOPE, open.address(), SCOPE, target.address(),
                stage.token(open), C),
            DispatchException.Reason.TRANSITION_NOT_PERMITTED);

        TaskStage.Staged closed = stage.closed(SELECTOR);
        assertRefused(() -> tasks.relate(SCOPE, closed.address(), SCOPE, closed.address(),
                stage.token(closed), C),
            DispatchException.Reason.CURATION_TARGET_SELF);
        assertRefused(() -> tasks.relate(SCOPE, closed.address(), SCOPE,
                ExchangeAddress.bracket(SELECTOR, 99), stage.token(closed), C),
            DispatchException.Reason.NOT_FOUND);

        tasks.relate(SCOPE, closed.address(), SCOPE, target.address(), stage.token(closed), C);
        assertThat(TaskStage.row(closed.identity()).curatedIn())
            .as("stored by the target's identity").isEqualTo(TaskStage.row(target.identity()).id());

        TaskView elsewhere = tasks.create(OTHER_SCOPE, SELECTOR, null,
            new TaskService.Draft("in another scope", "code", null, null), C,
            IdempotencyKey.NONE);
        TaskView across = tasks.relate(SCOPE, closed.address(), OTHER_SCOPE, elsewhere.address(),
            stage.token(closed), C);
        assertThat(across.curatedIn())
            .as("a target in any scope of the tenant is stored; a scope the caller cannot see "
                + "in the directory is not named")
            .isNull();
        assertThat(TaskStage.row(closed.identity()).curatedIn())
            .isEqualTo(TaskStage.row(elsewhere.identity()).id());
        assertThat(TaskStage.row(closed.identity()).state()).isEqualTo("closed");

        TaskView unrelated = tasks.unrelate(SCOPE, closed.address(), stage.token(closed), C);
        assertThat(unrelated.curatedIn()).isNull();
        assertThat(TaskStage.row(closed.identity()).curatedIn()).isNull();
        assertThat(unrelated.state()).isEqualTo(TaskState.CLOSED);
    }

    // -----------------------------------------------------------------------

    private TaskView create(Integer parent, String title) {
        return tasks.create(SCOPE, SELECTOR, parent,
            new TaskService.Draft(title, "code", MARK + title, null), C, IdempotencyKey.NONE);
    }

    private List<ExchangeAddress> addresses(Map<String, String> filter) {
        return tasks.query(SCOPE, SELECTOR, TaskFilter.of(filter), 50, C).tasks().stream()
            .map(TaskView::address).toList();
    }
}
