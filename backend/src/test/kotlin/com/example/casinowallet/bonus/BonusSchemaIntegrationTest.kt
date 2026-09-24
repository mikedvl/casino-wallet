package com.example.casinowallet.bonus

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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
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
class BonusSchemaIntegrationTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val namedJdbc: NamedParameterJdbcTemplate,
    private val flyway: Flyway,
    private val dataSource: DataSource,
) {
    @BeforeEach
    fun resetIsolatedDatabase() {
        flyway.clean()
        flyway.migrate()
    }

    @Test
    fun `clean migrations define bonus metadata without a duplicate balance`() {
        flyway.validate()
        assertThat(flyway.info().applied().map { it.version.toString() }).containsExactly("1", "2", "3", "4", "5", "6")
        assertThat(flyway.migrate().migrationsExecuted).isZero()
        val columns = jdbc.queryForList(
            // language=PostgreSQL
            """
            select column_name, data_type, numeric_precision, numeric_scale, is_nullable
            from information_schema.columns where table_schema = 'public' and table_name = 'bonus'
            """.trimIndent(),
        )
        assertThat(columns.map { it["column_name"] }).containsExactlyInAnyOrder("id", "player_id", "source_deposit_id",
            "initial_amount", "wagering_target", "wagering_progress", "status", "granted_at", "expires_at")
        for (column in columns) {
            assertThat(column["is_nullable"]).isEqualTo("NO")
            when (column["column_name"]) {
                "initial_amount", "wagering_target", "wagering_progress" -> {
                    assertThat(column["data_type"]).isEqualTo("numeric")
                    assertThat(column["numeric_precision"]).isEqualTo(19)
                    assertThat(column["numeric_scale"]).isEqualTo(2)
                }
                "id", "player_id", "source_deposit_id" -> assertThat(column["data_type"]).isEqualTo("uuid")
                "granted_at", "expires_at" -> assertThat(column["data_type"]).isEqualTo("timestamp with time zone")
                "status" -> assertThat(column["data_type"]).isEqualTo("character varying")
            }
        }
        insertBonus()
        assertThat(jdbc.queryForObject<BigDecimal>("select wagering_progress from bonus")).isEqualTo(BigDecimal("0.00"))
    }

    @Test
    fun `V5 upgrades populated V4 rounds without rewriting checksums or ledger history`() {
        flyway.clean()
        Flyway.configure().dataSource(dataSource).target("4").load().migrate()
        val history = jdbc.queryForList("select version, checksum from flyway_schema_history order by installed_rank")
        val depositId = UUID.randomUUID()
        val roundId = UUID.randomUUID()
        jdbc.update("insert into deposit (id, player_id, amount, status, completed_at) values (?, ?, 30, 'COMPLETED', clock_timestamp())",
            depositId, DemoPlayer.ID)
        jdbc.update("insert into game_round (id, player_id, stake, total_win) values (?, ?, 4, 10)", roundId, DemoPlayer.ID)
        insertLedger("REAL", "DEPOSIT_COMPLETED", "DEPOSIT", "30.00", depositId, "30.00")
        insertLedger("REAL", "ROUND_STAKE", "GAME_ROUND", "-4.00", roundId, "26.00")
        insertLedger("REAL", "ROUND_WIN", "GAME_ROUND", "10.00", roundId, "36.00")
        jdbc.update("update wallet set real_balance = 36 where player_id = ?", DemoPlayer.ID)
        val originalRound = jdbc.queryForMap("select * from game_round")
        val originalLedger = jdbc.queryForList("select * from ledger_entry order by id")

        val stage5 = Flyway.configure().dataSource(dataSource).target("5").load()
        assertThat(stage5.migrate().migrationsExecuted).isEqualTo(1)
        stage5.validate()
        assertThat(jdbc.queryForList("select version, checksum from flyway_schema_history where version in ('1', '2', '3', '4') order by installed_rank"))
            .isEqualTo(history)
        val migrated = jdbc.queryForMap("select * from game_round")
        assertThat(migrated.filterKeys { it in originalRound }).isEqualTo(originalRound)
        assertThat(migrated["real_stake"]).isEqualTo(BigDecimal("4.00"))
        assertThat(migrated["bonus_stake"]).isEqualTo(BigDecimal("0.00"))
        assertThat(migrated["real_win"]).isEqualTo(BigDecimal("10.00"))
        assertThat(migrated["bonus_win"]).isEqualTo(BigDecimal("0.00"))
        assertThat(jdbc.queryForList("select * from ledger_entry order by id")).isEqualTo(originalLedger)
        assertThat(jdbc.queryForObject<BigDecimal>("select real_balance from wallet")).isEqualTo(BigDecimal("36.00"))
        assertThat(jdbc.queryForObject<BigDecimal>("select sum(amount) from ledger_entry")).isEqualTo(BigDecimal("36.00"))
        assertThat(jdbc.queryForObject<Long>("select count(*) from bonus")).isZero()
    }

    @ParameterizedTest
    @CsvSource("initial, 0", "initial, -0.01", "initial, 100.01", "initial, NaN", "target, 0", "target, 399.99",
        "target, NaN", "progress, -0.01", "progress, NaN", "status, PENDING", "status, REVOKED", "status, UNKNOWN",
        "expires, 2026-01-01T00:00:00Z", "expires, 2025-12-31T23:59:59Z")
    fun `bonus checks reject invalid amounts targets progress status and timestamps`(field: String, value: String) {
        val overrides = if (field == "initial") {
            // Keep the target consistent so it cannot hide a missing grant cap constraint.
            val target = if (value == "NaN") "NaN" else (BigDecimal(value) * BigDecimal("20")).toPlainString()
            mapOf("initial" to value, "target" to target)
        } else {
            mapOf(field to value)
        }
        assertSqlState("23514") { insertBonus(overrides = overrides) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["initial", "target", "progress"])
    fun `bonus money is bounded by numeric precision`(field: String) {
        assertSqlState("22003") { insertBonus(overrides = mapOf(field to "100000000000000000.00")) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["id", "player", "deposit", "initial", "target", "progress", "status", "granted", "expires"])
    fun `all bonus metadata columns are required`(field: String) {
        assertSqlState("23502") { insertBonus(overrides = mapOf(field to null)) }
    }

    @Test
    fun `bonus primary key player lifetime and source deposit are independently unique`() {
        val id = UUID.randomUUID()
        val source = insertDeposit()
        insertBonus(source, mapOf("id" to id))
        val otherPlayer = UUID.randomUUID()
        jdbc.update("insert into wallet (player_id) values (?)", otherPlayer)
        val otherSource = insertDeposit(otherPlayer)
        assertSqlState("23505") { insertBonus(otherSource, mapOf("id" to id, "player" to otherPlayer)) }
        assertSqlState("23505") { insertBonus(insertDeposit()) }
        assertSqlState("23505") { insertBonus(source, mapOf("player" to otherPlayer)) }
        assertSqlState("23503") { insertBonus(otherSource, mapOf("player" to UUID.randomUUID())) }
        assertSqlState("23503") { insertBonus(UUID.randomUUID(), mapOf("player" to otherPlayer)) }
        assertThat(jdbc.queryForObject<Long>("select count(*) from bonus")).isEqualTo(1L)
    }

    @Test
    fun `progress beyond target remains representable in Stage 5`() {
        insertBonus(overrides = mapOf("progress" to "405.00"))
        assertThat(jdbc.queryForObject<String>("select status from bonus")).isEqualTo("ACTIVE")
    }

    @ParameterizedTest
    @CsvSource(
        "REAL, DEPOSIT_COMPLETED, DEPOSIT, 20", "BONUS, WELCOME_BONUS_GRANTED, DEPOSIT, 20",
        "REAL, ROUND_STAKE, GAME_ROUND, -1", "BONUS, ROUND_STAKE, GAME_ROUND, -3",
        "REAL, ROUND_WIN, GAME_ROUND, 2.50", "BONUS, ROUND_WIN, GAME_ROUND, 7.50",
    )
    fun `ledger accepts the supported wallet operation sign and reference combinations`(
        wallet: String, operation: String, reference: String, amount: String,
    ) {
        val id = UUID.randomUUID()
        insertLedger(wallet, operation, reference, amount, id)
        assertSqlState("23505") { insertLedger(wallet, operation, reference, amount, id) }
    }

    @ParameterizedTest
    @CsvSource(
        "BONUS, DEPOSIT_COMPLETED, DEPOSIT, 1", "REAL, WELCOME_BONUS_GRANTED, DEPOSIT, 1",
        "BONUS, WELCOME_BONUS_GRANTED, GAME_ROUND, 1", "BONUS, WELCOME_BONUS_GRANTED, DEPOSIT, -1",
        "BONUS, WELCOME_BONUS_GRANTED, DEPOSIT, 0", "BONUS, WELCOME_BONUS_GRANTED, DEPOSIT, NaN",
        "BONUS, ROUND_STAKE, DEPOSIT, -1", "BONUS, ROUND_WIN, DEPOSIT, 1",
        "BONUS, ROUND_STAKE, GAME_ROUND, 1", "BONUS, ROUND_WIN, GAME_ROUND, -1",
        "BONUS, ROUND_STAKE, GAME_ROUND, 0", "BONUS, ROUND_WIN, GAME_ROUND, 0",
        "UNKNOWN, ROUND_WIN, GAME_ROUND, 1", "BONUS, CONVERSION, GAME_ROUND, 1",
    )
    fun `ledger rejects invalid bonus classification signs references and future operations`(
        wallet: String, operation: String, reference: String, amount: String,
    ) {
        assertSqlState("23514") { insertLedger(wallet, operation, reference, amount) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["UPDATE", "DELETE", "TRUNCATE"])
    fun `bonus entries remain immutable alongside both wallet portions of a round`(operation: String) {
        val round = UUID.randomUUID()
        insertLedger("BONUS", "WELCOME_BONUS_GRANTED", "DEPOSIT", "20")
        insertLedger("REAL", "ROUND_STAKE", "GAME_ROUND", "-1", round)
        insertLedger("BONUS", "ROUND_STAKE", "GAME_ROUND", "-3", round)
        insertLedger("REAL", "ROUND_WIN", "GAME_ROUND", "2.50", round)
        insertLedger("BONUS", "ROUND_WIN", "GAME_ROUND", "7.50", round)
        val before = jdbc.queryForList("select * from ledger_entry order by id")
        assertSqlState("55000") {
            when (operation) {
                "UPDATE" -> jdbc.update("update ledger_entry set amount = 21 where operation_type = 'WELCOME_BONUS_GRANTED'")
                "DELETE" -> jdbc.update("delete from ledger_entry where reference_id = ?", round)
                "TRUNCATE" -> jdbc.execute("truncate ledger_entry")
                else -> error("Unknown test operation")
            }
        }
        assertThat(jdbc.queryForList("select * from ledger_entry order by id")).isEqualTo(before)
    }

    @ParameterizedTest
    @CsvSource("-1, 5, 2.50, 7.50", "5, -1, 2.50, 7.50", "1, 3, -0.01, 10.01", "1, 3, 10.01, -0.01",
        "1, 2.99, 2.50, 7.50", "1, 3, 2.50, 7.49", "NaN, 3, 2.50, 7.50")
    fun `round allocations must be nonnegative and exactly sum to stake and payout`(
        realStake: String, bonusStake: String, realWin: String, bonusWin: String,
    ) {
        assertSqlState("23514") {
            jdbc.update("insert into game_round (id, player_id, stake, total_win, real_stake, bonus_stake, real_win, bonus_win) values (?, ?, 4, 10, ?::numeric, ?::numeric, ?::numeric, ?::numeric)",
                UUID.randomUUID(), DemoPlayer.ID, realStake, bonusStake, realWin, bonusWin)
        }
    }

    private fun insertDeposit(player: UUID = DemoPlayer.ID): UUID = UUID.randomUUID().also {
        jdbc.update("insert into deposit (id, player_id, amount) values (?, ?, 20.00)", it, player)
    }

    private fun insertBonus(source: UUID = insertDeposit(), overrides: Map<String, Any?> = emptyMap()) {
        val parameters = mapOf("id" to UUID.randomUUID(), "player" to DemoPlayer.ID, "deposit" to source,
            "initial" to "20.00", "target" to "400.00", "progress" to "0.00", "status" to "ACTIVE",
            "granted" to "2026-01-01T00:00:00Z", "expires" to "2026-01-08T00:00:00Z") + overrides
        namedJdbc.update(
            // language=PostgreSQL
            """
            insert into bonus (id, player_id, source_deposit_id, initial_amount, wagering_target, wagering_progress, status, granted_at, expires_at)
            values (:id, :player, :deposit, :initial::numeric, :target::numeric, :progress::numeric, :status, :granted::timestamptz, :expires::timestamptz)
            """.trimIndent(), parameters,
        )
    }

    private fun insertLedger(
        wallet: String, operation: String, reference: String, amount: String,
        referenceId: UUID = UUID.randomUUID(), balance: String = "20.00",
    ) {
        jdbc.update(
            // language=PostgreSQL
            """
            insert into ledger_entry (id, player_id, wallet_type, operation_type, amount, balance_after, reference_type, reference_id)
            values (?, ?, ?, ?, ?::numeric, ?::numeric, ?, ?)
            """.trimIndent(), UUID.randomUUID(), DemoPlayer.ID, wallet, operation, amount, balance, reference, referenceId,
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
