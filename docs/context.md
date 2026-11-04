# Context strategy — M1.5 contract, M2 expansion

**What "context" means:** the minimum information Chomiii needs to understand *this* request correctly. Context is not synonymous with memory, a full transcript, or a giant prompt. The default should be **small, relevant, owner-authorized, and quick**.

## Context sources, trust and status

| Source | Trust boundary | M1.5 status | Later stage |
| --- | --- | --- | --- |
| Stable runtime identity (`SYSTEM.md`) | Trusted application resource | Loaded at app startup | Remains stable/versioned |
| Current user turn | User role, not system rules | Sent to provider | Same |
| Recent complete conversation history | Owner-scoped role-tagged data | Selected by `ContextWindow` | Improved token-aware selection |
| Personal preferences, commitments, facts | Constrained/consented user data | **M2 PR #3:** manual and opt-in learned owner-scoped memories and bounded lexical recall | M2 release hardening |
| Notes, files, web results, app records | Untrusted source data | **Not implemented** | M2/M4 retrieval |
| Task state, action results, deadline changes | Verified backend state | **Not implemented** | M3/M5 |
| Date/time and app capabilities | Typed runtime context | **Not implemented** | Added only when needed |

## What the application actually does today

- `ChatStreamService` loads one packaged, short `agent/SYSTEM.md` identity; user content cannot edit it.
- `ChatTurnStore.begin` checks conversation ownership, writes the new user turn, and reads stored role/status-tagged history.
- `ContextWindow.select` includes the current user message and the **newest complete** conversation turns, at most **20 messages and 16,000 Java characters** in total; it excludes interrupted/failed messages and retains chronological order.
- This is a **character budget**, not a model token budget, and it does not summarize or retrieve older conversations. One browser cookie scopes existing histories; it is not a signed-in cross-device identity.
- Context construction is measured separately from store and provider latency. No memory search or classification LLM call is performed before streaming.

## M1.5 assembly contract

```mermaid
flowchart LR
    S["Versioned SYSTEM.md"] --> A["Compose provider input"]
    H["Owner-checked recent history"] --> A
    U["Current user request"] --> A
    A --> P["ChatProvider: system + role-tagged messages"]
    P --> R["Stream response"]
```

1. Trusted instructions have a stable order and a strict size limit; do not mix retrieved text into their role.
2. User requests and external content remain lower-trust context, even when they contain imperative instructions.
3. Use a bounded, deterministic selector before inference. The core experience must not depend on a second model call to choose an ordinary prompt skill.
4. Keep dynamic facts out of the stable prompt prefix when possible; avoid pretending today's date or capabilities are available if not supplied.
5. If context retrieval eventually fails or times out, **degrade gracefully** rather than blocking a response indefinitely.
6. Do not imply a fact is stored, current or verified without knowing its source.

## M2: proper personal memory

Store useful user-authorized context separate from transcripts. Require owner identity, consent/visibility, provenance, timestamps, correction and deletion. Retrieve the few facts relevant to the request; do not push every memory into every prompt. Distinguish what the person told us, what they approved us to save, and what the model inferred.

Before adopting Agent Beacon, Mem0, Letta, or a homegrown store, evaluate privacy, local/cloud flows, life-cycle deletion, isolation and latency against consumer needs. Agent Beacon starts as an **evaluation candidate**, not an assumed fit: it is oriented toward coding-agent traces and lessons.

## M2.5: skills and task-specific context

A skill is useful only when it adds tested workflow value, such as an appointment planner or budgeting flow with structured inputs and later real tools. Do not require users to type `/skill` or select a generic "writing" plugin to do everyday tasks. Load only a relevant skill body, if any, and keep safety decisions in code rather than prompt prose.

## Context for coordinated everyday tasks (future design)

A request such as "I'm working late; notify the people I'm meeting and check
my usual groceries" may depend on *several different kinds* of information.
The current turn explains what changed; conversation history provides local
background; M2 personal memory may supply a previously approved usual grocery
basket; M4 calendar/contact tools provide fresh external facts; and M3/M5
action/task state records which operations are pending, approved, attempted or
completed. **These are not interchangeable data sources.**

Memory must carry provenance and be distinguishable from a live cart, email,
calendar invitation or actual purchase. A user may change a preference or
withdraw permission without changing a historical statement. When a source is
missing, stale or disconnected, ask for the relevant fact or offer prepared
work; never fabricate a "usual" preference, contact identity, availability
or action result. Retrieval should be relevance- and permission-scoped, not an
automatic dump of every known fact.

This is a future M2–M5 context composition problem and **not a demand** to
inject these data types into M1.5. See the [evaluation scenarios](design/experience-evaluation.md).

## What to measure

- Base system-prompt size and response context size; move from Java characters to provider-aware token estimates when supported.
- Frequency of missing or irrelevant context, user corrections and false memory claims.
- Context assembly p50/p95 and provider first-text p50/p95 on matched prompts.
- Privacy correctness: no cross-owner recall and no leaked file or tool content in trusted instructions.
- Whether remembering a fact or loading a workflow **actually improves task completion**.

**Acceptance for M1.5:** documented context boundaries, short trusted identity, deterministic current-message/history separation, focused tests, and no extra pre-stream inference. M2 and M2.5 are plans, not features already available.

## M2 implementation note (PR #3, not merged)

The proposed memory service stores user-explicit entries plus optionally learned
conversation facts and reviewable inferences. It performs
bounded, owner-scoped lexical selection on normal chat turns. The selected
memories are added as lower-trust **user-role context** immediately before
the current request, never as trusted system instructions. The app includes
a browser-visible **Use saved memories** toggle, which sends an opt-out
header for that turn.

This still uses browser-cookie ownership, not stable signed-in accounts.
A memory is not a source of instructions or authority to act, and relevant
memories will be transmitted to the configured model provider. The memory
lookup does not invoke another model. Retrieval/latency improvements must
be measured rather than assumed. See [M2 design](m2-design.md).
