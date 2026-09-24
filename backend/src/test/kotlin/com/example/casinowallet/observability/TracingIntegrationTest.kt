package com.example.casinowallet.observability

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.tracing.Tracer
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ActiveProfiles("observability")
@AutoConfigureObservability
@ExtendWith(OutputCaptureExtension::class)
@Import(TracingIntegrationTest.ExportConfiguration::class, TracingIntegrationTest.FailureController::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "management.otlp.tracing.export.enabled=false", "spring.flyway.clean-disabled=false",
    "payment.provider.hmac-secret=trace-test-secret",
])
class TracingIntegrationTest @Autowired constructor(
    private val http: TestRestTemplate,
    private val mapper: ObjectMapper,
    private val provider: SdkTracerProvider,
    private val exporter: RecordingExporter,
    private val tracer: Tracer,
    private val flyway: Flyway,
) {
    @BeforeEach
    fun reset() {
        provider.forceFlush().join(10, TimeUnit.SECONDS)
        exporter.spans.clear()
        flyway.clean()
        flyway.migrate()
    }

    @Test
    fun `request completion JSON includes independent request and generated trace context`(output: CapturedOutput) {
        val response = request("/api/wallet", "tracing-generated")
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.getFirst("X-Request-ID")).isEqualTo("tracing-generated")
        val event = requestLog(output, "tracing-generated")
        val traceId = event.path("trace_id").asText()
        assertThat(traceId).matches("[0-9a-f]{32}").isNotEqualTo("0".repeat(32))
        assertThat(event.path("span_id").asText()).matches("[0-9a-f]{16}")
        assertThat(event.path("service").path("name").asText()).isEqualTo("backend")
        val server = spans().single { it.traceId == traceId && it.kind == SpanKind.SERVER }
        assertThat(server.spanId).isEqualTo(event.path("span_id").asText())
    }

    @Test
    fun `W3C parent is preserved without exporting raw URL query or headers`(output: CapturedOutput) {
        val traceId = "1234567890abcdef1234567890abcdef"
        val parent = "1234567890abcdef"
        val response = request("/api/wallet?private=sensitive-query", "tracing-parent", headers = mapOf(
            "traceparent" to "00-$traceId-$parent-01", "Authorization" to "Bearer sensitive-authorization",
        ))
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(requestLog(output, "tracing-parent").path("trace_id").asText()).isEqualTo(traceId)
        val span = spans().single { it.traceId == traceId && it.kind == SpanKind.SERVER }
        assertThat(span.parentSpanId).isEqualTo(parent)
        assertThat(span.spanId).isNotEqualTo(parent)
        assertThat(span.attributes.asMap().keys.map { it.key }).doesNotContain("http.url", "url.full")
        assertThat(span.toString() + output.all).doesNotContain("sensitive-query", "sensitive-authorization")
    }

    @Test
    fun `financial child spans surround committed completion and distinguish controlled rejection`(output: CapturedOutput) {
        val created = request("/api/deposits", "tracing-deposit", """{"amount":"10.00"}""")
        val id = checkNotNull(created.body).path("depositId").asText()
        val completed = request("/api/demo/deposits/$id/complete", "tracing-complete", "")
        assertThat(completed.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(request("/api/rounds/play", "tracing-round", """{"stake":"8.00","totalWin":"0.00"}""").statusCode)
            .isEqualTo(HttpStatus.OK)
        val rejected = request("/api/rounds/play", "tracing-rejected", """{"stake":"8.00","totalWin":"0.00"}""")
        assertThat(rejected.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(checkNotNull(rejected.body).path("code").asText()).isEqualTo("INSUFFICIENT_FUNDS")
        assertThat(checkNotNull(request("/api/wallet", "tracing-balance").body).path("realBalance").asText()).isEqualTo("2.00")
        val finished = spans()
        for ((requestId, name, outcome) in listOf(
            Triple("tracing-complete", "casino.wallet.deposit.complete", "completed"),
            Triple("tracing-round", "casino.wallet.round.play", "completed"),
            Triple("tracing-rejected", "casino.wallet.round.play", "rejected"),
        )) {
            val event = requestLog(output, requestId)
            val child = finished.single { it.traceId == event.path("trace_id").asText() && it.name == name }
            assertThat(child.parentSpanId).isEqualTo(event.path("span_id").asText())
            assertThat(child.attributes.asMap().entries.single { it.key.key == "outcome" }.value).isEqualTo(outcome)
            assertThat(child.status.statusCode).isNotEqualTo(StatusCode.ERROR)
        }
        assertThat(output.all).doesNotContain("event=api_failure")
    }

    @Test
    fun `structured callback and technical errors retain correlation without sensitive messages`(output: CapturedOutput) {
        val rejected = request("/api/provider/deposits/callback", "tracing-signature",
            """{"private":"sensitive-raw-callback"}""", mapOf(
                "X-Signature" to "sensitive-signature", "Authorization" to "sensitive-authorization",
            ))
        assertThat(rejected.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(request("/test/trace-failure", "tracing-failure").statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        val failures = logs(output).filter { it.path("message").asText().startsWith("event=api_failure") }
        assertThat(failures).hasSize(1)
        assertThat(failures.single().path("log").path("level").asText()).isEqualTo("ERROR")
        assertThat(failures.single().path("request_id").asText()).isEqualTo("tracing-failure")
        assertThat(failures.single().path("trace_id").asText()).matches("[0-9a-f]{32}")
        assertThat(output.all + spans().joinToString()).doesNotContain(
            "trace-test-secret", "trace-test-db-password", "sensitive-signature", "sensitive-authorization",
            "sensitive-raw-callback", "sensitive-sql",
        )
        assertThat(output.all).contains("SQLException")
    }

    @Test
    fun `export filter strips sensitive span attributes exception events and error descriptions`() {
        val span = tracer.nextSpan().name("privacy-probe").start()
        span.tag("http.url", "http://local/?sensitive-url")
        span.tag("playerId", "sensitive-player")
        span.tag("outcome", "failed")
        span.error(SQLException("sensitive-sql"))
        span.end()
        val exported = spans().single { it.name == "privacy-probe" }
        assertThat(exported.status.statusCode).isEqualTo(StatusCode.ERROR)
        assertThat(exported.status.description).isEmpty()
        assertThat(exported.events).isEmpty()
        assertThat(exported.attributes.asMap().keys.map { it.key }).containsExactly("outcome")
        assertThat(exported.toString()).doesNotContain("sensitive-")
    }

    private fun spans(): List<SpanData> {
        assertThat(provider.forceFlush().join(10, TimeUnit.SECONDS).isSuccess).isTrue()
        return exporter.spans.toList()
    }

    private fun logs(output: CapturedOutput): List<JsonNode> = output.all.lineSequence()
        .filter { it.startsWith("{") }.map { mapper.readTree(it) }.toList()

    private fun requestLog(output: CapturedOutput, requestId: String): JsonNode = logs(output).single {
        it.path("request_id").asText() == requestId && it.path("message").asText().startsWith("event=http_request")
    }

    private fun request(path: String, id: String, body: String? = null, headers: Map<String, String> = emptyMap()) =
        http.exchange(path, if (body == null) HttpMethod.GET else HttpMethod.POST,
            HttpEntity(body, HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                set("X-Request-ID", id)
                headers.forEach { (name, value) -> set(name, value) }
            }), JsonNode::class.java)

    @RestController
    class FailureController {
        @GetMapping("/test/trace-failure")
        fun fail(): Nothing = throw SQLException("sensitive-sql")
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ExportConfiguration {
        @Bean
        fun recordingExporter() = RecordingExporter()
    }

    class RecordingExporter : SpanExporter {
        val spans = CopyOnWriteArrayList<SpanData>()
        override fun export(spans: Collection<SpanData>): CompletableResultCode {
            this.spans.addAll(spans)
            return CompletableResultCode.ofSuccess()
        }
        override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16.15-alpine3.23")
            .apply { withPassword("trace-test-db-password") }

        @DynamicPropertySource
        @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
