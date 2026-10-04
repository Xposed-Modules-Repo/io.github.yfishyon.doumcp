package io.github.yfishyon.doumcp.features.publish.bridge

import io.github.yfishyon.doumcp.core.Reflect
import io.github.yfishyon.doumcp.features.account.bridge.AccountBridge
import io.github.yfishyon.doumcp.features.search.resolver.SearchResolver
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 音乐搜索桥：按关键词搜配乐，返回可用于发布的音乐 ID。
 *
 * 走抖音搜索的通用请求方法（路径 + 参数 Map → Retrofit Call），命中音乐搜索接口，
 * 响应是原始 JSON，直接解析——不经过抖音的混淆模型。
 */
object PublishMusicBridge {
    /** 音乐搜索接口路径 */
    private const val MUSIC_SEARCH_PATH = "/aweme/v1/music/search/"

    fun searchJson(
        keyword: String,
        cursor: Int,
        count: Int,
    ): String {
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录")
        if (keyword.isBlank()) return errorJson("关键词为空")

        val api = SearchResolver.resolveApiProvider() ?: return errorJson("搜索 API 未定位")
        val genericCall = SearchResolver.resolveGenericCall() ?: return errorJson("搜索请求方法未定位")

        val params = LinkedHashMap<String, String>()
        params["keyword"] = keyword
        params["cursor"] = cursor.toString()
        params["count"] = count.toString()
        params["search_source"] = "normal_search"
        params["search_scene"] = "douyin_search"
        params["is_pull_refresh"] = "0"
        params["query_correct_type"] = "1"
        params["search_request_id"] = newRequestId()

        val call =
            genericCall.invoke(api, MUSIC_SEARCH_PATH, params)
                ?: return errorJson("请求对象创建失败")
        val response =
            runCatching { Reflect.invokeOrThrow(call, "execute") }
                .getOrElse { return errorJson("请求执行失败: ${it.message ?: it}") }
        val body = Reflect.field(response, "body")?.toString() ?: return errorJson("响应体为空")
        return parseResponse(body, cursor)
    }

    /** 解析音乐搜索响应：顶层 music_info_list 数组，每项的 music 字段即音乐对象。 */
    private fun parseResponse(
        bodyText: String,
        cursor: Int,
    ): String {
        val root = JSONObject(bodyText)
        val out = JSONObject()
        out.put("ok", true)
        out.put("nextCursor", root.optInt("cursor", cursor))
        out.put("hasMore", root.optInt("has_more", 0) == 1)

        val musics = JSONArray()
        val list = root.optJSONArray("music_info_list") ?: JSONArray()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val music = item.optJSONObject("music") ?: item
            musics.put(summarize(music))
        }
        out.put("musics", musics)
        return out.toString()
    }

    /** 音乐摘要：ID / 标题 / 作者 / 时长 / 是否原创 / 试听地址。 */
    private fun summarize(music: JSONObject): JSONObject {
        val out = JSONObject()
        out.put("id", music.optString("id_str").ifEmpty { music.optString("id") })
        out.put("title", music.optString("title"))
        out.put("author", music.optString("author"))
        out.put("duration", music.optLong("duration"))
        out.put("isOriginal", music.optBoolean("is_original"))
        (music.optString("owner_nickname").takeIf { it.isNotEmpty() })?.let { out.put("ownerNickname", it) }
        firstUrl(music.optJSONObject("play_url"))?.let { out.put("playUrl", it) }
        firstUrl(music.optJSONObject("cover_large") ?: music.optJSONObject("cover_medium"))
            ?.let { out.put("cover", it) }
        return out
    }

    private fun firstUrl(urlModel: JSONObject?): String? = urlModel?.optJSONArray("url_list")?.optString(0)?.takeIf { it.isNotEmpty() }

    /** 生成搜索请求 ID（格式：yyyyMMddHHmmss + 16 位大写 hex）。 */
    private fun newRequestId(): String {
        val time = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        val random =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .uppercase()
                .take(16)
        return time + random
    }

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
