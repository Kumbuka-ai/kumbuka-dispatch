---
type: contract-copy
title: "The dispatch service's verb surface: twenty-five calls, two sizes of answer, refusals that explain themselves"
written: 2026-10-06
amended: 2026-10-15
sources:
  - "platform-specs aa42521, docs/architecture/targets/TAR-0004-the-dispatch-service-carries-commissioned-work-from-issue-to-acceptance.md, sections 3, 5, 6, 7"
  - "platform-specs aa42521, docs/concepts/concept-dispatch-store-kernel-and-surface.md, section 3"
  - "the platform's pagination frame for every primary object, ratified by the operator 2026-10-11 (the substance of ADR-0049, not yet written): page_size, after, sort, direction, next_cursor, and the refusals PAGE_SIZE_REJECTED, CURSOR_MALFORMED, CURSOR_FOREIGN, SORT_UNDECLARED"
  - "platform-specs PR #9, docs/architecture/decisions/DEC-0043, rules R17 to R21, ratified 2026-10-15; DEC-0042 as ratified the same day (accepts in place of an unknown name)"
  - "platform-specs PR #9, docs/concepts/concept-dispatch-store-kernel-and-surface.md, section 3.5: the first entry of next after a claim"
---
<!--
Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
SPDX-License-Identifier: AGPL-3.0-only
This file is part of Kumbuka and is licensed under the GNU Affero
General Public License v3.0 only. See the LICENSE file in the
repository root for the full licence text.
-->

# The dispatch service's verb surface

This document is the expectation the service's conformance tests read: the calls, what each one
says about itself, and the refusals. It is written by hand from TAR-0004 and from the concept
document named above, and never generated from the service's declaration. Where those two
documents fix a text, it stands here word for word and is marked so; where they leave the wording
to the service, the text below was written with this surface and is the service's
proposal until the concept apparatus ratifies it.

## 1. What the service holds

A **task** is one commission and its answer, addressed as
`dispatch://<scope>/<selector>/<number>.<sub>`; its technical address is `dispatch://<uuid>`. Tasks
are grouped in **brackets**: a root, sub-position `0`, and the children `1`, `2` and so on. A root
that is closed has only closed children.

A task is in one of six states: `draft`, `open`, `active`, `on_hold` (with `question`,
`dependency` or `external`), `delivered`, `closed` (with `accepted`, `rejected`, `failed` or
`withdrawn`).

## 2. One form for every call

What names the target stands at the top level: the address, or scope and selector where no task
exists yet. The transport artefacts stand at the top level too: conflict token, receipt, duration,
idempotency key and the confirmation for a bracket root. Everything a call writes stands under
`fields`. Both levels are closed: an argument a call does not declare is refused, the refusal lists the
arguments the level accepts under `data.accepts` and never the name that arrived, and nothing is
written. JSON `null` on an argument means the argument is absent, at both levels and on both
surfaces: an optional argument takes its fallback, a required one is refused as
`ARGUMENT_MISSING`, and a name the call does not declare is not refused when its value is `null`.
The description of every optional argument names its fallback and says that `null` has the same
effect as leaving the argument out.

On REST the same calls carry the same names without the `dispatch_` prefix; the path carries the
scope and selector or the address, the `If-Match` header the conflict token, and the body the rest.

## 3. The calls

### 3.1 Transitions

**`dispatch_send`**
> Sends your draft: the task is frozen and becomes open, waiting for an executor to take it up.
> Moves the task from draft to open (not final). Call as the commissioner, with the conflict token
> of your last read.
>
> After this the commission's text cannot change; add to it with dispatch_annotate. Withdraw it
> with dispatch_withdraw.

**`dispatch_claim`** (concept section 3.5, word for word)
> Takes up one open task and makes you its holder. Moves the task from open to active (not final).
> Call as an executor, naming the task by its address.
>
> Returns a receipt and the end of your hold. Keep the receipt: every later call on this task
> needs it.
>
> The answer does NOT contain the task's text. Read it next with dispatch_read_text, part
> "dispatch", before you start working.
>
> Your hold lasts 30 minutes unless you state a duration; extend it with dispatch_renew.

