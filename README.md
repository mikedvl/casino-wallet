# Casino Wallet

A small casino wallet assignment built with Kotlin, Spring Boot, PostgreSQL and Angular 17.

The project is implemented in small, reviewable vertical slices with a green build required at the end of every stage.

> **Current status:** Stage 2 — Wallet Read Vertical Slice.
>
> Flyway creates and seeds the wallet. Spring JDBC serves its authoritative balances through `GET /api/wallet`, and Angular displays them in EUR.
>
> Deposits, callbacks, ledger, bonus lifecycle, rounds, wallet mutations and translations are not implemented yet.

See:

- [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) — staged implementation roadmap
- [NOTES.md](NOTES.md) — assumptions and architectural decisions

---

# Architecture

```mermaid
flowchart LR
    Browser --> Frontend[Angular 17 / Nginx]
    Frontend -->|/api| Backend[Kotlin / Spring Boot]
    Backend --> PostgreSQL[(PostgreSQL 16)]
```

The production/demo runtime consists of three separate services:

```text
Browser
   |
   v
frontend
Angular 17 + Nginx
   |
   | /api
   v
backend
Kotlin + Spring Boot
   |
   | JDBC
   v
postgres
PostgreSQL 16
```

Responsibilities:

```text
Frontend
  - serves Angular SPA
  - proxies /api
  - exposes /health
  - propagates X-Request-ID
  - writes access/error logs

Backend
  - REST API
  - business rules
  - transaction boundaries
  - observability
  - future wallet locking and financial consistency

PostgreSQL
  - durable application state
  - source of truth for wallet balances
  - ACID transactions
  - database constraints
  - row locking
```

---

# Wallet Read API

`GET /api/wallet` reads the single demo player's wallet. It requires no authentication, query parameters or player ID in the URL.

```json
{
  "realBalance": "0.00",
  "bonusBalance": "0.00"
}
```

Both values are decimal strings with exactly two fractional digits. Angular preserves those strings and displays `Real balance` and `Bonus balance` in EUR, with loading and retry states when the backend is not yet available.

Flyway runs on backend startup in both DEV and DEMO. `V1__create_wallet.sql` creates `wallet` with `player_id UUID PRIMARY KEY` and two `NUMERIC(19,2) NOT NULL DEFAULT 0.00` balances, each protected by a nonnegative `CHECK`. `V2__seed_demo_wallet.sql` inserts one demo wallet for `00000000-0000-0000-0000-000000000001` with `0.00 / 0.00`. Applied migrations are recorded in `flyway_schema_history`; restarting does not reseed or reset balances.

The read path is controller → application service → Spring JDBC → PostgreSQL. No wallet writes or row locks are exposed in Stage 2.

---

# Run Modes

The project intentionally supports **two different execution modes**.

They solve different problems and should not be confused.

| Mode | PostgreSQL | Backend | Frontend | Primary purpose |
|---|---|---|---|---|
| **DEV** | Docker | Local / IntelliJ | Local Angular dev server | Fast development, debugging, hot reload |
| **DEMO** | Docker | Docker | Docker + Nginx | Reproducible reviewer / clean-clone execution |

## DEV mode

Preferred during active development:

```text
Browser
   |
   v
Angular Dev Server
localhost:4200
   |
   | /api proxy
   v
Spring Boot
localhost:8080
   |
   | JDBC
   v
PostgreSQL
Docker
```

Advantages:

- Kotlin breakpoints in IntelliJ IDEA
- TypeScript/JavaScript debugging
- Angular live reload
- Spring Boot DevTools restart
- faster feedback loop
- no Docker image rebuild after every source change

## DEMO mode

Used for reviewer verification:

```text
Browser
   |
   v
Frontend container
Angular build + Nginx
   |
   v
Backend container
Spring Boot
   |
   v
PostgreSQL container
```

The complete application starts with:

```bash
docker compose up --build
```

This mode proves that the repository can be cloned and run reproducibly without relying on the developer's IDE configuration.

---

# Repository Structure

