# Verification evidence

## M2 evaluation harness, 2026-11-05

The evaluation branch is based on M2 commit `720cb5f`. It adds 40 synthetic
fixtures and test-scoped runner, scoring, reporting, judge, and manual-review
code. It does not change production memory behavior. The prior M2 baseline was
not rerun for this work at the owner's request.

| Check | Result | Evidence |
| --- | --- | --- |
| Offline harness | PASS | `backend/mvnw.cmd -Dtest=com.chomiii.evaluation.*Test test`: 48 tests, 46 passed and 2 opt-in entry points skipped. The generated offline report completed 40 fixtures. |
| Repeated selection | PASS | `mvnw.cmd -Dtest=EvaluationSuiteTest -Deval.category=long-messages -Deval.repetitions=3 test`: 21 scripted case runs completed. |
| Manual review | PASS | `ManualReviewCliTest` read a generated review template and wrote a separate report without provider calls. |
| PostgreSQL integration | PASS | On disposable PostgreSQL 17.11, `mvnw.cmd -Dtest=EvaluationSuiteTest,BudgetedEvaluationDatabaseTest -Deval.mode=integration -Deval.category=lifecycle test`: 2 JUnit tests passed and the report completed 11 lifecycle fixtures. |
| MySQL integration | PASS | The same command passed against disposable MySQL 8.0.43, with 11 lifecycle fixtures. Both database runs applied V1–V3 and verified the call-budget test. |
| Database target safety | PASS | Both runs supplied deliberately incorrect `SPRING_DATASOURCE_URL` and `SPRING_FLYWAY_URL` overrides. Flyway and the application connected to the validated disposable database instead. |
| External model evaluation | NOT RUN | No live Gemini or external judge calls were enabled. The independent judge adapter was tested against a local HTTP fixture only. |
| Independent implementation review | NOT RUN | No independent reviewer was dispatched. |

The full offline report is a scripted pipeline check, not a Gemini score. It
demonstrates that the current parser can accept an unsupported claim when the
supplied quote appears in the user message, and that exact duplicate checking
does not suppress a paraphrased duplicate. Scripted correction cases also leave
older contradictory facts active. These observations do not establish how often
Gemini produces such candidates. No live precision, recall, grounding, latency,
token cost, or release-gate result is claimed.

## M2 PR #3 review, 2026-11-05

The review fixed the missing session filter on memory endpoints. It also fixed
a stale-learning race, bounded Gemini extraction time, and corrected memory
panel loading and response validation. The original backend test run failed to
compile because `ChatIdentityTest` lacked a `MemoryLearningService` import.
With a disposable PostgreSQL database, the first full run exposed the missing
session filter and an assertion that mixed chat and extraction requests.
Both failures passed after the fixes.

| Check | Result | Evidence |
| --- | --- | --- |
| Backend suite and package | PASS | `backend/mvnw.cmd package` against disposable PostgreSQL 17.11: 105 tests, 104 passed and the opt-in live Gemini test skipped. Flyway applied V1, V2, and V3. |
| Memory and chat database tests | PASS | `mvnw.cmd -Dtest=MemoryDatabaseTest,ChatStreamDatabaseTest test`: 9 passed on PostgreSQL. Covers owner isolation, origin checks, approval, and stale learning after deletion or clear. |
| Frontend unit tests | PASS | `npm run test`: 34 passed. A new stale-load test failed before the load-order fix and passed after it. |
| Frontend checks | PASS | `npm ci`, `npm run lint`, `npm run typecheck`, and `npm run build`. `npm audit --audit-level=high` passed the high-severity gate; 7 moderate findings remain. |
| Browser fixture tests | PASS | `npm run test:browser`: 8 passed. |
| Browser and backend | PASS | `npm run test:browser:integration`: 2 passed against PostgreSQL; the opt-in live Gemini test was skipped. A browser saved, corrected, reloaded, and deleted a memory. A separate browser could not see it. |
| MySQL and PostgreSQL Testcontainers | NOT RUN | MySQL verification was deferred to keep database setup short. Docker is unavailable, so the PostgreSQL Testcontainers Failsafe suite did not run. |
| Live memory learning and cost | NOT RUN | No live Gemini extraction, learning accuracy study, or token-cost measurement was performed. |

