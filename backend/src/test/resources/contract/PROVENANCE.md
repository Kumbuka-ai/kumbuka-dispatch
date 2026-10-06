# Where `assistant-surface.md` came from

`assistant-surface.md` is **written by hand** from

    platform-specs @ aa425214ce66cefa3ce4a5789c76a4d778db560d
    docs/architecture/targets/TAR-0004-the-dispatch-service-carries-commissioned-work-from-issue-to-acceptance.md
    docs/concepts/concept-dispatch-store-kernel-and-surface.md

on 2026-10-06, for dispatch 200.7. It replaces the verbatim copy of the
earlier contract of the assistant surface, whose process verbs this surface no
longer carries.

## What is word for word, and what is not

The descriptions of `dispatch_claim` and `dispatch_claim_next`, the description
of the argument `apparatus` and the first entry of `next` after a claim are
concept section 3.5 word for word; the line breaks of the concept's code blocks
are its layout and are not part of the text. The concept fixes no other
wording, so the remaining descriptions and the refusal patterns are written
here, beside the surface, and are the service's proposal until the concept
apparatus ratifies them.

## Why a document and not a list in Java

The conformance tests hold the declaration against this document and against
the fixed values in `contract/TargetSurface.java`, both written from the
specification and neither from the code. A copy that is stale turns a test red
and somebody looks; an expectation derived from the declaration it checks is
green for ever and says nothing.
