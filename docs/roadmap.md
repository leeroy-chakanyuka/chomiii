# Roadmap

Status of each milestone is checked against [verification.md](verification.md).
M0 established the foundation. M1 has a locally verified Gemini chat path;
its Docker startup remains unverified on the machine used for this milestone.
M1.1 has measured the latency bottleneck without meeting its speed target;
M1.5 product/context scope is delivered in PR #2, with full-suite verification still outstanding; see [M1.5 closure](m1.5-closure.md).
The milestone expansions below are **planned scope, not delivery commitments or deadlines**.
Implementation status must be verified independently; a planned phase is not a claim that the feature works.
The active memory/tool-runtime review is tracked in PR #12, with PR #9 retained as a reference.
Later stages can overlap when dependencies permit; M4's connector catalogue does not have to be complete before M5, M6 or M7 work begins.

```mermaid
flowchart TD
    M0["M0 Foundation<br/>public repo · architecture · TDD · CI"]
    M1["M1 Chat<br/>Gemini first · BYOK · local streaming chat"]
    M2["M2 Memory<br/>user-scoped persistence"]
    M3["M3 Actions<br/>everyday tools · approvals · bounded loop"]
    M4["M4 Integrations<br/>Google · search · commerce · MCP"]
    M5["M5 Durable autonomy<br/>tasks · scheduling · retries"]
    M6["M6 South African workflows<br/>localization · budgeting · transport · messaging"]
    M7["M7 Consumer maturity<br/>onboarding · security · reliability · release"]
    M0 --> M1 --> M11["M1.1 Performance"] --> M15["M1.5 Product and context"] --> M2 --> M25["M2.5 Workflow skills"] --> M3 --> M4 --> M5 --> M55["M5.5 Headless agents"] --> M6 --> M65["M6.5 Voice"] --> M7 --> M8["M8 Optional infrastructure"] --> M9["M9 Education companion"]
```

## M0 — Foundation

Prove the engineering discipline on a minimal vertical slice before any AI work: a **public repository**, a **two-process architecture** behind an explicit HTTP contract, **test-first development**, and **CI**.

**Implemented so far:** repository contract ([AGENTS.md](../AGENTS.md)), two-process architecture (Java 21 / Spring MVC backend, React + Vite frontend), dual-database configuration — MySQL locally via `application-local.yml`, PostgreSQL in CI/production via `application-prod.yml` — with Flyway versioned migrations, the `GET /api/health` contract with the RFC 7807 error convention, this documentation set, and the CI workflow definition in `.github/workflows/ci.yml`.

