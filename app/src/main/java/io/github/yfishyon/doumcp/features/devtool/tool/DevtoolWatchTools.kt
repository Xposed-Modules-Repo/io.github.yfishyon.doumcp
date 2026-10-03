package io.github.yfishyon.doumcp.features.devtool.tool

import io.github.yfishyon.doumcp.core.addDouMcpTool
import io.github.yfishyon.doumcp.core.boolArg
import io.github.yfishyon.doumcp.core.errorResult
import io.github.yfishyon.doumcp.core.intArg
import io.github.yfishyon.doumcp.core.intArgOrNull
import io.github.yfishyon.doumcp.core.jsonArg
import io.github.yfishyon.doumcp.core.stringArg
import io.github.yfishyon.doumcp.core.stringListArgOrNull
import io.github.yfishyon.doumcp.core.toolCall
import io.github.yfishyon.doumcp.features.devtool.bridge.WatchBridge
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * devtool 动态 hook 工具注册：给宿主方法挂观察/篡改 hook、读调用日志、反内联。
 *
 * 会真实改变宿主行为，后果自负；仅在 debug 包可用。
 */
internal fun Server.registerDevtoolWatchTools() {
    addDouMcpTool(
        name = "watchMethod",
        description =
            "给宿主方法挂 hook，记录每次调用的参数、返回值/异常与耗时（可选调用栈）。" +
                "参数：class、method（kind=method 时必填）、paramTypes（可选，参数类型名数组，用于重载消歧）、" +
                "kind（method(默认)/ctor/clinit；clinit 需在该类初始化前挂，已初始化则永不触发）、" +
                "captureStack（默认 false，抓调用栈——最贵，热方法慎开）、stackDepth（默认 20）、" +
                "maxCalls（环形缓冲上限，默认 200，超出丢弃并计数）、" +
                "condition（可选 {\"argIndex\":0,\"equals\":\"值\"}，不满足则只透传、不记录不篡改）、" +
                "exceptionMode（可选，protective(默认)/passthrough/default；passthrough 时 hook 自身异常直接抛出，便于定位）、" +
                "priority（可选，hook 链优先级，默认 50，越大越先执行）、" +
                "argOverrides（可选 {\"0\": 值}，按参数下标替换入参）、returnOverride（可选，给出则直接返回该值、不调原方法）、" +
                "thisOverride（可选，替换实例方法的 this，走 Chain.proceedWith）。" +
                "对同一目标再次 watchMethod 会按同 id 原子替换（不摘再挂）。返回 watchId",
    ) { request ->
        val clazz = request.arguments.stringArg("class") ?: return@addDouMcpTool errorResult("缺少 class 参数")
        val hasReturnOverride = request.arguments?.containsKey("returnOverride") == true
        withContext(Dispatchers.IO) {
            toolCall {
                WatchBridge.watchJson(
                    clazz = clazz,
                    method = request.arguments.stringArg("method"),
                    paramTypes = request.arguments.stringListArgOrNull("paramTypes"),
                    kind = request.arguments.stringArg("kind") ?: "method",
                    captureStack = request.arguments.boolArg("captureStack", false),
                    stackDepth = request.arguments.intArg("stackDepth", 20),
                    maxCalls = request.arguments.intArg("maxCalls", 200),
                    exceptionMode = request.arguments.stringArg("exceptionMode"),
                    priority = request.arguments.intArgOrNull("priority"),
                    condition = request.arguments.jsonArg("condition") as? JsonObject,
                    argOverrides = request.arguments.jsonArg("argOverrides") as? JsonObject,
                    returnOverride = request.arguments.jsonArg("returnOverride"),
                    hasReturnOverride = hasReturnOverride,
                    thisOverride = request.arguments.jsonArg("thisOverride"),
                    hasThisOverride = request.arguments?.containsKey("thisOverride") == true,
                )
            }
        }
    }

    addDouMcpTool(
        name = "unwatchMethod",
        description = "摘除 hook（在工具线程同步摘除，不在 hook 回调栈内）。参数：watchId，或 class + method 定位",
    ) { request ->
        withContext(Dispatchers.IO) {
            toolCall {
                WatchBridge.unwatchJson(
                    watchId = request.arguments.stringArg("watchId"),
                    clazz = request.arguments.stringArg("class"),
                    method = request.arguments.stringArg("method"),
                )
            }
        }
    }

    addDouMcpTool(
        name = "listWatches",
        description = "列出当前所有 hook：目标、命中次数、丢弃计数、是否抓栈、是否改参数/返回值",
    ) { _ ->
        withContext(Dispatchers.IO) {
            toolCall { WatchBridge.listJson() }
        }
    }

    addDouMcpTool(
        name = "traceLog",
        description =
            "增量读取某个 hook 的调用记录（参数、返回值/异常、耗时、可选调用栈）。" +
                "参数：watchId、cursor（上次返回的 nextCursor，首次传 0；增量读取）、limit（可选；不传返回全部未读记录）" + "。热方法可能累积很多记录，建议开 condition 或传 limit",
    ) { request ->
        val watchId = request.arguments.stringArg("watchId") ?: return@addDouMcpTool errorResult("缺少 watchId 参数")
        withContext(Dispatchers.IO) {
            toolCall {
                WatchBridge.traceLogJson(
                    watchId = watchId,
                    cursor = request.arguments.stringArg("cursor")?.toLongOrNull() ?: 0L,
                    limit = request.arguments.intArgOrNull("limit"),
                )
            }
        }
    }

    addDouMcpTool(
        name = "deoptimize",
        description =
            "反内联，保证已挂的 hook 一定命中（短方法被内联时回调不触发）。" +
                "按 DexKit 的调用关系找出该方法的全部调用方并逐个反内联（A 内联了 B 时要反内联 A）。" +
                "参数：class、method、paramTypes（可选，重载消歧）",
    ) { request ->
        val clazz = request.arguments.stringArg("class") ?: return@addDouMcpTool errorResult("缺少 class 参数")
        val method = request.arguments.stringArg("method") ?: return@addDouMcpTool errorResult("缺少 method 参数")
        withContext(Dispatchers.IO) {
            toolCall {
                WatchBridge.deoptimizeJson(
                    clazz = clazz,
                    method = method,
                    paramTypes = request.arguments.stringListArgOrNull("paramTypes"),
                )
            }
        }
    }
}
