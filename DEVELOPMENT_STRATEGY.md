# Development Strategy

## 1. Purpose

This document defines the engineering approach used to implement the Casino Wallet assignment.

It complements:

- `TaskDescription.md` — assignment requirements;
- `IMPLEMENTATION_PLAN.md` — staged delivery roadmap;
- `NOTES.md` — reviewer-facing assumptions;
- `AGENTS.md` — mandatory implementation constraints.

This document describes how the code should be developed, tested, structured and reviewed.

If this document conflicts with the assignment requirements or `AGENTS.md`, the assignment and `AGENTS.md` take precedence.

The primary engineering goals are:

1. financial correctness;
2. deterministic behaviour;
3. concurrency safety;
4. clear transaction boundaries;
5. small reviewable changes;
6. meaningful automated tests;
7. operational visibility;
8. simple maintainable code.

Correctness is preferred over architectural cleverness.

---

# 2. Development Philosophy

The project uses:

- vertical-slice development;
- modular-monolith architecture;
- risk-based test-driven development;
- explicit dependency injection;
- explicit database transactions;
- PostgreSQL-backed integration testing;
- simple synchronous HTTP APIs;
- lightweight observability;
- small stage-by-stage delivery.

The preferred order of priorities is:

```text
Correctness
    ↓
Clarity
    ↓
Testability
    ↓
Maintainability
    ↓
Performance where demonstrated necessary
    ↓
Architectural sophistication
```

The project intentionally avoids speculative infrastructure and abstractions.

---

# 3. Core Engineering Principles

The implementation follows:

```text
KISS
SOLID
DRY
YAGNI
Composition over inheritance
Explicit behaviour over framework magic
```

These principles are guidelines, not goals by themselves.

## KISS

KISS has the highest priority.

Prefer:

```text
one explicit application service
+
one explicit transaction
+
one explicit SQL query
```

over a generalized framework that hides financial behaviour.

Financial code should be easy to inspect.

A reviewer should be able to answer:

```text
Where is the transaction?
Where is the wallet locked?
Where is the balance checked?
Where is the ledger written?
```

without following several layers of indirection.

---

## SOLID

SOLID is applied pragmatically.

### Single Responsibility

Examples:

```text
Controller
    -> HTTP mapping and validation

Application service
    -> use-case orchestration and transaction boundary

Repository
    -> SQL and persistence

Domain calculation
    -> deterministic business calculation
```

Controllers must not contain financial business logic.

Repositories must not decide business rules.

### Open/Closed

Do not create extension points before they are required.

A future possibility is not sufficient justification for an abstraction.

### Liskov Substitution

Prefer composition over inheritance.

Business services should normally not form inheritance hierarchies.

### Interface Segregation

Keep interfaces small when interfaces are actually justified.

Do not create:

```text
WalletService
WalletServiceImpl
```

only because a framework tutorial does so.

Introduce an interface when it provides a real boundary, for example:

- external provider abstraction;
- clock abstraction;
- persistence port when it materially improves isolation;
- multiple implementations;
- a useful test seam.

### Dependency Inversion

High-level financial use cases should not depend on infrastructure details unnecessarily.

At the same time, this project should not create a full hexagonal architecture framework around five tables.

Use dependency inversion where it improves clarity or testability.

---

# 4. DRY Without Premature Abstraction

Do not remove duplication merely because two blocks currently look similar.

Duplication should be removed when it represents the same concept and is likely to evolve together.

Prefer temporary duplication over an incorrect generic abstraction.

Avoid generic classes such as:

```text
BaseController
BaseService
BaseRepository
GenericCrudService
CommonManager
```

unless the project later demonstrates an actual need.

A useful rule is:

```text
first occurrence  -> implement clearly
second occurrence -> observe similarity
third occurrence  -> consider extracting a shared concept
```

Financial correctness must never be hidden behind generic CRUD abstractions.

---

# 5. Test-Driven Development Strategy

Testing is a first-class part of the implementation because the assignment contains:

- money;
- idempotency;
- transactionality;
- concurrency;
- time-dependent behaviour;
- proportional allocation and rounding.

The project uses **risk-based TDD**.

TDD is strict for behaviour where a regression could change financial state.

TDD is not applied mechanically to framework boilerplate.

---

## 5.1 Mandatory Test-First Areas

The following functionality should normally start with a failing test before implementation:

