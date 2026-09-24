package com.example.casinowallet.observability

import io.micrometer.tracing.Tracer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

enum class FinancialTraceOperation(val spanName: String) {
    DEPOSIT_COMPLETION("casino.wallet.deposit.complete"),
    ROUND_PLAY("casino.wallet.round.play"),
}

@Component
class FinancialTracing(
    private val tracer: Tracer,
    @param:Value("\${management.tracing.enabled:false}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val failureLogged = AtomicBoolean()

    // The caller supplies the existing controller action, including its transactional proxy.
    fun <T> observe(operation: FinancialTraceOperation, action: () -> T): T {
        if (!enabled) return action()
        val span = safely { tracer.nextSpan().name(operation.spanName).start() }
        val scope = safely { if (span == null) null else tracer.withSpan(span) }
        try {
            return action()
        } finally {
            safely { scope?.close() }
            safely { span?.end() }
        }
    }

    fun outcome(outcome: String) {
        if (!enabled) return
        safely {
            val span = tracer.currentSpan()
            span?.tag("outcome", outcome)
            // Only a fixed diagnostic error; never attach the original exception/payload.
            if (outcome == "failed") span?.error(IllegalStateException("Operation failed"))
        }
    }

    private fun <T> safely(action: () -> T): T? = try {
        action()
    } catch (_: RuntimeException) {
        if (failureLogged.compareAndSet(false, true)) log.warn("event=tracing_unavailable")
        null
    }
}
