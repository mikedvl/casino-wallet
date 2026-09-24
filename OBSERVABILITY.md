# Observability

Casino Wallet provides metrics, centralized logs and distributed tracing.

Observability is independent from financial correctness. PostgreSQL remains the source of truth for financial state, and monitoring failures do not affect financial transactions.

## Architecture

```text
Metrics:
Spring Boot / Micrometer
    → Prometheus
    → Grafana

Logs:
Spring Boot / Nginx stdout/stderr
    → Filebeat
    → Elasticsearch
    → Kibana

Traces:
Spring Boot / Micrometer Tracing
    → OpenTelemetry Collector
    → Jaeger
```

The application itself remains:

```text
Browser
    → Angular / Nginx
    → Spring Boot
    → PostgreSQL
```

Observability services do not participate in application transactions.

## Start

### IntelliJ IDEA

Open the project, select:

`OBSERVABILITY - Full Stack`

and press **Run**.

This starts the complete environment:

- PostgreSQL
- backend
- frontend
- Prometheus
- Grafana
- Elasticsearch
- Kibana
- Filebeat
- OpenTelemetry Collector
- Jaeger

No additional service needs to be started manually.

### Command line

From the repository root:

```bash
./scripts/start-observability.sh
```

Explicit `start` is equivalent:

```bash
./scripts/start-observability.sh start
```

The native Docker Compose equivalent is:

```bash
docker compose \
  -f compose.yaml \
  -f compose.observability.yaml \
  --profile observability \
  up -d --build --wait --wait-timeout 300
```

Only Docker Engine/Desktop and Docker Compose v2 are required.

The complete observability stack is significantly heavier than the normal three-service application stack because it includes Elasticsearch and Kibana.

## URLs

| Service | URL |
|---|---|
| Casino Wallet | <http://localhost:4200> |
| Backend readiness | <http://localhost:8080/actuator/health/readiness> |
| Prometheus metrics | <http://localhost:8080/actuator/prometheus> |
| Prometheus | <http://localhost:9090> |
| Grafana | <http://localhost:3000> |
| Kibana | <http://localhost:5601> |
| Elasticsearch | <http://localhost:9200> |
| Jaeger | <http://localhost:16686> |

Grafana opens with the provisioned **Casino Wallet · Operations** dashboard.

## Metrics

Prometheus scrapes:

```text
backend:8080/actuator/prometheus
```

every 15 seconds.

Grafana uses Prometheus as its provisioned datasource.

The dashboard includes:

- backend availability and readiness;
- HTTP request rate;
- 4xx / 5xx rates;
- request latency;
- JVM heap, CPU and threads;
- HikariCP connection-pool metrics;
- deposit callback outcomes;
- round outcomes;
- bonus lifecycle outcomes.

Custom business metrics include:

```text
casino_wallet_deposit_callbacks_total
casino_wallet_rounds_total
casino_wallet_bonus_lifecycle_total
casino_wallet_readiness
```

Business/request identifiers are not used as metric labels.

Useful Prometheus checks:

```promql
up{job="casino-wallet"}
```

```promql
casino_wallet_readiness
```

```promql
casino_wallet_rounds_total
```

`up{job="casino-wallet"}` should return `1`.

## Centralized Logs

The backend writes structured ECS JSON logs to stdout/stderr.

Nginx writes structured access logs and error logs to stdout/stderr.

```text
Spring Boot ─┐
             ├→ Docker logs → Filebeat → Elasticsearch → Kibana
Nginx ───────┘
```

Filebeat collects logs only from the Casino Wallet backend and frontend containers.

Important searchable fields include:

```text
service.name
log.level
event_name
request_id
trace_id
span_id
```

Logs are stored under:

```text
casino-wallet-logs-*
```

### Kibana

Open:

<http://localhost:5601>

In **Discover → ES|QL**, for example:

```esql
FROM casino-wallet-logs-*
| WHERE service.name == "backend"
| SORT @timestamp DESC
| KEEP @timestamp, log.level, event_name, message, request_id, trace_id, span_id
| LIMIT 50
```

Search a specific request:

```esql
FROM casino-wallet-logs-*
| WHERE request_id == "your-request-id"
```

Search a specific trace:

```esql
FROM casino-wallet-logs-*
| WHERE trace_id == "your-trace-id"
```

## Distributed Tracing

Tracing uses:

- Micrometer Tracing;
- OpenTelemetry;
- W3C `traceparent` / `tracestate`;
- OTLP HTTP/protobuf.

```text
Spring Boot
    → OpenTelemetry Collector
    → Jaeger
```

Each backend HTTP request receives a server span.

Financial operations also expose fixed application spans such as:

```text
casino.wallet.deposit.complete
casino.wallet.round.play
```

There are no JDBC spans and no SQL parameter capture.

### Correlation

The following identifiers have different purposes:

| Identifier | Purpose |
|---|---|
| `X-Request-ID` / `request_id` | Request and support correlation |
| `trace_id` | Complete distributed trace |
| `span_id` | Individual operation inside the trace |
| `depositId`, `roundId`, `bonusId` | Business object identity |

There is no generic application `transactionId`.

A request can be investigated as:

```text
HTTP response
    ↓
X-Request-ID
    ↓
Kibana
    ↓
trace_id
    ↓
Jaeger
```

Open Jaeger at:

<http://localhost:16686>

and select service:

```text
casino-wallet
```

## Security

Observability does not export:

- HMAC secrets;
- callback signatures;
- raw callback bodies;
- authorization headers;
- database passwords;
- request payloads;
- SQL parameter values.

Trace and metric delivery is operational only. Telemetry failure must never fail or roll back a financial transaction.

## Stop and Restart

Stop only observability services while keeping Casino Wallet running:

```bash
./scripts/start-observability.sh stop
```

This stops:

```text
prometheus
grafana
elasticsearch
kibana
filebeat
otel-collector
jaeger
```

while keeping:

```text
postgres
backend
frontend
```

running.

Restart observability:

```bash
./scripts/start-observability.sh start
```

To stop the application as well:

```bash
./scripts/start-demo.sh stop
```

Observability data uses separate Docker volumes and does not share the financial PostgreSQL volume.

## Verification

Check service state:

```bash
docker compose --profile observability ps --all
```

Validate the complete Compose configuration:

```bash
docker compose \
  -f compose.yaml \
  -f compose.observability.yaml \
  --profile observability \
  config --quiet
```

Normal project verification remains:

```bash
./scripts/verify.sh
```

A healthy container alone does not prove telemetry delivery:

- Prometheus should show the `casino-wallet` target as `UP`;
- Grafana should display live application metrics;
- Elasticsearch should contain `casino-wallet-logs-*`;
- Kibana should find logs by `request_id` and `trace_id`;
- Jaeger should contain traces for service `casino-wallet`.

## Production Considerations

The supplied configuration is intended for local development and demonstration.

A production deployment would additionally require decisions around authentication, TLS, network isolation, log retention, Elasticsearch sizing/backups, Prometheus retention/HA, trace sampling, durable trace storage and Collector capacity.

Metrics, logs and traces complement operational diagnostics; they never replace PostgreSQL financial state or the append-only ledger.