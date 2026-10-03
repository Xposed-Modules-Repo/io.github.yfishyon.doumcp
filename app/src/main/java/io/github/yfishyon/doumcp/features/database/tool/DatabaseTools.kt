package io.github.yfishyon.doumcp.features.database.tool

import io.github.yfishyon.doumcp.core.addDouMcpTool
import io.github.yfishyon.doumcp.core.errorResult
import io.github.yfishyon.doumcp.core.intArg
import io.github.yfishyon.doumcp.core.stringArg
import io.github.yfishyon.doumcp.core.toolCall
import io.github.yfishyon.doumcp.features.database.bridge.DatabaseBridge
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 数据库相关 MCP 工具注册：直接 SQL 查询/执行抖音本地库。 */
internal fun Server.registerDatabaseTools() {
    addDouMcpTool(
        name = "listDatabases",
        description =
            "列出抖音本地可查询的 SQLite 数据库文件名。" +
                "拿名字传给 listTables / query-database / execute-database-statement；" +
                "encrypted_ 前缀的加密库（IM 消息等）会自动解密，任意账号的库都可查",
    ) { _ ->
        withContext(Dispatchers.IO) {
            toolCall { DatabaseBridge.listDatabasesJson() }
        }
    }

    addDouMcpTool(
        name = "listTables",
        description =
            "列出指定数据库里的全部表和视图（名字、类型、建表语句）。" +
                "参数 database 为 listDatabases 返回的文件名",
    ) { request ->
        val database =
            request.arguments.stringArg("database")
                ?: return@addDouMcpTool errorResult("缺少 database 参数")
        withContext(Dispatchers.IO) {
            toolCall { DatabaseBridge.listTablesJson(database) }
        }
    }

    addDouMcpTool(
        name = "queryDatabase",
        description =
            "对指定数据库执行只读查询（SELECT / PRAGMA / EXPLAIN / WITH）。" +
                "参数 database 为 listDatabases 返回的文件名（encrypted_ 前缀自动解密）、" +
                "sql 为查询语句（建议加 LIMIT）、maxRows（可选，默认 100，最大 200）。" +
                "输出：首行「N row(s):」，后续每行「列=值, 列=值」（空值显示「列=」）；无结果输出「0 rows.」",
    ) { request ->
        val database =
            request.arguments.stringArg("database")
                ?: return@addDouMcpTool errorResult("缺少 database 参数")
        val sql =
            request.arguments.stringArg("sql")
                ?: return@addDouMcpTool errorResult("缺少 sql 参数")
        val maxRows = request.arguments.intArg("maxRows", 100)
        withContext(Dispatchers.IO) {
            toolCall { DatabaseBridge.queryJson(database, sql, maxRows) }
        }
    }

    addDouMcpTool(
        name = "executeDatabaseStatement",
        description =
            "对指定数据库执行写语句（INSERT / UPDATE / DELETE / DDL），后果自负。" +
                "参数 database 为 listDatabases 返回的文件名（encrypted_ 前缀自动解密）、sql 为语句。" +
                "成功输出「Statement executed.」，不返回影响行数——要验证效果再用 queryDatabase 查询确认；" +
                "失败输出「Statement failed: <描述> (code N, errno N): <详情>」",
    ) { request ->
        val database =
            request.arguments.stringArg("database")
                ?: return@addDouMcpTool errorResult("缺少 database 参数")
        val sql =
            request.arguments.stringArg("sql")
                ?: return@addDouMcpTool errorResult("缺少 sql 参数")
        withContext(Dispatchers.IO) {
            toolCall { DatabaseBridge.executeJson(database, sql) }
        }
    }
}
