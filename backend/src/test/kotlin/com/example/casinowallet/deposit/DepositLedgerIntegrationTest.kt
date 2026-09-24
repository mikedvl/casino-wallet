package com.example.casinowallet.deposit

import com.example.casinowallet.CasinoWalletApplication
import com.example.casinowallet.deposit.application.DepositApplicationService
import com.example.casinowallet.deposit.application.DepositCompletion
import com.example.casinowallet.support.PostgresLockProbe
import io.micrometer.core.instrument.MeterRegistry
import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.getBean
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.client.exchange
import org.springframework.boot.test.web.client.getForEntity
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.annotation.DirtiesContext
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
    properties = ["spring.flyway.clean-disabled=false", "payment.provider.hmac-secret=integration-test-secret"],
)
class DepositLedgerIntegrationTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val http: TestRestTemplate,
    private val flyway: Flyway,
    private val dataSource: DataSource,
    private val metrics: MeterRegistry,
) {
    @BeforeEach
    fun resetIsolatedDatabase() {
        // Only this test class's disposable PostgreSQL container is cleaned.
        flyway.clean()
        flyway.migrate()
    }

    @Test
    fun `creation stays pending and signed callback credits exactly once`() {
        val completed = metrics.get("casino.wallet.deposit.callbacks")
            .tags("operation", "provider_callback", "outcome", "completed").counter()
        val duplicate = metrics.get("casino.wallet.deposit.callbacks")
            .tags("operation", "provider_callback", "outcome", "duplicate").counter()
        val completedBefore = completed.count()
        val duplicateBefore = duplicate.count()
        val id = createDeposit("25")
        assertState(id, "PENDING", "0.00", 0)
        assertThat(callback(id, "2500").statusCode).isEqualTo(HttpStatus.OK)
        assertState(id, "COMPLETED", "25.00", 2, "25.00")
        assertThat(callback(id, "2500").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(completed.count()).isEqualTo(completedBefore + 1)
        assertThat(duplicate.count()).isEqualTo(duplicateBefore + 1)
        assertState(id, "COMPLETED", "25.00", 2, "25.00")
        assertError(callback(id, "2600"), HttpStatus.CONFLICT, "DEPOSIT_AMOUNT_MISMATCH")
        assertError(callback(id, "2500", "0".repeat(64)), HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE")
        assertState(id, "COMPLETED", "25.00", 2, "25.00")

        val wallet = checkNotNull(http.getForEntity<JsonNode>("/api/wallet").body)
        assertThat(wallet.path("realBalance").isTextual).isTrue()
        assertThat(wallet.path("realBalance").asText()).isEqualTo("25.00")
        assertThat(wallet.path("bonusBalance").asText()).isEqualTo("25.00")
        val ledger = checkNotNull(http.getForEntity<JsonNode>("/api/ledger").body)
        assertThat(ledger.path("totalElements").asLong()).isEqualTo(2L)
        val entry = ledger.path("items").single { it.path("operationType").asText() == "DEPOSIT_COMPLETED" }
        assertThat(entry.path("amount").isTextual).isTrue()
        assertThat(entry.path("amount").asText()).isEqualTo("25.00")
        assertThat(entry.path("balanceAfter").asText()).isEqualTo("25.00")
        assertThat(entry.path("referenceId").asText()).isEqualTo(id.toString())
        assertThat(entry.path("walletType").asText()).isEqualTo("REAL")
        assertThat(entry.path("operationType").asText()).isEqualTo("DEPOSIT_COMPLETED")
        assertThat(entry.path("referenceType").asText()).isEqualTo("DEPOSIT")
        assertThat(entry.path("createdAt").asText()).endsWith("Z")
        assertReconciled()
    }

    @Test
    fun `invalid signature precedes JSON parsing and database lookup`() {
        assertError(post("/api/provider/deposits/callback", "not JSON"), HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE")
        assertError(callback(UUID.randomUUID(), "2500", "bad"), HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE")
        assertError(callback(UUID.randomUUID(), "2500"), HttpStatus.NOT_FOUND, "DEPOSIT_NOT_FOUND")
        val id = createDeposit()
        assertError(callback(id, "2400"), HttpStatus.CONFLICT, "DEPOSIT_AMOUNT_MISMATCH")
        assertState(id, "PENDING", "0.00", 0)
    }

    @Test
    fun `deposit creation does not request a wallet financial lock`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            dataSource.connection.use { blocker ->
                blocker.autoCommit = false
                blocker.createStatement().use { statement ->
                    // Foreign-key KEY SHARE is compatible; FOR UPDATE would block here.
                    statement.executeQuery("select player_id from wallet for no key update").use { it.next() }
                }
                try {
                    val response = executor.submit<ResponseEntity<JsonNode>> {
                        post("/api/deposits", """{"amount":"25.00"}""")
                    }.get(5, TimeUnit.SECONDS)
                    assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
                } finally {
                    blocker.rollback()
                }
            }
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `concurrent duplicate callbacks wait on wallet before deposit and credit once`() {
        val id = createDeposit()
        val executor = Executors.newFixedThreadPool(2)
        try {
            dataSource.connection.use { blocker ->
                blocker.autoCommit = false
                val blockerPid = PostgresLockProbe.lockWallet(blocker, DEMO_PLAYER_ID)
                val callbacks = List(2) { executor.submit<ResponseEntity<JsonNode>> { callback(id, "2500") } }
                try {
                    PostgresLockProbe.awaitBlockedSessions(jdbc, blockerPid, 2)
                    dataSource.connection.use { probe ->
                        probe.autoCommit = false
                        try {
                            probe.prepareStatement("select id from deposit where id = ? for update nowait").use {
                                it.setObject(1, id)
                                it.executeQuery().use { row -> assertThat(row.next()).isTrue() }
                            }
                        } finally {
                            probe.rollback()
                        }
                    }
                    assertState(id, "PENDING", "0.00", 0)
                } finally {
                    blocker.rollback()
                }
                for (future in callbacks) {
                    assertThat(future.get(10, TimeUnit.SECONDS).statusCode).isEqualTo(HttpStatus.OK)
                }
            }
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
        assertState(id, "COMPLETED", "25.00", 2, "25.00")
        assertReconciled()
    }

    @Test
    fun `database failure after wallet and ledger writes rolls back the complete callback`() {
        val id = createDeposit()
        val completed = metrics.get("casino.wallet.deposit.callbacks")
            .tags("operation", "provider_callback", "outcome", "completed").counter()
        val failed = metrics.get("casino.wallet.deposit.callbacks")
            .tags("operation", "provider_callback", "outcome", "failed").counter()
        val completedBefore = completed.count()
        val failedBefore = failed.count()
        jdbc.execute(
            // language=PostgreSQL
            """
            create function fail_completed_deposit() returns trigger language plpgsql as $$
            begin
                if new.status = 'COMPLETED'
                   and exists (select 1 from wallet where player_id = new.player_id and real_balance = new.amount)
                   and exists (select 1 from ledger_entry where reference_id = new.id) then
                    raise exception 'Controlled integration test failure';
                end if;
                return new;
            end;
            $$;
            create trigger fail_completed_deposit before update on deposit
                for each row execute function fail_completed_deposit();
            """.trimIndent(),
        )
        assertError(callback(id, "2500"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR")
        assertThat(completed.count()).isEqualTo(completedBefore)
        assertThat(failed.count()).isEqualTo(failedBefore + 1)
        assertState(id, "PENDING", "0.00", 0)
        jdbc.execute("drop trigger fail_completed_deposit on deposit")
        jdbc.execute("drop function fail_completed_deposit()")
        assertThat(callback(id, "2500").statusCode).isEqualTo(HttpStatus.OK)
        assertState(id, "COMPLETED", "25.00", 2, "25.00")
        assertThat(completed.count()).isEqualTo(completedBefore + 1)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "{}", "null", "{\"amount\":25}", "{\"amount\":null}", "{\"amount\":\"0.00\"}",
        "{\"amount\":\"-1.00\"}", "{\"amount\":\"1.001\"}", "{\"amount\":\"1e2\"}",
        "{\"amount\":\"100000000000000000.00\"}",
    ])
    fun `invalid create requests leave financial state untouched`(body: String) {
        assertError(post("/api/deposits", body), HttpStatus.BAD_REQUEST, "INVALID_DEPOSIT_AMOUNT")
        assertThat(jdbc.queryForObject<Long>("select count(*) from deposit")).isZero()
        assertReconciled()
    }

    @Test
    fun `ledger uniqueness failure also rolls back a callback wallet credit`() {
        val id = createDeposit()
        // Model a pre-existing operation with stale deposit status; uniqueness must still prevent double credit.
        jdbc.update("update wallet set real_balance = 25.00 where player_id = ?", DEMO_PLAYER_ID)
        jdbc.update(
            // language=PostgreSQL
            """
            insert into ledger_entry (id, player_id, wallet_type, operation_type, amount, balance_after, reference_type, reference_id)
            values (?, ?, 'REAL', 'DEPOSIT_COMPLETED', 25.00, 25.00, 'DEPOSIT', ?)
            """.trimIndent(),
            UUID.randomUUID(), DEMO_PLAYER_ID, id,
        )
        assertError(callback(id, "2500"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR")
        assertState(id, "PENDING", "25.00", 1)
        assertReconciled()
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "-1", "2500.0", "25.5", "\"2500\"", "null", "true", "10000000000000000000"])
    fun `callback requires positive integral cents within the money range`(cents: String) {
        val id = createDeposit()
        assertError(callback(id, cents), HttpStatus.BAD_REQUEST, "INVALID_CALLBACK")
        assertState(id, "PENDING", "0.00", 0)
    }

    @Test
    fun `signed malformed ambiguous and invalid UUID callback bodies are rejected`() {
        for (body in listOf(
            "not JSON", "null", "{}", """{"depositId":"invalid","amountCents":2500}""",
            """{"depositId":"1-1-1-1-1","amountCents":2500}""",
            """{"depositId":"$DEMO_PLAYER_ID","amountCents":2500} {}""",
            """{"depositId":"$DEMO_PLAYER_ID","amountCents":2500,"amountCents":2500}""",
        )) {
            assertError(post("/api/provider/deposits/callback", body, sign(body)), HttpStatus.BAD_REQUEST, "INVALID_CALLBACK")
        }
        assertReconciled()
    }

    @Test
    fun `signature covers exact JSON whitespace and field order`() {
        val id = createDeposit()
        val body = """{"depositId":"$id","amountCents":2500}"""
        val reformatted = """{ "amountCents": 2500, "depositId": "$id" }"""
        assertError(post("/api/provider/deposits/callback", reformatted, sign(body)), HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE")
        assertState(id, "PENDING", "0.00", 0)
        assertThat(post("/api/provider/deposits/callback", reformatted, sign(reformatted)).statusCode).isEqualTo(HttpStatus.OK)
        assertState(id, "COMPLETED", "25.00", 2, "25.00")
    }

    @Test
    fun `invalid signature is rejected even while PostgreSQL cannot answer`() {
        val executor = Executors.newSingleThreadExecutor()
        val docker = postgres.dockerClient
        docker.pauseContainerCmd(postgres.containerId).exec()
        try {
            val response = executor.submit<ResponseEntity<JsonNode>> {
                post("/api/provider/deposits/callback", "not JSON", "bad")
            }.get(3, TimeUnit.SECONDS)
            assertError(response, HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE")
        } finally {
            docker.unpauseContainerCmd(postgres.containerId).exec()
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
        assertReconciled()
    }

    @Test
    fun `largest supported amount retains precision and a further credit rolls back`() {
        val id = createDeposit("99999999999999999.99")
        assertThat(callback(id, "9999999999999999999").statusCode).isEqualTo(HttpStatus.OK)
        assertState(id, "COMPLETED", "99999999999999999.99", 2, "100.00")
        val another = createDeposit("0.01")
        assertError(callback(another, "1"), HttpStatus.CONFLICT, "WALLET_BALANCE_LIMIT")
        assertState(another, "PENDING", "99999999999999999.99", 2, "100.00")
        val ledger = checkNotNull(http.getForEntity<JsonNode>("/api/ledger").body).path("items")
            .single { it.path("operationType").asText() == "DEPOSIT_COMPLETED" }
        assertThat(ledger.path("amount").asText()).isEqualTo("99999999999999999.99")
        assertThat(ledger.path("balanceAfter").asText()).isEqualTo("99999999999999999.99")
        assertReconciled()
    }

    @Test
    fun `a fresh application instance recognizes durable completion`() {
        val id = createDeposit()
        assertThat(callback(id, "2500").statusCode).isEqualTo(HttpStatus.OK)
        SpringApplicationBuilder(CasinoWalletApplication::class.java).web(WebApplicationType.NONE).run(
            "--spring.datasource.url=${postgres.jdbcUrl}",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--payment.provider.hmac-secret=integration-test-secret",
        ).use { context ->
            val result = context.getBean<DepositApplicationService>().complete(id, BigDecimal("25.00"))
            assertThat(result).isInstanceOfSatisfying(DepositCompletion::class.java) { assertThat(it.duplicate).isTrue() }
        }
        assertState(id, "COMPLETED", "25.00", 2, "25.00")
        assertReconciled()
    }

    @Test
    fun `multiple completions reconcile and retain the actual balance after each credit`() {
        val first = createDeposit("1.99")
        val second = createDeposit("0.01")
        assertThat(callback(first, "199").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(callback(second, "1").statusCode).isEqualTo(HttpStatus.OK)
        assertState(second, "COMPLETED", "2.00", 2)
        val items = checkNotNull(http.getForEntity<JsonNode>("/api/ledger").body).path("items")
        assertThat(items.map { it.path("balanceAfter").asText() }).containsExactly("2.00", "1.99")
        assertThat(items.map { it.path("amount").asText() }).containsExactly("0.01", "1.99")
        assertReconciled()
    }

    @Test
    fun `ledger empty pages and pagination boundaries are explicit`() {
        val empty = checkNotNull(http.getForEntity<JsonNode>("/api/ledger").body)
        assertThat(empty.path("items").isEmpty).isTrue()
        assertThat(empty.path("page").asInt()).isZero()
        assertThat(empty.path("size").asInt()).isEqualTo(20)
        assertThat(empty.path("totalElements").asLong()).isZero()
        assertThat(empty.path("totalPages").asLong()).isZero()
        for (query in listOf("page=-1", "size=0", "size=-1", "size=101")) {
            assertError(http.getForEntity<JsonNode>("/api/ledger?$query"), HttpStatus.BAD_REQUEST, "INVALID_PAGINATION")
        }
        for (query in listOf("page=2147483648", "page=x", "size=1.5")) {
            assertError(http.getForEntity<JsonNode>("/api/ledger?$query"), HttpStatus.BAD_REQUEST, "INVALID_REQUEST")
        }
        assertThat(http.getForEntity<JsonNode>("/api/ledger?page=2147483647&size=100").statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `ledger pagination orders timestamp ties by UUID and filters the demo player`() {
        // Insert deterministic immutable history; no UPDATE is used to manufacture timestamp ties.
        jdbc.execute(
            // language=PostgreSQL
            """
            insert into deposit (id, player_id, amount, status, completed_at)
            select ('10000000-0000-0000-0000-00000000000' || n)::uuid,
                '00000000-0000-0000-0000-000000000001', 1.00, 'COMPLETED', '2026-01-01T00:00:00Z'
            from generate_series(1, 3) n;
            insert into ledger_entry
                (id, player_id, wallet_type, operation_type, amount, balance_after, reference_type, reference_id, created_at)
            select ('20000000-0000-0000-0000-00000000000' || n)::uuid,
                '00000000-0000-0000-0000-000000000001', 'REAL', 'DEPOSIT_COMPLETED', 1.00, n,
                'DEPOSIT', ('10000000-0000-0000-0000-00000000000' || n)::uuid,
                case when n = 1 then '2026-01-01T00:00:00Z'::timestamptz else '2026-01-02T00:00:00Z'::timestamptz end
            from generate_series(1, 3) n;
            update wallet set real_balance = 3.00 where player_id = '00000000-0000-0000-0000-000000000001';
            insert into wallet (player_id, real_balance) values ('00000000-0000-0000-0000-000000000002', 5.00);
            insert into deposit (id, player_id, amount, status, completed_at)
                values ('10000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000002',
                    5.00, 'COMPLETED', '2026-01-03T00:00:00Z');
            insert into ledger_entry
                (id, player_id, wallet_type, operation_type, amount, balance_after, reference_type, reference_id, created_at)
                values ('20000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000002',
                    'REAL', 'DEPOSIT_COMPLETED', 5.00, 5.00, 'DEPOSIT',
                    '10000000-0000-0000-0000-000000000004', '2026-01-03T00:00:00Z');
            """.trimIndent(),
        )
        val first = checkNotNull(http.getForEntity<JsonNode>("/api/ledger?page=0&size=2").body)
        val second = checkNotNull(http.getForEntity<JsonNode>("/api/ledger?page=1&size=2").body)
        assertThat(first.path("items").map { it.path("id").asText() }).containsExactly(
            "20000000-0000-0000-0000-000000000003", "20000000-0000-0000-0000-000000000002",
        )
        assertThat(second.path("items").map { it.path("id").asText() })
            .containsExactly("20000000-0000-0000-0000-000000000001")
        for (page in listOf(first, second)) {
            assertThat(page.path("totalElements").asLong()).isEqualTo(3L)
            assertThat(page.path("totalPages").asLong()).isEqualTo(2L)
            assertThat(page.path("size").asInt()).isEqualTo(2)
        }
        assertThat(second.path("page").asInt()).isEqualTo(1)
        val beyond = checkNotNull(http.getForEntity<JsonNode>("/api/ledger?page=2&size=2").body)
        assertThat(beyond.path("items").isEmpty).isTrue()
        assertThat(beyond.path("totalElements").asLong()).isEqualTo(3L)
        assertReconciled()
    }

    private fun createDeposit(amount: String = "25.00"): UUID {
        val response = post("/api/deposits", """{"amount":"$amount"}""")
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val body = checkNotNull(response.body)
        assertThat(body.path("amount").isTextual).isTrue()
        assertThat(body.path("amount").asText()).isEqualTo(BigDecimal(amount).setScale(2).toPlainString())
        assertThat(body.path("status").asText()).isEqualTo("PENDING")
        return UUID.fromString(body.path("depositId").asText())
    }

    private fun callback(id: UUID, cents: String, signature: String? = null): ResponseEntity<JsonNode> {
        val body = """{"depositId":"$id","amountCents":$cents}"""
        return post("/api/provider/deposits/callback", body, signature ?: sign(body))
    }

    private fun post(path: String, body: String, signature: String? = null): ResponseEntity<JsonNode> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Request-ID", "deposit-integration-test")
            signature?.let { set("X-Signature", it) }
        }
        val response = http.exchange<JsonNode>(path, HttpMethod.POST, HttpEntity(body.toByteArray(Charsets.UTF_8), headers))
        assertThat(response.headers.getFirst("X-Request-ID")).isEqualTo("deposit-integration-test")
        return response
    }

    private fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("integration-test-secret".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return HexFormat.of().formatHex(mac.doFinal(body.toByteArray(Charsets.UTF_8)))
    }

    private fun assertState(id: UUID, status: String, realBalance: String, ledgerCount: Long, bonusBalance: String = "0.00") {
        val deposit = jdbc.queryForMap("select status, completed_at from deposit where id = ?", id)
        assertThat(deposit["status"]).isEqualTo(status)
        assertThat(deposit["completed_at"] != null).isEqualTo(status == "COMPLETED")
        assertThat(jdbc.queryForObject("select real_balance from wallet where player_id = ?", BigDecimal::class.java, DEMO_PLAYER_ID))
            .isEqualTo(BigDecimal(realBalance))
        assertThat(jdbc.queryForObject("select bonus_balance from wallet where player_id = ?", BigDecimal::class.java, DEMO_PLAYER_ID))
            .isEqualTo(BigDecimal(bonusBalance))
        assertThat(jdbc.queryForObject("select count(*) from ledger_entry where player_id = ?", Long::class.java, DEMO_PLAYER_ID))
            .isEqualTo(ledgerCount)
    }

    private fun assertReconciled() {
        val wallet = jdbc.queryForMap("select real_balance, bonus_balance from wallet where player_id = ?", DEMO_PLAYER_ID)
        for ((type, column) in mapOf("REAL" to "real_balance", "BONUS" to "bonus_balance")) {
            val ledgerTotal = jdbc.queryForObject(
                "select coalesce(sum(amount), 0.00) from ledger_entry where player_id = ? and wallet_type = ?",
                BigDecimal::class.java, DEMO_PLAYER_ID, type,
            )
            assertThat(ledgerTotal).isEqualByComparingTo(wallet[column] as BigDecimal)
        }
    }

    private fun assertError(response: ResponseEntity<JsonNode>, status: HttpStatus, code: String) {
        assertThat(response.statusCode).isEqualTo(status)
        val error = checkNotNull(response.body)
        assertThat(error.path("code").asText()).isEqualTo(code)
        assertThat(error.has("trace")).isFalse()
        assertThat(error.has("exception")).isFalse()
        assertThat(error.toString()).doesNotContain("integration-test-secret", "SQL", "Controlled integration test failure")
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
