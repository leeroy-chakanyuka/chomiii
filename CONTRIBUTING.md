# Contributing to chomiii

Thanks for contributing. Chomiii exists both to learn agentic engineering and to build capable, reliable agents through disciplined software engineering — these rules exist so both goals stay intact. The engineering contract in [AGENTS.md](AGENTS.md) is authoritative; this file explains how to follow it in practice.

## Prerequisites

See the [README](README.md) for local prerequisites and setup. In short:

- JDK 21, Git, Node.js + npm (LTS).
- A reachable **local MySQL** instance with the `chomiii` schema and a dedicated user whose credentials you set as environment variables (`CHOMIII_DB_USER`, `CHOMIII_DB_PASSWORD`; the URL defaults to `jdbc:mysql://localhost:3306/chomiii` in [`backend/src/main/resources/application-local.yml`](backend/src/main/resources/application-local.yml)).
- **Docker is not a local prerequisite.** Local work never needs it. The default `./mvnw verify` (PostgreSQL Testcontainers) is the CI-shaped check and does need Docker; contributors without Docker use the local MySQL route and rely on CI for the PostgreSQL path.

## Ground rules

- **Test-first development.** Specify observable behavior with a failing test, implement the smallest correct change, refactor, rerun focused tests plus relevant regression checks. The commands are in [docs/testing.md](docs/testing.md).
- **Conventional Spring MVC on the backend.** Feature-oriented packages (`com.chomiii.<feature>`), constructor injection, business logic outside controllers. Keep backend business logic out of frontend components.
- **Versioned Flyway migrations that work on both engines.** MySQL is the local database; PostgreSQL is the CI and production database. New migrations are `V<n>__<description>.sql` in `backend/src/main/resources/db/migration`, must be **vendor-compatible** (or shipped as vendor variants under database-specific Flyway locations such as `db/migration/{vendor}`), and are verified against both engines — never hand-edit a schema outside migrations, and never "fix it for one engine only".
- **Clarity over cleverness.** Small dependencies, straightforward code familiar to Java developers, no speculative abstractions, no redundant comments — explain non-obvious reasoning instead.
- **Independent review.** Meaningful implementation work gets reviewed by someone (or some agent) other than the author before merge.

## Architectural decisions

Explain consequential architecture choices and their trade-offs in [docs/architecture.md](docs/architecture.md) when they are made.

## Before you open a change

Run the relevant checks and record what you actually ran — [docs/verification.md](docs/verification.md) is the project's evidence log, and it must never contain invented results:

- Backend focused tests (no database, always safe): `cd backend; .\mvnw.cmd test`
- Backend local integration against **real MySQL** (Failsafe `*LocalIT` suite; needs an isolated database reached purely via `CHOMIII_DB_URL` / `CHOMIII_DB_USER` / `CHOMIII_DB_PASSWORD` env vars or `-DCHOMIII_DB_*` properties; no Docker): `cd backend; .\mvnw.cmd -Plocal-mysql verify`
- Backend integration against **PostgreSQL** via Testcontainers (needs Docker): `cd backend; ./mvnw verify` — leave this to CI if you do not run Docker locally; the CI workflow covers it.
- Frontend full suite (backend optional unless you run the integration suite): `cd frontend; npm run lint; npm run typecheck; npm run test; npm run build; npm run test:browser` and, with the backend running, `npm run test:browser:integration`

Any change that touches persistence needs at least one database-engine verification path (local MySQL and, via the CI workflow in [.github/workflows/ci.yml](.github/workflows/ci.yml), PostgreSQL). Do not merge persistence changes that have only been run without a real database.

Keep the README, roadmap, and verification evidence accurate. Distinguish implemented behavior from plans; never invent test results or paste raw confidential output.

## Commit style

Use meaningful, small commits describing the change, not the activity ("Add health endpoint MVC coverage", not "update files"). Do not rewrite public history without authorization.

## Submitting changes

1. Start from the current default branch of the project's hosted repository (kept in sync by the maintainer).
2. Follow the test-first workflow above; include the tests that justify the change.
3. Update the architecture documentation if the change makes a consequential architectural decision.
4. Run the relevant checks and mention their concrete outcome in your change description.
5. Keep the change scoped: only the files your change needs.

## Security

Never commit credentials, API keys, tokens, personal data, or production environment files; [`.gitignore`](.gitignore) excludes `.env*` beyond `.env.example`, and `.env.example` itself carries placeholders only that nothing loads automatically. Do not log secrets; treat external data and tool results as untrusted. Vulnerabilities are handled privately — see [SECURITY.md](SECURITY.md).
