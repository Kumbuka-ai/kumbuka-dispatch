# Group 9: free fields and content

What an act carries that the platform stores and returns and never evaluates. Model:
`../model/payload.linkml.yaml`. Physical level: store line V2 (`free_fields`, `content`). Diagram:
`../diagrams/09-free-fields-and-content.puml`.

## Purpose

Free fields are what a customer or their agent attaches to an instance, a commission or a return; the
platform keeps them and hands them back (DEC-0056). Content is the text of the work: commission and
criteria texts, questions and answers, the prose and translation basis of a return, remarks and
addenda (TAR-0004 2.4, ADR-0042 for its separation). Neither is ever read by the gate or a rule; that is
what separates them from control fields (group 8).

## Concepts

| Concept | Table | In one sentence |
|---|---|---|
| Free fields | `free_fields` | The one document of free fields an act attaches; whose it is follows from the act's kind. |
| Content | `content` | A text of a kind an act writes, with what it supplements or belongs to. |

## Invariants and what keeps them

| Invariant | Kept by |
|---|---|
| One free-field document per act, and it is a JSON object. | `uq_free_fields_act`, `ck_free_fields_document` |
| An addendum supplements exactly one earlier text or one answer act; no other kind supplements anything. | `ck_content_supplements`, `ck_content_supplements_other` |
| A criterion text belongs to its commission criterion, evidence to its delivery criterion, and only those kinds carry them. | `ck_content_criterion`, `ck_content_criterion_evidence` |
| One text per commission criterion and per delivery criterion. | partial unique indexes |
| Nothing changes once written; a correction is an addendum. | no UPDATE grant |
| Which kinds of content an act of a kind carries. | the service; without triggers the store cannot |
| Free fields are never evaluated. | the service: no rule, gate or query reads into the document |

## Questions this group answers

- What does the commission say, what do its criteria say, what evidence did the return give?
- Which addenda were written to a text, by whom and when?
- Which free fields did the customer attach to this instance or return?

## Why it is cut this way

- **Content apart from control.** A text can be long, is written by people and agents, and must never
  become an input of a decision; held apart, no rule can reach it by accident, and the content can be
  protected and retained on rules of its own.
- **One content table with a kind.** All texts share shape and lifecycle; the kind tells a reader what
  a text is. The criterion columns give a criterion text a key of its own.
- **Addenda instead of edits.** A frozen text stays as written; what is added is an act of its own
  (TAR-0004 2.5, annotate).
- **Free fields as JSONB, one document per act.** They have no declared schema by definition; a
  document is the honest form. They are the only JSON of the store besides the draft's.

## Rejected alternatives

Reconstructed for this chapter from the corpus; DEC-0056 records the separation.

- **Free fields as key-value rows.** Rejected: the platform does not know their structure, and rows
  would suggest a schema that does not exist.
- **Texts as columns of their payload tables.** Rejected: an addendum must be able to supplement any
  text, which needs texts to have one identity space.

## What the next wave adds here

A search index over the free-field documents is a later migration (V2 header). Retention rules for
content follow the audit concept.

## Open in the assessment

- The anchor of free fields and content is the act alone; that an instance's free fields hang on its
  start act is a rule of the service, assessed under F2 (does a reader find them).
- `content.kind` lists twelve kinds in a check; assessed with `ck_act_kind`.
