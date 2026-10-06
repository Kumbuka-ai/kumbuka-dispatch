package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static ai.kumbuka.dispatch.domain.TaskHoldingIT.assertRefused;
import static ai.kumbuka.dispatch.domain.TaskStage.C;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Withdrawing a selector, in the kernel and under the service role: a status
 * and never a deletion, only while nothing is numbered under the name, and
 * afterwards nothing new is.
 *
 * <p>No call of either surface withdraws a selector; this is the kernel's
 * method and the database's grant, measured together.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class SelectorWithdrawalIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final String UNUSED = "unused";
    static final String USED = "used";

    @Inject SelectorRegistry selectors;
    @Inject TaskService tasks;
    @Inject TenantContext tenantContext;

    private UUID tenant;
    private AutoCloseable binding;

    @BeforeEach
    void freshTenant() {
        PlatformFixture.grantDirectoryAccess();
        tenant = UUID.randomUUID();
        DomainFixture.declareSelector(tenant, SCOPE, UNUSED);
        DomainFixture.declareSelector(tenant, SCOPE, USED);
        binding = tenantContext.bind(tenant);
    }

    @AfterEach
    void unbind() throws Exception {
        binding.close();
    }

    @Test
    void a_selector_without_a_task_is_withdrawn_as_a_status() {
        selectors.withdraw(SCOPE, UNUSED);

        assertThat(withdrawn(UNUSED)).as("the row stays, marked withdrawn").isTrue();
    }

    @Test
    void a_selector_with_a_task_under_it_is_not_withdrawn() {
        tasks.create(SCOPE, USED, null, new TaskService.Draft("under it", "code", null, null), C,
            IdempotencyKey.NONE);

        assertRefused(() -> selectors.withdraw(SCOPE, USED),
            DispatchException.Reason.SELECTOR_IN_USE);
        assertThat(withdrawn(USED)).isFalse();
    }

    @Test
    void nothing_is_created_under_a_withdrawn_selector() {
        selectors.withdraw(SCOPE, UNUSED);

        assertRefused(() -> tasks.create(SCOPE, UNUSED, null,
                new TaskService.Draft("too late", "code", null, null), C, IdempotencyKey.NONE),
            DispatchException.Reason.SELECTOR_WITHDRAWN);
        assertThat(tasksUnder(UNUSED)).isZero();
    }

    // -----------------------------------------------------------------------

    private boolean withdrawn(String name) {
        return Boolean.parseBoolean(single("SELECT withdrawn::text FROM dispatch.selector WHERE tenant_id = '"
            + tenant + "' AND name = '" + name + "'"));
    }

    private int tasksUnder(String name) {
        return Integer.parseInt(single("SELECT count(*) FROM dispatch.task t JOIN dispatch.selector s "
            + "ON s.id = t.selector_id WHERE s.tenant_id = '" + tenant + "' AND s.name = '"
            + name + "'"));
    }

    /** One value, read as the administrator: what the transaction committed. */
    private static String single(String sql) {
        var config = ConfigProvider.getConfig();
        try (Connection c = DriverManager.getConnection(
                config.getValue("test.db.url", String.class),
                config.getValue("test.db.admin.username", String.class),
                config.getValue("test.db.admin.password", String.class));
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
