/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.HoldReason;
import ai.kumbuka.dispatch.domain.PageRequest;
import ai.kumbuka.dispatch.domain.SortKey;
import ai.kumbuka.dispatch.domain.TaskCall;
import ai.kumbuka.dispatch.domain.TaskFilter;
import ai.kumbuka.dispatch.domain.TaskInput;
import ai.kumbuka.dispatch.domain.TaskService;
import ai.kumbuka.dispatch.domain.TaskVerb;
import ai.kumbuka.dispatch.surface.AddressParser;
import ai.kumbuka.dispatch.surface.ApparatusPatterns;
import ai.kumbuka.dispatch.surface.Argument;
import ai.kumbuka.dispatch.surface.ArgumentPath;
import ai.kumbuka.dispatch.surface.Form;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.Refusal;
import ai.kumbuka.dispatch.surface.Surface;
import ai.kumbuka.dispatch.surface.SurfaceException;
import ai.kumbuka.dispatch.surface.VerbName;
import ai.kumbuka.dispatch.surface.VerbSurface;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One call's arguments, checked against the declaration before anything is
 * read out of them: the form check of both adapters (C1.2).
 *
 * <p><strong>Checked first and completely.</strong> The constructor walks both
 * levels of the incoming map and refuses, in this order: the arguments the
 * call does not declare, then the first one of the wrong type or outside its
 * values, then the first required one that is absent. Undeclared arguments are
 * refused one level at a time and all of a level together: every undeclared
 * name at the top in one refusal, and only when the top is clean every
 * undeclared name under {@code fields} in one. Nothing is resolved, read or
 * written before that walk finishes, which is what "nothing was written" in
 * an argument refusal is a statement about.
 *
 * <p><strong>{@code null} is absent</strong> (DEC-0043 R17). An argument sent
 * as JSON {@code null} is dropped before anything is checked, at the top and
 * under {@code fields}.
 *
 * <p><strong>One check for both adapters.</strong> MCP hands over the tool
 * call's arguments, REST the path, the {@code If-Match} header, the query and
 * the body, and both arrive as the same map closed against the same
 * declaration, so the check lives once here rather than once per adapter.
 *
 * <p><strong>The conflict token is not checked here.</strong> It is declared
 * required, and its absence is refused by the kernel as {@code
 * CONFLICT_TOKEN_MISSING}, after the scope has resolved (C1.1, C1.3): a call
 * on a scope the caller may not see answers {@code NOT_FOUND} whatever else it
 * lacks.
 *
 * <p>Every refusal here is a {@link SurfaceException}; nothing here catches
 * one.
 */
public final class CallPayload {

    /** The one nested object a call may carry. */
    public static final String FIELDS = Argument.FIELDS;

    private static final String PAGE_SIZE = "page_size";
    private static final String SORT = "sort";
    private static final String CONFLICT_TOKEN = "conflict_token";
    private static final String ADDRESS = "address";
    private static final String SCOPE = "scope";
    private static final String SELECTOR = "selector";
    private static final String RECEIPT = "receipt";
    private static final String DURATION = "duration";
    private static final String CONFIRMATION = "confirmation";
    private static final String KEY = "idempotency_key";
    private static final String REMARK = "remark";
    private static final String TEXT = "text";
    private static final String PART = "part";
    private static final String PARENT = "parent";
    private static final String CURATED_IN = "curated_in";
    private static final String APPARATUS = "apparatus";
    private static final String OPTION = "option";

    /** The filters of a listing, by the names the kernel and the declaration share. */
    static final List<String> FILTERS = TaskFilter.Field.wireNames();

    /**
     * The first instant and the bound past the last a task stores, as the
     * start of a deferral and as the end of a lease: inside what the timestamp
     * columns hold. An instant past that range parsed, reached the store and
     * failed there as a defect, and so did a lease whose end lies past it.
     */
    private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("+294276-01-01T00:00:00Z");

