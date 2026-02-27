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
import java.util.concurrent.Semaphore
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
        private const val INITIAL_PRIMARY_SAMPLE_COUNT = 1
        private const val PROGRESS_HEARTBEAT_MS = 1000L
        private const val STARTUP_BARRIER_TIMEOUT_SECONDS = 2L
        private const val NETWORK_WARMUP_WORKER_LIMIT = 2
        private const val WARMUP_CONFIG_SCAN_LIMIT = 64
        private const val MIN_SUCCESS_SAMPLES_FOR_WORKING_RESULT = 1
        private const val MAX_PARALLEL_NATIVE_MEASUREMENTS = 64
        private const val LAST_RESORT_SAMPLE_COUNT = 1
        private const val PROBE_URL_SCORE_SUCCESS_DELTA = 4
        private const val PROBE_URL_SCORE_FAILURE_DELTA = -3
        private const val PROBE_URL_SCORE_MIN = -100
        private const val PROBE_URL_SCORE_MAX = 100
        private val EXTRA_DELAY_TEST_URLS = listOf(
            "https://cp.cloudflare.com/generate_204"
        )
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
    private val nativeMeasurementSemaphore = Semaphore(
        min(workerThreads, MAX_PARALLEL_NATIVE_MEASUREMENTS).coerceAtLeast(1),
        true
    )

    private val runningCount = AtomicInteger(0)
    private val remainingCount = AtomicInteger(0)
    private val completedServerCount = AtomicInteger(0)
    private val startedAtMillis = AtomicLong(0L)
    private val speedtestConfigCache = ConcurrentHashMap<String, CachedSpeedtestConfig>()
    private val probeUrlScores = ConcurrentHashMap<String, AtomicInteger>()
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
        val probeUrlCandidates = buildProbeUrlCandidates(primaryTestUrl, alternateTestUrl)
        seedProbeUrlScores(probeUrlCandidates)
        val warmupUrl = probeUrlCandidates.firstOrNull().orEmpty()
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
                        prewarmWorker(warmupConfig, warmupUrl)
                    }

                    for (guid in queue) {
                        if (!isActive || !job.isActive || !shouldRun()) {
                            break
                        }
                        runningCount.incrementAndGet()
                        notifyProgress()
                        try {
                            val samples = startRealPing(guid, probeUrlCandidates)
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
        probeUrlScores.clear()
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

    private fun startRealPing(guid: String, probeUrls: List<String>): LongArray {
        val retFailure = LongArray(MEASUREMENTS_PER_SERVER) { -1L }
        if (!shouldRun() || !job.isActive || probeUrls.isEmpty()) {
            return retFailure
        }

        try {
            val configResult = getSpeedtestConfig(guid)
            if (!configResult.status) {
                notifyProgress()
                return retFailure
            }

            val orderedProbeUrls = prioritizeProbeUrls(probeUrls)
            if (orderedProbeUrls.isEmpty()) {
                return retFailure
            }
            val primaryUrl = orderedProbeUrls.first()
            var attemptedCount = 0
            val primarySamples = measureSamples(
                config = configResult.content,
                testUrl = primaryUrl,
                sampleCount = INITIAL_PRIMARY_SAMPLE_COUNT
            )
            recordProbeUrlOutcome(primaryUrl, primarySamples)
            attemptedCount += primarySamples.size
            var mergedSamples = normalizeSamples(
                primarySamples
            )
            var successCount = mergedSamples.count { it >= 0L }
            if (successCount >= MIN_SUCCESS_SAMPLES_FOR_WORKING_RESULT) {
                return trimDisplaySamples(mergedSamples, attemptedCount)
            }

            val secondaryUrl = orderedProbeUrls.getOrNull(1)
            if (!secondaryUrl.isNullOrBlank() && shouldRun() && job.isActive) {
                val secondaryBudget = min(
                    mergedSamples.count { it < 0L },
                    (MIN_SUCCESS_SAMPLES_FOR_WORKING_RESULT - successCount).coerceAtLeast(1)
                )
                if (secondaryBudget > 0) {
                    val secondarySamples = measureSamples(
                        config = configResult.content,
                        testUrl = secondaryUrl,
                        sampleCount = secondaryBudget
                    )
                    recordProbeUrlOutcome(secondaryUrl, secondarySamples)
                    attemptedCount += secondarySamples.size
                    mergedSamples = mergePrimaryAndFallbackSamples(mergedSamples, secondarySamples)
                    successCount = mergedSamples.count { it >= 0L }
                    if (successCount >= MIN_SUCCESS_SAMPLES_FOR_WORKING_RESULT) {
                        return trimDisplaySamples(mergedSamples, attemptedCount)
                    }
                }
            }

            if (successCount < MIN_SUCCESS_SAMPLES_FOR_WORKING_RESULT) {
                for (extraUrl in orderedProbeUrls.drop(2)) {
                    if (!shouldRun() || !job.isActive) {
                        break
                    }
                    val remainingSlots = mergedSamples.count { it < 0L }
                    if (remainingSlots <= 0) {
                        break
                    }
                    val extraBudget = min(
                        remainingSlots,
                        min(
                            (MIN_SUCCESS_SAMPLES_FOR_WORKING_RESULT - successCount).coerceAtLeast(1),
                            LAST_RESORT_SAMPLE_COUNT
                        )
                    )
                    if (extraBudget <= 0) {
                        break
                    }
                    val extraSamples = measureSamples(
                        config = configResult.content,
                        testUrl = extraUrl,
                        sampleCount = extraBudget
                    )
                    recordProbeUrlOutcome(extraUrl, extraSamples)
                    attemptedCount += extraSamples.size
                    mergedSamples = mergePrimaryAndFallbackSamples(mergedSamples, extraSamples)
                    successCount = mergedSamples.count { it >= 0L }
                    if (successCount >= MIN_SUCCESS_SAMPLES_FOR_WORKING_RESULT) {
                        break
                    }
                }
            }

            return trimDisplaySamples(mergedSamples, attemptedCount)
        } finally {
            speedtestConfigCache.remove(guid)
        }
    }

    private fun measureSamples(
        config: String,
        testUrl: String,
        sampleCount: Int
    ): LongArray {
        val normalizedSampleCount = sampleCount.coerceIn(1, MEASUREMENTS_PER_SERVER)
        if (testUrl.isBlank()) {
            return LongArray(normalizedSampleCount) { -1L }
        }

        // Fast path: one native startup for the whole sample series.
        val nativeSeries = withNativeMeasurementPermit {
            V2RayNativeManager.measureOutboundDelaySeries(
                config = config,
                testUrl = testUrl,
                samples = normalizedSampleCount
            )
        }
        if (nativeSeries != null && nativeSeries.isNotEmpty()) {
            return nativeSeries.copyOf(normalizedSampleCount)
        }

        // Backward-compatible path for older native bindings.
        val results = LongArray(normalizedSampleCount) { -1L }
        for (i in 0 until normalizedSampleCount) {
            if (!shouldRun() || !job.isActive) {
                break
            }
            val result = withNativeMeasurementPermit {
                V2RayNativeManager.measureOutboundDelay(config, testUrl)
            } ?: break
            results[i] = result
        }
        return results
    }

    private fun <T> withNativeMeasurementPermit(block: () -> T): T? {
        while (shouldRun() && job.isActive) {
            try {
                if (!nativeMeasurementSemaphore.tryAcquire(100L, TimeUnit.MILLISECONDS)) {
                    continue
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            try {
                return block()
            } finally {
                nativeMeasurementSemaphore.release()
            }
        }
        return null
    }

    private fun buildProbeUrlCandidates(primaryUrl: String, altUrl: String): List<String> {
        val candidates = linkedSetOf<String>()
        fun addCandidate(url: String?) {
            val normalized = url?.trim().orEmpty()
            if (normalized.isNotEmpty()) {
                candidates.add(normalized)
            }
        }

        addCandidate(primaryUrl)
        addCandidate(altUrl)
        addCandidate(AppConfig.DELAY_TEST_URL)
        addCandidate(AppConfig.DELAY_TEST_URL2)
        EXTRA_DELAY_TEST_URLS.forEach(::addCandidate)
        return candidates.toList()
    }

    private fun seedProbeUrlScores(candidates: List<String>) {
        if (candidates.isEmpty()) {
            return
        }
        val size = candidates.size
        candidates.forEachIndexed { index, url ->
            probeUrlScores.computeIfAbsent(url) {
                AtomicInteger(size - index)
            }
        }
    }

    private fun prioritizeProbeUrls(probeUrls: List<String>): List<String> {
        if (probeUrls.size <= 1) {
            return probeUrls
        }
        val baseOrder = HashMap<String, Int>(probeUrls.size)
        probeUrls.forEachIndexed { index, url ->
            baseOrder[url] = index
        }
        val size = probeUrls.size
        return probeUrls.sortedWith(
            compareByDescending<String> { url ->
                probeUrlScores[url]?.get() ?: (size - (baseOrder[url] ?: size))
            }.thenBy { url ->
                baseOrder[url] ?: Int.MAX_VALUE
            }
        )
    }

    private fun recordProbeUrlOutcome(url: String, samples: LongArray) {
        if (url.isBlank()) {
            return
        }
        val score = probeUrlScores.computeIfAbsent(url) { AtomicInteger(0) }
        val delta = if (samples.any { it >= 0L }) {
            PROBE_URL_SCORE_SUCCESS_DELTA
        } else {
            PROBE_URL_SCORE_FAILURE_DELTA
        }
        while (true) {
            val current = score.get()
            val next = (current + delta).coerceIn(PROBE_URL_SCORE_MIN, PROBE_URL_SCORE_MAX)
            if (score.compareAndSet(current, next)) {
                break
            }
        }
    }

    private fun mergePrimaryAndFallbackSamples(primarySamples: LongArray, fallbackSamples: LongArray): LongArray {
        val merged = normalizeSamples(primarySamples)
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

    private fun normalizeSamples(samples: LongArray): LongArray {
        val normalized = LongArray(MEASUREMENTS_PER_SERVER) { -1L }
        val limit = min(samples.size, MEASUREMENTS_PER_SERVER)
        for (index in 0 until limit) {
            normalized[index] = samples[index]
        }
        return normalized
    }

    private fun trimDisplaySamples(samples: LongArray, attemptedCount: Int): LongArray {
        val safeCount = attemptedCount.coerceIn(1, MEASUREMENTS_PER_SERVER)
        return samples.copyOf(safeCount)
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
        if (!shouldRun() || !job.isActive || warmupConfig.isNullOrBlank() || testUrl.isBlank()) {
            return
        }
        try {
            // Warm up each prepared worker thread so initial measurements are less likely to timeout.
            withNativeMeasurementPermit {
                V2RayNativeManager.measureOutboundDelay(warmupConfig, testUrl)
            }
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
