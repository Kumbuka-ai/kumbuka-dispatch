---
type: concept
title: "The dispatch service's assistant surface: process verbs, answers that name the next step, and refusals that explain themselves"
created: 2026-09-18
domain: platform
---

# The dispatch service's assistant surface

This document is the verb contract of the dispatch service for its assistant-facing surface (MCP).
It is written against the state machine as measured on 2026-09-18 and serves REQ-0150 to REQ-0154;
its answer and refusal shapes follow DEC-0040 and DEC-0042, and its rules DEC-0043. The tool
descriptions in section 5, the message patterns in section 4.4 and the table in section 6 are
normative text: the conformance probes of the service and of the router take their expected values
from this document, never from the code.

It builds on the draft standard for the verb surface (`concept-verb-surface-standard.md`), which
inventoried the divergences between the services, and departs from it in one respect: the assistant
surface of a service carries process verbs of its own rather than the generic verbs. The generic
surface (REST) keeps its generic verbs; its answers and refusals follow sections 3 and 4 all the same,
in its own vocabulary.

## 1. What the service holds

An **exchange** is one commission and its answer, addressed as
`dispatch://<scope>/<selector>/<number>.<sub>`.

A **bracket** is a group of exchanges sharing a number. Its root is sub-position `0`; its children
are `1`, `2`, and so on. A bracket is finished when its root is terminal, and its root can only
become terminal when every child is.

An **addendum** is a correction attached to an exchange that is frozen and not yet finished,
addressed with a letter after the sub-position (`.3a`). It is not a child and closes with the
exchange it corrects.

## 2. States and roles

| State | Meaning | Terminal |
|---|---|---|
| `open` | commissioned and frozen, waiting for someone to take it up | no |
| `active` | taken up; a holder works on it | no |
| `needs_input` | the holder waits for the commissioner, with either a question or a delivered answer | no |
| `returned` | the answer is frozen and awaits closure (reached only through the generic surface) | no |
| `closed` | finished | yes |
| `consumed` | finished, its answer carried forward into a named object | yes |
| `rejected` | declined before it was taken up | yes |
| `failed` | abandoned after it was taken up | yes |

`draft` exists only inside the transaction of `dispatch_commission` and is never visible on this
surface.

A caller takes one of four parts in an exchange:

- the **commissioner** gives and accepts work and must be a console identity;
- the **holder** has taken the exchange up and proves it with the receipt its take returned;
- a **candidate** could take the exchange up but has not: an executor, on an `open` exchange;
- a **bystander** is anyone else who may see the exchange.

One identity may act in several parts on different exchanges, never as commissioner and holder of
the same one.

## 3. The answer

Every answer about one exchange has this shape (DEC-0040):

```
{
  "address": "dispatch://kumbuka/satellite/26.1",
  "fields":  { ...the exchange as the caller may see it... },
  "conflict_token": "...",
  "next": [
    { "call": "dispatch_accept_return",     "does": "Accepts the delivered answer and finishes the exchange." },
    { "call": "dispatch_reply_to_executor", "does": "Sends it back with a message; the holder continues." }
  ],
  "waiting_for": null
}
```

`next` lists exactly the calls this caller can make successfully from the current state, by the
object's state, the caller's part and the preconditions the exchange carries (section 6): every
listed call succeeds for this caller, and every call that would succeed is listed. `waiting_for`
is present only where `next` is empty and the exchange is not finished, and names who or what it
waits for. A listing carries the same two members on every entry.

**Every address is complete.** Every address anywhere in an answer or a refusal -- the top-level
`address`, every entry of a listing, every reference inside `fields` (the bracket root of a child,
the target of a curated answer), every address in a refusal's `message` and `data`, every address
in `next` or `waiting_for` -- is the complete URI, exactly as every call takes it. Measured on
2026-09-18: `create` and `claim` answered `satellite/26.2` and `satellite/26.4`, and a refusal
listed a blocking child as `satellite/26.1 (draft)`.

