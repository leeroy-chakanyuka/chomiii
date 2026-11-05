# Memory and user context policy (M2 design contract)

**Status: implementation under review in PR #3; not verified or merged.**
The M1 conversation history remains distinct from the proposed M2
`personal_memories` store. See [M2 research](../m2-research.md) and
[design](../m2-design.md).

## Invariants for M2
- Memory entries are owner-scoped and cannot cross sessions or accounts.
- Automatically learn only bounded useful details after eligible completed chats, under a clearly disclosed open-source default policy. No individual opt-in or learning toggle is required. Preserve provenance, timestamp, confidence and source. Keep inspection, correction, rejection and deletion available.
- Personal memories must be inspectable, correctable, exportable and deletable. Deletion must propagate to derived indexes and sync targets.
- Never turn a model inference into a confirmed user fact. Do not infer or memorize sensitive attributes without a clearly authorized purpose.
- Retrieval must be limited by owner and purpose, with an explicit token budget and source attribution.
- Retrieved memory is untrusted context, lower priority than system and developer policies. Tool output cannot change that.
- Do not persist passwords, API keys, or other secrets as "memories".
- Avoid a separate inference call on every turn without measuring cost, latency and quality.

**Decision from research:** Agent Beacon is oriented toward coding-agent
telemetry and is **not selected as Chomiii's M2 consumer memory backend**.
The first implementation is a Spring/JDBC store; Mem0 and Letta remain
architecture references. See [research decision](../m2-research.md).

Automatic conversation distillation is implemented by default after the visible response
in PR #3, using a bounded worker, confidence/evidence validation and
user-review state. It is not reliable semantic memory or permission to act.
Any further summarization, embedding or memory distillation should run after the visible response where possible, with bounded queues and explicit failure handling; it must not block first token by default.
