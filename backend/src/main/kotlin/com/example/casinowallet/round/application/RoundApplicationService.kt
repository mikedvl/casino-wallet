package com.example.casinowallet.round.application

import com.example.casinowallet.bonus.persistence.JdbcBonusRepository
import com.example.casinowallet.bonus.application.BonusLifecycle
import com.example.casinowallet.bonus.domain.BonusStatus
import com.example.casinowallet.config.DemoPlayer
import com.example.casinowallet.ledger.domain.WalletType
import com.example.casinowallet.ledger.persistence.JdbcLedgerRepository
import com.example.casinowallet.round.domain.GameRound
import com.example.casinowallet.round.domain.RoundAllocation
import com.example.casinowallet.round.domain.RoundAmounts
import com.example.casinowallet.round.persistence.JdbcRoundRepository
import com.example.casinowallet.wallet.application.WalletBalanceLimitException
import com.example.casinowallet.wallet.domain.WalletSummary
import com.example.casinowallet.wallet.persistence.JdbcWalletRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

@Service
class RoundApplicationService(
    private val wallets: JdbcWalletRepository,
    private val rounds: JdbcRoundRepository,
    private val ledger: JdbcLedgerRepository,
    private val bonuses: JdbcBonusRepository,
    private val lifecycle: BonusLifecycle,
) {
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = [Exception::class])
    fun play(amounts: RoundAmounts): RoundOutcome {
        val playerId = DemoPlayer.ID
        val wallet = lifecycle.resolve(playerId, wallets.lockByPlayerId(playerId))
        val activeBonus = wallet.bonus?.takeIf { it.status == BonusStatus.ACTIVE }
        // Return expected rejections before any round writes, allowing prior lifecycle resolution to commit.
        if (activeBonus != null && amounts.stake > MAX_ACTIVE_BONUS_STAKE) return RoundRejection.MAX_BET_EXCEEDED
        val available = if (activeBonus != null) wallet.realBalance + wallet.bonusBalance else wallet.realBalance
        if (available < amounts.stake) return RoundRejection.INSUFFICIENT_FUNDS

        val allocation = RoundAllocation.realFirst(amounts, wallet.realBalance)
        val projectedReal = wallet.realBalance - allocation.realStake + allocation.realWin
        val projectedBonus = wallet.bonusBalance - allocation.bonusStake + allocation.bonusWin
        val completes = activeBonus != null && activeBonus.wageringProgress + amounts.stake >= activeBonus.wageringTarget
        if (projectedReal > WalletSummary.MAX_BALANCE || projectedBonus > WalletSummary.MAX_BALANCE
            || (completes && projectedReal + projectedBonus > WalletSummary.MAX_BALANCE)) {
            return RoundRejection.BALANCE_LIMIT
        }
        val round = GameRound(UUID.randomUUID(), playerId, amounts.stake, amounts.totalWin, allocation)
        val afterStake = checkNotNull(wallets.debitBalances(playerId, allocation.realStake, allocation.bonusStake))
        if (allocation.realStake.signum() > 0) {
            ledger.appendRoundStake(playerId, round.id, WalletType.REAL, allocation.realStake, afterStake.realBalance)
        }
        if (allocation.bonusStake.signum() > 0) {
            ledger.appendRoundStake(playerId, round.id, WalletType.BONUS, allocation.bonusStake, afterStake.bonusBalance)
        }
        rounds.insert(round)
        val finalWallet = if (round.totalWin.signum() > 0) {
            val afterWin = wallets.creditBalances(playerId, allocation.realWin, allocation.bonusWin) ?: throw WalletBalanceLimitException()
            if (allocation.realWin.signum() > 0) {
                ledger.appendRoundWin(playerId, round.id, WalletType.REAL, allocation.realWin, afterWin.realBalance)
            }
            if (allocation.bonusWin.signum() > 0) {
                ledger.appendRoundWin(playerId, round.id, WalletType.BONUS, allocation.bonusWin, afterWin.bonusBalance)
            }
            afterWin
        } else {
            afterStake
        }
        val settled = if (activeBonus != null) {
            bonuses.advanceWagering(activeBonus.id, amounts.stake)
            lifecycle.completeIfWagered(playerId, finalWallet)
        } else finalWallet
        return RoundResult(round, settled)
    }

    companion object {
        private val MAX_ACTIVE_BONUS_STAKE = BigDecimal("5.00")
    }
}

sealed interface RoundOutcome
data class RoundResult(val round: GameRound, val wallet: WalletSummary) : RoundOutcome
enum class RoundRejection : RoundOutcome { INSUFFICIENT_FUNDS, MAX_BET_EXCEEDED, BALANCE_LIMIT }

class InsufficientFundsException : RuntimeException()
class MaxBetExceededException : RuntimeException()
