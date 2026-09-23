package com.example.casinowallet.wallet.persistence

import com.example.casinowallet.wallet.domain.WalletSummary
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.util.UUID

@Repository
class JdbcWalletRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun lockByPlayerId(playerId: UUID): WalletSummary = checkNotNull(
        jdbc.queryForObject(
            // language=PostgreSQL
            "select real_balance, bonus_balance from wallet where player_id = :playerId for update",
            mapOf("playerId" to playerId),
        ) { row, _ -> WalletSummary(row.getBigDecimal("real_balance"), row.getBigDecimal("bonus_balance")) },
    )

    fun debitRealBalance(playerId: UUID, amount: BigDecimal): BigDecimal? = jdbc.query(
        // language=PostgreSQL
        """
        update wallet set real_balance = real_balance - :amount
        where player_id = :playerId and real_balance >= :amount
        returning real_balance
        """.trimIndent(),
        mapOf("playerId" to playerId, "amount" to amount),
    ) { row, _ -> row.getBigDecimal("real_balance") }.singleOrNull()

    fun creditRealBalance(playerId: UUID, amount: BigDecimal): BigDecimal? = jdbc.query(
        // language=PostgreSQL
        """
        update wallet set real_balance = real_balance + :amount
        where player_id = :playerId and real_balance + :amount <= 99999999999999999.99
        returning real_balance
        """.trimIndent(),
        mapOf("playerId" to playerId, "amount" to amount),
    ) { row, _ -> row.getBigDecimal("real_balance") }.singleOrNull()

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