- money allocation;
- cent-to-EUR conversion;
- deposit qualification;
- bonus cap;
- wagering target;
- wagering progress;
- maximum-bet rule;
- real-first stake allocation;
- proportional win allocation;
- rounding and exact remainder handling;
- bonus completion;
- bonus expiration;
- callback signature validation;
- callback idempotency;
- amount mismatch handling;
- transaction rollback;
- wallet/ledger reconciliation;
- concurrent wallet mutation;
- concurrent duplicate callback handling.

Typical cycle:

```text
RED
    ↓
write the smallest failing behavioural test

GREEN
    ↓
implement the smallest correct behaviour

REFACTOR
    ↓
improve structure without changing behaviour
```

---

## 5.2 Integration-First Behaviour

Some important behaviour cannot be proven correctly with mocks.

These cases use PostgreSQL integration tests:

```text
Flyway migrations
database constraints
SELECT ... FOR UPDATE
transaction rollback
callback idempotency
concurrent deposit processing
concurrent game rounds
wallet/ledger reconciliation
```

PostgreSQL Testcontainers is used.

H2 is not used.

Mocks must not be used to "prove" PostgreSQL locking behaviour.

---

## 5.3 Concurrency Tests

Concurrency tests must prove actual database contention.

Starting two threads at approximately the same time is not enough.

For the required scenario:

```text
initial balance = 10.00

bet A = 8.00
bet B = 8.00
```

the test must prove that:

```text
one transaction acquires the wallet row lock
the other transaction waits
the first transaction commits
the second observes the new balance
exactly one bet succeeds
final balance = 2.00
```

The test should verify actual PostgreSQL blocking rather than relying only on timing.

---

## 5.4 Infrastructure Tests

Framework configuration does not require dogmatic test-first development.

Infrastructure tests are added where they protect meaningful behaviour, for example:

- Spring context starts;
- Flyway migration succeeds;
- PostgreSQL connectivity works;
- health/readiness works;
- request correlation works;
- Docker healthchecks work.

---

# 6. Backend Test Layers

Backend tests are divided by purpose.

## Unit tests

Use for deterministic calculations.

Examples:

```text
bonus calculation
stake allocation
win allocation
rounding
wagering progress
expiration boundary decisions
signature calculation helpers
```

Unit tests should:

- require no Spring context;
- require no Docker;
- execute quickly;
- be deterministic.

Kotlin test names should describe behaviour.

Example:

```kotlin
@Test
fun `win allocation assigns rounding remainder to bonus`() {
    ...
}
```

---

## Persistence integration tests

Use real PostgreSQL Testcontainers.

Test:

- migrations;
- SQL queries;
- constraints;
- unique keys;
- locking;
- append-only guarantees.

---

## Application integration tests

Test complete use cases across:

```text
application service
    ↓
repository
    ↓
PostgreSQL
```

These tests are especially important for financial writes.

---

## HTTP integration tests

Test public API behaviour including:

- HTTP status;
- request validation;
- response model;
- error model;
- request ID;
- callback signature handling.

HTTP tests should not duplicate every domain calculation already covered by unit tests.

---

# 7. Frontend Testing Strategy

Frontend code is tested.

The goal is not maximum coverage percentage.

The goal is confidence in behaviour visible to the user.

Use the existing Angular test infrastructure unless a real reason to replace it appears.

Do not introduce another frontend testing framework only for fashion.

---

## Frontend unit/component tests

Test important behaviour such as:

- balances are rendered correctly;
- money strings are displayed correctly;
- loading state;
- API error state;
- deposit form validation;
- play-round form validation;
- disabled actions while a request is active;
- bonus progress rendering;
- pagination;
- EN/UK language switching;
- stale HTTP responses cannot overwrite newer state.

---

## HTTP service tests

Test that frontend services:

- call relative `/api` URLs;
- use the correct HTTP method;
- serialize request data correctly;
- deserialize backend responses correctly;
- preserve money as decimal strings.

The frontend must not become the source of truth for money.

---

## End-to-End Tests

A dedicated browser E2E framework is not required for the core assignment.

The Docker Compose smoke test already verifies the complete delivery path.

If all required stages are complete and time remains, a small E2E test may be added as a stretch improvement.

Do not delay financial correctness to introduce Cypress, Playwright or another large testing dependency.

---

# 8. Test Coverage Policy

No artificial global percentage target is required.

A high coverage number does not prove financial correctness.

Critical financial branches must have explicit behavioural tests.

