package io.github.yfishyon.doumcp.features.comment.tool

import io.github.yfishyon.doumcp.core.addDouMcpTool
import io.github.yfishyon.doumcp.core.errorResult
import io.github.yfishyon.doumcp.core.stringArg
import io.github.yfishyon.doumcp.core.toolCall
import io.github.yfishyon.doumcp.features.comment.bridge.CommentPublishBridge
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 评论发布相关 MCP 工具注册。 */
internal fun Server.registerCommentPublishTools() {
    addDouMcpTool(
        name = "postComment",
        description =
            "发表评论。参数 awemeId 为作品 ID；text 为评论文本（纯文字；云表情直接用 [表情名] 语法）；" +
                "replyCommentId（可选）传母评论 ID 即回复该评论；replyToReplyId（可选）传子评论 ID 即楼中楼回复该子评论（需同时传 replyCommentId 为其母评论）。" +
                "注意：返回 ok 只代表请求被接受，内容可能进入平台审核，可用 getComments 验证是否已展示",
    ) { request ->
        val awemeId =
            request.arguments.stringArg("awemeId")
                ?: return@addDouMcpTool errorResult("缺少 awemeId 参数")
        val text =
            request.arguments.stringArg("text")
                ?: return@addDouMcpTool errorResult("缺少 text 参数")
        val replyCommentId = request.arguments.stringArg("replyCommentId") ?: ""
        val replyToReplyId = request.arguments.stringArg("replyToReplyId") ?: ""
        withContext(Dispatchers.IO) {
            toolCall {
                CommentPublishBridge.postCommentJson(
                    awemeId,
                    text,
                    replyCommentId,
                    replyToReplyId,
                )
            }
        }
    }
}
