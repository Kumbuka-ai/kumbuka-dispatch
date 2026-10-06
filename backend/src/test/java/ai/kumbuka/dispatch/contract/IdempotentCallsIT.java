package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.surface.Mcp;
import ai.kumbuka.dispatch.surface.SurfaceFixture;
import ai.kumbuka.dispatch.surface.Writes;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The calls that take an idempotency key, on the surface: {@code create},
 * {@code claim} and {@code claim_next}.
 *
 * <p>A repeated create creates nothing and answers the first task. A
 * repeated claim answers the same task with a new receipt, with which the next
 * write succeeds, while the earlier receipt is refused; a repeated draw draws
 * nothing second (dispatch 200.7, part A2, criterion 8, on the wire).
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class IdempotentCallsIT {

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    void a_repeated_create_creates_nothing_and_answers_the_first_task() {
        Map<String, Object> create = Map.of("scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR, "idempotency_key", "create-once",
            "fields", Map.of("title", "created once", "apparatus", "code"));
        String first = String.valueOf(Mcp.answer(Mcp.call("dispatch_create", create))
            .get("address"));
        String before = Writes.snapshot();

        String again = String.valueOf(Mcp.answer(Mcp.call("dispatch_create", create))
            .get("address"));

        assertThat(again).isEqualTo(first);
        assertThat(Writes.snapshot()).as("the repeat wrote nothing").isEqualTo(before);
    }

    @Test
    void a_repeated_claim_answers_the_same_task_and_a_new_receipt() {
        String address = SurfaceFixture.address(SurfaceFixture.open("claimed twice", "code"));
        SurfaceFixture.asExecutor(identity);
        Map<String, Object> claim = Map.of("address", address,
            "idempotency_key", "claim-once");

        Map<String, Object> first = Mcp.answer(Mcp.call("dispatch_claim", claim));
        Map<String, Object> again = Mcp.answer(Mcp.call("dispatch_claim", claim));

        assertThat(again.get("address")).isEqualTo(first.get("address"));
        assertThat(again.get("receipt")).isNotEqualTo(first.get("receipt"));
        assertThat(Mcp.field(again, "lease_expires_at"))
            .as("the hold is not taken again: its end stays")
            .isEqualTo(Mcp.field(first, "lease_expires_at"));
        assertThat(Mcp.reason(Mcp.call("dispatch_renew", Map.of("address", address,
            "receipt", first.get("receipt")))))
            .as("the earlier receipt no longer holds")
            .isEqualTo("RECEIPT_WRONG");
        Mcp.answer(Mcp.call("dispatch_renew", Map.of("address", address,
            "receipt", again.get("receipt"))));
    }

    @Test
    void a_repeated_draw_draws_nothing_second() {
        SurfaceFixture.open("drawn once", "idem");
        String other = SurfaceFixture.address(SurfaceFixture.open("left open", "idem"));
        SurfaceFixture.asExecutor(identity);
        Map<String, Object> draw = Map.of("scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR, "apparatus", List.of("idem"),
            "idempotency_key", "draw-once");

        Map<String, Object> first = Mcp.answer(Mcp.call("dispatch_claim_next", draw));
        Map<String, Object> again = Mcp.answer(Mcp.call("dispatch_claim_next", draw));

        assertThat(again.get("address")).isEqualTo(first.get("address"));
        assertThat(Mcp.field(Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", other))), "state")).isEqualTo("open");
    }
}
