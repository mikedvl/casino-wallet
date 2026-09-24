package com.example.casinowallet.round

import com.example.casinowallet.config.DemoPlayer
import com.example.casinowallet.support.PostgresLockProbe
import io.micrometer.core.instrument.MeterRegistry
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.client.exchange
import org.springframework.boot.test.web.client.getForEntity
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.flyway.clean-disabled=false", "payment.provider.hmac-secret=round-test-secret"],
)
class RoundIntegrationTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val http: TestRestTemplate,
    private val flyway: Flyway,
    private val dataSource: DataSource,
    private val metrics: MeterRegistry,
) {
    @BeforeEach
    fun resetIsolatedDatabase() {
        // Only this test class's disposable PostgreSQL database is cleaned.
        flyway.clean()
        flyway.migrate()
    }

    @Test
    fun `losing round spends real stake and omits zero win history`() {
        fund("10.00")
        val id = assertSuccess(play("8", "0"), "8.00", "0.00", "2.00")
        assertRound(id, "8.00", "0.00")
        assertRoundLedger(id, listOf("ROUND_STAKE"), listOf("-8.00"), listOf("2.00"))
        assertState("2.00", 1, 1)
    }

    @Test
    fun `winning round credits total payout and paginated history preserves signed money`() {
        fund("10.00")
        val id = assertSuccess(play("4.00", "10.00"), "4.00", "10.00", "16.00")
        assertRound(id, "4.00", "10.00")
        assertRoundLedger(id, listOf("ROUND_STAKE", "ROUND_WIN"), listOf("-4.00", "10.00"), listOf("6.00", "16.00"))
        assertState("16.00", 1, 2)

        val first = checkNotNull(http.getForEntity<JsonNode>("/api/ledger?page=0&size=2").body)
        val second = checkNotNull(http.getForEntity<JsonNode>("/api/ledger?page=1&size=2").body)
        assertThat(first.path("items").map { it.path("operationType").asText() }).containsExactly("ROUND_WIN", "ROUND_STAKE")
        assertThat(first.path("items").map { it.path("amount").asText() }).containsExactly("10.00", "-4.00")
        assertThat(first.path("items").map { it.path("balanceAfter").asText() }).containsExactly("16.00", "6.00")
        for (entry in first.path("items")) {
            assertThat(entry.path("amount").isTextual).isTrue()
            assertThat(entry.path("balanceAfter").isTextual).isTrue()
            assertThat(entry.path("referenceType").asText()).isEqualTo("GAME_ROUND")
            assertThat(entry.path("referenceId").asText()).isEqualTo(id.toString())
        }
        assertThat(second.path("items").single().path("operationType").asText()).isEqualTo("DEPOSIT_COMPLETED")
        assertThat(first.path("totalElements").asLong()).isEqualTo(3L)
        assertThat(second.path("totalElements").asLong()).isEqualTo(3L)
    }

    @Test
    fun `exact balance can be spent down to zero`() {
        fund("8.00")
        val id = assertSuccess(play("8.00", "0.00"), "8.00", "0.00", "0.00")
        assertRoundLedger(id, listOf("ROUND_STAKE"), listOf("-8.00"), listOf("0.00"))
        assertState("0.00", 1, 1)
    }

    @Test
    fun `insufficient funds cannot be covered by the proposed payout`() {
        fund("10.00")
        val rejected = metrics.get("casino.wallet.rounds").tag("outcome", "rejected").counter()
        val before = rejected.count()
        assertError(play("10.01", "100.00"), HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS")
        assertThat(rejected.count()).isEqualTo(before + 1)
        assertState("10.00", 0, 0)
    }

    @Test
    fun `every play is a new round without invented idempotency`() {
        fund("19.99")
        val first = assertSuccess(play("8.00", "0.00"), "8.00", "0.00", "11.99")
        val second = assertSuccess(play("8.00", "0.00"), "8.00", "0.00", "3.99")
        assertThat(first).isNotEqualTo(second)
        assertState("3.99", 2, 2)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "{}", "null", "[]", "{\"stake\":\"8.00\"}", "{\"totalWin\":\"0.00\"}",
        "{\"stake\":8,\"totalWin\":\"0.00\"}", "{\"stake\":\"8.00\",\"totalWin\":0.0}",
        "{\"stake\":null,\"totalWin\":\"0.00\"}", "{\"stake\":\"8.00\",\"totalWin\":null}",
        "{\"stake\":true,\"totalWin\":\"0.00\"}", "{\"stake\":\"8.00\",\"totalWin\":{}}",
    ])
    fun `round contract requires both amounts as decimal strings`(body: String) {
        fund("10.00")
        assertError(post("/api/rounds/play", body), HttpStatus.BAD_REQUEST, "INVALID_ROUND_AMOUNT")
        assertState("10.00", 0, 0)
    }

    @ParameterizedTest
    @CsvSource(
        "0, 0", "-1, 0", "8.001, 0", "100000000000000000.00, 0", "1e1, 0", "NaN, 0",
        "8, -0.01", "8, 10.999", "8, 100000000000000000.00", "8, 1e1", "8, NaN",
    )
    fun `invalid round amounts leave all financial state intact`(stake: String, win: String) {
        fund("10.00")
        assertError(play(stake, win), HttpStatus.BAD_REQUEST, "INVALID_ROUND_AMOUNT")
        assertState("10.00", 0, 0)
    }

    @ParameterizedTest
    @ValueSource(strings = ["not JSON", ""])
    fun `malformed HTTP bodies return a safe request error`(body: String) {
        assertError(post("/api/rounds/play", body), HttpStatus.BAD_REQUEST, "INVALID_REQUEST")
        assertState("0.00", 0, 0, deposits = 0)
    }

    @Test
    fun `full numeric range and cents remain exact across stake and payout`() {
        fundHistoricalMaximumDeposit()
        val id = assertSuccess(play("99999999999999999.99", "99999999999999999.99"),
            "99999999999999999.99", "99999999999999999.99", "99999999999999999.99")
        assertRoundLedger(id, listOf("ROUND_STAKE", "ROUND_WIN"),
            listOf("-99999999999999999.99", "99999999999999999.99"), listOf("0.00", "99999999999999999.99"))
        assertState("99999999999999999.99", 1, 2)
    }

    @Test
    fun `payout overflowing the wallet rolls back stake round and ledger`() {
        fundHistoricalMaximumDeposit()
        assertError(play("0.01", "0.02"), HttpStatus.CONFLICT, "WALLET_BALANCE_LIMIT")
        assertState("99999999999999999.99", 0, 0)
    }

    @ParameterizedTest
    @CsvSource("8.00, 0.00, 2.00", "4.00, 10.00, 16.00")
    fun `failure at commit after settlement rolls back every financial write`(stake: String, win: String, balance: String) {
        fund("10.00")
        val completed = metrics.get("casino.wallet.rounds").tag("outcome", "completed").counter()
        val failed = metrics.get("casino.wallet.rounds").tag("outcome", "failed").counter()
        val completedBefore = completed.count()
        val failedBefore = failed.count()
        jdbc.execute(
            // language=PostgreSQL
            """
            create sequence round_failure_observed;
            create function fail_round_commit() returns trigger language plpgsql as $$
            begin
                if current_setting('transaction_isolation') = 'read committed'
                   and exists (select 1 from wallet where player_id = new.player_id
                               and real_balance = 10.00 - new.stake + new.total_win)
                   and exists (select 1 from ledger_entry where reference_id = new.id
                               and operation_type = 'ROUND_STAKE' and amount = -new.stake)
                   and (new.total_win = 0 or exists (
                       select 1 from ledger_entry where reference_id = new.id
                       and operation_type = 'ROUND_WIN' and amount = new.total_win)) then
                    perform nextval('round_failure_observed');
                    raise exception 'Controlled round commit failure';
                end if;
                return new;
            end;
            $$;
            create constraint trigger fail_round_commit after insert on game_round
                deferrable initially deferred for each row execute function fail_round_commit();
            """.trimIndent(),
        )
        assertError(play(stake, win), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR")
        assertThat(completed.count()).isEqualTo(completedBefore)
        assertThat(failed.count()).isEqualTo(failedBefore + 1)
        // Sequence advancement survives rollback, proving that all expected writes reached the commit hook.
        assertThat(jdbc.queryForObject<Boolean>("select is_called from round_failure_observed")).isTrue()
        assertState("10.00", 0, 0)
        jdbc.execute("drop trigger fail_round_commit on game_round")
        jdbc.execute("drop function fail_round_commit()")
        assertSuccess(play(stake, win), stake, win, balance)
        assertThat(completed.count()).isEqualTo(completedBefore + 1)
        assertState(balance, 1, if (BigDecimal(win).signum() == 0) 1 else 2)
    }

    @Test
    fun `two eight euro bets really contend for ten euros and only one succeeds`() {
        fund("10.00")
        val executor = Executors.newFixedThreadPool(2)
        val responses = try {
            dataSource.connection.use { blocker ->
                blocker.autoCommit = false
                val pid = PostgresLockProbe.lockWallet(blocker, DemoPlayer.ID)
                val requests = List(2) { executor.submit<ResponseEntity<JsonNode>> { play("8.00", "0.00") } }
                try {
                    PostgresLockProbe.awaitBlockedSessions(jdbc, pid, 2)
                    // GET /api/wallet now resolves bonus lifecycle under the same wallet lock.
                    assertThat(jdbc.queryForObject<BigDecimal>("select real_balance from wallet"))
                        .isEqualTo(BigDecimal("10.00"))
                    assertThat(jdbc.queryForObject<Long>("select count(*) from game_round")).isZero()
                    assertThat(jdbc.queryForObject<Long>("select count(*) from ledger_entry where reference_type = 'GAME_ROUND'"))
                        .isZero()
                } finally {
                    blocker.rollback()
                }
                requests.map { it.get(10, TimeUnit.SECONDS) }
            }
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
        assertThat(responses.map { it.statusCode }).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT)
        val id = assertSuccess(responses.single { it.statusCode == HttpStatus.OK }, "8.00", "0.00", "2.00")
        assertError(responses.single { it.statusCode == HttpStatus.CONFLICT }, HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS")
        assertRound(id, "8.00", "0.00")
        assertRoundLedger(id, listOf("ROUND_STAKE"), listOf("-8.00"), listOf("2.00"))
        assertState("2.00", 1, 1)
    }

    private fun fundHistoricalMaximumDeposit() {
        // Preserve Stage 4's full-range, no-bonus regression with reconciled history created before V5.
        val amount = "99999999999999999.99"
        flyway.clean()
        Flyway.configure().dataSource(dataSource).target("4").load().migrate()
        val id = UUID.randomUUID()
        jdbc.update("insert into deposit (id, player_id, amount, status, completed_at) values (?, ?, ?::numeric, 'COMPLETED', clock_timestamp())",
            id, DemoPlayer.ID, amount)
        jdbc.update("insert into ledger_entry (id, player_id, wallet_type, operation_type, amount, balance_after, reference_type, reference_id) values (?, ?, 'REAL', 'DEPOSIT_COMPLETED', ?::numeric, ?::numeric, 'DEPOSIT', ?)",
            UUID.randomUUID(), DemoPlayer.ID, amount, amount, id)
        jdbc.update("update wallet set real_balance = ?::numeric where player_id = ?", amount, DemoPlayer.ID)
        flyway.migrate()
        assertState(amount, 0, 0)
    }

    private fun fund(amount: String) {
        val created = post("/api/deposits", """{"amount":"$amount"}""")
        assertThat(created.statusCode).isEqualTo(HttpStatus.CREATED)
        val id = checkNotNull(created.body).path("depositId").asText()
        val cents = BigDecimal(amount).movePointRight(2).toBigIntegerExact()
        val body = """{"depositId":"$id","amountCents":$cents}"""
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("round-test-secret".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = HexFormat.of().formatHex(mac.doFinal(body.toByteArray(Charsets.UTF_8)))
        assertThat(post("/api/provider/deposits/callback", body, signature).statusCode).isEqualTo(HttpStatus.OK)
        assertState(amount, 0, 0)
    }

    private fun play(stake: String, win: String): ResponseEntity<JsonNode> =
        post("/api/rounds/play", """{"stake":"$stake","totalWin":"$win"}""")

    private fun post(path: String, body: String, signature: String? = null): ResponseEntity<JsonNode> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Request-ID", "round-integration-test")
            signature?.let { set("X-Signature", it) }
        }
        val response = http.exchange<JsonNode>(path, HttpMethod.POST, HttpEntity(body.toByteArray(Charsets.UTF_8), headers))
        assertThat(response.headers.getFirst("X-Request-ID")).isEqualTo("round-integration-test")
        return response
    }

    private fun assertSuccess(response: ResponseEntity<JsonNode>, stake: String, win: String, balance: String): UUID {
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = checkNotNull(response.body)
        for ((field, expected) in mapOf("stake" to stake, "totalWin" to win, "realBalance" to balance, "bonusBalance" to "0.00")) {
            assertThat(body.path(field).isTextual).isTrue()
            assertThat(body.path(field).asText()).isEqualTo(expected)
        }
        return UUID.fromString(body.path("roundId").asText())
    }

    private fun assertError(response: ResponseEntity<JsonNode>, status: HttpStatus, code: String) {
        assertThat(response.statusCode).isEqualTo(status)
        val body = checkNotNull(response.body)
        assertThat(body.path("code").asText()).isEqualTo(code)
        assertThat(body.has("trace")).isFalse()
        assertThat(body.has("exception")).isFalse()
        assertThat(body.toString()).doesNotContain("SQL", "round-test-secret", "Controlled round commit failure")
    }

    private fun assertRound(id: UUID, stake: String, win: String) {
        val round = jdbc.queryForMap("select * from game_round where id = ?", id)
        assertThat(round["player_id"]).isEqualTo(DemoPlayer.ID)
        assertThat(round["stake"]).isEqualTo(BigDecimal(stake))
        assertThat(round["total_win"]).isEqualTo(BigDecimal(win))
        assertThat(round["created_at"]).isNotNull()
    }

    private fun assertRoundLedger(id: UUID, operations: List<String>, amounts: List<String>, balances: List<String>) {
        val entries = jdbc.queryForList(
            // language=PostgreSQL
            "select * from ledger_entry where reference_type = 'GAME_ROUND' and reference_id = ? order by operation_type",
            id,
        )
        assertThat(entries.map { it["operation_type"] }).containsExactlyElementsOf(operations)
        assertThat(entries.map { it["amount"] }).containsExactlyElementsOf(amounts.map(::BigDecimal))
        assertThat(entries.map { it["balance_after"] }).containsExactlyElementsOf(balances.map(::BigDecimal))
        assertThat(entries.map { it["wallet_type"] }).containsOnly("REAL")
    }

    private fun assertState(balance: String, rounds: Long, roundEntries: Long, deposits: Long = 1) {
        val wallet = checkNotNull(http.getForEntity<JsonNode>("/api/wallet").body)
        assertThat(wallet.path("realBalance").asText()).isEqualTo(balance)
        assertThat(wallet.path("bonusBalance").asText()).isEqualTo("0.00")
        assertThat(jdbc.queryForObject<Long>("select count(*) from game_round")).isEqualTo(rounds)
        assertThat(jdbc.queryForObject<Long>("select count(*) from ledger_entry where reference_type = 'GAME_ROUND'"))
            .isEqualTo(roundEntries)
        assertThat(jdbc.queryForObject<Long>("select count(*) from ledger_entry where reference_type = 'DEPOSIT'"))
            .isEqualTo(deposits)
        assertThat(jdbc.queryForObject<Long>("select count(*) from ledger_entry where amount = 0 or balance_after < 0")).isZero()
        val total = jdbc.queryForObject<BigDecimal>("select coalesce(sum(amount), 0.00) from ledger_entry where wallet_type = 'REAL'")
        assertThat(total).isEqualByComparingTo(BigDecimal(balance))
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