```text
casino-wallet/
├── backend/                 Spring Boot application and backend Docker image
├── frontend/                Angular application and Nginx Docker image
├── scripts/
│   └── verify.sh            Local verification pipeline
├── compose.yaml             PostgreSQL, backend and frontend services
├── README.md
├── NOTES.md
├── IMPLEMENTATION_PLAN.md
├── AGENTS.md
├── .env.example
└── .gitignore
```

The local `docs/` directory contains private development notes and is intentionally excluded from Git.

---

# Technology Stack

| Component | Version |
|---|---|
| Java container images | `eclipse-temurin:21.0.12_8-jdk-noble` / `21.0.12_8-jre-noble` |
| Gradle Wrapper | `8.14.3` |
| Kotlin | `2.2.21` |
| Spring Boot | `3.5.16` |
| Flyway core / PostgreSQL module | `11.7.2` (Spring Boot dependency management) |
| Testcontainers | `1.21.4` |
| PostgreSQL | `postgres:16.15-alpine3.23` |
| Node builder | `node:20.20.2-alpine3.22` |
| Angular | `17.3.12` |
| Angular CLI | `17.3.17` |
| TypeScript | `5.4.5` |
| Nginx | `nginxinc/nginx-unprivileged:1.28.2-alpine` |

Top-level npm dependencies use exact versions and transitive dependencies are pinned by `package-lock.json`.

Gradle dependency versions are reproducible through the committed Gradle Wrapper and Spring Boot dependency management.

---

# Prerequisites

## DEMO mode

Required:

- Docker Engine or Docker Desktop
- Docker Compose v2
- `curl` for optional manual smoke checks

Tested with:

```text
Docker Engine: 28.0.4
Docker Compose: 2.34.0
```

No local Java or Node installation is required to run the complete containerized stack.

## DEV mode

Additionally required:

- JDK 21
- Node.js `>=20.9.0 <21`
- npm
- Bash
- Chrome or Chromium
- running Docker daemon for PostgreSQL and Testcontainers

Recommended Node version:

```text
20.20.2
```

The repository contains:

```text
frontend/.nvmrc
```

For `nvm` users:

```bash
cd frontend
nvm use
```

Verify the local environment:

```bash
java -version
node --version
npm --version
docker compose version
```

Expected major versions:

```text
Java: 21
Node: 20
Docker Compose: 2
```

Set `JAVA_HOME` to JDK 21.

If Chrome is not detected automatically by frontend tests, configure `CHROME_BIN`.

---

# DEMO Mode — Full Containerized Run

This is the recommended way for a reviewer to run the project.

## 1. Create local environment configuration

From the repository root:

```bash
cp .env.example .env
```

The values in `.env.example` are disposable local defaults, not production credentials.

The real `.env` file is ignored by Git.

## 2. Start the complete stack

```bash
docker compose up --build
```

Docker Compose starts:

```text
postgres
   ↓ healthy

backend
   ↓ ready

frontend
   ↓ healthy
```

If port `5432` is already occupied, change the published PostgreSQL port in `.env`:

```text
POSTGRES_PORT=15432
```

The internal container connection remains:

```text
postgres:5432
```

Only the host mapping changes.

## Service URLs

| Service | Default address |
|---|---|
| Frontend | http://localhost:4200 |
| Frontend health | http://localhost:4200/health |
| Backend health | http://localhost:8080/actuator/health |
| Backend liveness | http://localhost:8080/actuator/health/liveness |
| Backend readiness | http://localhost:8080/actuator/health/readiness |
| Backend info | http://localhost:8080/actuator/info |
| Backend metrics | http://localhost:8080/actuator/metrics |
| PostgreSQL | localhost:5432 |

Inspect the stack:

```bash
docker compose ps
```

Inspect logs:

```bash
docker compose logs -f
```

Stop the stack:

```bash
docker compose down
```

The PostgreSQL named volume is preserved.

Use:

```bash
docker compose down -v
```

only when the local database should intentionally be deleted.

Backend and frontend containers run as non-root users.

Runtime images contain runtime artifacts only, not project source trees or build caches.

---

# DEV Mode — Fast Local Development

DEV mode is the preferred workflow while changing application code.

The architecture is:

```text
PostgreSQL -> Docker
Backend    -> local JVM / IntelliJ IDEA
Frontend   -> local Angular dev server
Browser    -> Chrome
```

---

