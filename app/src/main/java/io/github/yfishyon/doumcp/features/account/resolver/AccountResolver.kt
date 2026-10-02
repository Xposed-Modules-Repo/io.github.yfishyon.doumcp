package io.github.yfishyon.doumcp.features.account.resolver

import io.github.yfishyon.doumcp.core.DexKitSupport
import io.github.yfishyon.doumcp.core.HostRuntime
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method

/**
 * 账号域的定位器。
 *
 * - 账号切换：账号用户服务实现上"参数为 (用户资料对象, 目标用户ID)、返回 void"，
 *   方法内带切换流程专属标记字符串
 * - 用户资料请求：持有 profile 接口路径（全等锚点）的类里，
 *   静态方法 (String url, boolean, String) → 用户模型；端点同时接受 user_id 与 sec_user_id
 */
object AccountResolver {
    private const val ACCOUNT_USER_SERVICE = "com.ss.android.ugc.aweme.account.service.IAccountUserService"
    private const val PROFILE_USER = "com.ss.android.ugc.aweme.profile.model.User"
    private const val PROFILE_API_PATH = "/aweme/v1/user/profile/other/"

    fun resolveSwitchMethod(): Method? =
        DexKitSupport.resolveCached("account_switch") { dexKit ->
            dexKit
                .findClass {
                    matcher { addInterface(ACCOUNT_USER_SERVICE) }
                }.findMethod {
                    matcher {
                        paramTypes(PROFILE_USER, "java.lang.String")
                        returnType = "void"
                        usingStrings(listOf("is_from_switch_account"), StringMatchType.Equals)
                    }
                }.singleOrNull()
                ?.getMethodInstance(HostRuntime.requireClassLoader())
        }

    fun resolveProfileFetchMethod(): Method? =
        DexKitSupport.resolveCached("profile_fetch") { dexKit ->
            dexKit
                .findClass {
                    matcher { usingStrings(listOf(PROFILE_API_PATH), StringMatchType.Equals) }
                }.findMethod {
                    matcher {
                        paramTypes("java.lang.String", "boolean", "java.lang.String")
                        returnType = PROFILE_USER
                    }
                }.firstOrNull()
                ?.getMethodInstance(HostRuntime.requireClassLoader())
        }
}
