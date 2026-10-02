package io.github.yfishyon.doumcp.features.database.bridge

import android.database.Cursor
import io.github.yfishyon.doumcp.core.HostRuntime
import io.github.yfishyon.doumcp.core.ModLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Method

/**
 * 数据库桥接：直接以 SQL 查询/执行抖音本地 SQLite 数据库。
 *
 * - 普通库：系统 SQLite API 直接打开
 * - 加密库（encrypted_ 前缀，IM 系全部加密）：用宿主自带的 WCDB 打开——
 *   密钥是固定算法（由账号 uid 拼接），uid 从文件名提取，任意账号的库都能解
 *
 * 输出为扁平 KV 文本：查询返回「N row(s):」+ 每行「列=值, 列=值」；
 * 执行返回「Statement executed.」，失败返回「Statement failed: …」。
 */
object DatabaseBridge {
    /** 结果行数上限。 */
    private const val MAX_ROWS = 200

    /** 允许的查询语句前缀。 */
    private val QUERY_PREFIXES = listOf("SELECT", "PRAGMA", "EXPLAIN", "WITH")

    /** 单字段值截断长度（超长二进制/文本截断显示）。 */
    private const val VALUE_MAX_LEN = 2000

    /** 加密库文件名前缀。 */
    private const val ENCRYPTED_PREFIX = "encrypted_"

    // 加密库每次打开都要跑一轮密钥派生，连接缓存后仅首次慢
    private val connections = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun databasesDir(): File = File(HostRuntime.requireContext().getApplicationInfo().dataDir, "databases")

    /** 数据库文件名白名单校验：只允许纯文件名，防路径穿越。 */
    private fun safeDbFile(name: String): File? {
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        return File(databasesDir(), name)
    }

    /** 枚举可查询的数据库文件（过滤 journal/wal/shm/material 附属文件）。 */
    fun listDatabasesJson(): String {
        val files = databasesDir().listFiles { f -> isDbFile(f) }?.map { it.name }?.sorted() ?: emptyList()
        return JSONObject()
            .put("ok", true)
            .put("count", files.size)
            .put("databases", JSONArray(files))
            .toString()
    }

    private fun isDbFile(f: File): Boolean {
        if (!f.isFile) return false
        val name = f.name
        return !name.endsWith("-journal") && !name.endsWith("-wal") && !name.endsWith("-shm") &&
            !name.endsWith(".material")
    }

    /**
     * 列出库内的表（名字、类型、建表语句）。
     *
     * @param dbName 数据库文件名（listDatabasesJson 返回）
     */
    fun listTablesJson(dbName: String): String {
        val result =
            runCatching {
                queryRows(dbName, "SELECT name, type, sql FROM sqlite_master WHERE type IN ('table','view') ORDER BY name", MAX_ROWS)
            }.getOrElse { return failedJson(it) }
        val (_, rows) = result
        return JSONObject()
            .put("ok", true)
            .put("database", dbName)
            .put("count", rows.length())
            .put("tables", rows)
            .toString()
    }

    /**
     * 只读查询（SELECT / PRAGMA / EXPLAIN / WITH）。
     *
     * 输出：首行「N row(s):」，后续每行「列=值, 列=值」；空值显示「列=」；
     * 无结果输出「0 rows.」。
     *
     * @param dbName 数据库文件名（listDatabasesJson 返回；encrypted_ 前缀自动解密）
     * @param sql 查询语句（建议加 LIMIT）
     * @param maxRows 返回行数上限（1..200）
     */
    fun queryJson(
        dbName: String,
        sql: String,
        maxRows: Int,
    ): String {
        val trimmed = sql.trim()
        if (trimmed.isEmpty()) return failedJson(IllegalArgumentException("SQL 为空"))
        if (QUERY_PREFIXES.none { trimmed.startsWith(it, ignoreCase = true) }) {
            return failedJson(IllegalArgumentException("查询只允许 SELECT / PRAGMA / EXPLAIN / WITH；写操作用 execute-database-statement"))
        }
        val limit = maxRows.coerceIn(1, MAX_ROWS)

        val (columns, rows) =
            runCatching {
                queryRows(dbName, trimmed, limit)
            }.getOrElse {
                evict(dbName)
                return failedJson(it)
            }

        val sb = StringBuilder()
        if (rows.length() == 0) {
            sb.append("0 rows.")
        } else {
            sb.append(rows.length()).append(" row(s):")
            for (i in 0 until rows.length()) {
                sb.append('\n')
                val row = rows.optJSONObject(i) ?: continue
                sb.append(columns.joinToString(", ") { c -> "$c=${formatValue(row.opt(c))}" })
            }
        }
        return JSONObject().put("ok", true).put("output", sb.toString()).toString()
    }

