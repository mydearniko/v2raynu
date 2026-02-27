package com.v2ray.ang.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.ServerAffiliationInfo
import com.v2ray.ang.dto.ServersCache
import com.v2ray.ang.dto.SubscriptionCache
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.handler.V2RayNativeManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.handler.V2rayConfigManager
import com.v2ray.ang.service.RealPingBatchStore
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import java.util.Collections
import java.util.Locale
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private var serverList = MmkvManager.decodeServerList()
    var subscriptionId: String = MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID, "").orEmpty()

    var keywordFilter: String = MmkvManager.decodeSettingsString(AppConfig.CACHE_KEYWORD_FILTER, "").orEmpty()
    val serversCache = mutableListOf<ServersCache>()
    val isRunning by lazy { MutableLiveData<Boolean>() }
    val updateListAction by lazy { MutableLiveData<Int>() }
    val updateTestResultAction by lazy { MutableLiveData<String>() }
    private val tcpingTestScope by lazy { CoroutineScope(Dispatchers.IO) }
    @Volatile
    private var testScopeSubscriptionId: String? = null
    @Volatile
    private var testScopeKeywordFilter: String? = null
    private var isDelayCheckPending = false
    private var delayCheckTimeoutJob: Job? = null
    private var delayCheckRequestedAtMs = 0L
    private var standaloneDelayCheckJob: Job? = null
    private val standaloneDelayGeneration = AtomicLong(0L)
    private var scopeIpCheckJob: Job? = null
    private val scopeIpCheckGeneration = AtomicLong(0L)

    private fun localizedContext(): Context {
        val app = getApplication<AngApplication>()
        return MyContextWrapper.wrap(app, SettingsManager.getLocale())
    }

    private fun localizedString(resId: Int, vararg args: Any): String {
        val ctx = localizedContext()
        return if (args.isEmpty()) {
            ctx.getString(resId)
        } else {
            ctx.getString(resId, *args)
        }
    }

    private fun requestDelayCheck() {
        delayCheckRequestedAtMs = SystemClock.elapsedRealtime()
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_MEASURE_DELAY, "")
    }

    private fun retryDelayCheckIfNeeded() {
        if (!isDelayCheckPending) return
        if (SystemClock.elapsedRealtime() - delayCheckRequestedAtMs < 1500L) return
        requestDelayCheck()
    }

    private fun clearDelayCheckPending() {
        isDelayCheckPending = false
        delayCheckTimeoutJob?.cancel()
        delayCheckTimeoutJob = null
    }

    private fun updateRunningState(running: Boolean) {
        if (isRunning.value != running) {
            isRunning.value = running
        }
    }

    private fun cancelRunningTests() {
        tcpingTestScope.coroutineContext[Job]?.cancelChildren()
        standaloneDelayGeneration.incrementAndGet()
        standaloneDelayCheckJob?.cancel()
        standaloneDelayCheckJob = null
        scopeIpCheckGeneration.incrementAndGet()
        scopeIpCheckJob?.cancel()
        scopeIpCheckJob = null
        SpeedtestManager.closeAllTcpSockets()
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_MEASURE_DELAY_CANCEL, "")
        MessageUtil.sendMsg2TestService(getApplication(), AppConfig.MSG_MEASURE_CONFIG_CANCEL, "")
        clearDelayCheckPending()
    }

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     */
    fun startListenBroadcast() {
        isRunning.value = false
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        ContextCompat.registerReceiver(getApplication(), mMsgReceiver, mFilter, Utils.receiverFlags())
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_REGISTER_CLIENT, "")
    }

    /**
     * Called when the ViewModel is cleared.
     */
    override fun onCleared() {
        getApplication<AngApplication>().unregisterReceiver(mMsgReceiver)
        cancelRunningTests()
        Log.i(AppConfig.TAG, "Main ViewModel is cleared")
        super.onCleared()
    }

    /**
     * Reloads the server list.
     */
    fun reloadServerList() {
        serverList = MmkvManager.decodeServerList()
        updateCache()
        updateListAction.value = -1
    }

    /**
     * Removes a server by its GUID.
     * @param guid The GUID of the server to remove.
     */
    fun removeServer(guid: String) {
        serverList.remove(guid)
        MmkvManager.removeServer(guid)
        val index = getPosition(guid)
        if (index >= 0) {
            serversCache.removeAt(index)
        }
    }