**`dispatch_claim_next`** (concept section 3.5, word for word)
> Takes up the next open task addressed to you, without naming one; use dispatch_claim when you
> know the address. Moves the task from open to active (not final). Call as an executor with
> scope, selector and apparatus.
>
> Returns the address, a receipt and the end of your hold. Keep the receipt: every later call on
> this task needs it.
>
> The answer does NOT contain the task's text. Read it next with dispatch_read_text, part
> "dispatch", before you start working.
>
> If nothing matches, nothing is taken.

**`dispatch_release`**
> Gives a task you hold back: it is open again for any executor. Moves the task from active to
> open (not final). Call as its holder, with your receipt. Use it when you lose control over the
> run.
>
> To give it back until a later instant use dispatch_defer; if the work cannot be done,
> dispatch_fail.

**`dispatch_defer`**
> Gives a task you hold back until an instant you name; nobody can take it up before then. Moves
> the task from active to open (not final). Call as its holder, with your receipt. Use it for a
> technical abort that a later attempt may overcome.
>
> dispatch_read and dispatch_query show the instant.

**`dispatch_renew`**
> Extends your hold on a task you are working on. The task stays active (not final). Call as its
> holder, with your receipt.
>
> The answer carries the new end of your hold.

**`dispatch_ask`**
> Pauses a task you hold and asks the commissioner a question, with answer options, free text
> admitted, or both. Moves the task from active to on_hold (not final). Call as its holder, with
> your receipt.
>
> You keep the task. The commissioner's dispatch_answer makes it active again with a fresh
> 30-minute hold; read the answer with dispatch_read_text, part "thread".

**`dispatch_answer`**
> Answers the question the holder asked: name one of its options, or give text where free text is
> admitted. Moves the task from on_hold to active (not final); the holder continues with a fresh
> 30-minute hold. Call as the commissioner, with the conflict token of your last read.
>
> Read the question first with dispatch_read_text, part "thread".

**`dispatch_hold`**
> Pauses a task you hold while it waits on a dependency or on something external. Moves the task
> from active to on_hold (not final). Call as its holder, with your receipt. You keep the task;
> the hold does not run out while it is paused.
>
> Continue with dispatch_resume.

**`dispatch_resume`**
> Continues a task you paused with dispatch_hold. Moves the task from on_hold to active (not
> final). Call as its holder, with your receipt.
>
> A question you asked is continued by the commissioner's dispatch_answer, not by this call.

**`dispatch_deliver`**
> Delivers your answer, its text and your metadata in one act. Moves the task from active to
> delivered (not final). Call as its holder, with your receipt.
>
> You keep the task while the commissioner reviews it. It closes when accepted, or comes back to
> you with a remark through dispatch_rework.

**`dispatch_rework`**
> Sends a delivered answer back to its holder with a remark saying what to change. Moves the task
> from delivered to active (not final); the holder continues with a fresh 30-minute hold. Call as
> the commissioner, with the conflict token of your last read.
>
> Read the answer first with dispatch_read_text, part "return".

**`dispatch_accept`**
> Accepts the delivered answer and closes the task. Moves the task from delivered to closed,
> outcome accepted (final). Call as the commissioner, with the conflict token of your last read;
> the identity that delivered cannot accept.
>
> Read the answer first with dispatch_read_text, part "return". On a bracket root with unfinished
> children, repeat with the confirmation the refusal hands out.

**`dispatch_reject`**
> Declines a commission you were offered, with a remark saying why. Moves the task from open to
> closed, outcome rejected (final). Call as an executor.

**`dispatch_fail`**
> Closes a task you hold as failed, with a remark saying why. Moves the task from active or
> on_hold to closed, outcome failed (final). Call as its holder, with your receipt. For a
> technical abort use dispatch_defer; to hand the task back, dispatch_release.
>
> On a bracket root with unfinished children, repeat with the confirmation the refusal hands out.

**`dispatch_withdraw`**
> Withdraws a commission that is no longer wanted. Moves the task from open, active, on_hold or
> delivered to closed, outcome withdrawn (final). Call as the commissioner, with the conflict
> token of your last read.
>
> On a bracket root with unfinished children the first call is refused and names them; repeat it
> with the confirmation it hands out to withdraw them and the root together.

### 3.2 Calls that are not transitions

**`dispatch_create`**
> Creates a task as a draft, for you to complete and send. Without parent it opens a new bracket
> and the task is its root; with parent it adds a child to that bracket. The service allocates the
> number. Call as a commissioner; you become the task's commissioner.
>
> Nobody is offered the draft until you send it with dispatch_send. Change it with
> dispatch_update, or discard it with dispatch_delete.

