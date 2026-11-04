# Chomiii agent instructions: M1.5

This is the **implemented prompt and skill foundation**, not yet a tool-running autonomous agent. The backend uses versioned, trusted Markdown from \`backend/src/main/resources/agent/\` on its system-instruction channel. \`AGENTS.md\` at the repository root is for coding contributors and is not sent to Gemini.

## Files and roles

- \`SYSTEM.md\` — short runtime identity, trust boundary and honest capability declaration.
- \`skills/<name>/SKILL.md\` — portable Agent Skills format with YAML \`name\` and \`description\`; \`triggers\` is Chomiii-specific optional routing metadata used by this first runtime.
- \`TOOLS_POLICY.md\` — M3 server-side permission/approval contract (not an executable tool).
- \`MEMORY_POLICY.md\` — M2 provenance, consent, isolation and deletion policy (no memory store yet).
- \`RESPONSE_PROTOCOL.md\` — action lifecycle and instruction precedence reference for future milestones.
- \`EVALUATION.md\` — focused test cases and evaluation checklist.

## Runtime

\`AgentInstructions\` loads the core and discovers skill files on the classpath **once** when its Java class initializes. It validates the frontmatter, builds a small name/description catalog, then deterministically selects at most one relevant skill based on phrase triggers from the latest user message. \`/skill <name>\` explicitly selects an installed skill. Only the selected instruction body is appended to the system prompt. There are no extra LLM calls, file reads per turn, hidden side effects or memory writes.

This is an interim **local selector**, not model-driven load-on-demand tools. The model cannot call \`load_skill\` or read reference files. When M3 adds a permissioned tool runtime, the skill catalog can be discovered with a typed read-only tool; the caller must enforce ownership and bounds outside the model.

## Trust boundary

System and skill files come from the versioned **application artifact**, not an HTTP request, user-controlled filesystem path or retrieved page. They are trusted only after code review. User text and external material are always lower-priority message data. A skill never grants tool access or authority to send, delete or persist data.

## Latency

Core files and catalog are cached for the process lifetime. Only one short skill can be appended, so normal chat avoids the cost of all skill bodies. Routing runs locally without network traffic. This does **not** eliminate the measured Gemini provider wait, and may slightly increase prompt-token cost for skill-activated turns. A separate frontend change parallelizes the two post-stream refresh GETs; this affects completion UI, not first token.

## Adding a skill

Create \`agent/skills/<slug>/SKILL.md\` with valid YAML frontmatter \`name\`, \`description\` and a comma-separated \`triggers\` line, plus focused instructions. Keep the body under 5,000 characters, with no external permissions implied. Add deterministic selection and safety tests, and review for prompt injection, unsupported claims and token cost. See [Agent Skills](https://agentskills.io/specification).

## Inspiration, not dependencies

- [Open Dot](https://github.com/whatif-dev/agent-open-dot): separated prompt composition, tools, policy checks and approvals.
- [Muse agent architecture](https://github.com/wlsdks/muse-agent): model neutrality, deterministic security gates, untrusted tool outputs and auditable actions.
- [Open Agent Skills](https://agentskills.io/specification): portable \`SKILL.md\` and progressive disclosure.
- [Anthropic Agent Skills engineering](https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills): compact catalog first, task-specific instructions on demand.

We drew architectural ideas from these sources; no code or prompt text was copied.
