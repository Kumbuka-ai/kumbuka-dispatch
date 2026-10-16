/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.contract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The declaration this service must publish, transcribed by hand (ADR-0039).
 *
 * <p>The counter-probe, never the source: written from the router's
 * definitions for this scheme ({@code Kumbuka-ai/platform}, {@code main},
 * {@code router/src/main/java/ai/kumbuka/router/verb/VerbCatalog.java}, read
 * 2026-10-15) for the effect classes and the answer shapes, from concept
 * section 3.2 and DEC-0043 R17 for the arguments and their defaults, and from
 * the refusals each call can meet for the reasons. It imports nothing from
 * the production packages, so a declaration that changes is compared with
 * what it was meant to be and not with itself.
 *
 * <p>The codes are the platform's table (service contract C8.7, platform-specs
 * {@code df81898}) wherever it names the condition, and the placements are
 * {@code TOP} and {@code FIELDS} as rule C5.4 writes them.
 *
 * <p>Where the router's own lists of reasons differ, the transcript follows
 * the service, and the difference is the router's to take up: {@code create}
 * meets the state of a closed parent, {@code annotate} the state of a draft,
 * {@code read_text} an executor that holds the task not, and {@code query}
 * the refusals of its page; {@code claim_next} meets no state, since it
 * draws only open tasks.
 */
public final class DeclarationTranscript {

    private DeclarationTranscript() {
    }

    /** The router's closed set of effect classes. */
    public static final List<String> EFFECT_CLASSES = List.of(
        "ITEM_READ", "COLLECTION_READ", "SCHEME_READ", "SCOPE_READ", "SCOPE_COMPOUND",
        "CREATE", "FIELD_WRITE", "GRAPH_ADD_OR_REMOVE", "ITEM_TRANSITION", "ITEM_READ_CALL",
        "ITEM_DELETE", "COLLECTION_COMPOUND");

    /** One call as the declaration states it. */
    public record Call(String effectClass, String answerShape, Set<String> refusals,
                       List<String> arguments) {
    }

    private static final List<String> COMMON = List.of("NOT_FOUND", "PAYLOAD_MALFORMED",
        "ADDRESS_MALFORMED", "ARGUMENT_UNKNOWN", "ARGUMENT_MISSING", "ARGUMENT_INVALID",
        "HEADER_INVALID", "ROLE_INSUFFICIENT", "SELECTOR_UNDECLARED", "SELECTOR_WITHDRAWN",
        "SCOPE_KIND_UNSUPPORTED", "VERB_NOT_CARRIED", "UNEXPECTED_FAILURE");
    private static final List<String> WRITE = List.of("SCOPE_READ_ONLY", "SCOPE_LOCKED");
    private static final List<String> HOLDER = List.of("CLAIM_HELD", "CLAIM_ABSENT",
        "RECEIPT_WRONG");

    /**
     * Every call by its identity, in the order of TAR-0004 section 3. An
     * argument is {@code name:type:placement:required|optional[=default]};
     * {@code optional} alone means its default is null, which leaves it unset.
     */
    public static final Map<String, Call> CALLS = calls();

