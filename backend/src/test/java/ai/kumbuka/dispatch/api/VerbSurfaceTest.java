package ai.kumbuka.dispatch.api;

import ai.kumbuka.dispatch.api.payload.Payloads;
import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.domain.Exchange;
import ai.kumbuka.dispatch.domain.ExchangeAddress;
import ai.kumbuka.dispatch.domain.ExchangeService;
import ai.kumbuka.dispatch.domain.ExchangeStatus;
import ai.kumbuka.dispatch.domain.ExchangeView;
import ai.kumbuka.dispatch.platform.ScopeDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The verb layer's branch logic, with the database mocked away.
 *
 * <p>Beside the integration tests rather than instead of them, on the standing
 * rule: a mocked test cannot see a missing GRANT, a row-level-security policy
 * or a migration drift, and an integration test is a poor place to enumerate
 * branches — a branch reached only under a particular row is one nobody
 * notices went missing. Neither substitutes for the other.
 *
 * <p>What is worth asserting here and nowhere else is the shape of the
 * <em>calls</em>: which domain method a verb reaches, in which order the
 * stages run, and which branch a prior state selects. Those are statements
 * about this code, and a real database would only make them slower to make.
 */
class VerbSurfaceTest {

    private static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Actor EXECUTOR = new Actor("an-executor", Actor.Kind.EXECUTOR);

    private ExchangeService exchanges;
    private ScopeDirectory scopes;
    private VerbSurface verbs;

    @BeforeEach
    void freshMocks() {
        exchanges = mock(ExchangeService.class);
        scopes = mock(ScopeDirectory.class);

        verbs = new VerbSurface();
        verbs.exchanges = exchanges;
        verbs.scopes = scopes;

        when(scopes.resolve(anyString(), anyString()))
            .thenReturn(new ScopeDirectory.ScopeAccess(SCOPE, TENANT, "probe-scope", false));
        when(exchanges.view(any(), any(), any())).thenReturn(viewOf(ExchangeStatus.OPEN));
        when(exchanges.read(any(), any())).thenReturn(anExchange());
    }

    // =======================================================================
    // read goes to view, and to nothing else
    // =======================================================================

    /**
     * The mapping the dispatch calls out as the one that looks obviously
     * right and is not.
     *
     * <p>Asserted on the calls rather than on the payload, because the payload
     * assertion is already in the bolt probe and it can only see the body that
     * happens to be there. This one holds even for an exchange whose body is
     * empty — which is the case where the wrong mapping would look correct.
     */
    @Test
    void the_read_verb_never_projects_the_entity_the_same_named_method_returns() {
        verbs.read(EXECUTOR, "probe-scope", "sprint", "164.1");

        verify(exchanges).view(eq(SCOPE), any(ExchangeAddress.class), eq(EXECUTOR));
    }

    /**
     * Every answer of every verb comes from the view.
     *
     * <p>A transition returns the entity, and eleven routes answer a
     * transition. A surface that serialised what it got back would leak the
     * body on all eleven at once, and each call site would look like it was
     * just returning what the domain handed it.
     */
    @Test
    void a_transition_answers_from_the_view_and_not_from_what_it_returned() {
        verbs.close(EXECUTOR, "probe-scope", "sprint", "164.1");

        verify(exchanges).close(eq(SCOPE), any(), eq(EXECUTOR));
        verify(exchanges).view(eq(SCOPE), any(), eq(EXECUTOR));
    }

    // =======================================================================
    // abandon: one verb, two methods, the prior state decides
    // =======================================================================

    @Test
    void abandon_from_open_refuses_the_commission() {
        when(exchanges.view(any(), any(), any())).thenReturn(viewOf(ExchangeStatus.OPEN));

        verbs.abandon(EXECUTOR, "probe-scope", "sprint", "164.1");

        verify(exchanges).reject(eq(SCOPE), any(), eq(EXECUTOR));
        verify(exchanges, never()).fail(any(), any(), any());
    }

    @Test
    void abandon_from_active_fails_the_work() {
        when(exchanges.view(any(), any(), any())).thenReturn(viewOf(ExchangeStatus.ACTIVE));

        verbs.abandon(EXECUTOR, "probe-scope", "sprint", "164.1");

        verify(exchanges).fail(eq(SCOPE), any(), eq(EXECUTOR));
        verify(exchanges, never()).reject(any(), any(), any());
    }

