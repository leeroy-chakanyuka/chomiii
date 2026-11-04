# Lessons from personal-agent architectures

**Purpose:** record *what we aspire to borrow*, not copy implementations or declare features complete. Status is based on the linked public descriptions and source/design documentation reviewed on 2026-11-04. Vendors' public claims are not independent security audits.

Chomiii is a general-purpose assistant for ordinary people. Coding is just one possible conversation topic—not its primary persona, UX, or tool surface.

## Meta Muse — ordinary-language actions with strong boundaries

**Reference:** [Meta introduction](https://about.fb.com/news/2026/09/introducing-muse-personal-ai-agent/) and [safety design](https://research.meta.ai/blog/security-and-safety-for-ai-agents-our-approach-with-muse).

**Observed public design:** a messaging-oriented personal agent, a dedicated secure VM/browser, connected services, user-controlled access, and a separate Sentinel approval/checking boundary. These are Meta's descriptions; the product is not an open-source architecture we can simply import.

**Aspiration for Chomiii:** ordinary users ask for outcomes in natural language. At M3, separate the assistant's *proposal* from Java-enforced *permission and execution*. Show reviewable action details and an audit trail.

**Not now:** a VM per person, automatic transactions, agent-generated tools, or a second model checking every normal chat request. We should not claim equivalent security.

## OpenAI dots — continuity and responsibility between conversations

**References:** [product announcement](https://openai.com/index/introducing-dots/) and [getting started](https://help.openai.com/en/articles/20001530-getting-started-with-your-dot).

**Observed public design:** an always-on agent with its own cloud computer, connected apps, relevant personal context, ongoing tasks, human review and follow-up. Source code and implementation details are not published here.

**Aspiration:** retain the *goal* and the user's last stopping point, not merely messages. Eventually show a plain-language list of what is in progress, waiting for a decision, finished or stopped. Let users redirect or cancel it.

**Not now:** background execution without durable task ownership, a cloud computer by default, or unprompted notifications without explicit user control. This is M5, not M1.5.

## Open-source Muse (not Meta Muse) — evidence-backed continuity

**References:** [repository](https://github.com/wlsdks/muse-agent), [architecture](https://github.com/wlsdks/muse-agent/blob/main/docs/architecture/README.md).

**Observed design:** its own provider interface, one runtime for several surfaces, owner-controlled personal storage, distinct memory/recall layers, explicit continuation threads, evidence receipts, and guarded actions.

**Aspiration:** when someone says "continue planning that weekend", recover the relevant facts, changes and decisions with provenance, and offer the next step. Ground personal facts in traceable sources instead of inventing memories.

**Not now:** copy the entire graph engine, 39-package workspace, or Rust runner into a Java app. Start with a small owner-scoped context/memory interface and measure whether retrieval actually helps.

## OpenDot by thinkwee — compact context, tool gates and honest task state

**References:** [repository](https://github.com/thinkwee/OpenDot) and [design notes](https://github.com/thinkwee/OpenDot/blob/master/docs/DESIGN.md).

**Observed design:** a single Python web service coordinating agents, model adapters, context compaction, a gatekeeper (allow/ask/deny), app connectors, a secret vault, durable jobs and scheduled routines. Stable instruction sections precede changing context; tools and large skill descriptions load only when relevant.

**Aspiration:** compose short, stable prompts; keep context budgets; add a deterministic policy gate for tools; restore pending jobs safely after restarts; give ordinary users a simple chat/inbox/plan experience.

**Not now:** multiple named agents for minor chores, full local-computer access, a large app directory, or automatic skill creation before the first reliable action works.

## OpenClaw — gateway, multi-channel runtime and layered memory

**References:** [architecture](https://github.com/openclaw/openclaw/blob/main/docs/concepts/architecture.md), [system prompt](https://github.com/openclaw/openclaw/blob/main/docs/concepts/system-prompt.md), [memory architecture](https://github.com/openclaw/openclaw/blob/main/docs/concepts/memory-architecture.md).

**Observed design:** one long-running gateway coordinates messaging surfaces, typed protocol, model runs and scheduled work. Prompt composition separates stable content from changing runtime details; memory distinguishes curated user facts, episodic history and prospective intents, with provenance rules.

**Aspiration:** a single Chomiii runtime with future mobile/chat surfaces sharing the same authorization and task state. In M1.5 specify context tiers; in M2 build provenance-aware memory; in M5 add durable scheduled tasks. Keep curation off the visible first-token path.

**Not now:** a large gateway, arbitrary node commands, many channel adapters or global auto-memory ingestion. Audit the actual code and threat model before adapting any specific security technique.

## Letta — small core memory plus retrievable longer history

**Reference:** [Letta repository](https://github.com/letta-ai/letta) and [documentation](https://docs.letta.com/).

**Observed design:** stateful agents with memory abstractions distinguishing directly accessible core context from larger stores accessed on demand.

**Aspiration:** keep a small, authorized personal-context summary and retrieve episodic facts only when the task needs them. User correction and removal must be explicit.

**Not now:** assume any memory framework satisfies our requirements for owner isolation, consent, deletion, security, and data locality. Evaluate those properties before adopting an implementation.

## Cross-cutting decisions for Chomiii

| Lesson | Earliest milestone | How we will know it works |
| --- | --- | --- |
| Design for ordinary conversations first | M1.5 | Real-life scenarios and usability criteria documented and reviewed |
| Keep system identity compact; separate changing context | M1.5 | No prompt pollution, role isolation tests, stable startup prompt |
| Distinguish chat history from consented personal memory | M2 | Owner-scoped recall, correction and deletion tests |
| Add specific workflows, not a pile of generic skills | M2.5 | A skill improves a measured real-user task |
| Keep actions behind typed code and permissions | M3 | No unapproved side effect in fake-model tests |
| Continue goals after a restart | M5 | Task recovery, cancellation and idempotency tests |
| Be honest about performance | M1.1 onward | Matched benchmark of direct model vs app, with first-token breakdown |

The shared ambition: **one approachable assistant, backed by separate systems for context, memory, action permissions and durable work**. We will borrow principles, not silently adopt dependencies.
