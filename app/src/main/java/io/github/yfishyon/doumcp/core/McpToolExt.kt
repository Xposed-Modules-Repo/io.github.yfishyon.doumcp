package io.github.yfishyon.doumcp.core

import io.github.yfishyon.doumcp.core.errorResult
import io.github.yfishyon.doumcp.core.textResult
import io.github.yfishyon.doumcp.core.toolCall
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonPrimitive

/** 取字符串参数（缺失返回 null） */
internal fun Map<String, kotlinx.serialization.json.JsonElement>?.stringArg(name: String): String? =
    (this?.get(name) as? JsonPrimitive)?.content

/** 取整型参数（缺失或非法返回默认值） */
internal fun Map<String, kotlinx.serialization.json.JsonElement>?.intArg(
    name: String,
    default: Int,
): Int = (this?.get(name) as? JsonPrimitive)?.content?.toIntOrNull() ?: default

/** 取布尔参数（缺失或非法返回默认值） */
internal fun Map<String, kotlinx.serialization.json.JsonElement>?.boolArgOrNull(name: String): Boolean? =
    (this?.get(name) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()

internal fun Map<String, kotlinx.serialization.json.JsonElement>?.boolArg(
    name: String,
    default: Boolean,
): Boolean = (this?.get(name) as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: default

/** 包装文本结果 */
internal fun textResult(text: String): CallToolResult = CallToolResult(content = listOf(TextContent(text = text)))

/** 包装统一失败结果（{"ok":false,"error":描述}） */
internal fun errorResult(message: String): CallToolResult =
    textResult(
        org.json
            .JSONObject()
            .put("ok", false)
            .put("error", message)
            .toString(),
    )

/**
 * 包装一次能力调用，统一异常与输出校验。
 *
 * 异常统一转错误结果；传出的内容必须校验为合法 JSON（协议约定返回 {ok:...} 结构），否则拦截为错误。
 */
internal fun toolCall(block: () -> String): CallToolResult =
    runCatching(block).fold(
        onSuccess = { output ->
            val valid =
                runCatching {
                    org.json.JSONObject(output).has("ok")
                }.getOrDefault(false)
            if (valid) {
                textResult(output)
            } else {
                errorResult("内部输出未通过 JSON 校验（缺少 ok 字段）")
            }
        },
        onFailure = { errorResult(it.toString()) },
    )
