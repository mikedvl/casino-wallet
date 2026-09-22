# Architecture and Implementation Roadmap

## Runtime Architecture

The application uses three runtime services connected through the default Docker Compose network:

```text
┌──────────────────────────────────────────────────────────────┐
│                         USER / BROWSER                       │
└──────────────────────────────┬───────────────────────────────┘
                               │
                               │ HTTP
                               │ http://localhost:${FRONTEND_PORT}
                               ▼
┌──────────────────────────────────────────────────────────────┐
│                    FRONTEND CONTAINER                        │
│                                                              │
│  Angular 17 production bundle                                │
│  Nginx                                                       │
│                                                              │
│  Responsibilities                                            │
│  • serve the Angular SPA                                     │
│  • SPA fallback to index.html                                │
│  • proxy /api requests to backend:8080                       │
│  • expose /health                                            │
│  • propagate X-Request-ID                                    │
│  • write access logs to stdout                               │
│  • write error logs to stderr                                │
└──────────────────────────────┬───────────────────────────────┘
                               │
                               │ /api
                               │ Docker Compose internal network
                               ▼
┌──────────────────────────────────────────────────────────────┐
│                     BACKEND CONTAINER                        │
│                                                              │
│  Kotlin + Spring Boot                                        │
│  Modular Monolith                                            │
│                                                              │
│  Planned modules                                             │
│  • application                                               │
│  • wallet                                                    │
│  • deposit                                                   │
│  • bonus                                                     │
│  • round                                                     │
│  • config                                                    │
│  • observability                                             │
│                                                              │
│  Responsibilities                                            │
│  • REST API                                                  │
│  • business rules                                            │
│  • transaction boundaries                                    │
│  • wallet row locking                                        │
│  • HMAC validation                                           │
│  • append-only ledger                                        │
│  • request correlation through X-Request-ID                  │
│  • structured logs to stdout/stderr                          │
│                                                              │
│  Infrastructure endpoints                                    │
│  • /actuator/health/liveness                                 │
│  • /actuator/health/readiness                                │
│  • /actuator/info                                            │
│  • /actuator/metrics                                         │
└──────────────────────────────┬───────────────────────────────┘
                               │
                               │ JDBC
                               │ jdbc:postgresql://postgres:5432/...
                               ▼
┌──────────────────────────────────────────────────────────────┐
│                    POSTGRESQL CONTAINER                      │
│                                                              │
│  PostgreSQL 16                                               │
│                                                              │
│  Responsibilities                                            │
│  • single source of financial truth                          │
│  • ACID transactions                                         │
│  • SELECT ... FOR UPDATE row locking                         │
│  • CHECK / UNIQUE / FOREIGN KEY constraints                  │
│  • durable callback idempotency                              │
│  • append-only ledger storage                                │
│                                                              │
│  Healthcheck                                                 │
│  • pg_isready                                                │
│                                                              │
│  Persistence                                                 │
│  • Docker named volume                                       │
└──────────────────────────────────────────────────────────────┘
```

### Request Flow

```text
Browser
  ↓
Angular / Nginx
  ↓ /api
Spring Boot
  ↓ JDBC
PostgreSQL
```

### Service Readiness

```text
PostgreSQL starts
  ↓
pg_isready = healthy
  ↓
Backend starts
  ↓
Actuator readiness = UP
  ↓
Frontend starts
  ↓
Nginx /health = 200
  ↓
Full stack ready
```

---

## Architecture Principles

### Financial Consistency

- PostgreSQL is the only financial source of truth.
- Financial state is strongly consistent.
- Money uses Kotlin `BigDecimal` and PostgreSQL `NUMERIC(19,2)`.
- Financial writes execute inside one application-service transaction.
- Transaction isolation is `READ_COMMITTED`.
- Wallet-changing operations use `SELECT ... FOR UPDATE`.
- Wallet state, ledger entries and related domain state commit or roll back together.
- Financial success is returned only after the database transaction commits.

### Locking

Global financial lock order:

