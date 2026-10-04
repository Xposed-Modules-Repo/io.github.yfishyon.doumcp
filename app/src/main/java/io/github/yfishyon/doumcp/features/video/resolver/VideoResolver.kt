package io.github.yfishyon.doumcp.features.video.resolver

import io.github.yfishyon.doumcp.core.DexKitSupport
import io.github.yfishyon.doumcp.core.HostRuntime
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method

/**
 * 视频域的定位器。
 *
 * 持有视频详情接口路径（全等锚点）的类里，静态方法 (作品 ID, 来源标记) → 视频模型。
 * 来源标记是页面来源，无页面上下文时与宿主自己内部调用一样传空串。
 */
object VideoResolver {
    private const val VIDEO_DETAIL_PATH = "/aweme/v1/aweme/detail/"
    private const val LISTENABLE_FUTURE = "com.google.common.util.concurrent.ListenableFuture"

    fun resolveDetailMethod(): Method? =
        DexKitSupport.resolveCached("video_detail") { dexKit ->
            dexKit
                .findClass {
                    matcher { usingStrings(listOf(VIDEO_DETAIL_PATH), StringMatchType.Equals) }
                }.findMethod {
                    matcher {
                        returnType = DexKitSupport.FEED_AWEME
                        paramTypes("java.lang.String", "java.lang.String")
                    }
                }.firstOrNull()
                ?.getMethodInstance(HostRuntime.requireClassLoader())
        }

    /**
     * 作品删除方法。
     *
     * 端点写在注解里、检索不到，按方法名与形状定位：(作品 ID, 软删标记) →
     * ListenableFuture 的 deleteItem 在全表唯一。
     */
    fun resolveDeleteMethod(): Method? =
        DexKitSupport.resolveCached("video_delete") { dexKit ->
            dexKit
                .findMethod {
                    matcher {
                        name("deleteItem", StringMatchType.Equals)
                        returnType(LISTENABLE_FUTURE, StringMatchType.Equals)
                        paramTypes("java.lang.String", "java.lang.Integer")
                    }
                }.firstOrNull()
                ?.getMethodInstance(HostRuntime.requireClassLoader())
        }
}
