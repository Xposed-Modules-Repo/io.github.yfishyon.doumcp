package io.github.yfishyon.doumcp.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * 抖音风格 toast：优先调宿主 IM 的静态工具方法（未混淆类名），失败回退系统 Toast；主线程执行。
 */
object DouToastHelper {
    private const val TOAST_UTILS_CLASS = "com.aweme.im.platform.utils.ToastUtils"

    private val mainHandler = Handler(Looper.getMainLooper())

    private val toastMethodCache = ConcurrentHashMap<ClassLoader, Method?>()

    /**
     * 显示 toast。
     *
     * @param context 任意上下文（内部取 applicationContext）
     * @param message 文本内容
     */
    fun show(
        context: Context,
        message: String,
    ) {
        mainHandler.post {
            val appContext = context.applicationContext
            val method = toastMethodCache.computeIfAbsent(appContext.classLoader) { resolve(it) }
            if (method != null) {
                runCatching {
                    method.invoke(null, appContext, message)
                    return@post
                }
            }
            runCatching { Toast.makeText(appContext, message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun resolve(classLoader: ClassLoader): Method? =
        runCatching {
            classLoader
                .loadClass(TOAST_UTILS_CLASS)
                .declaredMethods
                .firstOrNull {
                    Modifier.isStatic(it.modifiers) &&
                        it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == Context::class.java &&
                        it.parameterTypes[1] == String::class.java &&
                        it.returnType == Void.TYPE
                }?.apply { isAccessible = true }
        }.getOrNull()
}