## Local performance and UI follow-up, 2026-11-04

The response-time target is **not met**. In the five-sample after run,
first-visible browser text had p50 15.14 seconds and p95 17.43 seconds.
In alternating requests, direct Gemini had p50 18.88 seconds and p95
21.63 seconds. Both samples have substantial variance, so the local UI change
cannot be credited with a measured end-to-end speedup. The earlier ten-sample
baseline, matching direct comparison, stage timings, prompt conditions, and
limitations are in [perf/README.md](perf/README.md).

The frontend test held the conversation-list refresh open. Before the change,
the stream never started; after removing the unnecessary await, it started
without waiting. `ChatIdentityTest` failed before the system prompt identified
Chomiii as warm and passed afterward. `MarkdownReply.test.tsx` failed when
Markdown was shown as literal text and passed with Markdown rendering. The
browser key test initially could not find the remember option; it passed after
implementation and checked reload, removal, encrypted IndexedDB storage, and
absence of the raw test key in localStorage, sessionStorage, and cookies.

| Check | Result | Evidence |
| --- | --- | --- |
| Backend tests | PASS | 37 focused tests on isolated PostgreSQL, including provider streaming/cancellation, distinct credentials, log redaction, context, chat API, isolation, and restart. |
| Frontend unit tests | PASS | `npm run test`: 14 tests passed. |
| Frontend build and lint | PASS | `npm run build` and `npm run lint` passed. |
| Browser fixture tests | PASS | Seven tests passed before the extra storage-unavailable case; both remembered-key tests then passed. |
| Browser live latency | PASS, target missed | Five measured real Gemini requests, one warmup, no failures; see [browser-after.json](perf/browser-after.json). |
| Windows launcher | PASS | `node tools/launcher/smoke.mjs` started the actual frontend, backend, and isolated PostgreSQL twice and reopened a test conversation after restart. `start-chomiii.bat` also launched the app in a visible command window. |
| Independent review | NOT RUN | The owner deferred further independent review in this conversation. |

The existing green visual theme remains. The generated
[desktop](screenshots/chat-desktop.png) and [mobile](screenshots/chat-mobile.png)
screenshots show fixture conversations and do not contain user credentials.
Gemini supports thought summaries, but this change does not expose them as a
trace. The earlier badge and command references were not specific enough in
the available conversation to determine their original requirements.

## M1 baseline, 2026-11-04

Before M1 changes, the working tree was clean on `main` at `566abf8`.
The existing origin was `https://github.com/leeroy-chakanyuka/chomiii.git`.

| Local check | Result | Evidence |
| --- | --- | --- |
| Backend build and unit tests | PASS | `backend/mvnw.cmd package`: 23 tests passed and the executable jar was built. An earlier concurrent `mvnw.cmd test` timed out; the subsequent package run completed. |
| Frontend tests | PASS | `npm run test`: 7 tests passed. |
| Frontend build and lint | PASS | `npm run build` and `npm run lint` completed. |
| Standalone browser tests | PASS | `npm run test:browser`: 4 tests passed. These tests did not exercise a backend. |
| PostgreSQL | NOT RUN | Nothing was listening on port 5432. A local PostgreSQL binary is present; it had not been started for this baseline. |
| Docker Compose | BLOCKED | `docker compose config` could not run because `docker` was not found. The repository had no Compose file or Dockerfiles. |
| Pstack | PASS | OpenCode loaded the `poteto-mode`, `tdd`, and `architect` skills. |

## M1 local verification, 2026-11-04

