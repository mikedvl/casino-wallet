package com.example.casinowallet.ledger.web

import com.example.casinowallet.ledger.application.LedgerApplicationService
import com.example.casinowallet.ledger.application.LedgerPage
import com.example.casinowallet.ledger.domain.LedgerEntry
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

@RestController
class LedgerController(private val service: LedgerApplicationService) {
    @GetMapping("/api/ledger")
    fun getLedger(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): LedgerPageResponse = LedgerPageResponse.from(service.getDemoLedger(page, size))
}

data class LedgerPageResponse(
    val items: List<LedgerEntryResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Long,
) {
    companion object {
        fun from(page: LedgerPage): LedgerPageResponse = LedgerPageResponse(
            page.items.map(LedgerEntryResponse::from), page.page, page.size, page.totalElements, page.totalPages,
        )
    }
}

data class LedgerEntryResponse(
    val id: UUID,
    val walletType: String,
    val operationType: String,
    val amount: String,
    val balanceAfter: String,
    val referenceType: String,
    val referenceId: UUID,
    val createdAt: Instant,
) {
    companion object {
        fun from(entry: LedgerEntry): LedgerEntryResponse = LedgerEntryResponse(
            entry.id, entry.walletType, entry.operationType,
            entry.amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
            entry.balanceAfter.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
            entry.referenceType, entry.referenceId, entry.createdAt,
        )
    }
}
