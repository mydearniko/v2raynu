package com.v2ray.ang.handler

import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig.PREF_IS_BOOTED
import com.v2ray.ang.AppConfig.PREF_ROUTING_RULESET
import com.v2ray.ang.dto.AssetUrlCache
import com.v2ray.ang.dto.AssetUrlItem
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.RulesetItem
import com.v2ray.ang.dto.ServerAffiliationInfo
import com.v2ray.ang.dto.SubscriptionCache
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.dto.WebDavConfig
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object MmkvManager {

    //region private

    //private const val ID_PROFILE_CONFIG = "PROFILE_CONFIG"
    private const val ID_MAIN = "MAIN"
    private const val ID_PROFILE_FULL_CONFIG = "PROFILE_FULL_CONFIG"
    private const val ID_SERVER_RAW = "SERVER_RAW"
    private const val ID_SERVER_AFF = "SERVER_AFF"
    private const val ID_SUB = "SUB"
    private const val ID_ASSET = "ASSET"
    private const val ID_SETTING = "SETTING"
    private const val KEY_SELECTED_SERVER = "SELECTED_SERVER"
    private const val KEY_ANG_CONFIGS = "ANG_CONFIGS"
    private const val KEY_SUB_IDS = "SUB_IDS"
    private const val KEY_WEBDAV_CONFIG = "WEBDAV_CONFIG"

    //private val profileStorage by lazy { MMKV.mmkvWithID(ID_PROFILE_CONFIG, MMKV.MULTI_PROCESS_MODE) }
    private val mainStorage by lazy { MMKV.mmkvWithID(ID_MAIN, MMKV.MULTI_PROCESS_MODE) }
    private val profileFullStorage by lazy { MMKV.mmkvWithID(ID_PROFILE_FULL_CONFIG, MMKV.MULTI_PROCESS_MODE) }
    private val serverRawStorage by lazy { MMKV.mmkvWithID(ID_SERVER_RAW, MMKV.MULTI_PROCESS_MODE) }
    private val serverAffStorage by lazy { MMKV.mmkvWithID(ID_SERVER_AFF, MMKV.MULTI_PROCESS_MODE) }
    private val subStorage by lazy { MMKV.mmkvWithID(ID_SUB, MMKV.MULTI_PROCESS_MODE) }
    private val assetStorage by lazy { MMKV.mmkvWithID(ID_ASSET, MMKV.MULTI_PROCESS_MODE) }
    private val settingsStorage by lazy { MMKV.mmkvWithID(ID_SETTING, MMKV.MULTI_PROCESS_MODE) }
    private val serverConfigCache = ConcurrentHashMap<String, ProfileItem>()
    private val serverListCacheLock = Any()
    private val runtimeConfigRevision = AtomicLong(1L)
    @Volatile
    private var serverListCache: MutableList<String>? = null

    private fun bumpRuntimeConfigRevision() {
        runtimeConfigRevision.incrementAndGet()
    }

    fun getRuntimeConfigRevision(): Long {
        return runtimeConfigRevision.get()
    }

    //endregion

    //region Server

    /**
     * Gets the selected server GUID.
     *
     * @return The selected server GUID.
     */
    fun getSelectServer(): String? {
        return mainStorage.decodeString(KEY_SELECTED_SERVER)
    }

    /**
     * Sets the selected server GUID.
     *
     * @param guid The server GUID.
     */
    fun setSelectServer(guid: String) {
        mainStorage.encode(KEY_SELECTED_SERVER, guid)
    }

    /**
     * Encodes the server list.
     *
     * @param serverList The list of server GUIDs.
     */
    fun encodeServerList(serverList: MutableList<String>) {
        val snapshot = serverList.toMutableList()
        mainStorage.encode(KEY_ANG_CONFIGS, JsonUtil.toJson(snapshot))
        synchronized(serverListCacheLock) {
            serverListCache = snapshot.toMutableList()
        }
    }

    /**
     * Decodes the server list.
     *
     * @return The list of server GUIDs.
     */
    fun decodeServerList(): MutableList<String> {
        synchronized(serverListCacheLock) {
            serverListCache?.let { return it.toMutableList() }
        }
        val json = mainStorage.decodeString(KEY_ANG_CONFIGS)
        val list = if (json.isNullOrBlank()) {
            mutableListOf()
        } else {
            JsonUtil.fromJson(json, Array<String>::class.java)?.toMutableList() ?: mutableListOf()
        }
        synchronized(serverListCacheLock) {
            serverListCache = list.toMutableList()
        }
        return list
    }

    /**
     * Decodes the server configuration.
     *
     * @param guid The server GUID.
     * @return The server configuration.
     */
    fun decodeServerConfig(guid: String): ProfileItem? {
        if (guid.isBlank()) {
            return null
        }
        serverConfigCache[guid]?.let { return it.copy() }

        val json = profileFullStorage.decodeString(guid)
        if (json.isNullOrBlank()) {
            return null
        }
        val profile = JsonUtil.fromJson(json, ProfileItem::class.java) ?: return null
        serverConfigCache[guid] = profile
        return profile.copy()
    }

    /**
     * Decodes only the server address for a profile.
     *
     * This avoids creating a defensive copy when callers only need the host/IP value.
     *
     * @param guid The server GUID.
     * @return The server address, or null if unavailable.
     */
    fun decodeServerAddress(guid: String): String? {
        if (guid.isBlank()) {
            return null
        }
        serverConfigCache[guid]?.let { return it.server }

        val json = profileFullStorage.decodeString(guid) ?: return null
        val profile = JsonUtil.fromJson(json, ProfileItem::class.java) ?: return null
        serverConfigCache[guid] = profile
        return profile.server
    }

    /**
     * Extracts server addresses for all given GUIDs in bulk using lightweight
     * JSON parsing (org.json) instead of full Gson reflection.
     *
     * This is dramatically faster than calling [decodeServerAddress] per GUID
     * when the config cache is cold (e.g. thousands of profiles).
     *
     * @param guids The list of server GUIDs.
     * @return A map of GUID to server address (null values omitted).
     */
    fun decodeServerAddressesBulk(guids: List<String>): Map<String, String> {
        val result = HashMap<String, String>(guids.size)
        for (guid in guids) {
            if (guid.isBlank()) continue
            // Fast path: already cached from normal usage
            serverConfigCache[guid]?.server?.let {
                result[guid] = it
                continue
            }
            // Lightweight extraction without full deserialization
            val json = profileFullStorage.decodeString(guid) ?: continue
            try {
                val server = org.json.JSONObject(json).optString("server")
                if (!server.isNullOrBlank()) {
                    result[guid] = server
                }
            } catch (_: Exception) {
            }
        }
        return result
    }

//    fun decodeProfileConfig(guid: String): ProfileLiteItem? {
//        if (guid.isBlank()) {
//            return null
//        }
//        val json = profileStorage.decodeString(guid)
//        if (json.isNullOrBlank()) {
//            return null
//        }
//        return JsonUtil.fromJson(json, ProfileLiteItem::class.java)
//    }

    /**
     * Encodes the server configuration.
     *
     * @param guid The server GUID.
     * @param config The server configuration.
     * @return The server GUID.
     */
    fun encodeServerConfig(guid: String, config: ProfileItem): String {
        val key = guid.ifBlank { Utils.getUuid() }
        val snapshot = config.copy()
        profileFullStorage.encode(key, JsonUtil.toJson(snapshot))
        serverConfigCache[key] = snapshot
        val serverList = decodeServerList()
        if (!serverList.contains(key)) {
            serverList.add(0, key)
            encodeServerList(serverList)
            if (getSelectServer().isNullOrBlank()) {
                mainStorage.encode(KEY_SELECTED_SERVER, key)
            }
        }
//        val profile = ProfileLiteItem(
//            configType = config.configType,
//            subscriptionId = config.subscriptionId,
//            remarks = config.remarks,
//            server = config.getProxyOutbound()?.getServerAddress(),
//            serverPort = config.getProxyOutbound()?.getServerPort(),
//        )
//        profileStorage.encode(key, JsonUtil.toJson(profile))
        bumpRuntimeConfigRevision()
        return key
    }

    /**
     * Removes the server configuration.
     *
     * @param guid The server GUID.
     */
    fun removeServer(guid: String) {
        if (guid.isBlank()) {
            return
        }
        if (getSelectServer() == guid) {
            mainStorage.remove(KEY_SELECTED_SERVER)
        }
        val serverList = decodeServerList()
        serverList.remove(guid)
        encodeServerList(serverList)
        profileFullStorage.remove(guid)
        serverConfigCache.remove(guid)
        //profileStorage.remove(guid)
        serverAffStorage.remove(guid)
        bumpRuntimeConfigRevision()
    }

    /**
     * Removes multiple server configurations in one storage pass.
     *
     * @param guidList The server GUID list.
     * @return The number of removed servers.
     */
    fun removeServers(guidList: Collection<String>): Int {
        if (guidList.isEmpty()) {
            return 0
        }

        val removeSet = guidList.asSequence()
            .filter { it.isNotBlank() }
            .toSet()
        if (removeSet.isEmpty()) {
            return 0
        }

        if (getSelectServer()?.let { removeSet.contains(it) } == true) {
            mainStorage.remove(KEY_SELECTED_SERVER)
        }

        val serverList = decodeServerList()
        val before = serverList.size
        serverList.removeAll(removeSet)
        encodeServerList(serverList)

        removeSet.forEach { guid ->
            profileFullStorage.remove(guid)
            serverConfigCache.remove(guid)
            serverAffStorage.remove(guid)
        }
        if (before != serverList.size) {
            bumpRuntimeConfigRevision()
        }
        return before - serverList.size
    }

    /**
     * Removes the server configurations via subscription ID.
     *
     * @param subid The subscription ID.
     */
    fun removeServerViaSubid(subid: String) {
        if (subid.isBlank()) {
            return
        }
        val guidsToRemove = profileFullStorage.allKeys()?.filter { key ->
            decodeServerConfig(key)?.subscriptionId == subid
        } ?: return
        if (guidsToRemove.isNotEmpty()) {
            removeServers(guidsToRemove)
        }
    }

    /**
     * Encodes multiple server configurations in a single batch, updating the
     * server list only once at the end instead of per-server.
     *
     * @param configs The list of server configurations to encode.
     * @return The list of generated server GUIDs.
     */
    fun encodeServerConfigs(configs: List<ProfileItem>): List<String> {
        if (configs.isEmpty()) return emptyList()

        val keys = mutableListOf<String>()
        for (config in configs) {
            val key = Utils.getUuid()
            val snapshot = config.copy()
            profileFullStorage.encode(key, JsonUtil.toJson(snapshot))
            serverConfigCache[key] = snapshot
            keys.add(key)
        }

        val serverList = decodeServerList()
        val existingSet = serverList.toHashSet()
        val newKeys = keys.filter { !existingSet.contains(it) }
        if (newKeys.isNotEmpty()) {
            serverList.addAll(0, newKeys)
            encodeServerList(serverList)
        }

        if (getSelectServer().isNullOrBlank() && keys.isNotEmpty()) {
            mainStorage.encode(KEY_SELECTED_SERVER, keys.first())
        }

        if (keys.isNotEmpty()) {
            bumpRuntimeConfigRevision()
        }
        return keys
    }

    /**
     * Decodes the server affiliation information.
     *
     * @param guid The server GUID.
     * @return The server affiliation information.
     */
    fun decodeServerAffiliationInfo(guid: String): ServerAffiliationInfo? {
        if (guid.isBlank()) {
            return null
        }
        val json = serverAffStorage.decodeString(guid)
        if (json.isNullOrBlank()) {
            return null
        }
        return JsonUtil.fromJson(json, ServerAffiliationInfo::class.java)
    }

    /**
     * Encodes the server test delay in milliseconds.
     *
     * @param guid The server GUID.
     * @param testResult The test delay in milliseconds.
     */
    fun encodeServerTestDelayMillis(guid: String, testResult: Long) {
        if (guid.isBlank()) {
            return
        }
        val aff = decodeServerAffiliationInfo(guid) ?: ServerAffiliationInfo()
        aff.testDelayMillis = testResult
        aff.testDelaySamples = mutableListOf(testResult)
        serverAffStorage.encode(guid, JsonUtil.toJson(aff))
    }

    /**
     * Encodes multiple server test delay samples and stores a representative delay
     * for sorting/invalid checks.
     *
     * @param guid The server GUID.
     * @param testResults The measured delay samples in milliseconds.
     */
    fun encodeServerTestDelaySamples(guid: String, testResults: LongArray) {
        if (guid.isBlank()) {
            return
        }

        val aff = decodeServerAffiliationInfo(guid) ?: ServerAffiliationInfo()
        aff.testDelaySamples = testResults
            .toList()
            .sortedWith { a, b ->
                when {
                    a < 0L && b < 0L -> 0
                    a < 0L -> 1
                    b < 0L -> -1
                    else -> a.compareTo(b)
                }
            }
            .toMutableList()

        val valid = aff.testDelaySamples.filter { it >= 0L }.sorted()
        // Any successful sample means the server is reachable; keep failures only when all probes fail.
        aff.testDelayMillis = if (valid.isNotEmpty()) valid[valid.size / 2] else -1L
        serverAffStorage.encode(guid, JsonUtil.toJson(aff))
    }

    /**
     * Encodes per-server IP check summaries (source A/B).
     */
    fun encodeServerIpCheckSummary(guid: String, ipA: String?, ipB: String?) {
        if (guid.isBlank()) {
            return
        }
        val aff = decodeServerAffiliationInfo(guid) ?: ServerAffiliationInfo()
        aff.ipCheckA = ipA?.trim()?.takeIf { it.isNotEmpty() }
        aff.ipCheckB = ipB?.trim()?.takeIf { it.isNotEmpty() }
        serverAffStorage.encode(guid, JsonUtil.toJson(aff))
    }

    /**
     * Clears all test delay results.
     *
     * @param keys The list of server GUIDs.
     */
    fun clearAllTestDelayResults(keys: List<String>?) {
        keys?.forEach { key ->
            if (key.isNotBlank()) {
                // Affiliation currently stores delay-only fields, so key removal is the fastest clear path.
                serverAffStorage.remove(key)
            }
        }
    }

    /**
     * Removes all server configurations.
     *
     * @return The number of server configurations removed.
     */
    fun removeAllServer(): Int {
        val count = profileFullStorage.allKeys()?.count() ?: 0
        mainStorage.clearAll()
        profileFullStorage.clearAll()
        serverConfigCache.clear()
        synchronized(serverListCacheLock) {
            serverListCache = mutableListOf()
        }
        //profileStorage.clearAll()
        serverAffStorage.clearAll()
        if (count > 0) {
            bumpRuntimeConfigRevision()
        }
        return count
    }

    /**
     * Removes invalid server configurations.
     *
     * @param guid The server GUID.
     * @return The number of server configurations removed.
     */
    fun removeInvalidServer(guid: String): Int {
        var count = 0
        if (guid.isNotEmpty()) {
            decodeServerAffiliationInfo(guid)?.let { aff ->
                if (aff.testDelayMillis < 0L) {
                    removeServer(guid)
                    count++
                }
            }
        } else {
            serverAffStorage.allKeys()?.forEach { key ->
                decodeServerAffiliationInfo(key)?.let { aff ->
                    if (aff.testDelayMillis < 0L) {
                        removeServer(key)
                        count++
                    }
                }
            }
        }
        return count
    }

    /**
     * Encodes the raw server configuration.
     *
     * @param guid The server GUID.
     * @param config The raw server configuration.
     */
    fun encodeServerRaw(guid: String, config: String) {
        serverRawStorage.encode(guid, config)
        bumpRuntimeConfigRevision()
    }

    /**
     * Decodes the raw server configuration.
     *
     * @param guid The server GUID.
     * @return The raw server configuration.
     */
    fun decodeServerRaw(guid: String): String? {
        return serverRawStorage.decodeString(guid)
    }

    //endregion

    //region Subscriptions

    /**
     * Initializes the subscription list.
     */
    private fun initSubsList() {
        val subsList = decodeSubsList()
        if (subsList.isNotEmpty()) {
            return
        }
        subStorage.allKeys()?.forEach { key ->
            subsList.add(key)
        }
        encodeSubsList(subsList)
    }

    /**
     * Decodes the subscriptions.
     *
     * @return The list of subscriptions.
     */
    fun decodeSubscriptions(): List<SubscriptionCache> {
        initSubsList()

        val subscriptions = mutableListOf<SubscriptionCache>()
        decodeSubsList().forEach { key ->
            val json = subStorage.decodeString(key)
            if (!json.isNullOrBlank()) {
                val item = JsonUtil.fromJson(json, SubscriptionItem::class.java)?: SubscriptionItem()
                subscriptions.add(SubscriptionCache(key, item))
            }
        }
        return subscriptions
    }

    /**
     * Removes the subscription.
     *
     * @param subid The subscription ID.
     */
    fun removeSubscription(subid: String) {
        subStorage.remove(subid)
        val subsList = decodeSubsList()
        subsList.remove(subid)
        encodeSubsList(subsList)

        removeServerViaSubid(subid)
    }

    /**
     * Encodes the subscription.
     *
     * @param guid The subscription GUID.
     * @param subItem The subscription item.
     */
    fun encodeSubscription(guid: String, subItem: SubscriptionItem) {
        val key = guid.ifBlank { Utils.getUuid() }
        subStorage.encode(key, JsonUtil.toJson(subItem))

        val subsList = decodeSubsList()
        if (!subsList.contains(key)) {
            subsList.add(key)
            encodeSubsList(subsList)
        }
    }

    /**
     * Decodes the subscription.
     *
     * @param subscriptionId The subscription ID.
     * @return The subscription item.
     */
    fun decodeSubscription(subscriptionId: String): SubscriptionItem? {
        val json = subStorage.decodeString(subscriptionId) ?: return null
        return JsonUtil.fromJson(json, SubscriptionItem::class.java)
    }

    /**
     * Encodes the subscription list.
     *
     * @param subsList The list of subscription IDs.
     */
    fun encodeSubsList(subsList: MutableList<String>) {
        mainStorage.encode(KEY_SUB_IDS, JsonUtil.toJson(subsList))
    }

    /**
     * Decodes the subscription list.
     *
     * @return The list of subscription IDs.
     */
    fun decodeSubsList(): MutableList<String> {
        val json = mainStorage.decodeString(KEY_SUB_IDS)
        return if (json.isNullOrBlank()) {
            mutableListOf()
        } else {
            JsonUtil.fromJson(json, Array<String>::class.java)?.toMutableList()?: mutableListOf()
        }
    }

    //endregion

    //region Asset

    /**
     * Decodes the asset URLs.
     *
     * @return The list of asset URLs.
     */
    fun decodeAssetUrls(): List<AssetUrlCache> {
        val assetUrlItems = mutableListOf<AssetUrlCache>()
        assetStorage.allKeys()?.forEach { key ->
            val json = assetStorage.decodeString(key)
            if (!json.isNullOrBlank()) {
                val item = JsonUtil.fromJson(json, AssetUrlItem::class.java)?: AssetUrlItem()
                assetUrlItems.add(AssetUrlCache(key, item))
            }
        }
        return assetUrlItems.sortedBy { it.assetUrl.addedTime }
    }

    /**
     * Removes the asset URL.
     *
     * @param assetid The asset ID.
     */
    fun removeAssetUrl(assetid: String) {
        assetStorage.remove(assetid)
    }

    /**
     * Encodes the asset.
     *
     * @param assetid The asset ID.
     * @param assetItem The asset item.
     */
    fun encodeAsset(assetid: String, assetItem: AssetUrlItem) {
        val key = assetid.ifBlank { Utils.getUuid() }
        assetStorage.encode(key, JsonUtil.toJson(assetItem))
    }

    /**
     * Decodes the asset.
     *
     * @param assetid The asset ID.
     * @return The asset item.
     */
    fun decodeAsset(assetid: String): AssetUrlItem? {
        val json = assetStorage.decodeString(assetid) ?: return null
        return JsonUtil.fromJson(json, AssetUrlItem::class.java)
    }

    //endregion

    //region Routing

    /**
     * Decodes the routing rulesets.
     *
     * @return The list of routing rulesets.
     */
    fun decodeRoutingRulesets(): MutableList<RulesetItem>? {
        val ruleset = settingsStorage.decodeString(PREF_ROUTING_RULESET)
        if (ruleset.isNullOrEmpty()) return null
        return JsonUtil.fromJson(ruleset, Array<RulesetItem>::class.java)?.toMutableList()?: mutableListOf()
    }

    /**
     * Encodes the routing rulesets.
     *
     * @param rulesetList The list of routing rulesets.
     */
    fun encodeRoutingRulesets(rulesetList: MutableList<RulesetItem>?) {
        if (rulesetList.isNullOrEmpty())
            encodeSettings(PREF_ROUTING_RULESET, "")
        else
            encodeSettings(PREF_ROUTING_RULESET, JsonUtil.toJson(rulesetList))
    }

    //endregion

    //region settings
    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: String?): Boolean {
        val ret = settingsStorage.encode(key, value)
        if (ret) {
            bumpRuntimeConfigRevision()
        }
        return ret
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Int): Boolean {
        val ret = settingsStorage.encode(key, value)
        if (ret) {
            bumpRuntimeConfigRevision()
        }
        return ret
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Long): Boolean {
        val ret = settingsStorage.encode(key, value)
        if (ret) {
            bumpRuntimeConfigRevision()
        }
        return ret
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Float): Boolean {
        val ret = settingsStorage.encode(key, value)
        if (ret) {
            bumpRuntimeConfigRevision()
        }
        return ret
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Boolean): Boolean {
        val ret = settingsStorage.encode(key, value)
        if (ret) {
            bumpRuntimeConfigRevision()
        }
        return ret
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: MutableSet<String>): Boolean {
        val ret = settingsStorage.encode(key, value)
        if (ret) {
            bumpRuntimeConfigRevision()
        }
        return ret
    }

    /**
     * Decodes the settings string.
     *
     * @param key The settings key.
     * @return The settings value.
     */
    fun decodeSettingsString(key: String): String? {
        return settingsStorage.decodeString(key)
    }

    /**
     * Decodes the settings string.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsString(key: String, defaultValue: String?): String? {
        return settingsStorage.decodeString(key, defaultValue)
    }

    /**
     * Decodes the settings integer.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsInt(key: String, defaultValue: Int): Int {
        return settingsStorage.decodeInt(key, defaultValue)
    }

    /**
     * Decodes the settings long.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsLong(key: String, defaultValue: Long): Long {
        return settingsStorage.decodeLong(key, defaultValue)
    }

    /**
     * Decodes the settings float.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsFloat(key: String, defaultValue: Float): Float {
        return settingsStorage.decodeFloat(key, defaultValue)
    }

    /**
     * Decodes the settings boolean.
     *
     * @param key The settings key.
     * @return The settings value.
     */
    fun decodeSettingsBool(key: String): Boolean {
        return settingsStorage.decodeBool(key, false)
    }

    /**
     * Decodes the settings boolean.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsBool(key: String, defaultValue: Boolean): Boolean {
        return settingsStorage.decodeBool(key, defaultValue)
    }

    /**
     * Checks whether a settings key already exists in MMKV, regardless of value type.
     */
    fun containsSetting(key: String): Boolean {
        return settingsStorage.containsKey(key)
    }

    /**
     * Decodes the settings string set.
     *
     * @param key The settings key.
     * @return The settings value.
     */
    fun decodeSettingsStringSet(key: String): MutableSet<String>? {
        return settingsStorage.decodeStringSet(key)
    }

    /**
     * Encodes the start on boot setting.
     *
     * @param startOnBoot Whether to start on boot.
     */
    fun encodeStartOnBoot(startOnBoot: Boolean) {
        encodeSettings(PREF_IS_BOOTED, startOnBoot)
    }

    /**
     * Decodes the start on boot setting.
     *
     * @return Whether to start on boot.
     */
    fun decodeStartOnBoot(): Boolean {
        return decodeSettingsBool(PREF_IS_BOOTED, false)
    }

    //endregion

    //region WebDAV

    /**
     * Encodes the WebDAV config as JSON into storage.
     */
    fun encodeWebDavConfig(config: WebDavConfig): Boolean {
        return mainStorage.encode(KEY_WEBDAV_CONFIG, JsonUtil.toJson(config))
    }

    /**
     * Decodes the WebDAV config from storage.
     */
    fun decodeWebDavConfig(): WebDavConfig? {
        val json = mainStorage.decodeString(KEY_WEBDAV_CONFIG) ?: return null
        return JsonUtil.fromJson(json, WebDavConfig::class.java)
    }

    //endregion
}