**`dispatch_update`**
> Changes your draft: its title, apparatus, text or metadata; only what you give changes. Call as
> the commissioner, with the conflict token of your last read. The task stays a draft.
>
> A sent task cannot be changed; add to its text with dispatch_annotate.

**`dispatch_delete`**
> Deletes your draft outright; it leaves no trace. Call as the commissioner, with the conflict
> token of your last read. Only a draft can be deleted; a sent task is withdrawn with
> dispatch_withdraw.
>
> The answer carries the address the draft had, and nothing else.

**`dispatch_read`**
> Reads the head of one task: its state and attributes, whether you, someone else or nobody holds
> it, its metadata, which texts it has, and the calls open to you with what each does, or what the
> task waits for.
>
> The answer does NOT contain the task's text; read that with dispatch_read_text.

**`dispatch_read_text`**
> Reads one part of a task's text: "dispatch" (the commission), "return" (the valid answer),
> "thread" (questions, answers, remarks and earlier answers, in order) or "addenda". One part per
> call.
>
> The commissioner reads every part in every state; an executor reads only a task it holds. For
> "dispatch" and "return" the answer says how many addenda the text has.

**`dispatch_query`**
> Lists the tasks of one bracket kind in a scope, without their text: each with its state,
> attributes and the calls open to you. Narrow by state, apparatus, bracket or address;
> comma-separated values are alternatives, and an undeclared filter is refused.
>
> One page per call, in address order unless you sort otherwise. While next_cursor is in the
> answer, more follow: pass it as after, with the same other arguments, for the next page.

**`dispatch_annotate`**
> Adds an addendum to a text of a sent task: a supplement with its own time and author that cannot
> be removed. Name the part it supplements. Call as the identity that wrote that text; the task's
> state does not change.
>
> Read addenda with dispatch_read_text, part "addenda".

**`dispatch_relate`**
> Records the object a closed task was curated into: another task you can see, in any scope and
> bracket kind, never the task itself. Call as the commissioner, with the conflict token of your
> last read. The task stays closed.
>
> Remove the relation with dispatch_unrelate.

**`dispatch_unrelate`**
> Removes the record of the object a closed task was curated into. Call as the commissioner, with
> the conflict token of your last read. The task stays closed.

### 3.3 The argument `apparatus` of `dispatch_claim_next` (concept section 3.5, word for word)

> Which apparatus you draw for. Every task is addressed to an apparatus: the kind of executor it is
> meant for, such as "code" or "review". Give one or more patterns, matched as alternatives. "*"
> stands for any run of characters, so "agent-*" matches "agent-backend". Case-sensitive. A
> pattern of only "*" is refused. dispatch_query lists open tasks with their apparatus.

## 4. Answers

A writing call answers lean: `address`, `fields` with the state and its attributes (`hold_reason`,
`outcome`, `not_before`) and, for the holder, `lease_expires_at`; `conflict_token`; `next`.
`dispatch_claim` and `dispatch_claim_next` add `receipt`. `dispatch_read` and `dispatch_query`
answer the full head: in addition `title`, `apparatus`, `identity` (the technical address),
`holder` (`self`, `other` or `nobody`), `lapse_count`, the pending question's options, both
metadata, `curated_in`, and `texts`, the texts that exist by type and suffix, never their content.
`dispatch_query` answers `{ "objects": [...], "next_cursor": "..." }`, one page; `next_cursor` is
absent on the last page. It takes `page_size` (50 if omitted, a whole number from 1 to 200; any
other value is refused as `PAGE_SIZE_REJECTED`), `after` (the
`next_cursor` of the previous page), `sort` (`id` or `created_at`, `id` if omitted) and `direction`
(`asc` or `desc`, `asc` if omitted); `sort` and `direction` are closed sets of values, each value
described in the declaration. `id` sorts by the address, number and sub numerically;
`created_at` takes the address as its second key. A cursor continues only the listing that
handed it out: the same scope, selector, filter, sort and direction. `dispatch_delete` answers the address
and nothing else. No answer names the identity of an actor.

Text comes only from `dispatch_read_text`, one part per call. No other call, no listing and no
refusal carries a text of a task.