| Verification | Result | Evidence |
| --- | --- | --- |
| Backend unit and MVC tests | PASS | Isolated MySQL runner executed `mvn clean -Plocal-mysql verify`: 62 Surefire tests, 57 passed and five intentionally skipped because they require PostgreSQL or an opt-in live key. |
| MySQL migration and integration | PASS | Same clean run: Flyway applied V1 and V2; two MySQL Failsafe tests passed. Eight conversation API tests and four streaming API tests also passed against MySQL 8. |
| PostgreSQL integration and API | PASS | Isolated PostgreSQL 17.11 runner executed the focused chat, session, restart, rate, and context suite. Four streaming API tests, eight conversation API tests, hosted HTTPS/cookie, request limiting, restart, and four pure unit tests passed (20 total). The Docker Testcontainers suite was not run. |
| Frontend unit tests | PASS | `npm run test`: 12 tests passed across health, BYOK setup, SSE parsing, progress, stop, history, and key removal. |
| Frontend lint and build | PASS | `npm run lint` and `npm run build` passed, including both TypeScript configs. |
| Standalone browser tests | PASS | `npm run test:browser`: six Chromium tests passed, including two-turn fixture chat, reload, mobile layout, and keyboard submission. |
| Real browser, API, PostgreSQL, Gemini | PASS | A locally started Spring Boot jar and PostgreSQL instance ran `npm run test:browser:integration` with the opt-in live test. Two tests passed. The browser sent two user messages, received Gemini replies, reloaded, re-entered its key, and reopened the messages. |
| Live Gemini adapter | PASS | Opt-in `GeminiLiveTest` returned `Hello.` through the real Google SDK. |
| Conversation persistence and session isolation | PASS | PostgreSQL restart test reopened a message after the Spring application context restarted, marked a pending assistant turn interrupted, and cleared the stale claim. Both database-backed API suites rejected access from a different cookie. |
| Docker Compose stack and restart | BLOCKED | `docker compose config` could not run because Docker is not installed. `compose.yaml` parsed as YAML, but no container or named volume could be smoke-tested. |
| Security review | BLOCKED | Earlier independent provider and session reviews found issues that were fixed and tested. A review of the combined change was attempted but the dispatch was aborted; the user requested no independent review today. A self-audit and local checks are not an independent verdict. |

After reviewing the passing local checks, the project owner deferred Docker
smoke verification and a final independent review for this handoff. Neither
deferred check is claimed as passed.

The browser displayed actual Gemini text, shown without credentials in
[chat-live.png](screenshots/chat-live.png). The completed interaction was:

```text
User: My favourite programming language is Java. Please acknowledge that in one sentence.
Chomi: That's awesome, Java is a powerful and reliable language known for its "write once, run anywhere" philosophy!
User: Which programming language did I mention? Reply with its name.
Chomi: Java
```

The backend API test read the first `delta` from a real HTTP response while
the provider fake was blocked before completing. The frontend component test
observed `Hello` on screen before its second chunk arrived. Those tests
demonstrate progressive rendering rather than an artificial typing effect.
The live test showed real Gemini replies but did not count the number of
chunks Gemini emitted on that particular request.

### Additional M1 red-to-green evidence

`ContextWindowTest` initially returned only the current user message instead
of the earlier Java user turn and assistant reply. After implementing the
context window, both tests passed. `ChatStreamDatabaseTest` first returned
404 because the POST stream endpoint did not exist; four API cases passed
later against both PostgreSQL and MySQL. In the frontend,
`chatApi.test.ts` initially received `[]` instead of a `delta` containing
`Hello`, and `ChatApp.test.tsx` could not find the Gemini key field. The
parser and setup UI made those checks green. Finally, the session-budget test
returned 429 for a second browser behind the same proxy; after separating
known sessions from new-session IP limits, it passed against PostgreSQL.

The first opt-in live browser run received both Gemini replies and captured
its screenshot, then failed because a test selector matched both the chat
button and Delete. The corrected test passed on the next isolated run.

A final self-audit found that aborting the browser stream immediately after
the stop request could load history while the assistant row was still pending.
The frontend now waits for the terminal event and uses a six-second abort
fallback. A component test delays the backend's interruption and confirms
the UI displays `Response stopped` after the stored status changes. Its
three component tests and the TypeScript check passed after the change.

The M0 sections after the M1 test-first record are historical. Their test
counts and CI notes do not describe the M1 implementation.

### M1 provider catalog test-first cycle

`backend/mvnw.cmd -Dtest=ProviderCatalogMvcTest test` failed before the
catalog was added. The response to `GET /api/chat/providers` was 404 instead
of 200. The test specifies that Gemini and its default model are discoverable
without supplying a key.
After the catalog was added, the same command passed with 1 test and no failures.

