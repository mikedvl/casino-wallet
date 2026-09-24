package com.example.casinowallet.deposit

import com.example.casinowallet.deposit.web.ProviderSignatureVerifier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProviderSignatureVerifierTest {
    private val verifier = ProviderSignatureVerifier("Jefe")
    private val body = "what do ya want for nothing?".toByteArray(Charsets.UTF_8)
    // RFC 4231, test case 2: HMAC-SHA-256.
    private val signature = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"

    @Test
    fun `known HMAC vector accepts either hex case`() {
        assertThat(verifier.isValid(body, signature)).isTrue()
        assertThat(verifier.isValid(body, signature.uppercase())).isTrue()
    }

    @Test
    fun `missing malformed and incorrect signatures are rejected`() {
        for (value in listOf(null, "", "ab", "g".repeat(64), "0".repeat(64), "$signature ")) {
            assertThat(verifier.isValid(body, value)).isFalse()
        }
    }

    @Test
    fun `changing a byte or whitespace invalidates the original signature`() {
        assertThat(verifier.isValid(body + byteArrayOf(32), signature)).isFalse()
        val changed = body.copyOf().apply { this[0] = 'W'.code.toByte() }
        assertThat(verifier.isValid(changed, signature)).isFalse()
    }
}
