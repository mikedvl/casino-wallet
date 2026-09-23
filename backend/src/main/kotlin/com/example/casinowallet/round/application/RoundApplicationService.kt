package com.example.casinowallet.round.application

import com.example.casinowallet.config.DemoPlayer
import com.example.casinowallet.ledger.persistence.JdbcLedgerRepository
import com.example.casinowallet.round.domain.GameRound
import com.example.casinowallet.round.domain.RoundAmounts
import com.example.casinowallet.round.persistence.JdbcRoundRepository
import com.example.casinowallet.wallet.application.WalletBalanceLimitException
import com.example.casinowallet.wallet.domain.WalletSummary
import com.example.casinowallet.wallet.persistence.JdbcWalletRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class RoundApplicationService(
    private val wallets: JdbcWalletRepository,
    private val rounds: JdbcRoundRepository,
    private val ledger: JdbcLedgerRepository,
) {
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = [Exception::class])
    fun play(amounts: RoundAmounts): RoundResult {
        val playerId = DemoPlayer.ID
        val wallet = wallets.lockByPlayerId(playerId)
        if (wallet.realBalance < amounts.stake) throw InsufficientFundsException()

        val round = GameRound(UUID.randomUUID(), playerId, amounts.stake, amounts.totalWin)
        val afterStake = wallets.debitRealBalance(playerId, round.stake) ?: throw InsufficientFundsException()
        ledger.appendRoundStake(playerId, round.id, round.stake, afterStake)
        rounds.insert(round)
        val finalBalance = if (round.totalWin.signum() > 0) {
            val afterWin = wallets.creditRealBalance(playerId, round.totalWin) ?: throw WalletBalanceLimitException()
            ledger.appendRoundWin(playerId, round.id, round.totalWin, afterWin)
            afterWin
        } else {
            afterStake
        }
        return RoundResult(round, WalletSummary(finalBalance, wallet.bonusBalance))
    }
}

data class RoundResult(val round: GameRound, val wallet: WalletSummary)

class InsufficientFundsException : RuntimeException()
