/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The classes every service has carry the same name in every service (C2.4).
 *
 * <p>The list is the contract's, written here by hand rather than read from
 * any tree: a list derived from the service under test would accept whatever
 * the service happens to call things. A class missing by name here is a
 * deviation of this service, not a local choice.
 *
 * <p>Presence by simple name anywhere in the main tree, as the rule states it;
 * where each class lives is the business of the package and naming guards.
 *
 * <p>{@code KeysetClause} (C9.3) is not on the list: the contract names it
 * under its own rule, and the change that extracts it adds it here.
 */
class SharedClassNamesTest {

    /** C2.4, in the contract's order, then the tenancy template of C19.1. */
    static final List<String> SHARED = List.of(
        "VerbSurface", "AddressParser", "RefusalCode", "RefusalCatalogueGuard", "RefusalMapper",
        "UnexpectedFailures", "SurfaceDeclaration", "DeclarationResource", "WhoamiResource",
        "McpAdapter", "ScopeDirectory", "ScopeAccessRepository", "PageRequest", "Page",
        "PageCursor", "SortKey", "KeysetClause", "IdentityConfigurationGuard",
        "TenantBound", "TenantBindingInterceptor", "TenantDatabaseBinding",
        "TenantMigrationCallback", "TenancyConfigurationGuard", "TenantRequestFilter");

    @Test
    void every_shared_class_is_present_by_its_shared_name() {
        assertThat(missing(SHARED, namesIn(SourceTree.root("main"))))
            .as("these classes exist in every platform service under one name, so that a "
                + "reader who knows one service finds the same thing in the next (C2.4). "
                + "Add or rename the class")
            .isEmpty();
    }

    /**
     * The red state, observed on every build: a tree without one of the names
     * must be reported, by that name.
     */
    @Test
    void the_check_reports_a_shared_class_that_is_absent() {
        Set<String> withoutOne = new java.util.HashSet<>(SHARED);
        withoutOne.remove("RefusalCatalogueGuard");

        assertThat(missing(SHARED, withoutOne))
            .as("RED STATE, observed: a tree lacking a shared class must be reported, and "
                + "by the class's name")
            .containsExactly("RefusalCatalogueGuard");
    }

    static List<String> missing(List<String> expected, Set<String> present) {
        return expected.stream().filter(name -> !present.contains(name)).toList();
    }

    private static Set<String> namesIn(Path root) {
        Set<String> names = new java.util.HashSet<>();
        for (Path file : SourceTree.files(root)) {
            String fqcn = SourceTree.fqcn(root, file);
            names.add(fqcn.substring(fqcn.lastIndexOf('.') + 1));
        }
        assertThat(names).as("the main tree must have been read").hasSizeGreaterThan(10);
        return names;
    }
}
