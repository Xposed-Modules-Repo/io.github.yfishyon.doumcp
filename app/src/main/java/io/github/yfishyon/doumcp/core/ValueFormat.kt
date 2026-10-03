package io.github.yfishyon.doumcp.core

import org.json.JSONObject

/**
 * 运行时的值展示：把任意对象转成可读文本（带类名与身份哈希），并转成可直接入 JSON 的值。
 * 内容不截断。
 */
object ValueFormat {
    /** 可读描述：标量原样，复杂对象给「类名@身份哈希 + toString」。 */
    fun describe(value: Any?): String =
        when (value) {
            null -> {
                "null"
            }

            is String -> {
                "\"$value\""
            }

            is Char, is Boolean, is Number -> {
                value.toString()
            }

            is Class<*> -> {
                "${value.name} (Class)"
            }

            else -> {
                val text = runCatching { value.toString() }.getOrElse { "<toString 异常: ${it.javaClass.simpleName}>" }
                "${value.javaClass.name}@${Integer.toHexString(System.identityHashCode(value))} $text"
            }
        }

    /** 一次算出「JSON 值 + 展示文本」，复杂对象只调一次 toString。 */
    fun described(value: Any?): Pair<Any?, String> {
        val text = describe(value)
        val safe =
            when (value) {
                null -> JSONObject.NULL
                is String, is Boolean, is Int, is Long, is Double, is Float -> value
                is Char, is Number -> value.toString()
                else -> text
            }
        return safe to text
    }

    /** 转成可直接放进 [JSONObject] 的值：标量原样，复杂对象用描述串。 */
    fun jsonSafe(value: Any?): Any? =
        when (value) {
            null -> {
                JSONObject.NULL
            }

            is String, is Boolean, is Int, is Long, is Double, is Float -> {
                value
            }

            is Char -> {
                value.toString()
            }

            is Number -> {
                value.toString()
            }

            else -> {
                describe(value)
            }
        }
}
