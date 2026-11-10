/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import org.eclipse.microprofile.config.ConfigProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A fingerprint of everything a call of the verb surface can write, read as
 * the administrator across every tenant: the rows of {@code task}, {@code
 * task_text}, {@code task_idempotency_key} and {@code task_tag}, the latest
 * change of a task, and the bracket counters. Two equal fingerprints around a
 * call say it wrote nothing.
 */
public final class Writes {

    private Writes() {
    }

    public static String snapshot() {
        var config = ConfigProvider.getConfig();
        try (Connection c = DriverManager.getConnection(
                config.getValue("test.db.url", String.class),
                config.getValue("test.db.admin.username", String.class),
                config.getValue("test.db.admin.password", String.class));
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT "
                 + "(SELECT count(*) FROM dispatch.task), "
                 + "(SELECT coalesce(max(updated_at)::text, '-') FROM dispatch.task), "
                 + "(SELECT count(*) FROM dispatch.task_text), "
                 + "(SELECT count(*) FROM dispatch.task_idempotency_key), "
                 + "(SELECT coalesce(sum(next_number), 0) FROM dispatch.selector), "
                 + "(SELECT count(*) FROM dispatch.task_tag)")) {
            rs.next();
            return "tasks=" + rs.getLong(1) + " changed=" + rs.getString(2)
                + " texts=" + rs.getLong(3) + " keys=" + rs.getLong(4)
                + " numbers=" + rs.getLong(5) + " tags=" + rs.getLong(6);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
