package com.example.casinowallet.round

import com.example.casinowallet.config.DemoPlayer
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(properties = ["spring.flyway.clean-disabled=false"])
class RoundSchemaIntegrationTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val flyway: Flyway,
    private val dataSource: DataSource,
) {
    @BeforeEach
    fun resetIsolatedDatabase() {
        flyway.clean()
        flyway.migrate()
    }

    @Test
    fun `clean migrations define only the current round fields with exact money and required types`() {
        flyway.validate()
        assertThat(flyway.info().applied().map { it.version.toString() }).containsExactly("1", "2", "3", "4", "5")
        assertThat(flyway.migrate().migrationsExecuted).isZero()
        val columns = jdbc.queryForList(
            // language=PostgreSQL
            """
            select column_name, data_type, numeric_precision, numeric_scale, is_nullable
            from information_schema.columns where table_schema = 'public' and table_name = 'game_round'
            """.trimIndent(),
        )
        assertThat(columns.map { it["column_name"] }).containsExactlyInAnyOrder("id", "player_id", "stake", "total_win", "created_at",
            "real_stake", "bonus_stake", "real_win", "bonus_win")
        for (column in columns) {
            assertThat(column["is_nullable"]).isEqualTo("NO")
            when (column["column_name"]) {
                "stake", "total_win", "real_stake", "bonus_stake", "real_win", "bonus_win" -> {
                    assertThat(column["data_type"]).isEqualTo("numeric")
                    assertThat(column["numeric_precision"]).isEqualTo(19)
                    assertThat(column["numeric_scale"]).isEqualTo(2)
                }
                "id", "player_id" -> assertThat(column["data_type"]).isEqualTo("uuid")
                "created_at" -> assertThat(column["data_type"]).isEqualTo("timestamp with time zone")
            }
        }
    }

    @Test
    fun `V4 extends an applied V3 database without changing migration checksums or financial history`() {
        flyway.clean()
        val prior = Flyway.configure().dataSource(dataSource).target("3").load()
        assertThat(prior.migrate().migrationsExecuted).isEqualTo(3)
        val history = jdbc.queryForList("select version, checksum from flyway_schema_history order by installed_rank")
        val depositId = UUID.randomUUID()
        jdbc.update("insert into deposit (id, player_id, amount, status, completed_at) values (?, ?, 10.00, 'COMPLETED', clock_timestamp())",
            depositId, DemoPlayer.ID)
        insertLedger("DEPOSIT_COMPLETED", "DEPOSIT", "10.00", referenceId = depositId)
        jdbc.update("update wallet set real_balance = 10.00 where player_id = ?", DemoPlayer.ID)
        val entries = jdbc.queryForList("select * from ledger_entry")
        val stage4 = Flyway.configure().dataSource(dataSource).target("4").load()
        assertThat(stage4.migrate().migrationsExecuted).isEqualTo(1)
        stage4.validate()
        assertThat(jdbc.queryForList("select version, checksum from flyway_schema_history where version in ('1', '2', '3') order by installed_rank"))
            .isEqualTo(history)
        assertThat(jdbc.queryForList("select * from ledger_entry")).isEqualTo(entries)
        assertThat(jdbc.queryForObject<BigDecimal>("select real_balance from wallet")).isEqualTo(BigDecimal("10.00"))
        flyway.migrate()
        insertRound()
        insertLedger("ROUND_STAKE", "GAME_ROUND", "-1.00")
    }

    @ParameterizedTest
    @CsvSource(
        "0, 0, 23514", "-0.01, 0, 23514", "1, -0.01, 23514", "NaN, 0, 23514", "1, NaN, 23514",
        "100000000000000000.00, 0, 22003", "1, 100000000000000000.00, 22003",
    )
    fun `round constraints reject invalid money and values beyond numeric range`(stake: String, win: String, state: String) {
        assertSqlState(state) { insertRound(stake = stake, win = win) }
    }

    @Test
    fun `round primary key wallet foreign key and required columns are enforced`() {
        val id = UUID.randomUUID()
        insertRound(id)
        assertSqlState("23505") { insertRound(id) }
        assertSqlState("23503") { insertRound(playerId = UUID.randomUUID()) }
        assertSqlState("23502") { insertRound(stake = null) }
        assertSqlState("23502") { insertRound(win = null) }
        assertSqlState("23502") { insertRound(playerId = null) }
        assertSqlState("23502") {
            jdbc.update("insert into game_round (id, player_id, stake, total_win, real_stake, bonus_stake, real_win, bonus_win, created_at) values (?, ?, 1, 0, 1, 0, 0, 0, null)",
                UUID.randomUUID(), DemoPlayer.ID)
        }
    }

    @ParameterizedTest
    @CsvSource(
        "DEPOSIT_COMPLETED, DEPOSIT, -1", "DEPOSIT_COMPLETED, DEPOSIT, 0", "DEPOSIT_COMPLETED, GAME_ROUND, 1",
        "ROUND_STAKE, GAME_ROUND, 1", "ROUND_STAKE, GAME_ROUND, 0", "ROUND_STAKE, DEPOSIT, -1",
        "ROUND_WIN, GAME_ROUND, -1", "ROUND_WIN, GAME_ROUND, 0", "ROUND_WIN, DEPOSIT, 1",
        "UNKNOWN, GAME_ROUND, 1", "ROUND_WIN, UNKNOWN, 1", "ROUND_WIN, GAME_ROUND, NaN",
        "ROUND_STAKE, GAME_ROUND, NaN",
    )
    fun `ledger enforces operation reference and amount sign together`(operation: String, reference: String, amount: String) {
        assertSqlState("23514") { insertLedger(operation, reference, amount) }
    }

    @Test
    fun `round ledger accepts signed money preserves uniqueness and rejects unknown wallet types`() {
        val id = UUID.randomUUID()
        insertRound(id)
        insertLedger("ROUND_STAKE", "GAME_ROUND", "-1.00", referenceId = id)
        insertLedger("ROUND_WIN", "GAME_ROUND", "2.00", referenceId = id)
        assertThat(jdbc.queryForObject<Long>("select count(*) from ledger_entry")).isEqualTo(2L)
        assertSqlState("23505") { insertLedger("ROUND_STAKE", "GAME_ROUND", "-1.00", referenceId = id) }
        assertSqlState("23505") { insertLedger("ROUND_WIN", "GAME_ROUND", "2.00", referenceId = id) }
        assertSqlState("23514") { insertLedger("ROUND_WIN", "GAME_ROUND", "2.00", walletType = "UNKNOWN") }
        assertSqlState("22003") { insertLedger("ROUND_STAKE", "GAME_ROUND", "-100000000000000000.00") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["UPDATE", "DELETE", "TRUNCATE"])
    fun `round ledger remains append only at the database level`(operation: String) {
        val roundId = UUID.randomUUID()
        insertRound(roundId)
        insertLedger("ROUND_STAKE", "GAME_ROUND", "-1.00", referenceId = roundId)
        val before = jdbc.queryForList("select * from ledger_entry")
        assertSqlState("55000") {
            when (operation) {
                "UPDATE" -> jdbc.update("update ledger_entry set amount = -2.00 where reference_id = ?", roundId)
                "DELETE" -> jdbc.update("delete from ledger_entry where reference_id = ?", roundId)
                "TRUNCATE" -> jdbc.execute("truncate ledger_entry")
                else -> error("Unknown test operation")
            }
        }
        assertThat(jdbc.queryForList("select * from ledger_entry")).isEqualTo(before)
    }

    private fun insertRound(
        id: UUID = UUID.randomUUID(),
        playerId: UUID? = DemoPlayer.ID,
        stake: String? = "1.00",
        win: String? = "0.00",
    ) {
        jdbc.update("insert into game_round (id, player_id, stake, total_win, real_stake, bonus_stake, real_win, bonus_win) values (?, ?, ?::numeric, ?::numeric, ?::numeric, 0, ?::numeric, 0)",
            id, playerId, stake, win, stake, win)
    }

    private fun insertLedger(
        operation: String,
        reference: String,
        amount: String,
        referenceId: UUID = UUID.randomUUID(),
        walletType: String = "REAL",
    ) {
        jdbc.update(
            // language=PostgreSQL
            """
            insert into ledger_entry (id, player_id, wallet_type, operation_type, amount, balance_after, reference_type, reference_id)
            values (?, ?, ?, ?, ?::numeric, 10.00, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(), DemoPlayer.ID, walletType, operation, amount, reference, referenceId,
        )
    }

    private fun assertSqlState(state: String, action: () -> Unit) {
        val exception = assertThrows<DataAccessException>(action)
        assertThat(exception.mostSpecificCause).isInstanceOfSatisfying(SQLException::class.java) {
            assertThat(it.sqlState).isEqualTo(state)
        }
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16.15-alpine3.23")

        @DynamicPropertySource
        @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
