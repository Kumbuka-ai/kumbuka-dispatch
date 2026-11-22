/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.tenancy;

import ai.kumbuka.dispatch.domain.Act;
import ai.kumbuka.dispatch.domain.Instance;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManagerFactory;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service starts under the test configuration and nothing besides it,
 * against a database Flyway builds with the store line, {@code db/store} V1 to
 * V5, and Hibernate's validation of the entities {@code Instance} and {@code
 * Act} against that schema is green.
 *
 * <p>The start is the statement: the service boots with {@code validate}, so a
 * mapping that does not match the store line refuses the start, and every test
 * of this class with it. What the class reads besides is what tells this start
 * from another one: the history of the migration tool holds the five versions
 * of the store line and nothing of the line before it, the tables of that line
 * are not there, and the persistence unit carries the two entities.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class StoreLineStartIT {

    @Inject EntityManagerFactory units;

    @Test
    void the_service_starts_on_the_store_line_with_its_schema_validated() throws SQLException {
        var config = ConfigProvider.getConfig();
        assertThat(config.getValue("quarkus.hibernate-orm.database.generation", String.class))
            .as("Hibernate validates the mapping against the schema and writes none")
            .isEqualTo("validate");
        assertThat(config.getValue("quarkus.flyway.locations", String.class))
            .as("Flyway migrates the store line and no other")
            .isEqualTo("classpath:db/store");

        try (Connection c = Db.asAdmin()) {
            assertThat(strings(c, "SELECT version FROM dispatch.flyway_schema_history "
                    + "WHERE success AND version IS NOT NULL ORDER BY installed_rank"))
                .as("the database holds the five versions of the store line, in their order, and "
                    + "no version of the line before it")
                .containsExactly("1", "2", "3", "4", "5");
            assertThat(strings(c, "SELECT table_name FROM information_schema.tables "
                    + "WHERE table_schema = 'dispatch' AND table_name IN "
                    + "('instance', 'act', 'task_text', 'task_tag', 'task_idempotency_key') "
                    + "ORDER BY table_name"))
                .as("the store line's instance and act are there, and none of the tables only "
                    + "the line before it built")
                .containsExactly("act", "instance");
            c.rollback();
        }

        assertThat(List.of(units.getMetamodel().entity(Instance.class).getName(),
                units.getMetamodel().entity(Act.class).getName()))
            .as("the persistence unit maps the two entities the start validated")
            .containsExactly("Instance", "Act");

        given().when().get("/q/health/ready").then().statusCode(200);
    }

    private static List<String> strings(Connection c, String sql) throws SQLException {
        List<String> found = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                found.add(rs.getString(1));
            }
        }
        return found;
    }
}
