package com.example.casinowallet.ledger.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class LedgerEntry(
    val id: UUID,
    val walletType: String,
    val operationType: String,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
    val referenceType: String,
    val referenceId: UUID,
    val createdAt: Instant,
)
