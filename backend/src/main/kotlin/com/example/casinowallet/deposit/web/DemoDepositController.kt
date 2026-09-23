package com.example.casinowallet.deposit.web

import com.example.casinowallet.deposit.application.DepositApplicationService
import com.example.casinowallet.deposit.application.DepositBalanceLimit
import com.example.casinowallet.deposit.application.DepositCompletion
import com.example.casinowallet.deposit.domain.DepositStatus
import com.example.casinowallet.wallet.application.WalletBalanceLimitException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

// Reviewer convenience only; disable with demo.enabled=false outside this assignment's demo.
@RestController
@ConditionalOnProperty(name = ["demo.enabled"], havingValue = "true", matchIfMissing = true)
class DemoDepositController(private val service: DepositApplicationService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/api/demo/deposits/{depositId}/complete")
    fun complete(@PathVariable depositId: UUID): CallbackResponse {
        val result = when (val outcome = service.completeDemo(depositId)) {
            is DepositCompletion -> outcome
            DepositBalanceLimit -> throw WalletBalanceLimitException()
        }
        log.info("event=demo_deposit_completed deposit_id={} duplicate={}", result.depositId, result.duplicate)
        if (result.bonusGranted) log.info("event=welcome_bonus_granted deposit_id={}", result.depositId)
        return CallbackResponse(result.depositId, DepositStatus.COMPLETED)
    }
}
