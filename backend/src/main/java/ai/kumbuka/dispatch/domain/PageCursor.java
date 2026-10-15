/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Where a listing continues: the listing it belongs to and the last task its
 * page handed out.
 *
 * <h2>Form, version {@code v1}</h2>
 *
 * Nine fields separated by {@code |}, base64url-encoded without padding:
 * the version, the scope mark, the selector, the filter fingerprint, the sort
 * key, the direction, and the number, the sub and the instant of creation of
 * the last task. No signature and no secret: the cursor is opaque by
 * convention, not by cryptography, and what it carries is checked against the
 * call it arrives with, never trusted.
 *
 * <h2>What binds it</h2>
 *
 * A cursor continues one listing and no other: the same scope, selector,
 * filter, sort key and direction. One that arrives with a different call is
 * refused as foreign rather than read as a position in another list, which
 * would answer a page that skips or repeats tasks without a word. The scope
 * is carried as a mark of its technical identity rather than its name, so a
 * cursor of one tenant does not continue a listing of another tenant's scope
 * of the same name, and the identity itself does not reach the caller.
 *
 * <p>All three sort values of the last task travel, whatever the sort: they
 * are the keyset the next page starts after, and the instant is what a sort by
 * creation compares first.
 */
public record PageCursor(Binding binding, Position position) {

    /** The version this kernel writes and reads. */
    static final String VERSION = "v1";

    private static final String SEPARATOR = "|";
    private static final int FIELDS = 9;
    private static final int MARK_LENGTH = 16;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /**
     * The listing a cursor belongs to.
     *
     * @param scope     the mark of the scope's technical identity
     * @param selector  the bracket kind
     * @param filter    the fingerprint of the filter
     * @param sort      the sort key
     * @param direction the direction of the sort
     */
    public record Binding(String scope, String selector, String filter, SortKey sort,
                          SortKey.Direction direction) {

        public Binding {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(selector, "selector");
            Objects.requireNonNull(filter, "filter");
            Objects.requireNonNull(sort, "sort");
            Objects.requireNonNull(direction, "direction");
        }

        /** The listing a call asks for. */
        public static Binding of(UUID scopeId, String selector, TaskFilter filter,
                                 PageRequest page) {
            return new Binding(mark(scopeId.toString()), selector, mark(filter.canonical()),
                page.sort(), page.direction());
        }

        /** What differs from another binding, by name, in a fixed order. */
        List<String> differences(Binding other) {
            List<String> differ = new ArrayList<>();
            if (!scope.equals(other.scope)) {
                differ.add("scope");
            }
            if (!selector.equals(other.selector)) {
                differ.add("selector");
            }
            if (!filter.equals(other.filter)) {
                differ.add("filter");
            }
            if (sort != other.sort) {
                differ.add("sort");
            }
            if (direction != other.direction) {
                differ.add("direction");
            }
            return differ;
        }
    }

    /**
     * The sort values of the last task of a page: what the next page starts
     * after.
     */
    public record Position(int number, int sub, Instant createdAt) {

        public Position {
            Objects.requireNonNull(createdAt, "createdAt");
        }

        /** The position of a task. */
        public static Position of(Task task) {
            return new Position(task.number, task.sub, task.createdAt);
        }
    }

    public PageCursor {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(position, "position");
    }

    /** The cursor as it travels. */
    public String encode() {
        String plain = String.join(SEPARATOR, VERSION, binding.scope, binding.selector,
            binding.filter, binding.sort.wireName(), binding.direction.wireName(),
            String.valueOf(position.number), String.valueOf(position.sub),
            position.createdAt.toString());
        return ENCODER.encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads a cursor as a caller sent it.
     *
     * @throws DispatchException {@code CURSOR_MALFORMED} for anything this kernel
     *                           did not write, an earlier or later version included
     */
    public static PageCursor decode(String raw) {
        try {
            String plain = new String(DECODER.decode(raw), StandardCharsets.UTF_8);
            String[] f = plain.split("\\|", -1);
            if (f.length != FIELDS || !VERSION.equals(f[0])) {
                throw malformed(raw);
            }
            Binding binding = new Binding(f[1], f[2], f[3], sortOf(f[4], raw),
                directionOf(f[5], raw));
            int number = Integer.parseInt(f[6]);
            int sub = Integer.parseInt(f[7]);
            if (number < 1 || sub < 0 || binding.scope.isEmpty() || binding.selector.isEmpty()
                    || binding.filter.isEmpty()) {
                throw malformed(raw);
            }
            return new PageCursor(binding, new Position(number, sub, Instant.parse(f[8])));
        } catch (IllegalArgumentException | DateTimeParseException unreadable) {
            throw malformed(raw);
        }
    }

    /**
     * The position this cursor continues from, where it belongs to the listing
     * {@code expected} describes.
     *
     * @throws DispatchException {@code CURSOR_FOREIGN}, naming what differs
     */
    public Position continuing(Binding expected) {
        List<String> differ = binding.differences(expected);
        if (!differ.isEmpty()) {
            throw new DispatchException(DispatchException.Reason.CURSOR_FOREIGN,
                "the cursor continues a listing with another " + String.join(", ", differ)
                    + " than the one asked for.",
                differ);
        }
        return position;
    }

    private static SortKey sortOf(String wireName, String raw) {
        for (SortKey key : SortKey.values()) {
            if (key.wireName().equals(wireName)) {
                return key;
            }
        }
        throw malformed(raw);
    }

    private static SortKey.Direction directionOf(String wireName, String raw) {
        for (SortKey.Direction d : SortKey.Direction.values()) {
            if (d.wireName().equals(wireName)) {
                return d;
            }
        }
        throw malformed(raw);
    }

    private static DispatchException malformed(String raw) {
        return new DispatchException(DispatchException.Reason.CURSOR_MALFORMED,
            "the cursor is not one this service handed out.", List.of(String.valueOf(raw)));
    }

    /** A short, stable, one-way mark of a value. */
    static String mark(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return ENCODER.encodeToString(digest).substring(0, MARK_LENGTH);
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", absent);
        }
    }
}
