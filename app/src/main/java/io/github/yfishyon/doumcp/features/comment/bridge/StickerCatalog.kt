package io.github.yfishyon.doumcp.features.comment.bridge

import io.github.yfishyon.doumcp.core.Reflect
import java.util.concurrent.ConcurrentHashMap

/**
 * 表情目录：把已经列给客户端的表情按 ID 记下来。
 *
 * 发布评论时只凭表情 ID 取对象——客户端先把清单列出来（推荐列表或表情集），
 * 再拿清单里的 ID 发评论，两者共用同一份目录。
 */
object StickerCatalog {
    private val byId = ConcurrentHashMap<String, Any>()

    /** 记下一批表情（无 ID 的跳过）。 */
    fun remember(emojis: List<Any?>) {
        for (emoji in emojis) {
            emoji ?: continue
            val id = Reflect.field(emoji, "id")?.toString()?.takeIf { it.isNotEmpty() } ?: continue
            byId[id] = emoji
        }
    }

    /** 按 ID 取表情。 */
    fun find(id: String): Any? = byId[id]
}
