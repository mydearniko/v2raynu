package com.v2ray.ang.handler

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.IPAPIInfo
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

object SpeedtestManager {

    private const val IP_CHECK_PARALLEL_ATTEMPTS = 2
    private const val WHO_CHECK_PARALLEL_ATTEMPTS = 1
    private const val IP_CHECK_TIMEOUT_MS = 2500
    private const val GEO_CHECK_TIMEOUT_MS = 3500
    private const val FAST_IP_CHECK_PARALLEL_ATTEMPTS = 1
    private const val FAST_WHO_CHECK_PARALLEL_ATTEMPTS = 1
    private const val FAST_IP_CHECK_TIMEOUT_MS = 1200
    private const val FAST_GEO_CHECK_TIMEOUT_MS = 1600
    private const val WHO_SUMMARY_CACHE_TTL_MS = 2 * 60 * 1000L
    private const val WHO_SUMMARY_CACHE_MAX_ENTRIES = 1024
    private val IPV4_REGEX =
        Regex("^([01]?[0-9]?[0-9]|2[0-4][0-9]|25[0-5])\\.([01]?[0-9]?[0-9]|2[0-4][0-9]|25[0-5])\\.([01]?[0-9]?[0-9]|2[0-4][0-9]|25[0-5])\\.([01]?[0-9]?[0-9]|2[0-4][0-9]|25[0-5])$")

    private val tcpTestingSockets = ArrayList<Socket?>()
    private val whoSummaryCache = ConcurrentHashMap<String, CachedWhoSummary>()

    private data class IpGeoLookupConfig(
        val ipParallelAttempts: Int,
        val whoParallelAttempts: Int,
        val ipTimeoutMs: Int,
        val geoTimeoutMs: Int
    )

    private val defaultLookupConfig = IpGeoLookupConfig(
        ipParallelAttempts = IP_CHECK_PARALLEL_ATTEMPTS,
        whoParallelAttempts = WHO_CHECK_PARALLEL_ATTEMPTS,
        ipTimeoutMs = IP_CHECK_TIMEOUT_MS,
        geoTimeoutMs = GEO_CHECK_TIMEOUT_MS
    )

    private val fastLookupConfig = IpGeoLookupConfig(
        ipParallelAttempts = FAST_IP_CHECK_PARALLEL_ATTEMPTS,
        whoParallelAttempts = FAST_WHO_CHECK_PARALLEL_ATTEMPTS,
        ipTimeoutMs = FAST_IP_CHECK_TIMEOUT_MS,
        geoTimeoutMs = FAST_GEO_CHECK_TIMEOUT_MS
    )

    private data class CachedWhoSummary(
        val summary: String,
        val cachedAtMs: Long
    )

    /**
     * Measures the TCP connection time to a given URL and port.
     *
     * @param url The URL to connect to.
     * @param port The port to connect to.
     * @return The connection time in milliseconds, or -1 if the connection failed.
     */
    suspend fun tcping(url: String, port: Int): Long {
        var time = -1L
        for (k in 0 until 2) {
            val one = socketConnectTime(url, port)
            if (!currentCoroutineContext().isActive) {
                break
            }
            if (one != -1L && (time == -1L || one < time)) {
                time = one
            }
        }
        return time
    }

    /**
     * Measures the time taken to establish a TCP connection to a given URL and port.
     *
     * @param url The URL to connect to.
     * @param port The port to connect to.
     * @return The connection time in milliseconds, or -1 if the connection failed.
     */
    fun socketConnectTime(url: String, port: Int): Long {
        try {
            val targetHost = if (Utils.isPureIpAddress(url)) {
                if (url.contains(':')) {
                    return -1
                }
                url
            } else {
                HttpUtil.resolveHostToIP(url, ipv6Preferred = false, ipv4Only = true)?.firstOrNull()
                    ?: return -1
            }

            val socket = Socket()
            synchronized(this) {
                tcpTestingSockets.add(socket)
            }
            val start = System.currentTimeMillis()
            socket.connect(InetSocketAddress(targetHost, port), 3000)
            val time = System.currentTimeMillis() - start
            synchronized(this) {
                tcpTestingSockets.remove(socket)
            }
            socket.close()
            return time
        } catch (e: UnknownHostException) {
            Log.e(AppConfig.TAG, "Unknown host: $url", e)
        } catch (e: IOException) {
            Log.e(AppConfig.TAG, "socketConnectTime IOException: $e")
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to establish socket connection to $url:$port", e)
        }
        return -1
    }

