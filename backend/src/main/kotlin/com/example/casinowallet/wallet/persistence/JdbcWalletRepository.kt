package com.example.casinowallet.wallet.persistence

import com.example.casinowallet.wallet.domain.WalletSummary
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JdbcWalletRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun getByPlayerId(playerId: UUID): WalletSummary = checkNotNull(
        jdbc.queryForObject(
            // language=PostgreSQL
            """
            select real_balance, bonus_balance
            from wallet
            where player_id = :playerId
            """.trimIndent(),
            mapOf("playerId" to playerId),
        ) { row, _ ->
            WalletSummary(
                realBalance = row.getBigDecimal("real_balance"),
                bonusBalance = row.getBigDecimal("bonus_balance"),
            )
        },
    )
}
