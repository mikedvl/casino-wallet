package com.example.casinowallet.deposit.application

import com.example.casinowallet.bonus.domain.WelcomeBonusGrant
import com.example.casinowallet.bonus.persistence.JdbcBonusRepository
import com.example.casinowallet.config.DemoPlayer
import com.example.casinowallet.deposit.domain.Deposit
import com.example.casinowallet.deposit.domain.DepositStatus
import com.example.casinowallet.deposit.persistence.JdbcDepositRepository
import com.example.casinowallet.ledger.persistence.JdbcLedgerRepository
import com.example.casinowallet.wallet.application.WalletBalanceLimitException
import com.example.casinowallet.wallet.persistence.JdbcWalletRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

@Service
class DepositApplicationService(
    private val deposits: JdbcDepositRepository,
    private val wallets: JdbcWalletRepository,
    private val ledger: JdbcLedgerRepository,
    private val bonuses: JdbcBonusRepository,
    private val clock: Clock,
) {
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = [Exception::class])
    fun create(amount: BigDecimal): Deposit {
        val deposit = Deposit(UUID.randomUUID(), DemoPlayer.ID, amount, DepositStatus.PENDING)
        deposits.insert(deposit)
        return deposit
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = [Exception::class])
    fun complete(id: UUID, amount: BigDecimal): DepositCompletion {
        // The initial lookup only discovers the owner; state is re-read after wallet -> deposit locking.
        val playerId = deposits.findPlayerId(id) ?: throw DepositNotFoundException()
        wallets.lockByPlayerId(playerId)
        val deposit = deposits.findForUpdate(id) ?: throw DepositNotFoundException()
        check(deposit.playerId == playerId) { "Deposit owner changed while acquiring locks" }
        if (deposit.amount.compareTo(amount) != 0) throw DepositAmountMismatchException()
        if (deposit.status == DepositStatus.COMPLETED) return DepositCompletion(id, duplicate = true)

        val balance = wallets.creditRealBalance(playerId, deposit.amount) ?: throw WalletBalanceLimitException()
        ledger.appendDeposit(playerId, id, deposit.amount, balance)
        // Current deposit is still PENDING; older qualifying completions also consume lifetime eligibility.
        val grant = WelcomeBonusGrant.fromDeposit(deposit.amount, clock.instant())?.takeIf {
            !bonuses.existsForPlayer(playerId) && !deposits.hasCompletedQualifyingDeposit(playerId)
        }
        if (grant != null) {
            bonuses.insert(playerId, id, grant)
            val bonusBalance = wallets.creditBonusBalance(playerId, grant.amount) ?: throw WalletBalanceLimitException()
            ledger.appendWelcomeBonus(playerId, id, grant.amount, bonusBalance)
        }
        deposits.markCompleted(id)
        return DepositCompletion(id, duplicate = false, bonusGranted = grant != null)
    }
}

data class DepositCompletion(val depositId: UUID, val duplicate: Boolean, val bonusGranted: Boolean = false)

class DepositNotFoundException : RuntimeException()
class DepositAmountMismatchException : RuntimeException()
