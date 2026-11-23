/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * One seeded definition version as its read answers it: the head of the
 * version, its schema with its enumerations, its activities with their result
 * contracts, its start contract, and the enumerations of the platform's system
 * fields (DEC-0056/C5, C6).
 *
 * <p>A value with no entity in it: it is built inside the transaction of the
 * read and leaves it. Every list keeps the order the store declares: fields,
 * enumerations, activities and contract rows in the order they were declared,
 * labels and values in the order of their ordinal.
 *
 * @param selector           the selector the version belongs to
 * @param version            the number of the version, from 1
 * @param state              the state of the version, as the store names it
 * @param verdictRequired    whether a return of an instance needs a verdict
 * @param outcomeRequired    whether a return of an instance needs an outcome label
 * @param outcomeLabels      the labels an outcome of an instance may carry
 * @param schema             the control fields the version declares
 * @param enumerations       the enumerations the version declares
 * @param activities         the activities the version declares
 * @param startContract      the fields a start may or must carry
 * @param systemEnumerations the enumerations of the platform's system fields
 */
public record DefinitionView(String selector, int version, String state,
                             boolean verdictRequired, boolean outcomeRequired,
                             List<String> outcomeLabels, List<SchemaField> schema,
                             List<NamedValues> enumerations, List<ActivityEntry> activities,
                             List<ContractEntry> startContract,
                             List<NamedValues> systemEnumerations) {

    public DefinitionView {
        outcomeLabels = List.copyOf(outcomeLabels);
        schema = List.copyOf(schema);
        enumerations = List.copyOf(enumerations);
        activities = List.copyOf(activities);
        startContract = List.copyOf(startContract);
        systemEnumerations = List.copyOf(systemEnumerations);
    }

    /**
     * A control field of the schema.
     *
     * @param enumeration the enumeration a choice field takes its values from;
     *                    absent for every other type
     */
    public record SchemaField(String name, String type, @Nullable String enumeration) {
    }

    /** An enumeration by its name, with its values in their order. */
    public record NamedValues(String name, List<String> values) {

        public NamedValues {
            values = List.copyOf(values);
        }
    }

    /** An activity, with what a delivery of its tasks needs and the contract it answers to. */
    public record ActivityEntry(String name, boolean verdictRequired, boolean outcomeRequired,
                                boolean criteriaRequired, List<String> outcomeLabels,
                                List<ContractEntry> resultContract, String inputKind,
                                boolean mayAsk) {

        public ActivityEntry {
            outcomeLabels = List.copyOf(outcomeLabels);
            resultContract = List.copyOf(resultContract);
        }
    }

    /** A field a contract names, by the name of its control field, and whether it is required. */
    public record ContractEntry(String field, boolean required) {
    }
}
