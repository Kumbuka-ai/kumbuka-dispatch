# What the next wave adds to the store, and what it leaves alone

The store line V1 to V3 (with V4: the seed mark and the reference kind `item`) is built for the wave of
REA-0010. The wave after it builds the process rules and what belongs to them. That wave has no target
and no realization node yet; its concept work is carried in the planning record of the project.

This page answers one question before the build orders of the current wave are cut: **does anything that
is specified or foreseeable for the next wave force a change to a table, a column or a meaning of the
store as it is built now?** It is a compatibility check, not a target model. It decides nothing of the
next wave.

The measure is DEC-0018: a later migration is additive. A new table, a new nullable or defaulted column
and a widened check are additive. A dropped or renamed table or column, a narrowed check and a changed
meaning of an existing column are not.

State: 2026-11-20. Sources read: TAR-0004 2.1 to 2.12 and sections 4 to 6, DEC-0054, DEC-0055, DEC-0056,
REA-0010 sections 1, 2 (steps 3 to 5) and 4, the concepts on the process model, the data model, declared
fields and process selection, the path from a worklist item to a concluded instance, and activity kinds;
the planning record of the wave that builds the rules and of the current wave. Not read: two design returns, the
requirement nodes beyond their titles.

## 1. Result

No addition listed below drops, renames or narrows anything in V1 to V3, and none changes the meaning of
an existing column. Every one is a new table, a nullable or defaulted column, or a widened check.

Three findings concern the current wave itself and are listed in section 3. The remaining source of
changes to the tables of V1 to V3 is the assessment of the model against the evaluation catalogue, not
the next wave.

## 2. Additions and what they touch

| Addition | Source | Change to the store | Existing table touched | Open |
|---|---|---|---|---|
| Process rules, restrictions with one parameter table per pattern, obligations | TAR-0004 2.2, 2.6; process concept A.7 | new tables, owned by a definition version or by a version of the tenant rule set | none; they refer to `definition_version`, `activity`, `outcome_label` | a pattern naming several activities needs child rows; "response with a delay" |
| Tenant rule set with its versions and its trace | TAR-0004 2.2 | new tables that carry a tenant and no scope | none | the only tables without `scope_id`; which unit a tenant is (open question tenant-team-directory-mapping) |
| What admitted an act: rule row, role name, version of the tenant rule set | data model concept A.5 | nullable columns on `act` | `act` (columns) | - |
| Conditions as CEL text with a checked type; derived control fields | DEC-0056/C11, C16 to C20; REA-0010 D14 | expression on the rule rows; declaration at `control_field`; a derived value is a `field_value` row on the act that changed an input | `control_field` (columns) | a derived value hangs on an `accept` as well, so "a value only on a start or a deliver" widens in the service |
| Routing payload: delivery reacted to, bound version, documents read, confidence | TAR-0004 2.5; process concept A.8 | new payload table on the act; the content kind `decision_basis` exists | none | - |
| Interventions `repair` and `rebind` with their payloads | TAR-0004 2.7 | new payload tables; `ck_act_kind` and `ck_act_object` widened (`recall` exists) | `act` (checks) | - |
| Item criteria an instance takes over at its start, and their status and evidence reference in the instance return | DEC-0055/C9 | two new tables; a content kind for the copied text | `content` (check, one nullable column) | TAR-0004 2.3 states the earlier rule |
| Selection of a definition for a start request | concept on declared fields, section 3; items 1067, 1213 | new tables; a payload on the start act (version, row, aspects supplied) | none | the form is open on purpose (table, start event, external resolver) |
| Lock mode of a process step, take-up without a lease; refusing a take-up by a caller who does not hold the addressed role | items 1155, 1211; concept from item to instance, section 6 | defaulted column on `activity`; a holding without a lease is representable today; the addressed role exists from V4 | `activity` (column) | not decided; concept in draft |
| Addressing a sent task to another role by an act of its own | operator, 2026-11-20 | a new act kind with the new role as its payload; `task.addressed_role` follows as a projection | `act` (check) | whether at all, and whether only before the first take-up |
| Activity kinds, agent objects, capability descriptions, bindings | concept on activity kinds | new tables; nullable reference on `activity` | `activity` (column) | O1 to O4, O8, O9 of the concept block a target |
| Override of a declared separation (break-glass) | operator decision on the current wave | column on the restriction; a payload naming rule and reason | none | no node; own act or attribute of `accept` |
| Limiters on feedback loops | planning record | one more restriction pattern table | none | a concept of its own is required first |
| Role that answers the questions of an activity | planning record | nullable column on `activity` | `activity` (column) | variant not chosen |
| An instance takes no new tasks | planning record | a projection column on `instance`, or a third instance state | `instance` (column, or checks on `instance.state` and `act.to_state`) | attribute or state (DEC-0046) |
| Type `money` (amount and ISO 4217 currency, no conversion); a field marked searchable, should the projection of the values in force (V4, `instance_field_value`) hold fewer fields than all | operator, 2026-11-20 | type checks widened, two typed columns, the one-value check adjusted; defaulted column on `control_field`; new table | `control_field`, `field_value` (checks, columns) | the catalogue grows by decision (DEC-0056/C3); the projection changes at `accept`, not at `deliver` |
| Key identifier of an encrypted text | operator, 2026-11-20 | nullable columns on `content` | `content` (columns) | idea |
| A process marked as part of a family; a default process | concept from item to instance, section 12 | defaulted column on `definition_version` | `definition_version` (column) | not decided |
| The sequence as written, beside the rules it translates into | data model concept O1 | column or table at the definition version | none or `definition_version` | with the modelling interface |
| Due and excluded activities, `stuck`, `overdue`, the derived labels of an instance, hints | TAR-0004 2.5, 2.9, 2.10 | none: computed at read | none | whether `query` filters on computed exceptions fast enough is measured |
| Recurring instances from a template | REQ-0165 | new tables | none | not specified; its planning record was not read for this page |
| Audit register | ADR-0024; TAR-0004 2.11 | tables of their own in the enterprise edition | none | see section 4 |

