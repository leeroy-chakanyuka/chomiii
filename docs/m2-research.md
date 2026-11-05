# M2 research decision — personal memory for everyday users

**Date:** 2026-11-04. **Status:** evaluated public architecture and API documentation; implementation in PR #3 (under review) is a local first slice, not production release approval.

## Product grounding

- **What:** Let Chomiii remember a preference or commitment across conversations, recall it when relevant, and allow the user to inspect, correct or delete it.
- **Why:** Repeating "my usual groceries" or recurring priorities in every new chat defeats the purpose of a personal assistant.
- **For whom:** Everyday mobile-first people who want continuity without learning memory frameworks, paying for another platform, or surrendering control.
- **Success:** Owner isolation, transparency, provenance, practical recall, correction and deletion, reviewable inferences, bounded first-token overhead, and honest limitations. Memory accuracy must be evaluated with real conversations.

## What we investigated

| Candidate | Verified design | Benefit | Why not adopt now |
| --- | --- | --- | --- |
| [Agent Beacon](https://github.com/Asymptote-Labs/agent-beacon) | Open-source, local-first coding-agent telemetry; captures sessions/hooks/OTel and distills developer lessons into reusable knowledge via MCP/skills. [Architecture](https://github.com/Asymptote-Labs/agent-beacon/blob/main/docs/architecture/architecture.mdx) | Good model of provenance, capture instrumentation, review before promoting lessons | Designed primarily for **coding agent traces**, not per-consumer memory ownership and fine-grained deletion. Introduces an unnecessary capture/forwarding surface. **Do not use as Chomiii's M2 memory backend.** |
| [Mem0](https://github.com/mem0ai/mem0) | Memory extraction + retrieval with scoped `user_id`, session identifiers, update/delete and history APIs. [Architecture](https://github.com/mem0ai/mem0/blob/main/skills/mem0/references/architecture.md) | Useful boundaries for CRUD, owner filtering, metadata and lifecycle | Managed API adds a third-party data path; OSS embedding/vector stack and automatic inference introduce dependencies and complexity before quality is measured. Scoping combinations have implementation-specific semantics: **we must enforce isolation in our own SQL, never depend on model-provided filters.** |
| [Letta](https://github.com/letta-ai/letta) | Stateful agents with memory blocks, persistent messages, archival search and newer git-backed MemFS/dreaming. [Docs](https://docs.letta.com/configuration/memory) | Small curated context vs larger retrievable memory; explicit inspection and repair | More agent infrastructure and autonomous curation than required for a small Spring Boot consumer slice; sharing blocks across agents requires careful owner policy. |
| [Muse open source](https://github.com/wlsdks/muse-agent) | Personal continuity, source receipts, local storage, cross-session user context and explicit thread state. [Architecture](https://github.com/wlsdks/muse-agent/blob/main/docs/architecture/README.md) | User-facing continuity and provenance | Build the needed behaviors, not its entire package/graph runtime. |
| [OpenClaw memory](https://github.com/openclaw/openclaw/blob/main/docs/concepts/memory-architecture.md) | Curated vs episodic tiers and provenance-gated write paths; background curation | Do not dump every transcript into context; writes need gates | A whole gateway, file ingestion and dreaming subsystem would be premature. |

**Safety reference:** [OWASP LLM prompt injection](https://genai.owasp.org/llmrisk/llm01-prompt-injection/) warns that retrieved data can carry attacker instructions. Stored memories are **data, not higher-priority policy**. Do not promote a memory into the `systemInstruction` role.

## Decision

**Build the first M2 memory store inside the existing Java 21 + JDBC + Flyway application.** Use the same PostgreSQL/MySQL setup, owner checks, HTTP API, and ordinary React UI.

- Manual saves **plus automatic, best-effort learning from conversations by default**. An eligible completed turn can trigger a separate bounded Gemini extraction request AFTER the visible reply, isolated from chat workers. No front-end learning opt-in or request header is required. This uses additional API quota; the app discloses it in the Memories panel.
- Explicitly stated, evidence-backed facts (model confidence >=0.88) may be saved as active memories. Lower-confidence facts and inferences remain `suggested` until the person approves them. All can be edited or deleted. The model cannot directly write database records.
- Up to 100 entries per browser-session owner, 500 characters each, with UUID, source (`user_explicit`, `conversation_fact`, or `inferred`), status (`active` or `suggested`), confidence, source conversation, and timestamps.
- `/api/memories` read/create/update/delete/clear with consistent error handling. All SQL queries filter by the server-established owner hash, never a model-supplied identifier.
- Send only at most three locally selected, relevant memories to the model, capped at ~1,400 Java characters in **user-role context**, not trusted system instructions. Recall needs no embedding or additional model request. A user can disable memory use for a chat turn.
- No separate provider or cloud memory account; no additional Maven/npm dependencies.
- Memory records come from manual saves or post-reply learning. Model text saying "I'll remember" does not by itself create a record.

## What this slice cannot yet guarantee

- **Identity:** Existing auth is a 180-day anonymous browser cookie, not a registered person. Continuity works across chats in the same browser while that cookie remains. No cross-device/account recovery.
- **Retention:** The browser-session rows and their memories have no automatic expiry cleanup; losing a cookie can strand stored records. Define and test retention and deletion jobs before production rollout.
- **Deletion boundaries:** Deleting a memory removes the primary DB row; it does **not** erase a similar fact previously typed in chat history, a provider request already sent, infrastructure backups, or logs maintained externally. Clear disclosures and operational retention are required.
- **Search quality:** Current retrieval is lexical with limited normalization. Paraphrases, conflicting facts, chronology and indirect references may be missed. Benchmark ordinary-user scenarios before adding embeddings, rerankers or extraction.
- **At-rest protection:** Memory strings are plain database content, as are current chat transcripts; do not store passwords, keys or payment details. Encryption/key management is a future privacy requirement before collecting high-risk user data.
- **User control and disclosure:** Conversation learning is an openly documented default behavior, not a per-user opt-in. After a qualifying completed reply, the latest user message may be transmitted again to the selected Gemini provider for extraction. Relevant active memories are forwarded for future chats unless the user disables **Use saved memories**. Users can see, edit, approve/reject and delete memories. Self-hosters can modify this open-source behavior; the application should never claim its heuristics are guaranteed accurate.
- **No agent action:** This PR does not send emails, order groceries, schedule work, take real-world actions, or persist cross-app identity.

## Why this is a sensible first step

A small memory row with owner-scoped writes and provenance is easier to inspect and correct than a more complex store. The extractor can still miss facts, invent them, or store sensitive information. Its accuracy and privacy need evaluation with real conversations.

## Release criteria still required

1. Local backend tests + PostgreSQL/MySQL integration + frontend Vitest/Playwright run and pass (GitHub Actions billing currently blocks hosted jobs).
2. Explicit memory CRUD and chat recall work in a real browser against both supported databases, with cross-owner isolation tests.
3. Benchmark memory-enabled vs disabled p50/p95 first-visible text latency; no "free" performance claim.
4. Choose account identity/retention lifecycle for production, implement orphan cleanup and define deletion/backups policy.
5. User-study evidence for whether default automatic learning, review, rejection, and bounded lexical recall are accurate and understandable; measure false positives and additional model cost.

**Conclusion:** choose a small native M2 implementation **now**, keep Mem0/Letta techniques as future options, and keep Beacon for engineering telemetry rather than consumer memory.

## Follow-up decision: automatic learning (2026-11-05)

Product review established that users should **not have to manually curate every memory**.
The first implementation therefore makes extraction automatic **by default**,
without removing the Memory panel. A separate model request is made only after a
completed, eligible chat turn, from a bounded executor. No opt-in checkbox
or client authorization header is needed to learn. A strict JSON
parser checks confidence, source kind, evidence quoted from the current user
message, response length, and an initial sensitive-content exclusion list.
This filter is intentionally conservative and does **not** constitute a proven
PII classifier. Learned data is user-owned and can be edited, approved, or deleted.
Queued extraction captures the user's memory revision; a later manual mutation
invalidates older pending writes.

The **confidence score is a model estimate, not a calibrated probability**.
Direct facts and tentative inferences have separate states; tentative entries
are not passed to the answer model until reviewed. The person can manage and delete learned memories and can disable **recall** for
a particular chat. There is no separate learning toggle in the default product;
fork maintainers can adapt the behavior in source.

Known limits: the single-turn extractor cannot reliably infer preferences across
several conversations; queued in-memory work can be lost on restart; extra
model calls cost money and may use the same model as the main chat; sensitive
data protection is heuristic; and the provider sees a second copy of the
eligible user turn. Test real false-positive and omission rates before production.
