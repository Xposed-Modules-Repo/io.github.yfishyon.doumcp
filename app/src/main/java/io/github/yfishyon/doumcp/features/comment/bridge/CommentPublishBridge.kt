package io.github.yfishyon.doumcp.features.comment.bridge

import io.github.yfishyon.doumcp.core.Reflect
import io.github.yfishyon.doumcp.features.account.bridge.AccountBridge
import io.github.yfishyon.doumcp.features.search.resolver.SearchResolver
import org.json.JSONObject

/**
 * 评论发布桥。
 *
 * 端点：POST /aweme/v1/comment/publish/（单条发布；字段名来自 smali 的
 * Retrofit 注解逆向）。注意回复字段是 reply_id——multi_publish 批量端点
 * 用的才是 reply_to_comment_ids，别混。
 *
 * 走搜索 API 已验证的通用 POST 表单调用（动态路径 + 参数 Map → Retrofit Call），
 * 响应是原始 JSON。发布可能进平台审核：请求被接受 ≠ 已展示。
 */
object CommentPublishBridge {
    private const val COMMENT_PUBLISH_PATH = "/aweme/v1/comment/publish/"

    /**
     * 发表评论（阻塞走网络，调用方负责后台线程）。
     *
     * @param awemeId 作品 ID
     * @param text 评论文本（云表情直接用 [表情名] 语法）
     * @param replyToCommentId 母评论 ID（回复评论时传；空串表示直接评论）
     * @param replyToReplyId 子评论 ID（楼中楼回复子评论时传，需同时传 replyToCommentId 为其母评论）
     */
    fun postCommentJson(
        awemeId: String,
        text: String,
        replyToCommentId: String,
        replyToReplyId: String,
    ): String {
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) {
            return errorJson("未登录，无法发评论")
        }
        if (awemeId.isBlank() || text.isBlank()) return errorJson("awemeId 与 text 不能为空")

        val provider =
            SearchResolver.resolveApiProvider()
                ?: return errorJson("请求 API 未定位")
        val genericCall =
            SearchResolver.resolveGenericCall()
                ?: return errorJson("请求方法未定位")
        val api = provider.invoke(null) ?: return errorJson("API 实例为空")

        // 字段名来自 Retrofit 注解逆向（publish/ 端点 66 字段，取需要的）
        val form = LinkedHashMap<String, String>()
        form["aweme_id"] = awemeId
        form["text"] = text
        if (replyToCommentId.isNotEmpty()) form["reply_id"] = replyToCommentId
        if (replyToReplyId.isNotEmpty()) form["reply_to_reply_id"] = replyToReplyId
        form["text_extra"] = ""
        form["is_self_see"] = "0"
        form["channel_id"] = "0"
        form["publish_scene"] = "0"
        form["enter_from"] = "doumcp"

        val call =
            genericCall.invoke(api, COMMENT_PUBLISH_PATH, form)
                ?: return errorJson("请求对象创建失败")
        val response = Reflect.getter(call, "execute") ?: return errorJson("请求执行失败")
        // body 就是 JSON 字符串（Call<String>，String.toString 即内容）
        val rawBody = Reflect.field(response, "body")
        val body = rawBody?.toString() ?: return errorJson("响应体为空")

        val root =
            runCatching { JSONObject(body) }.getOrElse {
                return errorJson("响应解析失败: ${body.take(200)}")
            }
        val statusCode = root.optInt("status_code", -1)
        if (statusCode != 0) {
            return errorJson("发布失败 status_code=$statusCode msg=${root.optString("status_msg")}")
        }
        val comment = root.optJSONObject("comment")
        val createTime = comment?.optLong("create_time") ?: 0L
        val result =
            JSONObject()
                .put("ok", true)
                .put("cid", comment?.optString("cid") ?: "")
                .put("text", comment?.optString("text") ?: text)
        if (createTime > 0) {
            result.put(
                "createTime",
                java.text
                    .SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(createTime)),
            )
        }
        return result.toString()
    }

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