## 3. Findings on the current wave

- **The field `apparatus` has no place in the new store.** REA-0010 section 4 keeps it "on the surface
  and in the store", step 5 keeps it readable, filterable and written, and the acceptance of step 6 has
  the agent controller select work by it. V1 to V3 hold no column and no other place for it, the copy
  rule of step 4 names no destination, and the catalogue rows of `create` and `query` do not name it.
  **Decided by the operator on 2026-11-20:** the field becomes
  the addressed role of a commission, a system field that V4 adds as `commission.addressed_role`
  (frozen), `draft.addressed_role` and the projection `task.addressed_role`; the copy of step 4 writes
  the value of `apparatus` there; the verb surface keeps the name `apparatus` for this wave; after the
  send the value is fixed. The model carries the three attributes; REA-0010 and TAR-0004 2.4 are still
  to be revised.
- **TAR-0004 2.3 and DEC-0055/C9 disagree** on the criteria of a worklist item.
  The current wave builds neither the copy nor the statuses, so nothing built contradicts the decision;
  the target a reviewer reads does.
- **The actor of an act is immutable text.** `act.actor_subject` and `act.actor_owner_subject` are never
  changed. The erasure of a person (REQ-0116, open in TAR-0004 section 6) stays additive only if these
  columns hold an opaque subject identifier and never a name or an address. Neither V1 nor REA-0010
  states that; the model states it now as a rule the service keeps (chapter 03).

## 4. Audit

Direction of the operator, 2026-11-20, not yet a node: the audit is purely
additive, in tables of its own, with triggers where possible; a field that can be updated gets a
copy-on-write record; the engine is the community edition, audit and versioning come on top.

What the store as built means for that direction:

- Every table of the trace is insert-only by grant. Nothing has to be added to keep its rows.
- The columns the service may update are, without exception, projections of acts (`task`, `instance`,
  `definition_version.state`, `holding.ended_by_act_id`, `external_reference.ended_by_act_id`), counters
  (`selector.next_number`, `instance.next_sub`) or the draft workspace. The history of a projection is
  the acts it folds. No updatable column of the store needs a copy-on-write record to be reconstructable.
- The draft workspace is changed and deleted without a record on purpose: a register may record that a
  draft was created, never its content (data model concept A.10).
- A trigger sees a row that is written. A refused attempt (REQ-0080) and a read by a delegated identity
  (service contract C28.2) write no row of the store; a register that holds them needs a writer in the
  service.
- A schema is never changed in place: a change is a new definition version, written by `define` and put
  into force by `activate`, each an act of `definition_act` with its actor and time. The version is the
  copy.
- The register stays in the platform layer (ADR-0024); the dispatch service is not the central place of
  audit (operator, 2026-11-20). What the audit concept still has to do is specify the single audit
  points.

Nothing in this direction requires a change to V1 to V4.

## 5. Open topics

Before the build orders of the current wave:

1. The place of `apparatus` in the new store: done. REA-0010 D16 and TAR-0004 2.4 carry the
   addressed role.
2. TAR-0004 2.3 brought in line with DEC-0055/C9.
3. The assessment of V1 to V3 against the evaluation catalogue: done, see
   `assessments/2026-11-20-assessment.md` and `assessments/2026-11-20-decisions.md`.
4. The statement that an actor column holds an opaque identifier: done, REA-0010 D17.

The inside of the engine, next:

5. Rules, restrictions, obligations and the tenant rule set as tables; the owner pattern "exactly one of
   two"; tables without a scope.
6. What admitted an act; derived values and the act they hang on.
7. The gate's latency budget (process concept A.13: 50 ms at the 95th percentile, set and not measured).

Product decisions of the next wave, carried in the planning record:

8. Selection of a definition at the start (items 1067, 1213).
9. Lock mode per step, take-up without a lease, addressee (items 1155, 1211); bears on the holder
   protocol.
10. Activity kinds and agent binding (O1 to O4, O8, O9).
11. Break-glass; the design questions Q1 to Q6, Q9, Q11 to Q15.
12. Limiters; the role that answers questions; an instance that takes no new
    tasks.
13. `money`, searchable fields, encryption of content.
14. Addressing a sent task to another role.

Audit:

15. The audit concept: the single audit points and the mechanism of the register's writer.

Organisation:

16. The work on the wave that builds the rules starts "when the current wave is cut over". Since 2026-11-20 the
    cut-over waits for the next wave (REA-0010). The two conditions wait on each other.
17. Recurring instances are planned as work of their own; whether that work builds against
    the store of the integration branch is not read.
18. Which activities receive the input text of an instance: a declaration at the activity beside its
    input contract. From V4 every reader permitted to read content reads it (operator, 2026-11-20).
19. Addenda to the input text of an instance.
