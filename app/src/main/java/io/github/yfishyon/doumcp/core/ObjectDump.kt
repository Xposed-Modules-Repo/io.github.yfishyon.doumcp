package io.github.yfishyon.doumcp.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * 对象字段 dump：浅层只取字符串与数值，深层递归展开对象/集合/数组/Map。
 *
 * 宿主对象图很深且常带环，深层展开有深度上限与访问环去重（这是防死循环，不是截断）；
 * 内容不截断，由调用方按需限制。
 */
object ObjectDump {
    /** 默认最大展开层数。 */
    const val DEFAULT_DEPTH = 16

    /** 精简字段：只保留字符串与数值，空串跳过，复杂结构丢弃。 */
    fun shallow(obj: Any): JSONObject {
        val out = JSONObject()
        var currentClass: Class<*>? = obj.javaClass
        while (currentClass != null && currentClass != Any::class.java) {
            for (field in currentClass.declaredFields) {
                if (java.lang.reflect.Modifier
                        .isStatic(field.modifiers)
                ) {
                    continue
                }
                field.isAccessible = true
                when (val value = runCatching { field.get(obj) }.getOrNull()) {
                    null -> Unit
                    is String -> if (value.isNotEmpty()) out.put(field.name, value)
                    is Boolean, is Int, is Long -> out.put(field.name, value)
                    is Double -> if (value.isFinite()) out.put(field.name, value)
                    is Float -> if (value.isFinite()) out.put(field.name, value)
                    else -> Unit
                }
            }
            currentClass = currentClass.superclass
        }
        return out
    }

    /**
     * 全量字段：递归展开对象 / 集合 / 数组 / Map，只滤空值。
     *
     * @param depth 最大展开层数（防递归过深）
     */
    fun deep(
        obj: Any,
        depth: Int = DEFAULT_DEPTH,
    ): JSONObject = deepInto(obj, Collections.newSetFromMap(java.util.IdentityHashMap()), depth)

    private fun deepInto(
        obj: Any,
        visited: MutableSet<Any>,
        depth: Int,
    ): JSONObject {
        val out = JSONObject()
        var currentClass: Class<*>? = obj.javaClass
        while (currentClass != null && currentClass != Any::class.java) {
            for (field in currentClass.declaredFields) {
                if (java.lang.reflect.Modifier
                        .isStatic(field.modifiers)
                ) {
                    continue
                }
                field.isAccessible = true
                val value = runCatching { field.get(obj) }.getOrNull() ?: continue
                val converted = convert(value, visited, depth) ?: continue
                out.put(field.name, converted)
            }
            currentClass = currentClass.superclass
        }
        return out
    }

    private fun convert(
        value: Any,
        visited: MutableSet<Any>,
        depth: Int,
    ): Any? =
        when (value) {
            is String -> {
                value.ifEmpty { null }
            }

            is Boolean, is Int, is Long -> {
                value
            }

            is Double -> {
                value.takeIf { it.isFinite() }
            }

            is Float -> {
                value.takeIf { it.isFinite() }
            }

            is JSONObject, is JSONArray -> {
                value
            }

            is Collection<*>, is Array<*>, is Map<*, *> -> {
                convertContainer(value, visited, depth)
            }

            // 原生类型数组（byte[] / int[] 等）不是 Array<*>，单独处理，避免被当作无字段对象丢弃
            else -> {
                if (value.javaClass.isArray) {
                    val length =
                        java.lang.reflect.Array
                            .getLength(value)
                    JSONArray().apply {
                        for (index in 0 until length) {
                            put(
                                java.lang.reflect.Array
                                    .get(value, index),
                            )
                        }
                    }
                } else if (depth <= 0 || !visited.add(value)) {
                    null
                } else {
                    try {
                        deepInto(value, visited, depth - 1).takeIf { it.length() > 0 }
                    } finally {
                        visited.remove(value)
                    }
                }
            }
        }

    /** 容器展开：同样受深度与访问环约束（自引用集合/数组/Map 不会无界递归）。 */
    private fun convertContainer(
        value: Any,
        visited: MutableSet<Any>,
        depth: Int,
    ): Any? {
        if (depth <= 0 || !visited.add(value)) return null
        try {
            return when (value) {
                is Collection<*> -> {
                    JSONArray().apply {
                        value.forEach { item -> item?.let { convert(it, visited, depth - 1)?.let { c -> put(c) } } }
                    }
                }

                is Array<*> -> {
                    JSONArray().apply {
                        value.forEach { item -> item?.let { convert(it, visited, depth - 1)?.let { c -> put(c) } } }
                    }
                }

                is Map<*, *> -> {
                    JSONObject().apply {
                        value.forEach { (k, v) ->
                            v?.let { convert(it, visited, depth - 1)?.let { c -> put(k.toString(), c) } }
                        }
                    }
                }

                else -> {
                    null
                }
            }
        } finally {
            visited.remove(value)
        }
    }
}
