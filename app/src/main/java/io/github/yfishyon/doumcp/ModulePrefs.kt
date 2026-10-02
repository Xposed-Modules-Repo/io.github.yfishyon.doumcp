package io.github.yfishyon.doumcp

import android.content.Context
import io.fastkv.FastKV

/**
 * 用户配置（FastKV 原生 API）：服务端口 / 鉴权密钥 / 总开关。
 *
 * key 不改动（老数据读取依赖）；设置页保存后需重启抖音生效。
 */
object ModulePrefs {
    private const val STORE_NAME = "doumcp_settings"

    private const val KEY_PORT = "server_port"
    private const val KEY_TOKEN = "auth_token"
    private const val KEY_ENABLED = "server_enabled"

    private const val DEFAULT_PORT = 19320

    private fun kv(context: Context): FastKV = FastKV.Builder(context, STORE_NAME).build()

    fun getPort(context: Context): Int = kv(context).getInt(KEY_PORT, DEFAULT_PORT)

    fun setPort(
        context: Context,
        port: Int,
    ) {
        kv(context).putInt(KEY_PORT, port)
    }

    fun getToken(context: Context): String = kv(context).getString(KEY_TOKEN, "") ?: ""

    fun setToken(
        context: Context,
        token: String,
    ) {
        kv(context).putString(KEY_TOKEN, token)
    }

    /** 总开关（默认开启，关闭后宿主内不启动 MCP 服务） */
    fun isEnabled(context: Context): Boolean = kv(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        kv(context).putBoolean(KEY_ENABLED, enabled)
    }
}