The important question is:

```text
Can the tests detect a change that would lose, duplicate or misclassify money?
```

If the answer is no, the test suite is insufficient.

---

# 9. Backend Architecture

The backend remains a modular monolith.

Prefer feature-oriented packages.

Example as features are introduced:

```text
com.example.casinowallet

wallet/
    application/
    domain/
    persistence/
    web/

deposit/
    application/
    domain/
    persistence/
    web/

bonus/
    application/
    domain/
    persistence/

round/
    application/
    domain/
    persistence/
    web/

ledger/
    persistence/
    web/

config/
observability/
```

Directories are created only when they contain real code.

Do not create empty future packages.

---

# 10. Application Service Pattern

Financial use cases are orchestrated by application services.

Example:

```text
HTTP Controller
      ↓
Application Service
      ↓
Transaction
      ↓
Repository / SQL
      ↓
PostgreSQL
```

The application service owns:

- transaction boundary;
- locking order;
- orchestration;
- domain rule invocation;
- persistence coordination.

The controller must not own the transaction.

---

# 11. Domain Logic

Pure business calculations should be isolated from Spring when practical.

Examples:

```text
calculateBonus(...)
allocateStake(...)
allocateWin(...)
calculateWageringProgress(...)
```

Pure calculations are easier to reason about and test.

Do not create a large DDD framework.

Use domain objects only when they make financial rules clearer.

A custom `Money` abstraction should not be introduced merely because financial systems sometimes have one.

`BigDecimal` remains the authoritative numeric type unless repetition demonstrates that a dedicated value object would genuinely simplify the implementation.

---

# 12. Persistence Strategy

Use Spring JDBC / `NamedParameterJdbcTemplate`.

Do not use JPA or Hibernate.

Reasons:

- SQL remains visible;
- row locking is explicit;
- transaction behaviour is easier to inspect;
- PostgreSQL-specific concurrency behaviour remains clear;
- less ORM behaviour is hidden from the reviewer.

SQL belongs in persistence classes.

Use named parameters rather than manually concatenating SQL.

Never construct SQL from untrusted string input.

---

# 13. Dependency Injection

Dependency injection is used consistently.

## Backend

Use constructor injection.

Preferred:

```kotlin
@Service
class WalletApplicationService(
    private val walletRepository: WalletRepository,
    private val clock: Clock
)
```

Avoid field injection:

```kotlin
@Autowired
lateinit var repository: WalletRepository
```

Constructor injection makes dependencies explicit and improves testability.

Spring-managed business services should have immutable dependencies.

---

## Frontend

Use Angular dependency injection.

For standalone Angular code, `inject()` is acceptable and concise:

```typescript
private readonly walletApi = inject(WalletApiService);
```

or constructor injection may be used where it improves readability.

Use one convention consistently inside the project.

Services should normally use:

```typescript
providedIn: 'root'
```

when they represent application-wide stateless infrastructure or state facades.

---

# 14. Frontend Architecture

Angular remains a standalone application.

Prefer a small structure such as:

```text
src/app/

core/
    api/
    models/

wallet/
    wallet-page.component.ts
    wallet-api.service.ts
    wallet.facade.ts

shared/
    only when real reusable components exist
```

Do not create a large enterprise Angular architecture for one page.

---

# 15. Frontend State Management

Do not introduce NgRx.

Use Angular Signals for local application state.

A lightweight facade may own page state:

```text
wallet summary
bonus progress
ledger page
loading states
errors
```

Example conceptual flow:

```text
Component
    ↓
Facade
    ↓
API service
    ↓
HttpClient
```

The component should primarily handle presentation and user interaction.

The API service should primarily handle HTTP.

The facade may coordinate requests and UI state.

---

# 16. TypeScript Policy

Use strict TypeScript.

Avoid:

```typescript
any
```

unless interacting with an unavoidable untyped boundary.

Prefer explicit API models.

Example:

```typescript
export interface WalletSummary {
    realBalance: string;
    bonusBalance: string;
}
```

Money received from the backend remains a decimal string.

Do not convert authoritative monetary values to JavaScript `number`.

Formatting to a human-readable representation is a presentation concern only.

---

# 17. Naming Conventions

Naming should reveal intent.

Avoid abbreviations unless universally understood.

Avoid names such as:

```text
data
obj
tmp
helper
manager
processor
util
doStuff
handleData
```

