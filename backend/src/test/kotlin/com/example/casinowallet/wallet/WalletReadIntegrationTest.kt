package com.example.casinowallet.wallet

import com.example.casinowallet.wallet.application.WalletApplicationService
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.client.exchange
import org.springframework.boot.test.web.client.getForEntity
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForList
import org.springframework.jdbc.core.queryForObject
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.annotation.DirtiesContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.sql.SQLException
import java.util.UUID

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WalletReadIntegrationTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val http: TestRestTemplate,
    private val walletService: WalletApplicationService,
    private val flyway: Flyway,
) {
    @Test
    fun `migrations create the approved schema and seed one zero balance wallet`() {
        val tables = jdbc.queryForList<String>(
            // language=PostgreSQL
            "select table_name from information_schema.tables where table_schema = 'public'",
        )
        assertThat(tables).containsExactlyInAnyOrder("wallet", "deposit", "ledger_entry", "game_round", "flyway_schema_history")
        val failedMigrations = jdbc.queryForObject<Long>(
            // language=PostgreSQL
            "select count(*) from flyway_schema_history where success = false",
        )
        assertThat(failedMigrations).isZero()

        val wallets = jdbc.queryForList(
            // language=PostgreSQL
            "select player_id, real_balance, bonus_balance from wallet",
        )
        assertThat(wallets).hasSize(1)
        val wallet = wallets.single()
        assertThat(wallet["player_id"]).isEqualTo(DEMO_PLAYER_ID)
        assertThat(wallet["real_balance"]).isEqualTo(BigDecimal("0.00"))
        assertThat(wallet["bonus_balance"]).isEqualTo(BigDecimal("0.00"))
    }

    @Test
    fun `wallet money columns are required numeric values with precision 19 and scale 2`() {
        val columns = jdbc.queryForList(
            // language=PostgreSQL
            """
            select column_name, data_type, numeric_precision, numeric_scale, is_nullable
            from information_schema.columns
            where table_schema = 'public' and table_name = 'wallet'
            """.trimIndent(),
        )
        assertThat(columns.map { it["column_name"] })
            .containsExactlyInAnyOrder("player_id", "real_balance", "bonus_balance")
        for (name in listOf("real_balance", "bonus_balance")) {
            val column = columns.single { it["column_name"] == name }
            assertThat(column["data_type"]).isEqualTo("numeric")
            assertThat(column["numeric_precision"]).isEqualTo(19)
            assertThat(column["numeric_scale"]).isEqualTo(2)
            assertThat(column["is_nullable"]).isEqualTo("NO")
        }
    }

    @Test
    fun `reapplying migrations preserves the single demo wallet`() {
        flyway.validate()
        assertThat(flyway.migrate().migrationsExecuted).isZero()
        val walletCount = jdbc.queryForObject<Long>(
            // language=PostgreSQL
            "select count(*) from wallet",
        )
        assertThat(walletCount).isEqualTo(1L)
    }

    @Test
    fun `application service reads the seeded wallet through JDBC`() {
        val wallet = walletService.getDemoWallet()
        assertThat(wallet.realBalance).isEqualTo(BigDecimal("0.00"))
        assertThat(wallet.bonusBalance).isEqualTo(BigDecimal("0.00"))
        assertThat(wallet.realBalance.scale()).isEqualTo(2)
        assertThat(wallet.bonusBalance.scale()).isEqualTo(2)
    }

    @ParameterizedTest
    @CsvSource("-0.01, 0.00", "0.00, -0.01")
    fun `database rejects negative wallet balances`(realBalance: BigDecimal, bonusBalance: BigDecimal) {
        try {
            val exception = assertThrows<DataIntegrityViolationException> {
                jdbc.update(
                    // language=PostgreSQL
                    "update wallet set real_balance = ?, bonus_balance = ? where player_id = ?",
                    realBalance, bonusBalance, DEMO_PLAYER_ID,
                )
            }
            assertThat(exception.mostSpecificCause).isInstanceOfSatisfying(SQLException::class.java) { cause ->
                assertThat(cause.sqlState).isEqualTo("23514")
            }
        } finally {
            jdbc.update(
                // language=PostgreSQL
                "update wallet set real_balance = 0.00, bonus_balance = 0.00 where player_id = ?",
                DEMO_PLAYER_ID,
            )
        }
    }

    @Test
    fun `wallet endpoint returns the seeded balances as two decimal strings and propagates request id`() {
        val headers = HttpHeaders().apply { set("X-Request-ID", "wallet-read-test") }
        val response = http.exchange<JsonNode>(
            "/api/wallet", HttpMethod.GET, HttpEntity<Void>(headers),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.getFirst("X-Request-ID")).isEqualTo("wallet-read-test")
        val wallet = checkNotNull(response.body) { "Expected a wallet response body" }
        assertThat(wallet.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("realBalance", "bonusBalance")
        for (balance in listOf("realBalance", "bonusBalance")) {
            assertThat(wallet.path(balance).isTextual).isTrue()
            assertThat(wallet.path(balance).asText()).isEqualTo("0.00")
        }
    }

    @Test
    fun `wallet endpoint reads current database balances without precision loss`() {
        jdbc.update(
            // language=PostgreSQL
            "update wallet set real_balance = ?, bonus_balance = ? where player_id = ?",
            BigDecimal("99999999999999999.99"), BigDecimal("12.30"), DEMO_PLAYER_ID,
        )
        try {
            val response = http.getForEntity<JsonNode>("/api/wallet")
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val wallet = checkNotNull(response.body) { "Expected a wallet response body" }
            assertThat(wallet.path("realBalance").isTextual).isTrue()
            assertThat(wallet.path("realBalance").asText()).isEqualTo("99999999999999999.99")
            assertThat(wallet.path("bonusBalance").isTextual).isTrue()
            assertThat(wallet.path("bonusBalance").asText()).isEqualTo("12.30")
        } finally {
            jdbc.update(
                // language=PostgreSQL
                "update wallet set real_balance = 0.00, bonus_balance = 0.00 where player_id = ?",
                DEMO_PLAYER_ID,
            )
        }
    }

    @Test
    fun `missing demo wallet does not return invented balances or internal error details`() {
        jdbc.update(
            // language=PostgreSQL
            "delete from wallet where player_id = ?", DEMO_PLAYER_ID,
        )
        try {
            val response = http.getForEntity<JsonNode>("/api/wallet")
            assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
            val error = checkNotNull(response.body) { "Expected an error response body" }
            assertThat(error.has("realBalance")).isFalse()
            assertThat(error.has("bonusBalance")).isFalse()
            assertThat(error.has("trace")).isFalse()
            assertThat(error.has("message")).isFalse()
        } finally {
            jdbc.update(
                // language=PostgreSQL
                "insert into wallet (player_id, real_balance, bonus_balance) values (?, 0.00, 0.00)",
                DEMO_PLAYER_ID,
            )
        }
    }

    companion object {
        private val DEMO_PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001")

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
