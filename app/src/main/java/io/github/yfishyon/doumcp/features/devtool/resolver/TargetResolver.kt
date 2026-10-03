package io.github.yfishyon.doumcp.features.devtool.resolver

import io.github.yfishyon.doumcp.core.HostRuntime
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 目标定位器：把用户输入的类名 / 方法 / 字段定位成宿主里的 [Class] / [Method] / [Field]，
 * 并解析对象路径。
 *
 * 对象路径语法：`类全名 [ '#' 静态字段 ( '.' 字段 | '[' 下标 ']' )* ]`，
 * 例如 `com.x.Y#sInstance.user.id`、`com.x.Y#sList[0].name`。
 * 无 `#` 时表示类本身（静态上下文）。
 *
 * 所有工具只在当前进程（MCP 所在进程）生效；宿主子进程里的类在本进程不可见。
 */
object TargetResolver {
    private val methodCache = java.util.concurrent.ConcurrentHashMap<Class<*>, List<Method>>()
    private val staticFieldCache = java.util.concurrent.ConcurrentHashMap<Class<*>, List<Field>>()

    private sealed class Step {
        data class Field(
            val name: String,
        ) : Step()

        data class Index(
            val index: Int,
        ) : Step()
    }

    /** 按名加载宿主类（不触发类初始化）；支持描述符写法。 */
    fun loadClass(name: String): Class<*> {
        val normalized =
            if (name.startsWith("L") && name.endsWith(";")) {
                name.substring(1, name.length - 1).replace('/', '.')
            } else {
                name
            }
        if (normalized.isEmpty()) throw IllegalStateException("类名为空")
        return try {
            Class.forName(normalized, false, HostRuntime.requireClassLoader())
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException(
                "类不存在: $normalized（仅当前进程 ${HostRuntime.processName} 可见；" +
                    "若该类在宿主其它进程（如 IM、推送），本工具无法访问）",
            )
        } catch (e: LinkageError) {
            throw IllegalStateException("类无法解析: $normalized（${e.javaClass.simpleName}: ${e.message}）")
        } catch (e: Throwable) {
            throw IllegalStateException("类加载失败: $normalized（${e.javaClass.simpleName}: ${e.message}）")
        }
    }

    /** 按名与方法签名取方法（含继承链与接口）；无签名且重名时要求消歧。 */
    fun findMethod(
        clazz: Class<*>,
        name: String,
        paramTypes: List<String>? = null,
        paramCount: Int? = null,
    ): Method {
        val candidates = methodsOf(clazz).filter { it.name == name }
        if (candidates.isEmpty()) throw IllegalStateException("方法不存在: ${clazz.name}.$name")
        val matched =
            when {
                paramTypes != null -> candidates.filter { it.matchParams(paramTypes) }
                paramCount != null -> candidates.filter { it.parameterCount == paramCount }
                else -> candidates
            }
        if (matched.isEmpty()) {
            throw IllegalStateException(
                "方法名匹配但参数不匹配: ${clazz.name}.$name，" +
                    "候选签名：${candidates.joinToString("; ") { it.signatureText() }}",
            )
        }
        if (matched.size > 1) {
            throw IllegalStateException(
                "方法名不唯一: ${clazz.name}.$name，请指定 signature 消歧，" +
                    "候选签名：${matched.joinToString("; ") { it.signatureText() }}",
            )
        }
        return matched.first().also { it.isAccessible = true }
    }

    /** 取构造器（含继承无关，仅本类）；无签名且重载时要求消歧。 */
    fun findConstructor(
        clazz: Class<*>,
        paramTypes: List<String>? = null,
    ): Constructor<*> {
        val candidates = clazz.declaredConstructors.toList()
        val matched =
            if (paramTypes == null) {
                candidates
            } else {
                candidates.filter { it.matchParams(paramTypes) }
            }
        if (matched.isEmpty()) {
            throw IllegalStateException(
                "构造器参数不匹配: ${clazz.name}，候选：${candidates.joinToString("; ") { it.signatureText() }}",
            )
        }
        if (matched.size > 1) {
            throw IllegalStateException(
                "构造器不唯一: ${clazz.name}，请指定 signature 消歧，" +
                    "候选：${matched.joinToString("; ") { it.signatureText() }}",
            )
        }
        return matched.first().also { it.isAccessible = true }
    }

