package com.example.casinowallet.round.persistence

import com.example.casinowallet.round.domain.GameRound
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcRoundRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun insert(round: GameRound) {
        jdbc.update(
            // language=PostgreSQL
            """
            insert into game_round (id, player_id, stake, total_win, real_stake, bonus_stake, real_win, bonus_win)
            values (:id, :playerId, :stake, :totalWin, :realStake, :bonusStake, :realWin, :bonusWin)
            """.trimIndent(),
            mapOf("id" to round.id, "playerId" to round.playerId, "stake" to round.stake, "totalWin" to round.totalWin,
                "realStake" to round.allocation.realStake, "bonusStake" to round.allocation.bonusStake,
                "realWin" to round.allocation.realWin, "bonusWin" to round.allocation.bonusWin),
        )
    }
}
