package com.example.casinowallet.deposit.web

import com.example.casinowallet.deposit.application.DepositApplicationService
import com.example.casinowallet.deposit.domain.Deposit
import com.example.casinowallet.deposit.domain.DepositStatus
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.RoundingMode
import java.util.UUID

@RestController
class DepositController(private val service: DepositApplicationService, private val parser: DepositRequestParser) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/api/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body: JsonNode): DepositResponse {
        val deposit = service.create(parser.parseAmount(body))
        log.info("event=deposit_created deposit_id={}", deposit.id)
        return DepositResponse.from(deposit)
    }
}

data class DepositResponse(val depositId: UUID, val amount: String, val status: DepositStatus) {
    companion object {
        fun from(deposit: Deposit): DepositResponse = DepositResponse(
            deposit.id, deposit.amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(), deposit.status,
        )
    }
}
