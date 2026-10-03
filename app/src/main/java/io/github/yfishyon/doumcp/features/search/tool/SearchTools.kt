package io.github.yfishyon.doumcp.features.search.tool

import io.github.yfishyon.doumcp.core.addDouMcpTool
import io.github.yfishyon.doumcp.core.errorResult
import io.github.yfishyon.doumcp.core.intArg
import io.github.yfishyon.doumcp.core.stringArg
import io.github.yfishyon.doumcp.core.toolCall
import io.github.yfishyon.doumcp.features.search.bridge.SearchBridge
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 搜索相关 MCP 工具注册。 */
internal fun Server.registerSearchTools() {
    addDouMcpTool(
        name = "search",
        description =
            "抖音综合搜索，返回内容卡片列表（作品/音乐/话题等，不含账号）。" +
                "参数 keyword 为关键词；type（可选）过滤类型，1=视频/图文作品，不传返回聚合全部；" +
                "cursor（可选，默认 0）分页游标，传上页返回的 nextCursor；count（可选，默认 10）每页条数",
    ) { request ->
        val keyword =
            request.arguments.stringArg("keyword")
                ?: return@addDouMcpTool errorResult("缺少 keyword 参数")
        val cursor = request.arguments.intArg("cursor", 0)
        val count = request.arguments.intArg("count", 10)
        val type = request.arguments.intArg("type", -1).takeIf { it >= 0 }
        val query = request.arguments.stringArg("query") ?: ""
        withContext(Dispatchers.IO) {
            toolCall { SearchBridge.searchJson(keyword, cursor, count, type, query) }
        }
    }

    addDouMcpTool(
        name = "searchUsers",
        description =
            "抖音账号搜索，按关键词返回账号列表（uid/昵称/抖音号/粉丝数/头像/认证）。" +
                "参数 keyword 为关键词；cursor（可选，默认 0）分页游标，传上页返回的 nextCursor；" +
                "count（可选，默认 10）每页条数",
    ) { request ->
        val keyword =
            request.arguments.stringArg("keyword")
                ?: return@addDouMcpTool errorResult("缺少 keyword 参数")
        val cursor = request.arguments.intArg("cursor", 0)
        val count = request.arguments.intArg("count", 10)
        withContext(Dispatchers.IO) {
            toolCall { SearchBridge.searchUsersJson(keyword, cursor, count) }
        }
    }
}
