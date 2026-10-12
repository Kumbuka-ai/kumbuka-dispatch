/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.boundary;

import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import jakarta.enterprise.context.ApplicationScoped;
import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.List;

/**
 * Records every statement Hibernate prepares, unchanged, so a probe can ask
 * the planner about the statement the service actually issues rather than
 * about one written by hand to resemble it.
 *
 * <p>Test code only. It returns each statement as it received it, so it
 * changes nothing a test observes beyond the record it keeps.
 */
@PersistenceUnitExtension
@ApplicationScoped
public class RecordedStatements implements StatementInspector {

    private static final long serialVersionUID = 1L;

    private static final List<String> RECORDED = new ArrayList<>();

    @Override
    public String inspect(String sql) {
        synchronized (RECORDED) {
            RECORDED.add(sql);
        }
        return sql;
    }

    /** Forgets what was recorded so far. */
    public static void clear() {
        synchronized (RECORDED) {
            RECORDED.clear();
        }
    }

    /** The statements recorded since the last {@link #clear()}, in order. */
    public static List<String> recorded() {
        synchronized (RECORDED) {
            return List.copyOf(RECORDED);
        }
    }
}
