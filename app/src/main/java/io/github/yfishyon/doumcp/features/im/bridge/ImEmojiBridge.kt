package io.github.yfishyon.doumcp.features.im.bridge

import io.github.yfishyon.doumcp.core.HostRuntime
import io.github.yfishyon.doumcp.core.ModLog
import io.github.yfishyon.doumcp.core.Reflect
import io.github.yfishyon.doumcp.features.account.bridge.AccountBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * 云表情（小表情）目录桥。
 *
 * 数据源是宿主的小表情资源助手单例（未混淆类，含未混淆的加载接口）：
 * - 常驻目录：`getAllEmojiCount()` 取总数，`loadBaseEmoji(start, count)` 分页取全量，
 *   元素是表情模型（getText()=[显示名]）
 * - 限时活动表情：助手上唯一"无参返回列表"的方法（按签名定位，避免写混淆名），
 *   只包含当前处于活动时间窗口内的限定表情；窗口外的活动表情宿主已过滤，取不到
 */
object ImEmojiBridge {
    private const val EMOJI_HELPER = "com.ss.android.ugc.aweme.emoji.smallemoji.utils.EmojiResHelper"

    /**
     * 拉取表情目录。
     *
     * @param keyword 按表情名过滤（匹配 [名] 或 名，不区分大小写，为空返回全部）
     * @param includeHidden 是否附带限时活动表情（默认 false，只返回常驻目录）
     */
    fun getEmojisJson(
        keyword: String?,
        includeHidden: Boolean,
    ): String {
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录")

        return runCatching {
            val helperClass = Class.forName(EMOJI_HELPER, false, HostRuntime.requireClassLoader())
            val companion = helperClass.getDeclaredField("Companion").get(null)
            val helper =
                companion.javaClass.methods
                    .firstOrNull { it.parameterCount == 0 && it.returnType == helperClass }
                    ?.invoke(companion)
                    ?: return errorJson("表情资源助手获取失败（IM 未初始化）")

            val count =
                runCatching {
                    (
                        helperClass.methods
                            .first { it.name == "getAllEmojiCount" && it.parameterCount == 0 }
                            .invoke(helper) as Number
                    ).toInt()
                }.getOrDefault(0)
            if (count <= 0) return errorJson("表情目录为空")

            val load =
                helperClass.methods.firstOrNull {
                    it.name == "loadBaseEmoji" && it.parameterCount == 2
                } ?: return errorJson("表情加载方法未找到")

            @Suppress("UNCHECKED_CAST")
            val all =
                load.invoke(helper, 0, count) as? List<*>
                    ?: return errorJson("表情目录读取失败")

            val kw = keyword?.trim()?.removeSurrounding("[", "]") ?: ""

            fun textOf(e: Any): String = runCatching { Reflect.getter(e, "getText")?.toString() ?: "" }.getOrDefault("")

            val emojis = JSONArray()
            for (e in all) {
                e ?: continue
                val text = textOf(e)
                if (text.isEmpty()) continue // 尾部补位的空模型
                val name = text.removeSurrounding("[", "]")
                if (kw.isNotEmpty() &&
                    !name.contains(kw, ignoreCase = true) &&
                    !text.contains(kw, ignoreCase = true)
                ) {
                    continue
                }
                emojis.put(emojiJson(text))
            }

            val out =
                JSONObject()
                    .put("ok", true)
                    .put("count", emojis.length())
                    .put("emojis", emojis)

            // 限时活动表情（仅活动时间窗口内的可见；按"无参返回列表"签名定位）
            if (includeHidden) {
                val limitedList =
                    helperClass.methods
                        .singleOrNull { it.parameterCount == 0 && it.returnType == List::class.java }
                        ?.invoke(helper) as? List<*>
                if (limitedList != null) {
                    val limited = JSONArray()
                    for (e in limitedList) {
                        e ?: continue
                        val text = textOf(e)
                        if (text.isEmpty()) continue
                        val name = text.removeSurrounding("[", "]")
                        if (kw.isNotEmpty() &&
                            !name.contains(kw, ignoreCase = true) &&
                            !text.contains(kw, ignoreCase = true)
                        ) {
                            continue
                        }
                        limited.put(emojiJson(text))
                    }
                    out.put("limitedCount", limited.length())
                    out.put("limited", limited)
                }
            }
            out.toString()
        }.getOrElse {
            ModLog.e("getEmojis 失败", it)
            errorJson("表情目录读取异常: ${it.cause ?: it}")
        }
    }

    private fun emojiJson(text: String): JSONObject =
        JSONObject()
            .put("text", text)
            .put("name", text.removeSurrounding("[", "]"))

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