`next` lists the transitions open to the caller, each with one sentence. Right after a claim, its
first entry for the holder is (concept section 3.5, word for word):

```
{ "call": "dispatch_read_text",
  "does": "Reads the commission: the task's text, the work this claim takes on. Call it next, before you start the work." }
```

and for the commissioner of a delivered task, before `dispatch_accept`, it is `dispatch_read_text`
as well. Where nothing is open to the caller, `waiting_for` says what the task waits for; a
deferred task waits for "the instant in fields.not_before, and then an executor to take it up".
The step `dispatch_accept` is offered to the commissioner alone and says so: "Accepts, as the
commissioner, the answer the holder delivered, and closes the task."; the holder's step
`dispatch_deliver` says who takes it from there: "Delivers your answer and its metadata to the
commissioner, who accepts it or sends it back."

## 5. Refusals

```
{ "reason": "...", "message": "...", "data": { "attempted": "...", "state": "...", "next": [...] } }
```

Every refusal names the call as the caller made it, the reason and the way out; a refusal about a
task the caller may see carries its state in `data.state`. A refusal decided before the task is
read -- an argument, a scope, a bracket kind -- names no state, so a caller learns no state of a
task it may not see. Every member of `data` means one thing on every service. A refusal of a
call's arguments names them in `data.offenders`: the paths of the arguments it concerns, one under
`fields` with its path (`fields.title`), and the position of one value of several counted from
zero (`apparatus[2]`, `fields.options[1]`). A refusal of a name the call does not declare carries
no `offenders`: it lists the arguments the level accepts under `data.accepts`, with their paths,
and a call this service does not have is answered with the calls there are under `data.accepts`
and no `attempted`. `CHILDREN_NOT_FINISHED` names the unfinished children of the bracket root
under `data.children`, each with its address, its state and its `next`, beside
`data.confirmation`. `CONFLICT_TOKEN_STALE` carries the current token in `data.conflict_token`,
and `UNEXPECTED_FAILURE` its reference in `data.reference`. `NOT_FOUND` carries no `data` and the
same bytes whatever its cause.

Neither the message nor a member of `data` repeats the caller's input or the tenant's vocabulary
(DEC-0043 R18): no value the caller sent, no name a call does not declare, no scope, selector or
address the caller named, no declared bracket kind of a scope, no conflict token in the message,
no instant of a deferral. Where a statement needs one of these, the message points at the member
of `data` or the read that shows it. The message names the argument and the form it takes; the
form is one of the declared forms of section 5.1. Each reason has one remedy, an instruction
worded as the act the caller takes, and so is the sentence of every step in `next`.

| Reason | Remedy |
|---|---|
| `NOT_FOUND` | Check the scope and the address. |
| `STATE_DOES_NOT_ALLOW` | Make one of the calls in data.next. |
| `ROLE_DOES_NOT_ALLOW` | Make one of the calls in data.next. |
| `NOT_THE_HOLDER` | Claim the task first, or wait until its holder gives it back. |
| `RECEIPT_WRONG` | Pass the receipt from your latest claim. |
| `CHILDREN_NOT_FINISHED` | Repeat the call with data.confirmation, or finish the tasks in data.children first. |
| `DEFERRAL_PENDING` | Take the task up after that instant, or take up another task now. |
| `NOTHING_TO_TAKE` | List the tasks with the query call. |
| `CLAIM_DURATION_INVALID` | Send the duration as a positive ISO-8601 duration such as PT2H. |
| `PAGE_SIZE_REJECTED` | Send page_size as a whole number within the bounds the message names. |
| `CURSOR_MALFORMED` | Call the query again from the first page. |
| `CURSOR_FOREIGN` | Repeat the call with the arguments that handed the cursor out. |
| `SORT_UNDECLARED` | Send sort as id or created_at. |
| `ARGUMENT_UNKNOWN` | Use the names in data.accepts. |
| `ARGUMENT_MISSING` | Repeat the call with the argument in data.offenders. |
| `ARGUMENT_INVALID` | Send the argument in data.offenders in the form the message describes. |
| `CONFLICT_TOKEN_STALE` | Read the task again and repeat the call with data.conflict_token. |
| `SELECTOR_UNKNOWN` | Name a bracket kind the scope declares. |
| `SCOPE_KIND_UNSUPPORTED` | Name a project or a global scope. |
| `SCOPE_READ_ONLY` | Ask whoever administers the membership of this scope for the write right. |
| `SCOPE_LOCKED` | Wait for the lock to be lifted, or read instead. |
| `IDEMPOTENCY_KEY_REUSED` | Choose a new key. |
| `CALL_NOT_AT_THIS_ADDRESS` | Send the call to the route the message names. |
| `UNEXPECTED_FAILURE` | Report the reference. |

