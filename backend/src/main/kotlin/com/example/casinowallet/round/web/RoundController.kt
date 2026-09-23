package com.example.casinowallet.round.web

import com.example.casinowallet.round.application.RoundApplicationService
import com.example.casinowallet.round.application.RoundResult
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.RoundingMode
import java.util.UUID

@RestController
class RoundController(private val service: RoundApplicationService, private val parser: RoundRequestParser) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/api/rounds/play")
    fun play(@RequestBody body: JsonNode): RoundResponse {
        val result = service.play(parser.parse(body))
        // The transactional service proxy has committed before success is logged or returned.
        log.info("event=round_completed round_id={}", result.round.id)
        return RoundResponse.from(result)
    }
}

data class RoundResponse(
    val roundId: UUID,
    val stake: String,
    val totalWin: String,
    val realBalance: String,
    val bonusBalance: String,
) {
    companion object {
        fun from(result: RoundResult): RoundResponse = RoundResponse(
            result.round.id,
            result.round.stake.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
            result.round.totalWin.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
            result.wallet.realBalance.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
            result.wallet.bonusBalance.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
        )
    }
}
