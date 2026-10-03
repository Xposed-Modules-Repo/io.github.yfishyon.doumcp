package io.github.yfishyon.doumcp.features.comment.resolver

import io.github.yfishyon.doumcp.core.DexKitSupport
import io.github.yfishyon.doumcp.core.HostRuntime
import io.github.yfishyon.doumcp.core.ModLog
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 评论域的定位器。
 *
 * 评论接口是宿主自己的 Retrofit 接口（类名与端点都未混淆），实例由宿主
 * 的 Retrofit 服务工厂生成——与宿主自身发评论走同一条链路，公共参数与
 * 请求签名都由宿主的拦截器补齐。
 */
object CommentResolver {
    private const val COMMENT_REPLY_PATH = "/aweme/v1/comment/list/reply/"
    private const val COMMENT_API = "com.ss.android.ugc.aweme.comment.api.CommentRealApi"
    private const val RETROFIT_SERVICE = "com.ss.android.ugc.aweme.services.RetrofitService"
    private const val I_RETROFIT_SERVICE = "com.ss.android.ugc.aweme.framework.services.IRetrofitService"
    private const val I_RETROFIT = "com.ss.android.ugc.aweme.framework.services.IRetrofit"
    private const val COMMON_CONSTANTS = "com.ss.android.constants.CommonConstants"

    /** 发布方法参数个数（接口方法里唯一，用于定位） */
    private const val PUBLISH_PARAM_COUNT = 69

    @Volatile
    private var api: Any? = null

    /**
     * 评论接口实例（进程内复用）。
     *
     * 链路：Retrofit 服务入口 → 按主站域名建 Retrofit → 生成接口代理。
     */
    fun api(): Any? {
        api?.let { return it }
        synchronized(this) {
            api?.let { return it }
            val instance =
                runCatching {
                    val service = retrofitService()
                    val prefix =
                        HostRuntime
                            .hostClass(COMMON_CONSTANTS)
                            .getField("API_URL_PREFIX_SI")
                            .get(null) as String
                    val retrofit =
                        service.javaClass
                            .getMethod("createNewRetrofit", String::class.java)
                            .invoke(service, prefix)
                    HostRuntime
                        .hostClass(I_RETROFIT)
                        .methods
                        .first { it.name == "create" && it.parameterCount == 1 }
                        .invoke(retrofit, HostRuntime.hostClass(COMMENT_API))
                }.getOrElse {
                    ModLog.e("评论接口创建失败", it.cause ?: it)
                    return null
                }
            api = instance
            return instance
        }
    }

    /** 发布方法（接口上参数最多的那个，端点写死为发布接口）。 */
    fun resolvePublishMethod(): Method? =
        HostRuntime.hostClass(COMMENT_API).methods.firstOrNull { it.parameterCount == PUBLISH_PARAM_COUNT }

    /** 楼中楼拉取方法（挂起函数：请求参数对象 + Continuation → 评论列表）。 */
    fun resolveReplyFetcher(): Method? =
        DexKitSupport.resolveCached("comment_reply") { dexKit ->
            dexKit
                .findClass {
                    matcher { usingStrings(listOf(COMMENT_REPLY_PATH), StringMatchType.Equals) }
                }.findMethod {
                    matcher {
                        paramTypes(null, "kotlin.coroutines.Continuation")
                        returnType = "java.lang.Object"
                        usingStrings(listOf("comment_id"), StringMatchType.Equals)
                    }
                }.firstOrNull()
                ?.getMethodInstance(HostRuntime.requireClassLoader())
        }

    /** Retrofit 服务入口（宿主静态方法，返回服务接口实现）。 */
    private fun retrofitService(): Any {
        val entry =
            HostRuntime.hostClass(RETROFIT_SERVICE).declaredMethods.firstOrNull {
                Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    it.returnType.name == I_RETROFIT_SERVICE
            } ?: throw IllegalStateException("Retrofit 服务入口未找到")
        return entry.invoke(null) ?: throw IllegalStateException("Retrofit 服务为空")
    }
}