## 1. Start PostgreSQL

From the repository root:

```bash
docker compose up -d postgres
```

If local port `5432` is occupied:

```bash
POSTGRES_PORT=15432 docker compose up -d postgres
```

Check health:

```bash
docker compose ps postgres
```

Expected:

```text
healthy
```

---

## 2. Start backend locally

From:

```bash
cd backend
```

Default PostgreSQL port:

```bash
./gradlew bootRun
```

If PostgreSQL is published on `15432`:

```bash
DB_PORT=15432 ./gradlew bootRun
```

Backend URL:

```text
http://localhost:8080
```

Health checks:

```bash
curl http://localhost:8080/actuator/health/liveness
curl http://localhost:8080/actuator/health/readiness
```

Expected:

```json
{"status":"UP"}
```

Spring Boot DevTools supports application restart after compiled classes change.

---

## 3. Start frontend locally

In another terminal:

```bash
cd frontend
nvm use
npm start
```

or without `nvm`:

```bash
cd frontend
npm start
```

Frontend URL:

```text
http://localhost:4200
```

Angular development server provides live reload.

---

# Local API Proxy

Browser code always uses relative API URLs:

```text
/api/*
```

In DEV mode:

```text
Browser
   ↓
Angular Dev Server :4200
   ↓
proxy.conf.json
   ↓
Spring Boot :8080
```

The proxy target is:

```text
http://localhost:8080
```

This avoids development-only CORS configuration.

In DEMO mode:

```text
Browser
   ↓
Nginx
   ↓
/api
   ↓
backend:8080
```

`GET /api/wallet` returns HTTP `200` with the wallet JSON through either proxy. Unimplemented routes still return `404`.

---

# IntelliJ IDEA Development Workflow

Seven shared profiles are stored in `.run/` and appear in **Run / Debug Configurations** when the repository root is opened in IntelliJ IDEA.

## One-time IDE setup

- Link `backend/build.gradle.kts` as a Gradle project and let the import finish. The backend profile uses the imported `com.example.casino-wallet.main` module.
- Select JDK 21 as the Project SDK and Gradle JVM.
- Select Node 20 as the project Node runtime and its npm as the project package manager. `frontend/.nvmrc` remains the CLI version reference. Run `npm ci` in `frontend/` once before starting the dev server or frontend tests.
- Configure a local Docker connection named `Docker` in **Settings → Build, Execution, Deployment → Docker**, and start the Docker daemon.
- Configure the installed Chrome executable in **Settings → Tools → Web Browsers and Preview**. IntelliJ must have its Spring Boot, Docker, npm and JavaScript debugging support available.

JDK, Node, Docker socket and browser executable locations are machine-local IDE settings. Shared profiles contain project-relative paths and no credentials.

| Profile | Native type | Behaviour |
| --- | --- | --- |
| `01 - PostgreSQL` | Docker Compose | Starts only `postgres` in detached mode with `POSTGRES_PORT=15432`. |
| `02 - Backend` | Spring Boot | Starts PostgreSQL as a Before Launch task, then the local JVM with `DB_HOST=localhost` and `DB_PORT=15432`. |
| `03 - Frontend` | npm | Runs `npm run start` from `frontend/package.json`, opens `http://localhost:4200` in Chrome and starts the browser JavaScript debugger. |
| `DEV - Full Stack` | Compound | Starts `02 - Backend` and `03 - Frontend` together. PostgreSQL is supplied by the backend prerequisite. |
| `DEMO - Full Stack` | Docker Compose | Builds and starts `postgres`, `backend` and `frontend` from the existing `compose.yaml`, equivalent to `docker compose up --build`. |
| `TEST - Backend` | Gradle | Runs `test` in `backend/` with the project Gradle JVM. Testcontainers supplies isolated PostgreSQL containers. |
| `TEST - Frontend` | npm | Runs `npm test` from `frontend/package.json`: Angular unit/component tests with HTTP mocks and ChromeHeadless. |

## TEST: isolated test runs

**TEST - Backend → Run** executes all backend tests. **Debug** attaches to the test JVM and supports breakpoints in Kotlin tests and application code. The profile enables **Run as test**, so each launch reruns the tests even when Gradle considers them up to date. Gradle script debugging is disabled. A running Docker daemon is required; there is no Before Launch dependency on `01 - PostgreSQL` or the shared database on port `15432`.

