package com.example.casinowallet.round.domain

import java.math.BigDecimal
import java.util.UUID

data class GameRound(
    val id: UUID,
    val playerId: UUID,
    val stake: BigDecimal,
    val totalWin: BigDecimal,
    val allocation: RoundAllocation,
)
