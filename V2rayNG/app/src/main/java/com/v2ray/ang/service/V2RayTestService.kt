package com.v2ray.ang.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.MSG_MEASURE_CONFIG
import com.v2ray.ang.AppConfig.MSG_MEASURE_CONFIG_CANCEL
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.V2RayNativeManager
import com.v2ray.ang.util.MessageUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class V2RayTestService : Service() {

    // Each start request creates a new generation. Older generations are treated as stale and ignored.
    private val batchGeneration = AtomicLong(0L)
    private val activeWorkers = ConcurrentHashMap<Long, RealPingWorkerService>()

    /**
     * Initializes the V2Ray environment.
     */
    override fun onCreate() {
        super.onCreate()
        V2RayNativeManager.initCoreEnv(this)
    }

    /**
     * Binds the service.
     * @param intent The intent.
     * @return The binder.
     */
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Cleans up resources when the service is destroyed.
     */
    override fun onDestroy() {
        super.onDestroy()
        batchGeneration.incrementAndGet()
        cancelAllWorkers()
        RealPingBatchStore.clear()
    }

    /**
     * Handles the start command for the service.
     * @param intent The intent.
     * @param flags The flags.
     * @param startId The start ID.
     * @return The start mode.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getIntExtra("key", 0)) {
            MSG_MEASURE_CONFIG -> {
                val guidsList = resolveGuids(intent)
                if (guidsList.isNullOrEmpty()) {
                    MessageUtil.sendMsg2UI(this@V2RayTestService, AppConfig.MSG_MEASURE_CONFIG_FINISH, "-1")
                } else {
                    val batchId = batchGeneration.incrementAndGet()
                    cancelAllWorkers()

                    val worker = RealPingWorkerService(
                        context = this,
                        guids = guidsList,
                        shouldRun = { batchGeneration.get() == batchId }
                    ) { status ->
                        activeWorkers.remove(batchId)
                        if (batchGeneration.get() == batchId) {
                            MessageUtil.sendMsg2UI(this@V2RayTestService, AppConfig.MSG_MEASURE_CONFIG_FINISH, status)
                        }
                    }
                    activeWorkers[batchId] = worker
                    worker.start()
                }
            }

            MSG_MEASURE_CONFIG_CANCEL -> {
                batchGeneration.incrementAndGet()
                cancelAllWorkers()
                RealPingBatchStore.clear()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun resolveGuids(intent: Intent): ArrayList<String>? {
        val batchId = intent.getLongExtra("content_batch_id", -1L).takeIf { it > 0L }
            ?: intent.serializable<Long>("content")
        if (batchId != null) {
            return RealPingBatchStore.takeBatch(batchId)
        }
        return intent.serializable<ArrayList<String>>("content")
    }

    private fun cancelAllWorkers() {
        val snapshot = activeWorkers.values.toList()
        activeWorkers.clear()
        snapshot.forEach { it.cancel() }
    }
}