    /**
     * Closes all TCP sockets that are currently being tested.
     */
    fun closeAllTcpSockets() {
        synchronized(this) {
            tcpTestingSockets.forEach {
                it?.close()
            }
            tcpTestingSockets.clear()
        }
    }

    /**
     * Tests the connection to a given URL and port.
     *
     * @param context The Context in which the test is running.
     * @param port The port to connect to.
     * @return A pair containing the elapsed time in milliseconds and the result message.
     */
    fun testConnection(context: Context, port: Int): Pair<Long, String> {
        var result: String
        var elapsed = -1L

        val conn = HttpUtil.createProxyConnection(SettingsManager.getDelayTestUrl(), port, 15000, 15000) ?: return Pair(elapsed, "")
        try {
            val start = SystemClock.elapsedRealtime()
            val code = conn.responseCode
            elapsed = SystemClock.elapsedRealtime() - start

            result = when (code) {
                204 -> context.getString(R.string.connection_test_available, elapsed)
                200 if conn.contentLengthLong == 0L -> context.getString(R.string.connection_test_available, elapsed)
                else -> throw IOException(
                    context.getString(R.string.connection_test_error_status_code, code)
                )
            }
        } catch (e: IOException) {
            Log.e(AppConfig.TAG, "Connection test IOException", e)
            result = context.getString(R.string.connection_test_error, e.message)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Connection test Exception", e)
            result = context.getString(R.string.connection_test_error, e.message)
        } finally {
            conn.disconnect()
        }

        return Pair(elapsed, result)
    }

    fun getRemoteIPInfo(): String? {
        val url = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
            .takeIf { !it.isNullOrBlank() } ?: AppConfig.IP_API_URL

        val httpPort = SettingsManager.getHttpPort()
        val content = HttpUtil.getUrlContent(url, 5000, httpPort) ?: return null
        val ipInfo = parseIpApiInfo(content) ?: return null

        val ip = listOf(
            ipInfo.ip,
            ipInfo.clientIp,
            ipInfo.ip_addr,
            ipInfo.query
        ).firstOrNull { !it.isNullOrBlank() }

        val country = listOf(
            ipInfo.country_code,
            ipInfo.country,
            ipInfo.countryCode,
            ipInfo.location?.country_code
        ).firstOrNull { !it.isNullOrBlank() }

        return "(${country ?: "unknown"}) ${ip ?: "unknown"}"
    }

    /**
     * Performs multi-source IP lookups and then resolves each discovered IP with:
     * https://i.idanya.ru/who/$IP
     *
     * For each source (A/B), several parallel requests are fired and the first valid result wins.
     * Geo lookups use i.idanya.ru/who/$IP.
     */
    suspend fun getRemoteIpAndGeoInfoBySource(
        httpPortOverride: Int? = null,
        onPartialUpdate: ((Map<String, String>) -> Unit)? = null,
        fastMode: Boolean = false
    ): Map<String, String> = coroutineScope {
        val httpPort = httpPortOverride ?: SettingsManager.getHttpPort()
        val lookupConfig = if (fastMode) fastLookupConfig else defaultLookupConfig

        val pending = mutableListOf(
            async(Dispatchers.IO) {
                "A" to resolveIpGeoSummary(
                    ipCheckUrl = AppConfig.IP_CHECK_A_URL,
                    httpPort = httpPort,
                    lookupConfig = lookupConfig
                )
            },
            async(Dispatchers.IO) {
                "B" to resolveIpGeoSummary(
                    ipCheckUrl = AppConfig.IP_CHECK_B_URL,
                    httpPort = httpPort,
                    lookupConfig = lookupConfig
                )
            }
        )
        val resolved = linkedMapOf<String, String>()

        while (pending.isNotEmpty()) {
            val (finished, result) = select<Pair<Deferred<Pair<String, String>>, Pair<String, String>>> {
                pending.forEach { task ->
                    task.onAwait { value -> task to value }
                }
            }
            pending.remove(finished)
            resolved[result.first] = result.second
            onPartialUpdate?.invoke(LinkedHashMap(resolved))
        }

        LinkedHashMap(resolved)
    }