**No argument is accepted and discarded.** Every call refuses an argument it does not declare, by
name, including an argument nested in `fields` (REQ-0113); and every argument a call declares has
the effect its declaration states. Measured on 2026-09-18: `create` accepted `draft` inside its
body, discarded it and answered with success.

**An idempotency key means what it says.** A call that takes `idempotency_key` and is repeated by
the same caller in the same scope with the same key, within 24 hours of the first, creates nothing
and answers with the answer the first call produced, in the object's current state. The same key
with different arguments is refused as `IDEMPOTENCY_KEY_REUSED`.

## 4. The refusal

### 4.1 Shape

```
{
  "reason": "STATE_DOES_NOT_ALLOW",
  "message": "dispatch_accept_return is not possible on dispatch://kumbuka/sprint/183.1: the exchange is closed and finished. Nothing further can be done with it.",
  "data": {
    "attempted": "dispatch_accept_return",
    "state": "closed",
    "next": []
  }
}
```

### 4.2 Rules for every message

- It names the call the caller made, under the name the caller used on its surface. Kernel names
  never appear.
- Every other call it names -- in the message and in `data.next` -- is named in the vocabulary of
  the surface the caller used. A pattern never names a call literally; it names a step, written
  below as `<take>`, `<deliver>`, `<query>`, `<read>`, and the surface fills in its own call for
  that step (`dispatch_take` on the assistant surface, the corresponding REST verb on the generic
  surface).
- It names the complete address and, where the caller may see it, the state.
- It gives the specific reason in one sentence.
- It names what the caller can do instead; `data.next` lists the same calls.
- Lists of objects travel in `data`, never in a member of their own.
- It is built from the pattern alone. A sentence produced by the kernel never reaches the caller,
  in whole or in part.

### 4.3 The one deliberately indistinguishable refusal

An object that does not exist, a scope the caller may not see and an address that cannot be routed
receive the same refusal, with no `data` and therefore no list (DEC-0042). It names neither the call
nor the address, so that it is byte-identical across the three causes; the caller knows both from its
own request:

> `NOT_FOUND`: Nothing is visible to you at the address you gave. The address or the scope may be
> wrong, or you may lack access; for your protection and that of others these cases are not told
> apart. Check the scope name and the number.

### 4.4 Refusals of this surface

