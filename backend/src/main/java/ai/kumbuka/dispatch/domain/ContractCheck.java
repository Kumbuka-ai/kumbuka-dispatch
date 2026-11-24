/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.repository.DefinitionRepository;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The checks of what a call hands in against the version of its instance:
 * the field values of a start against the start contract and the schema
 * (DEC-0056/C7), and the return of a closing against what the version
 * requires of it.
 *
 * <p>Each check refuses with a typed refusal that names its rule by its
 * reason and the field by its path, and never the value (DEC-0056/C21). A
 * field the contract does not name is not repeated either, since its name is
 * the caller's: the refusal names the fields the contract names instead. The
 * checks read the declaration of the version and nothing else; they write
 * nothing, and are made before anything is written.
 */
final class ContractCheck {

    /** Where the field values of a start travel, as the declaration names it. */
    static final String VALUES = "fields.values.";

    static final String VERDICT = "fields.verdict";
    static final String OUTCOME = "fields.outcome";
    static final String TRANSLATION_BASIS = "fields.translation_basis";

    private ContractCheck() {
    }

    /** A field value of a start, checked: the field it is written for, and its typed value. */
    record Checked(ControlField field, TypedValue value) {
    }

    /** The return of a closing, checked: its verdict and the label it names, each where given. */
    record Return(@Nullable String verdict, @Nullable Long outcomeLabelId) {
    }

    /**
     * The field values of a start, each checked against the start contract
     * and the schema of the version, in the order of the contract.
     *
     * @param given the values as the call handed them in, by field name; a
     *              value of {@code null} is a value not sent (C7.4)
     * @throws DispatchException {@code CONTRACT_FIELD_UNDECLARED} for a field
     *         the contract does not name, then for each field of the contract in
     *         its order {@code CONTRACT_FIELD_MISSING}, {@code
     *         CONTRACT_FIELD_MISTYPED} or {@code CONTRACT_VALUE_UNENUMERATED}
     */
    static List<Checked> startValues(DefinitionRepository.Declared declared,
                                     Map<String, Object> given) {
        Map<Long, ControlField> fields = new LinkedHashMap<>();
        declared.fields().forEach(field -> fields.put(field.id(), field));
        Map<String, ContractField> contract = new LinkedHashMap<>();
        for (ContractField row : declared.contracts()) {
            if (row.activityId().isEmpty()) {
                contract.put(fieldOf(fields, row).name(), row);
            }
        }

        Map<String, Object> present = new LinkedHashMap<>();
        given.forEach((name, value) -> {
            if (value != null) {
                present.put(name, value);
            }
        });
        if (!contract.keySet().containsAll(present.keySet())) {
            throw new DispatchException(DispatchException.Reason.CONTRACT_FIELD_UNDECLARED,
                "a start carries a field its start contract does not name.",
                contract.keySet().stream().map(name -> VALUES + name).toList());
        }

        List<Checked> checked = new ArrayList<>();
        for (Map.Entry<String, ContractField> named : contract.entrySet()) {
            ControlField field = fieldOf(fields, named.getValue());
            Object value = present.get(named.getKey());
            if (value == null) {
                if (named.getValue().required()) {
                    throw refused(DispatchException.Reason.CONTRACT_FIELD_MISSING, field,
                        "the start contract requires this field.");
                }
                continue;
            }
            checked.add(new Checked(field, typed(declared, field, value)));
        }
        return List.copyOf(checked);
    }

    /** The field a contract row names: the store keys it to a field of the same version. */
    private static ControlField fieldOf(Map<Long, ControlField> fields, ContractField row) {
        return Objects.requireNonNull(fields.get(row.controlFieldId()),
            "a contract names a field of its own version");
    }

