package com.example.casinowallet.bonus.persistence

import com.example.casinowallet.bonus.domain.WelcomeBonusGrant
import com.example.casinowallet.bonus.domain.WelcomeBonus
import com.example.casinowallet.bonus.domain.BonusStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.ZoneOffset
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class JdbcBonusRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun existsForPlayer(playerId: UUID): Boolean = checkNotNull(jdbc.queryForObject(
        // language=PostgreSQL
        "select exists(select 1 from bonus where player_id = :playerId)",
        mapOf("playerId" to playerId), Boolean::class.java,
    ))

    fun findByPlayerId(playerId: UUID): WelcomeBonus? = jdbc.query(
        // language=PostgreSQL
        "select id, initial_amount, wagering_progress, wagering_target, status, expires_at from bonus where player_id = :playerId",
        mapOf("playerId" to playerId),
    ) { row, _ ->
        WelcomeBonus(row.getObject("id", UUID::class.java), row.getBigDecimal("initial_amount"),
            row.getBigDecimal("wagering_progress"), row.getBigDecimal("wagering_target"),
            BonusStatus.valueOf(row.getString("status")), row.getObject("expires_at", OffsetDateTime::class.java).toInstant())
    }.singleOrNull()

    fun finish(id: UUID, status: BonusStatus) {
        require(status != BonusStatus.ACTIVE)
        check(jdbc.update(
            // language=PostgreSQL
            "update bonus set status = :status where id = :id and status = 'ACTIVE'",
            mapOf("id" to id, "status" to status.name),
        ) == 1) { "Expected one active bonus under the wallet lock" }
    }

    fun insert(playerId: UUID, depositId: UUID, grant: WelcomeBonusGrant) {
        jdbc.update(
            // language=PostgreSQL
            """
            insert into bonus (id, player_id, source_deposit_id, initial_amount, wagering_target, status, granted_at, expires_at)
            values (:id, :playerId, :depositId, :amount, :target, 'ACTIVE', :grantedAt, :expiresAt)
            """.trimIndent(),
            mapOf("id" to UUID.randomUUID(), "playerId" to playerId, "depositId" to depositId,
                "amount" to grant.amount, "target" to grant.wageringTarget,
                "grantedAt" to grant.grantedAt.atOffset(ZoneOffset.UTC), "expiresAt" to grant.expiresAt.atOffset(ZoneOffset.UTC)),
        )
    }

    fun advanceWagering(id: UUID, stake: BigDecimal) {
        check(jdbc.update(
            // language=PostgreSQL
            "update bonus set wagering_progress = wagering_progress + :stake where id = :id and status = 'ACTIVE'",
            mapOf("id" to id, "stake" to stake),
        ) == 1) { "Expected one active bonus under the wallet lock" }
    }
}
