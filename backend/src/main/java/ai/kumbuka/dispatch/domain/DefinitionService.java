/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.repository.DefinitionRepository;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The reader of definitions: answers a seeded version of a selector with
 * everything it declares (DEC-0056/C6).
 *
 * <p>It reads and writes nothing else. A version enters the store as a seed,
 * by the copy of the cut-over or by a migration of the installation, and never
 * by a call; so this class has no method that writes, and a version that is
 * not seeded is not read. A selector the scope does not hold, a version the
 * selector does not have and a version that is not seeded are one refusal,
 * {@code NOT_FOUND}, which says nothing about which of the three it was.
 *
 * <p>Logs nothing (DEC-0051, C4).
 */
@ApplicationScoped
@TenantBound
public class DefinitionService {

    private final DefinitionRepository definitions;

    @Inject
    DefinitionService(DefinitionRepository definitions) {
        this.definitions = definitions;
    }

    /**
     * A seeded version as its read answers it.
     *
     * @param scopeId  the scope the selector belongs to, as the directory resolved it
     * @param selector the selector, by its name
     * @param version  the number of the version; absent for the active one
     * @throws DispatchException {@code NOT_FOUND} where the scope holds no such
     *                           seeded version for the bound tenant
     */
    @Transactional
    public DefinitionView read(UUID scopeId, String selector, @Nullable Integer version) {
        return definitions.seeded(scopeId, selector, version)
            .map(declared -> viewOf(selector, declared))
            .orElseThrow(() -> new DispatchException(DispatchException.Reason.NOT_FOUND,
                "no seeded version of that selector is there to read."));
    }

    /** The answer, built from the rows a version declares and from nothing else. */
    static DefinitionView viewOf(String selector, DefinitionRepository.Declared declared) {
        DefinitionVersion version = declared.version();

        Map<Long, List<String>> valuesOf = new LinkedHashMap<>();
        for (EnumerationValue value : declared.values()) {
            valuesOf.computeIfAbsent(value.enumerationId(), id -> new ArrayList<>())
                .add(value.value());
        }
        Map<Long, String> enumerationNames = new LinkedHashMap<>();
        List<DefinitionView.NamedValues> enumerations = new ArrayList<>();
        for (Enumeration enumeration : declared.enumerations()) {
            enumerationNames.put(enumeration.id(), enumeration.name());
            enumerations.add(new DefinitionView.NamedValues(enumeration.name(),
                valuesOf.getOrDefault(enumeration.id(), List.of())));
        }

        Map<Long, String> fieldNames = new LinkedHashMap<>();
        List<DefinitionView.SchemaField> schema = new ArrayList<>();
        for (ControlField field : declared.fields()) {
            fieldNames.put(field.id(), field.name());
            schema.add(new DefinitionView.SchemaField(field.name(), field.type(),
                field.enumerationId().map(enumerationNames::get).orElse(null)));
        }

        List<String> versionLabels = new ArrayList<>();
        Map<Long, List<String>> activityLabels = new LinkedHashMap<>();
        for (OutcomeLabel label : declared.labels()) {
            Optional<Long> activity = label.activityId();
            if (activity.isPresent()) {
                activityLabels.computeIfAbsent(activity.get(), id -> new ArrayList<>())
                    .add(label.label());
            } else {
                versionLabels.add(label.label());
            }
        }

        List<DefinitionView.ContractEntry> startContract = new ArrayList<>();
        Map<Long, List<DefinitionView.ContractEntry>> resultContracts = new LinkedHashMap<>();
        for (ContractField row : declared.contracts()) {
            // The store keys a contract row to a field of the same version.
            DefinitionView.ContractEntry entry = new DefinitionView.ContractEntry(
                Objects.requireNonNull(fieldNames.get(row.controlFieldId()),
                    "a contract names a field of its own version"), row.required());
            Optional<Long> activity = row.activityId();
            if (activity.isPresent()) {
                resultContracts.computeIfAbsent(activity.get(), id -> new ArrayList<>())
                    .add(entry);
            } else {
                startContract.add(entry);
            }
        }

        List<DefinitionView.ActivityEntry> activities = declared.activities().stream()
            .map(activity -> new DefinitionView.ActivityEntry(activity.name(),
                activity.verdictRequired(), activity.outcomeRequired(),
                activity.criteriaRequired(),
                activityLabels.getOrDefault(activity.id(), List.of()),
                resultContracts.getOrDefault(activity.id(), List.of()),
                activity.inputKind(), activity.mayAsk()))
            .toList();

        return new DefinitionView(selector, version.version(), version.state(),
            version.verdictRequired(), version.outcomeRequired(), versionLabels, schema,
            enumerations, activities, startContract, systemEnumerations());
    }

    /** The enumerations of the platform's system fields, in the form of a customer's. */
    static List<DefinitionView.NamedValues> systemEnumerations() {
        return Arrays.stream(SystemEnumeration.values())
            .map(system -> new DefinitionView.NamedValues(system.wireName(), system.members()))
            .toList();
    }
}
