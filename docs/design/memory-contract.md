# Memory and user context policy (M2 design contract)

**Status: implementation proposed in draft PR #3; not verified or merged.**
The M1 conversation history remains distinct from the proposed M2
`personal_memories` store. See [M2 research](../m2-research.md) and
[design](../m2-design.md).

## Invariants for M2
- Memory entries are owner-scoped and cannot cross sessions or accounts.
- Store a minimal, useful fact only with an explicit product policy and user consent. Preserve provenance, timestamp, confidence and source.
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

Any future summarization, embedding or memory distillation should run after the visible response where possible, with bounded queues and explicit failure handling; it must not block first token by default.
