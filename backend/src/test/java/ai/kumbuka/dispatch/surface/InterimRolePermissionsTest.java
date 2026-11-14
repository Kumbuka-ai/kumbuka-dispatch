/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import io.quarkus.security.StringPermission;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The interim source of the permissions: which permissions a token with the
 * realm roles {@code dispatch-console} and {@code dispatch-executor} holds
 * once the source has seen it.
 *
 * <p>The expectation is the table of the order of this step, written here
 * name by name, and read back from the identity the way the checks read it,
 * through {@code checkPermission}: never from the source's own table.
 */
class InterimRolePermissionsTest {

    /** Every permission of the catalogue, by name, as the order lists it. */
    private static final List<String> CATALOGUE = List.of(
        "dispatch.task.commission", "dispatch.task.execute", "dispatch.task.decide",
        "dispatch.task.confirm", "dispatch.task.withdraw", "dispatch.task.tag",
        "dispatch.instance.start", "dispatch.instance.conclude", "dispatch.instance.withdraw",
        "dispatch.instance.read", "dispatch.instance.read-metadata",
        "dispatch.instance.read-text", "dispatch.instance.read-text-as-holder",
        "dispatch.instance.read-text-as-commissioner");

    private static final Set<String> CONSOLE = Set.of(
        "dispatch.instance.start", "dispatch.instance.conclude", "dispatch.instance.withdraw",
        "dispatch.task.commission", "dispatch.task.decide", "dispatch.task.confirm",
        "dispatch.task.withdraw", "dispatch.task.tag", "dispatch.instance.read",
        "dispatch.instance.read-metadata", "dispatch.instance.read-text");

    private static final Set<String> EXECUTOR = Set.of(
        "dispatch.task.execute", "dispatch.task.tag", "dispatch.instance.read",
        "dispatch.instance.read-metadata", "dispatch.instance.read-text-as-holder");

    @Test
    void the_console_role_stands_for_the_consoles_permissions() {
        assertThat(held("dispatch-console")).containsExactlyInAnyOrderElementsOf(CONSOLE);
    }

    @Test
    void the_executor_role_stands_for_the_executors_permissions() {
        assertThat(held("dispatch-executor")).containsExactlyInAnyOrderElementsOf(EXECUTOR);
    }

    @Test
    void both_roles_stand_for_the_union() {
        Set<String> union = new TreeSet<>(CONSOLE);
        union.addAll(EXECUTOR);
        assertThat(held("dispatch-console", "dispatch-executor"))
            .containsExactlyInAnyOrderElementsOf(union);
    }

    @Test
    void neither_role_stands_for_no_permission_and_another_role_for_none_either() {
        assertThat(held()).as("a token without a role of the service holds nothing").isEmpty();
        assertThat(held("worklist-editor", "dispatch-admin"))
            .as("a role the source does not name stands for nothing").isEmpty();
    }

    @Test
    void the_permission_read_text_as_commissioner_is_granted_to_no_role() {
        assertThat(held("dispatch-console", "dispatch-executor"))
            .doesNotContain("dispatch.instance.read-text-as-commissioner");
    }

    @Test
    void the_roles_stay_on_the_identity_and_the_subject_is_kept() {
        SecurityIdentity augmented = InterimRolePermissions.withPermissions(
            identity("a-subject", "dispatch-console"));
        assertThat(augmented.getPrincipal().getName()).isEqualTo("a-subject");
        assertThat(augmented.getRoles()).containsExactly("dispatch-console");
    }

    /** The permissions of the catalogue an identity with these roles holds, read as the checks read them. */
    private static Set<String> held(String... roles) {
        SecurityIdentity augmented = InterimRolePermissions.withPermissions(
            identity("a-subject", roles));
        Set<String> held = new TreeSet<>();
        for (String name : CATALOGUE) {
            if (augmented.checkPermissionBlocking(new StringPermission(name))) {
                held.add(name);
            }
        }
        return held;
    }

    private static SecurityIdentity identity(String subject, String... roles) {
        var builder = QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal(subject));
        for (String role : roles) {
            builder.addRole(role);
        }
        return builder.build();
    }
}
