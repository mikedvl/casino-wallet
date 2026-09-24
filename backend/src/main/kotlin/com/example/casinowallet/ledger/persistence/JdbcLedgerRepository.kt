package com.example.casinowallet.ledger.persistence

import com.example.casinowallet.ledger.domain.LedgerEntry
import com.example.casinowallet.ledger.domain.WalletType
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JdbcLedgerRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun appendDeposit(playerId: UUID, depositId: UUID, amount: BigDecimal, balanceAfter: BigDecimal) {
        jdbc.update(
            // language=PostgreSQL
            """
            insert into ledger_entry
                (id, player_id, wallet_type, operation_type, amount, balance_after, reference_type, reference_id)
            values (:id, :playerId, 'REAL', 'DEPOSIT_COMPLETED', :amount, :balanceAfter, 'DEPOSIT', :depositId)
            """.trimIndent(),
            mapOf("id" to UUID.randomUUID(), "playerId" to playerId, "depositId" to depositId,
                "amount" to amount, "balanceAfter" to balanceAfter),
        )
    }

    fun appendWelcomeBonus(playerId: UUID, depositId: UUID, amount: BigDecimal, balanceAfter: BigDecimal) {
        jdbc.update(
            // language=PostgreSQL
            """
            insert into ledger_entry
                (id, player_id, wallet_type, operation_type, amount, balance_after, reference_type, reference_id)
            values (:id, :playerId, 'BONUS', 'WELCOME_BONUS_GRANTED', :amount, :balanceAfter, 'DEPOSIT', :depositId)
            """.trimIndent(),
            mapOf("id" to UUID.randomUUID(), "playerId" to playerId, "depositId" to depositId,
                "amount" to amount, "balanceAfter" to balanceAfter),
        )
    }

    fun appendRoundStake(playerId: UUID, roundId: UUID, walletType: WalletType, stake: BigDecimal, balanceAfter: BigDecimal) =
        appendRound(playerId, roundId, walletType, "ROUND_STAKE", stake.negate(), balanceAfter)

    fun appendRoundWin(playerId: UUID, roundId: UUID, walletType: WalletType, win: BigDecimal, balanceAfter: BigDecimal) =
        appendRound(playerId, roundId, walletType, "ROUND_WIN", win, balanceAfter)

    fun appendBonusConversion(playerId: UUID, bonusId: UUID, amount: BigDecimal, realBalanceAfter: BigDecimal) {
        jdbc.update(
            // language=PostgreSQL
            """
            insert into ledger_entry
                (id, player_id, wallet_type, operation_type, amount, balance_after, reference_type, reference_id)
            values (:bonusEntryId, :playerId, 'BONUS', 'BONUS_CONVERTED', -:amount, 0.00, 'BONUS', :bonusId),
                   (:realEntryId, :playerId, 'REAL', 'BONUS_CONVERTED', :amount, :balanceAfter, 'BONUS', :bonusId)
            """.trimIndent(),
            mapOf("bonusEntryId" to UUID.randomUUID(), "realEntryId" to UUID.randomUUID(), "playerId" to playerId,
                "bonusId" to bonusId, "amount" to amount, "balanceAfter" to realBalanceAfter),
        )
    }

    fun appendBonusForfeiture(playerId: UUID, bonusId: UUID, amount: BigDecimal) {
        jdbc.update(
            // language=PostgreSQL
            """
            insert into ledger_entry
                (id, player_id, wallet_type, operation_type, amount, balance_after, reference_type, reference_id)
            values (:id, :playerId, 'BONUS', 'BONUS_FORFEITED', -:amount, 0.00, 'BONUS', :bonusId)
            """.trimIndent(),
            mapOf("id" to UUID.randomUUID(), "playerId" to playerId, "bonusId" to bonusId, "amount" to amount),
        )
    }

    private fun appendRound(
        playerId: UUID, roundId: UUID, walletType: WalletType, operation: String, amount: BigDecimal, balanceAfter: BigDecimal,
    ) {
        jdbc.update(
            // language=PostgreSQL
            """
            insert into ledger_entry
                (id, player_id, wallet_type, operation_type, amount, balance_after, reference_type, reference_id)
            values (:id, :playerId, :walletType, :operation, :amount, :balanceAfter, 'GAME_ROUND', :roundId)
            """.trimIndent(),
            mapOf("id" to UUID.randomUUID(), "playerId" to playerId, "roundId" to roundId,
                "walletType" to walletType.name, "operation" to operation, "amount" to amount, "balanceAfter" to balanceAfter),
        )
    }

    fun findPage(playerId: UUID, offset: Long, size: Int): List<LedgerEntry> = jdbc.query(
        // language=PostgreSQL
        """
        select id, wallet_type, operation_type, amount, balance_after, reference_type, reference_id, created_at
        from ledger_entry where player_id = :playerId
        order by created_at desc, id desc limit :size offset :offset
        """.trimIndent(),
        mapOf("playerId" to playerId, "offset" to offset, "size" to size),
    ) { row, _ ->
        LedgerEntry(
            id = row.getObject("id", UUID::class.java),
            walletType = row.getString("wallet_type"),
            operationType = row.getString("operation_type"),
            amount = row.getBigDecimal("amount"),
            balanceAfter = row.getBigDecimal("balance_after"),
            referenceType = row.getString("reference_type"),
            referenceId = row.getObject("reference_id", UUID::class.java),
            createdAt = row.getObject("created_at", OffsetDateTime::class.java).toInstant(),
        )
    }

    fun count(playerId: UUID): Long = checkNotNull(jdbc.queryForObject(
        // language=PostgreSQL
        "select count(*) from ledger_entry where player_id = :playerId",
        mapOf("playerId" to playerId),
        Long::class.java,
    ))
}
