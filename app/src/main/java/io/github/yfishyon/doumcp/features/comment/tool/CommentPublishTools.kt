package io.github.yfishyon.doumcp.features.comment.tool

import io.github.yfishyon.doumcp.core.addDouMcpTool
import io.github.yfishyon.doumcp.core.errorResult
import io.github.yfishyon.doumcp.core.stringArg
import io.github.yfishyon.doumcp.core.toolCall
import io.github.yfishyon.doumcp.features.comment.bridge.CommentImageUploader
import io.github.yfishyon.doumcp.features.comment.bridge.CommentPublishBridge
import io.github.yfishyon.doumcp.features.comment.bridge.CommentStickerBridge
import io.github.yfishyon.doumcp.features.comment.bridge.Mention
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** 评论发布相关 MCP 工具注册。 */
internal fun Server.registerCommentPublishTools() {
    addDouMcpTool(
        name = "postComment",
        description =
            "发表评论。awemeId 为作品 ID；text 为评论文本（云表情直接用 [表情名] 语法）。" +
                "正文、表情包、图片三者至少给一个（只发表情包/图片时 text 可省略）。" +
                "stickerId（可选）为表情包 ID，取 getCommentStickers 返回的 id。" +
                "imagePaths（可选）为本地图片绝对路径的 JSON 数组，最多 9 张，如 [\"/sdcard/DCIM/Camera/a.jpg\"]。" +
                "回复一级评论：传 replyCommentId（评论 cid）与 replyToUid（该评论作者 uid，取 getComments 返回的 uid）。" +
                "楼中楼（回复子评论）：replyCommentId 传根评论 cid、replyToReplyId 传子评论 cid、" +
                "replyToUid 取该子评论作者 uid（getCommentReplies 返回的 uid）；replyUid（可选）取被回复评论所回复的用户 uid。" +
                "mentions（可选）为 JSON 数组 [{\"uid\":\"..\",\"secUid\":\"..\",\"nickname\":\"..\"}]，" +
                "文本里写「@昵称」，对应片段会被标为 @提及。" +
                "entities（可选）为样式实体列表（Telegram 风格），形如 " +
                "[{\"type\":\"blue\",\"offset\":0,\"length\":6}]：type 用语义名，offset/length 是正文里的" +
                "区间起点与长度（按 UTF-16 计），其余键与宿主区间模型的字段同名、按字段类型透传" +
                "（如 userId / conversationId / searchText）。可用 type：blue（蓝字，渲染成蓝色不可点击）、" +
                "topic（话题：正文里在话题文字前面加 # 才会渲染成可点击的话题，不加 # 就只是纯蓝字、不可点击）、" +
                "search（搜索词）、ai / group / lottery_join / lottery_inquire（卡片）。" +
                "entities 与上面的 mentions 可以同时给，两者会合在一起发；@提及只走 mentions，不要用 entities。" +
                "卡片类由正文前缀决定渲染，正文里必须写对应前缀（[AI互动] / [群聊] / [抽奖] / [中奖]），" +
                "只给区间不显示；其中群聊卡还要求正文群名与 conversationId 真实匹配。" +
                "注意：返回 ok 只代表请求被接受，内容可能进入平台审核，可用 getComments 验证是否已展示",
    ) { request ->
        val awemeId =
            request.arguments.stringArg("awemeId")
                ?: return@addDouMcpTool errorResult("缺少 awemeId 参数")
        val text = request.arguments.stringArg("text") ?: ""
        val replyCommentId = request.arguments.stringArg("replyCommentId") ?: ""
        val replyToReplyId = request.arguments.stringArg("replyToReplyId") ?: ""
        val replyToUid = request.arguments.stringArg("replyToUid") ?: ""
        val replyUid = request.arguments.stringArg("replyUid") ?: ""
        val mentions = request.arguments.stringArg("mentions")
        val entities = request.arguments.stringArg("entities") ?: ""
        val stickerId = request.arguments.stringArg("stickerId") ?: ""
        val imagePaths = parseImagePaths(request.arguments.stringArg("imagePaths"))

        withContext(Dispatchers.IO) {
            val sticker =
                if (stickerId.isBlank()) {
                    null
                } else {
                    CommentStickerBridge.findSticker(awemeId, stickerId)
                        ?: return@withContext errorResult("表情包未找到（先用 getCommentStickers 取 id）")
                }
            val images =
                if (imagePaths.isEmpty()) {
                    emptyList()
                } else {
                    runCatching { CommentImageUploader.upload(imagePaths) }
                        .getOrElse { return@withContext errorResult("图片上传失败: ${it.cause ?: it}") }
                }
            toolCall {
                CommentPublishBridge.postCommentJson(
                    awemeId = awemeId,
                    text = text,
                    replyCommentId = replyCommentId,
                    replyToReplyId = replyToReplyId,
                    replyToUid = replyToUid,
                    replyUid = replyUid,
                    mentions = parseMentions(mentions),
                    entities = entities,
                    sticker = sticker,
                    images = images,
                )
            }
        }
    }
}

/** imagePaths 参数（JSON 数组字符串）→ 本地图片路径列表。 */
private fun parseImagePaths(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    val array = JSONArray(raw)
    val paths = ArrayList<String>(array.length())
    for (i in 0 until array.length()) {
        val path = array.optString(i)
        if (path.isNotBlank()) paths.add(path)
    }
    return paths
}

/** mentions 参数（JSON 数组字符串）→ 提及列表。 */
private fun parseMentions(raw: String?): List<Mention> {
    if (raw.isNullOrBlank()) return emptyList()
    val array = JSONArray(raw)
    val mentions = ArrayList<Mention>(array.length())
    for (i in 0 until array.length()) {
        val item = array.getJSONObject(i)
        mentions.add(
            Mention(
                uid = item.optString("uid"),
                secUid = item.optString("secUid"),
                nickname = item.optString("nickname"),
            ),
        )
    }
    return mentions
}