    /**
     * From a state that is neither, the domain refuses and names the states
     * that would have worked.
     *
     * <p>The surface does not invent a refusal of its own here, and that is a
     * decision rather than an omission: it would have to guess which of the
     * two acts the caller meant in order to say why it was refused.
     */
    @Test
    void abandon_from_neither_state_lets_the_domain_name_what_would_have_worked() {
        when(exchanges.view(any(), any(), any())).thenReturn(viewOf(ExchangeStatus.NEEDS_INPUT));
        doThrow(new DispatchException(DispatchException.Reason.TRANSITION_NOT_PERMITTED,
            "reject is permitted from OPEN")).when(exchanges).reject(any(), any(), any());

        assertThatThrownBy(() -> verbs.abandon(EXECUTOR, "probe-scope", "sprint", "164.1"))
            .isInstanceOf(DispatchException.class)
            .hasMessageContaining("OPEN");
    }

    // =======================================================================
    // The order of the stages
    // =======================================================================

    /**
     * Grammar is stage 1 and the directory is stage 2, so a malformed address
     * must not reach the directory at all.
     *
     * <p>The order is what keeps stage 1 leak-free: its refusal is decidable
     * without knowing a scope, and a lookup performed before it would make
     * that untrue however the answer was worded.
     */
    @Test
    void a_malformed_address_never_reaches_the_directory() {
        assertThatThrownBy(() -> verbs.read(EXECUTOR, "probe-scope", "sprint", "164"))
            .isInstanceOf(SurfaceException.class);

        verify(scopes, never()).resolve(any(), any());
    }

    @Test
    void a_malformed_scope_never_reaches_the_directory_either() {
        assertThatThrownBy(() -> verbs.read(EXECUTOR, "NOT-A-LABEL", "sprint", "164.1"))
            .isInstanceOf(SurfaceException.class);

        verify(scopes, never()).resolve(any(), any());
    }

    /**
     * An uncarried verb is refused behind scope visibility, not in front of
     * it.
     *
     * <p>Capability is declared per scope, so a category error is in principle
     * a statement about a scope — and for a scope the caller cannot see the
     * answer is 404 however broken the rest of the call is.
     */
    @Test
    void an_uncarried_verb_resolves_the_scope_before_it_refuses() {
        assertThatThrownBy(() -> verbs.validate(EXECUTOR, "probe-scope", "sprint", "164.0"))
            .isInstanceOf(SurfaceException.class)
            .extracting(e -> ((SurfaceException) e).reason())
            .isEqualTo(SurfaceException.Reason.VERB_DEPTH_UNDECLARED);

        verify(scopes).resolve("an-executor", "probe-scope");
    }

    /**
     * A carried verb keeps the same order, and a refused filter is refused in
     * the same place a refused verb is.
     *
     * <p>{@code query} used to be the uncarried verb this order was measured
     * on. It is carried now, and the order it has to keep is unchanged: the
     * scope is resolved before the filter is judged, so a scope the caller
     * cannot see answers 404 whatever else is wrong with the call. A filter
     * refusal in front of that would say "no such field" to a caller who is
     * not entitled to know the collection exists.
     */
    @Test
    void a_refused_filter_is_refused_behind_scope_visibility_too() {
        assertThatThrownBy(() -> verbs.query(EXECUTOR, "probe-scope", "sprint",
                Map.of("nonesuch", "x")))
            .isInstanceOf(DispatchException.class)
            .extracting(e -> ((DispatchException) e).reason())
            .isEqualTo(DispatchException.Reason.FILTER_FIELD_UNKNOWN);

        verify(scopes).resolve("an-executor", "probe-scope");
    }

    // =======================================================================
    // The conflict token
    // =======================================================================

    @Test
    void a_field_write_without_a_token_is_refused_before_the_domain_is_called() {
        assertThatThrownBy(() -> verbs.update(EXECUTOR, "probe-scope", "sprint", "164.1",
            null, new Payloads.UpdateRequest("a draft", "a-receipt", null)))
            .isInstanceOf(SurfaceException.class)
            .extracting(e -> ((SurfaceException) e).reason())
            .isEqualTo(SurfaceException.Reason.CONFLICT_TOKEN_MISSING);

        verify(exchanges, never()).writeHandoverDraft(any(), any(), any(), any(), any(), any());
    }

