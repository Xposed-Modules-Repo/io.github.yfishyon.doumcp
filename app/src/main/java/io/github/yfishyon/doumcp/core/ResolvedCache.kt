package io.github.yfishyon.doumcp.core

import android.content.Context
import io.fastkv.FastKV
import io.github.yfishyon.doumcp.BuildConfig

/**
 * DexKit 定位结果的持久化缓存（FastKV），键按模块版本 + 宿主版本隔离。
 */
object ResolvedCache {
    private const val STORE_NAME = "doumcp_resolved"

    private fun kv(context: Context): FastKV = FastKV.Builder(context, STORE_NAME).build()

    fun save(
        context: Context,
        key: String,
        value: String,
    ) {
        kv(context).putString(key, value)
    }

    fun load(
        context: Context,
        key: String,
    ): String? = kv(context).getString(key, "")?.takeIf { it.isNotEmpty() }

    /**
     * 构造带版本的缓存键：任一版本变化后旧缓存自然失效。
     *
     * @param hostVersionCode 宿主版本号
     * @param feature 功能名
     */
    fun versionedKey(
        hostVersionCode: Long,
        feature: String,
    ): String = "m${BuildConfig.VERSION_NAME}/v$hostVersionCode/$feature"
}
