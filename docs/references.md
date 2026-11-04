# References

Study candidates for Chomiii's milestones. These are **study resources, not dependencies or commitments**. Chomiii is a personal assistant for everyday people, *not* a coding agent. See the separate [comparative architecture lessons](agent-landscape.md) and [product vision](product-vision.md). **Agent Beacon will be evaluated first for M2, but its coding-agent focus means adoption is not assumed.**

## Existing technical and engineering references

1. **[Pstack](https://github.com/cursor/plugins/tree/main/pstack)** — community plugin of rigorous agentic engineering playbooks and skills (routing, `/tdd`, verification skills such as blast-radius and benchmark checklists). Read from **M1 onward**: its TDD cadence and verification-before-merge habits are a reference for how Chomiii applies its own [AGENTS.md](../AGENTS.md) discipline to real features. Not an M0 prerequisite, and no Pstack work is happening now.

2. **[Agent Beacon](https://github.com/Asymptote-Labs/agent-beacon)** (Asymptote Labs; [beacon.sh](https://beacon.sh/)) — open-source (MIT) cross-harness telemetry and memory layer for coding agents: sessions captured into a normalized, local-first trace, distillation of reviewed lessons into skills, served back via MCP or Agent Skills. Local JSONL is the default, with optional cloud/SIEM forwarding and explicit privacy modes (Standard vs Metadata-only). **M2 evaluation candidate, not an assumed fit for consumer memory** — its memory-ownership model, privacy posture, retention/redaction controls, export/deletion guarantees, licensing, and cloud-vs-local data flow are the direct comparison point for M2's user-scoped memory design.

3. **[Spring AI](https://github.com/spring-projects/spring-ai)** ([docs](https://docs.spring.io/spring-ai/reference/)) — the Spring ecosystem's AI framework: portable model-client abstractions (chat, streaming, tool calling), prompt/advisor patterns, and Spring-native configuration. The natural library bridge for Gemini integration inside a conventional Spring Boot backend; fits M1's "provider behind an internal port" requirement.

4. **[LangChain4j](https://github.com/langchain4j/langchain4j)** ([docs](https://docs.langchain4j.dev/)) — a Java framework for LLM-backed applications with model providers, streaming, tool/function support, structured outputs, and a memory interface. The main Java-native counterpoint to Spring AI for M1–M3; evaluated on stack fit and dependency size.

5. **[Mem0](https://github.com/mem0ai/mem0)** — a memory layer for LLM applications capturing, consolidating, and serving user-context memory with an embed-and-retrieve design (Python/TypeScript ecosystems). M2 candidate on the memory lenses: user scoping, provenance, consent, export/deletion, and what runs locally vs in its cloud.

6. **[Letta](https://github.com/letta-ai/letta)** (formerly MemGPT; [docs](https://docs.letta.com/)) — agent memory framework descended from the MemGPT paper: core/archival memory hierarchy with the agent managing its own page-in/page-out, exposed as a server with multi-user storage. M2 candidate for user-scoped storage architecture and long-lived memory retention behavior.

7. **[LangGraph](https://github.com/langchain-ai/langgraph)** ([docs](https://langchain-ai.github.io/langgraph/)) — graph-based agent orchestration (state machines of nodes/edges with check-pointing and human-in-the-loop interrupts) from the LangChain team. M3 candidate for the bounded loop, permissions/approval gates, and traceability; contrasted with building the smallest contract-first loop in plain Java.

8. **[OpenHands](https://github.com/All-Hands-AI/OpenHands)** — open-source autonomous software-development agent platform (sandboxed runtime, event-sourced interaction history, browser and terminal tools, HTTP API surface). Reference for M3/M4 on tracing, tool-sandbox boundary design, and how a hosted agent product is shaped end to end.

9. **[LibreChat](https://github.com/danny-avila/LibreChat)** ([docs](https://www.librechat.ai/docs)) — open-source, self-hostable multi-provider chat UI with BYOK (users supply their own provider keys), streaming responses, multi-user accounts with per-user conversation persistence, and session handling. The closest public reference for M1's chat product: BYOK flows, streaming UX, conversation storage, and later the self-hosting expectations of M7.

## Evaluation lenses (applied at the right milestone, consistently)

- **Stack fit:** Spring Boot / Java 21 backend, plain JDBC + Flyway, React + TypeScript frontend; small, understood dependencies.
- **Memory/participant safety (M2):** user-scoped ownership, provenance of each memory, obtaining and recording consent, retrieval isolation between users, real correction and deletion paths.
- **Agent behavior (M3/M4):** bounded loops, per-tool permissions and approval, traces, timeouts, untrusted-input boundaries, documented-API-only integrations.
- **License and data flow:** cloud-vs-local defaults, what leaves the machine, and who can access it.
- **Maintenance honesty:** what the library does well vs what Chomiii should own itself; no candidate is adopted unless it earns its dependency.

Inclusion here does not mean a project has been selected or adopted.

## Product-oriented references

The [agent landscape](agent-landscape.md) examines [Meta Muse](https://about.fb.com/news/2026/09/introducing-muse-personal-ai-agent/),
[OpenAI dots](https://openai.com/index/introducing-dots/),
[open-source Muse](https://github.com/wlsdks/muse-agent),
[OpenDot](https://github.com/thinkwee/OpenDot),
[OpenClaw](https://github.com/openclaw/openclaw), and
[Letta](https://github.com/letta-ai/letta). It records what Chomiii aspires
to learn from each, and what it explicitly postpones. They are not dependencies.
