package io.github.yfishyon.doumcp.features.devtool.resolver

import io.github.yfishyon.doumcp.core.HostRuntime
import io.github.yfishyon.doumcp.features.devtool.bridge.ObjectHandles
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * 参数表达式求值：把工具收到的 JSON 参数转成真实的 Java 值。
 *
 * 支持的形态：
 * - 标量：字符串 / 数字 / true·false / null（按目标参数类型转换）
 * - `{"ref": "<对象路径>"}`、`{"static": "<类名>#<字段>"}`：取既有对象
 * - `{"handle": "obj:N"}`：取之前创建的对象
 * - `{"new": "<类名>", "args": [...]}`：构造实例
 * - `{"class": "<类名>"}`：Class 对象
 * - `{"enum": "<枚举类>", "name": "<常量>"}`：枚举常量
 * - `{"proxy": {"interfaces": [...], "returns": {"方法名": 值}}}`：动态代理
 *
 * 目标类型未知（target 为 null）时按 JSON 自然类型取值。
 */
object Expr {
    fun eval(
        element: JsonElement?,
        target: Class<*>?,
    ): Any? {
        if (element == null || element is JsonNull) return coerce(null, target)

        if (element is JsonArray) {
            val component = target?.takeIf { it.isArray }?.componentType
            if (component != null) {
                // 按目标元素类型真实构造（基础类型不装箱、对象数组不丢组件类型）
                val array =
                    java.lang.reflect.Array
                        .newInstance(component, element.size)
                element.forEachIndexed { index, item ->
                    java.lang.reflect.Array
                        .set(array, index, eval(item, component))
                }
                return array
            }
            return element.map { eval(it, null) }.toTypedArray()
        }

        if (element is JsonObject) return evalObject(element)

        val primitive = element as JsonPrimitive
        if (primitive.isString) return coerce(primitive.content, target)
        primitive.booleanOrNull?.let { return coerce(it, target) }
        primitive.longOrNull?.let { return coerce(it, target) }
        primitive.doubleOrNull?.let { return coerce(it, target) }
        return coerce(primitive.content, target)
    }

    /** 按目标参数类型逐个求值。 */
    fun evalArgs(
        array: JsonArray?,
        paramTypes: Array<Class<*>>,
    ): Array<Any?> {
        val items = array ?: JsonArray(emptyList())
        if (items.size != paramTypes.size) {
            throw IllegalStateException("参数个数不匹配：需要 ${paramTypes.size} 个，实际 ${items.size} 个")
        }
        return paramTypes.indices.map { eval(items[it], paramTypes[it]) }.toTypedArray()
    }

    /** 构造实例：按参数个数筛构造器，逐个尝试参数转换。 */
    fun newInstance(
        className: String,
        args: JsonArray?,
    ): Any {
        val clazz = TargetResolver.loadClass(className)
        if (clazz.isInterface || Modifier.isAbstract(clazz.modifiers)) {
            throw IllegalStateException("无法实例化抽象类或接口: $className")
        }
        val items = args ?: JsonArray(emptyList())
        val candidates = clazz.declaredConstructors.filter { it.parameterCount == items.size }
        if (candidates.isEmpty()) {
            throw IllegalStateException(
                "没有匹配的构造器: $className（参数个数 ${items.size}），" +
                    "候选：${clazz.declaredConstructors.joinToString("; ") { it.signatureText() }}",
            )
        }
        var lastError: Throwable? = null
        for (constructor in candidates) {
            try {
                val values = evalArgs(items, constructor.parameterTypes)
                return InvokerSupport.newInstance(constructor, values)
            } catch (t: java.lang.reflect.InvocationTargetException) {
                // 构造器已执行并自身抛出：解包抛出，不再尝试其它重载
                throw (t.cause ?: t)
            } catch (t: Exception) {
                lastError = t
            }
        }
        throw IllegalStateException("构造失败: $className（${lastError?.message ?: lastError}）")
    }