unless the name genuinely represents the concept.

---

## Kotlin

Classes and interfaces:

```text
PascalCase
```

Examples:

```text
WalletController
WalletApplicationService
JdbcWalletRepository
WalletSummary
DepositCallbackRequest
```

Functions and variables:

```text
camelCase
```

Examples:

```text
findWallet
realBalance
bonusBalance
depositAmount
wageringProgress
```

Constants:

```text
UPPER_SNAKE_CASE
```

Examples:

```text
MAX_ACTIVE_BONUS_STAKE
WELCOME_BONUS_CAP
```

Boolean names should read naturally:

```text
isBonusActive
hasSufficientBalance
isSignatureValid
```

---

## TypeScript

Classes and interfaces:

```text
PascalCase
```

Examples:

```text
WalletApiService
WalletFacade
WalletSummary
LedgerEntry
```

Variables, functions and methods:

```text
camelCase
```

Examples:

```text
realBalance
loadWallet
submitDeposit
isLoading
```

Event handlers:

```text
onDepositSubmit
onLanguageChange
```

Signals do not use a `$` suffix.

RxJS Observables may use `$`:

```text
wallet$
```

when an Observable is actually exposed.

---

## Files

Angular files use kebab-case.

Examples:

```text
wallet-page.component.ts
wallet-api.service.ts
wallet.facade.ts
wallet-summary.model.ts
```

---

## Database

Tables and columns use snake_case.

Examples:

```text
wallet
ledger_entry
game_round

player_id
real_balance
bonus_balance
created_at
```

---

## HTTP JSON

JSON uses camelCase.

Example:

```json
{
  "realBalance": "10.00",
  "bonusBalance": "5.00"
}
```

---

# 18. API Transport

The project uses synchronous HTTP REST APIs with JSON.

Example:

```text
Angular
   ↓ HTTP / JSON
Spring MVC
   ↓ JDBC
PostgreSQL
```

This is sufficient for all assignment requirements.

---

# 19. SignalR / WebSocket / SSE

.NET SignalR does not have a direct requirement-equivalent in this project.

Spring supports similar real-time mechanisms through:

- WebSocket;
- STOMP over WebSocket;
- Server-Sent Events.

They are intentionally not used.

The assignment does not require:

- live multiplayer state;
- server push;
- live odds;
- chat;
- real-time notifications;
- streaming game events.

Adding WebSocket infrastructure would increase complexity without solving a current requirement.

After an operation completes, the server returns authoritative state and the frontend updates its view.

If a future requirement genuinely needs one-way server updates, SSE should be considered before a full WebSocket protocol.

---

# 20. GraphQL and Open Graph

GraphQL is not used.

The API is small and has stable use cases, so REST provides a simpler contract and easier HTTP-level testing.

GraphQL would add schema and client complexity without solving a current problem.

The **Open Graph Protocol** is unrelated to backend transport.

Open Graph defines HTML metadata used for link previews on platforms such as social networks.

It has no role in the Casino Wallet API.

---

# 21. HTTP Contract

Use relative URLs:

```text
/api/...
```

Use:

```text
Content-Type: application/json
```

Money is serialized as decimal strings.

Example:

```json
{
  "realBalance": "10.00"
}
```

rather than:

```json
{
  "realBalance": 10.0
}
```

Dates and timestamps use ISO-8601 UTC representation.

Example:

```text
2026-09-23T08:30:00Z
```

---

# 22. Error Responses

API errors should have a consistent machine-readable representation.

Prefer Spring's standard `ProblemDetail` model where appropriate rather than inventing a large custom error framework.

A response should make it possible for the frontend to distinguish cases such as:

```text
INSUFFICIENT_FUNDS
MAX_BET_EXCEEDED
INVALID_SIGNATURE
DEPOSIT_AMOUNT_MISMATCH
VALIDATION_ERROR
```

Do not expose:

- stack traces;
- SQL;
- credentials;
- internal secrets.

---

# 23. Transaction Strategy

Every balance-changing use case uses one application-service transaction.

Conceptually:

```text
BEGIN

lock wallet

validate state

calculate changes

update wallet

write ledger

update related business state

COMMIT
```

Either all financial state changes commit or none do.

Transaction isolation remains:

```text
READ_COMMITTED
```

Wallet-changing operations use:

```sql
SELECT ... FOR UPDATE
```

Global lock order remains:

```text
wallet
    ↓
related deposit when required
```

