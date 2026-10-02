package io.github.yfishyon.doumcp.core

import android.content.Context

/**
 * 宿主运行时共享状态：上下文、ClassLoader、宿主版本号、进程名（进程内全局可读）。
 */
object HostRuntime {
    @Volatile
    var context: Context? = null

    @Volatile
    var classLoader: ClassLoader? = null

    @Volatile
    var versionCode: Long = -1

    @Volatile
    var processName: String = ""

    /** 取宿主上下文（未捕获时抛错，调用方按失败处理） */
    fun requireContext(): Context = context ?: throw IllegalStateException("宿主上下文未捕获")

    /** 取宿主 ClassLoader（未捕获时抛错，调用方按失败处理） */
    fun requireClassLoader(): ClassLoader = classLoader ?: throw IllegalStateException("宿主 ClassLoader 未捕获")

    /** 按名加载宿主类（不触发初始化） */
    fun hostClass(name: String): Class<*> = Class.forName(name, false, requireClassLoader())
}
