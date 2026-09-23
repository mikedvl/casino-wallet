package com.example.casinowallet.deposit

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.annotation.DirtiesContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import java.util.UUID

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(properties = ["spring.flyway.clean-disabled=false"])
class DepositLedgerSchemaIntegrationTest @Autowired constructor(private val jdbc: JdbcTemplate, private val flyway: Flyway) {
    @BeforeEach
    fun resetIsolatedDatabase() {
        flyway.clean()
        flyway.migrate()
    }

    @Test
    fun `migrations define required exact money types timestamps and history index`() {
        flyway.validate()
        assertThat(flyway.migrate().migrationsExecuted).isZero()
        val columns = jdbc.queryForList(
            // language=PostgreSQL
            """
            select table_name, column_name, data_type, numeric_precision, numeric_scale, is_nullable
            from information_schema.columns
            where table_schema = 'public' and table_name in ('deposit', 'ledger_entry')
            """.trimIndent(),
        )
        assertThat(columns.filter { it["table_name"] == "deposit" }.map { it["column_name"] })
            .containsExactlyInAnyOrder("id", "player_id", "amount", "status", "created_at", "completed_at")
        assertThat(columns.filter { it["table_name"] == "ledger_entry" }.map { it["column_name"] })
            .containsExactlyInAnyOrder("id", "player_id", "wallet_type", "operation_type", "amount", "balance_after",
                "reference_type", "reference_id", "created_at")
        for (column in columns) {
            assertThat(column["is_nullable"]).isEqualTo(if (column["column_name"] == "completed_at") "YES" else "NO")
            when (column["column_name"]) {
                "amount", "balance_after" -> {
                    assertThat(column["data_type"]).isEqualTo("numeric")
                    assertThat(column["numeric_precision"]).isEqualTo(19)
                    assertThat(column["numeric_scale"]).isEqualTo(2)
                }
                "created_at", "completed_at" -> assertThat(column["data_type"]).isEqualTo("timestamp with time zone")
                "id", "player_id", "reference_id" -> assertThat(column["data_type"]).isEqualTo("uuid")
            }
        }
        val index = jdbc.queryForObject<String>(
            "select indexdef from pg_indexes where schemaname = 'public' and indexname = 'ledger_entry_player_history_idx'",
        )
        assertThat(index).contains("(player_id, created_at DESC, id DESC)")
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "-0.01", "NaN"])
    fun `database rejects nonpositive and nonfinite deposit amounts`(amount: String) {
        assertSqlState("23514") {
            jdbc.update("insert into deposit (id, player_id, amount) values (?, ?, ?::numeric)",
                UUID.randomUUID(), PLAYER_ID, amount)
        }
    }

    @Test
    fun `deposit primary key foreign key nullability range and completion state are enforced`() {
        insertDeposit()
        assertSqlState("23505") { insertDeposit() }
        assertSqlState("23503") {
            jdbc.update("insert into deposit (id, player_id, amount) values (?, ?, 1.00)", UUID.randomUUID(), UUID.randomUUID())
        }
        assertSqlState("23502") {
            jdbc.update("insert into deposit (id, player_id, amount) values (?, ?, null)", UUID.randomUUID(), PLAYER_ID)
        }
        assertSqlState("22003") {
            jdbc.update("insert into deposit (id, player_id, amount) values (?, ?, 100000000000000000.00)",
                UUID.randomUUID(), PLAYER_ID)
        }
        for (sql in listOf(
            "update deposit set status = 'UNKNOWN'",
            "update deposit set status = 'COMPLETED'",
            "update deposit set completed_at = clock_timestamp()",
        )) {
            assertSqlState("23514") { jdbc.update(sql) }
        }
        jdbc.update("update deposit set status = 'COMPLETED', completed_at = clock_timestamp() where id = ?", DEPOSIT_ID)
        assertThat(jdbc.queryForObject<String>("select status from deposit")).isEqualTo("COMPLETED")
    }

    @Test
    fun `ledger unique business operation prevents a second entry even with a new primary key`() {
        insertDeposit()
        insertLedger()
        assertSqlState("23505") { insertLedger() }
        assertThat(jdbc.queryForObject<Long>("select count(*) from ledger_entry")).isEqualTo(1L)
    }

    @Test
    fun `ledger rejects invalid money foreign keys nulls and unknown operation types`() {
        insertDeposit()
        for (amount in listOf("0", "-0.01", "NaN")) {
            assertSqlState("23514") { insertLedger(amount = amount) }
        }
        for (balance in listOf("-0.01", "NaN")) {
            assertSqlState("23514") { insertLedger(balance = balance) }
        }
        assertSqlState("22003") { insertLedger(amount = "100000000000000000.00") }
        assertSqlState("22003") { insertLedger(balance = "100000000000000000.00") }
        assertSqlState("23503") { insertLedger(playerId = UUID.randomUUID()) }
        assertSqlState("23502") { insertLedger(amount = null) }
        assertSqlState("23514") { insertLedger(walletType = "UNKNOWN") }
        assertSqlState("23514") { insertLedger(operationType = "UNKNOWN") }
        assertSqlState("23514") { insertLedger(referenceType = "UNKNOWN") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["UPDATE", "DELETE", "TRUNCATE"])
    fun `database refuses every supported attempt to mutate ledger history`(operation: String) {
        insertDeposit()
        insertLedger()
        val before = jdbc.queryForList("select * from ledger_entry")
        assertSqlState("55000") {
            when (operation) {
                "UPDATE" -> jdbc.update("update ledger_entry set amount = 2.00 where reference_id = ?", DEPOSIT_ID)
                "DELETE" -> jdbc.update("delete from ledger_entry where reference_id = ?", DEPOSIT_ID)
                "TRUNCATE" -> jdbc.execute("truncate table ledger_entry")
                else -> error("Unknown test operation")
            }
        }
        assertThat(jdbc.queryForList("select * from ledger_entry")).isEqualTo(before)
    }

    private fun insertDeposit() {
        jdbc.update("insert into deposit (id, player_id, amount) values (?, ?, 1.00)", DEPOSIT_ID, PLAYER_ID)
    }

    private fun insertLedger(
        amount: String? = "1.00",
        balance: String = "1.00",
        playerId: UUID = PLAYER_ID,
        walletType: String = "REAL",
        operationType: String = "DEPOSIT_COMPLETED",
        referenceType: String = "DEPOSIT",
    ) {
        jdbc.update(
            // language=PostgreSQL
            """
            insert into ledger_entry
                (id, player_id, wallet_type, operation_type, amount, balance_after, reference_type, reference_id)
            values (?, ?, ?, ?, ?::numeric, ?::numeric, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(), playerId, walletType, operationType, amount, balance, referenceType, DEPOSIT_ID,
        )
    }

    private fun assertSqlState(state: String, action: () -> Unit) {
        val error = assertThrows<DataAccessException>(action)
        assertThat(error.mostSpecificCause).isInstanceOfSatisfying(SQLException::class.java) {
            assertThat(it.sqlState).isEqualTo(state)
        }
    }

    companion object {
        private val PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val DEPOSIT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001")

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