---

# 24. Money Rules

Authoritative backend money uses:

```text
Kotlin      BigDecimal
PostgreSQL  NUMERIC(19,2)
API         decimal strings
Frontend    strings for authoritative values
```

Never use:

```text
Double
Float
JavaScript number
```

for authoritative money calculations.

Rounding must always be explicit.

Never rely on an implicit default rounding mode.

---

# 25. Time

Time-dependent business logic uses an injected `Clock`.

Do not call:

```kotlin
Instant.now()
```

throughout business logic.

Prefer:

```kotlin
Instant.now(clock)
```

This makes expiration behaviour deterministic in tests.

---

# 26. Observability Strategy

Operational visibility is part of backend quality.

The baseline remains intentionally lightweight.

Existing infrastructure includes:

```text
Spring Boot Actuator
Micrometer
X-Request-ID
MDC
structured stdout/stderr logs
Nginx access/error logs
Docker healthchecks
```

Observability must follow implemented behaviour.

Do not add business metrics for functionality that does not exist yet.

---

# 27. Request Correlation

Every request has an effective:

```text
X-Request-ID
```

The same identifier should be visible through the request path where practical.

Backend completion logs include:

```text
request_id
method
path
status
duration
```

This makes it possible to correlate an HTTP failure with application logs without introducing distributed tracing infrastructure.

---

# 28. Business Logging

As business functionality is introduced, important state transitions should produce concise structured logs.

Examples:

```text
deposit created
deposit callback accepted
deposit callback rejected
duplicate callback detected
round accepted
round rejected
bonus activated
bonus completed
bonus expired
```

Logs should describe outcomes, not dump complete objects.

Never log:

- HMAC secrets;
- callback signatures;
- raw callback bodies;
- passwords;
- authorization values;
- complete sensitive payloads.

Avoid logging data merely because it is available.

---

# 29. Metrics

Use Micrometer.

Start with framework metrics already supplied by Spring Boot.

Add custom metrics only when they answer an operational question.

Possible later examples:

```text
deposit_callback_total{outcome=accepted}
deposit_callback_total{outcome=duplicate}
deposit_callback_total{outcome=invalid_signature}

round_total{outcome=accepted}
round_total{outcome=rejected}

financial_operation_duration
```

Do not create dozens of counters merely to make the project look observable.

Keep metric cardinality bounded.

Never use player IDs, deposit IDs or request IDs as metric labels.

---

# 30. Optional Observability Stretch Goal

A centralized observability stack is **not part of the required implementation**.

It may be attempted only after:

```text
all business stages complete
+
all tests green
+
Docker Compose demo green
+
documentation complete
+
delivery requirements satisfied
```

Financial correctness must never be delayed in order to build dashboards.

The default reviewer stack must remain:

```text
postgres
backend
frontend
```

Optional observability must not be required for application correctness.

---

## Metrics Stretch Goal

If time remains, the first optional observability improvement should be Prometheus.

Architecture:

```text
Spring Boot
    ↓ /actuator/prometheus
Prometheus
```

This would require:

```text
micrometer-registry-prometheus
```

and should be introduced only in the optional stage.

Prometheus is for metrics.

It is not a log transport.

---

## Centralized Logs Stretch Goal

Elasticsearch and Kibana may optionally be used for centralized logs.

Conceptually:

```text
backend stdout
frontend/Nginx stdout
        ↓
log shipper
        ↓
Elasticsearch
        ↓
Kibana
```

A lightweight shipper such as Fluent Bit or Filebeat would normally be required.

The application should continue logging to stdout/stderr.

Do not make Spring Boot directly dependent on Elasticsearch only for log delivery.

Log shipping is an infrastructure concern.

---

## Important Distinction

The tools serve different purposes:

```text
Prometheus
    -> metrics

Elasticsearch
    -> indexed logs/documents

Kibana
    -> visualization/search over Elasticsearch

Fluent Bit / Filebeat
    -> log shipping
```

Kibana does not replace Prometheus.

Prometheus does not collect normal application logs.

If optional centralized observability is implemented, the default application must still operate when the entire observability stack is stopped.

---

## Compose Strategy for Optional Observability

If an observability stack is eventually approved, prefer Docker Compose profiles so that:

```bash
docker compose up --build
```

still starts only the required application stack.

An optional command could later look conceptually like:

```bash
docker compose --profile observability up --build
```

This must not be implemented before the core assignment is complete and explicitly approved.

