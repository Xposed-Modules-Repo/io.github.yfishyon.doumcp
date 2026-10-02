package io.github.yfishyon.doumcp.features.comment.resolver

import io.github.yfishyon.doumcp.core.DexKitSupport
import io.github.yfishyon.doumcp.core.HostRuntime
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method

/**
 * 评论域的定位器：楼中楼拉取方法。
 *
 * 所在类是子评论接口的请求发起方（含接口路径字符串），方法是挂起函数
 * （参数含 Continuation、返回 Object），且方法内拼接了 "comment_id" 请求参数
 * ——这组特征在宿主内唯一。
 */
object CommentResolver {
    private const val COMMENT_REPLY_PATH = "/aweme/v1/comment/list/reply/"

    fun resolveReplyFetcher(): Method? =
        DexKitSupport.resolveCached("comment_reply") { dexKit ->
            dexKit
                .findClass {
                    matcher { usingStrings(listOf(COMMENT_REPLY_PATH), StringMatchType.Equals) }
                }.findMethod {
                    matcher {
                        // 挂起方法签名：(请求参数对象[混淆], Continuation) → Object
                        paramTypes(null, "kotlin.coroutines.Continuation")
                        returnType = "java.lang.Object"
                        usingStrings(listOf("comment_id"), StringMatchType.Equals)
                    }
                }.firstOrNull()
                ?.getMethodInstance(HostRuntime.requireClassLoader())
        }
}
