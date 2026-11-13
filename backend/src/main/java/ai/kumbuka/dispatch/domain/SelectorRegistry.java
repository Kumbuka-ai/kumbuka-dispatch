/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.repository.SelectorRepository;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The declared bracket names of a scope.
 *
 * <p>There is no method here that creates a selector as a side effect of using
 * one, and that absence is the class's main content. A selector is declared
 * deliberately — through the interface, and for our own estate through a
 * migration — because a name that comes into existence by being typed cannot
 * carry what a selector later has to carry, and because a typo would otherwise
 * open a namespace nobody meant to open.
 */
@ApplicationScoped
@TenantBound
public class SelectorRegistry {

    /*
     * Logs nothing (DEC-0051, C4): a refusal here is a typed exception the
     * surface logs by its code, and a selector is a name the caller wrote.
     */

    @Inject SelectorRepository selectors;

    /**
     * Refuses unless the selector is declared in this scope and not withdrawn.
     *
     * <p>The two refusals are told apart on purpose. "Never declared" is a
     * caller naming something that does not exist; "withdrawn" is a caller
     * naming something that did, whose addresses are still valid and readable.
     * Collapsing them into "unknown selector" would make a withdrawal look
     * like a typo.
     */
    @Transactional
    public Selector requireDeclared(UUID scopeId, String name) {
        Selector selector = find(scopeId, name).orElseThrow(() -> notDeclared(name));
        refuseIfWithdrawn(selector);
        return selector;
    }

    /**
     * Refuses a withdrawn selector, with the refusal {@link #requireDeclared}
     * gives. For a caller that holds the selector's row lock and must decide
     * on the row as it stands under it, not on what it read before.
     */
    static void refuseIfWithdrawn(Selector selector) {
        if (Boolean.TRUE.equals(selector.withdrawn)) {
            throw new DispatchException(DispatchException.Reason.SELECTOR_WITHDRAWN,
                "selector '" + selector.name + "' is withdrawn. Addresses already issued under it "
                    + "remain readable; no new task is numbered under it.");
        }
    }

    private static DispatchException notDeclared(String name) {
        return new DispatchException(
        DispatchException.Reason.SELECTOR_NOT_DECLARED,
        "selector '" + name + "' is not declared in this scope. Bracket names are "
            + "declared before use, never by first use: a typo must not silently "
            + "open a namespace.");
    }

    @Transactional
    public List<Selector> declared(UUID scopeId) {
        return selectors.declared(scopeId);
    }

    /**
     * Withdraws a selector that was never used.
     *
     * <p>Withdrawal is a status and never a deletion, because every address
     * ever issued under the name depends on it. A selector that HAS been used
     * cannot be withdrawn at all — the tasks under it would be left
     * pointing at a name the registry disowns.
     *
     * <p>Decided under the selector's row lock, which the numbering of a
     * bracket root takes as well: a root being created is counted once its
     * transaction committed, and a root created after the withdrawal finds the
     * selector withdrawn on the row it locked. A child needs its root, so
     * nothing is numbered under a selector withdrawn this way.
     */
    @Transactional
    public Selector withdraw(UUID scopeId, String name) {
        Selector selector = selectors.lock(scopeId, name).orElseThrow(() -> notDeclared(name));
        refuseIfWithdrawn(selector);
        long used = selectors.tasksUnder(scopeId, name);

        if (used > 0) {
            throw new DispatchException(DispatchException.Reason.SELECTOR_IN_USE,
                "selector '" + name + "' carries " + used + " task(s) and cannot be "
                    + "withdrawn. Only a never-used selector may be; the addresses under "
                    + "a used one depend on the name continuing to mean what it meant.");
        }
        selector.withdrawn = Boolean.TRUE;
        selectors.flush();
        return selector;
    }

    private Optional<Selector> find(UUID scopeId, String name) {
        return selectors.find(scopeId, name);
    }
}