    /** 动态代理：用配置值应答指定方法，其余按返回类型给默认值。 */
    fun createProxy(
        interfaces: List<Class<*>>,
        returns: JsonObject?,
    ): Any {
        if (interfaces.isEmpty()) throw IllegalStateException("代理至少需要一个接口")
        val nonInterface = interfaces.firstOrNull { !it.isInterface }
        if (nonInterface != null) throw IllegalStateException("代理只支持接口，不支持类: ${nonInterface.name}")
        val loader =
            interfaces.first().classLoader
                ?: HostRuntime.requireClassLoader()
        return Proxy.newProxyInstance(loader, interfaces.toTypedArray()) { proxy, method, callArgs ->
            when {
                method.name == "equals" && method.parameterCount == 1 -> {
                    proxy === callArgs?.firstOrNull()
                }

                method.name == "hashCode" && method.parameterCount == 0 -> {
                    System.identityHashCode(proxy)
                }

                method.name == "toString" && method.parameterCount == 0 -> {
                    "DouMCP-Proxy(${interfaces.joinToString(", ") { it.name }})"
                }

                else -> {
                    val override = returns?.get(method.name)
                    if (override != null) {
                        eval(override, method.returnType)
                    } else {
                        defaultOf(method.returnType)
                    }
                }
            }
        } as Any
    }

    /**
     * 把值转换成目标类型；target 为 null 时原样返回。
     *
     * @throws IllegalStateException 类型无法转换（明确报错，不静默兜底）
     */
    fun coerce(
        value: Any?,
        target: Class<*>?,
    ): Any? {
        if (target == null || target == Any::class.java || target == Object::class.java) return value

        if (value == null) {
            if (target.isPrimitive) throw IllegalStateException("目标类型 ${target.name} 不接受 null")
            return null
        }

        if (boxed(target).isInstance(value)) return value

        return when (target) {
            String::class.java, CharSequence::class.java -> {
                value.toString()
            }

            Char::class.java, Char::class.javaObjectType -> {
                when (value) {
                    is Char -> value
                    is String -> value.firstOrNull() ?: throw IllegalStateException("空字符串无法转成 char")
                    is Number -> value.toInt().toChar()
                    else -> throw IllegalStateException("无法转成 char: $value")
                }
            }

            Boolean::class.java, Boolean::class.javaObjectType -> {
                when (value) {
                    is Boolean -> value
                    is String -> value.toBooleanStrictOrNull() ?: throw IllegalStateException("无法转成 boolean: $value")
                    else -> throw IllegalStateException("无法转成 boolean: $value")
                }
            }

            Byte::class.java, Byte::class.javaObjectType -> {
                toNumber(value).toByte()
            }

            Short::class.java, Short::class.javaObjectType -> {
                toNumber(value).toShort()
            }

            Int::class.java, Int::class.javaObjectType -> {
                toNumber(value).toInt()
            }

            Long::class.java, Long::class.javaObjectType -> {
                toNumber(value).toLong()
            }

            Float::class.java, Float::class.javaObjectType -> {
                toNumber(value).toFloat()
            }

            Double::class.java, Double::class.javaObjectType -> {
                toNumber(value).toDouble()
            }

            Class::class.java -> {
                if (value is String) TargetResolver.loadClass(value) else value
            }

            else -> {
                if (Collection::class.java.isAssignableFrom(target) && value.javaClass.isArray) {
                    val list = ArrayList<Any?>()
                    for (
                    index in 0 until
                        java.lang.reflect.Array
                            .getLength(value)
                    ) {
                        list.add(
                            java.lang.reflect.Array
                                .get(value, index),
                        )
                    }
                    list
                } else if (target.isEnum && value is String) {
                    enumValue(target, value)
                } else {
                    throw IllegalStateException(
                        "类型不匹配：期望 ${target.name}，实际 ${value.javaClass.name}（$value）",
                    )
                }
            }
        }
    }

