package io.github.yfishyon.doumcp.features.comment.tool

import io.github.yfishyon.doumcp.core.errorResult
import io.github.yfishyon.doumcp.core.intArg
import io.github.yfishyon.doumcp.core.stringArg
import io.github.yfishyon.doumcp.core.toolCall
import io.github.yfishyon.doumcp.features.comment.bridge.CommentBridge
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 评论相关 MCP 工具注册。 */
internal fun Server.registerCommentTools() {
    addTool(
        name = "getComments",
        description =
            "获取一个视频的一级评论列表。参数 awemeId 为视频 ID；" +
                "cursor（可选，默认 0）分页游标；count（可选，默认 20）每页条数。" +
                "返回 JSON：comments 数组 + nextCursor + hasMore",
    ) { request ->
        val awemeId =
            request.arguments.stringArg("awemeId")
                ?: return@addTool errorResult("缺少 awemeId 参数")
        val cursor = request.arguments.stringArg("cursor")?.toLongOrNull() ?: 0L
        val count = request.arguments.intArg("count", 20)
        val keyword = request.arguments.stringArg("keyword") ?: ""

        // 切到 IO 线程走网络（避免阻塞 MCP 调度线程）
        withContext(Dispatchers.IO) {
            toolCall { CommentBridge.getCommentsJson(awemeId, cursor, count, keyword) }
        }
    }

    addTool(
        name = "getCommentReplies",
        description =
            "获取某条评论的楼中楼回复列表。参数 awemeId 为视频 ID；" +
                "commentId 为父评论 ID；cursor（可选，默认 0）分页游标；" +
                "count（可选，默认 20）每页条数。" +
                "返回 JSON：comments 数组（子评论，replyId 指向父评论）+ nextCursor + hasMore",
    ) { request ->
        val awemeId =
            request.arguments.stringArg("awemeId")
                ?: return@addTool errorResult("缺少 awemeId 参数")
        val commentId =
            request.arguments.stringArg("commentId")
                ?: return@addTool errorResult("缺少 commentId 参数")
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
}
