package com.example.casinowallet.deposit.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class ProviderSignatureVerifier(@Value($$"${payment.provider.hmac-secret}") secret: String) {
    init {
        require(secret.isNotBlank()) { "Payment provider HMAC secret must be configured" }
    }

    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
    private val hexSignature = Regex("[0-9a-fA-F]{64}")

    fun isValid(rawBody: ByteArray, signature: String?): Boolean {
        if (signature == null || !hexSignature.matches(signature)) return false
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return MessageDigest.isEqual(mac.doFinal(rawBody), HexFormat.of().parseHex(signature))
    }
}
