# Project

Casino Wallet assignment:

- Kotlin + Spring Boot backend
- Angular 17 frontend
- PostgreSQL 16
- Docker Compose
- modular-monolith architecture

## Scope and language

- Use English in all tracked source files, comments and documentation.
- Treat `IMPLEMENTATION_PLAN.md` as the tracked implementation roadmap.
- Implement only the explicitly approved stage.
- Never start the next stage automatically.
- Prefer a small, reviewable diff over a large multi-stage change.
- Never modify, copy, summarize or publish the ignored `docs/` directory.
- Do not create placeholder classes, empty future packages or speculative abstractions.

## Technology constraints

- Use Java 21.
- Use Angular 17; do not upgrade to another Angular major version.
- Use Node 20 for frontend development and builds.
- Use PostgreSQL 16.
- Backend is one Gradle project using Gradle Kotlin DSL.
- Keep dependency versions reproducible and avoid dynamic versions.
- Do not run `npm audit fix --force`.
- Do not introduce dependency overrides solely to hide Angular 17 audit findings.
- Add dependencies only when required by the current stage.

## Architecture

- Keep the backend a modular monolith.
- Preserve separation between application, domain, persistence and web concerns.
- Controllers must not contain business logic or own transaction boundaries.
- Application services own financial transaction boundaries.
- PostgreSQL is the only source of truth for financial state.
- Do not use Redis, local caches or TTL keys to guarantee balances, wagering state or payment idempotency.
- Do not introduce microservices, Kafka, API Gateway or additional infrastructure without an explicit requirement.

## Financial consistency

For financial stages:

- Use Kotlin `BigDecimal` and PostgreSQL `NUMERIC(19,2)` for money.
- Never use binary floating-point values for authoritative money calculations.
- Financial writes use one application-service `READ_COMMITTED` transaction.
- Wallet changes, ledger entries and related deposit, bonus or round state must commit or roll back together.
- Use PostgreSQL `SELECT ... FOR UPDATE` for wallet-changing operations.
- Preserve the global lock order:

  `wallet -> related deposit, when required`

- Do not use independent `REQUIRES_NEW` transactions for financial sub-operations.
- Do not perform external network calls while a financial database transaction is open.
- Return financial success only after the transaction commits.
- Use database constraints as a second line of defence.
- Keep the ledger append-only.
- Do not create zero-value ledger entries.
- Do not duplicate the mutable bonus balance outside the wallet.

## Backend

- Prefer explicit Spring JDBC persistence for financial operations.
- Keep SQL, transaction behaviour and row locking explicit.
- Do not introduce JPA/Hibernate unless the architecture is explicitly reconsidered.
- Use real PostgreSQL for persistence and concurrency integration tests.
- Do not use H2 as a substitute for PostgreSQL behaviour.
- Use Testcontainers for database integration and concurrency tests.
- Keep infrastructure and business tests deterministic.

## Frontend

- Keep Angular exactly on version 17.
- Use standalone components.
- Use relative `/api` URLs.
- Local development uses the Angular proxy; production uses Nginx proxying.
- Do not add unnecessary state-management or UI frameworks.
- Treat backend responses as authoritative for financial state.
- Do not perform authoritative money calculations with JavaScript `number`.
- Keep all visible committed application text in English until the localization stage.
- Final localization uses `en` and `uk`.

## Docker and runtime

The runtime architecture contains exactly three application services unless explicitly changed:

- `postgres`
- `backend`
- `frontend`

Guidelines:

- Keep PostgreSQL, backend and frontend independently containerized.
- Frontend production runtime uses Nginx.
- Containers must run as non-root where practical.
- Do not bake secrets into images.
- Use explicit image versions; never use `latest`.
- Keep the full stack runnable with:

  `docker compose up --build`

- Prefer PostgreSQL in Docker with backend/frontend running locally during active development.
- Preserve the PostgreSQL named volume unless data deletion is intentional.

## Observability

- Propagate `X-Request-ID` across Nginx and backend.
- Clear request-scoped MDC in `finally`.
- Write application logs to stdout/stderr.
- Never log:
    - credentials
    - passwords
    - HMAC secrets
    - signatures
    - authorization headers
    - raw callback bodies
    - sensitive payloads
- Keep liveness independent from PostgreSQL.
- Keep readiness dependent on required infrastructure such as PostgreSQL.
- Add logs and metrics only for behaviour that exists.
- Do not add speculative dashboards or business metrics.
- Do not introduce Prometheus, Grafana, ELK, WatchDog or distributed tracing unless explicitly approved.

## Testing

- Add tests together with the behaviour they protect.
- Prefer pure unit tests for deterministic money calculations.
- Use PostgreSQL Testcontainers for persistence, constraints, locking and concurrency.
- Concurrency tests must prove actual database lock contention, not merely start two threads at roughly the same time.
- Keep tests deterministic; avoid random outcomes in financial acceptance scenarios.
- Every implemented stage must finish with relevant green tests and builds.

## Verification

Before declaring a stage complete:

1. Run relevant tests.
2. Run relevant production builds.
3. Run `./scripts/verify.sh`.
4. Run the Docker Compose smoke test when infrastructure or runtime behaviour changed.
5. Verify applicable healthchecks.
6. Review dependency changes.
7. Review generated artifacts.
8. Review the complete diff for unrelated changes.
9. Report any warnings, known limitations or deviations instead of hiding them.

A green build does not imply a clean security audit. Known dependency findings must be documented rather than suppressed.

## Git safety

The user manages Git history and the index.

Agents must not run:

- `git add`
- `git commit`
- `git push`
- `git reset`
- `git clean`
- `git checkout`
- `git restore`
- `git rebase`
- `git stash`

Do not initialize nested Git repositories.

IDE settings may automatically mark newly created files as added. Do not alter the index to compensate.

Read-only Git commands are allowed, including:

- `git status --short`
- `git diff`
- `git diff --stat`
- `git diff --check`

## Completion report

At the end of an approved stage, report:

- files created or changed;
- important architectural decisions;
- dependencies added or changed;
- commands executed;
- test and build results;
- Docker/healthcheck results when applicable;
- warnings or known limitations;
- any deviation from the approved scope.

Then stop and wait for review.

## Definition of Done

A stage is complete only when:

- its approved scope is implemented;
- relevant tests pass;
- relevant builds pass;
- applicable smoke tests pass;
- documentation matches actual behaviour;
- no unrelated changes are present;
- no next-stage functionality was implemented prematurely.