package com.lagradost

import com.lagradost.cloudstream3.AcraApplication.Companion.getKey
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.syncproviders.AuthAPI
import com.lagradost.cloudstream3.syncproviders.InAppAuthAPI
import com.lagradost.cloudstream3.syncproviders.InAppAuthAPIManager

class TagsMacIptvAPI(index: Int) : InAppAuthAPIManager(index) {
    override val name = "Vos Tags"
    override val idPrefix = "tagsiptvbox"
    override val icon = R.drawable.ic_baseline_extension_24
    override val requiresUsername = true
    override val requiresPassword = true
    override val requiresServer = true
    override val createAccountUrl = ""

    companion object {
        const val IPTVBOX_USER_KEY: String = "tagsiptvbox_user"
    }

    override fun getLatestLoginData(): InAppAuthAPI.LoginData? {
        return getKey(accountId, IPTVBOX_USER_KEY)
    }

    override fun loginInfo(): AuthAPI.LoginInfo? {
        val data = getLatestLoginData() ?: return null
        return AuthAPI.LoginInfo(name = data.username ?: data.server, accountIndex = accountIndex)
    }

    override suspend fun login(data: InAppAuthAPI.LoginData): Boolean {
        if (data.server.isNullOrBlank() && data.username.isNullOrBlank() && data.password.isNullOrBlank()) return false // we require a server
        switchToNewAccount()
        setKey(accountId, IPTVBOX_USER_KEY, data)
        registerAccount()
        initialize()
        inAppAuths

        return true
    }

    override fun logOut() {
        removeAccountKeys()
        initializeData()
    }

    private fun initializeData() {
        val data = getLatestLoginData() ?: run {
            MacIPTVProvider.tags = ""
            return
        }
        MacIPTVProvider.tags = "${data.server.toString()}|${data.password}|${data.username}"
    }

    override suspend fun initialize() {
        initializeData()
    }
}