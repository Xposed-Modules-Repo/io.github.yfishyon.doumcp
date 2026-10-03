package io.github.yfishyon.doumcp.features.video.bridge

import io.github.yfishyon.doumcp.core.Reflect
import io.github.yfishyon.doumcp.features.account.bridge.ProfileBridge
import io.github.yfishyon.doumcp.features.video.resolver.VideoResolver
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier

/**
 * 视频数据桥：给定视频 ID 拉取完整视频数据。
 *
 * 走抖音自己的视频详情接口（由 功能包 Resolver 定位）。
 *
 * 字段语义的获取方式（不依赖混淆字段名）：
 * - 视频模型有语义 getter（getAid / getStatistics / getVideo / getAuthorUid …），优先走 getter
 * - 统计等纯数值模型没有语义 getter，但它的 toString() 是抖音自己生成的、
 *   带语义名（如 `diggCount=123`）——直接解析该输出，零硬编码
 * - 视频媒体对象（播放地址/封面）内部字段名混淆，改按**字段类型**提取 URL 列表
 */
object VideoBridge {
    private const val VIDEO_URL_MODEL = "com.ss.android.ugc.aweme.feed.model.VideoUrlModel"
    private const val URL_MODEL = "com.ss.android.ugc.aweme.base.model.UrlModel"

    /**
     * 获取视频详情（阻塞走网络，调用方负责后台线程）。
     *
     * @param awemeId 视频（作品）ID
     * @param withAuthor 是否顺带拉取作者完整资料（视频模型本身只带作者 UID）
     */
    fun getVideoDetailJson(
        awemeId: String,
        withAuthor: Boolean,
    ): String {
        if (awemeId.isBlank()) return errorJson("视频 ID 为空")

        val fetchMethod =
            VideoResolver.resolveDetailMethod()
                ?: return errorJson("视频详情接口未定位（DexKit 未就绪或特征失效）")

        val aweme =
            runCatching { fetchMethod.invoke(null, awemeId, "") }
                .getOrElse { return errorJson("视频详情请求失败: ${it.cause ?: it}") }
                ?: return errorJson("视频不存在或已被删除")

        val json =
            runCatching { extract(aweme) }
                .getOrElse { return errorJson("视频数据解析失败: $it") }

        if (withAuthor) attachAuthor(json)
        return json.toString()
    }

    private fun extract(aweme: Any): JSONObject {
        val json = JSONObject()
        json.put("ok", true)
        json.put("awemeId", Reflect.getter(aweme, "getAid")?.toString() ?: Reflect.field(aweme, "aid")?.toString() ?: "")
        json.put("desc", Reflect.field(aweme, "desc")?.toString() ?: "")

        Reflect.getter(aweme, "getAuthorUid")?.let { json.put("authorUid", it.toString()) }
        Reflect.getter(aweme, "getSecAuthorUid")?.let { json.put("authorSecUid", it.toString()) }

        // 统计数据：解析模型 toString()（抖音生成的输出自带语义名）
        Reflect.getter(aweme, "getStatistics")?.let { stats ->
            json.put("statistics", parseModelToString(stats))
        }
        // 视频媒体：播放地址 / 封面按字段类型提取
        Reflect.getter(aweme, "getVideo")?.let { video ->
            json.put("video", extractVideo(video))
        }
        // 图文笔记（aweme_type=68）的图片列表：字段类型是预览图模型，按类型取 urlList
        Reflect.field(aweme, "images")?.let { images ->
            val arr = JSONArray()
            for (image in images as? List<*> ?: emptyList<Any>()) {
                image ?: continue
                // 预览图模型内 URL 列表字段名为 urlList
                (Reflect.field(image, "urlList") as? List<*>)?.firstOrNull()?.let { arr.put(it.toString()) }
            }
            if (arr.length() > 0) json.put("images", arr)
        }
        return json
    }

    /**
     * 提取媒体信息。
     *
     * 媒体对象内部字段名混淆，改按**字段类型**识别：
     * 播放地址是 [VIDEO_URL_MODEL]、封面是 [URL_MODEL]，两者都含 urlList。
     */
    private fun extractVideo(video: Any): JSONObject {
        val playUrls = JSONArray()
        val coverUrls = JSONArray()
        val seenPlay = mutableSetOf<String>()
        val seenCover = mutableSetOf<String>()

        var current: Class<*>? = video.javaClass
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                val typeName = field.type.name
                val isPlay = typeName == VIDEO_URL_MODEL
                val isCover = typeName == URL_MODEL
                if (!isPlay && !isCover) continue

                field.isAccessible = true
                val holder = runCatching { field.get(video) }.getOrNull() ?: continue
                val urls = readUrlList(holder) ?: continue
                val (target, seen) = if (isPlay) playUrls to seenPlay else coverUrls to seenCover
                for (url in urls) {
                    if (url.isNotEmpty() && seen.add(url)) target.put(url)
                }
            }
            current = current.superclass
        }

        val json = JSONObject()
        json.put("playUrls", playUrls)
        json.put("coverUrls", coverUrls)
        return json
    }

    /** 对象 urlList 字段（URL 列表）非空时返回其字符串列表。 */
    private fun readUrlList(target: Any): List<String>? {
        val value = Reflect.field(target, "urlList") as? List<*> ?: return null
        return value
            .mapNotNull { it?.toString() }
            .filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
    }

    /** 顺带拉取作者完整资料（视频模型只带作者 UID）。 */
    private fun attachAuthor(json: JSONObject) {
        val secUid = json.optString("authorSecUid").ifEmpty { json.optString("authorUid") }
        if (secUid.isEmpty()) return
        runCatching { ProfileBridge.getUserProfileJson(secUid) }
            .getOrNull()
            ?.let { profileText -> runCatching { json.put("author", JSONObject(profileText)) } }
    }

    /** 统计模型 → 语义字段 JSON（供其他桥复用）。 */
    fun parseStatistics(stats: Any): JSONObject = parseModelToString(stats)

    /**
     * 解析抖音模型 toString() 里的语义字段。
     * 形如 `AwemeStatistics{aid='x', commentCount=1, diggCount=2, ...}`。
     * 跳过含嵌套结构的段（值里带花括号），保证解析安全。
     */
    private fun parseModelToString(target: Any): JSONObject {
        val json = JSONObject()
        val text = target.toString()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return json

        for (segment in text.substring(start + 1, end).split(", ")) {
            val eq = segment.indexOf('=')
            if (eq <= 0) continue
            val key = segment.substring(0, eq).trim()
            val raw = segment.substring(eq + 1).trim().trim('\'')
            if (key.isEmpty() || key.contains('{') || raw.contains('{') || raw == "null") continue
            runCatching {
                raw.toLongOrNull()?.let { json.put(key, it) } ?: json.put(key, raw)
            }
        }
        return json
    }

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
