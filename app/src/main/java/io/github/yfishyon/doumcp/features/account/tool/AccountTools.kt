package io.github.yfishyon.doumcp.features.account.tool

import io.github.yfishyon.doumcp.core.boolArg
import io.github.yfishyon.doumcp.core.errorResult
import io.github.yfishyon.doumcp.core.stringArg
import io.github.yfishyon.doumcp.core.toolCall
import io.github.yfishyon.doumcp.features.account.bridge.AccountBridge
import io.github.yfishyon.doumcp.features.account.bridge.ProfileBridge
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 账号域 MCP 工具注册：账号列表 / 切换 / 用户资料。 */
internal fun Server.registerAccountTools() {
    addTool(
        name = "getUserAccounts",
        description = "获取当前已登录的抖音账号列表（uid、是否当前账号）",
    ) { _ ->
        withContext(Dispatchers.IO) {
            toolCall { AccountBridge.getUserAccountsJson() }
        }
    }

    addTool(
        name = "switchAccount",
        description = "切换当前登录账号。参数 targetUid 从 getUserAccounts 的结果里取",
    ) { request ->
        val targetUid =
            request.arguments.stringArg("targetUid")
                ?: return@addTool errorResult("缺少 targetUid 参数")
        withContext(Dispatchers.IO) {
            toolCall { AccountBridge.switchAccount(targetUid) }
        }
    }

    addTool(
        name = "getUserProfile",
        description =
            "获取任意用户的资料（昵称、头像、粉丝数、简介等）。" +
                "参数 uidOrSecUid 支持 uid 或 secUid（自动识别）；查当前登录账号详情也用它；" +
                "full（可选，默认 false）为 true 时递归展开全部字段（含嵌套对象与数组）",
    ) { request ->
        val uidOrSecUid =
            request.arguments.stringArg("uidOrSecUid")
                ?: return@addTool errorResult("缺少 uidOrSecUid 参数")
        val full = request.arguments.boolArg("full", false)
        withContext(Dispatchers.IO) {
            toolCall { ProfileBridge.getUserProfileJson(uidOrSecUid, full) }
        }
    }
}
