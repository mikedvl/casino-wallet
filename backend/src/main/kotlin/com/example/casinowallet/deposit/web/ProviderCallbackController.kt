package com.example.casinowallet.deposit.web

import com.example.casinowallet.deposit.application.DepositApplicationService
import com.example.casinowallet.deposit.domain.DepositStatus
import com.example.casinowallet.web.ApiRequestException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class ProviderCallbackController(
    private val signatures: ProviderSignatureVerifier,
    private val parser: DepositRequestParser,
    private val service: DepositApplicationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/api/provider/deposits/callback")
    fun complete(
        @RequestBody(required = false) body: ByteArray?,
        @RequestHeader("X-Signature", required = false) signature: String?,
    ): CallbackResponse {
        val rawBody = body ?: byteArrayOf()
        if (!signatures.isValid(rawBody, signature)) {
            throw ApiRequestException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Invalid provider signature")
        }
        val callback = parser.parseCallback(rawBody)
        val result = service.complete(callback.depositId, callback.amount)
        // The transactional service proxy has committed before a success is logged or returned.
        log.info("event=deposit_callback_completed deposit_id={} duplicate={}", result.depositId, result.duplicate)
        if (result.bonusGranted) log.info("event=welcome_bonus_granted deposit_id={}", result.depositId)
        return CallbackResponse(result.depositId, DepositStatus.COMPLETED)
    }
}

data class CallbackResponse(val depositId: UUID, val status: DepositStatus)
