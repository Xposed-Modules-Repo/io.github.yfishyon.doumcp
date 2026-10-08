package io.github.yfishyon.doumcp.features.notice.resolver

import io.github.yfishyon.doumcp.core.DexKitSupport
import io.github.yfishyon.doumcp.core.HostRuntime
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method

/**
 * 互动通知域的定位器。
 *
 * 通知中心的三个入口（全部是宿主自己的网络层方法）：
 * - 发出的评论：接口方法名全等锚点，(起始时间, 条数) → Observable
 * - 通知列表（@我/收到的评论/赞/粉丝…）：接口方法名全等锚点，多参 → ListenableFuture
 * - 接口实例持有者：类内标签字符串全等锚点；其静态字段里类型即接口类的那个是现成实例
 * - 分组换算：通知分组 ID 由服务端配置下发（不同账号/版本不同），不能写死；
 *   含子类型标签字符串的类里的 (int) → int 静态方法做「语义类型 → 分组 ID」换算
 */
object NoticeResolver {
    private const val OBSERVABLE = "io.reactivex.Observable"
    private const val LISTENABLE_FUTURE = "com.google.common.util.concurrent.ListenableFuture"

    /** 发出的评论接口方法。 */
    fun resolveMineCommentMethod(): Method? =
        DexKitSupport.resolveCached("notice_mine_comment") { dexKit ->
            dexKit
                .findMethod {
                    matcher {
                        name("getMineComment", StringMatchType.Equals)
                        returnType(OBSERVABLE, StringMatchType.Equals)
                        paramTypes("long", "int")
                    }
                }.firstOrNull()
                ?.getMethodInstance(HostRuntime.requireClassLoader())
        }

    /** 通知列表接口方法（@我/收到的评论/赞/粉丝等分组共用）。 */
    fun resolveFetchNoticeMethod(): Method? =
        DexKitSupport.resolveCached("notice_fetch") { dexKit ->
            dexKit
                .findMethod {
                    matcher {
                        name("fetchNotice", StringMatchType.Equals)
                        returnType(LISTENABLE_FUTURE, StringMatchType.Equals)
                    }
                }.firstOrNull()
                ?.getMethodInstance(HostRuntime.requireClassLoader())
        }

    /** 接口实例持有类（其静态字段里挂着建好的 Retrofit 接口实例）。 */
    fun resolveApiHolderClass(): Class<*>? = resolveHolderTagMethod()?.declaringClass

    /** 分组换算方法：语义类型（0=粉丝 1=赞 2=@我 3=评论 8=推荐…）→ 服务端分组 ID。 */
    fun resolveGroupMapMethod(): Method? =
        DexKitSupport.resolveCached("notice_group_map") { dexKit ->
            dexKit
                .findClass {
                    matcher {
                        usingStrings(listOf("subtype_comment", "subtype_danmaku"), StringMatchType.Equals)
                    }
                }.findMethod {
                    matcher {
                        paramTypes("int")
                        returnType("int")
                    }
                }.firstOrNull()
                ?.getMethodInstance(HostRuntime.requireClassLoader())
        }

    private fun resolveHolderTagMethod(): Method? =
        DexKitSupport.resolveCached("notice_api_holder") { dexKit ->
            dexKit
                .findMethod {
                    matcher {
                        usingStrings(listOf("NoticeApiManager"), StringMatchType.Equals)
                    }
                }.firstOrNull()
                ?.getMethodInstance(HostRuntime.requireClassLoader())
        }
}