| Reason | Message pattern | What the caller can do |
|---|---|---|
| `NOT_FOUND` | 4.3 | check scope and address |
| `STATE_DOES_NOT_ALLOW` | `<call> is not possible on <address>: it is <state>. From <state> you can: <calls>.` -- for a terminal state: `... it is <state> and finished. Nothing further can be done with it.` | the calls in `data.next` |
| `ROLE_DOES_NOT_ALLOW` | `<call> can only be made by the <required part>. You take part in <address> as <your part>.` -- `<your part>` is the caller's actual part from section 2 | the calls in `data.next` |
| `NOT_THE_HOLDER` | `<address> is held by someone else until <claim expiry>. Only the holder can <what the call does>.` -- `<claim expiry>` is the ISO-8601 instant at which the claim lapses | wait, or `<read>` |
| `RECEIPT_MISSING` / `RECEIPT_WRONG` | `<call> needs the receipt you received from <take> for <address>.` / `... the receipt given is not the one issued for <address>.` | pass the receipt from the take |
| `NO_ANSWER_DELIVERED` | `<address> has no delivered answer to accept. The holder delivers with <deliver>.` | wait for the executor |
| `CHILDREN_NOT_FINISHED` | `<root> cannot be <closed / cancelled> while <n> exchange(s) of the bracket are unfinished: <address> (<state>), ...` with `data.offenders` as `[{ address, state, next }]`; each offender's `next` is computed for this caller on this surface | the calls in each offender's `next` |
| `NOTHING_TO_TAKE` | `Nothing in dispatch://<scope>/<selector> can be taken up right now: every exchange is finished, held by someone, or waiting for its commissioner.` | `<query>` |
| `CLAIM_DURATION_INVALID` | `The duration <value> is not a positive ISO-8601 duration such as PT2H.` | correct the duration |
| `IDEMPOTENCY_KEY_REUSED` | `The idempotency key <key> was used for a different <call> in scope <scope> within the last 24 hours.` | choose a new key |
| `CALL_NOT_AT_THIS_ADDRESS` | `<call> cannot be made on <address>: it applies to <what it applies to>. On <address> you can: <calls>.` | the calls in `data.next` |
| `ARGUMENT_UNKNOWN` | `<call> has no argument named <name>. Its arguments are: <list>.` | correct the name |
| `ARGUMENT_MISSING` | `<call> needs <name>: <what it is>.` | supply it |
| `ARGUMENT_INVALID` | `<name> = <value> is not valid for <call>: <why>.` -- `<why>` is a sentence of the pattern's own, never a kernel sentence | correct the value |
| `CONFLICT_TOKEN_MISSING` / `CONFLICT_TOKEN_STALE` | as DEC-0041 fixes them, with the message naming the call, the address and that the exchange was changed since it was read | read again and repeat |
| `SELECTOR_UNKNOWN` | `<selector> is not a bracket kind declared in scope <scope>. Declared: <list>.` -- `<list>` is every selector declared in that scope | use a declared one |
| `UNEXPECTED_FAILURE` | `<call> on <address> failed unexpectedly. This is a defect, not a rule. Nothing was changed. Report reference <ref>.` -- `<ref>` is written to the service's log with the failure, on every path that raises it | report the reference |

A scope that cannot be resolved receives the `NOT_FOUND` refusal and is never distinguished from it.

On the assistant surface the receipt and the conflict token are required arguments wherever a call
takes them, so their absence is answered as `ARGUMENT_MISSING`; `RECEIPT_MISSING` and
`CONFLICT_TOKEN_MISSING` are raised on the generic surface only, where both are optional in form.

A reason not in this table cannot be returned: the service refuses to start with an undeclared reason
in its catalogue.

## 5. The process verbs

Each entry gives the tool name, its description (normative), its arguments, the part that may call
it, the effect on the kernel and the resulting state. Arguments follow DEC-0040: those that choose
the target and the transport artefacts are top-level; everything the call writes is under `fields`.

### 5.1 Commissioner

**`dispatch_commission`**
> Give a piece of work to someone. Creates the exchange and freezes it in one step: once
> commissioned, the text cannot be changed, only corrected with dispatch_add_correction. Without
> `parent` it opens a new bracket and the exchange becomes its root; with `parent` it adds a child
> to that bracket. The service allocates the number. Afterwards the exchange is open and waits for
> an executor to take it up with dispatch_take.

Top-level: `scope`, `selector`, optional `parent` (complete address of a bracket root), optional
`idempotency_key`. Under `fields`: `title`, `apparatus`, `text`, optional `date` (default today),
optional `metadata`. Part: commissioner. Kernel: create, write the dispatch body, send, in one
transaction; a failure leaves nothing behind. Result: `open`.

**`dispatch_add_correction`**
> Attach a correction to a commissioned exchange whose text is frozen. The correction is shown with
> the exchange, cannot be removed, and closes together with it. Not possible once the exchange is
> finished.

Top-level: `address`, optional `idempotency_key`. Under `fields`: `title`, `text`. Part:
commissioner. From: any state that is not terminal. Kernel: append. Result: the exchange, unchanged
in state.

**`dispatch_accept_return`**
> Accept the answer the executor delivered and finish the exchange. The answer is frozen and the
> exchange becomes closed. Use dispatch_curate_return instead if the answer is to be carried forward
> into another object, and dispatch_reply_to_executor if it needs rework.

