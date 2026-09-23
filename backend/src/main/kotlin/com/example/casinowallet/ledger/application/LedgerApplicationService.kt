package com.example.casinowallet.ledger.application

import com.example.casinowallet.config.DemoPlayer
import com.example.casinowallet.ledger.domain.LedgerEntry
import com.example.casinowallet.ledger.persistence.JdbcLedgerRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class LedgerApplicationService(private val ledger: JdbcLedgerRepository) {
    // Items and count share one snapshot even when callbacks commit between these two reads.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun getDemoLedger(page: Int, size: Int): LedgerPage {
        if (page < 0 || size !in 1..100) throw InvalidLedgerPageException()
        val items = ledger.findPage(DemoPlayer.ID, page.toLong() * size, size)
        val total = ledger.count(DemoPlayer.ID)
        return LedgerPage(items, page, size, total, Math.ceilDiv(total, size.toLong()))
    }
}

data class LedgerPage(
    val items: List<LedgerEntry>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Long,
)

class InvalidLedgerPageException : RuntimeException()
