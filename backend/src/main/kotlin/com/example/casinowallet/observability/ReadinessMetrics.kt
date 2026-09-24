package com.example.casinowallet.observability

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.boot.actuate.health.Status
import org.springframework.stereotype.Component

@Component
class ReadinessMetrics(private val health: HealthEndpoint) : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        try {
            Gauge.builder("casino.wallet.readiness", health) { endpoint ->
                try {
                    if (endpoint.healthForPath("readiness")?.status == Status.UP) 1.0 else 0.0
                } catch (_: RuntimeException) {
                    0.0
                }
            }.description("Current Actuator readiness including PostgreSQL: 1 UP, 0 otherwise")
                .register(registry)
        } catch (_: RuntimeException) {
            LoggerFactory.getLogger(javaClass).warn("event=readiness_metric_unavailable")
        }
    }
}
