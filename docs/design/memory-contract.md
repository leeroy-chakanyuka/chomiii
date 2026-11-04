# Memory and user context policy (M2 design contract)

**Status: not implemented.** The current PostgreSQL conversation history is not a personal-memory or preferences engine.

## Invariants for M2
- Memory entries are owner-scoped and cannot cross sessions or accounts.
- Store a minimal, useful fact only with an explicit product policy and user consent. Preserve provenance, timestamp, confidence and source.
- Personal memories must be inspectable, correctable, exportable and deletable. Deletion must propagate to derived indexes and sync targets.
- Never turn a model inference into a confirmed user fact. Do not infer or memorize sensitive attributes without a clearly authorized purpose.
- Retrieval must be limited by owner and purpose, with an explicit token budget and source attribution.
- Retrieved memory is untrusted context, lower priority than system and developer policies. Tool output cannot change that.
- Do not persist passwords, API keys, or other secrets as "memories".
- Avoid a separate inference call on every turn without measuring cost, latency and quality.

**Candidate:** Evaluate Agent Beacon against these requirements first, but note it is built for coding-agent traces and must prove consumer fit, including its coding-agent focus, lifecycle, redaction and delete guarantees, before adopting it for end-user memory.

Any future summarization, embedding or memory distillation should run after the visible response where possible, with bounded queues and explicit failure handling; it must not block first token by default.
