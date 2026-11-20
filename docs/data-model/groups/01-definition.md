# Group 1: definition

The tables that declare a process. Model: `../model/definition.linkml.yaml`. Physical level: store
line V1 (`selector`, `definition_version`, `definition_act`, `definition_tag`, `activity`,
`outcome_label`), the seed mark with V4. Diagram: `../diagrams/01-definition.puml`.

## Purpose

A scope declares what kinds of work it runs and how. The declaration is versioned: a version is
written as a draft, put in force by an activation, and retired by the activation of a later one. Every
instance binds to the version in force at its start and keeps it (TAR-0004 2.2, P7 of the process
concept), so that the rules an instance ran under can always be read again.

## Concepts

| Concept | Table | In one sentence |
|---|---|---|
| Selector | `selector` | A kind of instance of a scope (sprint, satellite, orga); it numbers its instances. |
| Definition version | `definition_version` | One numbered version of the process a selector declares. |
| Definition act | `definition_act` | `define` or `activate` of a version, with who did it, how and when: the trace of the definition. |
| Definition tag | `definition_tag` | A tag of the platform catalogue the version carries, held as a value. |
| Activity | `activity` | A kind of work the version declares, with what its tasks require and take as input. |
| Outcome label | `outcome_label` | One value of the closed set of outcomes, either of an activity's tasks or of the version's instances. |

## Invariants and what keeps them

| Invariant | Kept by |
|---|---|
| At most one version per selector is active. | partial unique index `uq_definition_version_active ... WHERE state = 'active'` |
| A version number is unique per selector and never reissued. | `uq_definition_version_selector_version`; the service allocates |
| A definition act is never changed. | no UPDATE grant on `definition_act` for the service role |
| Of a version, only `state` changes. | the UPDATE grant on `definition_version` covers `state` alone |
| Activities and outcome labels never change after the version is written. | no UPDATE grant |
| An activity takes a bound input exactly when it names the bound activity. | `ck_activity_bound` |
| An outcome label belongs to exactly one owner: an activity or the version. | `ck_outcome_label_owner`; uniqueness per owner by two partial unique indexes |
| An authentication level is recorded only for an act on the console path. | `ck_definition_act_authentication_level` |
| Every row carries tenant and scope; every reference carries them too. | columns, `UNIQUE (tenant_id, scope_id, id)`, foreign keys over tenant and scope, forced row-level security |

## Questions this group answers

- Which version is in force for a selector, and which was in force when a given instance started?
- Who defined and who activated a version, on which path, when?
- Which activities does a version declare, what does each require in a return, may its executor ask?
- Which outcomes are possible for a task of an activity, and for an instance?

## Why it is cut this way

- **The version, not the selector, carries the declaration.** A change to a running process would
  otherwise change the rules under instances already running (P7).
- **Definition acts in a table of their own, apart from the acts of instances.** A definition has no
  instance; its trace answers different questions (who may write and who may put in force, REQ-0184)
  and must not mix with the trace of the work.
- **One table for outcome labels with two possible owners.** Labels of a task and of an instance have
  the same shape and the same rule (a closed set, DEC-0056/C5); two tables would duplicate the shape.
  The cost is a nullable pair and a check that exactly one is set.

## Rejected alternatives

Reconstructed for this chapter from the corpus and the store; where no node records the choice, the
assessment says so.

- **The declaration as one JSON document per version.** Rejected: the gate and the reads would parse
  the document on every act, and no foreign key could hold an activity named by a task.
- **Activities shared between versions.** Rejected: a later version could not change an activity
  without changing it under running instances.

## What the next wave adds here

Process rules, restrictions and conditions per version, and the tenant rule set with versions of its
own (TAR-0004 2.6). Their tables attach to `definition_version`; this group changes only by addition.

## Open in the assessment

- The partial unique index is the only place the rule "at most one active" is kept; a reader of the
  DBML view does not see it (`../method/format.md`).
- `seed` arrives with V4 and is not yet in the store; the reconciliation reports it until then.
