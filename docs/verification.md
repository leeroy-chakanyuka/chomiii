# Verification evidence

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
