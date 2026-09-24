package com.example.casinowallet.wallet.web

import com.example.casinowallet.wallet.application.WalletApplicationService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class WalletController(private val walletService: WalletApplicationService) {
    @GetMapping("/api/wallet")
    fun getWallet(): WalletResponse = WalletResponse.from(walletService.getDemoWallet())
}
