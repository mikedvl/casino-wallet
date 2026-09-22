package com.example.casinowallet

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.util.UUID

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BootstrapIntegrationTest {
    @Autowired
    private lateinit var http: TestRestTemplate

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `bootstrap exposes healthy infrastructure without business tables`() {
        assertThat(jdbc.queryForObject("select 1", Int::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_schema = 'public'",
            Long::class.java,
        )).isZero()

        for (path in listOf("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            val response = http.getForEntity(path, JsonNode::class.java)
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body!!.path("status").asText()).isEqualTo("UP")
            assertThat(response.body!!.has("components")).isFalse()
            assertThat(response.body!!.has("details")).isFalse()
        }
        val metrics = http.getForEntity("/actuator/metrics", JsonNode::class.java)
        assertThat(metrics.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(metrics.body!!.path("names").map { it.asText() }).contains("jvm.memory.used")
        assertThat(http.getForEntity("/actuator/info", String::class.java).statusCode).isEqualTo(HttpStatus.OK)

        for (endpoint in listOf("env", "configprops", "beans", "heapdump", "prometheus")) {
            assertThat(http.getForEntity("/actuator/$endpoint", String::class.java).statusCode)
                .isEqualTo(HttpStatus.NOT_FOUND)
        }
        assertThat(http.getForEntity("/api/wallet", String::class.java).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `request ids are generated propagated and bounded`() {
        val generated = http.getForEntity("/actuator/health/liveness", String::class.java)
            .headers.getFirst("X-Request-ID")
        assertThat(UUID.fromString(generated).toString()).isEqualTo(generated)

        for (supplied in listOf("review.request-123", "x".repeat(128), "unsafe/id", "x".repeat(129))) {
            val headers = HttpHeaders().apply { set("X-Request-ID", supplied) }
            val response = http.exchange(
                "/actuator/health/liveness", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java,
            )
            val actual = response.headers.getFirst("X-Request-ID")
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
            val readiness = http.getForEntity("/actuator/health/readiness", JsonNode::class.java)
            assertThat(readiness.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            assertThat(readiness.body!!.path("status").asText()).isEqualTo("DOWN")
            val liveness = http.getForEntity("/actuator/health/liveness", JsonNode::class.java)
            assertThat(liveness.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(liveness.body!!.path("status").asText()).isEqualTo("UP")
        } finally {
            postgres.dockerClient.unpauseContainerCmd(postgres.containerId).exec()
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertThat(http.getForEntity("/actuator/health/readiness", String::class.java).statusCode)
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