    private fun evalObject(obj: JsonObject): Any? {
        obj["ref"]?.let { return TargetResolver.resolveHolder(it.textContent("ref")) }
        obj["static"]?.let { return TargetResolver.resolveHolder(it.textContent("static")) }
        obj["class"]?.let { return TargetResolver.loadClass(it.textContent("class")) }
        obj["handle"]?.let { handle ->
            val id = handle.textContent("handle")
            return ObjectHandles.get(id) ?: throw IllegalStateException("句柄不存在或已淘汰: $id")
        }
        obj["enum"]?.let {
            return enumValue(
                TargetResolver.loadClass(it.textContent("enum")),
                obj["name"]?.textContent("name") ?: throw IllegalStateException("enum 需要 name"),
            )
        }
        obj["new"]?.let { return newInstance(it.textContent("new"), obj["args"] as? JsonArray) }
        obj["proxy"]?.let { return proxy(obj["proxy"] as? JsonObject ?: throw IllegalStateException("proxy 需要对象")) }
        throw IllegalStateException("无法识别的表达式：${obj.keys}（支持 ref/handle/new/static/class/enum/proxy 或标量）")
    }

    private fun proxy(spec: JsonObject): Any {
        val interfacesElement = spec["interfaces"] as? JsonArray ?: throw IllegalStateException("proxy 需要 interfaces 数组")
        val interfaces = interfacesElement.map { TargetResolver.loadClass(it.textContent("interfaces")) }
        val returns = spec["returns"] as? JsonObject
        return createProxy(interfaces, returns)
    }

    private fun enumValue(
        clazz: Class<*>,
        name: String,
    ): Any? {
        if (!clazz.isEnum) throw IllegalStateException("不是枚举类: ${clazz.name}")
        val constants = clazz.enumConstants ?: emptyArray()
        return constants.firstOrNull { (it as Enum<*>).name == name }
            ?: throw IllegalStateException(
                "枚举常量不存在: ${clazz.name}.$name，可选：${constants.joinToString { (it as Enum<*>).name }}",
            )
    }

    private fun JsonElement.textContent(key: String): String =
        (this as? JsonPrimitive)
            ?.takeUnless { it is JsonNull }
            ?.content
            ?: throw IllegalStateException("$key 需要字符串（不能为 null）")

    private fun toNumber(value: Any): Number =
        when (value) {
            is Number -> value
            is Boolean -> if (value) 1 else 0
            is String -> value.toLongOrNull() ?: value.toDoubleOrNull() ?: throw IllegalStateException("无法转成数字: $value")
            is Char -> value.code
            else -> throw IllegalStateException("无法转成数字: $value")
        }

    private fun defaultOf(type: Class<*>): Any? =
        when (type) {
            Void.TYPE, Void::class.java -> null
            Boolean::class.java, Boolean::class.javaObjectType -> false
            Char::class.java, Char::class.javaObjectType -> '\u0000'
            Byte::class.java, Byte::class.javaObjectType -> 0.toByte()
            Short::class.java, Short::class.javaObjectType -> 0.toShort()
            Int::class.java, Int::class.javaObjectType -> 0
            Long::class.java, Long::class.javaObjectType -> 0L
            Float::class.java, Float::class.javaObjectType -> 0f
            Double::class.java, Double::class.javaObjectType -> 0.0
            else -> null
        }

    /** 基础类型与其包装类型的对应。 */
    private fun boxed(type: Class<*>): Class<*> =
        when (type) {
            Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
            Char::class.javaPrimitiveType -> Char::class.javaObjectType
            Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
            Short::class.javaPrimitiveType -> Short::class.javaObjectType
            Int::class.javaPrimitiveType -> Int::class.javaObjectType
            Long::class.javaPrimitiveType -> Long::class.javaObjectType
            Float::class.javaPrimitiveType -> Float::class.javaObjectType
            Double::class.javaPrimitiveType -> Double::class.javaObjectType
            else -> type
        }
}
