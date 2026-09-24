# Keep the upstream runtime; add only the static HTTP healthcheck client.
FROM busybox:1.37.0-musl AS healthcheck
FROM jaegertracing/jaeger:2.21.0
COPY --from=healthcheck /bin/busybox /busybox
