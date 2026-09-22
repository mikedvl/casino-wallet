package com.example.casinowallet

import com.example.casinowallet.observability.RequestCorrelationFilter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.IOException
import java.util.UUID

class RequestCorrelationFilterTest {
    @Test
    fun `unsafe ids are replaced and MDC is cleared after successful requests`() {
        val filter = RequestCorrelationFilter()
        for (supplied in listOf("", "line\r\nbreak", "with space", "x".repeat(129))) {
            val request = MockHttpServletRequest("GET", "/actuator/info").apply {
                addHeader("X-Request-ID", supplied)
            }
            val response = MockHttpServletResponse()
            filter.doFilter(request, response) { _, _ ->
                assertThat(MDC.get("requestId")).isEqualTo(response.getHeader("X-Request-ID"))
            }
            val actual = response.getHeader("X-Request-ID")
            assertThat(UUID.fromString(actual).toString()).isEqualTo(actual)
            assertThat(MDC.get("requestId")).isNull()
        }
    }

    @Test
    fun `MDC is cleared even when the downstream request fails`() {
        val request = MockHttpServletRequest("GET", "/actuator/info").apply {
            addHeader("X-Request-ID", "test-failure")
        }
        assertThatThrownBy {
            RequestCorrelationFilter().doFilter(request, MockHttpServletResponse()) { _, _ ->
                assertThat(MDC.get("requestId")).isEqualTo("test-failure")
                throw IOException("Test failure")
            }
        }.isInstanceOf(IOException::class.java)
        assertThat(MDC.get("requestId")).isNull()
    }
}