**TEST - Frontend → Run** uses the existing `test` script, which already specifies `--watch=false --browsers=ChromeHeadless`. Install Chrome locally; for a nonstandard browser location, supply `CHROME_BIN` in the local launch environment. The profile uses the project Node 20 runtime and has no backend, PostgreSQL or Docker prerequisite.

For a single Kotlin test, use its gutter **Debug** action. For TypeScript test breakpoints, use the test's gutter **Debug** action with IntelliJ's Karma integration; debugging the npm process alone does not attach to browser tests. The [Karma plugin](https://www.jetbrains.com/help/idea/running-unit-tests-on-karma.html) must be installed and enabled for this workflow.

## DEV: Run, Debug and reload

Select **DEV - Full Stack → Run** for local development, or **Debug** for Kotlin and TypeScript breakpoints. The backend runs on JDK 21 and the frontend uses the project Node runtime. Browser JavaScript debugging is enabled for both actions.

The IDEA DEV database mapping is always `127.0.0.1:15432 → postgres:5432`; port `5432` inside the container is unchanged. Existing development database defaults are reused.

The frontend and backend can start in parallel, so the page may become available before backend readiness turns `UP`. Angular keeps the existing `/api` proxy to `http://localhost:8080`; use **Retry** if the initial wallet request arrives before the backend is ready.

Angular watches source files and reloads the browser. Spring Boot DevTools restarts the backend when compiled classes or resources change; use **Build Project** after backend edits. Kotlin breakpoints work directly in backend sources, and browser source maps support TypeScript breakpoints. If a startup breakpoint was passed before the browser debugger attached, reload the page.

Use IntelliJ's **Stop** menu to stop the DEV child sessions together (or **Stop All** when only this stack is running). The detached PostgreSQL service remains available. Stop it separately in Docker Services or with `docker compose stop postgres`; its named volume is preserved. Close the debug Chrome window before a fresh launch if IDEA reports that its browser profile is already in use.

## DEMO and CLI

Stop the local DEV processes before launching DEMO because both modes use host ports `8080` and `4200`. DEMO builds both application images and retains the existing Compose healthchecks and startup dependencies. Stop the stack through Docker Services or `docker compose stop`; do not select volume removal.

DEMO uses normal Compose environment settings: PostgreSQL defaults to host port `5432`. If that port is occupied, set `POSTGRES_PORT=15432` in the ignored root `.env`, as described in the Docker workflow above.

All CLI workflows remain independent of IntelliJ, including `docker compose up --build`, `./gradlew bootRun`, `npm start` and `./scripts/verify.sh`.

---

# DEV vs DEMO

Use **DEV mode** when:

- writing backend code;
- debugging Kotlin;
- writing frontend code;
- debugging TypeScript;
- using hot reload;
- iterating quickly.

```text
PostgreSQL Docker
+
local Spring Boot
+
local Angular
```

Use **DEMO mode** when:

- validating the delivered application;
- reproducing reviewer setup;
- testing Docker images;
- testing Nginx;
- verifying service startup ordering;
- performing a clean-clone smoke test.

```text
PostgreSQL Docker
+
backend Docker
+
frontend Docker
```

Do not use full Docker image rebuilds as the normal source-code development loop.

---

# Configuration

Local backend defaults match `.env.example`.

Supported backend environment variables:

```text
DB_HOST
DB_PORT
DB_NAME
DB_USER
DB_PASSWORD
SERVER_PORT
```

Example:

```bash
export DB_HOST=localhost
export DB_PORT=15432

cd backend
./gradlew bootRun
```

Spring Boot does not automatically load the repository root `.env` file during direct local execution.

Docker Compose maps its PostgreSQL configuration into the backend container automatically.

---

# Verification

Run the full local verification pipeline from the repository root:

```bash
./scripts/verify.sh
```

The script works from any current directory.

It performs:

```text
Backend
  -> Gradle check
  -> backend tests
  -> executable bootJar

Frontend
  -> npm ci
  -> headless Karma/Jasmine tests
  -> Angular production build

Infrastructure
  -> Docker Compose configuration validation
```

Backend verification uses:

```bash
./gradlew --no-daemon check bootJar
```

Tests requiring PostgreSQL use a real PostgreSQL Testcontainer.

H2 is not used.

Required checks are never silently skipped.

A successful verification means:

```text
source code compiles
+
tests pass
+
production artifacts build
+
Compose configuration is valid
```

It does not automatically start the long-lived full stack.

---

# Full-Stack Smoke Test

The complete Docker stack is verified separately.

```bash
docker compose config --quiet

docker compose build

docker compose up -d --wait --wait-timeout 180

docker compose ps

curl --fail \
  http://localhost:8080/actuator/health/liveness

curl --fail \
  http://localhost:8080/actuator/health/readiness

curl --fail \
  http://localhost:8080/actuator/metrics

curl --fail -i \
  -H 'X-Request-ID: reviewer-check' \
  http://localhost:8080/api/wallet

curl --fail -i \
  -H 'X-Request-ID: reviewer-proxy-check' \
  http://localhost:4200/api/wallet

curl --fail \
  http://localhost:4200/health

curl --fail \
  http://localhost:4200/

docker compose exec frontend \
  wget -qO- \
  http://backend:8080/actuator/health/readiness

docker compose logs backend frontend

docker compose down
```

Expected:

```text
postgres  -> healthy
backend   -> healthy
frontend  -> healthy
```

Backend readiness verifies real PostgreSQL connectivity.

Both wallet requests must return HTTP `200`, decimal strings `0.00 / 0.00` for the seeded wallet, and the supplied `X-Request-ID`. Backend startup logs show the Flyway migration result; on an empty database both migrations are applied.

Liveness remains independent from PostgreSQL so the JVM process can remain alive and recover from a temporary database outage.

---

# Manual DEV Smoke Test

For a quick manual verification of the local development workflow:

## PostgreSQL

```bash
POSTGRES_PORT=15432 docker compose up -d postgres
```

Verify:

```bash
docker compose ps postgres
```

Expected:

```text
healthy
```

## Backend

```bash
cd backend
DB_PORT=15432 ./gradlew bootRun
```

Verify:

```bash
curl -s \
  http://localhost:8080/actuator/health/liveness

curl -s \
  http://localhost:8080/actuator/health/readiness
```

Expected:

```json
{"status":"UP"}
```

## Frontend

```bash
cd frontend
npm start
```

Open:

```text
http://localhost:4200
```

The page loads the wallet from `/api/wallet` and displays:

```text
Casino Wallet

Real balance
0.00 EUR

Bonus balance
0.00 EUR
```

## Proxy verification

```bash
curl -i \
  -H 'X-Request-ID: manual-proxy-test' \
  http://localhost:4200/api/wallet
```

Expected:

```text
HTTP 200
X-Request-ID: manual-proxy-test

{"realBalance":"0.00","bonusBalance":"0.00"}
```

This confirms the wallet read path through:

```text
Angular development server
       ↓
proxy.conf.json
       ↓
Spring Boot
       ↓
PostgreSQL
```

---

# Reliability Strategy

Stage 2 implements wallet reads only. Balance-changing functionality belongs to later stages.

Later financial stages follow these rules:

- PostgreSQL is the only durable transactional system.
- Each balance-changing use case executes in one application-service transaction.
- Transaction isolation is `READ_COMMITTED`.
- Wallet-changing operations use `SELECT ... FOR UPDATE`.
- Global lock order is wallet first, then the related deposit when required.
- Wallet changes, ledger entries and related domain state commit or roll back together.
- `REQUIRES_NEW` is not used for financial sub-operations.
- External network calls are not performed while a financial transaction is open.
- Financial success is returned only after the database transaction commits.

Money uses:

```text
Kotlin      -> BigDecimal
PostgreSQL  -> NUMERIC(19,2)
```

Binary floating-point values are not used for authoritative money calculations.

Database constraints provide a second protection layer through:

```text
NOT NULL
CHECK
UNIQUE
FOREIGN KEY
append-only protections
```

The wallet already has a primary key, required balances and nonnegative checks. Ledger protections, related financial tables and wallet write locks belong to later stages.

---

# Consistency Strategy

Financial state uses strong consistency.

PostgreSQL is the single source of truth for real and bonus balances. Later stages will also store:

- wagering progress;
- deposit state;
- durable callback idempotency.

The `wallet` table contains the current real and bonus balances. Stage 2 exposes only reads.

The ledger will be append-only history written in the same transaction as each balance change.

Bonus metadata will not duplicate the mutable current bonus balance.

The frontend treats backend responses as authoritative and does not perform authoritative financial calculations.

Redis, local caches and TTL-based keys are intentionally excluded from financial consistency and idempotency.

---

# Observability

The project provides a lightweight observability baseline without deploying a separate monitoring platform.

## Backend

Spring Boot Actuator exposes:

```text
/actuator/health
/actuator/health/liveness
/actuator/health/readiness
/actuator/info
/actuator/metrics
```

Micrometer provides built-in JVM, HTTP and datasource metrics.

Sensitive Actuator endpoints and sensitive health details are not exposed.

---

## Request Correlation

The backend supports:

```text
X-Request-ID
```

Accepted caller IDs match:

```text
[A-Za-z0-9._-]{1,128}
```

Otherwise a new UUID is generated.

The effective request ID:

- is returned in the response;
- is stored in MDC during request processing;
- is removed from MDC in `finally`;
- appears in request-completion logs.

Completion logs contain:

```text
request_id
method
path
status
duration
```

The application does not log:

- request bodies;
- query strings;
- credentials;
- secrets;
- signatures;
- authorization values.

---

## Nginx

Nginx:

- exposes `/health`;
- propagates `X-Request-ID`;
- writes access logs to stdout;
- writes error logs to stderr;
- records status;
- records request duration;
- records upstream duration.

---

## Docker Compose Healthchecks

```text
PostgreSQL -> pg_isready
Backend    -> Actuator readiness
Frontend   -> /health
```

Operational inspection:

```bash
docker compose ps
docker compose logs -f
```

---

# Angular 17 Security Constraint

Angular 17 is explicitly required by the assignment and is therefore pinned to:

```text
17.3.12
```

Angular 17 is no longer within the upstream Angular support window.

At bootstrap review:

```bash
npm audit --omit=dev
```

reports:

```text
5 vulnerabilities
2 moderate
3 high
```

in Angular 17 runtime packages.

A complete:

```bash
npm audit
```

also reports findings in Angular build/test dependencies, including deprecated transitive packages.

Automated remediation proposes upgrading Angular to a newer major version, which would violate the explicit Angular 17 requirement.

Therefore the project intentionally does not run:

```bash
npm audit fix --force
```

and does not introduce dependency overrides that create an unsupported mixed Angular dependency graph.

The application remains a simple client-side SPA and does not introduce:

- Angular SSR;
- hydration;
- runtime template compilation;
- dynamic HTML rendering;
- unnecessary third-party UI frameworks.

For a real production deployment, Angular should be upgraded to a currently supported version before release.

References:

- https://angular.dev/reference/releases
- https://angular.dev/reference/versions

A green build does not imply a clean dependency-security audit.

Known dependency risks are documented rather than hidden.

---

# Intentional Exclusions

The following components are intentionally outside this assignment:

- Redis;
- Kafka;
- API Gateway;
- Kubernetes;
- Prometheus server;
- Grafana;
- Elasticsearch;
- Logstash;
- Kibana;
- WatchDog;
- distributed tracing infrastructure;
- production authentication;
- speculative business dashboards.

They are not required for ACID guarantees or correctness of this application.

Possible production extensions include:

- centralized metrics;
- centralized logs;
- distributed tracing;
- managed PostgreSQL;
- authentication;
- secrets management;
- production backup and replication.

They should be introduced only when corresponding operational requirements exist.

---

# Development Approach

Implementation proceeds through small vertical slices.

Every stage must:

1. have a clearly bounded scope;
2. produce a reviewable diff;
3. include relevant tests;
4. finish with a green build;
5. preserve the DEV workflow;
6. preserve the DEMO Docker Compose workflow;
7. keep documentation aligned with actual behaviour;
8. stop before the next stage until reviewed.

The detailed roadmap is maintained in:

[IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)
