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
| PostgreSQL Testcontainers suite: `./mvnw verify` | 🔶 **BLOCKED locally** | Needs Docker (absent); this is the CI-shaped check — to be proven in CI or on a Docker-capable machine |

MySQL has been verified locally. PostgreSQL still needs a successful CI run.

## Frontend

| Check | Status | Evidence |
| --- | --- | --- |
| `npm run test` (Vitest unit) | ✅ Observed | **7/7 tests passed** |
| `npm run lint` | ✅ Observed | Passed |
| `npm run typecheck` | ✅ Observed | Passed |
| `npm run build` | ✅ Observed | Passed |
| `npm run test:browser` (standalone Playwright) | ✅ Observed | **4 tests passed** |
| `npm run test:browser:integration` | ✅ PASS | **1 browser test passed** against the real Java backend connected to the temporary MySQL database |
| `npm run ci` (full battery) | 🔶 **NOT RUN** | Depends on the integration suite |

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
| Push to any remote | ❌ Never performed |
| CI pipeline (PostgreSQL Testcontainers, MySQL integration path, browser E2E) | ❌ **Not started** |
| Tagged release | ❌ None |

## Honest summary

Green and observed: backend unit/MVC tests, MySQL integration and Flyway migration, the real browser-to-backend path, frontend checks, and the `npm audit` high-severity gate. PostgreSQL, backend vulnerability scanning, and hosted CI remain unverified.
