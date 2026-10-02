package io.github.yfishyon.doumcp.features.search.resolver

import io.github.yfishyon.doumcp.core.DexKitSupport
import io.github.yfishyon.doumcp.core.HostRuntime
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 搜索域的定位器。
 *
 * 搜索 API 类名未混淆：接口静态字段指向 Retrofit 实例持有者，持有者内类型为搜索 API
 * 的静态字段即实例；实现类内唯一一个 (String 路径, Map 参数) → Retrofit Call
 * 的方法是通用表单请求入口（搜索、发评论都走它）。
 */
object SearchResolver {
    private const val SEARCH_API = "com.ss.android.ugc.aweme.discover.api.SearchApi\$RealApi"
    private const val RETROFIT_CALL = "com.bytedance.retrofit2.Call"

    @Volatile
    private var apiInstance: Any? = null

    /** 搜索 API 实例：接口静态字段 → Retrofit 持有者 → 持有者内类型匹配的静态字段。 */
    fun resolveApiProvider(): Any? {
        apiInstance?.let { return it }
        synchronized(this) {
            apiInstance?.let { return it }
            val apiClass = HostRuntime.hostClass(SEARCH_API)
            val holder =
                apiClass.declaredFields
                    .firstOrNull { Modifier.isStatic(it.modifiers) && it.type != apiClass }
                    ?.apply { isAccessible = true }
                    ?.get(null) ?: return null
            val instance =
                holder.javaClass.declaredFields
                    .firstOrNull { Modifier.isStatic(it.modifiers) && it.type == apiClass }
                    ?.apply { isAccessible = true }
                    ?.get(null) ?: return null
            apiInstance = instance
            return instance
        }
    }

    fun resolveGenericCall(): Method? =
        DexKitSupport.resolveCached("search_call") { dexKit ->
            dexKit
                .findClass {
                    matcher { className(SEARCH_API) }
                }.findMethod {
                    matcher {
                        paramTypes("java.lang.String", "java.util.Map")
                        returnType = RETROFIT_CALL
                    }
                }.firstOrNull()
                ?.getMethodInstance(HostRuntime.requireClassLoader())
        }
}
