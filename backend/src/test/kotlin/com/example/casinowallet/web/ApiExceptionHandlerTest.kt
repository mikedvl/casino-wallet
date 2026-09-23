package com.example.casinowallet.web

import com.example.casinowallet.observability.RequestCorrelationFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.converter.HttpMessageNotWritableException
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@ExtendWith(OutputCaptureExtension::class)
class ApiExceptionHandlerTest {
    @Test
    fun `framework serialization failures remain internal errors with one sanitized stack trace`(output: CapturedOutput) {
        val mvc = MockMvcBuilders.standaloneSetup(FailingResponseController())
            .setControllerAdvice(ApiExceptionHandler()).addFilters<StandaloneMockMvcBuilder>(RequestCorrelationFilter()).build()
        mvc.get("/test/serialization") { header("X-Request-ID", "serialization-test") }.andExpect {
            status { isInternalServerError() }
            header { string("X-Request-ID", "serialization-test") }
            jsonPath("$.code") { value("INTERNAL_ERROR") }
            jsonPath("$.detail") { value("The request could not be completed") }
        }
        assertThat(output.all.lineSequence().filter { "event=api_failure" in it }.toList()).hasSize(1)
        assertThat(output.all).contains("HttpMessageNotWritableException", "at com.example.casinowallet")
        assertThat(output.all).doesNotContain("sensitive-payload", "event=api_rejected")
    }

    @RestController
    class FailingResponseController {
        @GetMapping("/test/serialization")
        fun fail(): Nothing = throw HttpMessageNotWritableException("sensitive-payload")
    }
}
