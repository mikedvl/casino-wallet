package com.example.casinowallet.deposit.web

import com.example.casinowallet.deposit.domain.DepositAmounts
import com.example.casinowallet.web.ApiRequestException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID

@Component
class DepositRequestParser(mapper: ObjectMapper) {
    private val callbackReader = mapper.readerFor(JsonNode::class.java)
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

    fun parseAmount(body: JsonNode): BigDecimal {
        val amount = body.path("amount")
        if (!body.isObject || !amount.isTextual) throw invalidAmount()
        return try {
            DepositAmounts.parse(amount.textValue())
        } catch (_: IllegalArgumentException) {
            throw invalidAmount()
        }
    }

    fun parseCallback(rawBody: ByteArray): ProviderCallback {
        val body: JsonNode = try {
            callbackReader.readValue(rawBody)
        } catch (_: JsonProcessingException) {
            throw invalidCallback()
        }
        val id = body.path("depositId")
        val cents = body.path("amountCents")
        if (!body.isObject || !id.isTextual || !cents.isIntegralNumber) throw invalidCallback()
        return try {
            val depositId = UUID.fromString(id.textValue())
            require(depositId.toString().equals(id.textValue(), ignoreCase = true))
            ProviderCallback(depositId, DepositAmounts.fromCents(cents.bigIntegerValue()))
        } catch (_: IllegalArgumentException) {
            throw invalidCallback()
        }
    }

    private fun invalidAmount() = ApiRequestException(
        HttpStatus.BAD_REQUEST, "INVALID_DEPOSIT_AMOUNT", "Amount must be a positive decimal string fitting NUMERIC(19,2)",
    )

    private fun invalidCallback() = ApiRequestException(
        HttpStatus.BAD_REQUEST, "INVALID_CALLBACK", "Callback requires a UUID depositId and positive integer amountCents fitting NUMERIC(19,2)",
    )
}

data class ProviderCallback(val depositId: UUID, val amount: BigDecimal)
