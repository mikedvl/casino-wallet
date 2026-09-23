package com.example.casinowallet.bonus

import com.example.casinowallet.config.DemoPlayer
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import java.util.UUID

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(properties = ["spring.flyway.clean-disabled=false"])
class BonusLifecycleSchemaTest @Autowired constructor(private val jdbc: JdbcTemplate, private val flyway: Flyway) {
    @BeforeEach
    fun resetIsolatedDatabase() {
        flyway.clean()
        flyway.migrate()
    }

    @ParameterizedTest
    @CsvSource("COMPLETED, ACTIVE", "EXPIRED, ACTIVE", "COMPLETED, EXPIRED", "EXPIRED, COMPLETED")
    fun `completed and expired are terminal database states`(terminal: String, forbidden: String) {
        val deposit = UUID.randomUUID()
        val bonus = UUID.randomUUID()
        jdbc.update("insert into deposit (id, player_id, amount) values (?, ?, 20)", deposit, DemoPlayer.ID)
        jdbc.update("insert into bonus (id, player_id, source_deposit_id, initial_amount, wagering_target, status, granted_at, expires_at) values (?, ?, ?, 20, 400, 'ACTIVE', '2026-01-01Z', '2026-01-08Z')",
            bonus, DemoPlayer.ID, deposit)
        assertThat(jdbc.update("update bonus set status = ? where id = ?", terminal, bonus)).isEqualTo(1)
        assertSqlState("23514") { jdbc.update("update bonus set status = ? where id = ?", forbidden, bonus) }
        assertThat(jdbc.queryForObject("select status from bonus where id = ?", String::class.java, bonus)).isEqualTo(terminal)
    }

    @ParameterizedTest
    @CsvSource("BONUS_CONVERTED, REAL, 20", "BONUS_CONVERTED, BONUS, -20", "BONUS_FORFEITED, BONUS, -20")
    fun `valid lifecycle entries retain the existing durable uniqueness`(operation: String, wallet: String, amount: String) {
        val reference = UUID.randomUUID()
        insertLedger(operation, wallet, amount, "BONUS", reference)
        assertSqlState("23505") { insertLedger(operation, wallet, amount, "BONUS", reference) }
    }

    @ParameterizedTest
    @CsvSource(
        "BONUS_CONVERTED, REAL, -1, BONUS", "BONUS_CONVERTED, BONUS, 1, BONUS",
        "BONUS_CONVERTED, REAL, 0, BONUS", "BONUS_CONVERTED, BONUS, 0, BONUS",
        "BONUS_CONVERTED, REAL, 1, DEPOSIT", "BONUS_CONVERTED, BONUS, -1, GAME_ROUND",
        "BONUS_FORFEITED, REAL, -1, BONUS", "BONUS_FORFEITED, REAL, 1, BONUS",
        "BONUS_FORFEITED, BONUS, 1, BONUS", "BONUS_FORFEITED, BONUS, 0, BONUS",
        "BONUS_FORFEITED, BONUS, -1, DEPOSIT", "BONUS_FORFEITED, BONUS, -1, GAME_ROUND",
        "ROUND_STAKE, BONUS, -1, BONUS", "ROUND_WIN, REAL, 1, BONUS",
    )
    fun `lifecycle ledger rejects invalid sign wallet and reference combinations`(
        operation: String, wallet: String, amount: String, reference: String,
    ) {
        assertSqlState("23514") { insertLedger(operation, wallet, amount, reference) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["UPDATE", "DELETE", "TRUNCATE"])
    fun `conversion and forfeiture history remains append only`(operation: String) {
        val converted = UUID.randomUUID()
        insertLedger("BONUS_CONVERTED", "REAL", "20", "BONUS", converted)
        insertLedger("BONUS_CONVERTED", "BONUS", "-20", "BONUS", converted)
        insertLedger("BONUS_FORFEITED", "BONUS", "-10", "BONUS")
        val before = jdbc.queryForList("select * from ledger_entry order by id")
        assertSqlState("55000") {
            when (operation) {
                "UPDATE" -> jdbc.update("update ledger_entry set amount = 21 where reference_id = ?", converted)
                "DELETE" -> jdbc.update("delete from ledger_entry where reference_id = ?", converted)
                "TRUNCATE" -> jdbc.execute("truncate ledger_entry")
                else -> error("Unknown test operation")
            }
        }
        assertThat(jdbc.queryForList("select * from ledger_entry order by id")).isEqualTo(before)
    }

    private fun insertLedger(operation: String, wallet: String, amount: String, reference: String, referenceId: UUID = UUID.randomUUID()) {
        jdbc.update("insert into ledger_entry (id, player_id, wallet_type, operation_type, amount, balance_after, reference_type, reference_id) values (?, ?, ?, ?, ?::numeric, 0, ?, ?)",
            UUID.randomUUID(), DemoPlayer.ID, wallet, operation, amount, reference, referenceId)
    }

    private fun assertSqlState(state: String, action: () -> Unit) {
        val exception = assertThrows<DataAccessException>(action)
        assertThat(exception.mostSpecificCause).isInstanceOfSatisfying(SQLException::class.java) {
            assertThat(it.sqlState).isEqualTo(state)
        }
    }

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:16.15-alpine3.23")

        @DynamicPropertySource @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
