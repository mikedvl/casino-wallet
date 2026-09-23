package com.example.casinowallet.deposit

import com.example.casinowallet.deposit.application.DepositApplicationService
import com.example.casinowallet.deposit.web.DemoDepositController
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class DemoDepositConfigurationTest {
    @Test
    fun `demo completion is available for the assignment and can be explicitly disabled`() {
        val context = ApplicationContextRunner()
            .withBean(DepositApplicationService::class.java, { mock(DepositApplicationService::class.java) })
            .withUserConfiguration(DemoDepositController::class.java)
        context.run { assertThat(it).hasSingleBean(DemoDepositController::class.java) }
        context.withPropertyValues("demo.enabled=false").run { assertThat(it).doesNotHaveBean(DemoDepositController::class.java) }
    }
}
