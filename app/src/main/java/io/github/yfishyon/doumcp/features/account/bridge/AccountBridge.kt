package io.github.yfishyon.doumcp.features.account.bridge

import io.github.yfishyon.doumcp.core.HostRuntime
import io.github.yfishyon.doumcp.features.account.resolver.AccountResolver
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/**
 * 抖音账号能力桥：已登录账号列表 + 切换账号。
 *
 * 账号列表只输出 uid（详细信息统一走 [ProfileBridge] 的资料接口）。
 * 多账号管理器与用户服务的类名、方法名（getAllAccounts / userService /
 * getCurUserId）均未混淆，直接调用；切换方法由 功能包 Resolver 定位。
 */
object AccountBridge {
    private const val MULTI_ACCOUNTS_MANAGER = "com.ss.android.ugc.aweme.account.business.multiaccounts.MultiAccountsDataManager"
    private const val ACCOUNT_PROXY_SERVICE = "com.ss.android.ugc.aweme.account.AccountProxyService"

    fun getUserAccountsJson(): String {
        val managerClass = Class.forName(MULTI_ACCOUNTS_MANAGER, false, HostRuntime.requireClassLoader())

        val getAllAccounts =
            managerClass.declaredMethods.firstOrNull {
                it.parameterCount == 0 && it.returnType == List::class.java &&
                    Modifier.isStatic(it.modifiers) && it.name == "getAllAccounts"
            } ?: return errorJson("账号列表方法未找到")
        val uidList = getAllAccounts.invoke(null) as List<*>

        val currentUid = getCurrentUserId()
        val result = JSONArray()
        for (uidAny in uidList) {
            val uid = uidAny?.toString() ?: continue
            result.put(JSONObject().put("uid", uid).put("isCurrent", uid == currentUid))
        }
        return JSONObject().put("ok", true).put("accounts", result).toString()
    }

    fun getCurrentUserId(): String? {
        val serviceClass = Class.forName(ACCOUNT_PROXY_SERVICE, false, HostRuntime.requireClassLoader())
        val userService = serviceClass.getDeclaredMethod("userService")
        val userServiceInstance =
            userService.invoke(null)
                ?: return null
        return userServiceInstance.javaClass.getMethod("getCurUserId").invoke(userServiceInstance) as? String
    }

    /**
     * 切换到指定账号。
     *
     * @param targetUid 目标账号的用户 ID（从账号列表里取）
     */
    fun switchAccount(targetUid: String): String {
        val switchMethod =
            AccountResolver.resolveSwitchMethod()
                ?: return errorJson("切换方法定位失败（需更新 DexKit 特征）")

        return try {
            val serviceInstance = resolveUserServiceInstance()
            switchMethod.invoke(serviceInstance, null, targetUid)
            JSONObject().put("ok", true).put("targetUid", targetUid).toString()
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            errorJson("切换账号失败: $cause")
        }
    }

    private fun resolveUserServiceInstance(): Any? {
        val serviceClass = Class.forName(ACCOUNT_PROXY_SERVICE, false, HostRuntime.requireClassLoader())
        return serviceClass.getDeclaredMethod("userService").invoke(null)
    }

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
