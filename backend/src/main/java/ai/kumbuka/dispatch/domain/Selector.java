/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

/**
 * A selector of a scope: a row of {@code dispatch.selector} of the store line,
 * read so that a definition is found by the name a caller gives, and the
 * counter its instances are numbered from.
 *
 * <p>No call writes a selector, a migration of the installation declares it.
 * Its counter is the one column a call changes: {@code start} takes the next
 * number under a lock of the selector's row and moves the counter on, so two
 * starts under one selector never take one number and leave no gap. The
 * update names that column alone, the only one the runtime role may change.
 */
@Entity
@DynamicUpdate
@Table(name = "selector", schema = "dispatch")
public class Selector extends DefinitionRow {

    @Column(name = "name", nullable = false, updatable = false)
    private String name;

    @Column(name = "next_number", nullable = false)
    private long nextNumber;

    /**
     * The number the next instance of this selector takes, handed out once:
     * the counter moves on. Called only on the row as it stands under its lock.
     */
    long takeNumber() {
        return nextNumber++;
    }
}
