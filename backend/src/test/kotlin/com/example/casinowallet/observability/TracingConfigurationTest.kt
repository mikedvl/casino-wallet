package com.example.casinowallet.observability

import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class TracingConfigurationTest {
    @Test
    fun `default application configuration creates no OTLP exporter`() {
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(OtlpTracingAutoConfiguration::class.java))
            .run { context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(SpanExporter::class.java)
                assertThat(context.environment.getProperty("management.tracing.enabled")).isEqualTo("false")
                assertThat(context.environment.getProperty("management.otlp.tracing.export.enabled")).isEqualTo("false")
            }
    }

    @Test
    fun `tracer initialization failure cannot replace success or the original business error`() {
        val tracer = mock(Tracer::class.java)
        `when`(tracer.nextSpan()).thenThrow(IllegalStateException("private instrumentation failure"))
        val tracing = FinancialTracing(tracer, true)
        var calls = 0
        assertThat(tracing.observe(FinancialTraceOperation.ROUND_PLAY) { ++calls }).isEqualTo(1)
        assertThat(calls).isEqualTo(1)
        val failure = IllegalArgumentException("original failure")
        assertThat(assertThrows<IllegalArgumentException> {
            tracing.observe(FinancialTraceOperation.ROUND_PLAY) { throw failure }
        }).isSameAs(failure)
    }

    @Test
    fun `span completion failure cannot change an already completed operation`() {
        val tracer = mock(Tracer::class.java)
        val span = mock(Span::class.java)
        `when`(tracer.nextSpan()).thenReturn(span)
        `when`(span.name(FinancialTraceOperation.ROUND_PLAY.spanName)).thenReturn(span)
        `when`(span.start()).thenReturn(span)
        doThrow(IllegalStateException("private export failure")).`when`(span).end()
        val result = Any()
        assertThat(FinancialTracing(tracer, true).observe(FinancialTraceOperation.ROUND_PLAY) { result }).isSameAs(result)
    }
}
