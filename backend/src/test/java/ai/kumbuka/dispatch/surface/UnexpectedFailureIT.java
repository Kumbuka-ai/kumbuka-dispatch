/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A failure that is ours, on both surfaces and in the log (C8.3, DEC-0051).
 *
 * <p>Each adapter has a catch-all: everything that is no refusal and no
 * answer of the framework is {@code UNEXPECTED_FAILURE} 500 with {@code
 * data.reference}, a fresh UUID. The failure is logged once at ERROR by
 * {@link UnexpectedFailures}, with the reference and the class names of the
 * exception chain, and never with the exception object: a stack trace carries
 * what the failing code held, and a log shipper carries it out of the
 * container.
 *
 * <p>The verb surface is replaced for the run by one that fails with a
 * caller's value in its message, so the failure is provoked where a real one
 * would arise, below the adapter, and the answer is read for that value.
 *
 * <p>RED STATE, observed 2026-10-16 against the tree of the guards' commit, with
 * this class copied in: on both surfaces the log record carried the exception
 * object.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class UnexpectedFailureIT {

    private static final String MARKER = "zzecho4411";
    private static final String REFERENCE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Inject TestIdentityAssociation identity;

    private final List<LogRecord> recorded = new ArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord line) {
            recorded.add(line);
        }

        @Override
        public void flush() {
            // Nothing is buffered; the list IS the sink.
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    };
    private final Logger watched = Logger.getLogger(UnexpectedFailures.class.getName());

    @BeforeEach
    void failBelowTheAdapter() {
        VerbSurface failing = mock(VerbSurface.class);
        when(failing.read(any(), any())).thenThrow(
            new IllegalStateException("broke on " + MARKER,
                new IllegalArgumentException("deeper, " + MARKER)));
        QuarkusMock.installMockForType(failing, VerbSurface.class);
        SurfaceFixture.asConsole(identity);
        handler.setLevel(Level.ALL);
        watched.addHandler(handler);
    }

    @AfterEach
    void stopListening() {
        watched.removeHandler(handler);
    }

    @Test
    void over_rest_an_unexpected_failure_answers_500_with_a_reference_and_no_value() {
        Response answer = given().get(SurfaceFixture.item("7.0"));

        assertThat(answer.statusCode()).isEqualTo(500);
        assertThat(answer.jsonPath().getString("reason")).isEqualTo("UNEXPECTED_FAILURE");
        assertThat(answer.jsonPath().getString("data.reference")).matches(REFERENCE);
        assertThat(answer.asString()).doesNotContain(MARKER);
        loggedOnce(answer.jsonPath().getString("data.reference"));
    }

    @Test
    void over_mcp_an_unexpected_failure_is_a_refused_call_with_a_reference_and_no_value() {
        Response answer = Mcp.call("dispatch_read",
            Map.of("address", "dispatch://" + SurfaceFixture.SCOPE + "/sprint/7.0"));

        Map<String, Object> refusal = Mcp.refusal(answer);
        assertThat(refusal.get("reason")).isEqualTo("UNEXPECTED_FAILURE");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) refusal.get("data");
        assertThat(String.valueOf(data.get("reference"))).matches(REFERENCE);
        assertThat(answer.asString()).doesNotContain(MARKER);
        loggedOnce(String.valueOf(data.get("reference")));
    }

    /** A route the framework does not serve is the framework's answer, unchanged. */
    @Test
    void the_framework_s_own_answer_passes_the_catch_all_unchanged() {
        Response answer = given().get("/api/" + SurfaceFixture.SCOPE + "/sprint/7.0/more");

        assertThat(answer.statusCode()).isEqualTo(404);
        assertThat(answer.asString()).doesNotContain("UNEXPECTED_FAILURE");
    }

    /**
     * The reference the caller is told to report is in one log line at ERROR,
     * with the class names of the failure and without the failure itself.
     */
    private void loggedOnce(String reference) {
        List<LogRecord> lines = recorded.stream()
            .filter(line -> formatted(line).contains(reference))
            .toList();
        assertThat(lines).as("one line carries the reference").hasSize(1);
        LogRecord line = lines.get(0);
        assertThat(line.getLevel()).isEqualTo(Level.SEVERE);
        assertThat(formatted(line))
            .as("the line names what failed by its class names")
            .contains(IllegalStateException.class.getName())
            .contains(IllegalArgumentException.class.getName())
            .doesNotContain(MARKER);
        assertThat(line.getThrown())
            .as("no exception object is handed to the logger (DEC-0051)")
            .isNull();
    }

    /** The line's text and its parameters, which is all a log line carries out. */
    private static String formatted(LogRecord line) {
        Object[] parameters = line.getParameters();
        return line.getMessage() + " "
            + (parameters == null ? "" : java.util.Arrays.toString(parameters));
    }
}
