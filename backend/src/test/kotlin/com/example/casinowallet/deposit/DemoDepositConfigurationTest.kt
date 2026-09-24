package com.example.casinowallet.deposit

import com.example.casinowallet.deposit.application.DepositApplicationService
import com.example.casinowallet.deposit.web.DemoDepositController
import com.example.casinowallet.observability.CasinoWalletMetrics
import com.example.casinowallet.observability.FinancialTracing
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.tracing.Tracer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class DemoDepositConfigurationTest {
    @Test
    fun `demo completion is available for the assignment and can be explicitly disabled`() {
        val context = ApplicationContextRunner()
            .withBean(DepositApplicationService::class.java, { mock(DepositApplicationService::class.java) })
            .withBean(CasinoWalletMetrics::class.java, { CasinoWalletMetrics(SimpleMeterRegistry(), FinancialTracing(Tracer.NOOP, false)) })
            .withUserConfiguration(DemoDepositController::class.java)
        context.run { assertThat(it).hasSingleBean(DemoDepositController::class.java) }
        context.withPropertyValues("demo.enabled=false").run { assertThat(it).doesNotHaveBean(DemoDepositController::class.java) }
    }
}
