package io.github.yfishyon.doumcp.core

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

object Reflect {
    private val fieldsByClass = ConcurrentHashMap<Class<*>, Map<String, Field>>()
    private val methodsByClass = ConcurrentHashMap<Class<*>, Map<String, Method>>()

    /** 取实例字段值（含继承链）。 */
    fun field(
        holder: Any,
        name: String,
    ): Any? {
        val field = fieldsOf(holder.javaClass)[name] ?: return null
        return runCatching { field.get(holder) }.getOrNull()
    }

    /** 按顺序取多个字段，只返回有值的。 */
    fun fields(
        holder: Any,
        names: List<String>,
    ): List<Pair<String, Any>> {
        val map = fieldsOf(holder.javaClass)
        val result = ArrayList<Pair<String, Any>>(names.size)
        for (name in names) {
            val field = map[name] ?: continue
            runCatching { field.get(holder) }.getOrNull()?.let { result.add(name to it) }
        }
        return result
    }

    /** 该类的实例字段表（名 → Field），含继承链。 */
    fun fieldsOf(clazz: Class<*>): Map<String, Field> =
        fieldsByClass.computeIfAbsent(clazz) { target ->
            // LinkedHashMap：保留字段声明顺序（按序位定位字段时依赖它）
            val map = LinkedHashMap<String, Field>()
            var current: Class<*>? = target
            while (current != null && current != Any::class.java) {
                for (field in current.declaredFields) {
                    if (Modifier.isStatic(field.modifiers)) continue
                    field.isAccessible = true
                    map.putIfAbsent(field.name, field)
                }
                current = current.superclass
            }
            map
        }

    /**
     * 遍历实例上所有字段的值（字段名 → 值）。
     * 字段对象走缓存，值每次现取。
     */
    fun valuesOf(holder: Any): List<Triple<String, Class<*>, Any?>> =
        fieldsOf(holder.javaClass).values.map { field ->
            Triple(field.name, field.type, runCatching { field.get(holder) }.getOrNull())
        }

    /** 按名带参调用（参数类型匹配法：先精确类型，再宽松）。 */
    fun invoke(
        target: Any,
        name: String,
        vararg args: Any?,
    ): Any? {
        val method =
            target.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == args.size
            } ?: return null
        return runCatching { method.invoke(target, *args) }.getOrNull()
    }

    /**
     * 调无参方法，异常原样抛出（不吞）——诊断网络调用失败原因时用。
     */
    fun invokeOrThrow(
        holder: Any,
        name: String,
    ): Any {
        val method =
            holder.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                ?: throw IllegalStateException("方法不存在: $name")
        return try {
            method.invoke(holder) ?: throw IllegalStateException("$name 返回 null")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw (e.cause ?: e)
        }
    }

    /** 调无参 getter。 */
    fun getter(
        holder: Any,
        name: String,
    ): Any? {
        val method = methodsOf(holder.javaClass)[name] ?: return null
        return runCatching { method.invoke(holder) }.getOrNull()
    }

    /** 按名取方法（不存在返回 null）。 */
    fun method(
        clazz: Class<*>,
        name: String,
        paramCount: Int? = null,
    ): Method? {
        if (paramCount == null) return methodsOf(clazz)[name]
        return runCatching {
            clazz.methods.firstOrNull { it.name == name && it.parameterCount == paramCount }
        }.getOrNull()
    }

    private fun methodsOf(clazz: Class<*>): Map<String, Method> =
        methodsByClass.computeIfAbsent(clazz) { target ->
            val map = HashMap<String, Method>()
            for (method in target.methods) {
                if (method.parameterCount != 0) continue
                map.putIfAbsent(method.name, method)
            }
            map
        }
}