`backend/mvnw.cmd -Dtest=GeminiProviderTest test` was run against an empty
provider implementation before adding the SDK adapter. It failed with an empty
chunk list instead of `Hello`, ` world`.
With Google GenAI Java SDK 1.80.0, the same test passed against a local HTTP
fixture. The fixture observed the supplied test key in a header, the ordered
conversation and separate system instruction in the request, and two response
chunks. This is not a live Gemini test.

An idle-stream cancellation test initially timed out because closing the SDK
reader waited for a blocked read. The adapter now cancels the underlying
request. `backend/mvnw.cmd -Dtest=GeminiProviderTest test` passed all 13 tests,
including cancellation while the provider was idle and separate concurrent
credentials.

An actual delayed-server timeout test returned `UNAVAILABLE` instead of
`TIMEOUT`. The adapter now recognizes the HTTP client's interrupted I/O
timeout. The same focused test passed after that change.

The opt-in `GeminiLiveTest` ran once with the available `GEMINI_API_KEY` and
`CHOMIII_RUN_GEMINI_SMOKE=true`. Google returned `Hello.` for the prompt
`Reply with the single word hello.` The test used the real SDK and network,
not the local fixture. It verifies the adapter, not a complete chat journey.

An independent provider review identified the HTTP client's default read
timeout and the handling of HTTP 400 responses. Before their fixes, focused
tests failed when a stream paused for 11 seconds and when a non-key HTTP 400
was classified as an invalid key. The adapter now sets explicit timeouts and
distinguishes invalid requests. `backend/mvnw.cmd
"-Dtest=GeminiProviderTest,ProviderCatalogMvcTest" test` passed all 18 tests
after the fixes.

The complete backend unit and MVC regression command, `backend/mvnw.cmd test`,
then passed 41 tests with the opt-in live test skipped. The live test had
already been run separately as described above. An independent reviewer checked
the provider stage; the actionable timeout and error-classification findings
were covered by failing tests and fixed.

### M1 conversation storage test-first cycle

An isolated PostgreSQL 17.11 instance accepted a real query. Against that
database, the initial `ConversationDatabaseTest` failed because
`GET /api/conversations` returned 404 instead of 200. After the first storage
implementation, the PostgreSQL-backed `ConversationDatabaseTest` passed five
tests covering creation, ownership, deletion, ordering, constraints, and origin
checks. A later run passed seven conversation tests and two tests that reopen a
conversation after a Spring application context restart. After the restart test
was extended, a later PostgreSQL run passed all 11 conversation, hosted
security, and restart tests, including reopening a stored message. The restart
test was extended again to simulate a pending generation; its two cases passed
after confirming the incomplete assistant is interrupted and the claim clears.

An independent session review identified missing no-store response headers.
Two PostgreSQL-backed checks failed because `Cache-Control` was absent. A
hosted-profile check passed for HTTPS enforcement and secure cookie attributes.
The no-store checks passed in the later PostgreSQL and MySQL runs.

An isolated MySQL 8 run applied both migrations and rejected an invalid role.
The first test run failed because Spring translated MySQL's check-constraint
error differently from PostgreSQL's. The assertion now checks the observable
rejection and that no invalid row was stored. A clean local MySQL verification
passed eight conversation tests and two existing Flyway integration tests.
An earlier MySQL attempt failed during local TLS negotiation before migrations
started. A subsequent run passed the tests but failed on corrupt generated
JaCoCo data; the clean run produced the report successfully.

The first request-rate test failed because an unlimited stub accepted a third
request after a limit of two. A bounded per-client limiter is now in place.
A PostgreSQL-backed run passed 14 tests, including the limiter unit tests and
an HTTP 429 check. A later test found the shared-IP issue documented above.

## M0 historical verification

The project's evidence log: what was **actually run**, on this machine, and what was **not**. Updated by hand only with observed outcomes — never invent results ([AGENTS.md](../AGENTS.md)). Where a result predates a code change, that is stated; such results cannot be counted as evidence for the current state of the code and are followed up by re-running.

