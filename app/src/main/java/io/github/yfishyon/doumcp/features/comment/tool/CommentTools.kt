package io.github.yfishyon.doumcp.features.comment.tool

import io.github.yfishyon.doumcp.core.addDouMcpTool
import io.github.yfishyon.doumcp.core.errorResult
import io.github.yfishyon.doumcp.core.intArg
import io.github.yfishyon.doumcp.core.stringArg
import io.github.yfishyon.doumcp.core.stringListArgOrNull
import io.github.yfishyon.doumcp.core.toolCall
import io.github.yfishyon.doumcp.features.comment.bridge.CommentBridge
import io.github.yfishyon.doumcp.features.comment.bridge.CommentDeleteBridge
import io.github.yfishyon.doumcp.features.comment.bridge.CommentStickerBridge
import io.github.yfishyon.doumcp.features.comment.bridge.CommentStickerSetBridge
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 评论相关 MCP 工具注册。 */
internal fun Server.registerCommentTools() {
    addDouMcpTool(
        name = "getComments",
        description =
            "获取一个视频的一级评论列表。参数 awemeId 为视频 ID；" +
                "cursor（可选，默认 0）分页游标；count（可选，默认 20）每页条数。" +
                "返回 JSON：comments 数组 + nextCursor + hasMore",
    ) { request ->
        val awemeId =
            request.arguments.stringArg("awemeId")
                ?: return@addDouMcpTool errorResult("缺少 awemeId 参数")
        val cursor = request.arguments.stringArg("cursor")?.toLongOrNull() ?: 0L
        val count = request.arguments.intArg("count", 20)
        val keyword = request.arguments.stringArg("keyword") ?: ""

        // 切到 IO 线程走网络（避免阻塞 MCP 调度线程）
        withContext(Dispatchers.IO) {
            toolCall { CommentBridge.getCommentsJson(awemeId, cursor, count, keyword) }
        }
    }

    addDouMcpTool(
        name = "getCommentReplies",
        description =
            "获取某条评论的楼中楼回复列表。参数 awemeId 为视频 ID；" +
                "commentId 为父评论 ID；cursor（可选，默认 0）分页游标；" +
                "count（可选，默认 20）每页条数。" +
                "返回 JSON：comments 数组（子评论，replyId 指向父评论）+ nextCursor + hasMore",
    ) { request ->
        val awemeId =
            request.arguments.stringArg("awemeId")
                ?: return@addDouMcpTool errorResult("缺少 awemeId 参数")
        val commentId =
            request.arguments.stringArg("commentId")
                ?: return@addDouMcpTool errorResult("缺少 commentId 参数")
        val cursor = request.arguments.stringArg("cursor")?.toLongOrNull() ?: 0L
        val count = request.arguments.intArg("count", 20)
        val keyword = request.arguments.stringArg("keyword") ?: ""

        withContext(Dispatchers.IO) {
            toolCall {
                CommentBridge.getCommentRepliesJson(
                    awemeId,
                    commentId,
                    cursor,
                    count,
                    keyword,
                )
            }
        }
    }

    addDouMcpTool(
        name = "getCommentStickers",
        description =
            "获取可用的评论表情包（动图，非云表情小黄脸）清单，与抖音评论表情面板同一份数据。" +
                "awemeId（可选）为作品 ID；count（可选，默认 30）拉取条数；" +
                "keyword（可选）按表情名过滤。" +
                "返回 JSON：stickers 数组，每项含 id（发评论时作 stickerId）、宽高、stickerType、" +
                "animateUrls（动图地址列表，可直接打开看）与 staticUrls（静图地址列表）；" +
                "表情包本身没有名字，items 里通常不带 name",
    ) { request ->
        val awemeId = request.arguments.stringArg("awemeId") ?: ""
        val count = request.arguments.intArg("count", 30)
        val keyword = request.arguments.stringArg("keyword") ?: ""

        withContext(Dispatchers.IO) {
            toolCall { CommentStickerBridge.getStickersJson(awemeId, count, keyword) }
        }
    }

    addDouMcpTool(
        name = "getStickerSets",
        description =
            "获取用户添加的表情集（表情包专辑），以及表情集里的表情。" +
                "不传 setId：列出表情集，每项含 id、name（表情集名）、description、stickerType。" +
                "传 setId：列出该表情集里的表情，每项含 id（发评论时作 stickerId）、name（表情的文字描述）、" +
                "宽高、animateUrls（动图地址列表，可直接打开看）、staticUrls。" +
                "表情集里的表情都带文字描述，适合按语义挑一个发评论",
    ) { request ->
        val setId = request.arguments.stringArg("setId") ?: ""

        withContext(Dispatchers.IO) {
            toolCall { CommentStickerSetBridge.getSetsJson(setId) }
        }
    }

    addDouMcpTool(
        name = "deleteComment",
        description =
            "批量删除自己发布的评论（逐条删除，单条失败不影响其余）。" +
                "awemeId 为评论所属作品 ID；cids 为评论 ID 列表（JSON 数组，也接受单个 ID 字符串）。需要登录。" +
                "返回 JSON：results 数组，每项含 id、ok，失败时带 error",
    ) { request ->
        val awemeId =
            request.arguments.stringArg("awemeId")
                ?: return@addDouMcpTool errorResult("缺少 awemeId 参数")
        val cids =
            request.arguments.stringListArgOrNull("cids")
                ?: request.arguments
                    .stringArg("cids")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { listOf(it) }
                ?: emptyList()

        withContext(Dispatchers.IO) {
            toolCall { CommentDeleteBridge.deleteCommentsJson(awemeId, cids) }
        }
    }
}
