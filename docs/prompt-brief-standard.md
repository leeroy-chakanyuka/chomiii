# Prompt brief standard — Chomiii product-first engineering

**Decision (2026-11-04):** Every implementation prompt written for Chomiii, whether issued to OpenCode, another coding agent, or a developer, must make the product purpose visible. This repository rule also serves as a durable reference across future chat sessions.

Chomiii is a **general-purpose personal assistant for ordinary people**. The word *agent* refers to useful, consented action and continuing context—not an obligation to optimize for programming.

## Four questions that every prompt answers

| Question | Required content |
| --- | --- |
| **1. What do we want to accomplish?** | The intended end-user capability and the narrow implementation slice. |
| **2. Why?** | The actual friction or task burden this removes, and why it matters at this milestone. |
| **3. For whom?** | A plausible everyday user and the constraints that affect them, such as mobile UX, budget, privacy, speed, or non-technical onboarding. |
| **4. What would success look like?** | Testable acceptance criteria: normal path, failure path, privacy/permission behavior, and measurable quality where relevant. |

Treat these as **required ideas, not an imposed giant format**. For a one-line bug fix, one short product-grounding paragraph plus checks is sufficient. For a new milestone, use explicit sections and a practical user journey.

## Other reference material: include when applicable

- **[Product vision](product-vision.md)** is the product north star for every substantial task.
- **[Agent architecture](agent-architecture-plan.md)** is relevant when changing backend/frontend boundaries, providers, memory, tools, approvals, and durable work. Explain where new logic belongs and what must remain separate.
- **[Lessons from other agents](agent-landscape.md)** provide optional comparative reasoning: identify the precise pattern to borrow, the trade-off, and what *not* to import. Never cargo-cult an agent framework.
- **[Context strategy](context.md)** is **not required in every prompt**. Use it when the implementation deals with conversation history, user memory, retrieval, prompt assembly, state, provenance, privacy, or request latency.
- **[Roadmap](roadmap.md)** determines what belongs to M1.5, M2, M2.5, M3, and later—not what happens to be technically interesting.

## Ready-to-use implementation prompt skeleton

> **Goal (what):** Build [the smallest observable capability] in milestone [M#].
>
> **User and motivation (who and why):** This is for [everyday user] who needs to [accomplish real task], because [current friction].
>
> **User journey:** Given [realistic multi-step request], the assistant should [specific visible behavior]. Distinguish an answer, a prepared action, an approved action, and a completed action.
>
> **Scope/architecture:** Implement [specific interfaces, files, tests]. Respect [relevant documented boundaries]. Defer [later-milestone capabilities]. Explain alternatives only where they affect outcome, security, cost, or latency.
>
> **Acceptance:** [success scenarios], [failure/recovery scenarios], [permission and isolation checks], [measurable behavior], and [local verification commands]. Do not assert tests passed unless they were actually run.

For non-code design prompts, replace implementation details with an evidence-based design exercise and explicit decision criteria. Do not make the output look more implemented than it is.

## Example — M2 personal memory

**What:** The assistant can remember, retrieve, correct, and truly delete a user's approved commitments and preferences across separate conversations.

**Why:** Re-entering the same details defeats the purpose of a personal assistant. A user should not have to re-explain what "my usual grocery order" means every time.

**For whom:** A busy ordinary person using the app from a phone who wants continuity but may not understand embeddings, provenance, or database settings.

**Success:** The user can save and inspect a preference, get it back accurately in another session, correct it, and have it no longer return after deletion. Another user's memories never appear; unconfirmed guesses are never upgraded to facts; absence of a memory prompts a clarification instead of invention. Memory retrieval fits a bounded time/context budget and failures degrade safely.

**Architecture relevance:** Owner-scoped memory store and consent/provenance service distinct from transcript storage and system instructions. Evaluate Agent Beacon rather than assuming a coding-agent telemetry layer fits consumer memory. **No automatic orders or sending messages yet**; those are M3/M4 capabilities.

## Example — M3/M4 coordinated everyday actions

**What:** Handle one user's compound instruction to update commitments, prepare/send appropriate messages and assemble a usual grocery basket.

**Why:** A chatbot that merely drafts a list forces the user to coordinate everything themselves.

**For whom:** A person unexpectedly working late who needs to notify the right people, rearrange dinner and handle groceries without manually opening multiple apps.

**Success:** Resolve recipients and dates from confirmed or authorized context; identify which instructions can safely be prepared versus executed; propose messages and the usual basket; ask only for missing details; obtain explicit scoped approvals before sending or purchasing; never fabricate availability, price, cart contents, order status or memory; prevent duplicate sends/orders on retries; report each subtask's actual state. An unavailable integration or stock item is reported, not silently substituted.

**Architecture relevance:** A bounded agent loop orchestrates typed email, calendar and grocery integrations through server-side permissions, approvals, idempotency and auditable results. This is a **target scenario**, not something already implemented in M1.5.

## Review checklist

A prompt is ready when a non-technical person could recognize its intended benefit, an engineer knows the slice and constraints, tests can distinguish success from plausible failure, and future milestones are not misrepresented as shipped features.
