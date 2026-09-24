package com.example.casinowallet.observability

import com.example.casinowallet.bonus.domain.BonusStatus
import com.example.casinowallet.deposit.application.DepositAmountMismatchException
import com.example.casinowallet.deposit.application.DepositCompletion
import com.example.casinowallet.deposit.application.DepositNotFoundException
import com.example.casinowallet.round.application.InsufficientFundsException
import com.example.casinowallet.round.application.MaxBetExceededException
import com.example.casinowallet.round.application.RoundResult
import com.example.casinowallet.wallet.application.WalletBalanceLimitException
import com.example.casinowallet.web.ApiRequestException
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

enum class DepositMetricOperation(val tag: String) {
    PROVIDER_CALLBACK("provider_callback"), DEMO_COMPLETION("demo_completion")
}

@Component
class CasinoWalletMetrics(private val registry: MeterRegistry, private val tracing: FinancialTracing) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val failureLogged = AtomicBoolean()

    init {
        // Register the finite series before traffic, so dashboards also show zero outcomes.
        for (operation in DepositMetricOperation.entries) {
            for (outcome in listOf("completed", "duplicate", "rejected", "failed")) {
                safely { registry.counter(DEPOSITS, "operation", operation.tag, "outcome", outcome) }
            }
        }
        for (outcome in listOf("completed", "rejected", "failed")) {
            safely { registry.counter(ROUNDS, "outcome", outcome) }
        }
        for (outcome in listOf("completed", "expired")) {
            safely { registry.counter(BONUS, "outcome", outcome) }
        }
    }

    // Called only at the controller boundary: the action includes the transactional service proxy.
    fun depositCallback(operation: DepositMetricOperation, action: () -> DepositCompletion): DepositCompletion =
        tracing.observe(FinancialTraceOperation.DEPOSIT_COMPLETION) { depositOutcome(operation, action) }

    private fun depositOutcome(operation: DepositMetricOperation, action: () -> DepositCompletion): DepositCompletion = try {
        val result = action()
        val outcome = if (result.duplicate) "duplicate" else "completed"
        tracing.outcome(outcome)
        increment(DEPOSITS, "operation", operation.tag, "outcome", outcome)
        result
    } catch (exception: Exception) {
        tracing.outcome(failureOutcome(exception))
        increment(DEPOSITS, "operation", operation.tag, "outcome", failureOutcome(exception))
        throw exception
    }

    fun round(action: () -> RoundResult): RoundResult = tracing.observe(FinancialTraceOperation.ROUND_PLAY) { roundOutcome(action) }

    private fun roundOutcome(action: () -> RoundResult): RoundResult = try {
        val result = action()
        tracing.outcome("completed")
        increment(ROUNDS, "outcome", "completed")
        result
    } catch (exception: Exception) {
        tracing.outcome(failureOutcome(exception))
        increment(ROUNDS, "outcome", failureOutcome(exception))
        throw exception
    }

    // The lifecycle caller invokes this only from its existing afterCommit synchronization.
    fun bonusLifecycle(status: BonusStatus) {
        val outcome = when (status) {
            BonusStatus.COMPLETED -> "completed"
            BonusStatus.EXPIRED -> "expired"
            BonusStatus.ACTIVE -> return
        }
        increment(BONUS, "outcome", outcome)
    }

    private fun failureOutcome(exception: Exception): String = when (exception) {
        is ApiRequestException -> if (exception.status.is4xxClientError) "rejected" else "failed"
        is DepositAmountMismatchException, is DepositNotFoundException, is WalletBalanceLimitException,
        is InsufficientFundsException, is MaxBetExceededException -> "rejected"
        else -> "failed"
    }

    private fun increment(name: String, vararg tags: String) = safely { registry.counter(name, *tags).increment() }

    private fun safely(record: () -> Unit) {
        try {
            record()
        } catch (_: RuntimeException) {
            // Metrics cannot turn a committed financial result into an HTTP failure. No exception payload is logged.
            if (failureLogged.compareAndSet(false, true)) log.warn("event=business_metrics_unavailable")
        }
    }

    companion object {
        private const val DEPOSITS = "casino.wallet.deposit.callbacks"
        private const val ROUNDS = "casino.wallet.rounds"
        private const val BONUS = "casino.wallet.bonus.lifecycle"
    }
}