| Message | Reason | Pattern |
|---|---|---|
| `NOT_FOUND` | `NOT_FOUND` | `nothing is addressed here. Check the address, and that you are a member of the scope it names.` |
| `STATE` | `STATE_DOES_NOT_ALLOW` | `{call} is not possible in the task's present state, which data.state shows. You can make: {calls}.` |
| `STATE_NOTHING_OPEN` | `STATE_DOES_NOT_ALLOW` | `{call} is not possible in the task's present state, which data.state shows, and no call is open to you on it now; data.waiting_for says what it waits for.` |
| `STATE_CLOSED` | `STATE_DOES_NOT_ALLOW` | `{call} is not possible on this task: it is closed and finished. Nothing further can be done with it.` |
| `ROLE_COMMISSIONER` | `ROLE_DOES_NOT_ALLOW` | `{call} is the commissioner's call, and you take part in this task in another part.` |
| `ROLE_EXECUTOR` | `ROLE_DOES_NOT_ALLOW` | `{call} is an executor's call, and you take part here in another part.` |
| `ROLE_HOLDER` | `ROLE_DOES_NOT_ALLOW` | `{call} is the holder's call, and you take part in this task in another part.` |
| `ROLE_AUTHOR` | `ROLE_DOES_NOT_ALLOW` | `{call} is the call of the identity that wrote the text it supplements.` |
| `ROLE_OTHER_THAN_THE_DELIVERER` | `ROLE_DOES_NOT_ALLOW` | `{call} is the commissioner's call, made by an identity other than the one that delivered the answer.` |
| `ROLE_CAPACITY` | `ROLE_DOES_NOT_ALLOW` | `{call} needs a caller holding the commissioning or the executing capacity, and exactly one of them.` |
| `HOLDER_OTHER` | `NOT_THE_HOLDER` | `{call} is the holder's call, and another executor holds this task.` |
| `HOLDER_NOBODY` | `NOT_THE_HOLDER` | `{call} is the holder's call, and nobody holds this task.` |
| `HOLDER_LAPSED` | `NOT_THE_HOLDER` | `{call} is the holder's call, and your lease on this task ended.` |
| `TEXT_HOLDER_OTHER` | `NOT_THE_HOLDER` | `{call} reads the text of a task its executor holds, and another executor holds this one.` |
| `TEXT_HOLDER_NOBODY` | `NOT_THE_HOLDER` | `{call} reads the text of a task its executor holds, and nobody holds this one.` |
| `TEXT_HOLDER_LAPSED` | `NOT_THE_HOLDER` | `{call} reads the text of a task its executor holds, and your lease on this one ended.` |
| `RECEIPT_WRONG` | `RECEIPT_WRONG` | `{call} needs the receipt your claim handed out for this task: the receipt given is a different one.` |
| `CHILDREN_NOT_FINISHED` | `CHILDREN_NOT_FINISHED` | `{call} would close a bracket root while tasks of its bracket are unfinished; data.children lists them. Repeat the call with data.confirmation to withdraw them and close the root.` |
| `CHILDREN_CONFIRMATION_STALE` | `CHILDREN_NOT_FINISHED` | `{call} carries a confirmation that no longer holds: the unfinished tasks of the bracket changed since it was handed out, and data.children lists them now. Repeat the call with the one in data.confirmation.` |
| `DEFERRAL_PENDING` | `DEFERRAL_PENDING` | `{call} is not possible yet: the task is deferred, and {read} shows until when, in fields.not_before.` |
| `NOTHING_TO_TAKE` | `NOTHING_TO_TAKE` | `Nothing that your patterns match can be taken up in this bracket kind right now: every such task is a draft, held, paused, delivered, closed or deferred.` |
| `CLAIM_DURATION_INVALID` | `CLAIM_DURATION_INVALID` | `duration is not valid for {call}: it is a positive ISO-8601 duration such as PT2H.` |
| `PAGE_SIZE_REJECTED` | `PAGE_SIZE_REJECTED` | `page_size is not valid for {call}: it is a whole number from 1 to {max}.` |
| `CURSOR_MALFORMED` | `CURSOR_MALFORMED` | `after is not a cursor {query} handed out: it is the next_cursor of the previous page.` |
| `CURSOR_FOREIGN` | `CURSOR_FOREIGN` | `{call} carries a cursor of another listing: it was handed out for other values of {differs}. A cursor continues only the listing that handed it out.` |
| `SORT_UNDECLARED` | `SORT_UNDECLARED` | `sort is not valid for {call}: it takes id or created_at.` |
| `ARGUMENT_UNKNOWN` | `ARGUMENT_UNKNOWN` | `{call} takes these arguments: {accepts}.` |
| `ARGUMENT_UNKNOWN_UNDER_FIELDS` | `ARGUMENT_UNKNOWN` | `{call} takes these arguments under fields: {accepts}.` |
| `CALL_UNKNOWN` | `ARGUMENT_UNKNOWN` | `This service takes these calls: {accepts}.` |
| `ARGUMENT_MISSING` | `ARGUMENT_MISSING` | `{call} needs {path}.` |
| `ARGUMENT_MISSING_OPTIONS` | `ARGUMENT_MISSING` | `{call} needs fields.options, or fields.free_text set to true.` |
| `ARGUMENT_MISSING_ANSWER` | `ARGUMENT_MISSING` | `{call} needs fields.option or fields.text.` |
| `ARGUMENT_MISSING_CHANGE` | `ARGUMENT_MISSING` | `{call} needs at least one of fields.title, fields.apparatus, fields.text and fields.metadata.` |
| `ARGUMENT_INVALID` | `ARGUMENT_INVALID` | `{path} is not valid for {call}: {form}.` |
| `ARGUMENT_INVALID_IN_REQUEST` | `ARGUMENT_INVALID` | `{path} is not valid for this request: {form}.` |
| `VALUE_INVALID` | `ARGUMENT_INVALID` | `A value given is not valid for {call}: {form}.` |
| `CONFLICT_TOKEN_STALE` | `CONFLICT_TOKEN_STALE` | `{call} carries a conflict token that is not the one the task holds: it was changed since you read it. Its current token is in data.conflict_token.` |
| `SELECTOR_UNKNOWN` | `SELECTOR_UNKNOWN` | `selector names a bracket kind this scope does not declare.` |
| `SCOPE_KIND_UNSUPPORTED` | `SCOPE_KIND_UNSUPPORTED` | `The scope named is of a kind this service carries no tasks in; it carries tasks in project and global scopes.` |
| `SCOPE_READ_ONLY` | `SCOPE_READ_ONLY` | `{call} writes, and you may read this scope without writing to it. Reading it is unaffected; the write right is granted with the membership.` |
| `SCOPE_LOCKED` | `SCOPE_LOCKED` | `This scope is locked, so it refuses every write whatever your role. Reading it is unaffected. The lock is lifted where it was set.` |
| `IDEMPOTENCY_KEY_REUSED` | `IDEMPOTENCY_KEY_REUSED` | `The idempotency key given was spent on a different call, or with different arguments, in this scope within the last 24 hours.` |
| `ROUTE_CREATE` | `CALL_NOT_AT_THIS_ADDRESS` | `{call} is a call on a bracket kind: POST /api/<scope>/<selector>.` |
| `ROUTE_QUERY` | `CALL_NOT_AT_THIS_ADDRESS` | `{call} is a call on a bracket kind: GET /api/<scope>/<selector>.` |
| `ROUTE_ON_A_BRACKET_KIND` | `CALL_NOT_AT_THIS_ADDRESS` | `{call} is a call on a bracket kind: POST /api/<scope>/<selector>:{call}.` |
| `ROUTE_READ` | `CALL_NOT_AT_THIS_ADDRESS` | `{call} is a call on a task: GET /api/<scope>/<selector>/<number>.<sub>.` |
| `ROUTE_READ_CALL` | `CALL_NOT_AT_THIS_ADDRESS` | `{call} is a call on a task: GET /api/<scope>/<selector>/<number>.<sub>:{call}.` |
| `ROUTE_UPDATE` | `CALL_NOT_AT_THIS_ADDRESS` | `{call} is a call on a task: PATCH /api/<scope>/<selector>/<number>.<sub>.` |
| `ROUTE_DELETE` | `CALL_NOT_AT_THIS_ADDRESS` | `{call} is a call on a task: DELETE /api/<scope>/<selector>/<number>.<sub>.` |
| `ROUTE_ON_A_TASK` | `CALL_NOT_AT_THIS_ADDRESS` | `{call} is a call on a task: POST /api/<scope>/<selector>/<number>.<sub>:{call}.` |
| `UNEXPECTED_FAILURE` | `UNEXPECTED_FAILURE` | `{call} failed unexpectedly. This is a defect, not a rule. Nothing was changed. Report the reference in data.reference.` |
| `UNEXPECTED_FAILURE_OF_REQUEST` | `UNEXPECTED_FAILURE` | `The request failed unexpectedly. This is a defect, not a rule. Nothing was changed. Report the reference in data.reference.` |