Top-level: `address`. Part: commissioner. From: `needs_input` with a delivered answer, or
`returned`; not on a bracket root. Kernel: ratify and close in one transaction. Result: `closed`.

**`dispatch_curate_return`**
> Accept the delivered answer and finish the exchange by carrying it forward into another exchange
> you can see, for example the record of the bracket it belongs to. The target is stored with the
> exchange.

Top-level: `address`. Under `fields`: `into`, the complete address of the target: an exchange of
this service that the caller may see, in any scope and bracket kind, other than the exchange itself.
Part and from-states as `dispatch_accept_return`. Kernel: ratify and consume in one transaction;
`into` is resolved and stored as the target's durable identity (ADR-0014). A target the caller
cannot see is answered `NOT_FOUND`; the exchange itself as its own target is `ARGUMENT_INVALID` on
`into`. Result: `consumed`.

**`dispatch_reply_to_executor`**
> Answer the executor: either a question it asked, or a delivered answer that needs rework. The
> message is stored with the exchange and the holder continues working.

Top-level: `address`, `conflict_token`. Under `fields`: `message`. Part: commissioner. From:
`needs_input`. Kernel: store the message, resume. Result: `active`.

**`dispatch_cancel`**
> Withdraw a commission that is no longer wanted. The exchange is closed without an accepted answer.
> On a bracket root this ends the bracket without a record, and is refused while any exchange of
> the bracket is unfinished.

Top-level: `address`, `conflict_token`. Under `fields`: `reason`. Part: commissioner. From: `open`,
`active`, `needs_input`; on a bracket root only when every child is terminal. Kernel: store the
reason, close. Result: `closed`.

**`dispatch_close_bracket`**
> Finish a bracket: accept the record delivered on its root and close it. Refused while any exchange
> of the bracket is unfinished; the refusal names each one with its complete address and the call
> that would finish it.

Top-level: `address` of the root. Part: commissioner. From: root in `needs_input` with a delivered
record, or `returned`. Kernel: check children terminal, ratify, close, in one transaction. Result:
`closed`.

### 5.2 Executor

**`dispatch_take`**
> Take up an open exchange to work on it. Returns a receipt that later calls on this exchange need;
> keep it. The claim lasts for `duration`.

Top-level: `address`, `duration`. Part: candidate. Kernel: claim. Result: `active`, plus `receipt`.

**`dispatch_take_next`**
> Take up the next open exchange of a bracket kind, in address order. Returns the exchange and the
> receipt.

Top-level: `scope`, `selector`, `duration`. Part: executor. Kernel: claim_next. Result: `active`,
plus `receipt`.

**`dispatch_deliver_return`**
> Deliver your answer to the commissioner. The answer is stored and the exchange waits for the
> commissioner to accept it, curate it or send it back. You keep the exchange meanwhile.

Top-level: `address`, `receipt`. Under `fields`: `text`. Part: holder. From: `active`. Kernel: write
the return draft and block, in one transaction. Result: `needs_input`.

**`dispatch_ask_commissioner`**
> Stop and ask the commissioner something you cannot decide. The question is stored with the
> exchange; you keep it until the commissioner replies.

Top-level: `address`, `receipt`. Under `fields`: `question`. Part: holder. From: `active`. Kernel:
store the question, block. Result: `needs_input`.

**`dispatch_decline`**
> Decline the work. On an open exchange, any executor who could take it up may decline it, and the
> commission is refused. On an exchange you hold, it records that you could not complete it, and
> needs your receipt. The reason is stored. Final.

Top-level: `address`, and `receipt` when the caller is the holder. Under `fields`: `reason`. Part:
candidate on `open` (kernel reject, result `rejected`); holder on `active` (kernel fail, result
`failed`).

### 5.3 Every part

**`dispatch_read`**
> Read one exchange with what you may see of it, the calls open to you from its state, and who it is
> waiting for.

Top-level: `address`.

