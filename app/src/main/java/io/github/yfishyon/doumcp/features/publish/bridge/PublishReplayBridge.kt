package io.github.yfishyon.doumcp.features.publish.bridge

import io.github.yfishyon.doumcp.core.ApiError
import io.github.yfishyon.doumcp.core.Reflect
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.LinkedHashMap

/**
 * 发布发送器：以宿主自身的发布链路把一份完整表单发送出去。
 *
 * 收口方法与真实发布走同一条通道，公共参数与请求签名由宿主网络层照常补齐。
 */
object PublishReplayBridge {
    /**
     * 发送一份完整表单。
     *
     * 收口方法为实例方法时现场构造收口对象，静态方法则直接调用。
     */
    fun sendJson(
        caller: Method,
        fields: LinkedHashMap<String, String>,
    ): String {
        caller.isAccessible = true
        val target =
            if (java.lang.reflect.Modifier
                    .isStatic(caller.modifiers)
            ) {
                null
            } else {
                caller.declaringClass
                    .getDeclaredConstructor()
                    .apply { isAccessible = true }
                    .newInstance()
            }
        val call = caller.invoke(target, fields) ?: return errorJson("发布调用返回为空")

        val response =
            try {
                Reflect.invokeOrThrow(call, "execute")
            } catch (t: Throwable) {
                return serverErrorJson(t)
            }
        val httpCode = (Reflect.invoke(response, "code") as? Number)?.toInt()
        val body = Reflect.invoke(response, "body") ?: return errorJson("发布无响应体（http=$httpCode）")

        // 成功必须同时满足：响应体带 status_code 且为 0、返回作品 id 非空
        val statusField = Reflect.field(body, "status_code")
        if (statusField == null) return errorJson("发布响应缺少 status_code（可能未真正发布）")
        val statusCode = (statusField as? Number)?.toInt() ?: -1
        if (statusCode != 0) {
            val message = Reflect.field(body, "status_msg") as? String ?: ""
            return errorJson("发布失败 status_code=$statusCode $message")
        }

        val aweme = Reflect.field(body, "aweme")
        // 作品 id 取 getter 或字段，再转字符串（不同版本字段类型不一致）
        val awemeId =
            aweme
                ?.let { holder -> (Reflect.getter(holder, "getAid") ?: Reflect.field(holder, "aid"))?.toString() }
                ?.takeIf { it.isNotBlank() }
                ?: ""
        if (awemeId.isEmpty()) return errorJson("发布未返回作品 id（status_code=0 但无 aweme）")
        val result =
            JSONObject()
                .put("ok", true)
                .put("aweme_id", awemeId)
        httpCode?.let { result.put("http_code", it) }
        (Reflect.field(body, "requestId") as? String)
            ?.takeIf { it.isNotEmpty() }
            ?.let { result.put("request_id", it) }
        return result.toString()
    }

    /** 服务端异常转错误信息：错误码 + 服务端人话提示（status_msg）。 */
    private fun serverErrorJson(t: Throwable): String = errorJson("发布失败：${ApiError.describe(t)}")

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