---

# 31. Security Practices

Security-sensitive operations should remain explicit.

For provider callbacks:

- calculate HMAC over the exact raw HTTP body;
- use constant-time signature comparison;
- validate before entering the financial transaction;
- never log the secret;
- never log the signature;
- never log the raw callback body.

Database access uses parameterized SQL.

Secrets are provided through environment configuration.

No secrets are committed to Git.

---

# 32. Code Review Rules

Every stage should be reviewed for:

```text
correctness
tests
transaction boundaries
lock order
money representation
rounding
database constraints
idempotency
observability
security-sensitive logging
unnecessary abstractions
unnecessary dependencies
generated artifacts
documentation accuracy
```

Particular attention should be paid to code that mutates financial state.

---

# 33. Dependency Policy

A new dependency requires a concrete current need.

Before adding a library, ask:

```text
Can the JDK do this?
Can Spring Boot already do this?
Can Angular already do this?
Can PostgreSQL already do this?
```

If yes, prefer the existing platform.

Do not add dependencies only to reduce a few lines of straightforward code.

---

# 34. Refactoring Policy

Refactoring is encouraged after behaviour is protected by tests.

Refactoring must not silently change financial behaviour.

The preferred cycle is:

```text
behavioural test
    ↓
implementation
    ↓
green tests
    ↓
refactoring
    ↓
green tests again
```

Large architecture rewrites should not occur during an unrelated stage.

---

# 35. Stage Development Workflow

Each implementation stage follows this sequence.

## Step 1 — Understand

Review:

- assignment requirement;
- current implementation plan;
- relevant assumptions;
- current code;
- existing tests.

Define the exact stage boundary.

---

## Step 2 — Define Behaviour

List:

```text
happy path
boundary cases
failure cases
concurrency cases where relevant
```

---

## Step 3 — Write Tests First

For financial behaviour, create failing tests for the required behaviour.

For infrastructure behaviour, add focused tests where they provide meaningful protection.

---

## Step 4 — Implement Minimum Correct Behaviour

Do not implement future-stage features.

Avoid speculative generalization.

---

## Step 5 — Refactor

Improve names and structure while keeping tests green.

---

## Step 6 — Add Operational Visibility

Where applicable, add:

- concise logs;
- metrics;
- health behaviour.

Do not log sensitive data.

---

## Step 7 — Frontend

When the stage includes UI:

- add typed API contract;
- add frontend tests;
- implement presentation;
- preserve backend authority for financial state.

---

## Step 8 — Verify

Run relevant tests and then:

```bash
./scripts/verify.sh
```

When runtime behaviour changed, run the Docker Compose smoke test.

---

## Step 9 — Review Diff

Inspect:

```text
unrelated changes
unused dependencies
generated files
debug code
secrets
placeholder abstractions
missing tests
incorrect documentation
```

---

## Step 10 — Stop

Do not automatically start the next stage.

The user reviews and creates the Git commit.

---

# 36. Definition of Done for Business Features

A financial feature is not complete merely because the happy path works.

Where applicable, it must include:

- deterministic business tests;
- failure-path tests;
- boundary tests;
- PostgreSQL integration tests;
- rollback verification;
- concurrency verification;
- database constraints;
- safe logs;
- documentation updates.

The implementation should answer:

```text
What happens if the request is duplicated?
What happens if two requests arrive simultaneously?
What happens at the exact monetary boundary?
What happens if the transaction fails halfway through?
What happens if the server receives invalid input?
What state is committed?
What is written to the ledger?
```

---

# 37. Non-Goals

The development strategy does not require:

- microservices;
- Kafka;
- Redis;
- API Gateway;
- GraphQL;
- WebSocket;
- STOMP;
- SignalR-like infrastructure;
- Kubernetes;
- event sourcing;
- CQRS;
- full DDD;
- NgRx;
- a UI component framework;
- Elasticsearch;
- Kibana;
- Prometheus server;
- Grafana;
- distributed tracing.

Any of these technologies require a demonstrated requirement and explicit approval.

---

# 38. Final Principle

This is a financial consistency exercise.

The strongest implementation is not the one with the most technologies.

It is the one where a reviewer can clearly see:

```text
the business rule
+
the test that proves it
+
the transaction that protects it
+
the database constraint that reinforces it
+
the log or metric that makes failure diagnosable
```

The project should remain small enough that these relationships are obvious.