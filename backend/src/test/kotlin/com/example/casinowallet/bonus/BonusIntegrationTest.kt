package com.example.casinowallet.bonus

import com.example.casinowallet.config.DemoPlayer
import com.example.casinowallet.support.PostgresLockProbe
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.client.exchange
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@Import(BonusIntegrationTest.FixedTime::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.flyway.clean-disabled=false", "payment.provider.hmac-secret=bonus-test-secret"],
)
class BonusIntegrationTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val http: TestRestTemplate,
    private val flyway: Flyway,
    private val dataSource: DataSource,
) {
    @BeforeEach
    fun resetIsolatedDatabase() {
        flyway.clean()
        flyway.migrate()
    }

    @ParameterizedTest
    @CsvSource("19.99, 0.00", "20.00, 20.00", "75.50, 75.50", "100.00, 100.00", "100.01, 100.00")
    fun `only a qualifying completion grants a capped bonus with exact timestamps`(amount: String, grant: String) {
        val id = createDeposit(amount)
        assertWallet("0.00", "0.00")
        assertThat(jdbc.queryForObject<Long>("select count(*) from bonus")).isZero()
        assertThat(complete(id, amount).statusCode).isEqualTo(HttpStatus.OK)
        assertWallet(amount, grant)
        if (BigDecimal(grant).signum() == 0) {
            assertThat(jdbc.queryForObject<Long>("select count(*) from bonus")).isZero()
            assertThat(jdbc.queryForObject<Long>("select count(*) from ledger_entry")).isEqualTo(1L)
        } else {
            val bonus = jdbc.queryForMap("select * from bonus")
            assertThat(bonus["source_deposit_id"]).isEqualTo(id)
            assertThat(bonus["initial_amount"]).isEqualTo(BigDecimal(grant))
            assertThat(bonus["wagering_target"]).isEqualTo(BigDecimal(grant) * BigDecimal("20"))
            assertProgress("0.00")
            val times = jdbc.query("select granted_at, expires_at from bonus") { row, _ ->
                row.getTimestamp("granted_at").toInstant() to row.getTimestamp("expires_at").toInstant()
            }.single()
            assertThat(times.first).isEqualTo(GRANTED_AT)
            assertThat(times.second).isEqualTo(GRANTED_AT.plus(Duration.ofHours(168)))
            val entries = ledgerItems()
            assertThat(entries.map { it.path("operationType").asText() })
                .containsExactly("WELCOME_BONUS_GRANTED", "DEPOSIT_COMPLETED")
            val entry = entries.first()
            assertThat(entry.path("walletType").asText()).isEqualTo("BONUS")
            assertThat(entry.path("referenceType").asText()).isEqualTo("DEPOSIT")
            assertThat(entry.path("referenceId").asText()).isEqualTo(id.toString())
            assertThat(entry.path("amount").asText()).isEqualTo(grant)
            assertThat(entry.path("balanceAfter").asText()).isEqualTo(grant)
        }
        val beforeDuplicate = snapshot()
        assertThat(complete(id, amount).statusCode).isEqualTo(HttpStatus.OK)
        assertThat(snapshot()).isEqualTo(beforeDuplicate)
    }

    @Test
    fun `earlier small deposits do not consume eligibility and later deposits never grant twice`() {
        fund("10.00")
        fund("19.99")
        assertWallet("29.99", "0.00")
        val qualifying = fund("20.00")
        fund("50.00")
        assertWallet("99.99", "20.00")
        assertThat(jdbc.queryForObject<UUID>("select source_deposit_id from bonus")).isEqualTo(qualifying)
        assertThat(jdbc.queryForObject<Long>("select count(*) from bonus")).isEqualTo(1L)
        assertThat(jdbc.queryForObject<Long>("select count(*) from ledger_entry where operation_type = 'WELCOME_BONUS_GRANTED'"))
            .isEqualTo(1L)
        assertThat(jdbc.queryForObject<Long>("select count(*) from deposit where status = 'COMPLETED'")).isEqualTo(4L)
    }

    @Test
    fun `qualifying deposits completed before V5 prevent a retroactive grant`() {
        flyway.clean()
        Flyway.configure().dataSource(dataSource).target("4").load().migrate()
        val earlier = UUID.randomUUID()
        // A reconciled pre-V5 deposit is historical input, not a new welcome-bonus event.
        jdbc.update("insert into deposit (id, player_id, amount, status, completed_at) values (?, ?, 20, 'COMPLETED', clock_timestamp())",
            earlier, DemoPlayer.ID)
        jdbc.update("insert into ledger_entry (id, player_id, wallet_type, operation_type, amount, balance_after, reference_type, reference_id) values (?, ?, 'REAL', 'DEPOSIT_COMPLETED', 20, 20, 'DEPOSIT', ?)",
            UUID.randomUUID(), DemoPlayer.ID, earlier)
        jdbc.update("update wallet set real_balance = 20 where player_id = ?", DemoPlayer.ID)
        flyway.migrate()
        assertThat(complete(earlier, "20.00").statusCode).isEqualTo(HttpStatus.OK)
        fund("30.00")
        assertWallet("50.00", "0.00")
        assertThat(jdbc.queryForObject<Long>("select count(*) from bonus")).isZero()
        assertThat(jdbc.queryForObject<Long>("select count(*) from ledger_entry")).isEqualTo(2L)
        assertPlay("8.00", "0.00", "42.00", "0.00")
    }

    @Test
    fun `two qualifying callbacks actually contend and grant exactly once in commit order`() {
        val deposits = listOf(createDeposit("20.00") to "20.00", createDeposit("30.00") to "30.00")
        val responses = contend(deposits.map { (id, amount) -> { complete(id, amount) } })
        assertThat(responses.map { it.statusCode }).containsOnly(HttpStatus.OK)
        val bonus = jdbc.queryForMap("select source_deposit_id, initial_amount from bonus")
        val expectedGrant = deposits.single { it.first == bonus["source_deposit_id"] }.second
        assertThat(bonus["initial_amount"]).isEqualTo(BigDecimal(expectedGrant))
        assertWallet("50.00", expectedGrant)
        assertThat(jdbc.queryForObject<Long>("select count(*) from deposit where status = 'COMPLETED'")).isEqualTo(2L)
        assertThat(jdbc.queryForObject<Long>("select count(*) from ledger_entry")).isEqualTo(3L)
        val beforeDuplicates = snapshot()
        deposits.forEach { (id, amount) -> assertThat(complete(id, amount).statusCode).isEqualTo(HttpStatus.OK) }
        assertThat(snapshot()).isEqualTo(beforeDuplicates)
    }

    @Test
    fun `qualifying callback commit failure rolls back both balances bonus and both ledger entries`() {
        val id = createDeposit("20.00")
        val before = snapshot()
        jdbc.execute(
            // language=PostgreSQL
            """
            create sequence bonus_failure_observed;
            create function fail_bonus_commit() returns trigger language plpgsql as $$
            begin
                if new.status = 'COMPLETED'
                   and current_setting('transaction_isolation') = 'read committed'
                   and exists (select 1 from wallet where player_id = new.player_id and real_balance = 20 and bonus_balance = 20)
                   and exists (select 1 from bonus where source_deposit_id = new.id)
                   and (select count(*) from ledger_entry where reference_id = new.id) = 2 then
                    perform nextval('bonus_failure_observed');
                    raise exception 'Controlled bonus commit failure';
                end if;
                return new;
            end;
            $$;
            create constraint trigger fail_bonus_commit after update on deposit
                deferrable initially deferred for each row execute function fail_bonus_commit();
            """.trimIndent(),
        )
        assertError(complete(id, "20.00"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR")
        assertThat(jdbc.queryForObject<Boolean>("select is_called from bonus_failure_observed")).isTrue()
        assertThat(snapshot()).isEqualTo(before)
        assertWallet("0.00", "0.00")
        jdbc.execute("drop trigger fail_bonus_commit on deposit")
        jdbc.execute("drop function fail_bonus_commit()")
        assertThat(complete(id, "20.00").statusCode).isEqualTo(HttpStatus.OK)
        assertWallet("20.00", "20.00")
    }

    @Test
    fun `active max bet applies even to real only stakes and five euros is allowed`() {
        fund("20.00")
        val before = snapshot()
        assertError(play("5.01", "100.00"), HttpStatus.CONFLICT, "MAX_BET_EXCEEDED")
        assertThat(snapshot()).isEqualTo(before)
        val id = assertPlay("5.00", "0.00", "15.00", "20.00")
        assertAllocation(id, "5.00", "0.00", "0.00", "0.00")
        assertProgress("5.00")
        assertRoundLedger(id, listOf("REAL:ROUND_STAKE:-5.00:15.00"))
    }

    @Test
    fun `fully real funded four euro stake still advances wagering by the full stake`() {
        fund("20.00")
        val id = assertPlay("4.00", "10.00", "26.00", "20.00")
        assertAllocation(id, "4.00", "0.00", "10.00", "0.00")
        assertProgress("4.00")
        assertRoundLedger(id, listOf("REAL:ROUND_STAKE:-4.00:16.00", "REAL:ROUND_WIN:10.00:26.00"))
    }

    @ParameterizedTest
    @CsvSource(
        "4.00, 10.00, 3.00, 2.50, 7.50, 24.50, 23.00",
        "3.00, 10.00, 2.00, 3.33, 6.67, 24.67, 22.00",
    )
    fun `mixed round persists allocation and both wallet ledgers reconcile exactly`(
        stake: String, win: String, bonusStake: String, realWin: String, bonusWin: String, finalBonus: String, progress: String,
    ) {
        prepareMixedWallet()
        val id = assertPlay(stake, win, realWin, finalBonus)
        assertAllocation(id, "1.00", bonusStake, realWin, bonusWin)
        assertProgress(progress)
        val bonusAfterStake = (BigDecimal("20.00") - BigDecimal(bonusStake)).toPlainString()
        assertRoundLedger(id, listOf("REAL:ROUND_STAKE:-1.00:0.00", "BONUS:ROUND_STAKE:-$bonusStake:$bonusAfterStake",
            "REAL:ROUND_WIN:$realWin:$realWin", "BONUS:ROUND_WIN:$bonusWin:$finalBonus"))
        assertThat(jdbc.queryForObject<BigDecimal>("select initial_amount from bonus")).isEqualTo(BigDecimal("20.00"))
    }

    @Test
    fun `half up boundary assigns the one cent payout to real and omits zero bonus win`() {
        prepareMixedWallet()
        assertPlay("0.99", "0.00", "0.01", "20.00")
        val id = assertPlay("0.02", "0.01", "0.01", "19.99")
        assertAllocation(id, "0.01", "0.01", "0.01", "0.00")
        assertRoundLedger(id, listOf("REAL:ROUND_STAKE:-0.01:0.00", "BONUS:ROUND_STAKE:-0.01:19.99", "REAL:ROUND_WIN:0.01:0.01"))
        assertProgress("20.01")
    }

    @Test
    fun `mixed losing round writes both nonzero stakes and no win`() {
        prepareMixedWallet()
        val id = assertPlay("4.00", "0.00", "0.00", "17.00")
        assertAllocation(id, "1.00", "3.00", "0.00", "0.00")
        assertRoundLedger(id, listOf("REAL:ROUND_STAKE:-1.00:0.00", "BONUS:ROUND_STAKE:-3.00:17.00"))
        assertProgress("23.00")
    }

    @Test
    fun `bonus only funding omits zero real entries and exact combined funds may reach zero`() {
        fund("20.00")
        repeat(4) { assertThat(play("5.00", "0.00").statusCode).isEqualTo(HttpStatus.OK) }
        val id = assertPlay("4.00", "10.00", "0.00", "26.00")
        assertAllocation(id, "0.00", "4.00", "0.00", "10.00")
        assertRoundLedger(id, listOf("BONUS:ROUND_STAKE:-4.00:16.00", "BONUS:ROUND_WIN:10.00:26.00"))
        repeat(5) { assertThat(play("5.00", "0.00").statusCode).isEqualTo(HttpStatus.OK) }
        assertPlay("1.00", "0.00", "0.00", "0.00")
        assertProgress("50.00")
    }

    @Test
    fun `insufficient combined funds cannot use the future payout or change progress`() {
        fund("20.00")
        repeat(7) { assertThat(play("5.00", "0.00").statusCode).isEqualTo(HttpStatus.OK) }
        assertPlay("3.00", "0.00", "0.00", "2.00")
        fund("1.00")
        assertWallet("1.00", "2.00")
        val before = snapshot()
        assertError(play("4.00", "100.00"), HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS")
        assertThat(snapshot()).isEqualTo(before)
        assertProgress("38.00")
    }

    @Test
    fun `client supplied allocations are rejected rather than controlling the wallet classification`() {
        fund("20.00")
        val before = snapshot()
        for (field in listOf("realStake", "bonusStake", "realWin", "bonusWin")) {
            assertError(post("/api/rounds/play", """{"stake":"4.00","totalWin":"10.00","$field":"0.00"}"""),
                HttpStatus.BAD_REQUEST, "INVALID_ROUND_AMOUNT")
            assertThat(snapshot()).isEqualTo(before)
        }
    }

    @Test
    fun `mixed round commit failure restores both balances progress and financial history`() {
        prepareMixedWallet()
        val before = snapshot()
        jdbc.execute(
            // language=PostgreSQL
            """
            create sequence mixed_failure_observed;
            create function fail_mixed_commit() returns trigger language plpgsql as $$
            begin
                if current_setting('transaction_isolation') = 'read committed'
                   and new.real_stake = 1 and new.bonus_stake = 3 and new.real_win = 2.50 and new.bonus_win = 7.50
                   and exists (select 1 from wallet where player_id = new.player_id and real_balance = 2.50 and bonus_balance = 24.50)
                   and exists (select 1 from bonus where player_id = new.player_id and wagering_progress = 23)
                   and (select count(*) from ledger_entry where reference_id = new.id) = 4 then
                    perform nextval('mixed_failure_observed');
                    raise exception 'Controlled mixed commit failure';
                end if;
                return new;
            end;
            $$;
            create constraint trigger fail_mixed_commit after insert on game_round
                deferrable initially deferred for each row execute function fail_mixed_commit();
            """.trimIndent(),
        )
        assertError(play("4.00", "10.00"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR")
        assertThat(jdbc.queryForObject<Boolean>("select is_called from mixed_failure_observed")).isTrue()
        assertThat(snapshot()).isEqualTo(before)
        assertWallet("1.00", "20.00")
    }

    @Test
    fun `bonus payout overflow rolls back simultaneous real credit stakes and progress`() {
        fund("20.00")
        repeat(4) { assertThat(play("5.00", "0.00").statusCode).isEqualTo(HttpStatus.OK) }
        assertPlay("1.00", "99999999999999980.99", "0.00", "99999999999999999.99")
        fund("0.01")
        val before = snapshot()
        assertError(play("0.02", "0.04"), HttpStatus.CONFLICT, "WALLET_BALANCE_LIMIT")
        assertThat(snapshot()).isEqualTo(before)
        assertWallet("0.01", "99999999999999999.99")
    }

    @Test
    fun `concurrent active bonus rounds really block and accumulate both complete stakes`() {
        prepareMixedWallet()
        val beforeRounds = jdbc.queryForObject<Long>("select count(*) from game_round")
        val responses = contend(List(2) { { play("4.00", "0.00") } })
        assertThat(responses.map { it.statusCode }).containsOnly(HttpStatus.OK)
        assertThat(responses.map { checkNotNull(it.body).path("bonusBalance").asText() })
            .containsExactlyInAnyOrder("17.00", "13.00")
        assertWallet("0.00", "13.00")
        assertProgress("27.00")
        assertThat(jdbc.queryForObject<Long>("select count(*) from game_round")).isEqualTo(beforeRounds + 2)
        val ids = responses.map { UUID.fromString(checkNotNull(it.body).path("roundId").asText()) }
        assertThat(ids).doesNotHaveDuplicates()
        assertThat(jdbc.queryForObject("select sum(stake) from game_round where id in (?, ?)", BigDecimal::class.java, *ids.toTypedArray()))
            .isEqualTo(BigDecimal("8.00"))
    }

    @Test
    fun `target may be exceeded without conversion completion or removal of the active limit`() {
        fund("20.00")
        repeat(81) { assertThat(play("5.00", "5.00").statusCode).isEqualTo(HttpStatus.OK) }
        assertProgress("405.00")
        assertWallet("20.00", "20.00")
        val before = snapshot()
        assertError(play("5.01", "0.00"), HttpStatus.CONFLICT, "MAX_BET_EXCEEDED")
        assertThat(snapshot()).isEqualTo(before)
    }

    private fun prepareMixedWallet() {
        fund("20.00")
        repeat(3) { assertThat(play("5.00", "0.00").statusCode).isEqualTo(HttpStatus.OK) }
        assertPlay("4.00", "0.00", "1.00", "20.00")
        assertProgress("19.00")
    }

    private fun fund(amount: String): UUID = createDeposit(amount).also {
        assertThat(complete(it, amount).statusCode).isEqualTo(HttpStatus.OK)
    }

    private fun createDeposit(amount: String): UUID {
        val response = post("/api/deposits", """{"amount":"$amount"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        return UUID.fromString(checkNotNull(response.body).path("depositId").asText())
    }

    private fun complete(id: UUID, amount: String): ResponseEntity<JsonNode> {
        val cents = BigDecimal(amount).movePointRight(2).toBigIntegerExact()
        val body = """{"depositId":"$id","amountCents":$cents}"""
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("bonus-test-secret".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return post("/api/provider/deposits/callback", body, HexFormat.of().formatHex(mac.doFinal(body.toByteArray(Charsets.UTF_8))))
    }

    private fun play(stake: String, win: String): ResponseEntity<JsonNode> =
        post("/api/rounds/play", """{"stake":"$stake","totalWin":"$win"}""")

    private fun post(path: String, body: String, signature: String? = null): ResponseEntity<JsonNode> =
        request(path, HttpMethod.POST, body, signature)

    private fun request(path: String, method: HttpMethod, body: String? = null, signature: String? = null): ResponseEntity<JsonNode> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Request-ID", "bonus-integration-test")
            signature?.let { set("X-Signature", it) }
        }
        val response = http.exchange<JsonNode>(path, method, HttpEntity(body?.toByteArray(Charsets.UTF_8), headers))
        assertThat(response.headers.getFirst("X-Request-ID")).isEqualTo("bonus-integration-test")
        return response
    }

    private fun assertPlay(stake: String, win: String, real: String, bonus: String): UUID {
        val response = play(stake, win)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = checkNotNull(response.body)
        assertThat(body.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("roundId", "stake", "totalWin", "realBalance", "bonusBalance")
        for ((field, expected) in mapOf("stake" to stake, "totalWin" to win, "realBalance" to real, "bonusBalance" to bonus)) {
            assertThat(body.path(field).isTextual).isTrue()
            assertThat(body.path(field).asText()).isEqualTo(expected)
        }
        assertWallet(real, bonus)
        return UUID.fromString(body.path("roundId").asText())
    }

    private fun assertWallet(real: String, bonus: String) {
        val response = request("/api/wallet", HttpMethod.GET)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val wallet = checkNotNull(response.body)
        for ((type, field, expected) in listOf(Triple("REAL", "realBalance", real), Triple("BONUS", "bonusBalance", bonus))) {
            assertThat(wallet.path(field).isTextual).isTrue()
            assertThat(wallet.path(field).asText()).isEqualTo(expected)
            assertThat(jdbc.queryForObject("select coalesce(sum(amount), 0.00) from ledger_entry where player_id = ? and wallet_type = ?",
                BigDecimal::class.java, DemoPlayer.ID, type)).isEqualByComparingTo(BigDecimal(expected))
        }
        assertThat(jdbc.queryForObject<Long>("select count(*) from ledger_entry where amount = 0 or balance_after < 0")).isZero()
    }

    private fun assertProgress(expected: String) {
        val bonus = jdbc.queryForMap("select status, wagering_progress from bonus")
        assertThat(bonus["status"]).isEqualTo("ACTIVE")
        assertThat(bonus["wagering_progress"]).isEqualTo(BigDecimal(expected))
    }

    private fun assertAllocation(id: UUID, realStake: String, bonusStake: String, realWin: String, bonusWin: String) {
        val round = jdbc.queryForMap("select * from game_round where id = ?", id)
        for ((column, amount) in mapOf("real_stake" to realStake, "bonus_stake" to bonusStake, "real_win" to realWin, "bonus_win" to bonusWin)) {
            assertThat(round[column]).isEqualTo(BigDecimal(amount))
        }
        assertThat(round["stake"]).isEqualTo(BigDecimal(realStake) + BigDecimal(bonusStake))
        assertThat(round["total_win"]).isEqualTo(BigDecimal(realWin) + BigDecimal(bonusWin))
    }

    private fun ledgerItems(): List<JsonNode> {
        val response = request("/api/ledger?size=100", HttpMethod.GET)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val items = checkNotNull(response.body).path("items").toList()
        for (entry in items) {
            assertThat(entry.path("amount").isTextual).isTrue()
            assertThat(entry.path("balanceAfter").isTextual).isTrue()
        }
        return items
    }

    private fun assertRoundLedger(id: UUID, expected: List<String>) {
        val entries = ledgerItems().filter { it.path("referenceId").asText() == id.toString() }
        assertThat(entries.map { it.path("referenceType").asText() }).containsOnly("GAME_ROUND")
        assertThat(entries.map {
            listOf("walletType", "operationType", "amount", "balanceAfter").joinToString(":") { field -> it.path(field).asText() }
        }).containsExactlyInAnyOrderElementsOf(expected)
    }

    private fun assertError(response: ResponseEntity<JsonNode>, status: HttpStatus, code: String) {
        assertThat(response.statusCode).isEqualTo(status)
        val body = checkNotNull(response.body)
        assertThat(body.path("code").asText()).isEqualTo(code)
        assertThat(body.toString()).doesNotContain("SQL", "bonus-test-secret", "Controlled", "trace", "exception")
    }

    private fun snapshot() = mapOf(
        "wallet" to jdbc.queryForList("select * from wallet order by player_id"),
        "deposit" to jdbc.queryForList("select * from deposit order by id"),
        "bonus" to jdbc.queryForList("select * from bonus order by id"),
        "round" to jdbc.queryForList("select * from game_round order by id"),
        "ledger" to jdbc.queryForList("select * from ledger_entry order by id"),
    )

    private fun contend(actions: List<() -> ResponseEntity<JsonNode>>): List<ResponseEntity<JsonNode>> {
        val executor = Executors.newFixedThreadPool(actions.size)
        return try {
            dataSource.connection.use { blocker ->
                blocker.autoCommit = false
                val pid = PostgresLockProbe.lockWallet(blocker, DemoPlayer.ID)
                val before = snapshot()
                val requests = actions.map { action -> executor.submit<ResponseEntity<JsonNode>> { action() } }
                try {
                    PostgresLockProbe.awaitBlockedSessions(jdbc, pid, actions.size.toLong())
                    assertThat(snapshot()).isEqualTo(before)
                } finally {
                    blocker.rollback()
                }
                requests.map { it.get(10, TimeUnit.SECONDS) }
            }
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FixedTime {
        @Bean
        @Primary
        fun fixedClock(): Clock = Clock.fixed(GRANTED_AT, ZoneOffset.UTC)
    }

    companion object {
        private val GRANTED_AT = Instant.parse("2026-03-28T12:34:56Z")

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
