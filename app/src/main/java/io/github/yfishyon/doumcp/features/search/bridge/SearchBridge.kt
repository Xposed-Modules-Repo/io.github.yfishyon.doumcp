package io.github.yfishyon.doumcp.features.search.bridge

import io.github.yfishyon.doumcp.core.Reflect
import io.github.yfishyon.doumcp.features.search.resolver.SearchResolver
import org.json.JSONObject

/**
 * 搜索桥：关键词搜索视频，返回视频摘要列表。
 *
 * 走抖音搜索 API 的通用请求方法（路径 + 参数 Map → Retrofit Call），
 * 响应是原始 JSON，直接解析——不经过抖音的混淆模型。
 */
object SearchBridge {
    /** 视频搜索接口路径。 */
    private const val SEARCH_ITEM_PATH = "/aweme/v2/search/general/single/"

    /** 账号搜索接口路径。 */
    private const val USER_SEARCH_PATH = "/aweme/v1/discover/search/"

    /** 账号搜索的 type 取值（该接口用 type 区分搜索对象：1=账号）。 */
    private const val USER_SEARCH_TYPE = "1"

    /** 结果条目的字段（服务端 JSON 协议字段，语义稳定）。 */
    private const val AWEME_INFO = "aweme_info"

    /**
     * 综合搜索（阻塞走网络，调用方负责后台线程）。
     *
     * @param keyword 搜索关键词
     * @param cursor 分页游标（首页传 0，翻页传上页返回的 nextCursor）
     * @param count 每页条数
     * @param type null=聚合（全部卡片类型）；指定值只留该类型（1=视频/图文作品）
     */
    fun searchJson(
        keyword: String,
        cursor: Int,
        count: Int,
        type: Int?,
        query: String = "",
    ): String {
        if (keyword.isBlank()) return errorJson("关键词为空")

        val api =
            SearchResolver.resolveApiProvider()
                ?: return errorJson("搜索 API 未定位")
        val genericCall =
            SearchResolver.resolveGenericCall()
                ?: return errorJson("搜索请求方法未定位")

        // 请求参数：hook 抖音自身搜索抓到的业务参数模板（设备/埋点类由网络层附加）
        val params = LinkedHashMap<String, String>()
        params["keyword"] = keyword
        params["cursor"] = cursor.toString()
        params["count"] = count.toString()
        params["search_source"] = "normal_search"
        params["init_search_source"] = "normal_search"
        params["search_scene"] = "douyin_search"
        params["nice_search_type"] = "general"
        params["filter_selected"] = """{"sort_type":"0","publish_time":"0","filter_duration":""}"""
        params["need_filter_settings"] = "1"
        params["enable_history"] = "1"
        params["hot_search"] = "0"
        params["dynamic_tab_combine"] = "1"
        params["start_session"] = "1"
        params["is_pull_refresh"] = "0"
        params["query_correct_type"] = "1"
        params["client_history_need_store"] = "1"
        params["search_request_id"] = newRequestId()

        val call =
            genericCall.invoke(api, SEARCH_ITEM_PATH, params)
                ?: return errorJson("请求对象创建失败")

        // 同步执行：Call.execute() → Response
        val response =
            runCatching { Reflect.invokeOrThrow(call, "execute") }
                .getOrElse { return errorJson("请求执行失败: ${it.message ?: it}") }
        val body =
            readBodyText(response)
                ?: return errorJson("响应体为空")
        return parseResponse(body, cursor, type, query)
    }

    /**
     * 用户搜索（阻塞走网络，调用方负责后台线程）。
     *
     * 走账号搜索接口，响应是 user_list 卡片，账号信息在卡片的动态原始数据里。
     *
     * @param keyword 搜索关键词
     * @param cursor 分页游标（首页传 0，翻页传上页返回的 nextCursor）
     * @param count 每页条数
     */
    fun searchUsersJson(
        keyword: String,
        cursor: Int,
        count: Int,
    ): String {
        if (keyword.isBlank()) return errorJson("关键词为空")

        val api =
            SearchResolver.resolveApiProvider()
                ?: return errorJson("搜索 API 未定位")
        val genericCall =
            SearchResolver.resolveGenericCall()
                ?: return errorJson("搜索请求方法未定位")

        val params = LinkedHashMap<String, String>()
        params["keyword"] = keyword
        params["type"] = USER_SEARCH_TYPE
        params["cursor"] = cursor.toString()
        params["count"] = count.toString()
        params["search_source"] = "normal_search"
        params["init_search_source"] = "normal_search"
        params["search_scene"] = "douyin_search"
        params["search_request_id"] = newRequestId()

        val call =
            genericCall.invoke(api, USER_SEARCH_PATH, params)
                ?: return errorJson("请求对象创建失败")
        val response =
            runCatching { Reflect.invokeOrThrow(call, "execute") }
                .getOrElse { return errorJson("请求执行失败: ${it.message ?: it}") }
        val body =
            readBodyText(response)
                ?: return errorJson("响应体为空")
        return parseUserResponse(body, cursor)
    }

