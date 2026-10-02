package io.github.yfishyon.doumcp.core

/**
 * 宿主 API 域名前缀：反射读常量类上的全局字段，懒加载 + 缓存。
 */
object ApiPrefix {
    @Volatile
    private var prefix: String? = null

    /** 取 API 前缀（首次反射读取，之后走缓存） */
    fun get(): String {
        prefix?.let { return it }
        synchronized(this) {
            if (prefix == null) {
                val constantsClass = HostRuntime.hostClass("com.ss.android.constants.CommonConstants")
                prefix = constantsClass.getDeclaredField("API_URL_PREFIX_SI").get(null) as String
            }
            return prefix!!
        }
    }
}