    private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);
    private static final BigDecimal LONG_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final Pattern WHOLE_TEXT = Pattern.compile("[+-]?\\d+");
    /** Every run of this many digits fits a long; one more may not. */
    private static final int MAX_LONG_DIGITS = 18;

    private final ProcessVerb verb;
    private final VerbName call;
    private final Map<String, Object> top;
    private final Map<String, Object> written;

    /**
     * Reads the arguments of one call, refusing anything the declaration does
     * not admit.
     *
     * @param surface the surface the call arrived on, which names it in a
     *                refusal
     * @throws SurfaceException {@code ARGUMENT_UNKNOWN}, {@code ARGUMENT_INVALID}
     *                          or {@code ARGUMENT_MISSING}, naming the argument;
     *                          {@code PAGE_SIZE_REJECTED} for a page size that is
     *                          no whole number, {@code SORT_UNDECLARED} for a
     *                          sort the listing does not declare
     */
    public CallPayload(ProcessVerb verb, Surface surface, Map<String, Object> arguments) {
        this.verb = verb;
        this.call = VerbName.of(verb, surface);
        this.top = present(arguments);
        this.written = nestedFields();

        refuseUndeclared();
        refuseMistyped();
        refuseMissing();
    }

    // ======================================================================
    // The three checks
    // ======================================================================

    /** The members of one level that carry a value: a {@code null} is a member not sent. */
    private static Map<String, Object> present(Map<?, ?> level) {
        Map<String, Object> carried = new LinkedHashMap<>();
        if (level != null) {
            level.forEach((name, value) -> {
                if (value != null) {
                    carried.put(String.valueOf(name), value);
                }
            });
        }
        return carried;
    }

    private Map<String, Object> nestedFields() {
        Object nested = top.get(FIELDS);
        if (nested == null || !verb.hasFields()) {
            return Map.of();
        }
        if (!(nested instanceof Map<?, ?> map)) {
            throw refused(Refusal.argumentInvalid(call, ArgumentPath.FIELDS, Form.FIELDS_OBJECT));
        }
        return present(map);
    }

    private void refuseUndeclared() {
        List<ArgumentPath> topLevel = new ArrayList<>(ArgumentPath.of(verb.topArguments()));
        if (verb.hasFields()) {
            topLevel.add(ArgumentPath.FIELDS);
        }
        refuseUndeclared(top, verb.topNames(), false, topLevel);
        refuseUndeclared(written, verb.fieldArguments().stream().map(Argument::name).toList(),
            true, ArgumentPath.of(verb.fieldArguments()));
    }

    /**
     * Refuses a level that carries a name the declaration does not hold. The
     * refusal names none of them (DEC-0043 R19): it lists what the level
     * accepts.
     */
    private void refuseUndeclared(Map<String, Object> level, List<String> declared,
                                  boolean underFields, List<ArgumentPath> accepts) {
        boolean undeclared = level.keySet().stream().anyMatch(name -> !declared.contains(name));
        if (undeclared) {
            throw refused(Refusal.argumentUnknown(call, underFields, accepts));
        }
    }

    private void refuseMistyped() {
        for (Argument argument : verb.arguments()) {
            Object value = levelOf(argument).get(argument.name());
            Form form = value == null ? null : mistyped(argument, value);
            if (form == null) {
                continue;
            }
            if (PAGE_SIZE.equals(argument.name())) {
                // ADR-0049: every page size a listing cannot take is refused
                // as one, a value that is no whole number as much as one out
                // of bounds, so a caller correcting it branches on one code.
                throw refused(Refusal.pageSizeRejected(call, List.of(ArgumentPath.of(argument))));
            }
            if (SORT.equals(argument.name()) && form == Form.ONE_OF) {
                // ADR-0049: a sort the listing does not declare has its own code.
                throw refused(Refusal.sortUndeclared(call, List.of(ArgumentPath.of(argument))));
            }
            throw refused(Refusal.argumentInvalid(call, positionOf(argument, value), form));
        }
    }

    private static Form mistyped(Argument argument, Object value) {
        return switch (argument.type()) {
            case Argument.STRING -> {
                if (value instanceof Map || value instanceof List) {
                    yield Form.STRING;
                }
                if (!argument.values().isEmpty()
                        && !argument.valueNames().contains(String.valueOf(value))) {
                    yield Form.ONE_OF;
                }
                yield null;
            }
            case Argument.ARRAY -> value instanceof List<?> list
                    && list.stream().allMatch(String.class::isInstance)
                ? null
                : Form.LIST_OF_STRINGS;
            case Argument.OBJECT -> value instanceof Map ? null : Form.OBJECT;
            case Argument.BOOLEAN -> value instanceof Boolean
                    || "true".equals(value) || "false".equals(value)
                ? null
                : Form.BOOLEAN;
            case Argument.INTEGER -> wholeOf(value) == null ? Form.WHOLE_NUMBER : null;
            default -> throw new IllegalStateException(
                argument.name() + " is declared with a type the surface does not read");
        };
    }

    /**
     * Where a refused value stands: the argument, and for a list the position
     * of the first element that is no string (DEC-0043 R19).
     */
    private static ArgumentPath positionOf(Argument argument, Object value) {
        if (Argument.ARRAY.equals(argument.type()) && value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                if (!(list.get(i) instanceof String)) {
                    return ArgumentPath.of(argument, i);
                }
            }
        }
        return ArgumentPath.of(argument);
    }

    private void refuseMissing() {
        for (Argument argument : verb.arguments()) {
            if (!argument.required() || CONFLICT_TOKEN.equals(argument.name())) {
                continue;
            }
            Object value = levelOf(argument).get(argument.name());
            boolean absent = value == null
                || (value instanceof String s && s.isBlank())
                || (value instanceof List<?> l && l.isEmpty());
            if (absent) {
                throw refused(Refusal.argumentMissing(call, ArgumentPath.of(argument)));
            }
        }
    }

    private Map<String, Object> levelOf(Argument argument) {
        return argument.isField() ? written : top;
    }

    // ======================================================================
    // What the call names: addresses, read once and refused by the argument
    // that carried them
    // ======================================================================

    /** The call's name on the surface it arrived on, as a refusal names it. */
    public VerbName call() {
        return call;
    }

    public ProcessVerb verb() {
        return verb;
    }

    /** The task the top-level argument {@code address} names. */
    public VerbSurface.Item address() {
        return item(topPath(ADDRESS), top(ADDRESS));
    }

    /** The scope a call on a collection names. */
    public String scope() {
        return AddressParser.scopeOf(top(SCOPE))
            .orElseThrow(() -> refused(Refusal.argumentInvalid(call, topPath(SCOPE), Form.SCOPE)));
    }

    /** The bracket kind a call on a collection names. */
    public String selector() {
        return AddressParser.selectorOf(top(SELECTOR)).orElseThrow(() ->
            refused(Refusal.argumentInvalid(call, topPath(SELECTOR), Form.SELECTOR)));
    }

    /**
     * The bracket root a create names as its parent, as the number of its
     * bracket; null where it names none.
     */
    public Integer parentNumber(String scope, String selector) {
        String parent = top(PARENT);
        if (parent == null) {
            return null;
        }
        ArgumentPath path = topPath(PARENT);
        VerbSurface.Item root = item(path, parent);
        if (!root.scope().equals(scope) || !root.address().selector().equals(selector)) {
            throw refused(Refusal.argumentInvalid(call, path, Form.PARENT_ELSEWHERE));
        }
        if (root.address().sub() != 0) {
            throw refused(Refusal.argumentInvalid(call, path, Form.PARENT_NOT_A_ROOT));
        }
        return root.address().number();
    }

    /** The task a relation is curated into. */
    public VerbSurface.Item curatedIn() {
        return item(fieldPath(CURATED_IN), field(CURATED_IN));
    }

    private VerbSurface.Item item(ArgumentPath path, String raw) {
        AddressParser.Parts parts = AddressParser.partsOf(raw)
            .orElseThrow(() -> refused(Refusal.argumentInvalid(call, path, Form.ADDRESS)));
        return new VerbSurface.Item(parts.scope(),
            AddressParser.item(parts.selector(), parts.id()));
    }

    // ======================================================================
    // What a call writes
    // ======================================================================

    /** A draft as create writes it. */
    public TaskService.Draft draft() {
        return new TaskService.Draft(field("title"), field(APPARATUS), field(TEXT),
            metadata().orElse(null));
    }

    /** The changes of an update; refused where it names none. */
    public TaskService.Draft changes() {
        TaskService.Draft changes = draft();
        if (changes.title() == null && changes.apparatus() == null && changes.text() == null
                && changes.metadata() == null) {
            throw refused(Refusal.argumentMissing(call, Refusal.Missing.CHANGE,
                List.of(ArgumentPath.FIELDS)));
        }
        return changes;
    }

    /** The conflict token, as the call carried it; null where it carried none. */
    public String conflictToken() {
        return top(CONFLICT_TOKEN);
    }

    /** The idempotency key, as the call carried it; null where it carried none. */
    public String idempotencyKey() {
        return top(KEY);
    }

    /** The part of a text a read names. */
    public String textPart() {
        return top(PART).toUpperCase(Locale.ROOT);
    }

    /** The part an addendum is written to. */
    public String addendumPart() {
        return field(PART);
    }

    /** The path of the part an addendum is written to, for its refusal. */
    public ArgumentPath addendumPartPath() {
        return fieldPath(PART);
    }

    /** The text of an addendum. */
    public String text() {
        return field(TEXT);
    }

    /**
     * What a transition carries: the caller, the proofs, and the payload of
     * its row, built from arguments the declaration has already admitted and
     * refused by name where a combination is not one the kernel can take.
     */
    public TaskCall transition(Actor actor, TaskVerb transition, Clock clock) {
        return new TaskCall(actor, top(RECEIPT), top(CONFLICT_TOKEN), top(CONFIRMATION),
            payloadOf(transition, clock));
    }

    private TaskInput payloadOf(TaskVerb transition, Clock clock) {
        return switch (transition) {
            case SEND, ACCEPT -> TaskInput.NONE;
            case CLAIM, CLAIM_NEXT, RENEW, RESUME -> lease(clock);
            case RELEASE, WITHDRAW -> new TaskInput.Remark(field(REMARK));
            case REJECT, FAIL, REWORK -> new TaskInput.RequiredRemark(field(REMARK));
            case DEFER -> new TaskInput.Deferral(instant("not_before"), field(REMARK));
            case ASK -> question();
            case ANSWER -> reply();
            case HOLD -> new TaskInput.Pause(
                HoldReason.valueOf(field("reason").toUpperCase(Locale.ROOT)), field(REMARK));
            case DELIVER -> new TaskInput.Delivery(field(TEXT), metadata().orElse(null));
        };
    }

    /**
     * A lease as the call names it: a positive ISO-8601 duration whose end
     * lies inside what the store holds; the kernel's default where none is
     * named.
     */
    private TaskInput lease(Clock clock) {
        String raw = top(DURATION);
        if (raw == null) {
            return TaskInput.NONE;
        }
        Duration duration;
        try {
            duration = Duration.parse(raw);
        } catch (DateTimeParseException e) {
            throw refused(Refusal.claimDurationInvalid(call, topPath(DURATION)));
        }
        if (duration.isZero() || duration.isNegative()) {
            throw refused(Refusal.claimDurationInvalid(call, topPath(DURATION)));
        }
        if (duration.compareTo(Duration.between(clock.instant(), LATEST)) >= 0) {
            throw refused(Refusal.argumentInvalid(call, topPath(DURATION), Form.LEASE_END));
        }
        return new TaskInput.Lease(duration);
    }

    private Instant instant(String name) {
        Instant at = parsedInstant(field(name));
        if (at == null) {
            throw refused(Refusal.argumentInvalid(call, fieldPath(name), Form.INSTANT));
        }
        if (at.isBefore(EARLIEST) || !at.isBefore(LATEST)) {
            throw refused(Refusal.argumentInvalid(call, fieldPath(name), Form.INSTANT_RANGE));
        }
        return at;
    }

    /** The instant ISO-8601 text names, in UTC or with an offset; null where it names none. */
    private static Instant parsedInstant(String raw) {
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException notUtc) {
            try {
                return OffsetDateTime.parse(raw).toInstant();
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    }

    private TaskInput question() {
        List<String> options = list("options", Argument.Placement.FIELDS);
        boolean freeText = flag("free_text");
        if (options.isEmpty() && !freeText) {
            throw refused(Refusal.argumentMissing(call, Refusal.Missing.OPTIONS,
                List.of(fieldPath("options"))));
        }
        return new TaskInput.Question(field("question"), options, freeText);
    }

    private TaskInput reply() {
        String option = field(OPTION);
        String text = field(TEXT);
        if (option == null && text == null) {
            throw refused(Refusal.argumentMissing(call, Refusal.Missing.ANSWER,
                List.of(fieldPath(OPTION), fieldPath(TEXT))));
        }
        if (option != null && text != null) {
            throw refused(Refusal.argumentInvalid(call, fieldPath(TEXT), Form.ANSWER_ONE_OF_TWO));
        }
        return new TaskInput.Reply(option, text);
    }

    // ======================================================================
    // What a draw and a listing name
    // ======================================================================

    /**
     * The apparatus patterns a draw names: each well formed, and none that is
     * nothing but wildcards, which would narrow nothing.
     */
    public List<String> apparatusPatterns() {
        List<String> patterns = list(APPARATUS, Argument.Placement.TOP);
        Argument apparatus = declared(APPARATUS, Argument.Placement.TOP);
        int malformed = ApparatusPatterns.firstMalformedAt(patterns);
        if (malformed >= 0) {
            throw refused(Refusal.argumentInvalid(call, position(apparatus, patterns, malformed),
                Form.APPARATUS_PATTERN));
        }
        int unbounded = ApparatusPatterns.firstUnboundedAt(patterns);
        if (unbounded >= 0) {
            throw refused(Refusal.argumentInvalid(call, position(apparatus, patterns, unbounded),
                Form.APPARATUS_UNBOUNDED));
        }
        return patterns;
    }

    /**
     * The filter of a listing, as the kernel reads it: addresses in their
     * short form, each in the listing's own scope and bracket kind, apparatus
     * patterns well formed. Whether a value is one the field takes is the
     * kernel's to say, and it refuses one it cannot take by the filter's name.
     */
    public TaskFilter filter(String scope, String selector) {
        Map<String, String> filters = new LinkedHashMap<>();
        for (String name : FILTERS) {
            String raw = top(name);
            if (raw == null) {
                continue;
            }
            Argument argument = declared(name, Argument.Placement.TOP);
            List<String> values = List.of(raw.split(",", -1)).stream().map(String::trim).toList();
            String value = name.equals(TaskFilter.Field.ADDRESS.wireName())
                ? shortAddresses(argument, scope, selector, values)
                : raw;
            if (name.equals(TaskFilter.Field.APPARATUS.wireName())) {
                int malformed = ApparatusPatterns.firstMalformedAt(values);
                if (malformed >= 0) {
                    throw refused(Refusal.argumentInvalid(call,
                        position(argument, values, malformed), Form.APPARATUS_PATTERN));
                }
            }
            filters.put(name, value);
        }
        return TaskFilter.of(filters);
    }

    /** An address filter: complete addresses in, the kernel's short form out. */
    private String shortAddresses(Argument argument, String scope, String selector,
                                  List<String> values) {
        List<String> shortForms = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            ArgumentPath path = position(argument, values, i);
            Optional<AddressParser.Parts> parts = AddressParser.partsOf(values.get(i));
            if (parts.isEmpty()) {
                throw refused(Refusal.argumentInvalid(call, path, Form.ADDRESS_FILTER));
            }
            if (!parts.get().scope().equals(scope) || !parts.get().selector().equals(selector)) {
                throw refused(Refusal.argumentInvalid(call, path, Form.ADDRESS_FILTER_ELSEWHERE));
            }
            shortForms.add(parts.get().selector() + "/" + parts.get().id());
        }
        return String.join(",", shortForms);
    }

    /** The page a listing asks for; the kernel refuses what it cannot take. */
    public PageRequest page() {
        return PageRequest.of(wholeNumber(PAGE_SIZE), top(SORT),
            SortKey.Direction.of(top("direction")), top("after"));
    }

    /**
     * The path a refusal of one of several values names: the argument with
     * the value's position where it carries several, the argument alone
     * where it carries one (DEC-0043 R19).
     */
    private static ArgumentPath position(Argument argument, List<String> values, int at) {
        return values.size() > 1 ? ArgumentPath.of(argument, at) : ArgumentPath.of(argument);
    }

    // ======================================================================
    // Reading values. Every one of them has passed the three checks above.
    // ======================================================================

    /** The declared argument of this name at one level; a defect where there is none. */
    public Argument declared(String name, Argument.Placement placement) {
        Argument argument = verb.argument(name, placement);
        if (argument == null) {
            throw new IllegalStateException(verb.call() + " declares no " + name);
        }
        return argument;
    }

    /** The path of the declared top-level argument of this name. */
    public ArgumentPath topPath(String name) {
        return ArgumentPath.of(declared(name, Argument.Placement.TOP));
    }

    /** The path of the declared argument of this name under {@code fields}. */
    public ArgumentPath fieldPath(String name) {
        return ArgumentPath.of(declared(name, Argument.Placement.FIELDS));
    }

    /** A top-level string, or null where it is absent. */
    public String top(String name) {
        return string(top.get(name));
    }

    /** A string under {@code fields}, or null where it is absent. */
    public String field(String name) {
        return string(written.get(name));
    }

    /** A list of strings at either level, empty where it is absent. */
    public List<String> list(String name, Argument.Placement placement) {
        Object raw = (placement == Argument.Placement.FIELDS ? written : top).get(name);
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }

    /** A yes or no under {@code fields}, false where it is absent. */
    public boolean flag(String name) {
        Object raw = written.get(name);
        return Boolean.TRUE.equals(raw) || "true".equals(raw);
    }

    /**
     * A whole number at the top level, or null where it is absent. A number
     * past what a long holds arrives as the long's bound on its side.
     */
    public Long wholeNumber(String name) {
        return wholeOf(top.get(name));
    }

    /** The metadata object under {@code fields}, or empty where it is absent. */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> metadata() {
        Object raw = written.get("metadata");
        if (!(raw instanceof Map)) {
            return Optional.empty();
        }
        Map<String, Object> carried = new LinkedHashMap<>();
        ((Map<Object, Object>) raw).forEach((k, v) -> carried.put(String.valueOf(k), v));
        return Optional.of(carried);
    }

    /**
     * The whole number a value names, or null where it names none: a JSON
     * number with no fraction, or text that is a whole number. A number past
     * what a long holds comes back as the long's bound on its side, which is
     * enough to refuse it as too large without ever building it.
     */
    static Long wholeOf(Object value) {
        if (value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof String s) {
            return wholeOf(s.trim());
        }
        if (!(value instanceof Number n)) {
            return null;
        }
        BigDecimal exact;
        if (n instanceof BigDecimal d) {
            exact = d;
        } else if (n instanceof BigInteger b) {
            exact = new BigDecimal(b);
        } else if (Double.isFinite(n.doubleValue())) {
            exact = BigDecimal.valueOf(n.doubleValue());
        } else {
            return null;
        }
        if (exact.signum() != 0 && exact.stripTrailingZeros().scale() > 0) {
            return null;
        }
        if (exact.compareTo(LONG_MAX) > 0) {
            return Long.MAX_VALUE;
        }
        if (exact.compareTo(LONG_MIN) < 0) {
            return Long.MIN_VALUE;
        }
        return exact.longValueExact();
    }

    /** Text that is a sign and digits; more than eighteen digits are read as a bound. */
    private static Long wholeOf(String text) {
        if (!WHOLE_TEXT.matcher(text).matches()) {
            return null;
        }
        boolean negative = text.startsWith("-");
        String digits = text.replaceFirst("^[+-]?0*", "");
        if (digits.length() > MAX_LONG_DIGITS) {
            return negative ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        long magnitude = digits.isEmpty() ? 0 : Long.parseLong(digits);
        return negative ? -magnitude : magnitude;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static SurfaceException refused(Refusal refusal) {
        return new SurfaceException(refusal);
    }
}