    /** 按名取字段（含继承链与接口）。 */
    fun findField(
        clazz: Class<*>,
        name: String,
    ): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            current.declaredFields.firstOrNull { it.name == name }?.let {
                it.isAccessible = true
                return it
            }
            fieldFromInterfaces(current, name)?.let { return it }
            current = current.superclass
        }
        return null
    }

    /**
     * 解析对象路径，返回被测对象；路径只有类名时返回该 [Class]（静态上下文）。
     *
     * @throws IllegalStateException 类/字段不存在、下标越界、或中途为 null
     */
    fun resolveHolder(path: String): Any {
        val hash = path.indexOf('#')
        val className = (if (hash >= 0) path.substring(0, hash) else path).trim()
        val rest = if (hash >= 0) path.substring(hash + 1) else ""
        if (className.isEmpty()) throw IllegalStateException("路径缺少类名: $path")

        val clazz = loadClass(className)
        val steps = parseSteps(rest)
        if (steps.isEmpty()) return clazz

        var current: Any? = clazz
        for (step in steps) {
            current =
                when (step) {
                    is Step.Field -> readStep(current, step.name, path)
                    is Step.Index -> indexStep(current, step.index, path)
                }
        }
        return current ?: throw IllegalStateException("路径解析结果为 null: $path")
    }

    private fun readStep(
        holder: Any?,
        name: String,
        path: String,
    ): Any? {
        val context = holder ?: throw IllegalStateException("路径在 null 上继续取字段: $name（路径 $path）")
        val owner = if (context is Class<*>) context else context::class.java
        val field = findField(owner, name) ?: throw IllegalStateException("字段不存在: ${owner.name}.$name（路径 $path）")
        val isStatic = Modifier.isStatic(field.modifiers)
        return if (context is Class<*>) {
            if (!isStatic) throw IllegalStateException("静态上下文不能取实例字段: ${owner.name}.$name（路径 $path）")
            field.get(null)
        } else if (isStatic) {
            field.get(null)
        } else {
            field.get(context)
        }
    }

    private fun indexStep(
        holder: Any?,
        index: Int,
        path: String,
    ): Any? {
        val value = holder ?: throw IllegalStateException("下标作用于 null（路径 $path）")
        return when (value) {
            is Array<*> -> value.getOrNull(index)
            is List<*> -> value.getOrNull(index)
            is Collection<*> -> value.toList().getOrNull(index)
            else -> throw IllegalStateException("目标不是数组或集合，无法按下标取值（路径 $path）")
        } ?: throw IllegalStateException("下标越界或元素为空: [$index]（路径 $path）")
    }

    private fun parseSteps(raw: String): List<Step> {
        val text = raw.trim()
        val steps = ArrayList<Step>()
        val name = StringBuilder()
        var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                '.' -> {
                    if (name.isNotEmpty()) {
                        steps.add(Step.Field(name.toString().trim()))
                        name.clear()
                    }
                    i++
                }

                '[' -> {
                    if (name.isNotEmpty()) {
                        steps.add(Step.Field(name.toString().trim()))
                        name.clear()
                    }
                    val end = text.indexOf(']', i)
                    if (end <= i) throw IllegalStateException("路径下标未闭合: $text")
                    val raw = text.substring(i + 1, end).trim()
                    steps.add(Step.Index(raw.toIntOrNull() ?: throw IllegalStateException("下标非法: $raw")))
                    i = end + 1
                }

                else -> {
                    name.append(c)
                    i++
                }
            }
        }
        if (name.isNotEmpty()) steps.add(Step.Field(name.toString().trim()))
        return steps
    }

    /** 类及其接口、父类的全部方法（结果缓存，宿主类不会重复反射）。 */
    fun methodsOf(clazz: Class<*>): List<Method> = methodCache.computeIfAbsent(clazz) { buildMethods(it) }

    /** 类的静态字段（含继承链）；结果缓存。 */
    fun staticFieldsOf(clazz: Class<*>): List<Field> = staticFieldCache.computeIfAbsent(clazz) { buildStaticFields(it) }

    private fun buildMethods(clazz: Class<*>): List<Method> {
        val result = LinkedHashMap<String, Method>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (method in current.declaredMethods) {
                result.putIfAbsent(method.signatureText(), method)
            }
            for (iface in current.interfaces) {
                for (method in iface.methods) {
                    result.putIfAbsent(method.signatureText(), method)
                }
            }
            current = current.superclass
        }
        return result.values.toList()
    }

    private fun buildStaticFields(clazz: Class<*>): List<Field> {
        val result = LinkedHashMap<String, Field>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) {
                    field.isAccessible = true
                    result.putIfAbsent(field.name, field)
                }
            }
            current = current.superclass
        }
        return result.values.toList()
    }

    private fun fieldFromInterfaces(
        clazz: Class<*>,
        name: String,
    ): Field? {
        for (iface in clazz.interfaces) {
            iface.declaredFields.firstOrNull { it.name == name }?.let {
                it.isAccessible = true
                return it
            }
            fieldFromInterfaces(iface, name)?.let { return it }
        }
        return null
    }
}
