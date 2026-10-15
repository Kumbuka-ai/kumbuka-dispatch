/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every message a refusal can carry, and the remedy of every reason.
 *
 * <p>This is the half of the declaration that makes "a refusal explains
 * itself" checkable rather than hoped for. A refusal is worded from exactly
 * one declared {@link Message}; the place that raises it chooses which and
 * supplies the placeholders, and nothing else. A message is therefore the
 * same sentence wherever it is raised, and a sentence a raising place makes
 * up cannot reach a caller.
 *
 * <p><strong>The start-up guard reads this.</strong> Every {@link RefusalCode}
 * must have a remedy and at least one message; {@link #requireComplete(Map)}
 * is called at start-up and throws if a code lacks either. That is the
 * mechanism behind "a reason not in the table cannot be returned": not a rule
 * somebody keeps, but a service that does not come up.
 *
 * <h2>Only the platform's vocabulary (DEC-0043 R18, R19)</h2>
 *
 * A message repeats neither the caller's input nor the tenant's vocabulary.
 * Its placeholders take the name of a call ({@link VerbName}), the path or
 * name of a declared argument ({@link ArgumentPath}, {@link ArgumentName}), a
 * declared bound ({@link Bound}) and a declared {@link Form}, and lists of
 * these; none of them can be built from a request. The address, the scope,
 * the state, the conflict token and the instant of a deferral are not in any
 * message: a refusal points at the member of {@code data} or the read that
 * shows them.
 */
public final class ReasonCatalogue {

    private ReasonCatalogue() {
    }

    /**
     * One declared message: the reason it is raised under, and its pattern.
     *
     * <p>The placeholders a pattern may carry are {@code {call}}, {@code
     * {calls}}, {@code {read}}, {@code {query}}, {@code {path}}, {@code
     * {accepts}}, {@code {differs}}, {@code {max}} and {@code {form}}.
     */
    public enum Message {

        NOT_FOUND(RefusalCode.NOT_FOUND, ReasonCatalogue.NOT_FOUND_MESSAGE),

        STATE(RefusalCode.STATE_DOES_NOT_ALLOW,
            "{call} is not possible in the task's present state, which data.state shows. You "
                + "can make: {calls}."),
        STATE_NOTHING_OPEN(RefusalCode.STATE_DOES_NOT_ALLOW,
            "{call} is not possible in the task's present state, which data.state shows, and "
                + "no call is open to you on it now; data.waiting_for says what it waits for."),
        STATE_CLOSED(RefusalCode.STATE_DOES_NOT_ALLOW,
            "{call} is not possible on this task: it is closed and finished. Nothing further "
                + "can be done with it."),

        ROLE_COMMISSIONER(RefusalCode.ROLE_DOES_NOT_ALLOW,
            "{call} is the commissioner's call, and you take part in this task in another "
                + "part."),
        ROLE_EXECUTOR(RefusalCode.ROLE_DOES_NOT_ALLOW,
            "{call} is an executor's call, and you take part here in another part."),
        ROLE_HOLDER(RefusalCode.ROLE_DOES_NOT_ALLOW,
            "{call} is the holder's call, and you take part in this task in another part."),
        ROLE_AUTHOR(RefusalCode.ROLE_DOES_NOT_ALLOW,
            "{call} is the call of the identity that wrote the text it supplements."),
        ROLE_OTHER_THAN_THE_DELIVERER(RefusalCode.ROLE_DOES_NOT_ALLOW,
            "{call} is the commissioner's call, made by an identity other than the one that "
                + "delivered the answer."),
        ROLE_CAPACITY(RefusalCode.ROLE_DOES_NOT_ALLOW,
            "{call} needs a caller holding the commissioning or the executing capacity, and "
                + "exactly one of them."),

        HOLDER_OTHER(RefusalCode.NOT_THE_HOLDER,
            "{call} is the holder's call, and another executor holds this task."),
        HOLDER_NOBODY(RefusalCode.NOT_THE_HOLDER,
            "{call} is the holder's call, and nobody holds this task."),
        HOLDER_LAPSED(RefusalCode.NOT_THE_HOLDER,
            "{call} is the holder's call, and your lease on this task ended."),
        TEXT_HOLDER_OTHER(RefusalCode.NOT_THE_HOLDER,
            "{call} reads the text of a task its executor holds, and another executor holds "
                + "this one."),
        TEXT_HOLDER_NOBODY(RefusalCode.NOT_THE_HOLDER,
            "{call} reads the text of a task its executor holds, and nobody holds this one."),
        TEXT_HOLDER_LAPSED(RefusalCode.NOT_THE_HOLDER,
            "{call} reads the text of a task its executor holds, and your lease on this one "
                + "ended."),

        RECEIPT_WRONG(RefusalCode.RECEIPT_WRONG,
            "{call} needs the receipt your claim handed out for this task: the receipt given "
                + "is a different one."),

        CHILDREN_NOT_FINISHED(RefusalCode.CHILDREN_NOT_FINISHED,
            "{call} would close a bracket root while tasks of its bracket are unfinished; "
                + "data.children lists them. Repeat the call with data.confirmation to withdraw "
                + "them and close the root."),
        CHILDREN_CONFIRMATION_STALE(RefusalCode.CHILDREN_NOT_FINISHED,
            "{call} carries a confirmation that no longer holds: the unfinished tasks of the "
                + "bracket changed since it was handed out, and data.children lists them now. "
                + "Repeat the call with the one in data.confirmation."),

        DEFERRAL_PENDING(RefusalCode.DEFERRAL_PENDING,
            "{call} is not possible yet: the task is deferred, and {read} shows until when, "
                + "in fields.not_before."),

        NOTHING_TO_TAKE(RefusalCode.NOTHING_TO_TAKE,
            "Nothing that your patterns match can be taken up in this bracket kind right now: "
                + "every such task is a draft, held, paused, delivered, closed or deferred."),

        CLAIM_DURATION_INVALID(RefusalCode.CLAIM_DURATION_INVALID,
            "duration is not valid for {call}: it is a positive ISO-8601 duration such as "
                + "PT2H."),

        PAGE_SIZE_REJECTED(RefusalCode.PAGE_SIZE_REJECTED,
            "page_size is not valid for {call}: it is a whole number from 1 to {max}."),

        CURSOR_MALFORMED(RefusalCode.CURSOR_MALFORMED,
            "after is not a cursor {query} handed out: it is the next_cursor of the previous "
                + "page."),

        CURSOR_FOREIGN(RefusalCode.CURSOR_FOREIGN,
            "{call} carries a cursor of another listing: it was handed out for other values of "
                + "{differs}. A cursor continues only the listing that handed it out."),

        SORT_UNDECLARED(RefusalCode.SORT_UNDECLARED,
            "sort is not valid for {call}: it takes id or created_at."),

        ARGUMENT_UNKNOWN(RefusalCode.ARGUMENT_UNKNOWN,
            "{call} takes these arguments: {accepts}."),
        ARGUMENT_UNKNOWN_UNDER_FIELDS(RefusalCode.ARGUMENT_UNKNOWN,
            "{call} takes these arguments under fields: {accepts}."),
        CALL_UNKNOWN(RefusalCode.ARGUMENT_UNKNOWN,
            "This service takes these calls: {accepts}."),

        ARGUMENT_MISSING(RefusalCode.ARGUMENT_MISSING, "{call} needs {path}."),
        ARGUMENT_MISSING_OPTIONS(RefusalCode.ARGUMENT_MISSING,
            "{call} needs fields.options, or fields.free_text set to true."),
        ARGUMENT_MISSING_ANSWER(RefusalCode.ARGUMENT_MISSING,
            "{call} needs fields.option or fields.text."),
        ARGUMENT_MISSING_CHANGE(RefusalCode.ARGUMENT_MISSING,
            "{call} needs at least one of fields.title, fields.apparatus, fields.text and "
                + "fields.metadata."),

        ARGUMENT_INVALID(RefusalCode.ARGUMENT_INVALID, "{path} is not valid for {call}: {form}."),
        ARGUMENT_INVALID_IN_REQUEST(RefusalCode.ARGUMENT_INVALID,
            "{path} is not valid for this request: {form}."),
        VALUE_INVALID(RefusalCode.ARGUMENT_INVALID,
            "A value given is not valid for {call}: {form}."),

        CONFLICT_TOKEN_STALE(RefusalCode.CONFLICT_TOKEN_STALE,
            "{call} carries a conflict token that is not the one the task holds: it was "
                + "changed since you read it. Its current token is in data.conflict_token."),

        SELECTOR_UNKNOWN(RefusalCode.SELECTOR_UNKNOWN,
            "selector names a bracket kind this scope does not declare."),

        SCOPE_KIND_UNSUPPORTED(RefusalCode.SCOPE_KIND_UNSUPPORTED,
            "The scope named is of a kind this service carries no tasks in; it carries tasks "
                + "in project and global scopes."),

        SCOPE_READ_ONLY(RefusalCode.SCOPE_READ_ONLY,
            "{call} writes, and you may read this scope without writing to it. Reading it is "
                + "unaffected; the write right is granted with the membership."),

        SCOPE_LOCKED(RefusalCode.SCOPE_LOCKED,
            "This scope is locked, so it refuses every write whatever your role. Reading it is "
                + "unaffected. The lock is lifted where it was set."),

        IDEMPOTENCY_KEY_REUSED(RefusalCode.IDEMPOTENCY_KEY_REUSED,
            "The idempotency key given was spent on a different call, or with different "
                + "arguments, in this scope within the last 24 hours."),

        ROUTE_CREATE(RefusalCode.CALL_NOT_AT_THIS_ADDRESS,
            "{call} is a call on a bracket kind: POST /api/<scope>/<selector>."),
        ROUTE_QUERY(RefusalCode.CALL_NOT_AT_THIS_ADDRESS,
            "{call} is a call on a bracket kind: GET /api/<scope>/<selector>."),
        ROUTE_ON_A_BRACKET_KIND(RefusalCode.CALL_NOT_AT_THIS_ADDRESS,
            "{call} is a call on a bracket kind: POST /api/<scope>/<selector>:{call}."),
        ROUTE_READ(RefusalCode.CALL_NOT_AT_THIS_ADDRESS,
            "{call} is a call on a task: GET /api/<scope>/<selector>/<number>.<sub>."),
        ROUTE_READ_CALL(RefusalCode.CALL_NOT_AT_THIS_ADDRESS,
            "{call} is a call on a task: GET /api/<scope>/<selector>/<number>.<sub>:{call}."),
        ROUTE_UPDATE(RefusalCode.CALL_NOT_AT_THIS_ADDRESS,
            "{call} is a call on a task: PATCH /api/<scope>/<selector>/<number>.<sub>."),
        ROUTE_DELETE(RefusalCode.CALL_NOT_AT_THIS_ADDRESS,
            "{call} is a call on a task: DELETE /api/<scope>/<selector>/<number>.<sub>."),
        ROUTE_ON_A_TASK(RefusalCode.CALL_NOT_AT_THIS_ADDRESS,
            "{call} is a call on a task: POST /api/<scope>/<selector>/<number>.<sub>:{call}."),

        UNEXPECTED_FAILURE(RefusalCode.UNEXPECTED_FAILURE,
            "{call} failed unexpectedly. This is a defect, not a rule. Nothing was changed. "
                + "Report the reference in data.reference."),
        UNEXPECTED_FAILURE_OF_REQUEST(RefusalCode.UNEXPECTED_FAILURE,
            "The request failed unexpectedly. This is a defect, not a rule. Nothing was "
                + "changed. Report the reference in data.reference.");

        private final RefusalCode code;
        private final String pattern;

        Message(RefusalCode code, String pattern) {
            this.code = code;
            this.pattern = pattern;
        }

        public RefusalCode code() {
            return code;
        }

        public String pattern() {
            return pattern;
        }
    }

    /**
     * One declared reason: the code, its messages, and what the caller can do
     * about it.
     *
     * @param code     the caller-facing reason, stable across rewordings
     * @param messages the messages raised under it
     * @param remedy   what the caller does next: an instruction, worded as the
     *                 act to take (DEC-0042). Not sent on the wire — {@code
     *                 data.next} carries the calls — but declared, because a
     *                 reason nobody could act on is one that should not have
     *                 been declared.
     */
    public record Reason(RefusalCode code, List<Message> messages, String remedy) {

        public Reason {
            messages = List.copyOf(messages);
        }
    }

    /**
     * The one message the not-found class carries, transcribed from the
     * platform.
     *
     * <p><strong>Source:</strong> {@code Kumbuka-ai/platform}, {@code main},
     * {@code router/src/main/java/ai/kumbuka/router/surface/RouterException.java},
     * constant {@code NOT_FOUND_MESSAGE} (lines 35-37, read 2026-09-21) —
     * character for character. Copied and not imported: this service does not
     * compile against the router and must not.
     *
     * <p><strong>Why the router's words and not this service's own.</strong>
     * The DEC-0042 clause does not stop at the service boundary. Two hops
     * answering one condition in two wordings are two answers a caller can
     * tell apart by reading them: it learns which hop answered, and from that
     * whether a service stands behind a scheme at all — the enumeration oracle
     * ADR-0011 exists against.
     */
    public static final String NOT_FOUND_MESSAGE =
        "nothing is addressed here. Check the address, and that you are a member of "
            + "the scope it names.";

    private static final String MAKE_A_NEXT_CALL = "Make one of the calls in data.next.";

    private static final Map<RefusalCode, String> REMEDIES = remedies();

    private static final Map<RefusalCode, Reason> DECLARED = declare(REMEDIES);

    private static Map<RefusalCode, String> remedies() {
        Map<RefusalCode, String> remedies = new EnumMap<>(RefusalCode.class);
        remedies.put(RefusalCode.NOT_FOUND, "Check the scope and the address.");
        remedies.put(RefusalCode.STATE_DOES_NOT_ALLOW, MAKE_A_NEXT_CALL);
        remedies.put(RefusalCode.ROLE_DOES_NOT_ALLOW, MAKE_A_NEXT_CALL);
        remedies.put(RefusalCode.NOT_THE_HOLDER,
            "Claim the task first, or wait until its holder gives it back.");
        remedies.put(RefusalCode.RECEIPT_WRONG, "Pass the receipt from your latest claim.");
        remedies.put(RefusalCode.CHILDREN_NOT_FINISHED,
            "Repeat the call with data.confirmation, or finish the tasks in data.children "
                + "first.");
        remedies.put(RefusalCode.DEFERRAL_PENDING,
            "Take the task up after that instant, or take up another task now.");
        remedies.put(RefusalCode.NOTHING_TO_TAKE, "List the tasks with the query call.");
        remedies.put(RefusalCode.CLAIM_DURATION_INVALID,
            "Send the duration as a positive ISO-8601 duration such as PT2H.");
        remedies.put(RefusalCode.PAGE_SIZE_REJECTED,
            "Send page_size as a whole number within the bounds the message names.");
        remedies.put(RefusalCode.CURSOR_MALFORMED, "Call the query again from the first page.");
        remedies.put(RefusalCode.CURSOR_FOREIGN,
            "Repeat the call with the arguments that handed the cursor out.");
        remedies.put(RefusalCode.SORT_UNDECLARED, "Send sort as id or created_at.");
        remedies.put(RefusalCode.ARGUMENT_UNKNOWN, "Use the names in data.accepts.");
        remedies.put(RefusalCode.ARGUMENT_MISSING,
            "Repeat the call with the argument in data.offenders.");
        remedies.put(RefusalCode.ARGUMENT_INVALID,
            "Send the argument in data.offenders in the form the message describes.");
        remedies.put(RefusalCode.CONFLICT_TOKEN_STALE,
            "Read the task again and repeat the call with data.conflict_token.");
        remedies.put(RefusalCode.SELECTOR_UNKNOWN, "Name a bracket kind the scope declares.");
        remedies.put(RefusalCode.SCOPE_KIND_UNSUPPORTED, "Name a project or a global scope.");
        remedies.put(RefusalCode.SCOPE_READ_ONLY,
            "Ask whoever administers the membership of this scope for the write right.");
        remedies.put(RefusalCode.SCOPE_LOCKED, "Wait for the lock to be lifted, or read instead.");
        remedies.put(RefusalCode.IDEMPOTENCY_KEY_REUSED, "Choose a new key.");
        remedies.put(RefusalCode.CALL_NOT_AT_THIS_ADDRESS,
            "Send the call to the route the message names.");
        remedies.put(RefusalCode.UNEXPECTED_FAILURE, "Report the reference.");
        return remedies;
    }

    private static Map<RefusalCode, Reason> declare(Map<RefusalCode, String> remedies) {
        Map<RefusalCode, Reason> declared = new LinkedHashMap<>();
        for (RefusalCode code : RefusalCode.values()) {
            List<Message> messages = Arrays.stream(Message.values())
                .filter(m -> m.code() == code)
                .toList();
            String remedy = remedies.get(code);
            if (!messages.isEmpty() || remedy != null) {
                declared.put(code, new Reason(code, messages, remedy));
            }
        }
        return java.util.Collections.unmodifiableMap(declared);
    }

    /** The declared reasons by code, for the start-up guard. */
    public static Map<RefusalCode, Reason> byCode() {
        return DECLARED;
    }

    /** Every declared reason, in the order of the catalogue. */
    public static List<Reason> declared() {
        return List.copyOf(DECLARED.values());
    }

    /** One declared reason, or a failure that names the gap. */
    public static Reason of(RefusalCode code) {
        Reason reason = DECLARED.get(code);
        if (reason == null) {
            throw new IllegalStateException(
                code + " is not declared in the reason catalogue. A refusal with no "
                    + "declared message cannot be worded, and wording one here would be "
                    + "the second place refusals are decided.");
        }
        return reason;
    }

    /**
     * The text of one message, with its placeholders filled.
     *
     * <p>The values are typed: a call's name, an argument's path or name, a
     * bound, a form, or a list of one of them. An unfilled placeholder throws:
     * a half-filled message reads as finished, which is worse than none, and
     * the throw lands as {@code UNEXPECTED_FAILURE}, the honest name for it.
     */
    public static String message(Message message, Map<String, Object> values) {
        String rendered = message.pattern();
        for (Map.Entry<String, Object> value : values.entrySet()) {
            rendered = rendered.replace("{" + value.getKey() + "}", rendered(value.getValue()));
        }
        if (rendered.contains("{") && rendered.contains("}")) {
            throw new IllegalStateException(
                "the message " + message + " still carries an unfilled placeholder. A "
                    + "half-filled message reads as finished, which is worse than no message.");
        }
        return rendered;
    }

    private static String rendered(Object value) {
        return switch (value) {
            case VerbName v -> v.toString();
            case ArgumentPath p -> p.toString();
            case ArgumentName n -> n.toString();
            case Bound b -> b.toString();
            case Form f -> f.text();
            case List<?> list -> String.join(", ", list.stream().map(ReasonCatalogue::rendered)
                .toList());
            default -> throw new IllegalArgumentException(
                "a message takes only the platform's vocabulary as a placeholder, and a "
                    + value.getClass().getSimpleName() + " is none of it");
        };
    }

    /**
     * Refuses a catalogue that does not cover the codes.
     *
     * <p>Called from the start-up guard. A code with no message cannot be
     * worded and would escape as an unexpected failure the first time it is
     * raised; a code with no remedy is a refusal nobody could act on.
     */
    public static void requireComplete(Map<RefusalCode, Reason> catalogue) {
        List<String> undeclared = Arrays.stream(RefusalCode.values())
            .filter(code -> !catalogue.containsKey(code))
            .map(Enum::name)
            .toList();

        if (!undeclared.isEmpty()) {
            throw new IllegalStateException(
                "the reason catalogue does not declare " + undeclared + ". A reason not "
                    + "in the table cannot be returned, and the service refuses to start "
                    + "rather than raise one it cannot word.");
        }

        for (Reason reason : catalogue.values()) {
            if (reason.messages().isEmpty()
                || reason.remedy() == null || reason.remedy().isBlank()) {
                throw new IllegalStateException(
                    reason.code() + " is declared without a message or without a remedy. "
                        + "A refusal that cannot say what happened, or cannot say what to "
                        + "do about it, is not a declared refusal.");
            }
        }
    }
}
