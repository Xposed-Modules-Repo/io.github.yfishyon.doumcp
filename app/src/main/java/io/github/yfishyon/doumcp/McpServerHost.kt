package io.github.yfishyon.doumcp

import android.content.Context
import io.github.yfishyon.doumcp.core.DouToastHelper
import io.github.yfishyon.doumcp.core.ModLog
import io.github.yfishyon.doumcp.core.ToolRegistry
import io.github.yfishyon.doumcp.core.addDouMcpTool
import io.github.yfishyon.doumcp.features.account.tool.registerAccountTools
import io.github.yfishyon.doumcp.features.comment.tool.registerCommentPublishTools
import io.github.yfishyon.doumcp.features.comment.tool.registerCommentTools
import io.github.yfishyon.doumcp.features.database.bridge.DatabaseBridge
import io.github.yfishyon.doumcp.features.database.tool.registerDatabaseTools
import io.github.yfishyon.doumcp.features.devtool.tool.registerDevtoolRuntimeTools
import io.github.yfishyon.doumcp.features.devtool.tool.registerDevtoolSearchTools
import io.github.yfishyon.doumcp.features.devtool.tool.registerDevtoolWatchTools
import io.github.yfishyon.doumcp.features.im.tool.registerImTools
import io.github.yfishyon.doumcp.features.notice.tool.registerNoticeTools
import io.github.yfishyon.doumcp.features.publish.tool.registerPublishTools
import io.github.yfishyon.doumcp.features.search.tool.registerSearchTools
import io.github.yfishyon.doumcp.features.system.tool.registerSystemTools
import io.github.yfishyon.doumcp.features.video.tool.registerVideoTools
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * MCP 服务宿主：Ktor Streamable HTTP（仅监听 127.0.0.1）+ Bearer 鉴权，注册全部功能包工具。
 * 服务启动整体在自有后台线程执行，不阻塞宿主主线程。
 */
object McpServerHost {
    @Volatile
    private var started = false

    /** 启动序号：stop() 递增，使已通过端口预检但尚未落地的启动任务自我作废 */
    private var launchSeq = 0L

    private var engine: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    /** 启动任务队列（daemon，热重载时随 stop 一并关停） */
    private var launcher: java.util.concurrent.ExecutorService? = null

    /**
     * 启动服务（幂等）。启动流程投递到自有后台线程执行：
     * 调用点在宿主主线程的 hook 回调里，端口预检的重试等待不能发生在主线程。
     */
    fun start(port: Int) {
        synchronized(this) {
            if (started) return
            val exec =
                launcher?.takeIf { !it.isShutdown }
                    ?: java.util.concurrent.Executors
                        .newSingleThreadExecutor { runnable ->
                            Thread(runnable).apply { isDaemon = true }
                        }.also { launcher = it }
            val seq = ++launchSeq
            exec.execute { startBlocking(port, seq) }
        }
    }

    /** 停止服务并给排空时间（热重载前调用）；未执行的启动任务一并取消 */
    fun stop() {
        synchronized(this) {
            launchSeq++
            launcher?.shutdownNow()
            launcher = null
            runCatching { engine?.stop(1_000, 5_000) }
            engine = null
            started = false
        }
    }

    private fun startBlocking(
        port: Int,
        seq: Long,
    ) {
        // 端口预检含重试等待，放在锁外，避免阻塞并发的 start()/stop()
        var portFree = false
        for (attempt in 1..3) {
            portFree =
                runCatching {
                    java.net.ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1")).use { }
                }.isSuccess
            if (portFree) break
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return
            }
        }
        if (!portFree) {
            ModLog.e("MCP：端口 $port 持续被占用，放弃启动")
            return
        }

        synchronized(this) {
            if (started || seq != launchSeq) return
            try {
                startInternal(port)
                started = true
            } catch (t: Throwable) {
                ModLog.e("MCP：服务启动失败", t)
            }
        }
    }

    private fun startInternal(port: Int) {
        val context =
            io.github.yfishyon.doumcp.core.HostRuntime
                .requireContext()
        val token = ModulePrefs.getToken(context)

        val server =
            Server(
                serverInfo = Implementation(name = "doumcp", version = "1.1"),
                options =
                    ServerOptions(
                        capabilities =
                            ServerCapabilities(
                                tools = ServerCapabilities.Tools(listChanged = false),
                            ),
                    ),
            )

        registerTools(server)

        val serverEngine =
            embeddedServer(CIO, host = "127.0.0.1", port = port) {
                // 仅本机
                if (token.isNotEmpty()) {
                    intercept(ApplicationCallPipeline.Plugins) {
                        if (call.request.header("Authorization") != "Bearer $token") {
                            call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
                            finish()
                        }
                    }
                }
                routing {
                    // GET /api：列出全部工具名
                    get("/api") {
                        call.respondText(
                            org.json
                                .JSONObject()
                                .put("ok", true)
                                .put("tools", org.json.JSONArray(ToolRegistry.names()))
                                .toString(),
                            ContentType.Application.Json,
                        )
                    }
                    // POST /api/{工具名}：请求体即参数 JSON，返回工具结果
                    post("/api/{name}") {
                        val name = call.parameters["name"].orEmpty()
                        val body = call.receiveText()
                        val arguments =
                            runCatching {
                                if (body.isBlank()) null else Json.parseToJsonElement(body).jsonObject
                            }.getOrNull()
                        val result = ToolRegistry.call(name, arguments)
                        if (result == null) {
                            call.respondText(
                                org.json
                                    .JSONObject()
                                    .put("ok", false)
                                    .put("error", "未知工具: $name")
                                    .toString(),
                                ContentType.Application.Json,
                                HttpStatusCode.NotFound,
                            )
                        } else {
                            val text = result.content.filterIsInstance<TextContent>().joinToString("") { it.text }
                            call.respondText(text, ContentType.Application.Json)
                        }
                    }
                }
                mcpStreamableHttp { server }
            }
        serverEngine.start(wait = false)
        engine = serverEngine
        DouToastHelper.show(context, "抖M：服务已启动（端口 $port）")
        ModLog.i("MCP：服务已启动，端口 $port")
    }

    /** 注册工具：ping 为连通性检查（返回裸 pong，不走统一 JSON 结构），其余按功能包挂载 */
    private fun registerTools(server: Server) {
        server.addDouMcpTool(
            name = "ping",
            description = "测试抖M服务是否存活，返回 pong",
        ) { _ ->
            CallToolResult(content = listOf(TextContent(text = "pong")))
        }

        val registrations =
            mutableListOf<Pair<String, Server.() -> Unit>>(
                "account" to { registerAccountTools() },
                "video" to { registerVideoTools() },
                "im" to { registerImTools() },
                "comment" to { registerCommentTools() },
                "comment_publish" to { registerCommentPublishTools() },
                "publish" to { registerPublishTools() },
                "search" to { registerSearchTools() },
                "notice" to { registerNoticeTools() },
                "system" to { registerSystemTools() },
                "database" to { registerDatabaseTools() },
            )
        // 逆向调试工具只在 debug 包启用
        if (BuildConfig.DEBUG) {
            registrations += "devtool_search" to { registerDevtoolSearchTools() }
            registrations += "devtool_runtime" to { registerDevtoolRuntimeTools() }
            registrations += "devtool_watch" to { registerDevtoolWatchTools() }
        }
        for ((name, register) in registrations) {
            runCatching { register(server) }
                .onFailure { ModLog.e("MCP：$name 工具注册失败", it) }
        }
    }
}
