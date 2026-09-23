package com.example.casinowallet.round.persistence

import com.example.casinowallet.round.domain.GameRound
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcRoundRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun insert(round: GameRound) {
        jdbc.update(
            // language=PostgreSQL
            "insert into game_round (id, player_id, stake, total_win) values (:id, :playerId, :stake, :totalWin)",
            mapOf("id" to round.id, "playerId" to round.playerId, "stake" to round.stake, "totalWin" to round.totalWin),
        )
    }
}