    /**
     * 执行写语句（INSERT / UPDATE / DELETE / DDL）。
     *
     * 成功输出「Statement executed.」；要验证效果再用查询工具确认。
     *
     * @param dbName 数据库文件名（listDatabasesJson 返回；encrypted_ 前缀自动解密）
     * @param sql 写语句
     */
    fun executeJson(
        dbName: String,
        sql: String,
    ): String {
        val trimmed = sql.trim()
        if (trimmed.isEmpty()) return failedJson(IllegalArgumentException("SQL 为空"))

        runCatching {
            val db = connectionOf(dbName)
            if (dbName.startsWith(ENCRYPTED_PREFIX)) {
                WcdbMethod.execSQL(db, trimmed)
            } else {
                (db as android.database.sqlite.SQLiteDatabase).execSQL(trimmed)
            }
        }.getOrElse {
            evict(dbName)
            return failedJson(it)
        }

        return JSONObject().put("ok", true).put("output", "Statement executed.").toString()
    }

    // ==================== 查询执行 ====================

    private fun queryRows(
        dbName: String,
        sql: String,
        limit: Int,
    ): Pair<Array<String>, JSONArray> {
        val db = connectionOf(dbName)
        return if (dbName.startsWith(ENCRYPTED_PREFIX)) {
            val cursor = WcdbMethod.rawQuery(db, sql)
            try {
                cursorToRows(cursor, limit)
            } finally {
                runCatching { cursor.close() }
            }
        } else {
            (db as android.database.sqlite.SQLiteDatabase)
                .rawQuery(sql, null)
                .use { cursorToRows(it, limit) }
        }
    }

    /** 取库连接（缓存命中先验文件仍在，库被删则弃旧连接）。 */
    private fun connectionOf(dbName: String): Any {
        val file = safeDbFile(dbName) ?: throw IllegalArgumentException("非法的数据库名: $dbName")
        connections[dbName]?.let { cached ->
            if (file.isFile) return cached
            evict(dbName)
        }
        if (!file.isFile) throw IllegalArgumentException("数据库不存在: $dbName")
        val db = if (dbName.startsWith(ENCRYPTED_PREFIX)) openEncrypted(file) else openPlain(file)
        connections[dbName] = db
        return db
    }

    /** 弃连接（宿主删库/连接损坏后重建）。 */
    private fun evict(dbName: String) {
        val stale = connections.remove(dbName) ?: return
        runCatching {
            if (dbName.startsWith(ENCRYPTED_PREFIX)) {
                WcdbMethod.close(stale)
            } else {
                (stale as android.database.sqlite.SQLiteDatabase).close()
            }
        }
    }

