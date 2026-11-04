# Everyday-agent experience evaluation — staged, ambitious, verifiable

**Status:** design and evaluation protocol, **not** observed user-study results, benchmark evidence, or claims of implemented features. Chomiii is meant to become a **general-purpose personal agent for ordinary people**. The north star is a connected, context-aware task completed with appropriate consent—not a cleverly phrased answer to a question.

## Why ordinary chatbot questions are insufficient

A model can already explain a bank statement or compare simple purchase costs. These are useful baseline interactions, but they do not prove memory, coordination, external actions, or dependable follow-through.

Our harder tests must involve **several related commitments and people**, information carried forward appropriately, multiple authorized services, uncertainty, changing priorities, and partial failure. A single user request can create several distinct proposed actions, each with a verifiable result.

## North-star scenario: "I'm going to be tied up tonight"

**User:** "I'm going to be caught up with work tonight. Please email the two people I'm meeting and say I won't make it. I can't make dinner either. Check Checkers Sixty60 for my usual groceries, and take care of that."

Expected *future* behavior, not current M1.5 capabilities:

1. **Understand:** Identify the time, meeting invitees, dinner commitment and the difference between "check", "prepare an order" and "purchase". Do not mistake ambiguous contacts for the correct people.
2. **Gather authorized context:** Read calendar and relevant contact/email data only after appropriate connection and permissions. Retrieve the user's approved *usual groceries* memory, including quantities or preferences and how current it is.
3. **Make a coordinated plan:** Match messages to the correct recipients, avoid sending the same generic note to unrelated people, identify commitments the user wants moved, and state which steps are possible with installed integrations.
4. **Prepare, then authorize:** Present the proposed emails and their recipients. Sending to others requires the appropriate explicit approval. For a grocery purchase, obtain item, substitution, total-price, delivery-address/window and payment authorization as applicable; don't silently spend money.
5. **Act when authorized and supported:** Use typed integrations; record each actual send, calendar change and order/cart result, with idempotency to avoid duplicated actions.
6. **Recover gracefully:** If usual groceries are not in consented memory, ask what "usual" means. If Checkers Sixty60 integration is unavailable, explain that and offer a basket or checklist. If an item is unavailable, propose substitutions for approval; never claim an order exists based on a search result or a draft.
7. **Report honestly:** Show which messages were drafted/sent, which appointments were changed, what the grocery status is, and what still needs a decision.

The user should be able to redirect mid-task ("Actually, only email the first person") and stop later actions without erasing already completed ones.

## North-star scenario: "Free up some time for me"

**User:** "I've got a hectic week. Free up Tuesday afternoon and make sure I don't miss anything important."

Expected future behavior: inspect authorized commitments and any *user-approved* priority preferences; distinguish immovable commitments, flexible tasks and obligations to other people. Suggest a rearrangement with clear trade-offs **before** cancellations. Ask before communicating changes to other people. If importance is unknown, ask rather than guessing or treating inferred preferences as authoritative. Show a revised plan, the affected people, and any unresolved conflicts. A later correction should change how the assistant understands that priority, with consent.

## North-star scenario: "Continue where we stopped"

**User:** "Pick up the trip planning we started last week. Anything changed? If prices are reasonable, hold the best option."

Expected future behavior: recover the specific prior goal and latest decisions, cite the sources and timestamps available, check what has actually changed through authorized live sources, compare options against the person's constraints, and ask for scoped approval before a reversible hold or booking. If no live data exists, say so. If the app restarts during an approved action, avoid duplicates and provide a clear status.

## Staged evaluation matrix

| Stage | Realistic test | Evidence needed to pass |
| --- | --- | --- |
| **M1/M1.5: chat and context** | Multi-constraint planning in one conversation | Right facts retained within the current context; no fake sending, shopping or memory; concise answer; measured latency |
| **M2: personal memory** | "My usual groceries" and "Tuesday is family time" across sessions | Owner-scoped consent, source/provenance, retrieval, correction/deletion and honest "I don't know" |
| **M2.5: task workflows** | A repeatable appointment and multi-step planning flow | Measurable improvement versus plain chat, bounded prompt/context, fallback for missing data |
| **M3: authorized action** | Safely create or cancel a personal reminder/task | Typed schema and enforced permission, correct stop behavior, deterministic fake-model tests, trace |
| **M4: connected services** | Send the right approved email, check the right grocery catalog, reconcile calendar | Exact recipients and arguments, correct integrations, no invented prices/stock, scoped approvals |
| **M5: ongoing work** | Resume a delayed multi-step task after a restart | Durable state, progress, cancellation, no repeated sends/orders, accurate completion report |

This matrix separates an **aspirational full-agent benchmark** from current milestone acceptance. It does not imply Chomiii can send an email, access Sixty60, order groceries or schedule jobs today.

## Cross-cutting failures to deliberately test

- **Missing memory:** the assistant does not invent the user's "usual", contacts or priorities.
- **Wrong identity:** two people with similar names do not get mixed up; no cross-owner recall.
- **Ambiguous instruction:** "Check an order" is not automatically permission to buy; "free up time" is not authorization to cancel other people's meetings.
- **Partial permission:** approve one email, reject another, and stop the remaining tool chain cleanly.
- **Unavailable integration:** admit it cannot reach a service; produce useful prepared work rather than claiming success.
- **Stale external state:** check price, availability, recipient and delivery details before a consequential action; do not assume yesterday's data is current.
- **Duplicate/retry/restart:** no duplicated message, reservation, reminder or purchase.
- **User changes their mind:** retain completed actions, cancel future ones, and explain what is reversible.
- **Slow model/memory:** don't make nonessential personal-context work block first-visible response indefinitely; keep state consistent under timeout.

## How to score during real evaluation

Use explicitly recorded scenarios and results, not invented percentages. Track **task completion**, **correctness of each subtask**, **approval accuracy**, **unnecessary questions**, **memory recall/correction**, **recovery after failure**, **cost**, and **time-to-first-visible text** separately. A polished conversational reply does not compensate for a wrong recipient or duplicate order.

Run staged user studies with ordinary people, not just engineers; capture what they expected, whether the assistant's status was understandable, and whether it saved meaningful work.

## Automated tests that belong to PR #2

- Versioned `SYSTEM.md` is packaged, bounded and fails closed when missing.
- User messages never enter the trusted system instruction.
- Owner-scoped recent history remains role-tagged, bounded and excludes failed turns.
- No generic `SKILL.md` router or extra model-classification request occurs.
- Streaming/cancellation and concurrent post-stream refresh behave as intended.

Those tests are **authored, not independently run** here. No user study or north-star scenario has been executed. See [product vision](../product-vision.md), [prompt brief standard](../prompt-brief-standard.md), and [roadmap](../roadmap.md).
