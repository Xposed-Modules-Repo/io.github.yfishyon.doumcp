package io.github.yfishyon.doumcp.features.devtool.resolver

import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

/** 类型的可读名（数组与基础类型友好）。 */
internal fun Class<*>.typeText(): String = if (isArray) "${componentType!!.typeText()}[]" else name

/** 参数表文本，形如 `(java.lang.String, int)`。 */
internal fun Executable.paramsText(): String = "(" + parameterTypes.joinToString(", ") { it.typeText() } + ")"

/** 方法的可读签名，形如 `(java.lang.String, int) -> void`。 */
internal fun Method.signatureText(): String = "(" + parameterTypes.joinToString(", ") { it.typeText() } + ") -> " + returnType.typeText()

/** 构造器的可读签名。 */
internal fun Constructor<*>.signatureText(): String = "(" + parameterTypes.joinToString(", ") { it.typeText() } + ")"

/** 参数类型按「全名 / 简单名 / 基础类型名 / 数组写法」匹配（统一一处，避免多套实现不一致）。 */
internal fun Executable.matchParams(spec: List<String>): Boolean {
    val types = parameterTypes
    if (types.size != spec.size) return false
    return types.indices.all { index -> typeMatches(types[index], spec[index]) }
}

internal fun typeMatches(
    type: Class<*>,
    spec: String,
): Boolean =
    when {
        type.isArray -> {
            val component = type.componentType!!
            spec == "${component.simpleName}[]" ||
                spec == "${component.name}[]" ||
                spec == type.name
        }

        else -> {
            spec == type.name || spec == type.simpleName
        }
    }

/** 两个类型名列表按「全名或简单名」比较（用于 DexKit 的类型名与用户输入对照）。 */
internal fun List<String>.matchTypeNames(spec: List<String>): Boolean {
    if (size != spec.size) return false
    return indices.all { index -> typeNameMatches(this[index], spec[index]) }
}

internal fun typeNameMatches(
    actual: String,
    spec: String,
): Boolean = actual == spec || actual.substringAfterLast('.') == spec.substringAfterLast('.')
