package io.github.yfishyon.doumcp.core

import org.json.JSONObject

/**
 * 从宿主网络异常中提取服务端返回的可读信息。
 *
 * 宿主的接口异常类型未混淆，人话提示（服务端 status_msg）在 mErrorMsg / mPrompt /
 * mResponse 字段上；异常 message 本身只有「error_code = N, log_id」形态的机器串。
 */
object ApiError {
    /** 错误码（异常 message 里的 error_code）；取不到返回 null。 */
    fun code(t: Throwable): String? = Regex("error_code\\s*=\\s*(-?\\d+)").find(t.message ?: "")?.groupValues?.get(1)

    /**
     * 服务端的人话提示：优先 mErrorMsg（即 status_msg），其次 mPrompt，
     * 最后从原始响应 JSON 里解析 status_msg；都没有返回 null。
     */
    fun message(t: Throwable): String? {
        val direct =
            (Reflect.field(t, "mErrorMsg") as? String)?.takeIf { it.isNotBlank() }
                ?: (Reflect.field(t, "mPrompt") as? String)?.takeIf { it.isNotBlank() }
        if (direct != null) return direct
        val response = Reflect.field(t, "mResponse") ?: return null
        val text = response as? String ?: response.toString()
        return runCatching { JSONObject(text).optString("status_msg") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /** 组合描述：「（error_code=N）提示」，两段都取不到时回退异常文本。 */
    fun describe(t: Throwable): String {
        val code = code(t)
        val message = message(t)
        return when {
            code != null && message != null -> "error_code=$code $message"
            code != null -> "error_code=$code"
            message != null -> message
            else -> t.message ?: t.toString()
        }
    }
}