```text
wallet
  ↓
related deposit, when required
```

This order must remain consistent across all financial flows.

### Ledger

- Every non-zero balance change produces an append-only ledger entry.
- Ledger writes occur in the same transaction as the corresponding wallet mutation.
- Ledger is audit history, not event sourcing.
- Zero-value ledger entries are not created.

### Idempotency

Payment callback idempotency is enforced durably through:

```text
PostgreSQL state
+ row locks
+ database constraints
```

Redis, in-memory caches and TTL keys are not used for financial correctness or payment idempotency.

---

## Observability Baseline

Observability is intentionally lightweight and embedded in the existing runtime services.

### Backend

Spring Boot Actuator exposes:

```text
/actuator/health
/actuator/health/liveness
/actuator/health/readiness
/actuator/info
/actuator/metrics
```

Micrometer provides standard JVM, HTTP and datasource metrics.

### Request Correlation

All HTTP requests use:

```text
X-Request-ID
```

The effective request ID is:

- accepted from the caller when valid;
- generated when missing or invalid;
- propagated through Nginx;
- included in backend MDC;
- returned in the response;
- included in request-completion logs.

### Logging

Backend:

```text
stdout / stderr
```

Nginx:

```text
access logs → stdout
error logs  → stderr
```

Logs must never contain:

- passwords;
- HMAC secrets;
- signatures;
- authorization values;
- raw callback bodies;
- sensitive payloads.

### Healthchecks

| Service | Healthcheck |
|---|---|
| PostgreSQL | `pg_isready` |
| Backend | `/actuator/health/readiness` |
| Frontend | `/health` |

### Intentionally Excluded

The assignment does not deploy:

- Prometheus server;
- Grafana;
- Elasticsearch;
- Logstash;
- Kibana;
- WatchDog;
- distributed tracing infrastructure.

These remain possible production extensions when real operational requirements exist.

---

# Implementation Roadmap

## Delivery Strategy

Implementation proceeds through explicitly approved vertical slices.

Each stage must:

1. have a clearly bounded scope;
2. produce a small reviewable diff;
3. include relevant automated tests;
4. finish with a green build;
5. keep local development working;
6. keep the full Docker Compose smoke test working;
7. stop before the next stage until reviewed.

---

## Stage 1 — Repository Bootstrap, Containerized Delivery and Observability Baseline

### Goal

Create a reproducible project skeleton without casino business functionality.

### Outcome

- buildable Spring Boot application;
- buildable Angular 17 application;
- PostgreSQL container;
- backend container;
- frontend/Nginx container;
- Actuator health and metrics;
- request correlation;
- Docker healthchecks;
- English project documentation.

### Definition of Done

- backend tests and build pass;
- frontend tests and production build pass;
- all three Compose services are healthy;
- request IDs propagate correctly;
- full-stack smoke test passes;
- no wallet schema or casino business API exists.

---

## Stage 2 — Wallet Read Vertical Slice

### Goal

Introduce the first end-to-end business read path.

### Outcome

- Flyway enabled;
- `wallet` schema created;
- one seeded demo player;
- real and bonus balances stored as `NUMERIC(19,2)`;
- `GET /api/wallet`;
- Angular displays authoritative balances.

### Definition of Done

- migration tests pass against PostgreSQL;
- wallet summary integration tests pass;
- UI shows `0.00 / 0.00`;
- Compose remains healthy;
- no deposit, bonus, ledger or round logic exists yet.

---

## Stage 3 — Deposits and Append-Only Ledger

### Goal

Implement secure and idempotent deposit completion.

### Outcome

- pending deposit creation;
- raw-body HMAC-SHA256 validation;
- signed provider callback;
- durable callback idempotency;
- real balance credit;
- append-only ledger;
- paginated ledger read API.

### Definition of Done

Tests cover:

- valid callback;
- invalid signature;
- duplicate callback;
- concurrent duplicate callback;
- amount mismatch;
- transaction rollback;
- wallet/ledger reconciliation.

