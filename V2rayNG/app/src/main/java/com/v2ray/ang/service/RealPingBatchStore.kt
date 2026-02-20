package com.v2ray.ang.service

import com.tencent.mmkv.MMKV
import com.v2ray.ang.util.JsonUtil
import java.util.UUID

/**
 * Keeps large real-ping batch payloads in MMKV so they are accessible cross-process.
 */
object RealPingBatchStore {
    private const val ID_REAL_PING_BATCH = "REAL_PING_BATCH"
    private const val KEY_BATCH_PREFIX = "batch_"
    private const val KEY_LATEST_BATCH_ID = "latest_id"
    private val storage by lazy { MMKV.mmkvWithID(ID_REAL_PING_BATCH, MMKV.MULTI_PROCESS_MODE) }

    fun createBatch(guids: List<String>): Long {
        val id = (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE)
        clear()
        storage.encode("$KEY_BATCH_PREFIX$id", JsonUtil.toJson(guids))
        storage.encode(KEY_LATEST_BATCH_ID, id)
        return id
    }

    fun takeBatch(id: Long): ArrayList<String>? {
        val json = storage.decodeString("$KEY_BATCH_PREFIX$id") ?: return null
        storage.remove("$KEY_BATCH_PREFIX$id")
        if (storage.decodeLong(KEY_LATEST_BATCH_ID, -1L) == id) {
            storage.remove(KEY_LATEST_BATCH_ID)
        }
        val list = JsonUtil.fromJson(json, Array<String>::class.java)?.toMutableList() ?: return null
        return ArrayList(list)
    }

    fun clear() {
        storage.allKeys()?.forEach { key ->
            if (key.startsWith(KEY_BATCH_PREFIX) || key == KEY_LATEST_BATCH_ID) {
                storage.remove(key)
            }
        }
    }
}
