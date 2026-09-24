package com.example.casinowallet.support

import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Connection
import java.time.Duration
import java.util.UUID

object PostgresLockProbe {
    // The caller owns the transaction and must release it in finally.
    fun lockWallet(connection: Connection, playerId: UUID): Int = connection.prepareStatement(
        // language=PostgreSQL
        "select pg_backend_pid() from wallet where player_id = ? for update",
    ).use { statement ->
        check(!connection.autoCommit) { "A transaction must hold the wallet lock" }
        statement.setObject(1, playerId)
        statement.executeQuery().use { row ->
            check(row.next()) { "Expected the wallet row to lock" }
            row.getInt(1)
        }
    }

    fun awaitBlockedSessions(jdbc: JdbcTemplate, blockerPid: Int, expected: Long) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            // A waiter can queue behind another contender, so follow the full blocking chain.
            val waiting = jdbc.queryForObject(
                // language=PostgreSQL
                """
                with recursive blocking_tree(pid) as (
                    select ?::integer
                    union
                    select waiting.pid
                    from pg_stat_activity waiting
                    join blocking_tree blocker on blocker.pid = any(pg_blocking_pids(waiting.pid))
                    where waiting.datname = current_database() and waiting.wait_event_type = 'Lock'
                )
                select count(*) from blocking_tree where pid <> ?
                """.trimIndent(),
                Long::class.java, blockerPid, blockerPid,
            )
            assertThat(waiting).isEqualTo(expected)
        }
    }
}
