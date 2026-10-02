package io.github.yfishyon.doumcp.features.search.resolver

import io.github.yfishyon.doumcp.core.DexKitSupport
import io.github.yfishyon.doumcp.core.HostRuntime
import java.lang.reflect.Method

/**
 * 搜索域的定位器。
 *
 * 搜索 API 类名未混淆；实现类内唯一一个 (String 路径, Map 参数) → Retrofit Call
 * 的方法是通用表单请求入口（搜索、发评论都走它）。
 */
object SearchResolver {
    private const val SEARCH_API = "com.ss.android.ugc.aweme.discover.api.SearchApi\$RealApi"
    private const val RETROFIT_CALL = "com.bytedance.retrofit2.Call"

    fun resolveApiProvider(): Method? =
        DexKitSupport.resolveCached("search_provider") { dexKit ->
            dexKit
                .findClass {
                    matcher { className(SEARCH_API) }
                }.findMethod {
                    matcher {
                        modifiers = java.lang.reflect.Modifier.STATIC
                        paramCount = 0
                        returnType = SEARCH_API
                    }
                }.firstOrNull()
                ?.getMethodInstance(HostRuntime.requireClassLoader())
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
