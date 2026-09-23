package com.example.casinowallet.deposit.application

import com.example.casinowallet.config.DemoPlayer
import com.example.casinowallet.deposit.domain.Deposit
import com.example.casinowallet.deposit.domain.DepositStatus
import com.example.casinowallet.deposit.persistence.JdbcDepositRepository
import com.example.casinowallet.ledger.persistence.JdbcLedgerRepository
import com.example.casinowallet.wallet.persistence.JdbcWalletRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

@Service
class DepositApplicationService(
    private val deposits: JdbcDepositRepository,
    private val wallets: JdbcWalletRepository,
    private val ledger: JdbcLedgerRepository,
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
        deposits.markCompleted(id)
        return DepositCompletion(id, duplicate = false)
    }
}

data class DepositCompletion(val depositId: UUID, val duplicate: Boolean)

class DepositNotFoundException : RuntimeException()
class DepositAmountMismatchException : RuntimeException()
class WalletBalanceLimitException : RuntimeException()
