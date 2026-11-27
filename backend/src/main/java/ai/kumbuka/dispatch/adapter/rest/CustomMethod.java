/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.rest;

import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.Surface;
import ai.kumbuka.dispatch.surface.VerbName;
import jakarta.ws.rs.HttpMethod;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The routes of the REST surface: one per call, written by hand (C3.1).
 *
 * <p>The calls are those of the declaration, under the same names without the
 * {@code dispatch_} prefix the assistant surface carries. The routes are not
 * generated from the declaration; {@code RestRouteFormTest} holds the two
 * together: every declared call has its route here, in the form its effect
 * class names. A route is keyed by the call's name rather than by the
 * declaration's constant, so that a route without a call is a name that
 * resolves to none, which the test sees.
 *
 * <h2>The path form</h2>
 *
 * {@code /api/{scope}/{selector}} for a collection, {@code
 * /api/{scope}/{selector}/{number}} for an instance and {@code
 * /api/{scope}/{selector}/{number}.{sub}} for a task, and a call that is not
 * the plain read, write or delete of what the path names as a custom method in
 * colon notation after the last segment. A path template takes the whole
 * segment it appears in, so the colon is split off by {@link #split} rather
 * than routed by the framework.
 */
public enum CustomMethod {

    READ_DEFINITION("read_definition", HttpMethod.GET, Depth.COLLECTION, false),

    /**
     * A custom method on the collection: the plain POST on it is {@code
     * create}, which without a parent starts an instance with its first task.
     */
    START("start", HttpMethod.POST, Depth.COLLECTION, true),

    CONCLUDE("conclude", HttpMethod.POST, Depth.ITEM, true),
    WITHDRAW("withdraw", HttpMethod.POST, Depth.ITEM, true),

    /**
     * A custom method on the instance rather than a POST or DELETE on a
     * collection of references: an ended reference stays with the act that
     * ended it, and a DELETE would promise that it goes.
     */
    REFERENCE_ATTACH("reference_attach", HttpMethod.POST, Depth.ITEM, true),
    REFERENCE_DETACH("reference_detach", HttpMethod.POST, Depth.ITEM, true),

    /**
     * The plain POST on the collection, with and without a parent in the
     * body: the parent names the instance the task joins, and without one the
     * call starts an instance with the task as its first (operator,
     * 2026-11-24).
     */
    CREATE("create", HttpMethod.POST, Depth.COLLECTION, false),

    /** The plain PATCH on the address of a task. */
    UPDATE("update", "PATCH", Depth.ITEM, false),

    /** The plain DELETE on the address of a task: the draft goes. */
    DELETE("delete", HttpMethod.DELETE, Depth.ITEM, false),

    SEND("send", HttpMethod.POST, Depth.ITEM, true);

    /** Where a route acts. */
    public enum Depth {
        /** {@code /api/{scope}/{selector}}: a selector in a scope. */
        COLLECTION,
        /** {@code /api/{scope}/{selector}/{id}}: one object, an instance or a task. */
        ITEM
    }

    /** The separator of a custom method. It appears in no address part. */
    public static final char SEPARATOR = ':';

    private final String call;
    private final String method;
    private final Depth depth;
    private final boolean custom;

    CustomMethod(String call, String method, Depth depth, boolean custom) {
        this.call = call;
        this.method = method;
        this.depth = depth;
        this.custom = custom;
    }

    /** The call this route makes, by its name on this surface. */
    public String call() {
        return call;
    }

    public String method() {
        return method;
    }

    public Depth depth() {
        return depth;
    }

    /** Whether the call is named in colon notation after the address. */
    public boolean custom() {
        return custom;
    }

    /** The declared call this route makes. */
    public ProcessVerb verb() {
        return ProcessVerb.byName(Surface.REST, call).orElseThrow(() ->
            new IllegalStateException("the route " + call + " names no declared call"));
    }

    /**
     * The route a request takes, if there is one.
     *
     * @param verb the colon segment's verb; absent where the segment has none
     */
    public static Optional<CustomMethod> find(String method, Depth depth, @Nullable String verb) {
        return Arrays.stream(values())
            .filter(r -> r.method.equals(method) && r.depth == depth
                && (verb == null ? !r.custom : r.custom && r.call.equals(verb)))
            .findFirst();
    }

    /**
     * The route of a request, by its method and its path below the base;
     * empty where none takes it. For the edge, which words a refusal against
     * the call the request became.
     */
    public static Optional<CustomMethod> of(String method, String path) {
        return depthOf(path).flatMap(depth -> {
            String[] segments = segments(path);
            return find(method, depth, split(segments[segments.length - 1])[1]);
        });
    }

    /** The depth of a REST path: a collection, an object, or neither. */
    public static Optional<Depth> depthOf(String path) {
        String[] segments = segments(path);
        if (segments.length < 3 || !"api".equals(segments[0])) {
            return Optional.empty();
        }
        return switch (segments.length) {
            case 3 -> Optional.of(Depth.COLLECTION);
            case 4 -> Optional.of(Depth.ITEM);
            default -> Optional.empty();
        };
    }

    /**
     * The calls carried at one depth, by their names on a surface, for the
     * {@code data.accepts} of a verb refused there (C3.3).
     */
    public static List<VerbName> accepts(Depth depth, Surface surface) {
        return Arrays.stream(values())
            .filter(r -> r.depth == depth)
            .map(r -> VerbName.of(r.verb(), surface))
            .toList();
    }

    /** The methods the routes at one depth are reached with, for an {@code Allow}. */
    public static Optional<String> allow(Depth depth) {
        List<String> methods = Arrays.stream(values())
            .filter(r -> r.depth == depth)
            .map(CustomMethod::method)
            .distinct()
            .toList();
        return methods.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", methods));
    }

    /**
     * A path segment taken apart at its last colon: the address part and the
     * verb, or the segment and null where it carries no colon.
     */
    public static @Nullable String[] split(String segment) {
        int at = segment.lastIndexOf(SEPARATOR);
        return at < 0
            ? new String[] {segment, null}
            : new String[] {segment.substring(0, at), segment.substring(at + 1)};
    }

    private static String[] segments(String path) {
        String relative = path.startsWith("/") ? path.substring(1) : path;
        if (relative.endsWith("/")) {
            relative = relative.substring(0, relative.length() - 1);
        }
        return relative.split("/", -1);
    }
}
