package io.github.yfishyon.doumcp.features.comment.resolver

import io.github.yfishyon.doumcp.core.DexKitSupport
import io.github.yfishyon.doumcp.core.HostRuntime
import io.github.yfishyon.doumcp.core.ModLog
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 评论表情包（动图）域的定位器。
 *
 * 表情数据都来自宿主的表情资源接口与表情商店取数入口：接口是纯 Retrofit 接口
 * （类里没有代码字符串），端点写在注解里、检索不到——按方法名全等定位，两个
 * 清单方法名在全包里唯一。接口实例由宿主的 IM 网络能力按 IM 基础地址创建，
 * 与宿主表情面板走同一条链路。
 *
 * 表情集列表由宿主的表情商店取数入口取（锚点串写在方法体里），入口内部完成
 * 凭证与公共参数拼装，返回值直接是聚合响应。
 */
object CommentStickerResolver {
    /** 评论区表情清单方法 */
    private const val TRENDING_METHOD = "getTrendingEmojis"

    /** 表情集明细方法（按资源 ID 取表情集与其表情） */
    private const val RESOURCE_DETAIL_METHOD = "getResourcesDetail"

    /** 表情商店取数入口所在类的锚点串（该串只写在该入口的方法体里） */
    private const val SET_LIST_ANCHOR = "STORE_RECOMMEND"

    private const val RESOURCES_RESPONSE =
        "com.ss.android.ugc.aweme.emoji.store.model.response.ResourcesListAggregationResponse"

    private const val PLATFORM_ABILITY = "com.im.platform.PlatformAbility"
    private const val NETWORK_ABILITY = "com.aweme.im.platform.basic.INetworkAbility"
    private const val COMMON_CONSTANTS = "com.ss.android.constants.CommonConstants"

    /** IM 接口基地址 = 主站域名 + 该后缀（宿主 IM 网络能力的接口都按此前缀注册） */
    private const val IM_API_SUFFIX = "/aweme/v1/"

    /** 接口实例按接口类复用 */
    private val apis = ConcurrentHashMap<Class<*>, Any>()

    /** 评论区表情清单方法。 */
    fun resolveListMethod(): Method? = resolveApiMethod(TRENDING_METHOD)

    /** 表情集明细方法。 */
    fun resolveDetailMethod(): Method? = resolveApiMethod(RESOURCE_DETAIL_METHOD)

    /** 表情商店取数入口（静态，场景名 → 聚合响应）。 */
    fun resolveSetListMethod(): Method? =
        DexKitSupport.resolveCached("comment_sticker_sets") { dexKit ->
            dexKit
                .findClass {
                    matcher { usingStrings(listOf(SET_LIST_ANCHOR), StringMatchType.Equals) }
                }.findMethod {
                    matcher {
                        paramTypes("java.lang.String")
                        returnType = RESOURCES_RESPONSE
                    }
                }.firstOrNull()
                ?.getMethodInstance(HostRuntime.requireClassLoader())
        }

    /** 按方法名定位接口方法（两个清单方法名在全包里唯一）。 */
    private fun resolveApiMethod(name: String): Method? =
        DexKitSupport.resolveCached("comment_sticker_api_$name") { dexKit ->
            dexKit
                .findMethod {
                    matcher { name(name, StringMatchType.Equals) }
                }.firstOrNull()
                ?.getMethodInstance(HostRuntime.requireClassLoader())
        }

    /** 接口实例（按接口类进程内复用）。 */
    fun api(method: Method): Any? {
        val iface = method.declaringClass
        apis[iface]?.let { return it }
        synchronized(apis) {
            apis[iface]?.let { return it }
            val instance =
                runCatching {
                    val network = HostRuntime.hostClass(PLATFORM_ABILITY).getMethod("getNetwork").invoke(null)
                    val prefix =
                        HostRuntime
                            .hostClass(COMMON_CONSTANTS)
                            .getField("API_URL_PREFIX_SI")
                            .get(null) as String
                    HostRuntime
                        .hostClass(NETWORK_ABILITY)
                        .getMethod(
                            "create",
                            Class::class.java,
                            String::class.java,
                            Boolean::class.javaPrimitiveType,
                        ).invoke(network, iface, prefix + IM_API_SUFFIX, true)
                }.getOrElse {
                    ModLog.e("表情接口创建失败", it.cause ?: it)
                    return null
                }
            apis[iface] = instance
            return instance
        }
    }
}
