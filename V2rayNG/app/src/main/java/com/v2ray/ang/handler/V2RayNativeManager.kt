package com.v2ray.ang.handler

import android.content.Context
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.Utils
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V2Ray Native Library Manager
 *
 * Thread-safe singleton wrapper for Libv2ray native methods.
 * Provides initialization protection and unified API for V2Ray core operations.
 */
object V2RayNativeManager {
    private val initialized = AtomicBoolean(false)
    private val measureSeriesMethod: Method? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            Libv2ray::class.java.getMethod(
                "measureOutboundDelaySeries",
                String::class.java,
                String::class.java,
                java.lang.Long.TYPE
            )
        }.getOrNull()
    }
    private val setAttemptTimeoutMethod: Method? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            Libv2ray::class.java.getMethod(
                "setRealPingAttemptTimeoutMillis",
                java.lang.Long.TYPE
            )
        }.getOrNull()
    }

    /**
     * Initialize V2Ray core environment.
     * This method is thread-safe and ensures initialization happens only once.
     * Subsequent calls will be ignored silently.
     *
     */
    fun initCoreEnv(context: Context?) {
        if (initialized.compareAndSet(false, true)) {
            try {
                Seq.setContext(context?.applicationContext)
                val assetPath = Utils.userAssetPath(context)
                val deviceId = Utils.getDeviceIdForXUDPBaseKey()
                Libv2ray.initCoreEnv(assetPath, deviceId)
                Log.i(AppConfig.TAG, "V2Ray core environment initialized successfully")
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to initialize V2Ray core environment", e)
                initialized.set(false)
                throw e
            }
        } else {
            Log.d(AppConfig.TAG, "V2Ray core environment already initialized, skipping")
        }
    }


    /**
     * Get V2Ray core version.
     *
     * @return Version string of the V2Ray core
     */
    fun getLibVersion(): String {
        return try {
            Libv2ray.checkVersionX()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to check V2Ray version", e)
            "Unknown"
        }
    }

    /**
     * Measure outbound connection delay.
     *
     * @param config The configuration JSON string
     * @param testUrl The URL to test against
     * @return Delay in milliseconds, or -1 if test failed
     */
    fun measureOutboundDelay(config: String, testUrl: String): Long {
        return try {
            Libv2ray.measureOutboundDelay(config, testUrl)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to measure outbound delay", e)
            -1L
        }
    }

    /**
     * Measure multiple outbound delay samples in one native session.
     * Returns null when native batch measurement is unavailable or malformed.
     */
    fun measureOutboundDelaySeries(config: String, testUrl: String, samples: Int): LongArray? {
        if (samples <= 0) {
            return LongArray(0)
        }
        return try {
            val method = measureSeriesMethod ?: return null
            val payload = method.invoke(null, config, testUrl, samples.toLong()) as? String
            parseDelaySeries(payload, samples)
        } catch (e: Throwable) {
            Log.e(AppConfig.TAG, "Failed to measure outbound delay series", e)
            null
        }
    }

    /**
     * Configure real-ping attempt timeout in milliseconds.
     */
    fun setRealPingAttemptTimeoutMillis(timeoutMillis: Long) {
        try {
            val method = setAttemptTimeoutMethod ?: return
            method.invoke(null, timeoutMillis)
        } catch (e: Throwable) {
            Log.e(AppConfig.TAG, "Failed to set real-ping attempt timeout", e)
        }
    }

    private fun parseDelaySeries(payload: String?, expectedSamples: Int): LongArray? {
        if (payload.isNullOrBlank()) {
            return null
        }
        val parsed = Regex("-?\\d+")
            .findAll(payload)
            .mapNotNull { it.value.toLongOrNull() }
            .toList()
        if (parsed.isEmpty()) {
            return null
        }

        val ret = LongArray(expectedSamples) { -1L }
        val limit = minOf(expectedSamples, parsed.size)
        for (index in 0 until limit) {
            ret[index] = parsed[index]
        }
        return ret
    }

    /**
     * Create a new core controller instance.
     *
     * @param handler The callback handler for core events
     * @return A new CoreController instance
     */
    fun newCoreController(handler: CoreCallbackHandler): CoreController {
        return try {
            Libv2ray.newCoreController(handler)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to create core controller", e)
            throw e
        }
    }
}