    private fun openPlain(file: File): Any =
        android.database.sqlite.SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        )

    // ==================== 加密库打开（宿主 WCDB） ====================

    /**
     * 用宿主 WCDB 的兼容层打开加密库。
     *
     * 打开方法按签名定位（静态、四参：路径/密码字节/加密参数/损坏回调）；
     * 加密参数按宿主用法构造（version 3 档），密码由文件名里的 uid 推导。
     */
    private fun openEncrypted(file: File): Any {
        val cl = HostRuntime.requireClassLoader()
        val dbClass = Class.forName("com.tencent.wcdb.compat.SQLiteDatabase", false, cl)
        val open =
            WcdbMethod.locateOpen(dbClass)
                ?: throw IllegalStateException("WCDB 打开方法未定位")
        return open.invoke(null, file.absolutePath, passwordFor(file.name), WcdbMethod.cipherSpec(cl), null)
            ?: throw IllegalStateException("WCDB 打开失败")
    }

    /** 密钥算法（逆向确认）：byte{uid}imwcdb{uid}dance；uid 取文件名里最长的数字串。 */
    private fun passwordFor(dbName: String): ByteArray {
        val uid =
            Regex("\\d{16,}").findAll(dbName).maxByOrNull { it.value.length }?.value
                ?: throw IllegalArgumentException("文件名中无账号 uid: $dbName")
        return "byte${uid}imwcdb${uid}dance".toByteArray()
    }

    /** WCDB 方法定位与调用（兼容层方法名混淆，全部按结构定位）。 */
    private object WcdbMethod {
        @Volatile
        private var openMethod: Method? = null

        @Volatile
        private var specTemplate: Any? = null

        @Volatile
        private var resolved = false

        private fun resolve(dbClass: Class<*>) {
            if (resolved) return
            synchronized(this) {
                if (resolved) return
                runCatching {
                    val cl = dbClass.classLoader
                    val specClass = Class.forName("com.tencent.wcdb.compat.SQLiteCipherSpec", false, cl)
                    val errorType =
                        Class.forName(
                            "com.tencent.wcdb.compat.DatabaseErrorHandler",
                            false,
                            cl,
                        )
                    // 打开方法：静态、参数 (String, byte[], 加密参数, 损坏回调)
                    openMethod =
                        dbClass.declaredMethods.firstOrNull { m ->
                            java.lang.reflect.Modifier
                                .isStatic(m.modifiers) &&
                                m.parameterCount == 4 &&
                                m.parameterTypes[0] == String::class.java &&
                                m.parameterTypes[1] == ByteArray::class.java &&
                                m.parameterTypes[2] == specClass &&
                                m.parameterTypes[3] == errorType
                        }
                    // 加密参数：无参构造 + 唯一单 int 参方法（版本设置，宿主用第 3 档）
                    val spec = specClass.getDeclaredConstructor().newInstance()
                    val versionSetter =
                        specClass.declaredMethods.singleOrNull { m ->
                            m.parameterCount == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType
                        } ?: run {
                            ModLog.w("WCDB：加密参数版本设置方法未定位")
                            return@runCatching
                        }
                    versionSetter.invoke(spec, 3)
                    specTemplate = spec
                }.onFailure { ModLog.w("WCDB 方法定位失败: $it") }
                resolved = true
            }
        }

        fun locateOpen(dbClass: Class<*>): Method? {
            resolve(dbClass)
            return openMethod
        }

        fun cipherSpec(cl: ClassLoader): Any? {
            resolve(Class.forName("com.tencent.wcdb.compat.SQLiteDatabase", false, cl))
            return specTemplate
        }

        fun rawQuery(
            db: Any,
            sql: String,
        ): Cursor =
            db.javaClass.methods
                .first { it.name == "rawQuery" && it.parameterCount == 2 }
                .invoke(db, sql, arrayOfNulls<Any?>(0)) as Cursor

        fun execSQL(
            db: Any,
            sql: String,
        ) {
            db.javaClass.methods
                .first { it.name == "execSQL" && it.parameterCount == 2 }
                .invoke(db, sql, null)
        }

        fun close(db: Any) {
            db.javaClass.methods
                .first { it.name == "close" && it.parameterCount == 0 }
                .invoke(db)
        }
    }

    // ==================== 行提取与输出 ====================

    /** 标准游标 → 行数组。 */
    private fun cursorToRows(
        cursor: Cursor,
        limit: Int,
    ): Pair<Array<String>, JSONArray> {
        val columns = Array(cursor.columnCount) { cursor.getColumnName(it) }
        val rows = JSONArray()
        while (cursor.moveToNext() && rows.length() < limit) {
            val row = JSONObject()
            for (i in columns.indices) {
                row.put(columns[i], cursorValue(cursor, i))
            }
            rows.put(row)
        }
        return columns to rows
    }

    private fun cursorValue(
        cursor: Cursor,
        index: Int,
    ): Any =
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> ""
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
            Cursor.FIELD_TYPE_BLOB -> "blob(${cursor.getBlob(index).size} bytes)"
            else -> cursor.getString(index) ?: ""
        }

    /** KV 输出的值：空值留空，长值截断，控制字符压平。 */
    private fun formatValue(value: Any?): String {
        if (value == null || value == JSONObject.NULL) return ""
        val text = value.toString().replace('\n', ' ').replace('\r', ' ')
        return if (text.length > VALUE_MAX_LEN) text.take(VALUE_MAX_LEN) + "…(${text.length})" else text
    }

    /** 统一失败输出：Statement failed: <描述> (code N, errno 0): <详情>。 */
    private fun failedJson(error: Throwable): String {
        val cause = (error.cause ?: error)
        val message = cause.message ?: cause.toString()
        val code = Regex("Code: (\\d+)").find(message)?.groupValues?.getOrNull(1) ?: "1"
        return JSONObject()
            .put("ok", false)
            .put(
                "error",
                "Statement failed: ${cause.javaClass.simpleName} (code $code, errno 0): $message",
            ).toString()
    }
}
