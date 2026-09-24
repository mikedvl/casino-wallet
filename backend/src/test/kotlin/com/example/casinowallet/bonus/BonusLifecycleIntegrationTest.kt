package com.example.casinowallet.bonus

import com.example.casinowallet.config.DemoPlayer
import com.example.casinowallet.support.PostgresLockProbe
import io.micrometer.core.instrument.MeterRegistry
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
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
import java.time.Instant
import java.time.ZoneId
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
@ExtendWith(OutputCaptureExtension::class)
@Import(BonusLifecycleIntegrationTest.FixedTime::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.flyway.clean-disabled=false", "payment.provider.hmac-secret=lifecycle-test-secret"])
class BonusLifecycleIntegrationTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val http: TestRestTemplate,
    private val flyway: Flyway,
    private val dataSource: DataSource,
    private val clock: TestClock,
    private val metrics: MeterRegistry,
) {
    @BeforeEach
    fun resetIsolatedDatabase() {
        clock.now = GRANTED_AT
        flyway.clean()
        flyway.migrate()
    }

    @Test
    fun `wallet exposes typed bonus history and demo helper completes the stored amount exactly once`() {
        assertThat(wallet().path("bonus").isNull).isTrue()
        val id = createDeposit("20.00")
        assertState("0.00", "0.00", null)
        assertThat(post("/api/demo/deposits/$id/complete").statusCode).isEqualTo(HttpStatus.OK)
        val response = wallet()
        assertThat(response.path("realBalance").asText()).isEqualTo("20.00")
        assertThat(response.path("bonusBalance").asText()).isEqualTo("20.00")
        val bonus = response.path("bonus")
        assertThat(bonus.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("status", "initialAmount", "wageringProgress", "wageringTarget", "expiresAt")
        for ((field, value) in mapOf("initialAmount" to "20.00", "wageringProgress" to "0.00", "wageringTarget" to "400.00")) {
            assertThat(bonus.path(field).isTextual).isTrue()
            assertThat(bonus.path(field).asText()).isEqualTo(value)
        }
        assertThat(bonus.path("expiresAt").asText()).isEqualTo(EXPIRES_AT.toString())
        val before = snapshot()
        assertThat(post("/api/demo/deposits/$id/complete").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(callback(id, "20.00").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(snapshot()).isEqualTo(before)
        assertError(post("/api/demo/deposits/${UUID.randomUUID()}/complete"), HttpStatus.NOT_FOUND, "DEPOSIT_NOT_FOUND")
        assertError(post("/api/demo/deposits/not-a-uuid/complete"), HttpStatus.BAD_REQUEST, "INVALID_REQUEST")
    }

    @ParameterizedTest
    @CsvSource("-1, ACTIVE, 20.00, 0", "0, EXPIRED, 0.00, 1", "1, EXPIRED, 0.00, 1")
    fun `wallet expiration uses the exact clock boundary and is idempotent`(
        nanos: Long, status: String, bonusBalance: String, forfeitures: Long,
    ) {
        fund()
        clock.now = EXPIRES_AT.plusNanos(nanos)
        val summary = wallet()
        assertThat(summary.path("bonus").path("status").asText()).isEqualTo(status)
        assertState("20.00", bonusBalance, status)
        assertThat(lifecycleEntries().size.toLong()).isEqualTo(forfeitures)
        if (forfeitures > 0) assertEntry("BONUS_FORFEITED", "BONUS", "-20.00", "0.00")
        val beforeRepeat = snapshot()
        wallet()
        assertThat(snapshot()).isEqualTo(beforeRepeat)
    }

    @ParameterizedTest
    @CsvSource("399.99, ACTIVE, 20.00, 20.00", "400.00, COMPLETED, 40.00, 0.00", "405.00, COMPLETED, 40.00, 0.00")
    fun `existing Stage 5 wagering at or above target completes without losing history`(
        progress: String, status: String, real: String, bonus: String,
    ) {
        seedStage5(progress)
        val history = jdbc.queryForList("select version, checksum from flyway_schema_history order by installed_rank")
        val beforeMigration = snapshot()
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1)
        flyway.validate()
        assertThat(snapshot()).isEqualTo(beforeMigration)
        assertThat(jdbc.queryForList("select version, checksum from flyway_schema_history where version <> '6' order by installed_rank"))
            .isEqualTo(history)
        assertThat(flyway.info().applied().map { it.version.toString() }).containsExactly("1", "2", "3", "4", "5", "6")
        assertThat(wallet().path("bonus").path("status").asText()).isEqualTo(status)
        assertState(real, bonus, status)
        if (status == "COMPLETED") {
            assertEntry("BONUS_CONVERTED", "BONUS", "-20.00", "0.00")
            assertEntry("BONUS_CONVERTED", "REAL", "20.00", "40.00")
            val completed = snapshot()
            wallet()
            assertThat(snapshot()).isEqualTo(completed)
        }
    }

    @ParameterizedTest
    @CsvSource("0.00, 400.00", "4.99, 404.99")
    fun `completing mixed round converts the remaining bonus including its final win`(extraStake: String, progress: String) {
        prepareMixedNearTarget()
        if (BigDecimal(extraStake).signum() > 0) assertThat(play(extraStake, extraStake).statusCode).isEqualTo(HttpStatus.OK)
        val result = play("5.00", "10.00")
        assertThat(result.statusCode).isEqualTo(HttpStatus.OK)
        val body = checkNotNull(result.body)
        assertThat(body.path("realBalance").asText()).isEqualTo("26.00")
        assertThat(body.path("bonusBalance").asText()).isEqualTo("0.00")
        val round = jdbc.queryForMap("select * from game_round where id = ?", UUID.fromString(body.path("roundId").asText()))
        assertThat(round["real_win"]).isEqualTo(BigDecimal("2.00"))
        assertThat(round["bonus_win"]).isEqualTo(BigDecimal("8.00"))
        assertEntry("BONUS_CONVERTED", "BONUS", "-24.00", "0.00")
        assertEntry("BONUS_CONVERTED", "REAL", "24.00", "26.00")
        assertState("26.00", "0.00", "COMPLETED")
        assertThat(wallet().path("bonus").path("wageringProgress").asText()).isEqualTo(progress)
        assertThat(play("8.00", "0.00").statusCode).isEqualTo(HttpStatus.OK)
        val later = createDeposit("1.00")
        assertThat(callback(later, "1.00").statusCode).isEqualTo(HttpStatus.OK)
        assertState("19.00", "0.00", "COMPLETED")
        assertThat(lifecycleEntries()).hasSize(2)
    }

    @Test
    fun `zero remaining bonus completes without a zero conversion entry`() {
        fund()
        repeat(8) { assertThat(play("5.00", "0.00").statusCode).isEqualTo(HttpStatus.OK) }
        val id = createDeposit("10.00")
        assertThat(callback(id, "10.00").statusCode).isEqualTo(HttpStatus.OK)
        repeat(72) { assertThat(play("5.00", "5.00").statusCode).isEqualTo(HttpStatus.OK) }
        assertState("10.00", "0.00", "COMPLETED")
        assertThat(lifecycleEntries()).isEmpty()
    }

    @Test
    fun `zero remaining bonus expires without a zero forfeiture entry`() {
        fund()
        repeat(8) { assertThat(play("5.00", "0.00").statusCode).isEqualTo(HttpStatus.OK) }
        clock.now = EXPIRES_AT
        wallet()
        assertState("0.00", "0.00", "EXPIRED")
        assertThat(lifecycleEntries()).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["wallet", "callback", "round", "demo"])
    fun `valid financial boundaries resolve expiration before their own work`(trigger: String) {
        fund()
        val pending = createDeposit("1.00")
        clock.now = EXPIRES_AT
        when (trigger) {
            "wallet" -> wallet()
            "callback" -> assertThat(callback(pending, "1.00").statusCode).isEqualTo(HttpStatus.OK)
            "demo" -> assertThat(post("/api/demo/deposits/$pending/complete").statusCode).isEqualTo(HttpStatus.OK)
            "round" -> assertThat(play("8.00", "0.00").statusCode).isEqualTo(HttpStatus.OK)
        }
        val real = when (trigger) { "callback", "demo" -> "21.00"; "round" -> "12.00"; else -> "20.00" }
        assertState(real, "0.00", "EXPIRED")
        assertThat(lifecycleEntries()).hasSize(1)
    }

    @Test
    fun `pending creation invalid mismatched and duplicate callbacks do not resolve lifecycle`() {
        val completed = fund()
        val pending = createDeposit("1.00")
        clock.now = EXPIRES_AT
        val before = snapshot()
        assertError(callback(pending, "1.00", "0".repeat(64)), HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE")
        assertError(callback(pending, "2.00"), HttpStatus.CONFLICT, "DEPOSIT_AMOUNT_MISMATCH")
        assertThat(callback(completed, "20.00").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(post("/api/demo/deposits/$completed/complete").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(snapshot()).isEqualTo(before)
        createDeposit("2.00")
        assertState("20.00", "20.00", "ACTIVE")
        assertThat(lifecycleEntries()).isEmpty()
    }

    @Test
    fun `insufficient funds after expiration commits forfeiture without creating a round`() {
        fund()
        for (stake in listOf("5.00", "5.00", "5.00", "4.00")) {
            assertThat(play(stake, "0.00").statusCode).isEqualTo(HttpStatus.OK)
        }
        val before = snapshot()
        clock.now = EXPIRES_AT
        assertError(play("5.00", "0.00"), HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS")
        assertState("1.00", "0.00", "EXPIRED")
        assertEntry("BONUS_FORFEITED", "BONUS", "-20.00", "0.00")
        assertThat(snapshot()["rounds"]).isEqualTo(before["rounds"])
        assertThat(lifecycleEntries()).hasSize(1)
    }

    @ParameterizedTest
    @ValueSource(strings = ["round", "callback"])
    fun `expected balance limit rejection also preserves an already resolved expiration`(operation: String) {
        val id = createDeposit("99999999999999999.99")
        assertThat(callback(id, "99999999999999999.99").statusCode).isEqualTo(HttpStatus.OK)
        val pending = createDeposit("0.01")
        clock.now = EXPIRES_AT
        val response = if (operation == "round") play("0.01", "0.02") else callback(pending, "0.01")
        assertError(response, HttpStatus.CONFLICT, "WALLET_BALANCE_LIMIT")
        assertState("99999999999999999.99", "0.00", "EXPIRED")
        assertThat(jdbc.queryForObject<Long>("select count(*) from game_round")).isZero()
        assertEntry("BONUS_FORFEITED", "BONUS", "-100.00", "0.00")
        assertThat(jdbc.queryForObject("select status from deposit where id = ?", String::class.java, pending)).isEqualTo("PENDING")
    }

    @ParameterizedTest
    @CsvSource("400.00, COMPLETED", "395.00, EXPIRED")
    fun `concurrent wallet and round resolution contend and never both convert and forfeit`(progress: String, status: String) {
        seedStage5(progress)
        flyway.migrate()
        clock.now = EXPIRES_AT
        val executor = Executors.newFixedThreadPool(2)
        val responses = try {
            dataSource.connection.use { blocker ->
                blocker.autoCommit = false
                val pid = PostgresLockProbe.lockWallet(blocker, DemoPlayer.ID)
                val first = executor.submit<ResponseEntity<JsonNode>> { request("/api/wallet", HttpMethod.GET) }
                val second = executor.submit<ResponseEntity<JsonNode>> { play("0.01", "0.00") }
                try {
                    PostgresLockProbe.awaitBlockedSessions(jdbc, pid, 2)
                    assertState("20.00", "20.00", "ACTIVE")
                } finally {
                    blocker.rollback()
                }
                listOf(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
            }
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
        assertThat(responses.map { it.statusCode }).containsOnly(HttpStatus.OK)
        assertState(if (status == "COMPLETED") "39.99" else "19.99", "0.00", status)
        assertThat(lifecycleEntries().map { it["operation_type"] }).containsOnly(
            if (status == "COMPLETED") "BONUS_CONVERTED" else "BONUS_FORFEITED",
        )
        assertThat(lifecycleEntries()).hasSize(if (status == "COMPLETED") 2 else 1)
        val resolved = snapshot()
        wallet()
        assertThat(snapshot()).isEqualTo(resolved)
    }

    @ParameterizedTest
    @CsvSource("400.00, COMPLETED", "0.00, EXPIRED")
    fun `technical lifecycle failure rolls back and logs one safe correlated stack trace`(
        progress: String, status: String, output: CapturedOutput,
    ) {
        seedStage5(progress)
        flyway.migrate()
        clock.now = EXPIRES_AT
        val before = snapshot()
        installLifecycleFailure()
        val counter = metrics.get("casino.wallet.bonus.lifecycle").tag("outcome", status.lowercase()).counter()
        val countBefore = counter.count()
        assertError(request("/api/wallet", HttpMethod.GET), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR")
        assertThat(counter.count()).isEqualTo(countBefore)
        assertThat(jdbc.queryForObject<Boolean>("select is_called from lifecycle_failure_observed")).isTrue()
        assertThat(snapshot()).isEqualTo(before)
        assertState("20.00", "20.00", "ACTIVE")
        val failures = output.all.lineSequence().filter { "event=api_failure" in it }.toList()
        assertThat(failures).hasSize(1)
        assertThat(failures.single()).contains("ERROR", "request_id=lifecycle-test")
        assertThat(output.all).contains("at com.example.casinowallet", "PSQLException")
        assertThat(output.all).doesNotContain("lifecycle-test-secret", "sensitive-signature-value", "sensitive-raw-callback")
        assertThat(output.all).doesNotContain("event=bonus_completed", "event=bonus_expired")
        jdbc.execute("drop trigger fail_lifecycle_commit on bonus")
        jdbc.execute("drop function fail_lifecycle_commit()")
        wallet()
        assertState(if (status == "COMPLETED") "40.00" else "20.00", "0.00", status)
        assertThat(counter.count()).isEqualTo(countBefore + 1)
        wallet()
        assertThat(counter.count()).isEqualTo(countBefore + 1)
    }

    @Test
    fun `failure after completing round rolls back the round win progress and conversion together`() {
        prepareMixedNearTarget()
        val before = snapshot()
        installLifecycleFailure()
        assertError(play("5.00", "10.00"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR")
        assertThat(jdbc.queryForObject<Boolean>("select is_called from lifecycle_failure_observed")).isTrue()
        assertThat(snapshot()).isEqualTo(before)
        assertState("1.00", "20.00", "ACTIVE")
    }

    @ParameterizedTest
    @ValueSource(strings = ["callback", "demo", "round"])
    fun `technical failure rolls back expiration and the triggering financial operation`(trigger: String, output: CapturedOutput) {
        fund()
        val pending = createDeposit("1.00")
        clock.now = EXPIRES_AT
        val before = snapshot()
        val logStart = output.all.length
        installLifecycleFailure()
        val response = when (trigger) {
            "callback" -> callback(pending, "1.00")
            "demo" -> post("/api/demo/deposits/$pending/complete")
            else -> play("1.00", "0.00")
        }
        assertError(response, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR")
        assertThat(jdbc.queryForObject<Boolean>("select is_called from lifecycle_failure_observed")).isTrue()
        assertThat(snapshot()).isEqualTo(before)
        val logs = output.all.substring(logStart)
        assertThat(logs.lineSequence().filter { "event=api_failure" in it }.toList()).hasSize(1)
        assertThat(logs).contains("ERROR", "request_id=lifecycle-test", "PSQLException")
        assertThat(logs).doesNotContain("event=bonus_expired", "event=round_completed", "event=deposit_callback_completed",
            "event=demo_deposit_completed", "lifecycle-test-secret", "sensitive-signature-value", "sensitive-raw-callback")
    }

    @Test
    fun `ledger database failure returns the shared sanitized error contract`(output: CapturedOutput) {
        jdbc.execute("alter table ledger_entry rename to temporarily_unavailable_ledger")
        val logStart = output.all.length
        try {
            assertError(request("/api/ledger", HttpMethod.GET), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR")
            val logs = output.all.substring(logStart)
            assertThat(logs.lineSequence().filter { "event=api_failure" in it }.toList()).hasSize(1)
            assertThat(logs).contains("ERROR", "request_id=lifecycle-test", "PSQLException")
            assertThat(logs).doesNotContain("select id,", "relation \"ledger_entry\"", "does not exist")
        } finally {
            jdbc.execute("alter table temporarily_unavailable_ledger rename to ledger_entry")
        }
    }

    @Test
    fun `expected rejections are warnings without leaking signatures payloads or secrets`(output: CapturedOutput) {
        val id = fund()
        val invalidSignature = "a".repeat(64)
        assertError(callback(id, "20.00", invalidSignature), HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE")
        assertError(callback(id, "21.00"), HttpStatus.CONFLICT, "DEPOSIT_AMOUNT_MISMATCH")
        assertError(play("5.01", "0.00"), HttpStatus.CONFLICT, "MAX_BET_EXCEEDED")
        assertThat(output.all).contains("WARN", "code=INVALID_SIGNATURE", "code=DEPOSIT_AMOUNT_MISMATCH", "code=MAX_BET_EXCEEDED")
        assertThat(output.all).doesNotContain("event=api_failure", "lifecycle-test-secret", invalidSignature,
            "\"depositId\"", "amountCents")
    }

    private fun prepareMixedNearTarget() {
        fund()
        for (stake in listOf("5.00", "5.00", "5.00", "4.00")) {
            assertThat(play(stake, "0.00").statusCode).isEqualTo(HttpStatus.OK)
        }
        repeat(75) { assertThat(play("5.00", "5.00").statusCode).isEqualTo(HttpStatus.OK) }
        assertThat(play("1.00", "1.00").statusCode).isEqualTo(HttpStatus.OK)
        assertState("1.00", "20.00", "ACTIVE")
        assertThat(jdbc.queryForObject<BigDecimal>("select wagering_progress from bonus")).isEqualTo(BigDecimal("395.00"))
    }

    private fun seedStage5(progress: String) {
        // Reconstruct valid Stage 5 history: grant 20 and legal neutral rounds; every balance change has a ledger row.
        flyway.clean()
        Flyway.configure().dataSource(dataSource).target("5").load().migrate()
        val deposit = UUID.randomUUID()
        jdbc.update("insert into deposit (id, player_id, amount, status, completed_at) values (?, ?, 20, 'COMPLETED', ?::timestamptz)",
            deposit, DemoPlayer.ID, GRANTED_AT.toString())
        jdbc.update("insert into bonus (id, player_id, source_deposit_id, initial_amount, wagering_target, status, granted_at, expires_at) values (?, ?, ?, 20, 400, 'ACTIVE', ?::timestamptz, ?::timestamptz)",
            UUID.randomUUID(), DemoPlayer.ID, deposit, GRANTED_AT.toString(), EXPIRES_AT.toString())
        insertHistory("REAL", "DEPOSIT_COMPLETED", "20.00", "20.00", "DEPOSIT", deposit)
        insertHistory("BONUS", "WELCOME_BONUS_GRANTED", "20.00", "20.00", "DEPOSIT", deposit)
        jdbc.update("update wallet set real_balance = 20, bonus_balance = 20 where player_id = ?", DemoPlayer.ID)
        var remaining = BigDecimal(progress)
        while (remaining.signum() > 0) {
            val stake = remaining.min(BigDecimal("5.00"))
            val round = UUID.randomUUID()
            jdbc.update("insert into game_round (id, player_id, stake, total_win, real_stake, bonus_stake, real_win, bonus_win) values (?, ?, ?, ?, ?, 0, ?, 0)",
                round, DemoPlayer.ID, stake, stake, stake, stake)
            insertHistory("REAL", "ROUND_STAKE", stake.negate().toPlainString(), (BigDecimal("20.00") - stake).toPlainString(), "GAME_ROUND", round)
            insertHistory("REAL", "ROUND_WIN", stake.toPlainString(), "20.00", "GAME_ROUND", round)
            remaining -= stake
        }
        jdbc.update("update bonus set wagering_progress = ?::numeric where player_id = ?", progress, DemoPlayer.ID)
        assertState("20.00", "20.00", "ACTIVE")
    }

    private fun insertHistory(wallet: String, operation: String, amount: String, balance: String, reference: String, id: UUID) {
        jdbc.update("insert into ledger_entry (id, player_id, wallet_type, operation_type, amount, balance_after, reference_type, reference_id) values (?, ?, ?, ?, ?::numeric, ?::numeric, ?, ?)",
            UUID.randomUUID(), DemoPlayer.ID, wallet, operation, amount, balance, reference, id)
    }

    private fun installLifecycleFailure() {
        jdbc.execute(
            // language=PostgreSQL
            """
            create sequence lifecycle_failure_observed;
            create function fail_lifecycle_commit() returns trigger language plpgsql as $$
            begin
                if new.status <> 'ACTIVE'
                   and current_setting('transaction_isolation') = 'read committed'
                   and exists (select 1 from wallet where player_id = new.player_id and bonus_balance = 0)
                   and exists (select 1 from ledger_entry where reference_type = 'BONUS' and reference_id = new.id) then
                    perform nextval('lifecycle_failure_observed');
                    raise exception 'lifecycle-test-secret sensitive-signature-value sensitive-raw-callback';
                end if;
                return new;
            end;
            $$;
            create constraint trigger fail_lifecycle_commit after update on bonus
                deferrable initially deferred for each row execute function fail_lifecycle_commit();
            """.trimIndent(),
        )
    }

    private fun fund(): UUID = createDeposit("20.00").also { assertThat(callback(it, "20.00").statusCode).isEqualTo(HttpStatus.OK) }

    private fun createDeposit(amount: String): UUID {
        val response = post("/api/deposits", """{"amount":"$amount"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        return UUID.fromString(checkNotNull(response.body).path("depositId").asText())
    }

    private fun callback(id: UUID, amount: String, signature: String? = null): ResponseEntity<JsonNode> {
        val body = """{"depositId":"$id","amountCents":${BigDecimal(amount).movePointRight(2).toBigIntegerExact()}}"""
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("lifecycle-test-secret".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signed = signature ?: HexFormat.of().formatHex(mac.doFinal(body.toByteArray(Charsets.UTF_8)))
        return request("/api/provider/deposits/callback", HttpMethod.POST, body, signed)
    }

    private fun play(stake: String, win: String): ResponseEntity<JsonNode> =
        post("/api/rounds/play", """{"stake":"$stake","totalWin":"$win"}""")

    private fun wallet(): JsonNode {
        val response = request("/api/wallet", HttpMethod.GET)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return checkNotNull(response.body)
    }

    private fun post(path: String, body: String? = null) = request(path, HttpMethod.POST, body)

    private fun request(path: String, method: HttpMethod, body: String? = null, signature: String? = null): ResponseEntity<JsonNode> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Request-ID", "lifecycle-test")
            signature?.let { set("X-Signature", it) }
        }
        val response = http.exchange<JsonNode>(path, method, HttpEntity(body?.toByteArray(Charsets.UTF_8), headers))
        assertThat(response.headers.getFirst("X-Request-ID")).isEqualTo("lifecycle-test")
        return response
    }

    private fun assertError(response: ResponseEntity<JsonNode>, status: HttpStatus, code: String) {
        assertThat(response.statusCode).isEqualTo(status)
        val error = checkNotNull(response.body)
        assertThat(error.path("code").asText()).isEqualTo(code)
        assertThat(error.toString()).doesNotContain("SQL", "trace", "exception", "lifecycle-test-secret", "sensitive-")
    }

    private fun assertState(real: String, bonus: String, status: String?) {
        val wallet = jdbc.queryForMap("select real_balance, bonus_balance from wallet where player_id = ?", DemoPlayer.ID)
        assertThat(wallet["real_balance"]).isEqualTo(BigDecimal(real))
        assertThat(wallet["bonus_balance"]).isEqualTo(BigDecimal(bonus))
        if (status == null) assertThat(jdbc.queryForObject<Long>("select count(*) from bonus")).isZero()
        else assertThat(jdbc.queryForObject<String>("select status from bonus")).isEqualTo(status)
        for ((type, amount) in mapOf("REAL" to real, "BONUS" to bonus)) {
            assertThat(jdbc.queryForObject("select coalesce(sum(amount), 0.00) from ledger_entry where wallet_type = ? and player_id = ?",
                BigDecimal::class.java, type, DemoPlayer.ID)).isEqualByComparingTo(BigDecimal(amount))
        }
        assertThat(jdbc.queryForObject<Long>("select count(*) from ledger_entry where amount = 0 or balance_after < 0")).isZero()
    }

    private fun lifecycleEntries() = jdbc.queryForList("select * from ledger_entry where reference_type = 'BONUS' order by id")

    private fun assertEntry(operation: String, wallet: String, amount: String, balance: String) {
        val entry = lifecycleEntries().single { it["operation_type"] == operation && it["wallet_type"] == wallet }
        assertThat(entry["amount"]).isEqualTo(BigDecimal(amount))
        assertThat(entry["balance_after"]).isEqualTo(BigDecimal(balance))
        assertThat(entry["reference_id"]).isEqualTo(jdbc.queryForObject<UUID>("select id from bonus"))
    }

    private fun snapshot() = mapOf(
        "wallet" to jdbc.queryForList("select * from wallet order by player_id"),
        "deposits" to jdbc.queryForList("select * from deposit order by id"),
        "bonus" to jdbc.queryForList("select * from bonus order by id"),
        "rounds" to jdbc.queryForList("select * from game_round order by id"),
        "ledger" to jdbc.queryForList("select * from ledger_entry order by id"),
    )

    class TestClock : Clock() {
        @Volatile var now: Instant = GRANTED_AT
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(now, zone)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FixedTime {
        @Bean @Primary
        fun testClock(): TestClock = TestClock()
    }

    companion object {
        private val GRANTED_AT = Instant.parse("2026-01-01T12:00:00Z")
        private val EXPIRES_AT = Instant.parse("2026-01-08T12:00:00Z")

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
