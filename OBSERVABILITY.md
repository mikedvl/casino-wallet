# Observability

This is an optional post-assignment enhancement on `develop`. Casino Wallet correctness, financial transactions, tests and application readiness do not depend on any monitoring service.

## Architecture

```text
Spring Boot / Micrometer
        │ GET /actuator/prometheus (pulled every 15 seconds)
        ▼
    Prometheus
        ▲
        │ PromQL queries
      Grafana
```

Prometheus pulls metrics from the backend. Grafana queries Prometheus; Spring Boot does not push metrics to Grafana.

```text
Logs:   backend / Nginx stdout/stderr → Filebeat → Elasticsearch → Kibana
Traces: Spring Boot / Micrometer → OTLP/HTTP → Collector → OTLP/HTTP → Jaeger
```

Logs, traces and metrics have separate storage and purposes. None participates in a financial transaction. Spring Boot never sends logs directly to Elasticsearch or Kibana.

| Service | Image / dependency | Role |
|---|---|---|
| `backend` | Spring Boot-managed `micrometer-registry-prometheus` (1.15.12) | Exports framework and application metrics. |
| `prometheus` | `prom/prometheus:v3.13.3` | Metrics collection and a separate local TSDB. |
| `grafana` | `grafana/grafana:13.2.2` | Provisioned datasource and one operational dashboard. |
| `elasticsearch` | `docker.elastic.co/elasticsearch/elasticsearch:9.4.6` | Single-node log index storage. |
| `kibana` | `docker.elastic.co/kibana/kibana:9.4.6` | Log search / Discover. |
| `filebeat` | `docker.elastic.co/beats/filebeat:9.4.6` | The only log shipper; Docker autodiscovery. |
| `otel-collector` | `otel/opentelemetry-collector-contrib:0.161.0` | Bounded trace batching and forwarding. |
| `jaeger` | `jaegertracing/jaeger:2.21.0` | In-memory trace storage and UI. |