    /**
     * Resolves raw remote IPs from source A/B without Geo enrichment.
     */
    suspend fun getRemoteIpBySource(
        httpPortOverride: Int? = null,
        onPartialUpdate: ((Map<String, String>) -> Unit)? = null
    ): Map<String, String> = coroutineScope {
        val httpPort = httpPortOverride ?: SettingsManager.getHttpPort()
        val lookupConfig = defaultLookupConfig

        val pending = mutableListOf(
            async(Dispatchers.IO) {
                val ip = resolveRemoteIp(
                    ipCheckUrl = AppConfig.IP_CHECK_A_URL,
                    httpPort = httpPort,
                    lookupConfig = lookupConfig
                )
                "A" to ip
            },
            async(Dispatchers.IO) {
                val ip = resolveRemoteIp(
                    ipCheckUrl = AppConfig.IP_CHECK_B_URL,
                    httpPort = httpPort,
                    lookupConfig = lookupConfig
                )
                "B" to ip
            }
        )
        val resolved = linkedMapOf<String, String>()

        while (pending.isNotEmpty()) {
            val (finished, result) = select<Pair<Deferred<Pair<String, String?>>, Pair<String, String?>>> {
                pending.forEach { task ->
                    task.onAwait { value -> task to value }
                }
            }
            pending.remove(finished)
            result.second?.let { resolved[result.first] = it }
            onPartialUpdate?.invoke(LinkedHashMap(resolved))
        }

        LinkedHashMap(resolved)
    }

    /**
     * Builds user-facing summary lines from IP source A/B checks.
     */
    suspend fun getRemoteIpAndGeoInfoSummary(
        onPartialUpdate: ((String) -> Unit)? = null,
        httpPortOverride: Int? = null
    ): String? {
        val resolved = getRemoteIpAndGeoInfoBySource(
            httpPortOverride = httpPortOverride,
            onPartialUpdate = { partial ->
                buildIpGeoSummaryLines(partial)?.let { onPartialUpdate?.invoke(it) }
            }
        )
        return buildIpGeoSummaryLines(resolved)
    }

    private suspend fun resolveIpGeoSummary(
        ipCheckUrl: String,
        httpPort: Int,
        lookupConfig: IpGeoLookupConfig
    ): String {
        val ip = resolveRemoteIp(
            ipCheckUrl = ipCheckUrl,
            httpPort = httpPort,
            lookupConfig = lookupConfig
        ) ?: return "unavailable"

        getCachedWhoSummary(ip)?.let { cached ->
            return cached
        }

        val whoUrl = AppConfig.GEOIP_CHECK_URL.replace("\$IP", Utils.urlEncode(ip))
        val whoTasks = List(lookupConfig.whoParallelAttempts.coerceAtLeast(1)) {
            suspend {
                extractWhoLine(HttpUtil.getUrlContent(whoUrl, lookupConfig.geoTimeoutMs, httpPort))
                    ?.takeIf { it.isNotBlank() }
            }
        }
        val whoSummary = firstNonNullTasks(whoTasks)
        val resolved = whoSummary ?: ip
        if (!resolved.equals("unavailable", ignoreCase = true)) {
            cacheWhoSummary(ip, resolved)
        }
        return resolved
    }

    private suspend fun resolveRemoteIp(
        ipCheckUrl: String,
        httpPort: Int,
        lookupConfig: IpGeoLookupConfig
    ): String? {
        val tasks = List(lookupConfig.ipParallelAttempts.coerceAtLeast(1)) {
            suspend {
                extractFirstIp(
                    HttpUtil.getUrlContent(
                        url = ipCheckUrl,
                        timeout = lookupConfig.ipTimeoutMs,
                        httpPort = httpPort
                    )
                )
            }
        }
        return firstNonNullTasks(tasks)
    }

