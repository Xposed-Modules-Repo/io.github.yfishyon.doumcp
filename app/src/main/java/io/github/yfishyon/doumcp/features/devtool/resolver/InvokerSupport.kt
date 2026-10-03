package io.github.yfishyon.doumcp.features.devtool.resolver

import io.github.libxposed.api.XposedInterface
import io.github.yfishyon.doumcp.core.HostRuntime
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * 方法/构造器调用：走 libxposed 的调用器（绕过访问检查）。
 *
 * 调用类型：默认 `ORIGIN`（调原始实现、跳过所有 hook，行为可预期）；
 * `FULL` 则走完整 hook 链，可用于验证「宿主这样调会不会命中我挂的 hook」。
 *
 * 不缓存调用器：框架的 `getInvoker` 每次返回新对象、`setType` 是**原地改字段**，
 * 缓存后并发以不同 chain 调用同一方法会互相覆盖类型，故每次现取现设。
 */
internal object InvokerSupport {
    fun invoke(
        receiver: Any?,
        method: Method,
        args: Array<Any?>,
        special: Boolean = false,
        chainFull: Boolean = false,
    ): Any? {
        val invoker = HostRuntime.requireModule().getInvoker(method).setType(typeOf(chainFull))
        return if (special) {
            invoker.invokeSpecial(receiver ?: throw IllegalStateException("非虚调用需要实例（静态方法不支持）"), *args)
        } else {
            invoker.invoke(receiver, *args)
        }
    }

    fun newInstance(
        constructor: Constructor<*>,
        args: Array<Any?>,
    ): Any =
        HostRuntime
            .requireModule()
            .getInvoker(constructor)
            .setType(XposedInterface.Invoker.Type.ORIGIN)
            .newInstance(*args)

    private fun typeOf(chainFull: Boolean): XposedInterface.Invoker.Type =
        if (chainFull) {
            XposedInterface.Invoker.Type.Chain.FULL
        } else {
            XposedInterface.Invoker.Type.ORIGIN
        }
}