    @Test
    void a_stale_token_is_refused_before_the_domain_is_called() {
        assertThatThrownBy(() -> verbs.update(EXECUTOR, "probe-scope", "sprint", "164.1",
            "1999-01-01T00:00:00Z", new Payloads.UpdateRequest("a draft", "a-receipt", null)))
            .isInstanceOf(SurfaceException.class)
            .extracting(e -> ((SurfaceException) e).reason())
            .isEqualTo(SurfaceException.Reason.CONFLICT_TOKEN_STALE);

        verify(exchanges, never()).writeHandoverDraft(any(), any(), any(), any(), any(), any());
    }

    /**
     * The token an answer hands out is the one a later write is accepted on.
     *
     * <p>Truncated to microseconds, because that is what the column stores: an
     * untruncated nanosecond value would be handed out on the write and never
     * match on the read back, and a token that never matches turns every
     * second write into a 412.
     */
    @Test
    void the_token_handed_out_is_the_token_accepted_back() {
        String handed = verbs.read(EXECUTOR, "probe-scope", "sprint", "164.1").conflictToken();

        verbs.update(EXECUTOR, "probe-scope", "sprint", "164.1", handed,
            new Payloads.UpdateRequest("a draft", "a-receipt", null));

        verify(exchanges).writeHandoverDraft(eq(SCOPE), any(), eq(EXECUTOR),
            eq("a-receipt"), eq("a draft"), eq(null));
    }

    @Test
    void the_token_is_tolerated_in_the_quoted_form_an_entity_tag_arrives_in() {
        String handed = verbs.read(EXECUTOR, "probe-scope", "sprint", "164.1").conflictToken();

        verbs.update(EXECUTOR, "probe-scope", "sprint", "164.1", "\"" + handed + "\"",
            new Payloads.UpdateRequest("a draft", "a-receipt", null));

        verify(exchanges).writeHandoverDraft(any(), any(), any(), any(), any(), any());
    }

    /**
     * An addendum has no version marker, and the domain is never asked for
     * one.
     *
     * <p>Asked before the call rather than caught after it. The domain refuses
     * to draw an addendum on its own — right, since it has no standing without
     * what it corrects — and that refusal comes out of a
     * {@code @Transactional} method, which marks the surrounding transaction
     * rollback-only whether or not anybody catches it. Catching it produced a
     * 201 for an append that was then silently rolled back. So what is
     * asserted is that the call is not made, not that its failure is handled.
     */
    @Test
    void an_addendum_is_never_asked_for_a_conflict_token() {
        assertThat(verbs.read(EXECUTOR, "probe-scope", "sprint", "164.0a").conflictToken())
            .isNull();

        verify(exchanges, never()).read(any(), any());
    }

    // =======================================================================
    // The sub-collection forms
    // =======================================================================

    @Test
    void the_children_sub_collection_is_refused_anywhere_but_a_bracket_root() {
        assertThatThrownBy(() -> verbs.createChild(EXECUTOR, "probe-scope", "sprint", "164.1",
            new Payloads.CreateRequest("a child", "code", LocalDate.parse("2026-09-01"), null)))
            .isInstanceOf(SurfaceException.class)
            .extracting(e -> ((SurfaceException) e).reason())
            .isEqualTo(SurfaceException.Reason.ADDRESS_MALFORMED);

        verify(exchanges, never()).addChild(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
            any(), any(), any(), any());
    }

    @Test
    void a_verb_that_takes_arguments_refuses_a_call_that_brought_none() {
        assertThatThrownBy(() -> verbs.create(EXECUTOR, "probe-scope", "sprint", null))
            .isInstanceOf(SurfaceException.class)
            .extracting(e -> ((SurfaceException) e).reason())
            .isEqualTo(SurfaceException.Reason.PAYLOAD_MALFORMED);
    }

    // =======================================================================
    // Stand-ins
    // =======================================================================

    private static ExchangeView viewOf(ExchangeStatus status) {
        return new ExchangeView("sprint/164.1", "sprint", 164, 1, "a commission", "code",
            LocalDate.parse("2026-09-01"), status, null, null, null);
    }

    /** An entity whose only interesting field here is when it was last written. */
    private static Exchange anExchange() {
        Exchange e = new Exchange();
        e.selector = "sprint";
        e.number = 164;
        e.sub = 1;
        e.updatedAt = Instant.parse("2026-09-01T12:00:00.123456789Z");
        return e;
    }
}
