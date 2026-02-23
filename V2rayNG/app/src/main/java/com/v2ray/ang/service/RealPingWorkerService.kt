package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2RayNativeManager
import com.v2ray.ang.handler.V2rayConfigManager
import com.v2ray.ang.util.MessageUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Worker that runs a batch of real-ping tests independently.
 * Each batch owns its own CoroutineScope/dispatcher and can be cancelled separately.
 */
class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val shouldRun: () -> Boolean = { true },
    private val onFinish: (status: String) -> Unit = {}
) {
    private companion object {
        private const val MEASUREMENTS_PER_SERVER = 3
        private const val PROGRESS_HEARTBEAT_MS = 1000L
        private const val STARTUP_BARRIER_TIMEOUT_SECONDS = 2L
        private const val NETWORK_WARMUP_WORKER_LIMIT = 8
        private const val WARMUP_CONFIG_SCAN_LIMIT = 64
        private const val MIN_SUCCESS_SAMPLES_FOR_STABLE_RESULT = 2
    }

    private val job = SupervisorJob()
    private val configuredThreads = SettingsManager.getRealPingThreadCount()
    private val workerThreads = min(configuredThreads, guids.size.coerceAtLeast(1))
    private val executor = ThreadPoolExecutor(
        workerThreads,
        workerThreads,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
        RealPingThreadFactory()
    ).apply {
        prestartAllCoreThreads()
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("RealPingBatchWorker"))

    private val runningCount = AtomicInteger(0)
    private val remainingCount = AtomicInteger(0)
    private val completedServerCount = AtomicInteger(0)
    private val startedAtMillis = AtomicLong(0L)
    private val speedtestConfigCache = ConcurrentHashMap<String, CachedSpeedtestConfig>()
    @Volatile
    private var heartbeatJob: Job? = null

    private data class CachedSpeedtestConfig(
        val status: Boolean,
        val content: String
    )

    fun start() {
        if (!shouldRun()) {
            onFinish("-1")
            close()
            return
        }
        if (guids.isEmpty()) {
            onFinish("0")
            close()
            return
        }

        val timeoutMillis = SettingsManager.getRealPingAttemptTimeoutMillis()
        val primaryTestUrl = SettingsManager.getDelayTestUrl()
        val alternateTestUrl = SettingsManager.getDelayTestUrl(true)
        V2RayNativeManager.setRealPingAttemptTimeoutMillis(timeoutMillis.toLong())
        startedAtMillis.set(System.currentTimeMillis())
        remainingCount.set(guids.size)
        completedServerCount.set(0)
        notifyProgress()

        heartbeatJob = scope.launch {
            while (isActive && shouldRun()) {
                delay(PROGRESS_HEARTBEAT_MS)
                notifyProgress()
            }
        }

        val queue = Channel<String>(capacity = workerThreads * 2)
        val workers = mutableListOf<kotlinx.coroutines.Job>()
        val activeWorkers = workerThreads
        val startupBarrier = CountDownLatch(activeWorkers)
        val warmupConfig = prepareWarmupConfig()

        val warmupWorkers = min(activeWorkers, NETWORK_WARMUP_WORKER_LIMIT)
        repeat(activeWorkers) { workerIndex ->
            workers.add(
                scope.launch {
                    startupBarrier.countDown()
                    try {
                        startupBarrier.await(STARTUP_BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    } catch (_: Throwable) {
                        // ignore
                    }

                    if (workerIndex < warmupWorkers) {
                        prewarmWorker(warmupConfig, primaryTestUrl)
                    }

                    for (guid in queue) {
                        if (!isActive || !job.isActive || !shouldRun()) {
                            break
                        }
                        runningCount.incrementAndGet()
                        notifyProgress()
                        try {
                            val samples = startRealPing(guid, primaryTestUrl, alternateTestUrl)
                            if (isActive && job.isActive && shouldRun()) {
                                MessageUtil.sendMsg2UI(
                                    context,
                                    AppConfig.MSG_MEASURE_CONFIG_SUCCESS,
                                    RealPingResult(guid = guid, samples = samples)
                                )
                            }
                        } finally {
                            runningCount.decrementAndGet()
                            remainingCount.decrementAndGet()
                            completedServerCount.incrementAndGet()
                            notifyProgress()
                        }
                    }
                }
            )
        }

        scope.launch {
            try {
                guids.forEach {
                    if (!isActive || !job.isActive || !shouldRun()) {
                        return@forEach
                    }
                    queue.send(it)
                }
            } finally {
                queue.close()
            }
        }

        scope.launch {
            try {
                workers.joinAll()
                if (shouldRun()) {
                    onFinish("0")
                }
            } catch (_: CancellationException) {
                if (shouldRun()) {
                    onFinish("-1")
                }
            } finally {
                heartbeatJob?.cancel()
                notifyProgress()
                close()
            }
        }
    }

    fun cancel() {
        job.cancel()
        try {
            executor.shutdownNow()
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun close() {
        speedtestConfigCache.clear()
        try {
            executor.shutdownNow()
        } catch (_: Throwable) {
            // ignore
        }
        try {
            dispatcher.close()
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun startRealPing(guid: String, primaryUrl: String, altUrl: String): LongArray {
        val retFailure = LongArray(MEASUREMENTS_PER_SERVER) { -1L }
        if (!shouldRun() || !job.isActive) {
            return retFailure
        }

        try {
            val configResult = getSpeedtestConfig(guid)
            if (!configResult.status) {
                notifyProgress()
                return retFailure
            }

            val primarySamples = measureSamples(
                config = configResult.content,
                testUrl = primaryUrl
            )
            if (!shouldRun() || !job.isActive) {
                return retFailure
            }
            if (!shouldTryAlternateUrl(primarySamples, primaryUrl, altUrl)) {
                return primarySamples
            }

            val fallbackSamples = measureSamples(
                config = configResult.content,
                testUrl = altUrl
            )
            return mergePrimaryAndFallbackSamples(primarySamples, fallbackSamples)
        } finally {
            speedtestConfigCache.remove(guid)
        }
    }

    private fun measureSamples(
        config: String,
        testUrl: String
    ): LongArray {
        // Fast path: one native startup for the whole sample series.
        val nativeSeries = V2RayNativeManager.measureOutboundDelaySeries(
            config = config,
            testUrl = testUrl,
            samples = MEASUREMENTS_PER_SERVER
        )
        if (nativeSeries != null && nativeSeries.isNotEmpty()) {
            return nativeSeries.copyOf(MEASUREMENTS_PER_SERVER)
        }

        // Backward-compatible path for older native bindings.
        val results = LongArray(MEASUREMENTS_PER_SERVER) { -1L }
        for (i in 0 until MEASUREMENTS_PER_SERVER) {
            if (!shouldRun() || !job.isActive) {
                break
            }
            val result = V2RayNativeManager.measureOutboundDelay(config, testUrl)
            results[i] = result
        }
        return results
    }

    private fun shouldTryAlternateUrl(primarySamples: LongArray, primaryUrl: String, altUrl: String): Boolean {
        if (altUrl.isBlank()) {
            return false
        }
        if (altUrl.equals(primaryUrl, ignoreCase = true)) {
            return false
        }
        return primarySamples.count { it >= 0L } < MIN_SUCCESS_SAMPLES_FOR_STABLE_RESULT
    }

    private fun mergePrimaryAndFallbackSamples(primarySamples: LongArray, fallbackSamples: LongArray): LongArray {
        val merged = primarySamples.copyOf(MEASUREMENTS_PER_SERVER)
        val fallbackSuccesses = fallbackSamples.filter { it >= 0L }
        if (fallbackSuccesses.isEmpty()) {
            return merged
        }

        var fallbackIndex = 0
        for (index in merged.indices) {
            if (merged[index] >= 0L) {
                continue
            }
            if (fallbackIndex >= fallbackSuccesses.size) {
                break
            }
            merged[index] = fallbackSuccesses[fallbackIndex]
            fallbackIndex++
        }
        return merged
    }

    private fun prepareWarmupConfig(): String? {
        val scanLimit = min(guids.size, WARMUP_CONFIG_SCAN_LIMIT)
        for (index in 0 until scanLimit) {
            val guid = guids[index]
            val configResult = getSpeedtestConfig(guid)
            if (configResult.status) {
                return configResult.content
            }
        }
        return null
    }

    private fun prewarmWorker(warmupConfig: String?, testUrl: String) {
        if (!shouldRun() || !job.isActive || warmupConfig.isNullOrBlank()) {
            return
        }
        try {
            // Warm up each prepared worker thread so initial measurements are less likely to timeout.
            V2RayNativeManager.measureOutboundDelay(warmupConfig, testUrl)
        } catch (_: Throwable) {
            // ignore warmup failures
        }
    }

    private fun getSpeedtestConfig(guid: String): CachedSpeedtestConfig {
        speedtestConfigCache[guid]?.let { return it }
        val configResult = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid)
        val cached = if (configResult.status) {
            CachedSpeedtestConfig(status = true, content = configResult.content)
        } else {
            CachedSpeedtestConfig(status = false, content = "")
        }
        speedtestConfigCache[guid] = cached
        return cached
    }

    private fun notifyProgress() {
        if (!shouldRun()) {
            return
        }
        val totalServers = guids.size.coerceAtLeast(1)
        val elapsedSeconds = ((System.currentTimeMillis() - startedAtMillis.get()) / 1000L).coerceAtLeast(0L)
        val content = buildString(96) {
            append(completedServerCount.get().coerceIn(0, totalServers))
            append('/')
            append(totalServers)
            append(" servers / ")
            append(runningCount.get().coerceAtLeast(0))
            append(" active / ")
            append(remainingCount.get().coerceAtLeast(0))
            append(" left / ")
            append(elapsedSeconds)
            append('s')
        }
        MessageUtil.sendMsg2UI(context, AppConfig.MSG_MEASURE_CONFIG_NOTIFY, content)
    }

    private class RealPingThreadFactory : ThreadFactory {
        private val id = AtomicInteger(1)

        override fun newThread(runnable: Runnable): Thread {
            return Thread(runnable, "RealPingWorker-${id.getAndIncrement()}").apply {
                priority = Thread.NORM_PRIORITY
            }
        }
    }
}