### 5.1 The forms an argument takes

`ARGUMENT_INVALID` names the form the argument takes as one of these, in place of `{form}`:

| Form | Text |
|---|---|
| `FIELDS_OBJECT` | `it carries the values this call writes, and is an object` |
| `STRING` | `it is a string` |
| `ONE_OF` | `it is one of the values the description of the argument lists` |
| `LIST_OF_STRINGS` | `it is a list of strings, even when it carries one value` |
| `OBJECT` | `it is an object` |
| `BOOLEAN` | `it is true or false` |
| `WHOLE_NUMBER` | `it is a whole number` |
| `PARENT_ELSEWHERE` | `it names another scope or bracket kind than the call does, and a child is created in its root's bracket` |
| `PARENT_NOT_A_ROOT` | `a parent is a bracket root, the <number>.0 of its bracket` |
| `APPARATUS_PATTERN` | `a pattern is one or more of A-Z, a-z, 0-9, '+', '-' and '*', and '*' is the only wildcard; the underscore and the percent sign carry a meaning of their own in the comparison and are excluded` |
| `APPARATUS_UNBOUNDED` | `a pattern of nothing but '*' matches every apparatus, which is the blind draw the argument exists to refuse` |
| `STATE_FILTER` | `it is one or more of draft, open, active, on_hold, delivered and closed, comma-separated` |
| `BRACKET_FILTER` | `it is one or more bracket numbers, comma-separated` |
| `ADDRESS_FILTER` | `it is one or more complete task addresses, comma-separated` |
| `ADDRESS_FILTER_ELSEWHERE` | `a listing filters the tasks of its own scope and bracket kind` |
| `VALUE_FILTER` | `it is one or more values, comma-separated` |
| `LEASE_END` | `a lease ends before the year 294276` |
| `INSTANT` | `an instant is written in ISO-8601 with its offset, such as 2026-10-07T09:00:00Z` |
| `INSTANT_RANGE` | `an instant lies from 0001-01-01T00:00:00Z up to +294276-01-01T00:00:00Z` |
| `ANSWER_ONE_OF_TWO` | `an answer names an option or carries text, not both` |
| `ADDRESS` | `a task's address is written dispatch://<scope>/<selector>/<number>.<sub>` |
| `SCOPE` | `a scope's name is a DNS label: lower case, digits and inner hyphens` |
| `SELECTOR` | `a bracket kind's name is lower case, digits and inner hyphens` |
| `METADATA` | `metadata carries your own keys, each value a string or a list of strings of at most 512 characters, and no URL carrying credentials` |
| `CURATION_TARGET` | `it names another task than this one` |
| `ADDENDUM_TARGET` | `it names a part the task carries a text of; the read of the task lists them under fields.texts` |
| `ADDENDA_EXHAUSTED` | `that text already carries addenda a to z, and a further letter would reissue one that exists` |
| `ANSWER_OPTION` | `it is one of the question's options, which the read of the task lists` |
| `ANSWER_FREE_TEXT` | `the question admits no free text; name one of its options, which the read of the task lists` |
| `VALUE` | `it is a value this call takes` |
| `ONCE` | `it arrived twice: in the query or the body, and in the path or a header, and a call takes each argument once` |
| `BODY` | `a body is a JSON object of the call's arguments` |
| `SURFACE_HEADER` | `it takes the one value assistant, once, or is left out` |
