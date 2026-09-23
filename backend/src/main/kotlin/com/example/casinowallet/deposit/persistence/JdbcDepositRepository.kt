package com.example.casinowallet.deposit.persistence

import com.example.casinowallet.deposit.domain.Deposit
import com.example.casinowallet.deposit.domain.DepositStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JdbcDepositRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun insert(deposit: Deposit) {
        jdbc.update(
            // language=PostgreSQL
            "insert into deposit (id, player_id, amount) values (:id, :playerId, :amount)",
            mapOf("id" to deposit.id, "playerId" to deposit.playerId, "amount" to deposit.amount),
        )
    }

    fun findPlayerId(id: UUID): UUID? = jdbc.query(
        // language=PostgreSQL
        "select player_id from deposit where id = :id",
        mapOf("id" to id),
    ) { row, _ -> row.getObject("player_id", UUID::class.java) }.singleOrNull()

    fun findForUpdate(id: UUID): Deposit? = jdbc.query(
        // language=PostgreSQL
        "select id, player_id, amount, status from deposit where id = :id for update",
        mapOf("id" to id),
    ) { row, _ ->
        Deposit(
            id = row.getObject("id", UUID::class.java),
            playerId = row.getObject("player_id", UUID::class.java),
            amount = row.getBigDecimal("amount"),
            status = DepositStatus.valueOf(row.getString("status")),
        )
    }.singleOrNull()

    fun markCompleted(id: UUID) {
        check(jdbc.update(
            // language=PostgreSQL
            "update deposit set status = 'COMPLETED', completed_at = clock_timestamp() where id = :id and status = 'PENDING'",
            mapOf("id" to id),
        ) == 1) { "Expected one pending deposit" }
    }
}
