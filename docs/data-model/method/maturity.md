# Maturity of the data model

Maturity here is a property of the model, not of the organisation that maintains it. Maturity models
for data management (the CMMI Data Management Maturity model, the DAMA DMBOK assessments) rate the
processes of an organisation and are not used to rate a schema.

A data model is mature to the degree that its three levels exist and agree: the conceptual level says
what the data means, the logical level what shape it has and which rules it keeps, the physical level
how the database holds it. A schema alone answers the last question and leaves the reader to guess the
first two.

## Levels

| Level | Name | Requires |
|---|---|---|
| 0 | physical only | the store line exists and migrates; nothing else is written down |
| 1 | logical | every table and column has a definition; every column is assigned to one kind of data under DEC-0056/C1; every invariant is named with the constraint, trigger or code that keeps it |
| 2 | conceptual | every concept is defined in the language of the domain and traced to the clause of the architecture corpus it serves; every clause of the target that concerns stored data has a concept that carries it |
| 3 | reconciled | the three levels are checked against each other by a script: every table maps to a concept and every concept to at least one table; the physical level is regenerated from the SQL and compared on every change |

A level counts as reached only when the level below it is reached.

## Target

The model is accepted for building at level 3. Level 3 is what lets a reader trust the explanation:
a definition that no longer matches the table is caught by the reconciliation, not by the next reader.

## State

2026-11-20: level 0.