**`dispatch_query`**
> List the exchanges of one bracket kind in a scope, narrowed by filters. Each entry carries its
> complete address, its state and the calls open to you.

Top-level: `scope`, `selector`, optional filters on the declared fields.

## 6. How `next` is computed

For a caller C and an exchange E in state S, from the same table that enforces the transitions
(DEC-0040). Where the column `waiting_for` is empty, `next` is not empty and `waiting_for` is absent.

| S | C is | `next` | `waiting_for` |
|---|---|---|---|
| `open` | commissioner | `dispatch_add_correction`, `dispatch_cancel` (root: only if every child is terminal) | |
| `open` | candidate | `dispatch_take`, `dispatch_decline` | |
| `open` | bystander | (none) | an executor to take it up |
| `active` | holder | `dispatch_deliver_return`, `dispatch_ask_commissioner`, `dispatch_decline` | |
| `active` | commissioner | `dispatch_add_correction`, `dispatch_cancel` (root: only if every child is terminal) | |
| `active` | candidate or bystander | (none) | the holder |
| `needs_input`, answer delivered | commissioner | `dispatch_accept_return`, `dispatch_curate_return`, `dispatch_reply_to_executor`, `dispatch_add_correction`, `dispatch_cancel` -- on a root: `dispatch_close_bracket` (only if every child is terminal) instead of the first two, and `dispatch_cancel` only if every child is terminal | |
| `needs_input`, question asked | commissioner | `dispatch_reply_to_executor`, `dispatch_add_correction`, `dispatch_cancel` (root: only if every child is terminal) | |
| `needs_input` | holder, candidate or bystander | (none) | the commissioner |
| `returned` | commissioner | `dispatch_accept_return`, `dispatch_curate_return`, `dispatch_add_correction` -- on a root: `dispatch_close_bracket` (only if every child is terminal) instead of the first two | |
| `returned` | anyone else | (none) | the commissioner |
| any terminal | anyone | (none) | nobody: the exchange is finished |

`dispatch_read` is always available on a visible exchange and is not listed. On the generic surface
the same table applies with that surface's verbs.

## 7. Changes to the service this contract requires

1. The twelve process verbs and the two reading verbs of section 5 on the MCP adapter, replacing the
   fifteen generic tools there.
2. Atomic compound transitions: commission (create, write, send), accept (ratify, close), curate
   (ratify, consume), close-bracket (check, ratify, close), deliver (write, block).
3. Stored values: `into` for a consumed exchange, resolved to the target's durable identity; the
   commissioner's message, the executor's question, and the reason of a cancellation or a decline.
4. `next` and `waiting_for` in every answer and on every listing entry, and `next` inside `data` of
   every refusal that carries `data`, computed from the transition table, the caller's part and the
   exchange's preconditions.
5. Refusal messages under section 4: surface names instead of kernel names, the way out instead of
   the way in, lists in `data`, every call named in the caller's surface vocabulary.
6. Every address in every answer and refusal in complete form.
7. Closed input schemas on the service and on the router: no argument accepted and discarded, and no
   declared argument without its effect. The router's schema for `create` currently declares
   `additionalProperties: true` on `body`, which is the path by which `draft` reached the service and
   was dropped there.
8. Idempotency keys remembered per caller and scope for 24 hours.
9. A catalogue of declared reasons checked at start-up.
10. The generic REST surface returns the same answers and refusals, in its own vocabulary.

## 8. Open points

- `needs_input` carries two meanings, a question and a delivered answer, told apart by whether an
  answer is present. A dedicated state for a delivered answer would be cleaner and changes the
  kernel; this contract works without it.
- The executor's text-bearing verbs rely on the claim and the receipt for exclusivity and take no
  conflict token; the commissioner's verbs on an existing exchange do. The departure on the executor
  side is deliberate.
- Every console identity may act as commissioner of any exchange it may see. Narrowing the part to
  the identity that commissioned the exchange is a change of rights and is not decided here.
