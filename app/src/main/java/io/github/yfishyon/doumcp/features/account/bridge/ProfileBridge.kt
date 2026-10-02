package io.github.yfishyon.doumcp.features.account.bridge

import io.github.yfishyon.doumcp.core.ApiPrefix
import io.github.yfishyon.doumcp.features.account.resolver.AccountResolver
import org.json.JSONObject
import java.util.Collections
import java.util.LinkedHashMap

/**
 * 用户资料桥：uid / secUid → 抖音自己的用户资料对象。
 *
 * 走 profile 接口，陌生用户也能查；当前登录账号的详情也用同一接口获取。
 * 请求 URL 前缀从抖音常量类反射取得（接口域名的全局常量字段），
 * 请求方法由 [AccountResolver.resolveProfileFetchMethod] 用 DexKit 定位。
 */
object ProfileBridge {
    private const val PROFILE_API_PATH = "/aweme/v1/user/profile/other/"

    private val profileCache =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, Any>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, Any>): Boolean = size > 128
            },
        )

    private fun buildUrl(uidOrSecUid: String): String = "${ApiPrefix.get()}$PROFILE_API_PATH?sec_user_id=$uidOrSecUid"

    /**
     * 获取用户资料（阻塞走网络，调用方负责后台线程）。
     *
     * @param uidOrSecUid 用户 ID 或 secUid（自动识别）
     * @return JSON 字符串
     */
    fun getUserProfileJson(uidOrSecUid: String): String {
        if (uidOrSecUid.isBlank()) return errorJson("参数为空")
        profileCache[uidOrSecUid]?.let { return toJson(it, uidOrSecUid) }

        val fetchMethod =
            AccountResolver.resolveProfileFetchMethod()
                ?: return errorJson("资料接口未定位（DexKit 未就绪或特征失效）")

        val url = buildUrl(uidOrSecUid)
        val user =
            runCatching {
                fetchMethod.invoke(null, url, false, "")
            }.getOrElse { return errorJson("资料请求失败: ${it.cause ?: it}") }

        if (user == null) return errorJson("资料请求失败（用户可能不存在）")
        profileCache[uidOrSecUid] = user
        return toJson(user, uidOrSecUid)
    }

    private fun toJson(
        user: Any,
        queriedId: String,
    ): String {
        val json = JSONObject()
        json.put("ok", true)
        var currentClass: Class<*>? = user.javaClass
        while (currentClass != null && currentClass != Any::class.java) {
            for (field in currentClass.declaredFields) {
                if (java.lang.reflect.Modifier
                        .isStatic(field.modifiers)
                ) {
                    continue
                }
                field.isAccessible = true
                when (val value = runCatching { field.get(user) }.getOrNull()) {
                    null -> Unit
                    is String -> if (value.isNotEmpty()) json.put(field.name, value)
                    is Boolean, is Int, is Long, is Double, is Float -> json.put(field.name, value)
                    else -> Unit
                }
            }
            currentClass = currentClass.superclass
        }
        json.put("queriedId", queriedId)
        return json.toString()
    }

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
