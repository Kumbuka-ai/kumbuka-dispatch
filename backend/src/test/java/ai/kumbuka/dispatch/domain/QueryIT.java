package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The listing: what it narrows by, what it refuses to narrow by, and what it
 * hands over once it has narrowed.
 *
 * <h2>The two properties, and why they are in one class</h2>
 *
 * A filter that is refused and a body that is withheld look unrelated until
 * you notice they fail the same way: quietly, with a plausible answer. An
 * ignored filter returns the full set, which reads exactly like a correct
 * narrow one. A listing built on the raw read returns every body, which reads
 * exactly like a listing. Neither raises anything, and nobody notices until
 * something is somewhere it should not be.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class QueryIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final String SELECTOR = "sprint";
    static final Duration CLAIM = Duration.ofHours(1);

    static final Actor EXECUTOR = new Actor("query-executor", Actor.Kind.EXECUTOR);
    static final Actor OTHER = new Actor("query-executor-2", Actor.Kind.EXECUTOR);
    static final Actor CONSOLE = new Actor("query-console", Actor.Kind.CONSOLE);

    @Inject ExchangeService exchanges;
    @Inject TenantContext tenantContext;

    private UUID tenant;
    private AutoCloseable binding;

    @BeforeEach
    void freshTenant() {
        tenant = UUID.randomUUID();
        DomainFixture.declareSelector(tenant, SCOPE, SELECTOR);
        binding = tenantContext.bind(tenant);
    }

    @AfterEach
    void unbind() throws Exception {
        binding.close();
    }

    // =======================================================================
    // Probe D — the filter refuses, and it also works
    // =======================================================================

    /**
     * An undeclared field is a typed refusal that names the field.
     *
     * <p>The field is named because a refusal that only says "bad filter"
     * sends the caller back to guess which of its parameters was the problem,
     * and the answer is right there when the check runs.
     */
    @Test
    void an_undeclared_filter_field_is_refused_and_named() {
        assertThatThrownBy(() -> exchanges.query(SCOPE, SELECTOR,
                QueryFilter.of(Map.of("title", "anything")), CONSOLE))
            .isInstanceOf(DispatchException.class)
            .satisfies(thrown -> {
                DispatchException e = (DispatchException) thrown;
                assertThat(e.reason())
                    .isEqualTo(DispatchException.Reason.FILTER_FIELD_UNKNOWN);
                assertThat(e.offenders())
                    .as("the refusal names the field, and 'title' in particular is one a "
                        + "caller will reach for — free-text matching is the first line of "
                        + "the expression language this deliberately is not")
                    .containsExactly("title");
                assertThat(e.getMessage())
                    .as("and says what WOULD have worked, or the caller learns the "
                        + "vocabulary one refusal at a time")
                    .contains("status", "apparatus", "number");
            });
    }

    /** Every unknown field at once, rather than the first one and a second trip. */
    @Test
    void several_undeclared_fields_are_all_named_in_one_refusal() {
        assertThatThrownBy(() -> exchanges.query(SCOPE, SELECTOR,
                QueryFilter.of(new java.util.LinkedHashMap<>(Map.of(
                    "title", "x", "body", "y"))), CONSOLE))
            .isInstanceOf(DispatchException.class)
            .satisfies(thrown -> assertThat(((DispatchException) thrown).offenders())
                .as("a caller fixing one field at a time is a caller making two round "
                    + "trips to learn what one refusal could have said")
                .containsExactlyInAnyOrder("title", "body"));
    }

    /**
     * A value the field cannot take is refused too, and for a reason of its
     * own.
     *
     * <p>Matching it against nothing would answer with an empty page, and an
     * empty page says "there is nothing here" — which is a statement about the
     * data, not about the question. The caller asked for something that does
     * not exist and has to be told so.
     */
    @Test
    void a_status_this_scheme_does_not_have_is_refused_rather_than_matched_against_nothing() {
        openAndSend("something real");

        assertThatThrownBy(() -> exchanges.query(SCOPE, SELECTOR,
                QueryFilter.of(Map.of("status", "banana")), CONSOLE))
            .isInstanceOf(DispatchException.class)
            .extracting(e -> ((DispatchException) e).reason())
            .isEqualTo(DispatchException.Reason.FILTER_VALUE_REFUSED);
    }

    /** An empty value means neither "no filter" nor a filter, so it is refused. */
    @Test
    void an_empty_filter_value_is_refused_rather_than_read_as_no_filter() {
        assertThatThrownBy(() -> exchanges.query(SCOPE, SELECTOR,
                QueryFilter.of(Map.of("status", "")), CONSOLE))
            .isInstanceOf(DispatchException.class)
            .extracting(e -> ((DispatchException) e).reason())
            .isEqualTo(DispatchException.Reason.FILTER_VALUE_REFUSED);
    }

    /**
     * And the declared filters actually narrow.
     *
     * <p>Without this the refusals above would be satisfied by a filter that
     * refuses everything it does not know and ignores everything it does — the
     * exact defect, with a refusal in front of it.
     */
    @Test
    void a_declared_filter_narrows_and_the_values_within_it_are_alternatives() {
        Exchange open = openAndSend("still open");
        Exchange closed = openAndSend("finished");
        exchanges.reject(SCOPE, addressOf(closed), CONSOLE);

        assertThat(addressesOf(exchanges.query(SCOPE, SELECTOR,
                QueryFilter.of(Map.of("status", "open")), CONSOLE)))
            .as("one value narrows to that value")
            .containsExactly(open.address());

        assertThat(addressesOf(exchanges.query(SCOPE, SELECTOR,
                QueryFilter.of(Map.of("status", "open,rejected")), CONSOLE)))
            .as("and comma-separated values are alternatives within the field, in the "
                + "order of the address space")
            .containsExactly(open.address(), closed.address());
    }

    /** Separate fields are read together, not as alternatives. */
    @Test
    void separate_filters_are_conjunctive() {
        Exchange forCode = openAndSend("addressed to code", "code");
        openAndSend("addressed to concept", "concept");

        assertThat(addressesOf(exchanges.query(SCOPE, SELECTOR,
                QueryFilter.of(Map.of("status", "open", "apparatus", "code")), CONSOLE)))
            .as("a caller asking for open AND code gets neither the closed ones nor the "
                + "ones addressed elsewhere")
            .containsExactly(forCode.address());
    }

    /** No filter at all is the whole selector, in address order. */
    @Test
    void an_unfiltered_listing_is_the_selector_in_address_order() {
        Exchange first = openAndSend("first");
        Exchange second = openAndSend("second");

        assertThat(addressesOf(exchanges.query(SCOPE, SELECTOR, QueryFilter.none(), CONSOLE)))
            .as("the order is the address space's own — number then sub — and no second "
                + "ordering is invented for a listing")
            .containsExactly(first.address(), second.address());
    }

    /**
     * An addendum is not listed, for the same reason it is not readable on its
     * own.
     *
     * <p>A listing that returned addresses the read verb refuses would be
     * handing out addresses that do not work.
     */
    @Test
    void addenda_are_not_listed() {
        Exchange base = openAndSend("the base");
        Exchange addendum = exchanges.addAddendum(SCOPE, addressOf(base), "a correction",
            "code", LocalDate.now(), CONSOLE);

        List<String> listed = addressesOf(
            exchanges.query(SCOPE, SELECTOR, QueryFilter.none(), CONSOLE));

        assertThat(listed).containsExactly(base.address());
        assertThat(listed)
            .as("an addendum has no standing of its own; it is reached through the "
                + "exchange it hangs from")
            .doesNotContain(addendum.address());
    }

    // =======================================================================
    // Probe E — the projection holds in the listing
    // =======================================================================

    /**
     * An executing apparatus listing a selector receives no body for an
     * exchange it has not claimed. Not an empty one — none.
     *
     * <p>This is the bolt that a listing is most likely to walk around,
     * because the obvious construction is a loop over the raw read and the
     * raw read takes no actor. The failure is silent: every body in the
     * selector, handed to a caller that has claimed nothing, with no error to
     * notice it by.
     */
    @Test
    void an_executor_listing_receives_no_body_for_what_it_has_not_claimed() {
        openAndSend("a commission with a body");

        List<ExchangeView> listed = exchanges.query(SCOPE, SELECTOR, QueryFilter.none(),
            EXECUTOR);

        assertThat(listed).singleElement().satisfies(view -> {
            assertThat(view.body())
                .as("enough to refuse and not enough to work: the title, the apparatus and "
                    + "the date decide whether to take something up, and the body is what "
                    + "taking it up buys")
                .isNull();
            assertThat(view.title())
                .as("while the rest of the projection is there — a listing that withheld "
                    + "everything would be useless and would also pass the assertion above")
                .isEqualTo("a commission with a body");
        });
    }

    /** The console reads bodies as a matter of course, and the listing says so. */
    @Test
    void a_console_listing_receives_the_body() {
        openAndSend("a commission with a body");

        assertThat(exchanges.query(SCOPE, SELECTOR, QueryFilter.none(), CONSOLE))
            .singleElement()
            .satisfies(view -> assertThat(view.body())
                .as("operators read commissions as a matter of course, and the listing "
                    + "uses the same projection as the single read rather than a second "
                    + "rule of its own")
                .isNotNull());
    }

    /**
     * And the holder receives the body of what it holds — which is what makes
     * the withholding above a claim gate rather than a blanket denial.
     */
    @Test
    void the_holder_receives_the_body_of_the_exchange_it_holds() {
        Exchange held = openAndSend("claimed by the executor");
        openAndSend("not claimed by anybody");
        exchanges.takeup(SCOPE, addressOf(held), EXECUTOR, CLAIM);

        List<ExchangeView> listed = exchanges.query(SCOPE, SELECTOR, QueryFilter.none(),
            EXECUTOR);

        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).body())
            .as("what this executor holds, it may read")
            .isNotNull();
        assertThat(listed.get(1).body())
            .as("and what it does not hold, it may not — in the same answer, which is "
                + "what makes this a per-row decision rather than a per-caller one")
            .isNull();
    }

    /** Another executor's claim buys nothing for this one. */
    @Test
    void a_claim_held_by_somebody_else_does_not_open_the_body() {
        Exchange held = openAndSend("claimed by somebody else");
        exchanges.takeup(SCOPE, addressOf(held), OTHER, CLAIM);

        assertThat(exchanges.query(SCOPE, SELECTOR, QueryFilter.none(), EXECUTOR))
            .singleElement()
            .satisfies(view -> {
                assertThat(view.body())
                    .as("a claim is one executor's, and a listing is not a way around it")
                    .isNull();
                assertThat(view.effectiveHolder())
                    .as("while who holds it is visible: that is what lets a caller decide "
                        + "to wait rather than retry")
                    .isEqualTo(OTHER.subject());
            });
    }

    // =======================================================================

    private Exchange openAndSend(String title) {
        return openAndSend(title, "code");
    }

    private Exchange openAndSend(String title, String apparatus) {
        Exchange opened = exchanges.openBracket(SCOPE, SELECTOR, title, apparatus,
            LocalDate.now(), CONSOLE);
        exchanges.send(SCOPE, addressOf(opened), CONSOLE);
        return opened;
    }

    private static List<String> addressesOf(List<ExchangeView> views) {
        return views.stream().map(ExchangeView::address).toList();
    }

    private static ExchangeAddress addressOf(Exchange e) {
        return new ExchangeAddress(e.selector, e.number, e.sub, e.addendumSuffix);
    }
}
