package com.example.casinowallet.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID
import java.util.concurrent.TimeUnit

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestCorrelationFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val safeRequestId = Regex("[A-Za-z0-9._-]{1,128}")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val suppliedId = request.getHeader("X-Request-ID")
        val requestId = suppliedId?.takeIf { it.length <= 128 && safeRequestId.matches(it) }
            ?: UUID.randomUUID().toString()
        val started = System.nanoTime()
        var status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
        MDC.put("requestId", requestId)
        response.setHeader("X-Request-ID", requestId)
        try {
            filterChain.doFilter(request, response)
            status = response.status
        } finally {
            try {
                // requestURI excludes query parameters; never log headers or bodies.
                val path = request.requestURI.replace(Regex("[\\r\\n\\t\"\\\\]"), "_")
                log.info(
                    "event=http_request method={} path={} status={} duration_ms={}",
                    request.method,
                    path,
                    status,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                )
            } finally {
                MDC.remove("requestId")
            }
        }
    }
}
