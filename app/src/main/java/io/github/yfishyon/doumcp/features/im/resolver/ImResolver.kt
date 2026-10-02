package io.github.yfishyon.doumcp.features.im.resolver

import io.github.yfishyon.doumcp.core.DexKitSupport
import io.github.yfishyon.doumcp.core.HostRuntime
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * IM 域的定位器：IM 用户资料请求。
 *
 * 用户仓库类（未混淆）内返回 IM 用户模型的 2 参方法（第二参数是 Kotlin 回调）；
 * 仓库单例是类型指向自身的静态字段。
 */
object ImResolver {
    private const val IM_USER_REPOSITORY = "com.ss.android.ugc.aweme.im.sdk.core.IMUserRepository"
    private const val IM_USER = "com.ss.android.ugc.aweme.im.service.model.IMUser"

    @Volatile
    private var repoInstance: Any? = null

    /** 返回 (请求方法, 仓库实例)；无参仓库时实例为 null。 */
    fun resolveUserFetch(): Pair<Method, Any?>? =
        DexKitSupport
            .resolveCached("im_user_fetch") { dexKit ->
                dexKit
                    .findClass {
                        matcher { className(IM_USER_REPOSITORY) }
                    }.findMethod {
                        matcher {
                            returnType = IM_USER
                            paramTypes(null, "kotlin.jvm.functions.Function1")
                        }
                    }.firstOrNull()
                    ?.getMethodInstance(HostRuntime.requireClassLoader())
            }?.let { method ->
                if (repoInstance == null) {
                    val repoClass = method.declaringClass
                    val singletonField =
                        repoClass.declaredFields.firstOrNull {
                            Modifier.isStatic(it.modifiers) && it.type == repoClass
                        } ?: return null
                    singletonField.isAccessible = true
                    repoInstance = singletonField.get(null)
                }
                method to repoInstance
            }
}
