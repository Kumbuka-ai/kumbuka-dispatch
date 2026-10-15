/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

/**
 * The effect class of a call: the form an HTTP request takes for it, drawn
 * from the platform's closed set (ADR-0039).
 *
 * <p>The names are the router's, character for character: {@code
 * Kumbuka-ai/platform}, {@code main}, {@code
 * router/src/main/java/ai/kumbuka/router/verb/VerbCatalog.java}, enum {@code
 * EffectClass}, read 2026-10-15. Copied and not imported, as the not-found
 * message is: this service does not compile against the router. The router
 * reads them from the declaration without a mapping, and a class it does not
 * know is a load error there, never a pass-through.
 *
 * <p>The whole set stands here, the classes no call of this service takes
 * included, so that the set this service names is the set the router knows.
 */
public enum EffectClass {

    /** GET on an item URI. */
    ITEM_READ,
    /** GET on a collection URI. */
    COLLECTION_READ,
    /** GET on the scheme URI itself: a verb the router answers. */
    SCHEME_READ,
    /** GET on a scope URI. */
    SCOPE_READ,
    /** Custom method in colon notation on the scope URI. */
    SCOPE_COMPOUND,
    /** POST on a collection, 201 with Location. */
    CREATE,
    /** PATCH on an item with If-Match. */
    FIELD_WRITE,
    /** POST or DELETE on a sub-collection. */
    GRAPH_ADD_OR_REMOVE,
    /** Custom method in colon notation on the item URI. */
    ITEM_TRANSITION,
    /** GET on the item URI with a call in colon notation. */
    ITEM_READ_CALL,
    /** DELETE on the item URI: the object goes. */
    ITEM_DELETE,
    /** Custom method in colon notation on the collection URI. */
    COLLECTION_COMPOUND
}
