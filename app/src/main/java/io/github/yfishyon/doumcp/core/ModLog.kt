package io.github.yfishyon.doumcp.core

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * 统一日志出口（libxposed 通道）：未 bind 前静默丢弃，不落系统 Log。
 */
object ModLog {
    private const val TAG = "DouMCP"

    @Volatile
    private var xposed: XposedInterface? = null

    /** 入口时机绑定（onModuleLoaded 调用一次） */
    fun bind(module: XposedInterface) {
        xposed = module
    }

    fun i(message: String) = dispatch(Log.INFO, message, null)

    fun w(message: String) = dispatch(Log.WARN, message, null)

    fun w(
        message: String,
        error: Throwable,
    ) = dispatch(Log.WARN, message, error)

    fun e(message: String) = dispatch(Log.ERROR, message, null)

    fun e(
        message: String,
        error: Throwable,
    ) = dispatch(Log.ERROR, message, error)

    private fun dispatch(
        priority: Int,
        message: String,
        error: Throwable?,
    ) {
        val module = xposed
        if (module != null) {
            if (error != null) {
                module.log(priority, TAG, message, error)
            } else {
                module.log(priority, TAG, message)
            }
        }
    }
}
