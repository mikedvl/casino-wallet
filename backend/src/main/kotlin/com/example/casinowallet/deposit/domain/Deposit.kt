package com.example.casinowallet.deposit.domain

import java.math.BigDecimal
import java.util.UUID

enum class DepositStatus { PENDING, COMPLETED }

data class Deposit(
    val id: UUID,
    val playerId: UUID,
    val amount: BigDecimal,
    val status: DepositStatus,
)
