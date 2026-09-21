package ai.kumbuka.dispatch.platform;

import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Resolving a scope against the platform's read contract — and, more
 * importantly, what happens when it cannot be resolved.
 *
 * <p>The interesting assertions here are the refusals. Under row-level
 * security an unbound session and an inaccessible scope produce the same
 * observable thing: zero rows. If the service returned an empty result for
 * both, the next person to see it would read "no such scope", and the
 * plausible repairs for that are to widen a privilege or to keep a local copy
 * of the directory — repairs to a symptom whose cause was a forgotten
 * binding, and each one a hole in the boundary the product sells.
 *
 * <p>So the two cases carry different typed reasons, and neither is ever an
 * empty return.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ScopeDirectoryIT {

    @Inject ScopeDirectory directory;
    @Inject TenantContext tenantContext;

    /**
     * The platform's grant, issued once the service's role exists. In a
     * deployment this is the platform's own migration; here it is a fixture,
     * for the same reason and in the same order.
     */
    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @Test
    void a_scope_the_subject_may_enter_resolves() {
        var access = directory.resolve(SubstrateDatabaseResource.PROBE_SUBJECT,
            SubstrateDatabaseResource.PROBE_SCOPE_SLUG, Access.READ);

        assertThat(access.slug()).isEqualTo(SubstrateDatabaseResource.PROBE_SCOPE_SLUG);
        assertThat(access.scopeId())
            .isEqualTo(UUID.fromString(SubstrateDatabaseResource.SCOPE_ID));
        assertThat(access.archived())
            .as("archived is published rather than filtered, so a write into a retired "
                + "scope can be refused with a specific error instead of 'not found'")
            .isFalse();
    }

    /**
     * The three columns V24 added arrive, and they arrive with the values the
     * contract derives rather than with defaults.
     *
     * <p>Asserted against a scope whose every new column is the INTERESTING
     * value — a project scope that is not locked and IS writable — plus the
     * locked one below, because a record of booleans that were never populated
     * reads as all-false and satisfies any assertion that only names false.
     */
    @Test
    void the_contract_publishes_the_kind_the_lock_and_the_write_right() {
        var access = directory.resolve(SubstrateDatabaseResource.PROBE_SUBJECT,
            SubstrateDatabaseResource.PROBE_SCOPE_SLUG, Access.READ);

        assertThat(access.kind())
            .as("the kind is published now; the view used to filter on it and publish "
                + "nothing, so a service could not tell which kind it had reached")
            .isEqualTo("project");
        assertThat(access.locked()).isFalse();
        assertThat(access.canWrite())
            .as("an unmuted member of an unlocked shared scope writes. Without this the "
                + "refusals below would hold against a directory that refuses everything")
            .isTrue();
    }

    /**
     * A global scope resolves — which it could not before V24.
     *
     * <p>The view ended in {@code WHERE kind = 'project'}, so a global scope
     * was not merely unwritable, it was invisible: this service answered the
     * not-found class for a scope the platform holds and every member has.
     */
    @Test
    void a_global_scope_resolves_now_that_the_kind_filter_is_gone() {
        var access = directory.resolve(SubstrateDatabaseResource.PROBE_SUBJECT,
            SubstrateDatabaseResource.GLOBAL_SCOPE_SLUG, Access.WRITE);

        assertThat(access.kind()).isEqualTo("global");
        assertThat(access.canWrite()).isTrue();
    }

    /**
     * A private scope is refused, and it is refused on a READ.
     *
     * <p>The distinction the reason carries: this caller CAN see the scope —
     * the directory returned a row for it — so the refusal is about what the
     * scheme carries and not about what the caller may reach. Answering the
     * not-found class instead would have been a lie about the contract's
     * answer.
     */
    @Test
    void a_private_scope_is_refused_as_a_kind_this_service_does_not_serve() {
        assertThatThrownBy(() -> directory.resolve(SubstrateDatabaseResource.PROBE_SUBJECT,
            SubstrateDatabaseResource.PRIVATE_SCOPE_SLUG, Access.READ))
            .isInstanceOfSatisfying(DispatchException.class, e -> assertThat(e.reason())
                .as("a private scope is a per-tenant container for memory content, and an "
                    + "exchange has no meaning in one. The caller is told what the scheme "
                    + "carries so that it stops rather than retries")
                .isEqualTo(DispatchException.Reason.SCOPE_KIND_UNSUPPORTED));
    }

    /**
     * A locked scope reads and does not write, and the lock answers under its
     * own reason.
     *
     * <p>The pair in one test on purpose: the read is what makes the refusal
     * of the write a statement about writing rather than about the scope
     * being out of reach.
     */
    @Test
    void a_locked_scope_reads_and_refuses_a_write_under_its_own_reason() {
        var read = directory.resolve(SubstrateDatabaseResource.PROBE_SUBJECT,
            SubstrateDatabaseResource.LOCKED_SCOPE_SLUG, Access.READ);

        assertThat(read.locked()).isTrue();
        assertThat(read.canWrite())
            .as("the contract derives the write right as 'not locked and …', which is why "
                + "the lock has to be checked FIRST or its own reason is unreachable")
            .isFalse();

        assertThatThrownBy(() -> directory.resolve(SubstrateDatabaseResource.PROBE_SUBJECT,
            SubstrateDatabaseResource.LOCKED_SCOPE_SLUG, Access.WRITE))
            .isInstanceOfSatisfying(DispatchException.class, e -> assertThat(e.reason())
                .as("SCOPE_LOCKED and not SCOPE_READ_ONLY. Checking the write right first "
                    + "would answer every locked scope SCOPE_READ_ONLY and leave this "
                    + "reason declared and unproducible")
                .isEqualTo(DispatchException.Reason.SCOPE_LOCKED));
    }

    /**
     * A muted member reads a shared scope and does not write it.
     *
     * <p>A read-only situation is a SUBJECT and not a scope: the contract
     * derives the write right from the membership's mute flag, so the same
     * scope answers differently for two callers. A fixture built the other way
     * round — a scope marked read-only — would have probed something the
     * platform cannot produce.
     */
    @Test
    void a_muted_member_reads_a_shared_scope_and_is_refused_the_write() {
        var read = directory.resolve(SubstrateDatabaseResource.MUTED_SUBJECT,
            SubstrateDatabaseResource.PROBE_SCOPE_SLUG, Access.READ);

        assertThat(read.canWrite()).isFalse();
        assertThat(read.locked())
            .as("and the scope is NOT locked, which is what keeps this case apart from "
                + "the one above")
            .isFalse();

        assertThatThrownBy(() -> directory.resolve(SubstrateDatabaseResource.MUTED_SUBJECT,
            SubstrateDatabaseResource.PROBE_SCOPE_SLUG, Access.WRITE))
            .isInstanceOfSatisfying(DispatchException.class, e -> assertThat(e.reason())
                .isEqualTo(DispatchException.Reason.SCOPE_READ_ONLY));
    }

    @Test
    void a_scope_the_subject_may_not_enter_is_a_refusal_and_not_an_empty_result() {
        assertThatThrownBy(() -> directory.resolve("a-subject-who-is-not-a-member",
            SubstrateDatabaseResource.PROBE_SCOPE_SLUG, Access.READ))
            .isInstanceOfSatisfying(DispatchException.class, e -> assertThat(e.reason())
                .as("the directory answers for the bound subject only, and existence in "
                    + "its answer IS the permission — so a subject who is not a member "
                    + "gets a refusal, never an empty list to be worked around")
                .isEqualTo(DispatchException.Reason.SCOPE_UNRESOLVED));
    }

    @Test
    void a_scope_that_does_not_exist_is_a_refusal_too() {
        assertThatThrownBy(() -> directory.resolve(SubstrateDatabaseResource.PROBE_SUBJECT,
            "no-such-scope", Access.READ))
            .isInstanceOfSatisfying(DispatchException.class, e ->
                assertThat(e.reason()).isEqualTo(DispatchException.Reason.SCOPE_UNRESOLVED));
    }

    /**
     * The fail-closed probe, both halves.
     *
     * <p>Resolving with no subject must fail with a reason that names the
     * binding — not with the same reason as an inaccessible scope, and not
     * with an empty result. Then the same call with the subject bound must
     * succeed, which is what shows the refusal was about the binding and not
     * about the scope.
     */
    @Test
    void resolving_without_a_bound_session_fails_loudly_and_names_the_binding() {
        assertThatThrownBy(() -> directory.resolve(null,
            SubstrateDatabaseResource.PROBE_SCOPE_SLUG, Access.READ))
            .isInstanceOfSatisfying(DispatchException.class, e -> {
                assertThat(e.reason())
                    .as("an unbound session is a DIFFERENT refusal from an inaccessible "
                        + "scope. Collapsing them is what makes somebody widen a privilege "
                        + "to fix a forgotten set_config")
                    .isEqualTo(DispatchException.Reason.SESSION_NOT_BOUND);
                assertThat(e.getMessage()).contains("app.subject");
            });

        assertThatThrownBy(() -> directory.resolve("  ",
            SubstrateDatabaseResource.PROBE_SCOPE_SLUG, Access.READ))
            .isInstanceOfSatisfying(DispatchException.class, e ->
                assertThat(e.reason()).isEqualTo(DispatchException.Reason.SESSION_NOT_BOUND));

        // The other half: with the subject bound the same scope resolves. Without
        // this, the assertions above would hold just as well against a directory
        // that never resolves anything at all.
        assertThat(directory.resolve(SubstrateDatabaseResource.PROBE_SUBJECT,
                SubstrateDatabaseResource.PROBE_SCOPE_SLUG, Access.READ).slug())
            .as("and with the session bound the very same call succeeds, which is what "
                + "makes the refusals above about the binding rather than about the view")
            .isEqualTo(SubstrateDatabaseResource.PROBE_SCOPE_SLUG);
    }

    /**
     * The tenant axis reaches the directory too: a subject that is a member
     * under one tenant does not resolve the scope while another tenant is
     * bound. The view keys on both settings, and this is the half that would
     * be missed by only ever testing the subject.
     */
    @Test
    void a_foreign_tenant_binding_does_not_resolve_the_scope() throws Exception {
        try (AutoCloseable ignored = tenantContext.bind(UUID.randomUUID())) {
            assertThatThrownBy(() -> directory.resolve(SubstrateDatabaseResource.PROBE_SUBJECT,
                SubstrateDatabaseResource.PROBE_SCOPE_SLUG, Access.READ))
                .isInstanceOfSatisfying(DispatchException.class, e -> assertThat(e.reason())
                    .as("the directory keys on tenant AND subject; a valid subject under "
                        + "the wrong tenant must not reach the scope")
                    .isEqualTo(DispatchException.Reason.SCOPE_UNRESOLVED));
        }
    }
}
