package com.example.casinowallet.observability

import com.example.casinowallet.bonus.domain.BonusStatus
import com.example.casinowallet.deposit.application.DepositCompletion
import com.example.casinowallet.round.application.InsufficientFundsException
import com.example.casinowallet.round.application.RoundResult
import com.example.casinowallet.round.domain.GameRound
import com.example.casinowallet.round.domain.RoundAllocation
import com.example.casinowallet.wallet.domain.WalletSummary
import com.example.casinowallet.web.ApiRequestException
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.AbstractMeter
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.tracing.Tracer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.util.UUID

class CasinoWalletMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val tracing = FinancialTracing(Tracer.NOOP, false)
    private val metrics = CasinoWalletMetrics(registry, tracing)

    @Test
    fun `meters have only the finite operation and outcome tag sets`() {
        assertThat(registry.meters).hasSize(13)
        assertThat(registry.meters.map { it.id.name }.toSet()).containsExactlyInAnyOrder(
            "casino.wallet.deposit.callbacks", "casino.wallet.rounds", "casino.wallet.bonus.lifecycle",
        )
        for (meter in registry.meters) {
            assertThat(meter.id.tags.map { it.key }).isSubsetOf("operation", "outcome")
            assertThat(meter.id.tags.map { it.value }).isSubsetOf(
                "provider_callback", "demo_completion", "completed", "duplicate", "rejected", "failed", "expired",
            )
            assertThat((meter as Counter).count()).isZero()
        }
    }

    @Test
    fun `deposit completion duplicate and rejection are distinct outcomes without identity labels`() {
        val completed = DepositCompletion(UUID.randomUUID(), duplicate = false)
        assertThat(metrics.depositCallback(DepositMetricOperation.PROVIDER_CALLBACK) { completed }).isSameAs(completed)
        metrics.depositCallback(DepositMetricOperation.PROVIDER_CALLBACK) { completed.copy(duplicate = true) }
        assertThrows<ApiRequestException> {
            metrics.depositCallback(DepositMetricOperation.PROVIDER_CALLBACK) {
                throw ApiRequestException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "sensitive payload")
            }
        }
        for (outcome in listOf("completed", "duplicate", "rejected")) {
            assertThat(depositCount("provider_callback", outcome)).isEqualTo(1.0)
            assertThat(depositCount("demo_completion", outcome)).isZero()
        }
        assertThat(registry.meters).hasSize(13)
        assertThat(registry.meters.flatMap { it.id.tags }.map { it.value })
            .doesNotContain(completed.depositId.toString(), "sensitive payload", "INVALID_SIGNATURE")
    }

    @Test
    fun `demo completion has a separate bounded operation tag`() {
        metrics.depositCallback(DepositMetricOperation.DEMO_COMPLETION) { DepositCompletion(UUID.randomUUID(), false) }
        assertThat(depositCount("demo_completion", "completed")).isEqualTo(1.0)
        assertThat(depositCount("provider_callback", "completed")).isZero()
    }

    @Test
    fun `round outcomes count once and propagate the same failure`() {
        val result = roundResult()
        assertThat(metrics.round { result }).isSameAs(result)
        assertThrows<InsufficientFundsException> { metrics.round { throw InsufficientFundsException() } }
        val failure = IllegalStateException("private technical details")
        assertThat(assertThrows<IllegalStateException> { metrics.round { throw failure } }).isSameAs(failure)
        for (outcome in listOf("completed", "rejected", "failed")) {
            assertThat(registry.get("casino.wallet.rounds").tag("outcome", outcome).counter().count()).isEqualTo(1.0)
        }
    }

    @Test
    fun `lifecycle counters distinguish completion from expiration`() {
        metrics.bonusLifecycle(BonusStatus.COMPLETED)
        metrics.bonusLifecycle(BonusStatus.EXPIRED)
        metrics.bonusLifecycle(BonusStatus.EXPIRED)
        assertThat(registry.get("casino.wallet.bonus.lifecycle").tag("outcome", "completed").counter().count()).isEqualTo(1.0)
        assertThat(registry.get("casino.wallet.bonus.lifecycle").tag("outcome", "expired").counter().count()).isEqualTo(2.0)
    }

    @Test
    fun `meter registration failure cannot replace an operation result`() {
        val broken = SimpleMeterRegistry().apply {
            config().meterFilter(object : MeterFilter {
                override fun map(id: Meter.Id): Meter.Id = throw IllegalStateException("private registry detail")
            })
        }
        val isolated = CasinoWalletMetrics(broken, tracing)
        val result = roundResult()
        assertThat(isolated.round { result }).isSameAs(result)
    }

    private fun depositCount(operation: String, outcome: String): Double = registry.get("casino.wallet.deposit.callbacks")
        .tags("operation", operation, "outcome", outcome).counter().count()

    @Test
    fun `counter increment failure cannot replace committed success or the original rejection`() {
        val broken = object : SimpleMeterRegistry() {
            override fun newCounter(id: Meter.Id): Counter = object : AbstractMeter(id), Counter {
                override fun increment(amount: Double): Unit = throw IllegalStateException("private counter detail")
                override fun count(): Double = 0.0
            }
        }
        val isolated = CasinoWalletMetrics(broken, tracing)
        val result = roundResult()
        assertThat(isolated.round { result }).isSameAs(result)
        val rejected = InsufficientFundsException()
        assertThat(assertThrows<InsufficientFundsException> { isolated.round { throw rejected } }).isSameAs(rejected)
    }

    private fun roundResult(): RoundResult {
        val zero = BigDecimal("0.00")
        val stake = BigDecimal("8.00")
        return RoundResult(GameRound(UUID.randomUUID(), UUID.randomUUID(), stake, zero,
            RoundAllocation(stake, zero, zero, zero)), WalletSummary(BigDecimal("2.00"), zero))
    }
}