Image versions were selected from the [Prometheus maintained releases](https://prometheus.io/download/) (3.13 LTS) and [Grafana OSS releases](https://grafana.com/grafana/download?edition=oss&platform=docker). No existing application image versions were changed.

The Elastic components share the same [9.4.6 release](https://www.elastic.co/downloads/past-releases/elasticsearch-9-4-6). Collector and Jaeger use [Collector 0.161.0](https://github.com/open-telemetry/opentelemetry-collector-releases/releases/tag/v0.161.0) and [Jaeger 2.21.0](https://www.jaegertracing.io/download/). Their minimal local Dockerfiles add only the static HTTP healthcheck client from `busybox:1.37.0-musl`; the upstream non-root runtime remains intact.

The default Compose model starts only `postgres`, `backend` and `frontend`. The `observability` profile adds seven optional services, for ten total. No application service depends on monitoring. The original `scripts/start-demo.sh` is unchanged.

## Start and open

Only Docker Engine/Desktop and Docker Compose v2 with `--wait` support are required. Local Java, Node, npm, Prometheus or Grafana installations are not needed.

Start the application and all seven monitoring services:

```bash
./scripts/start-observability.sh
```

No argument means `start`; explicit `start` is equivalent. The executable also works by full path from another directory. It checks Docker/Compose prerequisites, validates both Compose files, builds images, waits up to 300 seconds for healthchecks, verifies all ten services and prints their effective host URLs. On failure it prints status and bounded logs and preserves containers/data for inspection. It does not install software or run tests.

The transparent native equivalent, from the repository root, remains:

```bash
docker compose -f compose.yaml -f compose.observability.yaml \
  --profile observability up -d --build --wait --wait-timeout 300
```

The small explicit override activates the backend's `observability` Spring profile and rotated Docker `json-file` logs for backend/frontend. It preserves `docker compose logs` and contains no duplicate application architecture. The profile alone starts the monitoring containers; use **both files** for JSON backend logs and trace export. Without the override, the application does not try to reach a Collector.

The shared IntelliJ configuration **OBSERVABILITY - Full Stack** starts the same complete environment using both Compose files, the `observability` profile and the existing Docker connection named `Docker`. **DEMO - Full Stack** remains the simple three-service workflow. Use the helper's `stop` command for monitoring-only shutdown.

The `scripts/` entry points remain distinct: `start-demo.sh` manages the simple application, `start-observability.sh` manages optional startup/monitoring-only stop, and `verify.sh` runs local tests and production builds.

The local Docker engine used for verification has approximately **8 GiB** of memory. Allow that order of capacity for the complete stack; Elasticsearch uses a fixed 1 GiB JVM heap and Kibana a 768 MiB Node heap, with additional native/container memory. Image build time is separate from the 300-second readiness timeout.

| URL | Purpose |
|---|---|
| <http://localhost:4200> | Casino Wallet UI. |
| <http://localhost:9090> | Prometheus queries and target status. |
| <http://localhost:3000> | Grafana home dashboard; no login or manual setup. |
| <http://localhost:3000/d/casino-wallet> | Casino Wallet operational dashboard. |
| <http://localhost:9200> | Local Elasticsearch API; no authentication in this demo. |
| <http://localhost:5601> | Kibana Discover / ES\|QL log search. |
| <http://localhost:16686> | Jaeger; select service `casino-wallet`. |
| <http://localhost:8080/actuator/prometheus> | Raw backend exposition. |
| <http://localhost:8080/actuator/health/readiness> | Existing readiness endpoint, including PostgreSQL. |

Prometheus scrapes `backend:8080/actuator/prometheus` on the Compose network every **15 seconds**, with a **5-second timeout**. Grafana's provisioned datasource, UID `casino-prometheus`, points to `http://prometheus:9090`. Neither container uses host `localhost` to reach another service.

Optional port overrides are `PROMETHEUS_PORT=9090`, `GRAFANA_PORT=3000`, `ELASTICSEARCH_PORT=9200`, `KIBANA_PORT=5601` and `JAEGER_PORT=16686`. Set them through the shell or an ignored `.env`; `.env` is not required. All published ports bind to `127.0.0.1`. Backend/frontend/PostgreSQL defaults remain `8080`/`4200`/`15432`. OTLP and Collector/Jaeger health ports stay internal; no local DEV exporter port is published.

Grafana uses anonymous **Viewer** access. Login form, basic authentication, self-signup and initial admin-user creation are disabled. No password or API token is required or supplied by the repository. Provisioning files and dashboards are mounted read-only; dashboard editing through the UI is disabled. This is local demonstration access, not production access control.

Prometheus and Grafana use their image-provided non-root users and read-only root filesystems. Writable state is limited to their named volumes and Grafana's temporary filesystem. Healthchecks use Prometheus `/-/ready` and Grafana `/api/health`. Restart policy remains Compose's default, consistent with the application services.

## Metrics and meaning

Only the following custom business counter families are added:

| Exported name | Fixed labels | Meaning |
|---|---|---|
| `casino_wallet_deposit_callbacks_total` | `operation`: `provider_callback`, `demo_completion`; `outcome`: `completed`, `duplicate`, `rejected`, `failed` | One outcome for each controller-dispatched provider callback or demo completion. |
| `casino_wallet_rounds_total` | `outcome`: `completed`, `rejected`, `failed` | One outcome for each controller-dispatched play request. |
| `casino_wallet_bonus_lifecycle_total` | `outcome`: `completed`, `expired` | One committed terminal bonus transition. |

All **13 counter series** are registered at zero before traffic. Counter values reset when the backend process restarts. They are operational measurements, not durable financial totals and not an alternative to the PostgreSQL ledger. Prometheus `rate`/`increase` handle process counter resets when enough samples exist.

`CasinoWalletMetrics` observes explicit controller actions, including parsing and the transactional service proxy. `completed` and `duplicate` are counted only after that proxy returns and commits; an exception before/at commit cannot count as success. Controlled domain/validation errors count as `rejected`; unexpected exceptions count as `failed` and are rethrown unchanged. Provider and demo paths are distinguished to avoid presenting simulated completions as signed provider traffic. A duplicate measures another request, not another credit.

Malformed HTTP requests rejected by Spring before controller invocation appear in the framework HTTP metrics, but not these business counters. Direct application-service calls also do not count as callback/round HTTP operations. Transport/serialization failures after a committed service result are visible in HTTP error metrics; business counters describe the committed operation, not proof that a client received the response.

Bonus lifecycle counters run inside the existing `afterCommit` synchronization. They count transitions even when the remaining bonus is zero; repeated terminal-state reads do not increment them. A technical rollback emits no lifecycle success. An expiration committed before a controlled round rejection correctly produces both an expiration and a rejected-round outcome: these describe different events.

Counter registration/increment errors cannot change a financial result; an instrumentation failure is logged once without exception payloads. No financial calculation, transaction isolation, lock order, HMAC validation or ledger rule is changed.

The additional gauge `casino_wallet_readiness` is `1` only when the existing Actuator readiness group is `UP`, otherwise `0`. It evaluates that group at scrape time, including its PostgreSQL check. This adds one health evaluation per scrape and no new health dependency. Prometheus `up` instead measures whether scraping works; these signals are intentionally separate.

Framework meters provide HTTP duration histograms, JVM heap/threads/CPU and Hikari pool measurements. `/actuator/health`, liveness, readiness, info and metrics remain available. Unrelated sensitive Actuator endpoints remain inaccessible.

## Cardinality and privacy

Custom labels come only from the fixed values above. No player/deposit/round/bonus/ledger IDs, request IDs, arbitrary error text, raw URLs, signatures, secrets or payloads become metric labels. Framework HTTP metrics use mapped route templates such as `/api/demo/deposits/{depositId}/complete`, not a separate series for every deposit. Prometheus adds the fixed scrape `job` and `instance` labels.

Business identifiers remain in the existing safe logs where appropriate. Request and trace identifiers are explained below; none is a metric label. No generic `transactionId` is introduced.

## Centralized logs

The optional backend profile uses Spring Boot 3.5's built-in **ECS JSON** console format. No third-party encoder, direct Elasticsearch appender or logging framework was added. Normal DEV/demo retains its existing text format. Existing event names and sanitized technical errors are preserved.

Indexed backend fields include `@timestamp`, `service.name=backend`, `log.level`, `log.logger`, `message`, `request_id`, `trace_id` and `span_id`. Filebeat extracts `event_name` from the existing `event=...` message prefix. Safe business identifiers remain in the message. Expected rejections remain INFO/WARN; technical failures retain one ERROR with sanitized exception types/frames.

Nginx access logs are JSON on stdout with `service.name=frontend`, `log.level`, `event_name=http_request`, `request_id`, method/path/status, duration in seconds and upstream duration. Query parameters, headers and request bodies are excluded. Native Nginx errors remain on stderr and receive service metadata from discovery.

Filebeat was selected for its native Docker autodiscovery and Compose-label filtering. It reads Docker `json-file` logs from **only this Compose project's backend/frontend containers**, including containers recreated later. There are no hardcoded container IDs or generated names. JSON is decoded into fields; a field allowlist avoids indexing unnecessary Docker metadata. Daily data streams use `casino-wallet-logs-*` with Elasticsearch-managed backing indices; correlation fields have keyword mappings.

The socket and container log directory are mounted read-only. Filebeat runs as root to read them, and stores its registry, a bounded **256 MB disk queue** and two rotating 1 MiB event-error diagnostic files in `filebeat_data`. Docker socket access is nevertheless powerful: a read-only bind does not make Docker API methods read-only. This arrangement is for a trusted local Docker Desktop/Linux demo, not an isolation boundary. No socket is published over HTTP, and unrelated containers are not selected for harvesting.

Kibana is configured to reach `http://elasticsearch:9200` automatically. Open **Discover**, switch to **ES|QL**, and query without creating a data view:

```esql
FROM casino-wallet-logs-*
| WHERE service.name == "backend"
| SORT @timestamp DESC
| KEEP @timestamp, log.level, event_name, message, request_id, trace_id, span_id
| LIMIT 50
```

Useful replacement filters (substitute an actual ID):

```esql
| WHERE request_id == "your-request-id"
| WHERE trace_id == "your-trace-id"
| WHERE service.name == "frontend"
| WHERE event_name == "round_completed"
| WHERE event_name == "api_rejected"
| WHERE service.name == "backend" AND log.level == "ERROR"
```

Use one filter or combine conditions as needed. An absent ERROR is normal when no technical failure occurred. No Kibana setup container or custom Kibana dashboard is required.

## Tracing and correlation

Spring Boot manages `micrometer-tracing-bridge-otel` **1.5.12** and `opentelemetry-exporter-otlp` **1.49.0**. The base configuration disables tracing/export; `application-observability.yml` enables W3C propagation and **100% local sampling**. A valid incoming `traceparent` continues its parent trace; otherwise Spring creates a new context. No browser SDK, broad header capture or baggage-to-MDC mapping is enabled.

Backend export uses **OTLP HTTP/protobuf** to `http://otel-collector:4318/v1/traces`. Boot's asynchronous batch processor has a 512-span queue, 128-span batches and a five-second schedule, with bounded export/connect timeouts. Telemetry can be dropped during an outage; requests never wait for delivery or roll back because of it. SDK export failure diagnostics are allowed to report a bounded failed batch; they contain no financial payload.

The Collector receives OTLP/HTTP, applies a 128 MiB memory limiter and batching, and exports to `http://jaeger:4318`. Its queue is bounded and retries expire after 60 seconds. Its health endpoint is internal port 13133. Jaeger uses an in-memory store capped at 10,000 traces; traces disappear when Jaeger restarts. Its own query-service tracing is [disabled with `OTEL_TRACES_SAMPLER=always_off`](https://www.jaegertracing.io/docs/2.21/operations/monitoring/); application trace ingestion remains enabled. Neither uses Elasticsearch or financial PostgreSQL for traces.

Each traced HTTP request has a SERVER span. Two fixed INTERNAL child spans observe the existing transactional service proxy through the controller boundary: `casino.wallet.deposit.complete` and `casino.wallet.round.play`. They include validation/commit time and a controlled outcome. They do not move the transaction boundary, add transactions or change rollback/locking. A completed outcome is recorded after proxy commit; controlled rejections are distinct from technical failures.

`TracePrivacyFilter` uses Boot's supported Micrometer export filter to allow only method, route template, status, outcome, exception type and operation tags. Raw URLs/query values and other attributes are removed **before export**; exception event payloads and status descriptions are stripped. Failed status remains visible. There are **no JDBC spans**, SQL capture, datasource proxies or spans around every repository call.

| Identifier | Purpose |
|---|---|
| `X-Request-ID` / `request_id` | Existing response/support correlation; propagated through Nginx/backend. |
| `trace_id` | W3C distributed trace identity, searchable in logs and Jaeger. |
| `span_id` | Current operation within that trace. |
| Existing business IDs | Identify the deposit, round, bonus or ledger reference in safe business logs. |

The request filter runs inside Spring's HTTP observation scope, so completion logs contain both request and trace correlation; its MDC cleanup remains in `finally`. To correlate a request, supply an `X-Request-ID`, confirm the response header, search that ID in Kibana, copy `trace_id`, and open `http://localhost:16686/trace/<trace_id>`. IDs do not appear in business response bodies or financial tables.

## Dashboard

The single **Casino Wallet · Operations** dashboard is provisioned from `observability/grafana/dashboards/casino-wallet.json`:

- Backend scrape status and PostgreSQL-inclusive readiness.
- Application `/api` request rate, 4xx/5xx rate, mean and p95 latency.
- JVM heap usage/maximum, process CPU and live threads.
- Hikari active/idle/pending/max connections and connection-timeout rate.
- Deposit, round and bonus-lifecycle outcome counters since backend start.

Application HTTP panels exclude healthcheck/scrape traffic. Rates need at least two scrapes; allow 30–45 seconds after startup. Latency is absent until there is application traffic. Error rates display zero when no corresponding error series exists; check scrape/readiness before interpreting zero errors. Business counters can legitimately remain zero until the relevant actions occur.

Safe sample traffic on a new demo wallet: create and demo-complete a `20.00` deposit, retry that completion, play `4.00` with payout `0.00`, then try `5.01` while the bonus is active. Expect one completion, one duplicate, one completed round and one rejected round. This persists legitimate demo history. Do not change database rows to manufacture metrics.

## Shutdown and data

Stop only monitoring while Casino Wallet continues running:

```bash
./scripts/start-observability.sh stop
```

This stops only Prometheus, Grafana, Elasticsearch, Kibana, Filebeat, Collector and Jaeger. Containers, images and all data volumes are preserved. The backend keeps its observability profile and remains usable; asynchronous export failures may be logged while monitoring is stopped. No backend recreation or database reset is performed.

Native monitoring-only stop:

```bash
docker compose -f compose.yaml -f compose.observability.yaml --profile observability \
  stop prometheus grafana elasticsearch kibana filebeat otel-collector jaeger
```

Start again to restore monitoring; Compose reconciles the existing application without resetting data:

```bash
./scripts/start-observability.sh start
```

To stop the Casino Wallet application too, use `./scripts/start-demo.sh stop`. This removes project containers/network while preserving volumes and images. The native complete-stack shutdown remains:

```bash
docker compose -f compose.yaml -f compose.observability.yaml --profile observability down
```

`prometheus_data`, `grafana_data`, `elasticsearch_data` and `filebeat_data` are separate Compose-managed named volumes. They do not use `postgres_data`. Prometheus retention is bounded to **7 days / 256 MB**; WAL/head data and overhead can exceed that size temporarily. Grafana stores SQLite state in its own volume. Elasticsearch stores daily log data streams and Kibana saved state; daily naming is not automatic retention. This small local demo has no ILM policy: intentionally delete old demo logs/monitoring volumes when needed. Production needs a defined lifecycle and storage budget.

To intentionally delete **only monitoring data**, first capture the actual named volumes from this project's existing monitoring containers, even if stopped:

```bash
prometheus_volume=$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/prometheus"}}{{.Name}}{{end}}{{end}}' "$(docker compose --profile observability ps --all --quiet prometheus)")
grafana_volume=$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/var/lib/grafana"}}{{.Name}}{{end}}{{end}}' "$(docker compose --profile observability ps --all --quiet grafana)")
elasticsearch_volume=$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/usr/share/elasticsearch/data"}}{{.Name}}{{end}}{{end}}' "$(docker compose --profile observability ps --all --quiet elasticsearch)")
filebeat_volume=$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/usr/share/filebeat/data"}}{{.Name}}{{end}}{{end}}' "$(docker compose --profile observability ps --all --quiet filebeat)")
docker compose --profile observability stop prometheus grafana elasticsearch kibana filebeat otel-collector jaeger
docker compose --profile observability rm -f prometheus grafana elasticsearch kibana filebeat otel-collector jaeger
docker volume rm "$prometheus_volume" "$grafana_volume" "$elasticsearch_volume" "$filebeat_volume"
```

Run this recipe while all monitoring containers still exist, before a full `down`. The mount destinations select only their own data volumes; project/volume names are not hardcoded. PostgreSQL remains untouched. No image deletion or global pruning is needed. Existing retained container logs may be re-ingested after Filebeat's registry is deleted.

The observability helper intentionally has no `reset`/cleanup command. Do **not** use `down -v` or `start-demo.sh reset` for monitoring-only cleanup: those are whole-project disposable-data operations and also delete the financial PostgreSQL volume. Normal shutdown preserves volumes and application images.

## Troubleshooting and verification

```bash
docker compose -f compose.yaml -f compose.observability.yaml --profile observability config --quiet
docker compose --profile observability ps --all
docker compose --profile observability logs --tail=60 filebeat elasticsearch kibana otel-collector jaeger
docker compose --profile observability exec prometheus promtool check config /etc/prometheus/prometheus.yml
```

In Prometheus, check the `casino-wallet` target and query `up{job="casino-wallet"}`, `casino_wallet_readiness`, `jvm_memory_used_bytes` and `casino_wallet_rounds_total`. A healthy container alone does not prove its target is UP. Check `backend:8080`, the scrape path, backend health and logs if scraping fails.

Grafana should open the dashboard immediately. Its datasource is provisioned automatically; no import/setup wizard is necessary. If there is no data, allow two scrapes, generate application traffic and inspect Prometheus before changing Grafana. Provisioned file changes are polled every 30 seconds. Port conflicts can be resolved with the explicit optional port overrides.

For logs, check Elasticsearch `/_cluster/health`, `/_cat/indices/casino-wallet-logs-*`, Filebeat logs and Kibana `/api/status`. Filebeat health checks its output connection; verify actual indexed documents as well. For traces, check Collector logs and Jaeger service `casino-wallet`; allow asynchronous batching before searching. Health alone is not proof of log/trace delivery. A Collector outage may lose spans; an Elasticsearch outage leaves the app unaffected while Filebeat buffers/retries.

The existing `./scripts/verify.sh` still runs backend tests/build, frontend tests/build and Compose validation. Focused tests additionally verify metric registration, bounded tags, failure isolation, the exposition endpoint, database readiness and post-commit semantics using real PostgreSQL rollback cases. The Prometheus endpoint test explicitly enables Spring Boot's test-time metrics export; other tests need no running monitoring services.

Tracing tests opt into the observability profile with a recording exporter: they verify real HTTP/child spans, W3C continuity, correlated JSON, sanitized errors and default-disabled export. Collector/Elasticsearch outages and recovery are also checked against the real Compose stack; a successful healthcheck alone does not replace that smoke test.

## Production caveats and boundary

This local profile is not a complete production monitoring platform. Elasticsearch/Kibana security is disabled and Grafana uses anonymous Viewer access. Restrict management endpoints/networks, configure authentication/authorization and TLS, and reconsider privileged shipper access. Production also needs log retention/ILM, Elasticsearch sizing/backups, trace sampling, durable Jaeger storage, Collector capacity and Prometheus HA decisions. Do not expose these loopback-only defaults publicly as-is. Metrics/logs/traces cannot replace financial audit records; telemetry loss does not invalidate a committed operation.

This stage adds no financial schema/API changes, generic transaction ID, browser tracing SDK, alerting service, Kafka or Redis. Any further observability work needs separate approval.
