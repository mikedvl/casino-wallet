package com.example.casinowallet

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.client.exchange
import org.springframework.boot.test.web.client.getForEntity
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.annotation.DirtiesContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.util.UUID

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@AutoConfigureObservability(tracing = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BootstrapIntegrationTest @Autowired constructor(
    private val http: TestRestTemplate,
    private val jdbc: JdbcTemplate,
) {
    @Test
    fun `infrastructure exposes health and only approved actuator endpoints`() {
        assertThat(jdbc.queryForObject<Int>(
            // language=PostgreSQL
            "select 1",
        )).isEqualTo(1)

        for (path in listOf("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            val response = http.getForEntity<JsonNode>(path)
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val health = checkNotNull(response.body) { "Expected a health response body for $path" }
            assertThat(health.path("status").asText()).isEqualTo("UP")
            assertThat(health.has("components")).isFalse()
            assertThat(health.has("details")).isFalse()
        }
        val metrics = http.getForEntity<JsonNode>("/actuator/metrics")
        assertThat(metrics.statusCode).isEqualTo(HttpStatus.OK)
        val metricsBody = checkNotNull(metrics.body) { "Expected a metrics response body" }
        assertThat(metricsBody.path("names").map { it.asText() }).contains("jvm.memory.used")
        assertThat(http.getForEntity<String>("/actuator/info").statusCode).isEqualTo(HttpStatus.OK)

        for (endpoint in listOf("env", "configprops", "beans", "heapdump", "threaddump")) {
            assertThat(http.getForEntity<String>("/actuator/$endpoint").statusCode)
                .isEqualTo(HttpStatus.NOT_FOUND)
        }
    }

    @Test
    fun `prometheus exposes framework and bounded application metrics`() {
        val response = http.getForEntity<String>("/actuator/prometheus")
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.contentType.toString()).contains("text/plain")
        assertThat(response.body).contains("jvm_memory_used_bytes", "casino_wallet_rounds_total",
            "casino_wallet_readiness 1.0")
    }

    @Test
    fun `request ids are generated propagated and bounded`() {
        val generated = checkNotNull(
            http.getForEntity<String>("/actuator/health/liveness").headers.getFirst("X-Request-ID"),
        ) { "Expected a generated X-Request-ID header" }
        assertThat(UUID.fromString(generated).toString()).isEqualTo(generated)

        for (supplied in listOf("review.request-123", "x".repeat(128), "unsafe/id", "x".repeat(129))) {
            val headers = HttpHeaders().apply { set("X-Request-ID", supplied) }
            val response = http.exchange<String>(
                "/actuator/health/liveness", HttpMethod.GET, HttpEntity<Void>(headers),
            )
            val actual = checkNotNull(response.headers.getFirst("X-Request-ID")) {
                "Expected an X-Request-ID response header"
            }
            if (supplied.length <= 128 && '/' !in supplied) {
                assertThat(actual).isEqualTo(supplied)
            } else {
                assertThat(actual).isNotEqualTo(supplied)
                assertThat(UUID.fromString(actual).toString()).isEqualTo(actual)
            }
        }
    }

    @Test
    fun `database outage affects readiness but not liveness and recovers`() {
        postgres.dockerClient.pauseContainerCmd(postgres.containerId).exec()
        try {
            val readiness = http.getForEntity<JsonNode>("/actuator/health/readiness")
            assertThat(readiness.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            val readinessBody = checkNotNull(readiness.body) { "Expected a readiness response body" }
            assertThat(readinessBody.path("status").asText()).isEqualTo("DOWN")
            val liveness = http.getForEntity<JsonNode>("/actuator/health/liveness")
            assertThat(liveness.statusCode).isEqualTo(HttpStatus.OK)
            val livenessBody = checkNotNull(liveness.body) { "Expected a liveness response body" }
            assertThat(livenessBody.path("status").asText()).isEqualTo("UP")
            assertThat(http.getForEntity<String>("/actuator/prometheus").body).contains("casino_wallet_readiness 0.0")
        } finally {
            postgres.dockerClient.unpauseContainerCmd(postgres.containerId).exec()
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertThat(http.getForEntity<String>("/actuator/health/readiness").statusCode)
                .isEqualTo(HttpStatus.OK)
        }
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16.15-alpine3.23")

        @DynamicPropertySource
        @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                val url = postgres.jdbcUrl
                "$url${if ('?' in url) '&' else '?'}connectTimeout=2&socketTimeout=3"
            }
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