Environment: **local Windows machine, 2026-11-03, M0**.

## Tooling present

| Tool | Status |
| --- | --- |
| Java 21 | ✅ Present |
| Maven (via Maven Wrapper `mvnw.cmd`) | ✅ Present |
| Node.js + npm | ✅ Present |
| Git | ✅ Present |
| GitHub CLI (`gh`) | ✅ Present |
| OpenCode | ✅ Present |
| Docker | ❌ **Absent** — not installed locally; installation declined by the project owner. Consequence: the PostgreSQL Testcontainers suite cannot run locally and depends on CI. |

## Backend

| Check | Status | Evidence |
| --- | --- | --- |
| TDD red→green for the health endpoint | ✅ Observed | `HealthMvcTest` written and run first: **red (HTTP 404)** before the endpoint existed, then **green** after implementation |
| TDD red→green for safe error logging | ✅ Observed | `ApiExceptionHandlerTest` failed when a dummy sensitive message appeared in logs; it passed after the handler stopped logging exception messages and stack traces. |
| Backend unit/MVC tests | ✅ PASS | **23 tests passed** with `.\mvnw.cmd test` after the database and error-logging changes |
| Local MySQL + Flyway integration: `.\mvnw.cmd -Plocal-mysql verify` | ✅ PASS | **2 integration tests passed** against a newly created, isolated MySQL 8.0 database; Flyway applied migration V1. The temporary database was removed afterwards. |
| PostgreSQL production-profile smoke test | ✅ PASS | A temporary PostgreSQL 16.15 instance ran without Docker. The application started with the `prod` profile and Flyway applied V1 successfully; the server and test data were removed afterwards. |
| PostgreSQL Testcontainers suite: `./mvnw verify` | 🔶 **BLOCKED** | Needs Docker (absent locally); the first CI attempt could not start because GitHub reported an account billing issue. |

Both engines have been exercised locally. The PostgreSQL Testcontainers suite still needs a successful CI run.

## Frontend

| Check | Status | Evidence |
| --- | --- | --- |
| `npm ci` | ✅ PASS | npm's debug log records exit 0 after installing 273 packages; the terminal wrapper reached its timeout as the command completed. `npm ls --depth=0` passed afterwards. |
| `npm run test` (Vitest unit) | ✅ Observed | **7/7 tests passed** |
| `npm run lint` | ✅ Observed | Passed |
| `npm run typecheck` | ✅ Observed | Passed |
| `npm run build` | ✅ Observed | Passed |
| `npm run test:browser` (standalone Playwright) | ✅ Observed | **4 tests passed** |
| `npm run test:browser:integration` | ✅ PASS | **1 browser test passed for each engine**, against the real Java backend connected to temporary MySQL and PostgreSQL databases in separate runs. |
| `npm run ci` (full battery) | 🔶 **NOT RUN** | Its component checks were run separately rather than as one command. |

## Dependency audits

| Check | Status | Evidence |
| --- | --- | --- |
| `npm audit --audit-level=high` (frontend) | ✅ Threshold met | Passes the **high**-severity gate; **12 moderate findings remain** in the minimatch dependency chain |
| Backend dependency audit | 🔶 **NOT RUN** | OWASP dependency-check is configured in CI and has not run yet |
| Local secret scan | ✅ PASS | Gitleaks 8.30.1 scanned the proposed Git diff and reported no leaks. |
| Independent review | ✅ PASS | A separate reviewer inspected the implementation and workflow before publication; identified documentation and typecheck gaps were corrected. |

## Repository, CI, releases