    /** 解析账号搜索响应：user_list 卡片内动态原始数据里的 user_info 即账号对象。 */
    private fun parseUserResponse(
        bodyText: String,
        cursor: Int,
    ): String {
        val root = JSONObject(bodyText)
        val out = JSONObject()
        out.put("ok", true)
        out.put("nextCursor", root.optInt("cursor", cursor))
        out.put("hasMore", root.optInt("has_more", 0) == 1)

        val users = org.json.JSONArray()
        val list = root.optJSONArray("user_list") ?: org.json.JSONArray()
        for (i in 0 until list.length()) {
            val card = list.optJSONObject(i) ?: continue
            val rawData = card.optJSONObject("dynamic_patch")?.optString("raw_data") ?: continue
            val userInfo =
                runCatching { JSONObject(rawData).optJSONObject("user_info") }.getOrNull() ?: continue
            users.put(extractUserSummary(userInfo))
        }
        out.put("users", users)
        return out.toString()
    }

    /** 账号摘要：账号对象原样输出，滤掉空值（null / 空串 / 空数组 / 空对象），保留 0 与 false。 */
    private fun extractUserSummary(user: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = user.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (user.isNull(key)) continue
            val value = user.opt(key)
            if (value is String && value.isEmpty()) continue
            if (value is org.json.JSONArray && value.length() == 0) continue
            if (value is JSONObject && value.length() == 0) continue
            out.put(key, value)
        }
        return out
    }

    /** 生成搜索请求 ID（格式：yyyyMMddHHmmss + 16 位大写 hex）。 */
    private fun newRequestId(): String {
        val time = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).format(java.util.Date())
        val random =
            java.util.UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .uppercase()
                .take(16)
        return time + random
    }

    /** 从 Retrofit Response 取 body 文本（body 即原始 JSON 字符串）。 */
    private fun readBodyText(response: Any): String? = Reflect.field(response, "body")?.toString()

    /**
     * 解析搜索响应。
     *
     * 结构：business_data 数组，每项 {type, data:{type, aweme_info}}。
     *
     * @param filterType null=聚合（全部条目）；指定值时只留该类型（1=视频）
     */
    private fun parseResponse(
        bodyText: String,
        cursor: Int,
        filterType: Int?,
        query: String,
    ): String {
        val root = JSONObject(bodyText)
        val out = JSONObject()
        out.put("ok", true)
        out.put("nextCursor", root.optInt("cursor", cursor))
        out.put("hasMore", root.optInt("has_more", 0) == 1)

        val results = org.json.JSONArray()
        val cards = root.optJSONArray("business_data") ?: org.json.JSONArray()
        for (i in 0 until cards.length()) {
            val card = cards.optJSONObject(i) ?: continue
            val data = card.optJSONObject("data") ?: continue
            val type = data.optInt("type")
            if (filterType != null && type != filterType) continue
            val aweme = data.optJSONObject(AWEME_INFO)
            if (aweme != null) {
                results.put(extractVideoSummary(aweme, type))
            } else {
                // 非作品卡片（用户/话题/广告等）：输出类型与原始结构便于识别
                val item = JSONObject()
                item.put("type", type)
                item.put("raw", data)
                results.put(item)
            }
        }
        out.put("results", results)
        return out.toString()
    }

    /** 作品摘要：作者 / 文案 / 统计 / 封面 / 播放地址（图文笔记同样有这些字段）。 */
    private fun extractVideoSummary(
        aweme: JSONObject,
        type: Int,
    ): JSONObject {
        val out = JSONObject()
        out.put("type", type)
        out.put("awemeId", aweme.optString("aweme_id"))
        out.put("awemeType", aweme.optInt("aweme_type"))
        out.put("desc", aweme.optString("desc"))
        aweme.optJSONObject("author")?.let { author ->
            out.put("authorUid", author.optString("uid"))
            out.put("authorNickname", author.optString("nickname"))
            out.put("authorSecUid", author.optString("sec_uid"))
        }
        aweme.optJSONObject("statistics")?.let { stats ->
            val s = JSONObject()
            for (name in listOf("digg_count", "comment_count", "share_count", "play_count", "collect_count")) {
                s.put(name, stats.optLong(name))
            }
            out.put("statistics", s)
        }
        aweme.optJSONObject("video")?.let { video ->
            video.optJSONObject("cover")?.let { cover ->
                firstUrl(cover)?.let { out.put("cover", it) }
            }
            video.optJSONObject("play_addr")?.let { play ->
                firstUrl(play)?.let { out.put("playUrl", it) }
            }
        }
        // 图文笔记的图片列表
        aweme.optJSONArray("images")?.let { images ->
            val arr = org.json.JSONArray()
            for (i in 0 until images.length()) {
                images.optJSONObject(i)?.let { img ->
                    firstUrl(img.optJSONObject("url_list") ?: img)?.let { arr.put(it) }
                }
            }
            if (arr.length() > 0) out.put("images", arr)
        }
        out.put("createTime", formatTime(aweme.optLong("create_time") / 1000))
        return out
    }

    private fun firstUrl(urlModel: JSONObject): String? {
        val list = urlModel.optJSONArray("url_list") ?: return null
        return list.optString(0).takeIf { it.isNotEmpty() }
    }

    private fun formatTime(seconds: Long): String =
        java.text
            .SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(seconds * 1000))

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
