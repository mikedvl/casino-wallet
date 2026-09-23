package com.example.casinowallet.web

import com.example.casinowallet.deposit.application.DepositAmountMismatchException
import com.example.casinowallet.deposit.application.DepositNotFoundException
import com.example.casinowallet.ledger.application.InvalidLedgerPageException
import com.example.casinowallet.round.application.InsufficientFundsException
import com.example.casinowallet.round.application.MaxBetExceededException
import com.example.casinowallet.wallet.application.WalletBalanceLimitException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiRequestException::class)
    fun invalidRequest(exception: ApiRequestException): ProblemDetail =
        problem(exception.status, exception.code, exception.message)

    @ExceptionHandler(DepositNotFoundException::class)
    fun depositNotFound(): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "DEPOSIT_NOT_FOUND", "Deposit was not found")

    @ExceptionHandler(DepositAmountMismatchException::class)
    fun amountMismatch(): ProblemDetail =
        problem(HttpStatus.CONFLICT, "DEPOSIT_AMOUNT_MISMATCH", "Callback amount does not match the deposit")

    @ExceptionHandler(WalletBalanceLimitException::class)
    fun balanceLimit(): ProblemDetail =
        problem(HttpStatus.CONFLICT, "WALLET_BALANCE_LIMIT", "Credit would exceed the wallet balance limit")

    @ExceptionHandler(InvalidLedgerPageException::class)
    fun invalidPage(): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "Page must be nonnegative and size must be between 1 and 100")

    @ExceptionHandler(InsufficientFundsException::class)
    fun insufficientFunds(): ProblemDetail =
        problem(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS", "Insufficient available balance for the stake")

    @ExceptionHandler(MaxBetExceededException::class)
    fun maxBetExceeded(): ProblemDetail =
        problem(HttpStatus.CONFLICT, "MAX_BET_EXCEEDED", "Stake cannot exceed EUR 5.00 while a bonus is active")

    @ExceptionHandler(Exception::class)
    fun unexpected(exception: Exception): ProblemDetail {
        // Keep exception types and stack frames; driver/cause messages can contain SQL, payloads or secrets.
        log.error("event=api_failure exception_type={}", exception.javaClass.simpleName, safeException(exception))
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed")
            .apply { setProperty("code", "INTERNAL_ERROR") }
    }

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? = super.handleExceptionInternal(
        ex,
        if (statusCode.is5xxServerError) unexpected(ex) else problem(statusCode, "INVALID_REQUEST", "Invalid request"),
        headers, statusCode, request,
    )

    private fun problem(status: HttpStatusCode, code: String, detail: String): ProblemDetail {
        if (status.value() == 401 || status.value() == 409) {
            log.warn("event=api_rejected code={} status={}", code, status.value())
        } else {
            log.info("event=api_rejected code={} status={}", code, status.value())
        }
        return ProblemDetail.forStatusAndDetail(status, detail).apply { setProperty("code", code) }
    }

    private fun safeException(source: Throwable, depth: Int = 0): Throwable = Throwable(source.javaClass.name).apply {
        stackTrace = source.stackTrace
        // Bound malformed/cyclic cause chains without copying potentially sensitive exception messages.
        if (depth < 8) source.cause?.takeIf { it !== source }?.let { initCause(safeException(it, depth + 1)) }
    }
}

class ApiRequestException(val status: HttpStatus, val code: String, override val message: String) : RuntimeException(message)
