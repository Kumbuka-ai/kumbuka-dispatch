# Group 8: control fields

The fields a definition declares, the contracts that name them, and the values acts write for them.
Model: `../model/control.linkml.yaml`. Physical level: store line V3 (`enumeration`,
`enumeration_value`, `control_field`, `contract_field`, `field_value`). Diagram:
`../diagrams/08-control-fields.puml`.

## Purpose

DEC-0056 separates four kinds of data: system fields the platform defines, control fields a definition
declares, free fields, and content. The gate and the rules of the next wave evaluate only system and
control fields. A control field is declared per definition version with a name and a type of the
platform's catalogue (boolean, number, date, text, choice); a start contract names the fields a start
carries, a result contract the fields a delivery of an activity carries. An act writes one typed value
per field; a later act writes a new one, and the current value is a fold.

## Concepts

| Concept | Table | In one sentence |
|---|---|---|
| Enumeration | `enumeration` | A named list of values a version declares for its choice fields. |
| Enumeration value | `enumeration_value` | One value of an enumeration, by its text and place. |
| Control field | `control_field` | A field the schema of a version declares, with name, type and, for a choice, its enumeration. |
| Contract field | `contract_field` | A field a contract names: the start contract of the version or the result contract of an activity. |
| Field value | `field_value` | The value one act writes for one field, in the column of its type. |

## Invariants and what keeps them

| Invariant | Kept by |
|---|---|
| A field name and an enumeration name are unique in their version and are identifiers. | `uq_control_field_version_name`, `uq_enumeration_version_name`; the name pattern checks |
| A choice field has an enumeration of the same version; no other type has one. | `ck_control_field_enumeration`; the foreign key over `(definition_version_id, enumeration_id)` |
| A contract names a field at most once; activity and field belong to the version of the contract. | two partial unique indexes; foreign keys over `definition_version_id` |
| A value has exactly one typed column set, the one its type names. | `ck_field_value_one_value`, `ck_field_value_typed_value` |
| The type of a value is the type of its field. | the foreign key over `(control_field_id, value_type)` into `uq_control_field_id_type` |
| A choice is a value of its field's enumeration. | the keys over `(control_field_id, enumeration_id)` and `(enumeration_id, choice_value_id)`; under MATCH SIMPLE they bind only where `enumeration_id` is set |
| An act writes a field at most once. | `uq_field_value_act_control_field` |
| Nothing changes once written. | no UPDATE grant on any table of this group |
| The field belongs to the version of the act's instance; a value hangs only on a start or deliver; a field name shadows no system field. | the service (REA-0010 S5); without triggers and without the version on the act the store cannot |

## Questions this group answers

- Which control fields does a version declare, of which type, with which values for a choice?
- What must a start carry, and what must a delivery of this activity carry?
- What is the current value of a field on this instance, and which act wrote it?
- Which instances have a field with a given value (the rule and selection reads of wave 3)?

## Why it is cut this way

- **Typed columns, not one text column.** A rule compares a number as a number and a date as a date;
  with one text column every comparison would parse. The cost is five nullable columns and two checks.
- **Type and enumeration carried into the value's keys.** This lets the store, not only the service,
  refuse a value of the wrong type or a choice outside its enumeration, without triggers.
- **Fields per version, not per scope.** A field is part of the declaration (group 1) and changes with
  it (P7).
- **Values on the act, not on the instance.** The history of a value is part of the trace; the current
  value is the last one written.

## Rejected alternatives

Reconstructed for this chapter from the corpus; DEC-0056 records the main choice.

- **Control data in a JSONB document with a JSON schema per version.** Rejected in DEC-0056: the gate
  would evaluate unchecked structure, and no key could hold a choice to its enumeration.
- **One table per declared field (columns generated per definition).** Rejected: a definition would
  become a migration.
- **Entity-attribute-value with a single text value.** The current cut is an EAV form; it is kept
  typed to avoid the parsing cost that makes plain EAV weak.

## What the next wave adds here

Derived fields of an instance (computed from control fields, TAR-0004 2.3) and the conditions of
process rules and restrictions, which read field values by name; both attach to `control_field`.

## Open in the assessment

- The EAV form is assessed against prior art (Camunda variables, Temporal search attributes) and
  measured under P4 for the rule reads.
- Three checks are the service's; the assessment records them as accepted gaps or as candidates for
  denormalising the version onto the act.