//    /**
//     * Appends a custom configuration server.
//     * @param server The server configuration to append.
//     * @return True if the server was successfully appended, false otherwise.
//     */
//    fun appendCustomConfigServer(server: String): Boolean {
//        if (server.contains("inbounds")
//            && server.contains("outbounds")
//            && server.contains("routing")
//        ) {
//            try {
//                val config = CustomFmt.parse(server) ?: return false
//                config.subscriptionId = subscriptionId
//                val key = MmkvManager.encodeServerConfig("", config)
//                MmkvManager.encodeServerRaw(key, server)
//                serverList.add(0, key)
////                val profile = ProfileLiteItem(
////                    configType = config.configType,
////                    subscriptionId = config.subscriptionId,
////                    remarks = config.remarks,
////                    server = config.getProxyOutbound()?.getServerAddress(),
////                    serverPort = config.getProxyOutbound()?.getServerPort(),
////                )
//                serversCache.add(0, ServersCache(key, config))
//                return true
//            } catch (e: Exception) {
//                e.printStackTrace()
//            }
//        }
//        return false
//    }

    /**
     * Swaps the positions of two servers.
     * @param fromPosition The initial position of the server.
     * @param toPosition The target position of the server.
     */
    fun swapServer(fromPosition: Int, toPosition: Int) {
        if (subscriptionId.isEmpty()) {
            Collections.swap(serverList, fromPosition, toPosition)
        } else {
            val fromPosition2 = serverList.indexOf(serversCache[fromPosition].guid)
            val toPosition2 = serverList.indexOf(serversCache[toPosition].guid)
            Collections.swap(serverList, fromPosition2, toPosition2)
        }
        Collections.swap(serversCache, fromPosition, toPosition)
        MmkvManager.encodeServerList(serverList)
    }

    /**
     * Updates the cache of servers.
     */
    @Synchronized
    fun updateCache() {
        val keyword = keywordFilter.trim().lowercase()
        serversCache.clear()
        for (guid in serverList) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
//            var profile = MmkvManager.decodeProfileConfig(guid)
//            if (profile == null) {
//                val config = MmkvManager.decodeServerConfig(guid) ?: continue
//                profile = ProfileLiteItem(
//                    configType = config.configType,
//                    subscriptionId = config.subscriptionId,
//                    remarks = config.remarks,
//                    server = config.getProxyOutbound()?.getServerAddress(),
//                    serverPort = config.getProxyOutbound()?.getServerPort(),
//                )
//                MmkvManager.encodeServerConfig(guid, config)
//            }

            if (subscriptionId.isNotEmpty() && subscriptionId != profile.subscriptionId) {
                continue
            }

            if (keyword.isEmpty() || matchesKeyword(profile, keyword)) {
                serversCache.add(ServersCache(guid, profile))
            }
        }
    }

    /**
     * Returns a stable snapshot of the currently visible servers.
     *
     * Scope always follows the active subscription tab + keyword filter.
     */
    @Synchronized
    private fun getScopedServerSnapshot(): List<ServersCache> {
        // Fast path: actions should operate on the current UI scope without forcing a global reload.
        // This avoids re-reading all profiles from storage for every menu action.
        if (serversCache.isEmpty() && serverList.isNotEmpty()) {
            updateCache()
        }
        return serversCache.toList()
    }

    /**
     * Returns a stable snapshot of GUIDs in the current action scope.
     */
    private fun getScopedGuidSnapshot(): List<String> {
        val scopedServers = getScopedServerSnapshot()
        val guids = ArrayList<String>(scopedServers.size)
        for (item in scopedServers) {
            guids.add(item.guid)
        }
        return guids
    }

    private fun matchesKeyword(profile: com.v2ray.ang.dto.ProfileItem, keyword: String): Boolean {
        if (keyword.isEmpty()) return true

        val searchableFields = sequenceOf(
            profile.remarks,
            profile.sni,
            profile.host,
            profile.authority
        )

        return searchableFields.any { field ->
            field
                ?.replace('\n', ',')
                ?.replace('\r', ',')
                ?.lowercase()
                ?.contains(keyword) == true
        }
    }

    private fun normalizeServerAddressForDuplicateCheck(server: String?): String? {
        var normalized = server?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        if (normalized.startsWith("[") && normalized.contains("]")) {
            normalized = normalized.substring(1, normalized.indexOf(']'))
        } else if (normalized.count { it == ':' } == 1 && normalized.contains('.')) {
            val colonIndex = normalized.lastIndexOf(':')
            val portPart = normalized.substring(colonIndex + 1)
            if (portPart.all { it.isDigit() }) {
                normalized = normalized.substring(0, colonIndex)
            }
        }

        normalized = normalized.trim()
        if (normalized.isEmpty()) {
            return null
        }

        if (normalized.startsWith("::ffff:", ignoreCase = true) && normalized.contains('.')) {
            normalized = normalized.substring(7)
        }

        return normalized.lowercase(Locale.ROOT)
    }

    /**
     * Updates the configuration via subscription for all servers.
     * @return The number of updated configurations.
     */
    fun updateConfigViaSubAll(): Int {
        if (subscriptionId.isEmpty()) {
            return AngConfigManager.updateConfigViaSubAll()
        } else {
            val subItem = MmkvManager.decodeSubscription(subscriptionId) ?: return 0
            return AngConfigManager.updateConfigViaSub(SubscriptionCache(subscriptionId, subItem))
        }
    }

    /**
     * Exports all servers.
     * @return The number of exported servers.
     */
    fun exportAllServer(): Int {
        val serverListCopy = getScopedGuidSnapshot()

        val ret = AngConfigManager.shareNonCustomConfigsToClipboard(
            getApplication<AngApplication>(),
            serverListCopy
        )
        return ret
    }

    /**
     * Tests the TCP ping for all servers.
     */
    fun testAllTcping(): Int {
        tcpingTestScope.coroutineContext[Job]?.cancelChildren()
        SpeedtestManager.closeAllTcpSockets()
        val serversCopy = getScopedServerSnapshot()
        val targetGuids = serversCopy.map { it.guid }
        MmkvManager.clearAllTestDelayResults(targetGuids)

        for (item in serversCopy) {
            item.profile.let { outbound ->
                val serverAddress = outbound.server
                val serverPort = outbound.serverPort
                if (serverAddress != null && serverPort != null) {
                    tcpingTestScope.launch {
                        val testResult = SpeedtestManager.tcping(serverAddress, serverPort.toInt())
                        launch(Dispatchers.Main) {
                            MmkvManager.encodeServerTestDelayMillis(item.guid, testResult)
                            updateListAction.value = getPosition(item.guid)
                        }
                    }
                }
            }
        }
        return serversCopy.size
    }

    /**
     * Tests the real ping for all servers.
     */
    fun testAllRealPing(): Int {
        // Do not send MSG_MEASURE_CONFIG_CANCEL here.
        // V2RayTestService already cancels the previous batch when a new MSG_MEASURE_CONFIG arrives.
        updateListAction.value = -1

        val guids = ArrayList(getScopedGuidSnapshot())
        if (guids.isEmpty()) {
            return 0
        }

        // Save the scope that was active when the test started so onTestsFinished()
        // operates on the same group even if the user switches tabs during the test.
        testScopeSubscriptionId = subscriptionId
        testScopeKeywordFilter = keywordFilter

        viewModelScope.launch(Dispatchers.Default) {
            MmkvManager.clearAllTestDelayResults(guids)
            val batchId = RealPingBatchStore.createBatch(guids)
            MessageUtil.sendMsg2TestService(getApplication(), AppConfig.MSG_MEASURE_CONFIG, batchId)
        }
        return guids.size
    }

    /**
     * Checks remote IP A/B for all currently working servers in the active scope.
     *
     * Scope follows active group + keyword filter.
     * Only servers with at least one successful delay sample are checked.
     */
    fun checkWorkingServersIpAAndBInScope(): Int {
        val targetGuids = getScopedGuidSnapshot().filter { guid ->
            isServerWorkingForIpCheck(MmkvManager.decodeServerAffiliationInfo(guid))
        }

        scopeIpCheckGeneration.incrementAndGet()
        scopeIpCheckJob?.cancel()
        scopeIpCheckJob = null

        if (targetGuids.isEmpty()) {
            updateTestResultAction.value = localizedString(R.string.ip_check_no_working_server_scope)
            return 0
        }

        val generation = scopeIpCheckGeneration.get()
        val total = targetGuids.size
        val unavailable = localizedString(R.string.ip_check_unavailable)
        val configuredThreadCount = SettingsManager.getRealPingThreadCount().coerceAtLeast(1)
        val workerCount = minOf(
            configuredThreadCount,
            total.coerceAtLeast(1),
            MAX_SCOPED_IP_CHECK_WORKERS
        )
        scopeIpCheckJob = viewModelScope.launch(Dispatchers.IO) {
            updateTestResultAction.postValue(localizedString(R.string.ip_check_progress, 0, total))
            val completionCount = AtomicInteger(0)
            val semaphore = Semaphore(workerCount)
            val workers = targetGuids.map { guid ->
                async {
                    semaphore.withPermit {
                        if (!isActive || generation != scopeIpCheckGeneration.get()) {
                            return@withPermit
                        }

                        val (ipA, ipB) = fetchServerIpChecksForGuid(guid, generation)
                        if (!isActive || generation != scopeIpCheckGeneration.get()) {
                            return@withPermit
                        }

                        val persisted = preserveExistingIpCheckSummary(
                            guid = guid,
                            ipA = ipA,
                            ipB = ipB,
                            unavailable = unavailable
                        )
                        MmkvManager.encodeServerIpCheckSummary(guid, persisted.first, persisted.second)
                        val completed = completionCount.incrementAndGet()
                        withContext(Dispatchers.Main) {
                            updateListAction.value = getPosition(guid)
                        }
                        updateTestResultAction.postValue(localizedString(R.string.ip_check_progress, completed, total))
                    }
                }
            }
            workers.awaitAll()

            if (isActive && generation == scopeIpCheckGeneration.get()) {
                updateTestResultAction.postValue(localizedString(R.string.ip_check_done, total))
            }
        }
        return total
    }

    /**
     * Tests the real ping for the current server.
     */
    fun testCurrentServerRealPing() {
        cancelRunningTests()
        if (!V2RayServiceManager.isRunning()) {
            testCurrentServerDelayWithoutService()
            return
        }
        startServiceBackedDelayCheck(showTestingState = false)
    }

    private companion object {
        private const val PARALLEL_DELAY_ATTEMPTS = 3
        private const val MAX_SCOPED_IP_CHECK_WORKERS = 8
        private const val TEMP_PROXY_WARMUP_TIMEOUT_MS = 900L
    }

    private fun isServerWorkingForIpCheck(aff: ServerAffiliationInfo?): Boolean {
        val samples = aff?.testDelaySamples.orEmpty()
        if (samples.isNotEmpty()) {
            return samples.any { it >= 0L }
        }
        return (aff?.testDelayMillis ?: 0L) > 0L
    }

    private suspend fun fetchServerIpChecksForGuid(guid: String, generation: Long): Pair<String, String> {
        val app = getApplication<AngApplication>()
        val unavailable = localizedString(R.string.ip_check_unavailable)
        if (generation != scopeIpCheckGeneration.get()) {
            return unavailable to unavailable
        }

        V2RayNativeManager.initCoreEnv(app)

        val merged = linkedMapOf<String, String>()
        suspend fun collectFromConfig(configContent: String) {
            val runtimeCandidate = prepareRuntimeConfigForScopedIpCheck(configContent) ?: return
            if (generation != scopeIpCheckGeneration.get() || hasCompleteIpCheckResult(merged, unavailable)) {
                return
            }
            val resolved = resolveScopedIpChecksViaTemporaryCore(
                runtimeConfig = runtimeCandidate.first,
                httpPortForChecks = runtimeCandidate.second
            )
            mergeScopedIpCheckResults(merged, resolved, unavailable)
        }

        val fastConfigResult = V2rayConfigManager.getV2rayConfig4Speedtest(app, guid)
        if (fastConfigResult.status && fastConfigResult.content.isNotBlank()) {
            collectFromConfig(fastConfigResult.content)
        }

        if (generation != scopeIpCheckGeneration.get()) {
            return unavailable to unavailable
        }

        if (!hasCompleteIpCheckResult(merged, unavailable)) {
            val fullConfigResult = V2rayConfigManager.getV2rayConfig(app, guid)
            if (fullConfigResult.status && fullConfigResult.content.isNotBlank()) {
                collectFromConfig(fullConfigResult.content)
            }
        }

        val ipA = merged["A"]?.trim().orEmpty().ifEmpty { unavailable }
        val ipB = merged["B"]?.trim().orEmpty().ifEmpty { unavailable }
        return ipA to ipB
    }

    private fun mergeScopedIpCheckResults(
        target: MutableMap<String, String>,
        incoming: Map<String, String>,
        unavailable: String
    ) {
        listOf("A", "B").forEach { source ->
            val candidate = incoming[source]?.trim().orEmpty()
            if (isUnavailableIpCheckValue(candidate, unavailable)) {
                return@forEach
            }
            val existing = target[source]
            if (isUnavailableIpCheckValue(existing, unavailable)) {
                target[source] = candidate
            }
        }
    }

    private fun hasCompleteIpCheckResult(values: Map<String, String>, unavailable: String): Boolean {
        return !isUnavailableIpCheckValue(values["A"], unavailable) &&
            !isUnavailableIpCheckValue(values["B"], unavailable)
    }

    private fun isUnavailableIpCheckValue(value: String?, unavailable: String): Boolean {
        val normalized = value?.trim().orEmpty()
        return normalized.isEmpty() ||
            normalized.equals(unavailable, ignoreCase = true) ||
            normalized.equals("unavailable", ignoreCase = true)
    }

    private suspend fun resolveScopedIpChecksViaTemporaryCore(
        runtimeConfig: String,
        httpPortForChecks: Int
    ): Map<String, String> = coroutineScope {
        if (httpPortForChecks <= 0) {
            return@coroutineScope emptyMap()
        }
        val unavailable = localizedString(R.string.ip_check_unavailable)

        val controller = V2RayNativeManager.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(l: Long, s: String?): Long = 0
        })

        try {
            controller.startLoop(runtimeConfig, 0)
            if (!waitForCoreRunning(controller)) {
                return@coroutineScope emptyMap()
            }
            waitForTemporaryProxyReady(httpPortForChecks)

            val merged = linkedMapOf<String, String>()
            val pending = mutableListOf(
                async(Dispatchers.IO) {
                    SpeedtestManager.getRemoteIpAndGeoInfoBySource(
                        httpPortOverride = httpPortForChecks,
                        fastMode = true
                    )
                },
                async(Dispatchers.IO) {
                    SpeedtestManager.getRemoteIpAndGeoInfoBySource(
                        httpPortOverride = httpPortForChecks,
                        fastMode = false
                    )
                }
            )
            try {
                while (pending.isNotEmpty() && !hasCompleteIpCheckResult(merged, unavailable)) {
                    val (finished, resolved) = select<Pair<Deferred<Map<String, String>>, Map<String, String>>> {
                        pending.forEach { deferred ->
                            deferred.onAwait { value -> deferred to value }
                        }
                    }
                    pending.remove(finished)
                    mergeScopedIpCheckResults(merged, resolved, unavailable)
                }
            } finally {
                pending.forEach { it.cancel() }
            }

            if (!hasCompleteIpCheckResult(merged, unavailable)) {
                delay(140L)
                val rescueResolved = SpeedtestManager.getRemoteIpAndGeoInfoBySource(
                    httpPortOverride = httpPortForChecks,
                    fastMode = false
                )
                mergeScopedIpCheckResults(merged, rescueResolved, unavailable)
            }

            merged.takeIf { it.isNotEmpty() } ?: emptyMap()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to check scoped IP via temporary core", e)
            emptyMap()
        } finally {
            runCatching {
                if (controller.isRunning) {
                    controller.stopLoop()
                }
            }.onFailure { err ->
                Log.e(AppConfig.TAG, "Failed to stop temporary core for scoped IP checks", err)
            }
        }
    }

    private suspend fun waitForTemporaryProxyReady(
        httpPort: Int,
        timeoutMs: Long = TEMP_PROXY_WARMUP_TIMEOUT_MS
    ) {
        if (httpPort <= 0 || timeoutMs <= 0L) {
            return
        }
        val ipCheckUrlA = SettingsManager.getIpCheckUrlA()
        val ipCheckUrlB = SettingsManager.getIpCheckUrlB()
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val quickA = HttpUtil.getUrlContent(ipCheckUrlA, 450, httpPort)
            if (!quickA.isNullOrBlank()) {
                return
            }
            val quickB = HttpUtil.getUrlContent(ipCheckUrlB, 450, httpPort)
            if (!quickB.isNullOrBlank()) {
                return
            }
            delay(70L)
        }
    }

    private fun preserveExistingIpCheckSummary(
        guid: String,
        ipA: String,
        ipB: String,
        unavailable: String
    ): Pair<String, String> {
        val existing = MmkvManager.decodeServerAffiliationInfo(guid)
        val existingA = existing?.ipCheckA?.trim().orEmpty()
        val existingB = existing?.ipCheckB?.trim().orEmpty()

        val finalA = if (isUnavailableIpCheckValue(ipA, unavailable) &&
            !isUnavailableIpCheckValue(existingA, unavailable)
        ) {
            existingA
        } else {
            ipA
        }
        val finalB = if (isUnavailableIpCheckValue(ipB, unavailable) &&
            !isUnavailableIpCheckValue(existingB, unavailable)
        ) {
            existingB
        } else {
            ipB
        }
        return finalA to finalB
    }

    private suspend fun waitForCoreRunning(
        controller: libv2ray.CoreController,
        timeoutMs: Long = 2200L
    ): Boolean {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            if (controller.isRunning) {
                return true
            }
            delay(60L)
        }
        return controller.isRunning
    }

    private fun prepareRuntimeConfigForScopedIpCheck(configJson: String): Pair<String, Int>? {
        val stripped = stripTunInbound(configJson)
        val root = JsonUtil.parseString(stripped) ?: return null
        val httpPort = findAvailableLoopbackPort()
        if (httpPort <= 0) {
            return null
        }

        val existingInbounds = root.getAsJsonArray("inbounds")
        var preservedTag = "socks"
        if (existingInbounds != null) {
            for (index in 0 until existingInbounds.size()) {
                val inboundObj = existingInbounds[index]?.asJsonObject ?: continue
                val protocol = inboundObj.get("protocol")?.asString?.lowercase().orEmpty()
                val tag = inboundObj.get("tag")?.asString?.trim().orEmpty()
                if (protocol != "tun" && tag.lowercase() != "tun") {
                    if (tag.isNotEmpty()) {
                        preservedTag = tag
                    }
                    break
                }
            }
        }

        val inbounds = com.google.gson.JsonArray()
        val inbound = com.google.gson.JsonObject().apply {
            addProperty("tag", preservedTag)
            addProperty("listen", AppConfig.LOOPBACK)
            addProperty("port", httpPort)
            addProperty("protocol", "http")
            add("settings", com.google.gson.JsonObject())
        }
        inbounds.add(inbound)
        root.add("inbounds", inbounds)

        return JsonUtil.toJson(root) to httpPort
    }

    private fun findAvailableLoopbackPort(): Int {
        return runCatching {
            ServerSocket(0).use { socket ->
                socket.reuseAddress = true
                socket.localPort
            }
        }.getOrDefault(0)
    }

    private fun testCurrentServerDelayWithoutService() {
        val generation = standaloneDelayGeneration.incrementAndGet()
        standaloneDelayCheckJob?.cancel()
        standaloneDelayCheckJob = viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<AngApplication>()
            val selectedGuid = MmkvManager.getSelectServer()
            if (selectedGuid.isNullOrBlank()) {
                updateTestResultAction.postValue(localizedString(R.string.connection_not_connected))
                return@launch
            }

            try {
                V2RayNativeManager.initCoreEnv(app)
                V2RayNativeManager.setRealPingAttemptTimeoutMillis(
                    SettingsManager.getRealPingAttemptTimeoutMillis().toLong()
                )

                val configResult = V2rayConfigManager.getV2rayConfig4Speedtest(app, selectedGuid)
                if (!configResult.status || configResult.content.isBlank()) {
                    if (generation == standaloneDelayGeneration.get()) {
                        updateTestResultAction.postValue(localizedString(R.string.connection_test_fail))
                    }
                    return@launch
                }

                // Fire 6 parallel delay requests: 3 primary URL + 3 alt URL
                val primaryUrl = SettingsManager.getDelayTestUrl()
                val altUrl = SettingsManager.getDelayTestUrl(true)
                val config = configResult.content
                val pending = mutableListOf<Deferred<Long>>()
                repeat(PARALLEL_DELAY_ATTEMPTS) {
                    pending.add(async(Dispatchers.IO) {
                        V2RayNativeManager.measureOutboundDelay(config, primaryUrl)
                    })
                    pending.add(async(Dispatchers.IO) {
                        V2RayNativeManager.measureOutboundDelay(config, altUrl)
                    })
                }

                // Take the first successful (>=0) result
                var delayMillis = -1L
                while (pending.isNotEmpty() && delayMillis < 0L) {
                    if (generation != standaloneDelayGeneration.get()) {
                        pending.forEach { it.cancel() }
                        return@launch
                    }
                    val (finished, value) = select<Pair<Deferred<Long>, Long>> {
                        pending.forEach { d ->
                            d.onAwait { v -> d to v }
                        }
                    }
                    pending.remove(finished)
                    if (value >= 0L) {
                        delayMillis = value
                    }
                }
                // Cancel remaining requests once we have a result
                pending.forEach { it.cancel() }

                if (generation != standaloneDelayGeneration.get()) {
                    return@launch
                }

                val result = if (delayMillis >= 0L) {
                    localizedString(R.string.connection_test_available, delayMillis)
                } else {
                    localizedString(R.string.connection_test_fail)
                }
                updateTestResultAction.postValue(result)

                if (delayMillis >= 0L) {
                    var lastPartialDetails: String? = null
                    val details = fetchStandaloneIpGeoSummary(
                        selectedGuid = selectedGuid,
                        generation = generation
                    ) { partial ->
                        if (partial == lastPartialDetails) return@fetchStandaloneIpGeoSummary
                        lastPartialDetails = partial
                        updateTestResultAction.postValue("$result\n$partial")
                    }

                    if (generation != standaloneDelayGeneration.get()) {
                        return@launch
                    }
                    val content = if (details.isNullOrBlank()) result else "$result\n$details"
                    updateTestResultAction.postValue(content)
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Standalone delay check failed", e)
                if (generation == standaloneDelayGeneration.get()) {
                    updateTestResultAction.postValue(
                        localizedString(R.string.connection_test_error, e.message ?: "unknown")
                    )
                }
            }
        }
    }

    private suspend fun fetchStandaloneIpGeoSummary(
        selectedGuid: String,
        generation: Long,
        onPartialUpdate: (String) -> Unit
    ): String? {
        if (generation != standaloneDelayGeneration.get()) {
            return null
        }

        val app = getApplication<AngApplication>()
        V2RayNativeManager.initCoreEnv(app)
        val configCandidates = linkedSetOf<String>()
        val fastConfigResult = V2rayConfigManager.getV2rayConfig4Speedtest(app, selectedGuid)
        if (fastConfigResult.status && fastConfigResult.content.isNotBlank()) {
            configCandidates.add(fastConfigResult.content)
        }
        val fullConfigResult = V2rayConfigManager.getV2rayConfig(app, selectedGuid)
        if (fullConfigResult.status && fullConfigResult.content.isNotBlank()) {
            configCandidates.add(fullConfigResult.content)
        }

        var bestUnavailableSummary: String? = null
        for (configContent in configCandidates) {
            if (generation != standaloneDelayGeneration.get()) {
                return null
            }

            val runtimeCandidates = mutableListOf<Pair<String, Int>>()
            prepareRuntimeConfigForScopedIpCheck(configContent)?.let { runtimeCandidates.add(it) }
            runtimeCandidates.add(stripTunInbound(configContent) to SettingsManager.getHttpPort())

            for ((runtimeConfig, httpPortForChecks) in runtimeCandidates.distinct()) {
                if (generation != standaloneDelayGeneration.get()) {
                    return null
                }
                val summary = resolveStandaloneIpGeoSummaryViaTemporaryCore(
                    runtimeConfig = runtimeConfig,
                    httpPortForChecks = httpPortForChecks,
                    generation = generation,
                    onPartialUpdate = onPartialUpdate
                )
                if (summary.isNullOrBlank()) {
                    continue
                }
                if (isUsableStandaloneIpGeoSummary(summary)) {
                    return summary
                }
                if (bestUnavailableSummary.isNullOrBlank()) {
                    bestUnavailableSummary = summary
                }
            }
        }

        val directSummary = SpeedtestManager.getRemoteIpAndGeoInfoSummary(
            onPartialUpdate = { partial ->
                if (generation == standaloneDelayGeneration.get()) {
                    onPartialUpdate(partial)
                }
            },
            httpPortOverride = 0
        )
        if (!directSummary.isNullOrBlank()) {
            if (isUsableStandaloneIpGeoSummary(directSummary)) {
                return directSummary
            }
            if (bestUnavailableSummary.isNullOrBlank()) {
                bestUnavailableSummary = directSummary
            }
        }
        return bestUnavailableSummary
    }

    private suspend fun resolveStandaloneIpGeoSummaryViaTemporaryCore(
        runtimeConfig: String,
        httpPortForChecks: Int,
        generation: Long,
        onPartialUpdate: (String) -> Unit
    ): String? {
        if (httpPortForChecks <= 0) {
            return null
        }
        val controller = V2RayNativeManager.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(l: Long, s: String?): Long = 0
        })

        return try {
            controller.startLoop(runtimeConfig, 0)
            if (!waitForCoreRunning(controller)) {
                null
            } else {
                SpeedtestManager.getRemoteIpAndGeoInfoSummary(
                    onPartialUpdate = { partial ->
                        if (generation == standaloneDelayGeneration.get()) {
                            onPartialUpdate(partial)
                        }
                    },
                    httpPortOverride = httpPortForChecks
                )
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to start temporary core for standalone IP checks", e)
            null
        } finally {
            runCatching {
                if (controller.isRunning) {
                    controller.stopLoop()
                }
            }.onFailure { err ->
                Log.e(AppConfig.TAG, "Failed to stop temporary core for standalone IP checks", err)
            }
        }
    }

    private fun isUsableStandaloneIpGeoSummary(summary: String): Boolean {
        val unavailable = localizedString(R.string.ip_check_unavailable)
        return summary
            .lineSequence()
            .map { it.trim() }
            .any { line ->
                line.isNotEmpty() &&
                    !line.equals(unavailable, ignoreCase = true) &&
                    !line.equals("unavailable", ignoreCase = true)
            }
    }

    private fun stripTunInbound(configJson: String): String {
        val root = JsonUtil.parseString(configJson) ?: return configJson
        val inbounds = root.getAsJsonArray("inbounds") ?: return configJson
        val filtered = com.google.gson.JsonArray()
        inbounds.forEach { inbound ->
            val inboundObj = inbound?.asJsonObject ?: return@forEach
            val protocol = inboundObj.get("protocol")?.asString?.lowercase().orEmpty()
            val tag = inboundObj.get("tag")?.asString?.lowercase().orEmpty()
            if (protocol == "tun" || tag == "tun") {
                return@forEach
            }
            filtered.add(inboundObj)
        }
        root.add("inbounds", filtered)
        return JsonUtil.toJson(root)
    }

    private fun startServiceBackedDelayCheck(showTestingState: Boolean) {
        if (showTestingState) {
            updateTestResultAction.value = localizedString(R.string.connection_test_testing)
        }
        isDelayCheckPending = true
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_REGISTER_CLIENT, "")
        requestDelayCheck()

        delayCheckTimeoutJob?.cancel()
        delayCheckTimeoutJob = viewModelScope.launch {
            delay(12000L)
            if (!isDelayCheckPending) return@launch

            MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_MEASURE_DELAY_CANCEL, "")
            clearDelayCheckPending()
            updateTestResultAction.value = localizedString(R.string.connection_test_fail)
        }
    }

    /**
     * Changes the subscription ID.
     * @param id The new subscription ID.
     */
    fun subscriptionIdChanged(id: String) {
        if (subscriptionId != id) {
            subscriptionId = id
            MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, subscriptionId)
        }
        reloadServerList()
    }

    /**
     * Gets the subscriptions.
     * @return The list of subscription groups.
     */
    fun getSubscriptions(): List<GroupMapItem> {
        val subscriptions = MmkvManager.decodeSubscriptions()
        val subscriptionIds = subscriptions.map { it.guid }

        if (subscriptions.isEmpty()) {
            if (subscriptionId.isNotEmpty()) {
                subscriptionId = ""
                MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, subscriptionId)
                reloadServerList()
            }
            return emptyList()
        }

        if (!subscriptionIds.contains(subscriptionId)) {
            subscriptionId = subscriptionIds.first()
            MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, subscriptionId)
            reloadServerList()
        }

        return subscriptions.map { sub ->
            GroupMapItem(id = sub.guid, remarks = sub.subscription.remarks)
        }
    }

    /**
     * Gets the position of a server by its GUID.
     * @param guid The GUID of the server.
     * @return The position of the server.
     */
    fun getPosition(guid: String): Int {
        serversCache.forEachIndexed { index, it ->
            if (it.guid == guid)
                return index
        }
        return -1
    }

    /**
     * Removes duplicate servers by address (ignoring port/protocol/settings).
     * Keeps the healthiest candidate per duplicate-address group when possible.
     * @return The number of removed servers.
     */
    fun removeDuplicateServer(): Int {
        val scopedServers = getScopedServerSnapshot()
        data class DuplicateCandidate(
            val guid: String,
            val originalIndex: Int,
            val successCount: Int,
            val bestLatencyMillis: Long,
            val allFailures: Boolean
        )

        val groupedByAddress = HashMap<String, MutableList<DuplicateCandidate>>(scopedServers.size.coerceAtLeast(16))
        scopedServers.forEachIndexed { index, item ->
            val addressKey = normalizeServerAddressForDuplicateCheck(item.profile.server) ?: return@forEachIndexed
            val aff = MmkvManager.decodeServerAffiliationInfo(item.guid)
            val samples = aff?.testDelaySamples.orEmpty()
            val validSamples = samples.filter { it >= 0L }
            val successCount = if (samples.isNotEmpty()) {
                validSamples.size
            } else if ((aff?.testDelayMillis ?: 0L) > 0L) {
                1
            } else {
                0
            }
            val bestLatencyMillis = when {
                validSamples.isNotEmpty() -> validSamples.minOrNull() ?: Long.MAX_VALUE
                (aff?.testDelayMillis ?: 0L) > 0L -> aff?.testDelayMillis ?: Long.MAX_VALUE
                else -> Long.MAX_VALUE
            }
            val allFailures = when {
                samples.isNotEmpty() -> validSamples.isEmpty()
                else -> (aff?.testDelayMillis ?: 0L) < 0L
            }

            groupedByAddress.getOrPut(addressKey) { mutableListOf() }
                .add(
                    DuplicateCandidate(
                        guid = item.guid,
                        originalIndex = index,
                        successCount = successCount,
                        bestLatencyMillis = bestLatencyMillis,
                        allFailures = allFailures
                    )
                )
        }

        val deleteServer = mutableListOf<String>()
        groupedByAddress.values.forEach { candidates ->
            if (candidates.size <= 1) {
                return@forEach
            }

            val keep = candidates
                .sortedWith(
                    compareByDescending<DuplicateCandidate> { it.successCount > 0 }
                        .thenByDescending { it.successCount }
                        .thenBy { it.allFailures }
                        .thenBy { it.bestLatencyMillis }
                        .thenBy { it.originalIndex }
                )
                .firstOrNull() ?: return@forEach

            candidates.forEach { candidate ->
                if (candidate.guid != keep.guid) {
                    deleteServer.add(candidate.guid)
                }
            }
        }

        return MmkvManager.removeServers(deleteServer)
    }

    /**
     * Removes all servers.
     * @return The number of removed servers.
     */
    fun removeAllServer(): Int {
        val scopedGuids = getScopedGuidSnapshot()
        return MmkvManager.removeServers(scopedGuids)
    }

    /**
     * Removes invalid servers.
     * @return The number of removed servers.
     */
    fun removeInvalidServer(): Int {
        val invalidGuids = getScopedGuidSnapshot().filter { guid ->
            (MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L) < 0L
        }
        return MmkvManager.removeServers(invalidGuids)
    }

    /**
     * Sorts servers by their test results.
     */
    fun sortByTestResults() {
        data class ServerScore(
            val guid: String,
            val successCount: Int,
            val averageLatencyMillis: Long,
            val worstLatencyMillis: Long,
            val jitterMillis: Long,
            val originalIndex: Int
        )

        val scopedGuids = getScopedGuidSnapshot()
        if (scopedGuids.size <= 1) {
            return
        }

        val serverScores = mutableListOf<ServerScore>()
        scopedGuids.forEachIndexed { index, key ->
            val aff = MmkvManager.decodeServerAffiliationInfo(key)
            val samples = aff?.testDelaySamples.orEmpty()
            val successCount = if (samples.isNotEmpty()) {
                samples.count { it >= 0L }
            } else if ((aff?.testDelayMillis ?: 0L) > 0L) {
                1
            } else {
                0
            }

            val validSamples = when {
                samples.isNotEmpty() -> samples.filter { it >= 0L }
                (aff?.testDelayMillis ?: 0L) > 0L -> listOf(aff?.testDelayMillis ?: Long.MAX_VALUE)
                else -> emptyList()
            }

            val averageLatencyMillis = if (validSamples.isEmpty()) {
                Long.MAX_VALUE
            } else {
                validSamples.sum().toDouble().div(validSamples.size).toLong()
            }

            val worstLatencyMillis = if (validSamples.isEmpty()) {
                Long.MAX_VALUE
            } else {
                validSamples.maxOrNull() ?: Long.MAX_VALUE
            }

            val jitterMillis = if (validSamples.size <= 1) {
                0L
            } else {
                (validSamples.maxOrNull() ?: 0L) - (validSamples.minOrNull() ?: 0L)
            }

            serverScores.add(
                ServerScore(
                    guid = key,
                    successCount = successCount,
                    averageLatencyMillis = averageLatencyMillis,
                    worstLatencyMillis = worstLatencyMillis,
                    jitterMillis = jitterMillis,
                    originalIndex = index
                )
            )
        }

        val sortedServerGuids = serverScores
            .sortedWith(
                compareByDescending<ServerScore> { it.successCount }
                    .thenBy { it.averageLatencyMillis }
                    .thenBy { it.worstLatencyMillis }
                    .thenBy { it.jitterMillis }
                    .thenBy { it.originalIndex }
            )
            .map { it.guid }

        val serverList = MmkvManager.decodeServerList()
        val scopedGuidSet = sortedServerGuids.toHashSet()
        var sortedIndex = 0
        for (index in serverList.indices) {
            if (!scopedGuidSet.contains(serverList[index])) {
                continue
            }
            if (sortedIndex >= sortedServerGuids.size) {
                break
            }
            serverList[index] = sortedServerGuids[sortedIndex]
            sortedIndex++
        }

        MmkvManager.encodeServerList(serverList)
    }

    /**
     * Initializes assets.
     * @param assets The asset manager.
     */
    fun initAssets(assets: AssetManager) {
        viewModelScope.launch(Dispatchers.Default) {
            SettingsManager.initAssets(getApplication<AngApplication>(), assets)
        }
    }

    /**
     * Filters the configuration by a keyword.
     * @param keyword The keyword to filter by.
     */
    fun filterConfig(keyword: String) {
        if (keyword == keywordFilter) {
            return
        }
        keywordFilter = keyword
        MmkvManager.encodeSettings(AppConfig.CACHE_KEYWORD_FILTER, keywordFilter)
        reloadServerList()
    }

    fun onTestsFinished() {
        // Use the scope that was active when the test started, not the current one.
        // This ensures auto-remove/auto-sort only affect the group that was tested,
        // even if the user switched tabs during the test.
        val savedSubId = testScopeSubscriptionId
        val savedKeyword = testScopeKeywordFilter
        testScopeSubscriptionId = null
        testScopeKeywordFilter = null

        viewModelScope.launch(Dispatchers.Default) {
            val originalSubId = subscriptionId
            val originalKeyword = keywordFilter
            val needRestore = savedSubId != null && (savedSubId != originalSubId || savedKeyword != originalKeyword)
            if (needRestore) {
                subscriptionId = savedSubId
                keywordFilter = savedKeyword ?: originalKeyword
            }

            try {
                if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST)) {
                    removeInvalidServer()
                }

                if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SORT_AFTER_TEST)) {
                    sortByTestResults()
                }
            } finally {
                if (needRestore) {
                    subscriptionId = originalSubId
                    keywordFilter = originalKeyword
                }
            }

            withContext(Dispatchers.Main) {
                reloadServerList()
            }
        }
    }

    private val mMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> {
                    updateRunningState(true)
                    retryDelayCheckIfNeeded()
                }

                AppConfig.MSG_STATE_NOT_RUNNING -> {
                    updateRunningState(false)
                    if (isDelayCheckPending) {
                        clearDelayCheckPending()
                        updateTestResultAction.value = localizedString(R.string.connection_not_connected)
                    }
                }

                AppConfig.MSG_STATE_START_SUCCESS -> {
                    getApplication<AngApplication>().toastSuccess(R.string.toast_services_success)
                    updateRunningState(true)
                    if (!isDelayCheckPending) {
                        startServiceBackedDelayCheck(showTestingState = true)
                    } else {
                        retryDelayCheckIfNeeded()
                    }
                }

                AppConfig.MSG_STATE_START_FAILURE -> {
                    getApplication<AngApplication>().toastError(R.string.toast_services_failure)
                    updateRunningState(false)
                    if (isDelayCheckPending) {
                        clearDelayCheckPending()
                        updateTestResultAction.value = localizedString(R.string.connection_not_connected)
                    }
                }

                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    updateRunningState(false)
                    if (isDelayCheckPending) {
                        clearDelayCheckPending()
                        updateTestResultAction.value = localizedString(R.string.connection_not_connected)
                    }
                }

                AppConfig.MSG_MEASURE_DELAY_SUCCESS -> {
                    clearDelayCheckPending()
                    updateTestResultAction.value = intent.getStringExtra("content")
                }

                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> {
                    val result = intent.serializable<RealPingResult>("content") ?: return
                    MmkvManager.encodeServerTestDelaySamples(result.guid, result.samples)
                    updateListAction.value = getPosition(result.guid)
                }

                AppConfig.MSG_MEASURE_CONFIG_NOTIFY -> {
                    val content = intent.getStringExtra("content")
                    updateTestResultAction.value = content
                }

                AppConfig.MSG_MEASURE_CONFIG_FINISH -> {
                    val content = intent.getStringExtra("content")
                    if (content == "0") {
                        onTestsFinished()
                    }
                }
            }
        }
    }
}
