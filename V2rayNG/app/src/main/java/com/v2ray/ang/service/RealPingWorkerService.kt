package com.v2ray.ang.service

import android.content.Context
import android.os.SystemClock
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2RayNativeManager
import com.v2ray.ang.handler.V2rayConfigManager
import com.v2ray.ang.util.MessageUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.channels.Channel
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
        private const val PROGRESS_MIN_UPDATE_INTERVAL_MS = 200L
        private const val NETWORK_WARMUP_WORKER_LIMIT = 1
        private const val MIN_SUCCESS_SAMPLES_FOR_WORKING_RESULT = 1
        private const val FIRST_WAVE_PARALLEL_URLS_HIGH_THREADS = 3
        private const val FIRST_WAVE_PARALLEL_URLS_MID_THREADS = 2
        private const val FIRST_WAVE_PARALLEL_THREADS_HIGH_THRESHOLD = 256
        private const val FIRST_WAVE_PARALLEL_THREADS_MID_THRESHOLD = 96
        private const val MAX_PARALLEL_FALLBACK_URLS = 4
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
    private val workerThreads = min(configuredThreads, guids.size.coerceAtLeast(1)).coerceAtLeast(1)
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
        workerThreads.coerceAtLeast(1),
        true
    )
    private val firstWaveParallelUrlCount = when {
        workerThreads >= FIRST_WAVE_PARALLEL_THREADS_HIGH_THRESHOLD -> FIRST_WAVE_PARALLEL_URLS_HIGH_THREADS
        workerThreads >= FIRST_WAVE_PARALLEL_THREADS_MID_THRESHOLD -> FIRST_WAVE_PARALLEL_URLS_MID_THREADS
        else -> 1
    }

    private val runningCount = AtomicInteger(0)
    private val remainingCount = AtomicInteger(0)
    private val completedServerCount = AtomicInteger(0)
    private val startedAtMillis = AtomicLong(0L)
    private val lastProgressNotifyAtMs = AtomicLong(0L)
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
        lastProgressNotifyAtMs.set(0L)
        notifyProgress(force = true)

        heartbeatJob = scope.launch {
            while (isActive && shouldRun()) {
                delay(PROGRESS_HEARTBEAT_MS)
                notifyProgress()
            }
        }

        val queue = Channel<String>(capacity = workerThreads * 2)
        val workers = mutableListOf<kotlinx.coroutines.Job>()
        val activeWorkers = workerThreads
        val warmupConfig = prepareWarmupConfig()

        val warmupWorkers = min(activeWorkers, NETWORK_WARMUP_WORKER_LIMIT)
        repeat(activeWorkers) { workerIndex ->
            workers.add(
                scope.launch {
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
                notifyProgress(force = true)
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

    private suspend fun startRealPing(guid: String, probeUrls: List<String>): LongArray {
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
            var attemptedCount = 0
            val firstWaveUrls = orderedProbeUrls
                .take(firstWaveParallelUrlCount.coerceIn(1, orderedProbeUrls.size))
            var mergedSamples = LongArray(MEASUREMENTS_PER_SERVER) { -1L }
            var successCount = mergedSamples.count { it >= 0L }

            if (firstWaveUrls.size == 1) {
                val primaryUrl = firstWaveUrls.first()
                val primarySamples = measureSamples(
                    config = configResult.content,
                    testUrl = primaryUrl,
                    sampleCount = INITIAL_PRIMARY_SAMPLE_COUNT
                )
                recordProbeUrlOutcome(primaryUrl, primarySamples)
                attemptedCount += primarySamples.size
                mergedSamples = normalizeSamples(primarySamples)
                successCount = mergedSamples.count { it >= 0L }
                if (successCount >= MIN_SUCCESS_SAMPLES_FOR_WORKING_RESULT) {
                    return trimDisplaySamples(mergedSamples, attemptedCount)
                }
            } else {
                val firstWaveAttempts = measureFallbackUrlsInParallel(
                    config = configResult.content,
                    probeUrls = firstWaveUrls
                )
                attemptedCount += firstWaveAttempts.attemptedCount
                if (firstWaveAttempts.bestSamples.isNotEmpty()) {
                    mergedSamples = mergePrimaryAndFallbackSamples(mergedSamples, firstWaveAttempts.bestSamples)
                    successCount = mergedSamples.count { it >= 0L }
                    if (successCount >= MIN_SUCCESS_SAMPLES_FOR_WORKING_RESULT) {
                        return trimDisplaySamples(mergedSamples, attemptedCount)
                    }
                }
            }

            val fallbackAttempts = measureFallbackUrlsInParallel(
                config = configResult.content,
                probeUrls = orderedProbeUrls.drop(firstWaveUrls.size)
            )
            attemptedCount += fallbackAttempts.attemptedCount
            if (fallbackAttempts.bestSamples.isNotEmpty()) {
                mergedSamples = mergePrimaryAndFallbackSamples(mergedSamples, fallbackAttempts.bestSamples)
                successCount = mergedSamples.count { it >= 0L }
                if (successCount >= MIN_SUCCESS_SAMPLES_FOR_WORKING_RESULT) {
                    return trimDisplaySamples(mergedSamples, attemptedCount)
                }
            }

            return trimDisplaySamples(mergedSamples, attemptedCount)
        } finally {
            speedtestConfigCache.remove(guid)
        }
    }

    private data class FallbackAttemptResult(
        val attemptedCount: Int,
        val bestSamples: LongArray
    )

    private suspend fun measureFallbackUrlsInParallel(
        config: String,
        probeUrls: List<String>
    ): FallbackAttemptResult = coroutineScope {
        if (probeUrls.isEmpty() || !shouldRun() || !job.isActive) {
            return@coroutineScope FallbackAttemptResult(
                attemptedCount = 0,
                bestSamples = LongArray(0)
            )
        }

        val candidates = probeUrls
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_PARALLEL_FALLBACK_URLS)
            .toList()
        if (candidates.isEmpty()) {
            return@coroutineScope FallbackAttemptResult(
                attemptedCount = 0,
                bestSamples = LongArray(0)
            )
        }

        val tasks = candidates.map { url ->
            async(Dispatchers.IO) {
                if (!shouldRun() || !job.isActive) {
                    url to LongArray(0)
                } else {
                    url to measureSamples(
                        config = config,
                        testUrl = url,
                        sampleCount = 1
                    )
                }
            }
        }.toMutableList()

        var attemptedCount = 0
        var bestSamples = LongArray(0)
        try {
            while (tasks.isNotEmpty() && shouldRun() && job.isActive) {
                val (finished, outcome) = select<Pair<kotlinx.coroutines.Deferred<Pair<String, LongArray>>, Pair<String, LongArray>>> {
                    tasks.forEach { task ->
                        task.onAwait { value -> task to value }
                    }
                }
                tasks.remove(finished)

                val (url, samples) = outcome
                if (samples.isEmpty()) {
                    continue
                }
                attemptedCount += samples.size
                recordProbeUrlOutcome(url, samples)
                if (samples.any { it >= 0L }) {
                    bestSamples = samples
                    break
                }
            }
        } finally {
            tasks.forEach { it.cancel() }
        }

        return@coroutineScope FallbackAttemptResult(
            attemptedCount = attemptedCount,
            bestSamples = bestSamples
        )
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
        val firstGuid = guids.firstOrNull() ?: return null
        val configResult = getSpeedtestConfig(firstGuid)
        return configResult.content.takeIf { configResult.status }
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
        val speedtestConfig = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid)
        val cached = if (speedtestConfig.status && speedtestConfig.content.isNotBlank()) {
            CachedSpeedtestConfig(status = true, content = speedtestConfig.content)
        } else {
            // Fallback to full runtime config for profiles that fail speedtest config generation.
            val fullConfig = V2rayConfigManager.getV2rayConfig(context, guid)
            if (fullConfig.status && fullConfig.content.isNotBlank()) {
                CachedSpeedtestConfig(status = true, content = fullConfig.content)
            } else {
                CachedSpeedtestConfig(status = false, content = "")
            }
        }
        speedtestConfigCache[guid] = cached
        return cached
    }

    private fun notifyProgress(force: Boolean = false) {
        if (!shouldRun()) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!force) {
            val last = lastProgressNotifyAtMs.get()
            if (now - last < PROGRESS_MIN_UPDATE_INTERVAL_MS) {
                return
            }
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
            append(" / ")
            append(workerThreads)
            append(" threads")
        }
        lastProgressNotifyAtMs.set(now)
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
