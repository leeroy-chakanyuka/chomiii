# Security policy

## Report a vulnerability

Do **not** open a public GitHub issue, discussion, or pull request containing suspected or confirmed vulnerability details.

Preferred: use GitHub's **private vulnerability reporting** on this repository (Security tab → "Report a vulnerability"), which notifies maintainers privately.

Include what you can of: the affected component (backend, frontend, documentation), affected version or commit, a minimal reproduction, impact, and any fix you would suggest.

Please give maintainers a reasonable window to respond and ship a fix before any public disclosure. We will credit reporters who wish to be credited.

## Supported versions

Chomiii has no released versions yet (backend `0.1.0-SNAPSHOT`, frontend `0.1.0`, milestone M0). Only the latest state of the repository is effectively supported. There is no CI or hosted deployment yet; fixes are applied to the repository source.

## Security expectations for contributors

The [engineering contract](AGENTS.md) applies to every change:

- **Never commit** credentials, API keys, tokens, personal data, or production environment files. [`.gitignore`](.gitignore) excludes `.env*` except the placeholder-only `.env.example`; keep it that way. `.env.example` documents the contract; nothing loads it automatically.
- **Do not log secrets.** Log statements must never include credentials or tokens. The exception handler deliberately exposes no internal details in error responses while logging the full cause server-side.
- **Treat external data and tool results as untrusted**, especially once agents, memory, and file access arrive in later milestones.
- **Least privilege** for every credential, and appropriate authorization for consequential external actions.

## Database credentials

- Local development runs the default Spring profile `local` on MySQL; only the URL is defaulted (`jdbc:mysql://localhost:3306/chomiii` in [`backend/src/main/resources/application-local.yml`](backend/src/main/resources/application-local.yml)) and it is overridden with `CHOMIII_DB_URL` if needed. Credentials come from `CHOMIII_DB_USER` / `CHOMIII_DB_PASSWORD` environment variables. Keep these credentials out of any committed file and out of logs and diagnostics.
- Production runs the Spring profile **`prod`** against PostgreSQL via [`backend/src/main/resources/application-prod.yml`](backend/src/main/resources/application-prod.yml), which requires all three `CHOMIII_DB_*` variables from the environment with no defaults; the URL has the form `jdbc:postgresql://HOST:5432/DATABASE` and comes from the deployment's secret store. Never place real values in `.env.example` or any tracked file.
- Migration SQL and integration tests are also expected to work against both engines; fixtures and CI containers must remain credential-free — test databases are throwaway instances configured via environment, never real production credentials.

## Known limitations (M0)

Recorded here honestly, not as an invitation to exploit anywhere other than your own machine:

- The backend has **no authentication or authorization**; every endpoint is currently open and intended for local operation only.
- CORS is not configured; the dev server uses a same-origin proxy instead.
- `spring-boot-starter-jdbc` and plain SQL are in use; data models are minimal and the test-first convention extends to permission checks when they arrive.
- Database credentials are read from environment variables only (`CHOMIII_DB_URL`, `CHOMIII_DB_USER`, `CHOMIII_DB_PASSWORD`, plus `SPRING_PROFILES_ACTIVE=prod` in production); keep them out of logs and diagnostics.
- Both database engines sit behind the same trust boundary today; production hardening (network isolation, TLS, least-privilege database roles for MySQL and PostgreSQL alike) belongs to the M7 consumer-maturity work (auth, ops, privacy).
