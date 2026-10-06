package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.repository.ScopeAccessRepository;
import ai.kumbuka.dispatch.repository.TaskRepository;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The kernel of the task lifecycle (TAR-0004, concept section 2): the
 * sixteen transitions and the nine calls that are not transitions.
 *
 * <p>Every transition is one transaction: lock the row, compute the
 * {@link TaskSituation}, call {@link Decision#of}, set the attributes the
 * {@link TaskVerb} row names, insert its text row, stamp {@code
 * state_changed_at} and {@code state_changed_by}. Nothing here decides
 * whether a transition is permitted; the row and the decision do.
 *
 * <p>Stands beside {@link ExchangeService} and serves no caller yet: neither
 * {@code surface} nor {@code adapter} reaches it, which a test holds. The verb
 * surface binds it in step 4 of REA-0009.
 *
 * <p>No answer of this class carries a text of a task except {@link
 * #readText}, which carries exactly one part.
 */
@ApplicationScoped
@TenantBound
public class TaskService {

    /** Says verb, address, state and reason; never a text, metadata or who called. */
    private static final Logger LOG = Logger.getLogger(TaskService.class);

    /** The call names an idempotency key is spent on. */
    static final String CREATE = "dispatch_create";
    static final String ANNOTATE = "dispatch_annotate";

    /** The last letter an addendum suffix may take; past it is refused, never wrapped. */
    private static final char LAST_SUFFIX = 'z';

    @Inject TaskRepository tasks;
    @Inject SelectorRegistry selectors;
    @Inject ScopeAccessRepository scopes;

    private final Clock clock;

    TaskService() {
        this(Clock.systemUTC());
    }

    TaskService(Clock clock) {
        this.clock = clock;
    }

    /**
     * What a draft carries: on {@link #create} title and apparatus are
     * mandatory; on {@link #update} a null leaves the stored value.
     */
    public record Draft(String title, String apparatus, String text, Map<String, Object> metadata) {

        boolean isEmpty() {
            return title == null && apparatus == null && text == null && metadata == null;
        }
    }

    // ======================================================================
    // The calls that are not transitions
    // ======================================================================

    /**
     * Creates a task in {@code draft}: a bracket root, or a child under the
     * root numbered {@code parentNumber}. The caller becomes its commissioner.
     *
     * <p>Under an idempotency key a repeat by the same caller in the same scope
     * with the same arguments creates nothing and answers the first task as it
     * stands; the same key on other arguments is refused, as {@code
     * commission} handles it today.
     */
    @Transactional
    public TaskView create(UUID scopeId, String selector, Integer parentNumber, Draft draft,
                           Actor caller, IdempotencyKey key) {
        requireCommissioner(caller, "create");
        Objects.requireNonNull(draft.title(), "title");
        Objects.requireNonNull(draft.apparatus(), "apparatus");
        Metadata.validate(draft.metadata());

        String digest = IdempotencyService.digestOf(Arrays.asList(selector,
            parentNumber == null ? null : String.valueOf(parentNumber), draft.title(),
            draft.apparatus(), draft.text(), String.valueOf(draft.metadata())));
        Optional<Task> repeat = firstAnswerFor(scopeId, caller, key, CREATE, digest)
            .flatMap(id -> tasks.findById(scopeId, id));
        if (repeat.isPresent()) {
            return view(repeat.get(), caller);
        }

        Selector declared = selectors.requireDeclared(scopeId, selector);
        Task task = new Task();
        task.scopeId = scopeId;
        task.selector = declared;
        if (parentNumber == null) {
            task.number = allocateNumber(declared);
            task.sub = 0;
        } else {
            requireOpenBracket(scopeId, declared, parentNumber);
            task.number = parentNumber;
            Integer highest = tasks.highestSub(scopeId, declared.id, parentNumber);
            task.sub = highest == null ? 1 : highest + 1;
        }
        task.title = draft.title();
        task.apparatus = draft.apparatus();
        task.dispatchMetadata = draft.metadata();
        task.begin(now(), caller.subject());
        tasks.insert(task);
        if (draft.text() != null) {
            insertText(task, TextType.DISPATCH, null, draft.text(), caller);
        }
        remember(scopeId, caller, key, CREATE, digest, task);

        LOG.infof("create %s -> %s", task.address(), TaskState.DRAFT.wireName());
        return view(task, caller);
    }

    /** Changes a draft's title, apparatus, text or metadata. Refused once sent. */
    @Transactional
    public TaskView update(UUID scopeId, ExchangeAddress address, Draft changes,
                           String conflictToken, Actor caller) {
        Task task = lockOrRefuse(scopeId, address);
        TaskSituation s = situation(task);
        requireDraft(s, "update");
        requireCommissioner(caller, "update");
        requireToken(s, conflictToken);
        if (changes.isEmpty()) {
            throw new DispatchException(DispatchException.Reason.UPDATE_EMPTY,
                "update on a draft takes at least one of title, apparatus, text or metadata; "
                    + "an empty write would rotate the conflict token and change nothing.");
        }
        Metadata.validate(changes.metadata());

        if (changes.title() != null) {
            task.title = changes.title();
        }
        if (changes.apparatus() != null) {
            task.apparatus = changes.apparatus();
        }
        if (changes.metadata() != null) {
            task.dispatchMetadata = changes.metadata();
        }
        if (changes.text() != null) {
            writeDispatchText(task, changes.text(), caller);
        }
        task.touch(caller.subject());
        tasks.flush();
        LOG.infof("update %s", task.address());
        return view(task, caller);
    }

    /**
     * Deletes a draft outright: it leaves no marker (DEC-0015).
     *
     * @return the address the draft had, which is the whole answer
     */
    @Transactional
    public ExchangeAddress delete(UUID scopeId, ExchangeAddress address, String conflictToken,
                                  Actor caller) {
        Task task = lockOrRefuse(scopeId, address);
        TaskSituation s = situation(task);
        requireDraft(s, "delete");
        requireCommissioner(caller, "delete");
        requireToken(s, conflictToken);
        tasks.delete(task);
        LOG.infof("delete %s", address);
        return address;
    }

    /** The head of one task: process, never text. Writes nothing. */
    @Transactional
    public TaskView read(UUID scopeId, ExchangeAddress address, Actor caller) {
        return view(findOrRefuse(scopeId, address), caller);
    }

    /**
     * One part of a task's text.
     *
     * <p>The commissioner reads every part in every state; an executor reads
     * only a task it holds (TAR-0004 section 6). Writes nothing.
     */
    @Transactional
    public TaskTextView readText(UUID scopeId, ExchangeAddress address, TextPart part,
                                 Actor caller) {
        Task task = findOrRefuse(scopeId, address);
        TaskSituation s = situation(task);
        if (!caller.isConsole() && !s.heldBy(caller)) {
            throw new DispatchException(s.formerHolder(caller)
                ? DispatchException.Reason.LEASE_LAPSED
                : DispatchException.Reason.CLAIM_REQUIRED,
                "an executor reads the text of a task only while it holds it, and "
                    + address + " is not held by the caller.");
        }
        return TaskTexts.part(part, tasks.texts(task.id));
    }

    /**
     * The heads of a selector's tasks, narrowed by the declared filter, in
     * address order, up to {@code limit}. Writes nothing.
     */
    @Transactional
    public TaskListing query(UUID scopeId, String selector, TaskFilter filter, int limit,
                             Actor caller) {
        if (limit < 1) {
            throw new DispatchException(DispatchException.Reason.FILTER_VALUE_REFUSED,
                "the page bound is at least 1, was " + limit + ".", List.of("limit"));
        }
        selectors.requireDeclared(scopeId, selector);
        Instant now = now();

        List<Task> matching = tasks.listing(scopeId, selector, filter.apparatusPatterns(),
                filter.brackets()).stream()
            .filter(t -> filter.admits(TaskSituation.of(t, now, List.of()).state(), t.address()))
            .toList();
        List<Task> page = matching.stream().limit(limit).toList();
        List<TaskText> texts = tasks.texts(page.stream().map(t -> t.id).toList());

        List<TaskView> heads = page.stream()
            .map(t -> TaskView.of(t, TaskSituation.of(t, now, List.of()), caller,
                texts.stream().filter(x -> x.taskId.equals(t.id)).toList(), curatedIn(t)))
            .toList();
        return new TaskListing(heads, matching.size() > page.size());
    }

    /**
     * Adds an addendum to a text of a sent task.
     *
     * <p>Only the identity that wrote the supplemented text adds to it; the
     * addendum hangs on the youngest text of that type, takes the next free
     * letter of that type, and has no title.
     *
     * <p>Under an idempotency key a repeat attaches nothing and answers the
     * task: an addendum cannot be removed, so a duplicate would be permanent.
     */
    @Transactional
    public TaskView annotate(UUID scopeId, ExchangeAddress address, TextType part, String text,
                             Actor caller, IdempotencyKey key) {
        if (text == null || text.isBlank()) {
            throw new DispatchException(DispatchException.Reason.ADDENDUM_TEXT_MISSING,
                "an addendum carries its text, and it arrives with this call or never.");
        }
        Task task = lockOrRefuse(scopeId, address);
        String digest = IdempotencyService.digestOf(
            Arrays.asList(address.toString(), part.wireName(), text));
        if (firstAnswerFor(scopeId, caller, key, ANNOTATE, digest).isPresent()) {
            return view(task, caller);
        }
        TaskSituation s = situation(task);
        if (s.state() == TaskState.DRAFT) {
            throw new DispatchException(DispatchException.Reason.TRANSITION_NOT_PERMITTED,
                address + " is a draft and is edited, not annotated.");
        }
        TaskText supplemented = TaskTexts.youngestBase(tasks.texts(task.id), part)
            .orElseThrow(() -> new DispatchException(DispatchException.Reason.NOT_FOUND,
                address + " has no " + part.wireName() + " text to supplement."));
        if (!caller.subject().equals(supplemented.createdBy)) {
            throw new DispatchException(DispatchException.Reason.ACTOR_UNKNOWN,
                "an addendum is added by the identity that wrote the text it supplements.");
        }
        insertText(task, part, nextSuffix(task, part, address), text, caller);
        remember(scopeId, caller, key, ANNOTATE, digest, task);
        LOG.infof("annotate %s %s", address, part.wireName());
        return view(task, caller);
    }

    /** Records the object a closed task was curated into: any task of the tenant. */
    @Transactional
    public TaskView relate(UUID scopeId, ExchangeAddress address, UUID targetScopeId,
                           ExchangeAddress target, String conflictToken, Actor caller) {
        Task task = requireClosedForRelation(scopeId, address, conflictToken, caller);
        Task into = tasks.find(targetScopeId, target).orElseThrow(() -> new DispatchException(
            DispatchException.Reason.NOT_FOUND, "no task at " + target));
        if (into.id.equals(task.id)) {
            throw new DispatchException(DispatchException.Reason.CURATION_TARGET_SELF,
                "a task cannot be curated into itself");
        }
        task.curateIn(into.id);
        task.touch(caller.subject());
        tasks.flush();
        LOG.infof("relate %s", address);
        return view(task, caller);
    }

    /** Removes the relation {@code curated_in} from a closed task. */
    @Transactional
    public TaskView unrelate(UUID scopeId, ExchangeAddress address, String conflictToken,
                             Actor caller) {
        Task task = requireClosedForRelation(scopeId, address, conflictToken, caller);
        task.curateIn(null);
        task.touch(caller.subject());
        tasks.flush();
        LOG.infof("unrelate %s", address);
        return view(task, caller);
    }

    // ======================================================================
    // The transitions
    // ======================================================================

    /**
     * Applies one transition other than a take-up.
     *
     * <p>On a bracket root, a closing verb confirmed by the caller withdraws
     * the unfinished children first and then closes the root, in this
     * transaction.
     */
    @Transactional
    public TaskView act(UUID scopeId, ExchangeAddress address, TaskVerb verb, TaskCall call) {
        if (verb.holding() == TaskVerb.Holding.TAKE) {
            throw new IllegalArgumentException(verb.wireName() + " hands out a receipt; call "
                + "claim or claimNext");
        }
        Task task = lockOrRefuse(scopeId, address);
        Instant now = now();
        List<Task> children = task.isBracketRoot() && verb.closes()
            ? tasks.children(scopeId, task.selector.id, task.number)
            : List.of();
        TaskSituation s = TaskSituation.of(task, now, children);
        if (Decision.of(verb, s, call) instanceof Decision.AlreadyThere) {
            LOG.debugf("%s on %s: already %s", verb.wireName(), address, verb.outcome().wireName());
            return view(task, call.caller());
        }
        TaskInput payload = decide(verb, s, call);

        String by = call.caller().subject();
        if (!s.unfinishedChildren().isEmpty()) {
            withdrawChildren(children, now, by);
        }
        apply(task, verb, s, payload, now, by);
        tasks.flush();
        writeText(task, verb, payload, call.caller());

        LOG.infof("%s %s -> %s", verb.wireName(), address, verb.target().wireName());
        return view(task, call.caller());
    }

    /** Takes up the task at an address and mints its receipt. */
    @Transactional
    public TaskClaim claim(UUID scopeId, ExchangeAddress address, TaskCall call) {
        Task task = lockOrRefuse(scopeId, address);
        return take(task, TaskVerb.CLAIM, call);
    }

    /**
     * Takes up the next drawable task of a selector whose apparatus matches
     * one of the patterns, and mints its receipt.
     *
     * <p>The draw locks with {@code SKIP LOCKED}, so two concurrent draws never
     * take the same task; the drawn row is then decided like any other.
     *
     * @throws DispatchException {@code NOTHING_TO_CLAIM} when nothing is drawable
     */
    @Transactional
    public TaskClaim claimNext(UUID scopeId, String selector, List<String> apparatusPatterns,
                               TaskCall call) {
        selectors.requireDeclared(scopeId, selector);
        Task task = tasks.lockNextDrawable(scopeId, selector, apparatusPatterns, now(),
                TaskSituation.LAPSES_TO_PARK - 1)
            .orElseThrow(() -> new DispatchException(DispatchException.Reason.NOTHING_TO_CLAIM,
                "nothing in '" + selector + "' addressed to " + apparatusPatterns
                    + " is drawable: every task there those patterns match is a draft, "
                    + "held, paused, delivered, closed or deferred. The selector exists; "
                    + "this is an empty draw, not a missing address."));
        return take(task, TaskVerb.CLAIM_NEXT, call);
    }

    private TaskClaim take(Task task, TaskVerb verb, TaskCall call) {
        Instant now = now();
        TaskSituation s = TaskSituation.of(task, now, List.of());
        TaskInput.Lease lease = (TaskInput.Lease) decide(verb, s, call);

        String by = call.caller().subject();
        if (s.lapsed()) {
            task.recordLapse();
        }
        task.enter(TaskState.ACTIVE, null, null, now, by);
        String receipt = Receipt.mint();
        task.award(by, receipt, now.plus(lease.duration()));
        task.touch(by);
        tasks.flush();

        LOG.infof("%s %s -> %s", verb.wireName(), task.address(), TaskState.ACTIVE.wireName());
        return new TaskClaim(view(task, call.caller()), receipt);
    }

    /** Decides, and refuses with the decision's reason; answers the payload to apply. */
    private static TaskInput decide(TaskVerb verb, TaskSituation s, TaskCall call) {
        Decision decision = Decision.of(verb, s, call);
        if (decision instanceof Decision.AlreadyThere) {
            throw new IllegalStateException(verb.wireName() + " never closes a task");
        }
        if (decision instanceof Decision.Refused refused) {
            LOG.debugf("%s on %s refused at %s: %s", verb.wireName(), s.address(),
                refused.check(), refused.reason());
            throw refused.asException();
        }
        return verb.payloadOf(call);
    }

    /** Sets what the row names: state, attributes, holder, lease, payload attributes. */
    private static void apply(Task task, TaskVerb verb, TaskSituation s, TaskInput payload,
                              Instant now, String by) {
        if (payload instanceof TaskInput.Sending sending && sending.metadata() != null) {
            task.dispatchMetadata = sending.metadata();
        }
        if (verb.target() != s.state()) {
            HoldReason reason = payload instanceof TaskInput.Pause pause
                ? pause.reason()
                : verb.holdReason();
            task.enter(verb.target(), reason, verb.outcome(), now, by);
        }
        switch (verb.holding()) {
            case NONE, TAKE -> {
                // TAKE is applied by take(), which mints the receipt.
            }
            case LEASE -> task.lease(now.plus(((TaskInput.Lease) payload).duration()));
            case PAUSE -> task.pause();
            case RESTART -> task.lease(now.plus(TaskInput.DEFAULT_LEASE));
            case DROP -> task.dropHolder();
        }
        switch (payload) {
            case TaskInput.Deferral deferral -> task.deferUntil(deferral.notBefore());
            case TaskInput.Question question -> task.ask(question.asOptions());
            case TaskInput.Delivery delivery -> task.writeReturnMetadata(delivery.metadata());
            default -> {
                // The other payloads set no attribute of the task.
            }
        }
        task.touch(by);
    }

    /** Inserts the text row the verb names, where the payload carries its text. */
    private void writeText(Task task, TaskVerb verb, TaskInput payload, Actor caller) {
        if (verb.text() == null) {
            return;
        }
        String text = switch (payload) {
            case TaskInput.Remark remark -> remark.text();
            case TaskInput.RequiredRemark remark -> remark.text();
            case TaskInput.Deferral deferral -> deferral.remark();
            case TaskInput.Question question -> question.text();
            case TaskInput.Reply reply -> reply.recorded();
            case TaskInput.Pause pause -> pause.remark();
            case TaskInput.Delivery delivery -> delivery.text();
            case TaskInput.Nothing ignored -> null;
            case TaskInput.Sending ignored -> null;
            case TaskInput.Lease ignored -> null;
        };
        if (text != null && !text.isBlank()) {
            insertText(task, verb.text(), null, text, caller);
        }
    }

    /** Closes each unfinished child as withdrawn, before its root closes. */
    private void withdrawChildren(List<Task> children, Instant now, String by) {
        for (Task child : children) {
            if (TaskSituation.of(child, now, List.of()).state() != TaskState.CLOSED) {
                child.enter(TaskState.CLOSED, null, Outcome.WITHDRAWN, now, by);
                child.dropHolder();
                child.touch(by);
                LOG.infof("withdraw %s -> %s", child.address(), TaskState.CLOSED.wireName());
            }
        }
        tasks.flush();
    }

    // ======================================================================
    // Machinery
    // ======================================================================

    private TaskView view(Task task, Actor caller) {
        TaskSituation s = situation(task);
        return TaskView.of(task, s, caller, tasks.texts(task.id), curatedIn(task));
    }

    /**
     * The situation of a task now, without its children.
     *
     * <p>Only check 6 reads the children, and only {@link #act} runs it on a
     * closing verb, with the children it read there. A head's {@code next}
     * comes from checks 1 to 4 and does not need them.
     */
    private TaskSituation situation(Task task) {
        return TaskSituation.of(task, now(), List.of());
    }

    /**
     * The complete address of the curation target, where the caller may see
     * its scope; the target's scope answers for the bound subject.
     */
    private String curatedIn(Task task) {
        if (task.curatedInId() == null) {
            return null;
        }
        return tasks.findAnywhere(task.curatedInId())
            .flatMap(target -> scopes.slugOf(target.scopeId)
                .map(slug -> target.address().complete(slug)))
            .orElse(null);
    }

    private Task requireClosedForRelation(UUID scopeId, ExchangeAddress address,
                                          String conflictToken, Actor caller) {
        Task task = lockOrRefuse(scopeId, address);
        TaskSituation s = situation(task);
        if (s.state() != TaskState.CLOSED) {
            throw new DispatchException(DispatchException.Reason.TRANSITION_NOT_PERMITTED,
                address + " is " + s.state().wireName() + "; a relation is recorded only on "
                    + "a closed task.");
        }
        requireCommissioner(caller, "a relation");
        requireToken(s, conflictToken);
        return task;
    }

    private static void requireDraft(TaskSituation s, String call) {
        if (s.state() != TaskState.DRAFT) {
            throw new DispatchException(DispatchException.Reason.TRANSITION_NOT_PERMITTED,
                s.address() + " is " + s.state().wireName() + "; " + call + " applies only "
                    + "to a draft. A sent task is corrected by an addendum.");
        }
    }

    private static void requireCommissioner(Actor caller, String call) {
        if (!caller.isConsole()) {
            throw new DispatchException(DispatchException.Reason.ACTOR_UNKNOWN,
                call + " is the commissioner's, and the caller does not hold the "
                    + "commissioning capacity.");
        }
    }

    private static void requireToken(TaskSituation s, String presented) {
        Checks.conflictToken(s, presented).ifPresent(refused -> {
            throw refused.asException();
        });
    }

    private void requireOpenBracket(UUID scopeId, Selector selector, int number) {
        Task root = tasks.find(scopeId, ExchangeAddress.bracket(selector.name, number))
            .orElseThrow(() -> new DispatchException(DispatchException.Reason.NOT_FOUND,
                "no bracket " + selector.name + "/" + number + "; a bracket opens with its "
                    + "root."));
        if (TaskSituation.of(root, now(), List.of()).state() == TaskState.CLOSED) {
            throw new DispatchException(DispatchException.Reason.TRANSITION_NOT_PERMITTED,
                root.address() + " is closed, and a closed root has only closed children.");
        }
    }

    /** Takes the next bracket number under the selector's row lock, in this transaction. */
    private int allocateNumber(Selector selector) {
        Selector locked = tasks.lockSelector(selector.id).orElseThrow(() ->
            new DispatchException(DispatchException.Reason.SELECTOR_NOT_DECLARED,
                "selector '" + selector.name + "' is not declared in this scope."));
        int allocated = locked.nextNumber;
        locked.nextNumber = allocated + 1;
        return allocated;
    }

    private String nextSuffix(Task task, TextType type, ExchangeAddress address) {
        String highest = tasks.highestSuffix(task.id, type);
        if (highest == null) {
            return "a";
        }
        char next = (char) (highest.charAt(0) + 1);
        if (next > LAST_SUFFIX) {
            throw new DispatchException(DispatchException.Reason.ADDENDUM_SUFFIX_EXHAUSTED,
                address + " already carries addenda through 'z' on its " + type.wireName()
                    + " text; a wrapped suffix would reissue one that exists.");
        }
        return String.valueOf(next);
    }

    private void writeDispatchText(Task task, String text, Actor caller) {
        Optional<TaskText> base = TaskTexts.youngestBase(tasks.texts(task.id), TextType.DISPATCH);
        if (base.isPresent()) {
            base.get().text = text;
        } else {
            insertText(task, TextType.DISPATCH, null, text, caller);
        }
    }

    private void insertText(Task task, TextType type, String suffix, String text, Actor caller) {
        TaskText row = new TaskText();
        row.scopeId = task.scopeId;
        row.taskId = task.id;
        row.type(type);
        row.addendumSuffix = suffix;
        row.text = text;
        row.createdBy = caller.subject();
        tasks.insert(row);
    }

    private Task lockOrRefuse(UUID scopeId, ExchangeAddress address) {
        return tasks.lock(scopeId, address).orElseThrow(() -> notFound(address));
    }

    private Task findOrRefuse(UUID scopeId, ExchangeAddress address) {
        return tasks.find(scopeId, address).orElseThrow(() -> notFound(address));
    }

    private static DispatchException notFound(ExchangeAddress address) {
        return new DispatchException(DispatchException.Reason.NOT_FOUND, "no task at " + address);
    }

    private Instant now() {
        return Instant.now(clock);
    }

    // ----------------------------------------------------------------------
    // Idempotency of create, on task_idempotency_key
    // ----------------------------------------------------------------------

    /**
     * The task a repeat of a call answers with, if it is one; refuses the key
     * spent on another call. The rule of {@link IdempotencyService}, on the
     * task's own ledger.
     */
    private Optional<Long> firstAnswerFor(UUID scopeId, Actor caller, IdempotencyKey key,
                                          String call, String digest) {
        if (!(key instanceof IdempotencyKey.Given given)) {
            return Optional.empty();
        }
        Optional<SpentTaskKey> held = tasks.lockKey(scopeId, caller.subject(), given.value());
        if (held.isEmpty() || !held.get().stillStandsAt(now())) {
            return Optional.empty();
        }
        if (!held.get().records(call, digest)) {
            throw new DispatchException(DispatchException.Reason.IDEMPOTENCY_KEY_REUSED,
                "the key was already spent on a different call in this scope");
        }
        return Optional.of(held.get().taskId);
    }

    private void remember(UUID scopeId, Actor caller, IdempotencyKey key, String call,
                          String digest, Task produced) {
        if (!(key instanceof IdempotencyKey.Given given)) {
            return;
        }
        Optional<SpentTaskKey> held = tasks.lockKey(scopeId, caller.subject(), given.value());
        SpentTaskKey row = held.orElseGet(SpentTaskKey::new);
        row.scopeId = scopeId;
        row.callerSubject = caller.subject();
        row.key = given.value();
        row.callName = call;
        row.argumentDigest = digest;
        row.taskId = produced.id;
        row.firstSeenAt = now();
        if (held.isEmpty()) {
            tasks.insert(row);
        }
    }
}
