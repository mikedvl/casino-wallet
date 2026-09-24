# The upstream scratch image has no HTTP client for Docker healthchecks.
FROM busybox:1.37.0-musl AS healthcheck
FROM otel/opentelemetry-collector-contrib:0.161.0
COPY --from=healthcheck /bin/busybox /busybox
