package io.github.yfishyon.doumcp.features.system.tool

import io.github.yfishyon.doumcp.core.stringArg
import io.github.yfishyon.doumcp.core.toolCall
import io.modelcontextprotocol.kotlin.sdk.server.Server
import org.json.JSONObject

/** 系统信息相关 MCP 工具注册。 */
internal fun Server.registerSystemTools() {
    addTool(
        name = "getCurrentTime",
        description =
            "获取抖音设备上的当前时间（本地时区）。" +
                "format（可选）：iso=yyyy-MM-dd HH:mm:ss（默认）、ms=毫秒时间戳、s=秒时间戳，" +
                "或任意 SimpleDateFormat 模式（如 yyyy-MM-dd、HH:mm:ss）。" +
                "抖音数据库的时间字段大多是毫秒时间戳（13 位数字）；个别字段（如火花 ext）是秒（10 位）。" +
                "换算时先看位数判断单位",
    ) { request ->
        val format = request.arguments.stringArg("format") ?: "iso"
        toolCall {
            val now = System.currentTimeMillis()
            val zone =
                java.util.TimeZone
                    .getDefault()
                    .id
            val iso =
                java.text
                    .SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        java.util.Locale.getDefault(),
                    ).format(java.util.Date(now))
            val millis =
                when (format) {
                    "ms" -> {
                        now.toString()
                    }

                    "s" -> {
                        (now / 1000).toString()
                    }

                    "iso" -> {
                        iso
                    }

                    else -> {
                        runCatching {
                            java.text
                                .SimpleDateFormat(format, java.util.Locale.getDefault())
                                .format(java.util.Date(now))
                        }.getOrElse { return@toolCall timeError("不支持的格式: $format") }
                    }
                }
            JSONObject()
                .put("ok", true)
                .put("time", millis)
                .put("iso", iso)
                .put("millis", now)
                .put("timezone", zone)
                .toString()
        }
    }
}

private fun timeError(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
