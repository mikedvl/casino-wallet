package com.example.casinowallet.observability

import io.micrometer.tracing.exporter.FinishedSpan
import io.micrometer.tracing.exporter.SpanFilter
import io.micrometer.tracing.otel.bridge.OtelFinishedSpan
import io.opentelemetry.sdk.trace.data.DelegatingSpanData
import io.opentelemetry.sdk.trace.data.StatusData
import org.springframework.stereotype.Component

/** Sanitizes completed spans before Boot's asynchronous OTLP exporter sees them. */
@Component
class TracePrivacyFilter : SpanFilter {
    override fun map(span: FinishedSpan): FinishedSpan {
        span.setTags(span.tags.filterKeys { it in ALLOWED_TAGS })
        // Exception events may contain SQL, payloads or headers in their message/stack.
        // Safe exception types and outcomes remain in tags and sanitized application logs.
        span.setEvents(emptyList())
        val source = OtelFinishedSpan.toOtel(span)
        return OtelFinishedSpan.fromOtel(object : DelegatingSpanData(source) {
            override fun getStatus(): StatusData = StatusData.create(source.status.statusCode, "")
        })
    }

    companion object {
        private val ALLOWED_TAGS = setOf("method", "uri", "status", "outcome", "exception", "operation")
    }
}