    private suspend fun <T> firstNonNullTasks(tasks: List<suspend () -> T?>): T? = coroutineScope {
        if (tasks.isEmpty()) {
            return@coroutineScope null
        }

        val pending = tasks.map { task ->
            async(Dispatchers.IO) {
                try {
                    task()
                } catch (e: Exception) {
                    Log.w(AppConfig.TAG, "Parallel check task failed", e)
                    null
                }
            }
        }.toMutableList()

        try {
            while (pending.isNotEmpty()) {
                val (finished, value) = select<Pair<Deferred<T?>, T?>> {
                    pending.forEach { deferred ->
                        deferred.onAwait { resolved -> deferred to resolved }
                    }
                }
                pending.remove(finished)
                if (value != null) {
                    return@coroutineScope value
                }
            }
            null
        } finally {
            pending.forEach { it.cancel() }
        }
    }

    private fun buildIpGeoSummaryLines(resolved: Map<String, String>): String? {
        val lines = listOf("A", "B").mapNotNull { label ->
            resolved[label]?.trim()?.takeIf { it.isNotEmpty() }
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun extractFirstIp(body: String?): String? {
        if (body.isNullOrBlank()) return null

        val trimmed = body.trim()
        normalizeIpCandidate(trimmed)?.let { candidate ->
            if (isIpAddress(candidate)) {
                return candidate
            }
        }

        val ipv4Regex = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
        ipv4Regex.findAll(trimmed)
            .mapNotNull { normalizeIpCandidate(it.value) }
            .firstOrNull { token -> token.isNotEmpty() && isIpv4Address(token) }
            ?.let { return it }

        val tokenRegex = Regex("[\\s,;\"'<>\\[\\]\\(\\){}]+")
        return trimmed
            .split(tokenRegex)
            .asSequence()
            .mapNotNull { normalizeIpCandidate(it) }
            .firstOrNull { token -> token.isNotEmpty() && isIpAddress(token) }
    }

    private fun extractWhoLine(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val normalized = stripAnsi(body)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.isBlank()) return null
        val firstLine = normalized
            .lineSequence()
            .map { it.trimEnd() }
            .firstOrNull { it.isNotBlank() }
            ?: normalized
        return firstLine
            .replace(Regex("^[^\\p{L}\\p{N}(\\[]+"), "")
            .trim()
            .ifEmpty { firstLine.trim() }
    }

    private fun normalizeIpCandidate(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val cleaned = value.trim()
            .trim('[', ']', '(', ')', '{', '}', '<', '>', ',', ';', '"', '\'')
            .removeSuffix(".")
            .takeIf { it.isNotEmpty() }
            ?: return null

        // Handle common IPv4 host:port format.
        if (cleaned.count { it == ':' } == 1 && cleaned.contains('.')) {
            val port = cleaned.substringAfterLast(':')
            if (port.all { it.isDigit() }) {
                return cleaned.substringBeforeLast(':')
            }
        }

        return cleaned
    }

    private fun isIpAddress(value: String): Boolean {
        return isIpv4Address(value) || isIpv6Address(value)
    }

    private fun isIpv4Address(value: String): Boolean {
        return IPV4_REGEX.matches(value)
    }

    private fun isIpv6Address(value: String): Boolean {
        if (!value.contains(':')) return false
        val normalized = value.substringBefore('%')
        return runCatching {
            InetAddress.getByName(normalized) is Inet6Address
        }.getOrDefault(false)
    }

    private fun getCachedWhoSummary(ip: String): String? {
        val entry = whoSummaryCache[ip] ?: return null
        val now = SystemClock.elapsedRealtime()
        if (now - entry.cachedAtMs > WHO_SUMMARY_CACHE_TTL_MS) {
            whoSummaryCache.remove(ip, entry)
            return null
        }
        return entry.summary
    }

    private fun cacheWhoSummary(ip: String, summary: String) {
        if (whoSummaryCache.size >= WHO_SUMMARY_CACHE_MAX_ENTRIES) {
            whoSummaryCache.clear()
        }
        whoSummaryCache[ip] = CachedWhoSummary(
            summary = summary,
            cachedAtMs = SystemClock.elapsedRealtime()
        )
    }

    private fun stripAnsi(value: String): String {
        return value.replace(Regex("\u001B\\[[;\\d]*m"), "")
    }

    private fun parseIpApiInfo(content: String): IPAPIInfo? {
        return try {
            JsonUtil.fromJson(content, IPAPIInfo::class.java)
        } catch (e: Exception) {
            Log.w(AppConfig.TAG, "Failed to parse IP API response as JSON", e)
            null
        }
    }
}
