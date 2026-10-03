package io.github.yfishyon.doumcp.features.video.tool

import io.github.yfishyon.doumcp.core.addDouMcpTool
import io.github.yfishyon.doumcp.core.boolArg
import io.github.yfishyon.doumcp.core.errorResult
import io.github.yfishyon.doumcp.core.intArg
import io.github.yfishyon.doumcp.core.stringArg
import io.github.yfishyon.doumcp.core.toolCall
import io.github.yfishyon.doumcp.features.video.bridge.UserAwemeBridge
import io.github.yfishyon.doumcp.features.video.bridge.VideoBridge
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 视频/作品相关 MCP 工具注册。 */
internal fun Server.registerVideoTools() {
    addDouMcpTool(
        name = "getAwemeDetail",
        description =
            "解析一个作品（视频/图文笔记）的完整数据：标题、类型、统计数据（播放/点赞/评论/分享/收藏）、" +
                "发布时间、图集图片、视频地址等。参数 awemeId 为作品 ID；" +
                "withAuthor（可选，默认 false）是否附带作者资料",
    ) { request ->
        val awemeId =
            request.arguments.stringArg("awemeId")
                ?: return@addDouMcpTool errorResult("缺少 awemeId 参数")
        val withAuthor = request.arguments.boolArg("withAuthor", false)

        withContext(Dispatchers.IO) {
            toolCall { VideoBridge.getVideoDetailJson(awemeId, withAuthor) }
        }
    }

    addDouMcpTool(
        name = "getUserAwemes",
        description =
            "获取某个用户发布的作品列表（分页）。参数 uidOrSecUid 支持 uid 或 secUid；" +
                "cursor（可选，默认 0）翻页游标，传上页返回的 nextCursor；count（可选，默认 10）每页条数。" +
                "需要登录。也可用它查询自己刚发布的作品",
    ) { request ->
        val uidOrSecUid =
            request.arguments.stringArg("uidOrSecUid")
                ?: return@addDouMcpTool errorResult("缺少 uidOrSecUid 参数")
        val cursor = request.arguments.stringArg("cursor")?.toLongOrNull() ?: 0L
        val count = request.arguments.intArg("count", 10)
        val keyword = request.arguments.stringArg("keyword") ?: ""

        withContext(Dispatchers.IO) {
            toolCall {
                UserAwemeBridge.getUserAwemesJson(uidOrSecUid, cursor, count, keyword)
            }
        }
    }
}
