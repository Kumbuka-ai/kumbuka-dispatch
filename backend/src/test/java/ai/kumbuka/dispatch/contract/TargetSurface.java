/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.CounterProbe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The surface the target and the concept document fix, as fixed values.
 *
 * <p>Written by hand from {@code platform-specs}: the calls from TAR-0004
 * section 3 but the draw, which REA-0010 takes off the surface, and what each
 * takes from the table in section 3.2 of
 * {@code concept-dispatch-store-kernel-and-surface.md}; and from the
 * commission of sprint/210, the two calls on the tags of a task and the
 * filter {@code tag}. Never read from the
 * declaration: a test whose expectation comes from the thing it checks
 * asserts that one copy equals itself.
 *
 * <p>Where the table leaves a name open, the name the service chose stands
 * here, marked: the filters of {@code query} ("filters" in the table; the
 * concept's section 5 names state, apparatus pattern, bracket and a list of
 * addresses). The page of {@code query} is no longer the service's choice:
 * {@code page_size}, {@code after}, {@code sort} and {@code direction} are
 * the request the platform's pagination frame fixes for every primary object
 * (ratified by the operator 2026-10-11, the substance of ADR-0049), and they
 * stand here from that frame.
 */
@CounterProbe
public final class TargetSurface {

    private TargetSurface() {
    }

    /**
     * The transitions of TAR-0004 section 3, in its order, but the draw:
     * {@code claim_next} left the surface in the second step of the dispatch
     * wave of REA-0010, and a task is taken up by its address.
     */
    public static final List<String> TRANSITIONS = List.of(
        "send", "recall", "claim", "release", "defer", "renew", "ask", "answer",
        "hold", "resume", "deliver", "rework", "accept", "reject", "fail", "withdraw");

    /** The nine calls that are not transitions, TAR-0004 section 3, in its order. */
    public static final List<String> OTHERS = List.of(
        "create", "update", "delete", "read", "read_text", "query", "annotate", "relate",
        "unrelate");

    /**
     * The two calls on the tags of a task, from the commission of sprint/210
     * (tag system v1): no transitions, and no calls of TAR-0004 section 3.
     */
    public static final List<String> TAGS = List.of("tag_attach", "tag_detach");

    /** Every call on REST: the names of section 3, and the two on the tags. */
    public static final List<String> REST = concat(concat(TRANSITIONS, OTHERS), TAGS);

    /** Every call on the assistant surface: {@code dispatch_<verb>}. */
    public static final List<String> MCP = REST.stream().map(v -> "dispatch_" + v).toList();

    /** One call's arguments: what stands at the top level, and what under {@code fields}. */
    public record Arguments(List<String> top, List<String> fields) {
    }

    /**
     * Concept section 3.2, row by row. "address" stands for the address,
     * "conflict_token", "receipt", "duration", "idempotency_key" and
     * "confirmation" for the transport artefacts of section 3.1.
     */
    public static final Map<String, Arguments> ARGUMENTS = arguments();

    private static Map<String, Arguments> arguments() {
        Map<String, Arguments> table = new LinkedHashMap<>();
        table.put("create", new Arguments(
            List.of("scope", "selector", "parent", "idempotency_key"),
            List.of("title", "apparatus", "text", "metadata")));
        table.put("update", new Arguments(List.of("address", "conflict_token"),
            List.of("title", "apparatus", "text", "metadata")));
        table.put("delete", new Arguments(List.of("address", "conflict_token"), List.of()));
        table.put("send", new Arguments(List.of("address", "conflict_token"), List.of()));
        table.put("recall", new Arguments(List.of("address", "conflict_token"), List.of()));
        table.put("claim", new Arguments(
            List.of("address", "duration", "idempotency_key"), List.of()));
        table.put("release", new Arguments(List.of("address", "receipt"), List.of("remark")));
        table.put("defer", new Arguments(List.of("address", "receipt"),
            List.of("not_before", "remark")));
        table.put("renew", new Arguments(List.of("address", "receipt", "duration"),
            List.of()));
        table.put("ask", new Arguments(List.of("address", "receipt"),
            List.of("question", "options", "free_text")));
        table.put("answer", new Arguments(List.of("address", "conflict_token"),
            List.of("option", "text")));
        table.put("hold", new Arguments(List.of("address", "receipt"),
            List.of("reason", "remark")));
        table.put("resume", new Arguments(List.of("address", "receipt", "duration"),
            List.of()));
        table.put("deliver", new Arguments(List.of("address", "receipt"),
            List.of("text", "metadata")));
        table.put("rework", new Arguments(List.of("address", "conflict_token"),
            List.of("remark")));
        table.put("accept", new Arguments(
            List.of("address", "conflict_token", "confirmation"), List.of()));
        table.put("reject", new Arguments(List.of("address"), List.of("remark")));
        table.put("fail", new Arguments(List.of("address", "receipt", "confirmation"),
            List.of("remark")));
        table.put("withdraw", new Arguments(
            List.of("address", "conflict_token", "confirmation"), List.of("remark")));
        table.put("read", new Arguments(List.of("address"), List.of()));
        table.put("read_text", new Arguments(List.of("address", "part"), List.of()));
        // "filters": the names are the service's; the page is the pagination
        // frame's (see the class comment).
        // The filter tag is the commission's of sprint/210: one token.
        table.put("query", new Arguments(
            List.of("scope", "selector", "state", "apparatus", "bracket", "address", "tag",
                "page_size", "after", "sort", "direction"),
            List.of()));
        table.put("annotate", new Arguments(List.of("address", "idempotency_key"),
            List.of("text", "part")));
        table.put("relate", new Arguments(List.of("address", "conflict_token"),
            List.of("curated_in")));
        table.put("unrelate", new Arguments(List.of("address", "conflict_token"), List.of()));
        // The commission names address and tag; the tag is what the call
        // writes, so it stands under fields, as curated_in does on relate.
        table.put("tag_attach", new Arguments(List.of("address"), List.of("tag")));
        table.put("tag_detach", new Arguments(List.of("address"), List.of("tag")));
        return Map.copyOf(table);
    }

    /** The arguments {@code remark} is mandatory on (concept section 3.2). */
    public static final List<String> REMARK_REQUIRED = List.of("fail", "reject", "rework");

    /**
     * Concept section 3.5, worded positively under DEC-0043 R21 (the concept
     * follows this wording). The line breaks of the document's code blocks are
     * its layout; a paragraph is a blank line.
     */
    public static final String CLAIM_DESCRIPTION =
        "Takes up one open task and makes you its holder. Moves the task from open to "
            + "active (intermediate). Call as an executor, naming the task by its address.\n\n"
            + "Returns a receipt and the end of your hold. Keep the receipt: every later "
            + "call on this task needs it.\n\n"
            + "The task's text comes from dispatch_read_text, part \"dispatch\": read it next, "
            + "before you start working.\n\n"
            + "Your hold lasts 30 minutes, or the duration you state; extend it with "
            + "dispatch_renew.";

    /** The sentence about the text in the description of a take-up. */
    public static final String THE_TEXT_SENTENCE =
        "The task's text comes from dispatch_read_text, part \"dispatch\": read it next, "
            + "before you start working.";

    /** The first entry of {@code next} after a claim. */
    public static final String FIRST_NEXT_CALL = "dispatch_read_text";
    public static final String FIRST_NEXT_DOES =
        "Reads the commission: the task's text, the work this claim takes on. Call it next, "
            + "before you start the work.";

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new java.util.ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }
}
