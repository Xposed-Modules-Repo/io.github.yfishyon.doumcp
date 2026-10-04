package io.github.yfishyon.doumcp.features.video.bridge

import io.github.yfishyon.doumcp.core.ApiPrefix
import io.github.yfishyon.doumcp.core.HostRuntime
import io.github.yfishyon.doumcp.core.Reflect
import io.github.yfishyon.doumcp.features.account.bridge.AccountBridge
import io.github.yfishyon.doumcp.features.video.resolver.VideoResolver
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.Future

/**
 * 作品删除桥：按作品 ID 逐个删除自己发布的作品。
 *
 * 走宿主自己的删除接口（Retrofit 接口，端点写在注解里），接口实例由宿主的
 * Retrofit 服务工厂生成——与宿主自身删作品走同一条链路，公共参数与请求签名
 * 都由宿主的拦截器补齐。
 */
object VideoDeleteBridge {
    private const val RETROFIT_SERVICE = "com.ss.android.ugc.aweme.services.RetrofitService"
    private const val I_RETROFIT_SERVICE = "com.ss.android.ugc.aweme.framework.services.IRetrofitService"
    private const val I_RETROFIT = "com.ss.android.ugc.aweme.framework.services.IRetrofit"

    /**
     * 软删标记（is_soft_delete）：0 直接删除，1 进「最近删除」。
     * 该账号对软删有限制（传 1 时服务端返回不可软删），作品删除用 0。
     */
    private const val IS_SOFT_DELETE = 0

    /**
     * 批量删除作品（逐个调用，单条失败不影响其余）。
     *
     * @param awemeIds 作品 ID 列表
     */
    fun deleteAwemesJson(awemeIds: List<String>): String {
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录，无法删除作品")
        if (awemeIds.isEmpty()) return errorJson("awemeIds 不能为空")

        val method = VideoResolver.resolveDeleteMethod() ?: return errorJson("作品删除接口未定位")
        val api =
            runCatching { createApi(method.declaringClass) }
                .getOrElse { return errorJson("作品删除接口创建失败: ${it.cause ?: it}") }
                ?: return errorJson("作品删除接口创建失败")

        val results = JSONArray()
        for (awemeId in awemeIds) {
            results.put(deleteOne(method, api, awemeId))
        }
        return JSONObject().put("ok", true).put("results", results).toString()
    }

    /** 删除单个作品；任何异常都收敛为带 id 的失败项。 */
    private fun deleteOne(
        method: Method,
        api: Any,
        awemeId: String,
    ): JSONObject {
        val item = JSONObject().put("id", awemeId)
        return runCatching {
            val future =
                method.invoke(api, awemeId, IS_SOFT_DELETE)
                    ?: return@runCatching item.put("ok", false).put("error", "接口返回为空")
            val response = (future as Future<*>).get()
            val code = (Reflect.field(response, "status_code") as? Number)?.toInt() ?: 0
            if (code != 0) {
                item.put("ok", false).put("error", Reflect.field(response, "status_msg") as? String ?: "status_code=$code")
            } else {
                item.put("ok", true)
            }
        }.getOrElse { item.put("ok", false).put("error", (it.cause ?: it).toString()) }
    }

    /** 按删除接口所在类生成实例：Retrofit 服务入口 → 主站域名 Retrofit → 接口代理。 */
    private fun createApi(apiClass: Class<*>): Any? {
        val service =
            HostRuntime
                .hostClass(RETROFIT_SERVICE)
                .declaredMethods
                .firstOrNull {
                    Modifier.isStatic(it.modifiers) &&
                        it.parameterCount == 0 &&
                        it.returnType.name == I_RETROFIT_SERVICE
                }?.invoke(null)
                ?: return null
        val retrofit =
            service.javaClass
                .getMethod("createNewRetrofit", String::class.java)
                .invoke(service, ApiPrefix.get())
        return HostRuntime
            .hostClass(I_RETROFIT)
            .methods
            .first { it.name == "create" && it.parameterCount == 1 }
            .invoke(retrofit, apiClass)
    }

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