    private static Map<String, Call> calls() {
        Map<String, Call> calls = new LinkedHashMap<>();
        String address = "address:string:TOP:required";
        String token = "conflict_token:string:TOP:required";
        String receipt = "receipt:string:TOP:required";
        String duration = "duration:string:TOP:optional=PT30M";
        String key = "idempotency_key:string:TOP:optional";
        String confirmation = "confirmation:string:TOP:optional";
        String remark = "remark:string:FIELDS:optional";
        String remarkRequired = "remark:string:FIELDS:required";
        String scope = "scope:string:TOP:required";
        String selector = "selector:string:TOP:required";

        put(calls, "send", "ITEM_TRANSITION", "TASK_LEAN",
            reasons(WRITE, "TRANSITION_REFUSED", "CONFLICT_TOKEN_STALE", "CONFLICT_TOKEN_MISSING"), address, token);
        put(calls, "claim", "ITEM_TRANSITION", "CLAIM_RECEIPT",
            reasons(WRITE, "TRANSITION_REFUSED",
                "DEFERRAL_PENDING", "IDEMPOTENCY_KEY_REUSED"), address, duration, key);
        put(calls, "claim_next", "COLLECTION_COMPOUND", "CLAIM_RECEIPT",
            reasons(WRITE, "DRAW_EMPTY",
                "IDEMPOTENCY_KEY_REUSED"),
            scope, selector, "apparatus:array:TOP:required", duration, key);
        put(calls, "release", "ITEM_TRANSITION", "TASK_LEAN",
            holder("TRANSITION_REFUSED"), address, receipt, remark);
        put(calls, "defer", "ITEM_TRANSITION", "TASK_LEAN",
            holder("TRANSITION_REFUSED"), address, receipt,
            "not_before:string:FIELDS:required", remark);
        put(calls, "renew", "ITEM_TRANSITION", "TASK_LEAN",
            holder("TRANSITION_REFUSED"), address, receipt,
            duration);
        put(calls, "ask", "ITEM_TRANSITION", "TASK_LEAN",
            holder("TRANSITION_REFUSED"), address, receipt,
            "question:string:FIELDS:required", "options:array:FIELDS:optional",
            "free_text:boolean:FIELDS:optional=false");
        put(calls, "answer", "ITEM_TRANSITION", "TASK_LEAN",
            reasons(WRITE, "TRANSITION_REFUSED", "CONFLICT_TOKEN_STALE", "CONFLICT_TOKEN_MISSING"), address, token,
            "option:string:FIELDS:optional", "text:string:FIELDS:optional");
        put(calls, "hold", "ITEM_TRANSITION", "TASK_LEAN",
            holder("TRANSITION_REFUSED"), address, receipt,
            "reason:string:FIELDS:required", remark);
        put(calls, "resume", "ITEM_TRANSITION", "TASK_LEAN",
            holder("TRANSITION_REFUSED"), address, receipt,
            duration);
        put(calls, "deliver", "ITEM_TRANSITION", "TASK_LEAN",
            holder("TRANSITION_REFUSED"), address, receipt, "text:string:FIELDS:required",
            "metadata:object:FIELDS:optional");
        put(calls, "rework", "ITEM_TRANSITION", "TASK_LEAN",
            reasons(WRITE, "TRANSITION_REFUSED", "CONFLICT_TOKEN_STALE", "CONFLICT_TOKEN_MISSING"), address, token,
            remarkRequired);
        put(calls, "accept", "ITEM_TRANSITION", "TASK_LEAN",
            reasons(WRITE, "TRANSITION_REFUSED", "CONFLICT_TOKEN_STALE", "CONFLICT_TOKEN_MISSING",
                "CHILDREN_NOT_FINISHED"), address, token, confirmation);
        put(calls, "reject", "ITEM_TRANSITION", "TASK_LEAN",
            reasons(WRITE, "TRANSITION_REFUSED"), address, remarkRequired);
        put(calls, "fail", "ITEM_TRANSITION", "TASK_LEAN",
            holder("TRANSITION_REFUSED", "CHILDREN_NOT_FINISHED"), address, receipt,
            confirmation, remarkRequired);
        put(calls, "withdraw", "ITEM_TRANSITION", "TASK_LEAN",
            reasons(WRITE, "TRANSITION_REFUSED", "CONFLICT_TOKEN_STALE", "CONFLICT_TOKEN_MISSING",
                "CHILDREN_NOT_FINISHED"), address, token, confirmation, remark);
        put(calls, "create", "CREATE", "TASK_LEAN_CREATED",
            reasons(WRITE, "TRANSITION_REFUSED", "IDEMPOTENCY_KEY_REUSED"), scope, selector,
            "parent:string:TOP:optional", key, "title:string:FIELDS:required",
            "apparatus:string:FIELDS:required", "text:string:FIELDS:optional",
            "metadata:object:FIELDS:optional");
        put(calls, "update", "FIELD_WRITE", "TASK_LEAN",
            reasons(WRITE, "TRANSITION_REFUSED", "CONFLICT_TOKEN_STALE", "CONFLICT_TOKEN_MISSING"), address, token,
            "title:string:FIELDS:optional", "apparatus:string:FIELDS:optional",
            "text:string:FIELDS:optional", "metadata:object:FIELDS:optional");
        put(calls, "delete", "ITEM_DELETE", "TASK_DELETED",
            reasons(WRITE, "TRANSITION_REFUSED", "CONFLICT_TOKEN_STALE", "CONFLICT_TOKEN_MISSING"), address, token);
        put(calls, "read", "ITEM_READ", "TASK_HEAD", reasons(List.of()), address);
        put(calls, "read_text", "ITEM_READ_CALL", "TASK_TEXT",
            reasons(List.of(), "CLAIM_HELD", "CLAIM_ABSENT"), address, "part:string:TOP:required");
        put(calls, "query", "COLLECTION_READ", "TASK_LISTING",
            reasons(List.of(), "PAGE_SIZE_REJECTED", "CURSOR_MALFORMED", "CURSOR_FOREIGN",
                "SORT_UNDECLARED"),
            scope, selector, "state:string:TOP:optional", "apparatus:string:TOP:optional",
            "bracket:string:TOP:optional", "address:string:TOP:optional",
            "page_size:integer:TOP:optional=50", "after:string:TOP:optional",
            "sort:string:TOP:optional=id", "direction:string:TOP:optional=asc");
        put(calls, "annotate", "ITEM_TRANSITION", "TASK_LEAN",
            reasons(WRITE, "TRANSITION_REFUSED", "IDEMPOTENCY_KEY_REUSED"), address, key,
            "text:string:FIELDS:required", "part:string:FIELDS:required");
        put(calls, "relate", "ITEM_TRANSITION", "TASK_LEAN",
            reasons(WRITE, "TRANSITION_REFUSED", "CONFLICT_TOKEN_STALE", "CONFLICT_TOKEN_MISSING"), address, token,
            "curated_in:string:FIELDS:required");
        put(calls, "unrelate", "ITEM_TRANSITION", "TASK_LEAN",
            reasons(WRITE, "TRANSITION_REFUSED", "CONFLICT_TOKEN_STALE", "CONFLICT_TOKEN_MISSING"), address, token);
        return java.util.Collections.unmodifiableMap(calls);
    }

    /** The closed sets of values, by call and argument. */
    public static final Map<String, List<String>> VALUES = Map.of(
        "hold.reason", List.of("dependency", "external"),
        "read_text.part", List.of("dispatch", "return", "thread", "addenda"),
        "annotate.part", List.of("dispatch", "return", "question", "answer", "remark"),
        "query.sort", List.of("id", "created_at"),
        "query.direction", List.of("asc", "desc"));

    private static void put(Map<String, Call> calls, String verb, String effect, String shape,
                            Set<String> refusals, String... arguments) {
        calls.put("dispatch_" + verb, new Call(effect, shape, refusals, List.of(arguments)));
    }

    private static Set<String> reasons(List<String> base, String... more) {
        Set<String> all = new java.util.TreeSet<>(COMMON);
        all.addAll(base);
        all.addAll(List.of(more));
        return java.util.Collections.unmodifiableSet(all);
    }

    private static Set<String> holder(String... more) {
        Set<String> all = new java.util.TreeSet<>(reasons(WRITE, more));
        all.addAll(HOLDER);
        return java.util.Collections.unmodifiableSet(all);
    }
}
