package com.example.casinowallet.wallet.persistence

import com.example.casinowallet.wallet.domain.WalletSummary
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.util.UUID

@Repository
class JdbcWalletRepository(private val jdbc: NamedParameterJdbcTemplate) {
    private val walletSummaryRowMapper = RowMapper<WalletSummary> { row, _ ->
        WalletSummary(
            realBalance = row.getBigDecimal("real_balance"),
            bonusBalance = row.getBigDecimal("bonus_balance"),
        )
    }

    fun lockByPlayerId(playerId: UUID): WalletSummary = checkNotNull(
        jdbc.queryForObject(
            // language=PostgreSQL
            "select real_balance, bonus_balance from wallet where player_id = :playerId for update",
            mapOf("playerId" to playerId),
            walletSummaryRowMapper,
        ),
    )

    fun debitBalances(playerId: UUID, real: BigDecimal, bonus: BigDecimal): WalletSummary? = jdbc.query(
        // language=PostgreSQL
        """
        update wallet set real_balance = real_balance - :real, bonus_balance = bonus_balance - :bonus
        where player_id = :playerId and real_balance >= :real and bonus_balance >= :bonus
        returning real_balance, bonus_balance
        """.trimIndent(),
        mapOf("playerId" to playerId, "real" to real, "bonus" to bonus),
        walletSummaryRowMapper,
    ).singleOrNull()

    fun creditBalances(playerId: UUID, real: BigDecimal, bonus: BigDecimal): WalletSummary? = jdbc.query(
        // language=PostgreSQL
        """
        update wallet set real_balance = real_balance + :real, bonus_balance = bonus_balance + :bonus
        where player_id = :playerId
            and real_balance + :real <= 99999999999999999.99
            and bonus_balance + :bonus <= 99999999999999999.99
        returning real_balance, bonus_balance
        """.trimIndent(),
        mapOf("playerId" to playerId, "real" to real, "bonus" to bonus),
        walletSummaryRowMapper,
    ).singleOrNull()

    fun creditRealBalance(playerId: UUID, amount: BigDecimal): BigDecimal? = jdbc.query(
        // language=PostgreSQL
        """
        update wallet set real_balance = real_balance + :amount
        where player_id = :playerId and real_balance + :amount <= 99999999999999999.99
        returning real_balance
        """.trimIndent(),
        mapOf("playerId" to playerId, "amount" to amount),
    ) { row, _ -> row.getBigDecimal("real_balance") }.singleOrNull()

    fun creditBonusBalance(playerId: UUID, amount: BigDecimal): BigDecimal? = jdbc.query(
        // language=PostgreSQL
        """
        update wallet set bonus_balance = bonus_balance + :amount
        where player_id = :playerId and bonus_balance + :amount <= 99999999999999999.99
        returning bonus_balance
        """.trimIndent(),
        mapOf("playerId" to playerId, "amount" to amount),
    ) { row, _ -> row.getBigDecimal("bonus_balance") }.singleOrNull()

    fun convertBonusToReal(playerId: UUID): WalletSummary? = jdbc.query(
        // language=PostgreSQL
        """
        update wallet set real_balance = real_balance + bonus_balance, bonus_balance = 0.00
        where player_id = :playerId and real_balance + bonus_balance <= 99999999999999999.99
        returning real_balance, bonus_balance
        """.trimIndent(),
        mapOf("playerId" to playerId),
        walletSummaryRowMapper,
    ).singleOrNull()

    fun forfeitBonus(playerId: UUID): WalletSummary = checkNotNull(jdbc.queryForObject(
        // language=PostgreSQL
        "update wallet set bonus_balance = 0.00 where player_id = :playerId returning real_balance, bonus_balance",
        mapOf("playerId" to playerId),
        walletSummaryRowMapper,
    ))
}
