package com.v2ray.ang.handler

import kotlinx.coroutines.flow.MutableStateFlow

object SettingsChangeManager {
    private val _restartService = MutableStateFlow(false)
    private val _setupGroupTab = MutableStateFlow(false)
    private val _refreshMainUi = MutableStateFlow(false)

    // Mark restartService as requiring a restart
    fun makeRestartService() {
        _restartService.value = true
    }

    // Read and clear the restartService flag
    fun consumeRestartService(): Boolean {
        val v = _restartService.value
        _restartService.value = false
        return v
    }

    // Mark reinitGroupTab as requiring tab reinitialization
    fun makeSetupGroupTab() {
        _setupGroupTab.value = true
    }

    // Read and clear the reinitGroupTab flag
    fun consumeSetupGroupTab(): Boolean {
        val v = _setupGroupTab.value
        _setupGroupTab.value = false
        return v
    }

    // Mark the main UI as requiring recreation
    fun makeRefreshMainUi() {
        _refreshMainUi.value = true
    }

    // Read and clear the main UI refresh flag
    fun consumeRefreshMainUi(): Boolean {
        val v = _refreshMainUi.value
        _refreshMainUi.value = false
        return v
    }
}
