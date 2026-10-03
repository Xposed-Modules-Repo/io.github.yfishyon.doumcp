package io.github.yfishyon.doumcp.features.comment.bridge

import io.github.yfishyon.doumcp.core.ModLog
import io.github.yfishyon.doumcp.core.Reflect
import io.github.yfishyon.doumcp.features.account.bridge.AccountBridge
import io.github.yfishyon.doumcp.features.comment.resolver.CommentStickerResolver
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 评论表情包（动图）桥：拉取宿主表情面板同一份清单，并按 ID 取出表情对象。
 *
 * 清单只经宿主自己的表情资源接口取（来源标记固定为评论区），响应模型里
 * 清单容器字段名混淆，按**字段类型**取列表；表情模型本身未混淆，字段即协议名。
 * 推荐列表里的表情没有名字，带名字的表情来自表情集（见 表情集桥）。
 */
object CommentStickerBridge {
    /** 清单来源标记：评论区（宿主按该标记区分评论区与私信） */
    private const val SOURCE_COMMENT = "comment"

    private const val TASK = "bolts.Task"

    private const val DEFAULT_COUNT = 30
    private const val MAX_COUNT = 100
    private const val TIMEOUT_MS = 15_000L
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    @Volatile
    private var cache: List<Any>? = null

    @Volatile
    private var cachedAt = 0L

    /**
     * 拉取评论表情包清单（阻塞走网络，调用方负责后台线程）。
     *
     * @param awemeId 作品 ID（可空；宿主用它做推荐分组，空则不传）
     * @param count 拉取条数
     * @param keyword 按表情名过滤（不区分大小写，为空返回全部）
     */
    fun getStickersJson(
        awemeId: String,
        count: Int,
        keyword: String,
    ): String {
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录")

        val emojis = fetch(awemeId, count.coerceIn(1, MAX_COUNT)) ?: return errorJson("表情包清单获取失败")

        val array = JSONArray()
        val kw = keyword.trim()
        emojis.forEachIndexed { index, emoji ->
            emoji ?: return@forEachIndexed
            val item = stickerJson(emoji, index)
            if (kw.isNotEmpty() && !item.optString("name").contains(kw, ignoreCase = true)) return@forEachIndexed
            array.put(item)
        }
        return JSONObject()
            .put("ok", true)
            .put("count", array.length())
            .put("stickers", array)
            .toString()
    }

    /**
     * 按表情 ID 取表情对象（发布用）。
     *
     * 先查表情目录（客户端列过的清单都在里面）；未命中时顺带刷一次推荐列表
     * 再查一次，覆盖"客户端只列过推荐列表、服务端换了一批"的情况。
     *
     * @param awemeId 作品 ID（透传给清单接口）
     * @param stickerId 表情 ID（清单返回的 id）
     */
    fun findSticker(
        awemeId: String,
        stickerId: String,
    ): Any? {
        if (stickerId.isBlank()) return null
        StickerCatalog.find(stickerId)?.let { return it }
        fetch(awemeId, MAX_COUNT)
        return StickerCatalog.find(stickerId)
    }

    private fun fetch(
        awemeId: String,
        count: Int,
    ): List<Any>? {
        cache?.takeIf { System.currentTimeMillis() - cachedAt < CACHE_TTL_MS }?.let { return it }

        val method = CommentStickerResolver.resolveListMethod()
        if (method == null) {
            ModLog.e("表情包清单：清单方法未定位")
            return null
        }
        val api = CommentStickerResolver.api(method) ?: return null

        val list =
            runCatching {
                val task =
                    method.invoke(api, null, 0, count, SOURCE_COMMENT, awemeId.ifBlank { null })
                        ?: return null
                val taskClass = Class.forName(TASK, false, task.javaClass.classLoader)

                // 限时等待用签名定位：宿主侧被改名，唯一 2 参（long, TimeUnit）方法是它
                val await =
                    taskClass.methods.firstOrNull {
                        it.parameterCount == 2 &&
                            it.parameterTypes[0] == Long::class.javaPrimitiveType &&
                            it.parameterTypes[1] == TimeUnit::class.java
                    } ?: throw IllegalStateException("等待方法未定位")
                if (await.invoke(task, TIMEOUT_MS, TimeUnit.MILLISECONDS) as? Boolean != true) {
                    ModLog.e("表情包清单：请求超时")
                    return null
                }
                // 请求失败时结果为空
                extractList(taskClass.getMethod("getResult").invoke(task))
                    ?: return null.also { ModLog.e("表情包清单：请求失败") }
            }.getOrElse {
                ModLog.e("表情包清单：请求异常", it.cause ?: it)
                return null
            } ?: return null

        StickerCatalog.remember(list)
        cache = list
        cachedAt = System.currentTimeMillis()
        return list
    }

    /** 从响应模型取出表情列表（清单容器字段名混淆，按字段类型取列表字段）。 */
    private fun extractList(response: Any?): List<Any>? {
        val holder = response?.let { Reflect.field(it, "trendingEmojis") } ?: return null
        val field =
            Reflect.fieldsOf(holder.javaClass).values.firstOrNull {
                List::class.java.isAssignableFrom(it.type)
            } ?: return null
        return runCatching { field.get(holder) as? List<*> }.getOrNull()?.filterNotNull()
    }

    /** 单个表情 → JSON（id、名字、宽高、类型、动图与静图地址）。 */
    internal fun stickerJson(
        emoji: Any,
        index: Int,
    ): JSONObject {
        val json =
            JSONObject()
                .put("index", index)
                .put("id", Reflect.field(emoji, "id")?.toString() ?: "")
        (Reflect.field(emoji, "displayName") as? String)
            ?.takeIf { it.isNotEmpty() }
            ?.let { json.put("name", it) }
        (Reflect.field(emoji, "width") as? Number)?.toInt()?.let { json.put("width", it) }
        (Reflect.field(emoji, "height") as? Number)?.toInt()?.let { json.put("height", it) }
        (Reflect.field(emoji, "stickerType") as? Number)?.toInt()?.let { json.put("stickerType", it) }
        mediaUrls(emoji, "animateUrl")?.let { json.put("animateUrls", it) }
        mediaUrls(emoji, "staticUrl")?.let { json.put("staticUrls", it) }
        return json
    }

    /** 表情某个媒体字段的完整地址列表（可直接打开）。 */
    private fun mediaUrls(
        emoji: Any,
        field: String,
    ): JSONArray? {
        val media = Reflect.field(emoji, field) ?: return null
        val urls = Reflect.field(media, "urlList") as? List<*> ?: return null
        val array = JSONArray()
        for (url in urls) {
            val text = url?.toString()
            if (!text.isNullOrEmpty()) array.put(text)
        }
        return array.takeIf { it.length() > 0 }
    }

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