Creating a pending deposit must not lock the wallet or trigger bonus expiration.

---

## Stage 4 — Atomic Game Rounds and Concurrency

### Goal

Implement atomic real-money rounds and deterministic concurrency protection.

### Outcome

- real-first stake debit;
- synchronous round settlement;
- deterministic `totalWin`;
- saved stake and win allocation;
- wallet row locking.

### Definition of Done

- money calculation tests pass;
- two concurrent bets of `8.00` against `10.00` produce exactly one success;
- PostgreSQL lock contention is explicitly verified;
- final real balance is `2.00`;
- rejected operation creates no round or bet ledger entry;
- test setup preserves wallet/ledger reconciliation.

---

## Stage 5 — Welcome Bonus and Mixed-Funds Rounds

### Goal

Add welcome bonus lifecycle and mixed real/bonus money handling.

### Outcome

- first qualifying completed deposit grants bonus;
- bonus capped at `100.00`;
- wagering target is bonus ×20;
- real-first mixed stake allocation;
- active-bonus `5.00` stake limit;
- proportional real/bonus win allocation;
- wagering progress.

### Definition of Done

Tests cover:

- `19.99 / 20.00` threshold;
- `100.00` cap;
- one lifetime welcome bonus;
- concurrent qualifying deposits;
- mixed stake allocation;
- mixed win allocation;
- rounding and exact remainder;
- wagering progress.

No duplicate mutable bonus balance is introduced.

---

## Stage 6 — Bonus Completion, Expiration and Complete Angular Page

### Goal

Complete bonus lifecycle and all required frontend functionality.

### Outcome

Backend:

- wagering completion;
- bonus-to-real conversion;
- lazy expiration;
- deterministic `Clock`.

Frontend:

- wallet balances;
- bonus progress;
- deposit form;
- play-a-round form;
- paginated ledger;
- English/Ukrainian translations;
- translated errors and states.

### Definition of Done

- completion tests pass;
- expiration boundary tests pass;
- repeated expiration is idempotent;
- zero-value ledger entries are not created;
- forms and validation work;
- pagination works;
- EN/UK switching works;
- stale HTTP responses cannot overwrite newer state.

---

## Stage 7 — CI, Clean-Clone Verification and Pull Request Delivery

### Goal

Make the delivered solution reproducible and review-ready.

### Outcome

- CI runs the same verification used locally;
- clean clone builds and tests successfully;
- full Compose smoke test succeeds;
- README and NOTES reflect actual behaviour;
- final GitHub Pull Request is ready for review.

### Definition of Done

A clean checkout can successfully run:

```text
tests
→ builds
→ Docker image builds
→ Docker Compose
→ healthchecks
→ smoke verification
```

The final diff contains no:

- generated build output;
- secrets;
- IDE files;
- private `docs/`;
- unrelated changes.

---

# Rules Preserved Across All Stages

## Financial Reliability

- PostgreSQL remains the only financial source of truth.
- Money never uses binary floating point.
- Balance and ledger mutations are atomic.
- Financial operations use one application-service transaction.
- Preserve the wallet-first lock order.
- Do not introduce independent `REQUIRES_NEW` financial writes.
- Do not perform external network calls inside financial transactions.
- Return financial success only after commit.

## Architecture

- Keep the backend a modular monolith.
- Do not introduce microservices without a demonstrated requirement.
- Do not introduce Redis for financial state or idempotency.
- Do not create speculative abstractions or placeholder modules.
- Controllers remain free of transaction and business logic.

## Observability

- Add logs and metrics only for behaviour that actually exists.
- Propagate `X-Request-ID`.
- Never log secrets or sensitive payloads.
- Keep liveness independent from PostgreSQL.
- Keep readiness dependent on required infrastructure.

## Delivery

- Every stage ends with a green build.
- Every stage produces a reviewable diff.
- Documentation changes together with behaviour.
- The full Docker Compose smoke test must remain functional.
- Do not begin the next stage automatically.
- Git staging, commits and pushes remain manual.