package com.example.casinowallet.round.web

import com.example.casinowallet.round.domain.RoundAmounts
import com.example.casinowallet.web.ApiRequestException
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class RoundRequestParser {
    fun parse(body: JsonNode): RoundAmounts {
        val stake = body.path("stake")
        val totalWin = body.path("totalWin")
        if (!body.isObject || !stake.isTextual || !totalWin.isTextual) throw invalidAmount()
        if (listOf("realStake", "bonusStake", "realWin", "bonusWin").any(body::has)) throw invalidAmount()
        return try {
            RoundAmounts.parse(stake.textValue(), totalWin.textValue())
        } catch (_: IllegalArgumentException) {
            throw invalidAmount()
        }
    }

    private fun invalidAmount() = ApiRequestException(
        HttpStatus.BAD_REQUEST, "INVALID_ROUND_AMOUNT",
        "Stake must be positive and totalWin nonnegative decimal strings fitting NUMERIC(19,2)",
    )
}