| Item | Status |
| --- | --- |
| Hosted Git repository | ✅ Created and confirmed public: [leeroy-chakanyuka/chomiii](https://github.com/leeroy-chakanyuka/chomiii) |
| Push | ✅ Initial commit `9bbf163cda89fc249b6533ecfa299ed64b9c439b` pushed to `main` |
| CI pipeline | ❌ **BLOCKED** — [run 41718011204](https://github.com/leeroy-chakanyuka/chomiii/actions/runs/41718011204) failed before any job started. GitHub reported: "The job was not started because your account is locked due to a billing issue." |
| Tagged release | ❌ None |

## Honest summary

Green and observed: backend unit/MVC tests, MySQL integration, Flyway migrations on both database engines, the browser-to-backend path on both engines, frontend checks, and the `npm audit` high-severity gate. PostgreSQL Testcontainers, backend vulnerability scanning, and hosted CI remain unverified until the GitHub billing blocker is resolved.


## M1.5 implementation and design closure: everyday-assistant identity and context (2026-11-04)

- **Implemented in branch:** short, cached classpath `SYSTEM.md` for a
  general-purpose assistant, separate from user messages. Removed the
  generic skill router and all six initial `SKILL.md` files after product
  review; a real workflow-skill milestone is planned for M2.5.
- **Documented only, not built yet:** the product definition and target
  architecture, context and user-scoped memory plans, lessons from Meta
  Muse, OpenAI dots, open-source Muse, OpenDot, OpenClaw and Letta.
- **Tests authored:** `AgentInstructionsTest`, existing `ChatIdentityTest`
  and `ContextWindowTest`. **Not independently executed in this environment.**
- **Latency change retained:** the two independent post-stream browser
  refresh requests run concurrently. This improves request scheduling after
  streaming, **not Gemini first-token latency**; no speedup is claimed.
- **CI blocker:** GitHub Actions has refused to assign runners due to the
  account billing issue. This is not evidence that tests ran or failed.
  Full Maven/frontend suites remain outstanding. See [M1.5 completion review](m1.5-closure.md) for exact scope and verification debt.

M1.5 Java loader isolated smoke check (2026-11-04): Java 21 compilation and seven assertions passed on a recreated copy of the fetched `AgentInstructions.java` and `SYSTEM.md` source. This **does not** exercise the Maven/JUnit integration, the production classpath, or the frontend. Do not mark full CI or release gates green based on this result.

M1.5 context-window isolated smoke check (2026-11-04): Java 21 compiled a recreated copy of the fetched `ContextWindow.java` plus a minimal `ChatProvider` type stub; **six assertions passed** (message budget, selection order, user role, oversized request rejection and interrupted-text exclusion). This is **not** repository Maven/JUnit execution.

## M2 PR #3 under review — research and implementation status (2026-11-04)

- **Research completed:** compared Agent Beacon, Mem0, Letta, Muse, OpenClaw
  and OWASP memory/prompt-injection boundaries. The adopted first slice is
  native JDBC/Flyway memory with explicit user saves and bounded retrieval;
  [decision record](m2-research.md).
- **Implemented on PR branch, pending review:** `V3__personal_memories.sql`,
  `MemoryController`, `MemoryService`, `MemoryRepository`,
  `MemorySelector`, chat context wiring, and a React Memories manager.
  Post-response automatic memory extraction is now the default; a bounded
  model output can propose direct facts or reviewable inferences, but the
  assistant still cannot buy things
  or access external services.
- **Tests authored, not yet run:** unit selection/service tests, explicit
  trust-boundary tests, real-DB CRUD and cross-owner isolation tests,
  React memory manager and API parsing checks.
- **Known gaps:** no signed-in accounts/cross-device sync, no automatic
  cookie-expiry cleanup, no semantic memory or cross-turn inference,
  incomplete deletion coverage for upstream provider/backups, no verified
  multi-database migration, full UI or latency benchmarks.
- **GitHub CI:** account billing prevents runner allocation. Do not
  treat the workflow status as compiled/tested evidence.
- **M2 is not certified complete.** This is an unmerged PR for review and further
  verification, not a release or merge instruction.

M2 PR #3 review follow-up: memory context length and its single additional message now count against the original 16,000-character/20-message chat budget. This is a static source review fix, **not a verified test run**.

M2 automatic-learning change (2026-11-05): default-on queued post-reply
Gemini extractor, evidence/confidence validation, suggested inference review,
and revision-based stale-write protection are implemented on the PR branch.
Parser, learning service, memory-state and frontend default/review tests were
authored. **Neither full Maven nor frontend tests have been executed in this
session**. The provider's reported confidence has not been empirically
calibrated, and no live memory-learning benchmark has been run.
