package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What happens between two apparatuses while an exchange is being worked.
 *
 * <p>Showing and taking up are two verbs; the receipt is the holder; the
 * handover is a pull request the operator merges; the clock sits on the claim
 * and writes nothing. Each of those is a decision that the obvious alternative
 * would have got wrong, and each is asserted here by what it REFUSES.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ExchangeMechanicsIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final Duration CLAIM = Duration.ofHours(1);

    static final Actor EXECUTOR = new Actor("probe-executor", Actor.Kind.EXECUTOR);
    static final Actor OTHER_EXECUTOR = new Actor("probe-executor-2", Actor.Kind.EXECUTOR);
    static final Actor CONSOLE = new Actor("probe-console", Actor.Kind.CONSOLE);

    @Inject ExchangeService exchanges;
    @Inject TenantContext tenantContext;

    private UUID tenant;
    private AutoCloseable binding;

    @BeforeEach
    void freshTenant() {
        tenant = UUID.randomUUID();
        DomainFixture.declareSelector(tenant, SCOPE, "sprint");
        binding = tenantContext.bind(tenant);
    }

    @AfterEach
    void unbind() throws Exception {
        binding.close();
    }

    // -----------------------------------------------------------------------
    // Showing and taking up are two verbs
    // -----------------------------------------------------------------------

    @Test
    void viewing_writes_nothing() {
        Exchange sent = openAndSend("a commission somebody is looking at");
        Instant before = sent.updatedAt;
        ExchangeStatus statusBefore = sent.status();

        exchanges.view(SCOPE, at(sent), EXECUTOR);
        exchanges.view(SCOPE, at(sent), CONSOLE);

        Exchange after = exchanges.read(SCOPE, at(sent));
        assertThat(after.status())
            .as("looking at the queue must not commit anybody to anything. The predecessor "
                + "merged showing and taking up and described itself as the only query in "
                + "the service that writes — an honest description of a construction fault")
            .isEqualTo(statusBefore);
        assertThat(after.updatedAt)
            .as("and the row is untouched, measured on the timestamp rather than assumed")
            .isEqualTo(before);
        assertThat(after.effectiveHolder(Instant.now()))
            .as("viewing awards nothing")
            .isNull();
    }

    @Test
    void the_view_withholds_the_body_from_an_executor_that_has_not_claimed() {
        Exchange sent = openAndSend("a commission with a body nobody has claimed");

        ExchangeView asExecutor = exchanges.view(SCOPE, at(sent), EXECUTOR);
        assertThat(asExecutor.body())
            .as("enough to refuse, not enough to work. This is the first of three bolts "
                + "against the race: a loser cannot have started, because it never had "
                + "anything to start from")
            .isNull();
        assertThat(asExecutor.title())
            .as("but enough to decide against taking it up")
            .isEqualTo("a commission with a body nobody has claimed");

        assertThat(exchanges.view(SCOPE, at(sent), CONSOLE).body())
            .as("a console identity reads it, because operators read commissions as a "
                + "matter of course. Without this half the guarantee would just be a "
                + "switched-off feature")
            .isNotNull();
    }

    @Test
    void the_body_arrives_with_the_claim() {
        Exchange sent = openAndSend("a commission about to be claimed");
        exchanges.takeup(SCOPE, at(sent), EXECUTOR, CLAIM);

        assertThat(exchanges.view(SCOPE, at(sent), EXECUTOR).body())
            .as("taking it up is what buys the body")
            .isNotNull();
        assertThat(exchanges.view(SCOPE, at(sent), OTHER_EXECUTOR).body())
            .as("and only for the holder — another executor still sees none")
            .isNull();
    }

    // -----------------------------------------------------------------------
    // The receipt
    // -----------------------------------------------------------------------

    @Test
    void the_receipt_is_minted_by_the_service_and_is_opaque() {
        Exchange sent = openAndSend("a commission to be claimed");
        var claim = exchanges.takeup(SCOPE, at(sent), EXECUTOR, CLAIM);

        assertThat(claim.receipt())
            .as("the receipt is minted after the award, so it cannot collide: several "
                + "executor instances on one machine would collide on any name they could "
                + "derive for themselves, and the service would see one holder where there "
                + "are two")
            .isNotBlank()
            .doesNotContain(EXECUTOR.subject())
            .doesNotContain(sent.address());
    }

    @Test
    void a_handover_draft_needs_the_receipt() {
        Exchange sent = openAndSend("a commission being worked");
        var claim = exchanges.takeup(SCOPE, at(sent), EXECUTOR, CLAIM);

        assertThatThrownBy(() -> exchanges.writeHandoverDraft(SCOPE, at(sent), EXECUTOR,
            null, "an answer", null))
            .as("the subject alone is not enough: several runs can share one service "
                + "identity, and the receipt is what tells the run that won the award "
                + "from one that merely looks like it")
            .isInstanceOfSatisfying(DispatchException.class, x -> assertThat(x.reason())
                .isEqualTo(DispatchException.Reason.CLAIM_REQUIRED));

        assertThatThrownBy(() -> exchanges.writeHandoverDraft(SCOPE, at(sent), EXECUTOR,
            "a-receipt-nobody-issued", "an answer", null))
            .isInstanceOfSatisfying(DispatchException.class, x -> assertThat(x.reason())
                .isEqualTo(DispatchException.Reason.RECEIPT_MISMATCH));

        Exchange written = exchanges.writeHandoverDraft(SCOPE, at(sent), EXECUTOR,
            claim.receipt(), "an answer", null);
        assertThat(written.handoverBody())
            .as("and with the issued receipt it goes through, so the refusals were the "
                + "receipt check and not a missing code path")
            .isEqualTo("an answer");
    }

    // -----------------------------------------------------------------------
    // The handover as a pull request
    // -----------------------------------------------------------------------

    @Test
    void the_draft_is_overwritten_wholesale_while_the_exchange_stays_active() {
        Exchange sent = openAndSend("a commission with reworked answers");
        var claim = exchanges.takeup(SCOPE, at(sent), EXECUTOR, CLAIM);

        exchanges.writeHandoverDraft(SCOPE, at(sent), EXECUTOR, claim.receipt(),
            "first attempt", null);
        Exchange second = exchanges.writeHandoverDraft(SCOPE, at(sent), EXECUTOR,
            claim.receipt(), "second attempt", null);

        assertThat(second.handoverBody())
            .as("rework is the normal case, not the exception. The draft is replaced "
                + "wholesale — there is no verb that appends to one, and the intermediate "
                + "rounds do not survive in the document. That is the deliberate trade: "
                + "nobody needs a wrong handover text kept")
            .isEqualTo("second attempt");
        assertThat(second.status())
            .as("and the exchange stays active throughout — no new object, no addendum, "
                + "no reopening")
            .isEqualTo(ExchangeStatus.ACTIVE);
    }

    @Test
    void an_executor_cannot_ratify_and_a_console_identity_can() {
        Exchange sent = openAndSend("a commission awaiting approval");
        var claim = exchanges.takeup(SCOPE, at(sent), EXECUTOR, CLAIM);
        exchanges.writeHandoverDraft(SCOPE, at(sent), EXECUTOR, claim.receipt(),
            "the answer", null);

        assertThatThrownBy(() -> exchanges.ratify(SCOPE, at(sent), EXECUTOR))
            .as("ratification is the operator's own act. An executor that could approve "
                + "its own answer would be reviewing itself — and the refusal carries its "
                + "OWN reason, because the transition IS permitted from this state, just "
                + "not to this caller")
            .isInstanceOfSatisfying(DispatchException.class, x -> assertThat(x.reason())
                .isEqualTo(DispatchException.Reason.RATIFICATION_NOT_PERMITTED));

        Exchange ratified = exchanges.ratify(SCOPE, at(sent), CONSOLE);
        assertThat(ratified.status())
            .as("and the console identity is not refused, so the refusal above is about "
                + "the capacity rather than about a missing path")
            .isEqualTo(ExchangeStatus.RETURNED);
        assertThat(ratified.ratifiedAt()).isNotNull();
    }

    @Test
    void ratification_takes_no_answer_text() {
        // A signature that accepted the text would let one call be both the
        // writing and the approving of it, which is not a review. Read from
        // the class so that adding such a parameter breaks this.
        boolean takesText = java.util.Arrays.stream(ExchangeService.class.getMethods())
            .filter(m -> m.getName().equals("ratify"))
            .anyMatch(m -> java.util.Arrays.stream(m.getParameterTypes())
                .filter(String.class::equals).count() > 0);

        assertThat(takesText)
            .as("ratification freezes an answer that is already there; it does not create "
                + "one. A String parameter here would be the text coming in with the "
                + "approval")
            .isFalse();
    }

    @Test
    void a_ratified_exchange_takes_no_further_handover() {
        Exchange sent = openAndSend("a commission already answered");
        var claim = exchanges.takeup(SCOPE, at(sent), EXECUTOR, CLAIM);
        exchanges.writeHandoverDraft(SCOPE, at(sent), EXECUTOR, claim.receipt(),
            "the answer", null);
        exchanges.ratify(SCOPE, at(sent), CONSOLE);

        assertThatThrownBy(() -> exchanges.writeHandoverDraft(SCOPE, at(sent), EXECUTOR,
            claim.receipt(), "a second answer", null))
            .as("bolt three: a state precondition that does not depend on who is asking. "
                + "It is the cover for several runs sharing one service identity, where a "
                + "holder check passes for both — and a receipt is a bearer token that "
                + "instances on one machine can read from a shared filesystem")
            .isInstanceOfSatisfying(DispatchException.class, x -> assertThat(x.reason())
                .isEqualTo(DispatchException.Reason.HANDOVER_ALREADY_RATIFIED));

        // Same refusal for a console identity: the precondition is about state.
        assertThatThrownBy(() -> exchanges.writeHandoverDraft(SCOPE, at(sent), CONSOLE,
            null, "a third answer", null))
            .isInstanceOfSatisfying(DispatchException.class, x -> assertThat(x.reason())
                .isEqualTo(DispatchException.Reason.HANDOVER_ALREADY_RATIFIED));
    }

    // -----------------------------------------------------------------------
    // The claim duration
    // -----------------------------------------------------------------------

    @Test
    void a_non_positive_claim_duration_is_refused() {
        Exchange sent = openAndSend("a commission claimed for no time at all");

        for (Duration bad : new Duration[] {Duration.ZERO, Duration.ofMinutes(-5)}) {
            assertThatThrownBy(() -> exchanges.takeup(SCOPE, at(sent), EXECUTOR, bad))
                .as("a claim that has already lapsed when it is awarded leaves every later "
                    + "reader to decide what that was supposed to mean")
                .isInstanceOfSatisfying(DispatchException.class, x -> assertThat(x.reason())
                    .isEqualTo(DispatchException.Reason.CLAIM_DURATION_NOT_POSITIVE));
        }
    }

    // -----------------------------------------------------------------------
    // Metadata
    // -----------------------------------------------------------------------

    @Test
    void metadata_carrying_credentials_are_refused() {
        Exchange draft = exchanges.openBracket(SCOPE, "sprint", "a commission", "code",
            LocalDate.now(), CONSOLE);

        assertThatThrownBy(() -> exchanges.send(SCOPE, at(draft), CONSOLE,
            Map.of("pull-request", "https://user:secret@example.invalid/pr/1")))
            .as("this service holds pointers and never follows them, so a credential "
                + "stored here can only ever be read by somebody — never used")
            .isInstanceOfSatisfying(DispatchException.class, x -> assertThat(x.reason())
                .isEqualTo(DispatchException.Reason.METADATA_REFUSED));
    }

    @Test
    void metadata_long_enough_to_be_prose_are_refused() {
        Exchange draft = exchanges.openBracket(SCOPE, "sprint", "a commission", "code",
            LocalDate.now(), CONSOLE);

        assertThatThrownBy(() -> exchanges.send(SCOPE, at(draft), CONSOLE,
            Map.of("note", "x".repeat(Metadata.MAX_VALUE_LENGTH + 1))))
            .as("metadata carry an address or an identifier, never an assertion. Anything "
                + "this long is prose, and prose belongs in the body where the freeze "
                + "protects it")
            .isInstanceOfSatisfying(DispatchException.class, x -> assertThat(x.reason())
                .isEqualTo(DispatchException.Reason.METADATA_REFUSED));
    }

    @Test
    void an_address_is_accepted_as_metadata_and_frozen_at_send() {
        Exchange draft = exchanges.openBracket(SCOPE, "sprint", "a commission", "code",
            LocalDate.now(), CONSOLE);
        Exchange sent = exchanges.send(SCOPE, at(draft), CONSOLE,
            Map.of("pull-request", "https://example.invalid/pr/1"));

        assertThat(sent.dispatchMetadata)
            .as("a pull-request URL is an address, which is exactly what metadata are for")
            .containsEntry("pull-request", "https://example.invalid/pr/1");

        assertThatThrownBy(() -> PlatformFixture.run(
            "UPDATE dispatch.exchange SET dispatch_metadata = '{\"pull-request\":\"changed\"}'::jsonb "
                + "WHERE id = '" + sent.id + "'"))
            .as("write-once, frozen at send like everything else. A pointer that changes "
                + "is a pointer whose readers cannot tell which one they followed")
            .hasMessageContaining("write-once");
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private Exchange openAndSend(String title) {
        Exchange e = exchanges.openBracket(SCOPE, "sprint", title, "code",
            LocalDate.now(), CONSOLE);
        return exchanges.send(SCOPE, at(e), CONSOLE);
    }

    private static ExchangeAddress at(Exchange e) {
        return new ExchangeAddress(e.selector, e.number, e.sub, e.addendumSuffix);
    }
}
