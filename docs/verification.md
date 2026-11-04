# Verification evidence

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

The M0 sections following the M1 evidence describe earlier verification and
are not evidence for M1.

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
