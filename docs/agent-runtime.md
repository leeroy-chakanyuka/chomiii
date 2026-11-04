# Minimal runtime identity — M1.5

Chomiii's **current** runtime is a streaming chatbot for everyday conversations, not an autonomous tool-using agent. The M1.5 contribution is a small, versioned identity that can be reviewed independently of code.

## What is loaded

- `backend/src/main/resources/agent/SYSTEM.md` is packaged into the Java artifact.
- `AgentInstructions` reads and validates it once per process and supplies the same short text for all turns.
- `ChatStreamService` sends it via `ChatProvider.ChatInput.systemInstruction`. The current user message and bounded owner-scoped history remain role-tagged messages, never substituted into system instructions.
- System prompts are capped at **2,000 Java characters** and rejected if missing or empty. The startup path does **not** load generic `SKILL.md` files, run a separate router model, or read prompt files for every turn.
- This changes the wording supplied to the model, not its permissions or actual capabilities.

The repository-root `AGENTS.md` is the engineering contributor contract for developers. It is not Chomiii's persona and is not shipped as runtime model context.

## What the persona should and should not do

Be warm, reliable and practical in ordinary life; answer questions, help make decisions, explain, write and plan. Do not portray the product as a specialist coding agent. Do not pretend to browse, send mail, schedule jobs, remember facts long-term, or work after the chat finishes. A small prompt is not a permission system.

## Context belongs in M1.5 too

The [context contract](context.md) defines present history selection and future tiers for user preferences, external sources and task state. It intentionally separates **context assembly** from **memory persistence**. See also [product vision](product-vision.md).

## What moved out of M1.5

The initial PR experimented with six generic auto-selected skills, including debugging. Product review determined that those are mostly ordinary model capabilities and make the UX too developer-oriented. They were removed, along with the extra routing code. **M2.5** will assess concrete, everyday workflow-specific skills against real-user utility and token/latency cost.

Tool approvals, persistent memory and autonomous work are future milestones. Their contracts are kept under `docs/design/` rather than injected into the chat runtime.

## Performance and tests

A short constant prompt avoids extra pre-stream inference and keeps most context stable. The post-stream frontend improvement parallelizes two independent refreshes; neither change is claimed to fix Gemini provider first-token waits. See [performance](perf/README.md).

Run `./mvnw -Dtest=AgentInstructionsTest,ChatIdentityTest,ContextWindowTest test` in `backend/` (or the wrapper on Windows). Tests are authored but CI cannot run due to account billing restrictions. See [verification](verification.md).