**Acceptance criteria — status as observed (see [verification.md](verification.md) for evidence; pending rows are M0's remaining work):**

| # | Criterion | Status |
| --- | --- | --- |
| M0-1 | Architecture proves test-first: backend unit/MVC tests green | ✅ Observed (23 tests after the database-strategy changes) |
| M0-2 | TDD red→green cycle demonstrably executed for the health endpoint | ✅ Observed (red 404, then green) |
| M0-3 | Frontend unit tests, lint, typecheck, build green; standalone Playwright suite green | ✅ Observed (7/7, lint/typecheck/build passed; 4 browser tests) |
| M0-4 | Database verification on both engines (local MySQL suite; PostgreSQL in CI) | ✅ MySQL suite and PostgreSQL migration smoke test passed locally; 🔶 PostgreSQL Testcontainers CI blocked |
| M0-5 | Frontend↔backend browser integration green | ✅ Browser-to-backend tests passed locally against both MySQL and PostgreSQL; CI blocked |
| M0-6 | Dependency audits (frontend high gate; backend OWASP scan in CI) | ⚠️ Frontend high threshold met; 12 moderate minimatch findings remain. Backend scan configured in CI, not yet executed |
| M0-7 | Public hosted repository, pushed history, and **CI green on it** | 🔶 Public repository created and pushed; GitHub Actions blocked by an account billing issue |

## M1 — Chat (implemented locally, Docker smoke pending)

The browser accepts a user-supplied Gemini key, selects a configured model,
streams real responses, and resumes conversations saved in PostgreSQL. A live
browser run completed a two-turn conversation with Gemini, reloaded, and
reopened the history with the same anonymous session cookie. The key required
re-entry after reload. See [verification.md](verification.md) and the
[API reference](api.md) for observed checks and contracts.

- A small `ChatProvider` port keeps Gemini SDK calls outside chat services.
- The frontend keeps API keys in memory. The Java backend creates
  request-scoped Gemini clients and never stores the key.
- Flyway V2 persists conversations and messages on PostgreSQL and MySQL.
- Anonymous HttpOnly cookies and owner-filtered queries isolate browser
  sessions; the production profile requires HTTPS and an allowed Origin.
- `POST` SSE sends `start`, `delta`, `complete`, or `error` events. A stop
  request cancels the active provider call.
- Unit, PostgreSQL API, MySQL migration and API, browser fixture, and live
  Gemini browser checks passed locally. The machine has no Docker executable,
  so Compose configuration and stack restart remain unverified.

M1 does not claim a hosted deployment, account sign-in, CI results, or
cross-device synchronization. Those were part of an older draft acceptance
criterion and are outside the local M1 scope. Authentication, abuse
prevention for a public service, and privacy operations need reassessment
before hosting.

## M1.1 — Performance and observability (partially implemented; speed target unmet)

Measure browser time to first visible token, provider first-token latency, and completion with p50/p95 statistics. The existing [benchmark](perf/README.md) recorded a 15.14-second median first *visible* reply in five browser requests; most measured API waiting time occurred while opening the Gemini stream (7.91–21.83 seconds), whereas the initial database transaction took 9–28 ms. Direct provider calls were also highly variable. This implicates the provider path during those samples but does **not** prove all application overhead is irrelevant. The <=2-second first-visible-token goal remains unmet. Do not invent speedups from prompt changes; compare matched requests, models and load conditions.

## M1.5 — Product foundations and context (scope delivered; tests pending)

First answer [what we are building and who it is for](product-vision.md).
Chomiii is an everyday personal assistant, not a coding-specific agent. Keep
a concise versioned identity ([SYSTEM.md](../backend/src/main/resources/agent/SYSTEM.md)) and define the [context contract](context.md):
trusted identity, current user turn, bounded owner-scoped recent history, and
clear separation from personal memory. No automatic generic skill selection,
unnecessary provider calls, or promises of capabilities we do not have.

**Acceptance:** a simple general-purpose identity, product jobs and success
measures, an inspectable context path, tests for instruction separation and
limits, and an honest description of what exists, and a ranked everyday-use-case backlog.
Source-derived design lessons
are recorded in [agent-landscape.md](agent-landscape.md); the target boundaries
are in [agent-architecture-plan.md](agent-architecture-plan.md).

## M2 — Memory

**Current M2 review:** [PR #3](https://github.com/leeroy-chakanyuka/chomiii/pull/3)
now includes automatic extraction by default after eligible completed chats, evidence
checks, user review of inferred candidates, and a save/list/correct/delete/
approve/clear UI and SQL store,
plus relevant lower-trust context. The [research decision](m2-research.md)
rejects Beacon as a consumer memory backend for now and adopts a small Spring
service. **This is an initial slice, not verified release completion.** Cookie
identity is not a named account; cross-device synchronization, retention
cleanup and full test evidence still require work. See [design](m2-design.md).


Give each person user-scoped, trustworthy personal memory and relevant context. Evaluate **Agent Beacon first as a candidate**, not an automatic adoption: its coding-agent telemetry and learned-skill focus may not fit ordinary consumer memory. Compare with [references](references.md) using consent, provenance, privacy, export/deletion, owner isolation, data flow, and latency; document the result.

- **User-scoped persistent memory**: memory always belongs to a named user, isolated at the data layer, never shared silently between accounts.
- Remember / retrieve / correct / delete paths, each tested end-to-end including the browser; deletion actually deletes.
- **Provenance and consent**: every memory records where it came from and what the user approved; users can inspect and delete it.
- **Acceptance:** memory CRUD with user scoping, provenance, and consent covered by tests on both engines and in the browser; deletion propagated to connected stores where providers are used.
- **Consumer scenario:** "my usual groceries" is recalled only when saved or approved, updated when corrected, and honestly unknown when never supplied. This enables future workflows but does not purchase anything in M2.

## M2.5 — Task-specific skills and workflows

Introduce a skill or structured workflow **only when it demonstrably improves a
real everyday task**, such as appointment preparation, organizing a budget or
coordinating a personal plan. Standard writing, summarization and basic
reasoning remain normal model abilities, not compulsory `/skill` commands.

- Progressively load a relevant workflow definition or reference material,
  avoiding a large permanent system prompt or a second model call by default.
- Keep skills declarative; no skill grants access to a user's private data or
  authorizes an external action.
- **Acceptance:** real-user task scenarios, bounded context size, measurable
  utility versus plain chat, and tests ensuring untrusted material cannot
  modify higher-priority instructions.

## M3 — First real actions

From chat to useful, permissioned everyday actions: a minimal agent runtime
fully testable with a fake model. Start with a small ordinary task such as
creating a reminder or managing a personal to-do—not a coding tool.

- **Tool registry and tool calls**: a small set of typed, everyday tools with documented schemas.
- **Bounded loop**: planner loop capped in steps, wall-clock **timeout**, and a deterministic stop.
- **Permissions and approval**: every tool behind a server-side least-privilege check; consequential external actions require explicit user approval.
- **Trace**: each run traced (steps, tool calls, approvals) so behavior is auditable.
- **Acceptance:** deterministic fake-LLM tests for the whole loop; no tool executes without its permission check; timeout and bounded-turn behavior covered by tests; traces inspectable.
- **Consumer scenario:** prepare and authorize a simple reminder/task action end-to-end. Compound email/calendar/grocery workflows are an integration-era goal, not the first M3 slice.

### M3 subphases — actions and adaptive plans
- **M3.1 Planning:** validate structured proposals and clarify genuinely missing information.
- **M3.2 Tool runtime:** provider-independent registry, `memory.search`, bounded execution, typed observations and server-owned authorization.
- **M3.3 First actions:** owner-scoped to-do creation/listing/completion, idempotency, and in-chat approvals.
- **M3.4 Adaptive execution:** persistent, revisable plans; tool observation, pause/resume, cancellation and accurate progress.
- **Permissions:** `Deny / Approve once / Allow always` with narrowly scoped, revocable grants; see [permissions issue #13](https://github.com/leeroy-chakanyuka/chomiii/issues/13). High-impact external actions may still require specific confirmation.
- **Evaluation:** use a reusable fictional user and compound multi-tool stories; keep integration tests, tool traces and provider independence within the affected implementation phases.

These are functional checkpoints, not calendar dates.

## M4 — Integrations

Connect the existing Java-owned agent runtime to external capabilities, using official APIs when available and permitted browser automation only after feasibility, terms and security checks. Integration names below are **targets, not claims of accessible APIs or completed features**. External read access can require explicit permissions, and payments must remain user-confirmed.

### M4 integration phases (proposed order)

| Phase | Scope | Intended outcome |
| --- | --- | --- |
| M4.1 — Google I | Read-only Calendar, Gmail and Drive | Authenticated search/inspection with narrow OAuth scopes |
| M4.2 — Google II | Calendar changes, email drafts/sending and permitted Drive writes | Approved actions with audit traces and safe retry behavior |
| M4.3 — Google productivity | Google Tasks and feasibility of Keep | Internal to-dos and Google task data can be linked without duplicate execution |
| M4.4 — Web search | Search provider adapter | Grounded external research with source links and bounded results |
| M4.5 — Online shopping | Takealot, Bash, Makro, OneDayOnly | Discovery/price comparison where access is permitted; seller APIs are not assumed to support consumer carts |
| M4.6 — Food discovery | Uber Eats and Mr D | Menus, availability and pricing where supported |
| M4.7 — Food ordering | Basket preparation and checkout handoff | Verify exact contents and let users finish payment themselves |
| M4.8 — Grocery discovery | Pick n Pay asap!, Woolies Dash, SPAR2U | Search, compare and check availability where supported |
| M4.9 — Grocery basket handoff | Supported grocery providers | Build and verify baskets, preserving user-controlled checkout |
| M4.10 — Checkers Sixty60 | Discovery, cart and handoff feasibility | Regional commerce workflow behind the same tool contracts |

**MCP interoperability** is cross-cutting: introduce external MCP servers through the same capability registry and permissions boundary when a real use case warrants one, rather than creating an independent execution engine.

**Inference-provider portability** remains a cross-cutting planned workstream (previously labeled **M4.5** in the older roadmap); the old number is retired to avoid colliding with M4.5 online shopping. Evaluate Gemini, Groq, OpenRouter, Cloudflare Workers AI, Mistral and local inference for legitimate quotas, latency, privacy, terms, streaming and native tool calls. Require user-approved fallback rather than silently switching where user data goes.

**Acceptance:** Each connector has documented access feasibility, authenticated owner isolation where relevant, authorization, error and failure tests, cancellation/timeout behavior, redacted traces, and verified user-facing outcomes. Browser automation must not report cart success without confirming items/quantities and a viable checkout handoff. A blocked provider remains a research/backlog item, not a fake integration.

The integration wishlist can live in a separate `docs/integrations-backlog.md` when created; it should retain the full range of local and international targets without implying they are all M4 critical path.

## M5 — Durable autonomy

Long-running work the user can trust even when the server, network, or process fails.

- **Tasks**: user-declared goals tracked across restarts; **scheduling**, **retries** with backoff, and **cancellation** that actually stops work.
- **Recovery and idempotency** so retried work never double-executes consequential actions; **history** of what ran, what changed, and why.
- **Acceptance:** kill-and-recover tests prove tasks resume exactly once; cancellation and idempotency covered by tests; per-task history inspectable in the browser.

### M5 subphases — durable autonomy
- **M5.1:** persistent scheduled jobs and restart-safe execution.
- **M5.2:** recurring routines with explicit user-configured schedules and controls.
- **M5.3:** authorized event/webhook triggers, deduplication and revocation.
- **M5.4:** long-running goals, checkpoint/recovery, bounded retries and idempotency.
- **M5.5:** isolated headless agents/delegation and collaboration, only after security and lifecycle boundaries are sound. The existing M5.5 designation is retained.
- **M5.6:** proactive notifications, activity history, pause/resume and autonomy limits (the originally discussed fifth ordinary-autonomy capability; numbered M5.6 to preserve M5.5 for headless agents).

M5 extends M3.4's persistent plan state; it does not replace the conversation/tool runtime.

## M6 — South African workflows

Regional, practical usefulness — researched first.

- **Budgeting in rand** (local spending habits), **shopping** (price-gathering across local retailers), and **transport** (Gautrain, e-hailing, minibus-taxi contexts) based on real local providers.
- **WhatsApp** integration **only after researching its APIs** (official Meta Business API) and their constraints; if the research shows it is not feasible for a self-serve product, WhatsApp stays out and the decision is recorded.
- **Multilingual / regional awareness** so the agent handles South African contexts, names, and languages in memory retrieval and responses.
- **Acceptance:** each workflow implemented against a documented provider or explicitly cut with recorded reasoning; correctness for rand values, regional content, and language covered by tests.

### M6 subphases — local workflows
- **M6.1 Localization:** rand, South African dates/addresses, regional context, public holidays, terminology and supported languages.
- **M6.2 Shopping and budgeting intelligence:** compare complete basket costs with delivery, substitutions, promotions and realistic household budgets using M4 integrations where available.
- **M6.3 Transport intelligence:** combine permitted public transport and e-hailing information, prices and personal routines without inventing live schedules.
- **M6.4 WhatsApp and local communication:** investigate official Meta WhatsApp Business Platform scope, eligibility and UX, then ship only supported workflows.
- **M6.5 Voice chat:** push-to-talk STT/TTS first, with streaming conversation, interruption and accessibility evaluated subsequently. This is kept as the already-reserved voice milestone.

M6 combines existing tools into regional outcomes. It must not rebuild M4 connectors.

## M7 — Consumer maturity

Make it something real people can rely on.

- **Onboarding** that works without an engineering background; **reliability** (uptime, error budgets, graceful degradation) and **privacy** (clear data flow, deletion honored everywhere) as product features, not paperwork.
- **Cost visibility and control** for BYOK usage and any running tasks; **authentication** and **operations** runbook finalized.
- **Hosted** offering plus documented, working **self-hosting** (same contract, real deployment path).
- **Acceptance:** hosted and self-hosted paths both pass the full test battery incl. browser E2E; security review complete; roadmap and verification evidence reconciled.

### M7 subphases — consumer readiness
- **M7.1 Onboarding:** understandable account setup, consent, connected-service onboarding and memory controls.
- **M7.2 Security and privacy:** robust authentication, permission revocation, owner isolation, data export/deletion and security review.
- **M7.3 Reliability, cost and operations:** quotas, model spend visibility, monitoring, incident response, graceful degradation and performance.
- **M7.4 Production readiness:** supported hosted and self-hosted flows, mobile-friendly interaction and end-to-end release verification.

Voice and consumer usability may be prioritized earlier when they unblock meaningful testing; numbering is a planning aid, not a strict sequential dependency.

## M8 — Optional infrastructure and deployment

Document reproducible deployment and operational checks. Evaluate containers, scaling and infrastructure complexity only when they serve demonstrated needs. No Docker requirement is introduced retroactively for earlier milestones.

## M9 — Education companion (later milestone)

Build a student-centered experience after the general-purpose agent and core integrations are established. This is deliberately positioned **near the end**, not as an immediate M4 dependency.

- Connect to Blackboard where institutional authorization permits (evaluate LTI launches against REST API access; do not assume LTI alone grants complete course/grade/file access).
- Read course announcements, assignments, exams, deadlines and course attachments with owner-scoped consent.
- On login, incrementally synchronize changed coursework rather than reprocessing every file; use M5 scheduling for optional recurring refreshes.
- Prepare ready-to-start study plans and sessions, retrieve supporting course materials, and distinguish study assistance from completing assessed work for the student.
- Offer question practice and progress-aware adaptation, optionally using a future StarPath integration rather than duplicating a whole learning platform inside Chomiii.
- Test institution-level access restrictions, revocation, privacy, source attribution, changed deadlines, file parsing, duplicate sync, and offline/recovery behavior.

**Acceptance:** a synthetic student fixture can produce a trustworthy daily coursework overview and useful study session with correct source links, no fabricated deadlines, and no unsupported Blackboard access claims.

## Milestone management notes

- **No dates or delivery deadlines** are assigned by this roadmap. Personal estimates and model-rate-limit observations are not milestone commitments.
- Prioritization is driven by user impact, integration feasibility and available resources; stages may overlap once their technical dependencies are satisfied.
- A phase is complete only when its acceptance criteria have been verified. Keep evidence in [verification.md](verification.md).
