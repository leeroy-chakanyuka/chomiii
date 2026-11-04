# M2 research decision — personal memory for everyday users

**Date:** 2026-11-04. **Status:** evaluated public architecture and API documentation; implementation in PR #3 (under review) is a local first slice, not production release approval.

## Product grounding

- **What:** Let Chomiii remember a user-approved preference or commitment across conversations, recall it when relevant, and allow the user to inspect, correct or delete it.
- **Why:** Repeating "my usual groceries" or recurring priorities in every new chat defeats the purpose of a personal assistant.
- **For whom:** Everyday mobile-first people who want continuity without learning memory frameworks, paying for another platform, or surrendering control.
- **Success:** Owner isolation, explicit consent, provenance, practical recall, correction/deletion, no fabricated memories or automatic saving, bounded first-token overhead, and honest limitations.

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

- Explicit `Save memory` action, no automatic chat harvesting or inferred facts.
- Up to 100 entries per browser-session owner, 500 characters each, with UUID, source=`user_explicit`, created/updated timestamps.
- `/api/memories` read/create/update/delete/clear with consistent error handling. All SQL queries filter by the server-established owner hash, never a model-supplied identifier.
- Send only at most three locally selected, relevant memories to the model, capped at ~1,400 Java characters in **user-role context**, not trusted system instructions; zero embedding requests and zero extra model calls. A user can disable memory use for a chat turn.
- No separate provider or cloud memory account; no additional Maven/npm dependencies.
- The browser UI is the source of truth for "remembering": saving through the manager is what creates a record. Mere model text saying "I'll remember" cannot create a memory.

## What this slice cannot yet guarantee

- **Identity:** Existing auth is a 180-day anonymous browser cookie, not a registered person. Continuity works across chats in the same browser while that cookie remains. No cross-device/account recovery.
- **Retention:** The browser-session rows and their memories have no automatic expiry cleanup; losing a cookie can strand stored records. Define and test retention and deletion jobs before production rollout.
- **Deletion boundaries:** Deleting a memory removes the primary DB row; it does **not** erase a similar fact previously typed in chat history, a provider request already sent, infrastructure backups, or logs maintained externally. Clear disclosures and operational retention are required.
- **Search quality:** Current retrieval is lexical with limited normalization. Paraphrases, conflicting facts, chronology and indirect references may be missed. Benchmark ordinary-user scenarios before adding embeddings, rerankers or extraction.
- **At-rest protection:** Memory strings are plain database content, as are current chat transcripts; do not store passwords, keys or payment details. Encryption/key management is a future privacy requirement before collecting high-risk user data.
- **Consent boundary:** Saving an entry is explicit consent for that entry to be considered during relevant chats; when used, the selected fact is forwarded to the currently selected Gemini model provider. The UI lets the user opt out of recall on a chat turn.
- **No agent action:** This PR does not send emails, order groceries, schedule work, infer private preferences automatically, or persist cross-app identity.

## Why this is a sensible first step

A modest, auditable memory row with an explicit write path is better than sophisticated memory that invents facts, remembers private information without approval or leaks across people. We can replace the retrieval algorithm later behind a stable owner-checked service.

## Release criteria still required

1. Local backend tests + PostgreSQL/MySQL integration + frontend Vitest/Playwright run and pass (GitHub Actions billing currently blocks hosted jobs).
2. Explicit memory CRUD and chat recall work in a real browser against both supported databases, with cross-owner isolation tests.
3. Benchmark memory-enabled vs disabled p50/p95 first-visible text latency; no "free" performance claim.
4. Choose account identity/retention lifecycle for production, implement orphan cleanup and define deletion/backups policy.
5. User-study evidence for whether explicit save UX is natural and whether lexical recall is sufficiently useful.

**Conclusion:** choose a small native M2 implementation **now**, keep Mem0/Letta techniques as future options, and keep Beacon for engineering telemetry rather than consumer memory.
