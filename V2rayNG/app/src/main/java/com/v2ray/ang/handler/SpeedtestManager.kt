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
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

object SpeedtestManager {

    private const val PARALLEL_CHECK_ATTEMPTS = 3
    private const val IP_CHECK_TIMEOUT_MS = 5000
    private const val GEO_CHECK_TIMEOUT_MS = 7000
    private val IPV4_REGEX =
        Regex("^([01]?[0-9]?[0-9]|2[0-4][0-9]|25[0-5])\\.([01]?[0-9]?[0-9]|2[0-4][0-9]|25[0-5])\\.([01]?[0-9]?[0-9]|2[0-4][0-9]|25[0-5])\\.([01]?[0-9]?[0-9]|2[0-4][0-9]|25[0-5])$")

    private val tcpTestingSockets = ArrayList<Socket?>()

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
     * For each source (A/B), 3 parallel requests are fired and the first valid result wins.
     * Then 3 parallel Geo lookups are fired for that IP and the first valid result wins.
     */
    suspend fun getRemoteIpAndGeoInfoSummary(
        onPartialUpdate: ((String) -> Unit)? = null,
        httpPortOverride: Int? = null
    ): String? = coroutineScope {
        val httpPort = httpPortOverride ?: SettingsManager.getHttpPort()

        val pending = mutableListOf(
            async(Dispatchers.IO) {
                "A" to resolveIpGeoSummary(
                    ipCheckUrl = SettingsManager.getIpCheckUrlA(),
                    httpPort = httpPort
                )
            },
            async(Dispatchers.IO) {
                "B" to resolveIpGeoSummary(
                    ipCheckUrl = SettingsManager.getIpCheckUrlB(),
                    httpPort = httpPort
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
            buildIpGeoSummaryLines(resolved)?.let { onPartialUpdate?.invoke(it) }
        }

        buildIpGeoSummaryLines(resolved)
    }

    private suspend fun resolveIpGeoSummary(ipCheckUrl: String, httpPort: Int): String {
        val ip = firstNonNull(PARALLEL_CHECK_ATTEMPTS) {
            extractFirstIp(HttpUtil.getUrlContent(ipCheckUrl, IP_CHECK_TIMEOUT_MS, httpPort))
        } ?: return "unavailable"

        val whoUrl = "https://i.idanya.ru/who/${Utils.urlEncode(ip)}"
        val whoSummary = firstNonNull(PARALLEL_CHECK_ATTEMPTS) {
            summarizeGeoBody(HttpUtil.getUrlContent(whoUrl, GEO_CHECK_TIMEOUT_MS, httpPort))
                ?.takeIf { it.isNotBlank() }
        }
        return whoSummary ?: ip
    }

    private suspend fun <T> firstNonNull(attempts: Int, task: suspend () -> T?): T? = coroutineScope {
        if (attempts <= 0) {
            return@coroutineScope null
        }

        val pending = MutableList(attempts) {
            async(Dispatchers.IO) {
                try {
                    task()
                } catch (e: Exception) {
                    Log.w(AppConfig.TAG, "Parallel check attempt failed", e)
                    null
                }
            }
        }

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
            resolved[label]?.let { "IP $label: $it" }
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun extractFirstIp(body: String?): String? {
        if (body.isNullOrBlank()) return null

        val trimmed = body.trim()
        if (isIpv4Address(trimmed)) {
            return trimmed
        }

        val candidateRegex = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
        return candidateRegex.findAll(trimmed)
            .map { it.value.trim('[', ']', '(', ')', '{', '}', '<', '>', ',', ';', '"', '\'') }
            .firstOrNull { token -> token.isNotEmpty() && isIpv4Address(token) }
    }

    private fun isIpv4Address(value: String): Boolean {
        return IPV4_REGEX.matches(value)
    }

    private fun summarizeGeoBody(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val trimmed = stripAnsi(body).trim()

        parseIpApiInfo(trimmed)?.let { info ->
            val country = listOf(
                info.country_name,
                info.country,
                info.country_code,
                info.countryCode,
                info.location?.country_code
            ).firstOrNull { !it.isNullOrBlank() }
            val ip = listOf(
                info.ip,
                info.clientIp,
                info.ip_addr,
                info.query
            ).firstOrNull { !it.isNullOrBlank() }
            if (!country.isNullOrBlank() || !ip.isNullOrBlank()) {
                return listOf(country, ip)
                    .filter { !it.isNullOrBlank() }
                    .joinToString(" ")
                    .take(120)
            }
        }

        JsonUtil.parseString(trimmed)?.let { json ->
            val country = json.get("country")?.takeIf { !it.isJsonNull }?.asString
            val region = json.get("region")?.takeIf { !it.isJsonNull }?.asString
            val city = json.get("city")?.takeIf { !it.isJsonNull }?.asString
            val ip = json.get("query")?.takeIf { !it.isJsonNull }?.asString
            val summary = listOf(country, region, city, ip)
                .filter { !it.isNullOrBlank() }
                .joinToString(" ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (summary.isNotEmpty()) {
                return summary.take(120)
            }
        }

        return trimmed
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.replace(Regex("<[^>]+>"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.take(120)
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