    /** One value in the type of its field, or the refusal of its type or enumeration. */
    private static TypedValue typed(DefinitionRepository.Declared declared, ControlField field,
                                    Object value) {
        Optional<TypedValue> typed = switch (field.type()) {
            case "boolean" -> value instanceof Boolean flag
                ? Optional.of(TypedValue.ofBoolean(flag)) : Optional.empty();
            case "number" -> number(value).map(TypedValue::ofNumber);
            case "date" -> date(value).map(TypedValue::ofDate);
            case "text" -> value instanceof String text && text.length() <= TypedValue.TEXT_BOUND
                ? Optional.of(TypedValue.ofText(text)) : Optional.empty();
            case "choice" -> value instanceof String choice
                ? Optional.of(choice(declared, field, choice)) : Optional.empty();
            default -> Optional.empty();
        };
        return typed.orElseThrow(() -> refused(DispatchException.Reason.CONTRACT_FIELD_MISTYPED,
            field, "the value is not of its field's type."));
    }

    private static Optional<BigDecimal> number(Object value) {
        if (value instanceof BigDecimal decimal) {
            return Optional.of(decimal);
        }
        if (value instanceof BigInteger whole) {
            return Optional.of(new BigDecimal(whole));
        }
        if (value instanceof Integer || value instanceof Long) {
            return Optional.of(BigDecimal.valueOf(((Number) value).longValue()));
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            return Double.isFinite(d) ? Optional.of(BigDecimal.valueOf(d)) : Optional.empty();
        }
        return Optional.empty();
    }

    /** A calendar day, written {@code yyyy-MM-dd}. */
    private static Optional<LocalDate> date(Object value) {
        if (!(value instanceof String text)) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(text));
        } catch (DateTimeParseException notADay) {
            return Optional.empty();
        }
    }

    private static TypedValue choice(DefinitionRepository.Declared declared, ControlField field,
                                     String choice) {
        long enumeration = field.enumerationId().orElseThrow(() ->
            new IllegalStateException("a choice field names an enumeration of its version"));
        return declared.values().stream()
            .filter(v -> v.enumerationId() == enumeration && v.value().equals(choice))
            .findFirst()
            .map(v -> TypedValue.ofChoice(v.id()))
            .orElseThrow(() -> refused(DispatchException.Reason.CONTRACT_VALUE_UNENUMERATED, field,
                "the value is no value of its field's enumeration."));
    }

    private static DispatchException refused(DispatchException.Reason reason, ControlField field,
                                             String rule) {
        return new DispatchException(reason, rule, List.of(VALUES + field.name()));
    }

    /**
     * The return of a closing, checked against the version of its instance: a
     * verdict where the version requires one, an outcome label where it
     * requires one, a label only of the version's labels, and a label only
     * with its translation basis.
     *
     * @throws DispatchException {@code RETURN_PART_MISSING} naming the part, or
     *         {@code RETURN_OUTCOME_UNDECLARED}
     */
    static Return closing(DefinitionRepository.Declared declared, @Nullable String verdict,
                          @Nullable String outcome, @Nullable String translationBasis) {
        DefinitionVersion version = declared.version();
        if (verdict == null && version.verdictRequired()) {
            throw missing(VERDICT, "the version requires a verdict.");
        }
        if (outcome == null) {
            if (version.outcomeRequired()) {
                throw missing(OUTCOME, "the version requires an outcome label.");
            }
            return new Return(verdict, null);
        }
        Long label = declared.labels().stream()
            .filter(l -> l.activityId().isEmpty() && l.label().equals(outcome))
            .findFirst()
            .map(OutcomeLabel::id)
            .orElseThrow(() -> new DispatchException(
                DispatchException.Reason.RETURN_OUTCOME_UNDECLARED,
                "an outcome label is one of the version's labels.", List.of(OUTCOME)));
        if (translationBasis == null) {
            throw missing(TRANSLATION_BASIS, "an outcome label needs its translation basis.");
        }
        return new Return(verdict, label);
    }

    private static DispatchException missing(String part, String rule) {
        return new DispatchException(DispatchException.Reason.RETURN_PART_MISSING, rule,
            List.of(part));
    }
}
