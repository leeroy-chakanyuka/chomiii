# Chomiii — user-story backlog

**Status:** Product scenarios and evaluation targets. Not a claim that any tool, integration, payment flow or automation is implemented.

Chomiii is a general-purpose personal agent for ordinary people. The success criterion is not whether it writes a plausible plan; it is whether it asks for only genuinely missing information, selects relevant memory, uses available tools correctly, obtains appropriate consent, and reports *verified* outcomes.

Related documents: [roadmap](roadmap.md), [everyday-agent experience evaluation](design/experience-evaluation.md), [target agent architecture](agent-architecture-plan.md). Permission design: [Issue #13 — Deny / Approve once / Allow always](https://github.com/leeroy-chakanyuka/chomiii/issues/13).

## How to use this backlog

- Stories are **scenario-level acceptance targets**, not separate mandatory tools, tickets or immediately authorized implementation.
- Keep the list broad; the current discussion prioritizes **food and shopping**, but nothing else is removed or promised for immediate delivery.
- Prefer a reusable fictional persona containing recurring routines, multiple commitments, changing preferences and realistic distractor memories. Preserve ownership isolation and consent.
- Classify expectations as **available now**, **blocked on integration/permission**, or **future milestone** when turning a story into an executable test. Never simulate a successful real-world action in production.
- Judge results by clarification quality, tool selection, memory relevance, approval correctness, cost, latency, fault recovery and final verified state, not merely plan elegance.
- No delivery dates are implied by this backlog.

## Story index

| ID | User outcome | Primary capabilities | Milestone coverage | Priority in present discussion |
| --- | --- | --- | --- | --- |
| US-01 | Get dinner and make room tonight | Calendar + memory + food discovery/order | M3.4, M4 commerce, M5 optional | **Focus** |
| US-02 | Replan an evening after a work call | Calendar + priorities + food + approvals | M3.4, M4, M5 optional | **Focus** |
| US-03 | Prepare my usual groceries | Memory + retail comparison + cart handoff | M4, M6.2 | **Focus** |
| US-04 | Free up time without dropping commitments | Calendar + to-dos + context + consent | M3.4, M4 | Core |
| US-05 | Create and complete to-dos | Persistent actions + grants + traces | M3.3 | Foundation |
| US-06 | Resume a multi-step goal after interruption | Persistent plan + observations + safe retry | M3.4, M5 | Foundation |
| US-07 | Prepare a Blackboard study session | Coursework + attachments + memory + plan | M9 Education | **Later** |
| US-08 | Keep coursework prepared for each evening | Incremental sync + scheduling + notifications | M5, M9 Education | **Later** |
| US-09 | Provide targeted practice based on progress | Course materials + performance + possible StarPath adapter | M9 Education | **Later** |
| US-10 | Handle a changed plan and partial permission | Approvals + cancellation + idempotent actions | M3.3–M5 | Cross-cutting |

### US-01 — “Get me dinner and make space for it”

**User:** “Hey Chomiii, I've got something going on tonight. Please get me dinner and make some space for it.”

**Given:** It is afternoon; the user might have a recurring 17:00–19:00 hobby run, a fixed appointment and other calendar items. Approved memory may describe food preferences and whether the run is flexible.

**Expected behavior:**
1. Notice that “tonight” is not a specific event time or delivery window. Ask for the one genuinely missing detail when needed, rather than inventing a time or assuming the calendar entry is movable.
2. Read the relevant day's calendar through an authorized tool; search approved memory for the *specific remaining commitments* and food preferences, rather than indiscriminately loading a full memory dump.
3. Distinguish fixed obligations from optional or habitual blocks. A remembered routine is evidence for a suggestion, **not permission** to move it.
4. Offer a concise revised evening plan; if confidence is insufficient, surface uncertainty instead of quietly rescheduling.
5. Search supported food services and prepare a selected basket. Show actual restaurant/menu availability, customizations, costs and delivery options when supported.
6. Seek the appropriate action-level or scoped approval for calendar changes and basket modifications; do not equate a calendar grant with permission to place an order.
7. Hand the authenticated basket to the user for checkout where technically supported, and let the user make the payment. Do not claim an order was placed merely because a cart was prepared.
8. Report precisely what changed, what was prepared, and what still needs a decision.

**Acceptance / failure probes:** no stated time; unavailable food tool; stale menu; conflicting memories; cancelled run versus optional run; price change; denied calendar change; missing checkout handoff; duplicate tool call; two owners' memories. A completed answer must distinguish *proposed*, *approved*, *executed* and *paid*.

### US-02 — “A work call just came in; sort out my evening”

**User:** “I've got a last-minute work call. Move my gym session, but make sure I still eat afterward.”

**Expected behavior:** Clarify call time or other indispensable details; inspect calendar and relevant routine/meal memories; identify what is flexible; produce an order of operations; ask for permission where necessary; reschedule the event if authorized; prepare meal options that fit the resulting time; observe tool outcomes; revise the plan if the gym cannot move or food is unavailable.

**Acceptance / failure probes:** user declines the gym move; remembered gym routine differs from the calendar; tool failure mid-plan; two pending changes require separate approvals; actual event update succeeds but food preparation fails. Do not undo or repeat successful actions without consent.

### US-03 — “Handle my usual groceries”

**User:** “I'm running out of groceries. Find my usuals, compare what it'll cost and prepare a basket.”

**Expected behavior:** Retrieve approved preferences and usual quantities; ask when unknown; compare products **including fees, substitutions and delivery** from genuinely accessible services; display stale/unverified prices as such; construct and verify a cart if a provider supports it; return a checkout handoff without processing payment.

**South African candidates:** Checkers Sixty60, Pick n Pay asap!, Woolies Dash, SPAR2U, plus shopping services such as Takealot/Makro/Bash/OneDayOnly where consumer use is permitted. Provider integration feasibility is unresolved until verified; a seller API is **not** proof of customer-cart access.

**Acceptance / failure probes:** brand variants, out-of-stock items, price changes, user's budget in rand, unavailable API, manual checkout link, wrong delivery address. A saved food preference alone never means consent to buy.

### US-04 — “Free up Tuesday afternoon”

**User:** “I've got a hectic week. Free up Tuesday afternoon, but don't let me miss anything important.”

**Expected behavior:** Read authorized calendar and to-dos, retrieve priority preferences, propose trade-offs, preserve fixed commitments, ask before modifying others' meetings or notifying people, and give a truthful changed/unchanged breakdown.

**Acceptance / failure probes:** unknown importance, duplicate appointments, scheduling collision, canceled change, conflict with an ongoing plan.

### US-05 — “Create to-dos so I can get started”

**User:** “Create tasks for my preparation this week and mark the first one complete when I'm done.”

**Expected behavior:** Use `todo.create`, `todo.list`, `todo.complete` through the registered provider-neutral Java tool runtime, with owner-scoped persistence, idempotency and correct approval semantics. Do not report success until the DB confirms the result.

**Acceptance / failure probes:** repeated call, permission denial, conflicting due dates, partial DB failure, retry and concurrent approvals.

### US-06 — “Pick up where we stopped”

**User:** “Continue the plan we started yesterday. What's left?”

**Expected behavior:** Recover the exact ongoing goal, completed steps, approvals and observations. Do not repeat completed side effects, and revise only the necessary pending steps. M5 extends this with scheduled/background recovery.

**Acceptance / failure probes:** restart, expired permission, changed goal, stale external data, unavailable tool.

### US-07 — “My 9 PM study session should already be ready”

**User:** “Open my coursework and tell me exactly what I need to work on tonight.”

**Expected behavior:** With valid Blackboard/institutional access, inspect assignments, announcements, exam dates and **attached course files**, not just visible deadlines; combine these with approved memory about study routines, strengths and constraints; prioritize what is genuinely urgent; prepare a ready-to-start session with links to source material and tasks. If access is unavailable, say so and work with uploaded files and previously authorized data.

**Acceptance / failure probes:** unread PDF, deadline mentioned only inside an attachment, missing time zone, conflicting exam dates, already-submitted work, unavailable Blackboard API, multi-course priorities. Never fabricate a deadline or claim to have read files that weren't retrieved.

**Placement:** **M9 Education**, not a critical-path M4 integration. Blackboard LTI launch and REST content access are separate feasibility questions.

### US-08 — “Zero study admin at night”

**User:** “Every evening, have tomorrow's study plan ready before I sit down.”

**Expected behavior:** On login, incrementally refresh changed coursework (not a full refetch of all files). With explicit M5 authorization, schedule recurring refreshes and generate the plan in advance, maintaining revision/freshness metadata and notifying the user appropriately. Without background capability, prepare it when requested or when the user opens Chomiii, without promising autonomous execution.

**Acceptance / failure probes:** unchanged files not redownloaded, new assignment found, deadline moved, disconnected account, revoked access, rerun after process restart, duplicate jobs.

### US-09 — “Practice what I actually need”

**User:** “Test me on the chapter I'm struggling with, then adjust tomorrow's study plan.”

**Expected behavior:** Use retrieved course content to generate grounded practice questions and explanations. Maintain performance signals only with appropriate consent; adapt difficulty and topic selection from evidence rather than assuming a learning profile from a single wrong answer. Evaluate an optional **StarPath integration** instead of recreating an entire spaced-repetition/analytics product inside Chomiii.

**Acceptance / failure probes:** unsupported topic, answer-key error, conflicting source material, limited performance history, privacy separation, question quality.

### US-10 — “No, don't do that; just do this one”

**User:** “Actually, don't move my run. Only create the to-do.”

**Expected behavior:** A multi-tool plan responds to changed consent in real time, executes only still-authorized operations, prevents approval replay, retains truthful records for already executed actions, and pauses dependent steps rather than silently skipping approval.

**Acceptance / failure probes:** deny/approve once/allow always, persistent grant scope and revocation, changed arguments after approval, prompt injection in tool results, cancellations and retried calls.

## Cross-cutting design rules

1. **Ask for missing facts, not facts already in the conversation or trustworthy authorized memory.** For consequential time/location/money decisions, do not manufacture values to keep the flow moving.
2. **Memory quality means selection and reconciliation, not simply making snapshots smaller.** Store broad approved personal context; retrieve specific evidence on demand; preserve corrections and provenance. Avoid hard-coding memory conflict semantics to commute/residence/meal examples.
3. **Tools are provider-neutral.** A model selects a typed request; Java owns validation, owner identity, authorization, execution and redacted tracing. Tool responses are lower-trust observations, not instructions.
4. **Confidence guides clarification, not authorization.** “Allow always” grants are scoped, inspectable and revocable; high-impact purchases/communications may still need per-action confirmation. [Issue #13](https://github.com/leeroy-chakanyuka/chomiii/issues/13).
5. **No false action claims.** Differentiate selected restaurant, created cart, submitted order and completed payment; proposed event move versus confirmed calendar update; extracted deadline versus verified course source.
6. **Integrations are conditional.** Official API access, credentials, terms and checkout handoff must be validated. Browser automation is an implementation option only where allowed, isolated and securely authenticated.
7. **No unnecessary administration.** The default interface is conversational, concise and ready-to-act; show step details when a decision or failure requires them.
8. **Every story needs deterministic fake-provider tests plus real-adapter validation when accessible.** Measure tool correctness, relevant memory retrieval, unnecessary clarifications, approval handling, privacy, durability, time and cost.

## Suggested implementation order

- **Now / M3:** US-05 and the authorization/replanning foundations of US-10, then the calendar-less portions of US-01/US-02 as deterministic end-to-end agent simulations.
- **M4:** complete real read/write connectors and food/commerce discovery/handoff to unlock US-01/US-02/US-03; do not assume a retailer API or browser flow is available before verification.
- **M5/M6:** persistent recurring execution and South African cost/transport/localization features.
- **Later / M9:** US-07, US-08 and US-09, with Blackboard access feasibility and optional StarPath collaboration.

The older north-star scenarios involving groceries, calendars, communications and travel remain in [experience evaluation](design/experience-evaluation.md); this document is the backlog of discussed user stories and their acceptance conditions, not a replacement for that evaluation protocol.
