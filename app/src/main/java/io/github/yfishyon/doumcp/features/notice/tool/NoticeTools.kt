package io.github.yfishyon.doumcp.features.notice.tool

import io.github.yfishyon.doumcp.core.addDouMcpTool
import io.github.yfishyon.doumcp.core.errorResult
import io.github.yfishyon.doumcp.core.intArg
import io.github.yfishyon.doumcp.core.stringArg
import io.github.yfishyon.doumcp.core.toolCall
import io.github.yfishyon.doumcp.features.notice.bridge.NoticeBridge
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 互动通知（消息页的通知中心）相关 MCP 工具注册。 */
internal fun Server.registerNoticeTools() {
    addDouMcpTool(
        name = "getNotices",
        description =
            "获取通知中心的消息列表（抖音消息页-互动消息的各子页签）。参数 type 为分组：" +
                "at=别人@我的信息、comment=收到的评论、like=赞、fans=粉丝/关注、recommend=推荐。" +
                "cursor（可选，默认 0 取最新）翻页游标，传上页返回的 nextCursor；" +
                "count（可选，默认 20）每页条数。需要登录。返回 items 数组" +
                "（每条含 time/read/content/author/awemeId 等，字段以该条通知实际携带的为准）、" +
                "hasMore、nextCursor",
    ) { request ->
        val type =
            request.arguments.stringArg("type")
                ?: return@addDouMcpTool errorResult("缺少 type 参数")
        val cursor = request.arguments.stringArg("cursor")?.toLongOrNull() ?: 0L
        val count = request.arguments.intArg("count", 20)

        withContext(Dispatchers.IO) {
            toolCall { NoticeBridge.getNoticesJson(type, cursor, count) }
        }
    }

    addDouMcpTool(
        name = "getSentComments",
        description =
            "获取我在抖音发出过的评论列表（消息页-互动消息-发出的评论）。" +
                "cursor（可选，默认 0 取最新）翻页游标，传上页返回的 nextCursor；" +
                "count（可选，默认 20）每页条数。需要登录。返回 items 数组" +
                "（每条含 time/text/diggCount/awemeId/cid 等）、hasMore、nextCursor",
    ) { request ->
        val cursor = request.arguments.stringArg("cursor")?.toLongOrNull() ?: 0L
        val count = request.arguments.intArg("count", 20)

        withContext(Dispatchers.IO) {
            toolCall { NoticeBridge.getSentCommentsJson(cursor, count) }
        }
    }
}
