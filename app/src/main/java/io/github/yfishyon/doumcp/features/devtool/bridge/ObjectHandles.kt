package io.github.yfishyon.doumcp.features.devtool.bridge

import java.util.concurrent.atomic.AtomicInteger

/**
 * 跨调用对象句柄表：`newInstance` / `invokeMethod(asHandle=true)` / `dynamicProxy` 产出的对象
 * 存成 `obj:N` 句柄，后续工具可用 `{"handle":"obj:N"}` 在别的调用里引用它。
 *
 * 有界（LRU 淘汰），避免强引用把宿主对象长期钉住；热重载时清空。
 */
object ObjectHandles {
    private const val MAX_HANDLES = 64

    private val counter = AtomicInteger(0)

    private val handles =
        object : LinkedHashMap<String, Any>(MAX_HANDLES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>): Boolean = size > MAX_HANDLES
        }

    /** 存入对象并返回新句柄（每次调用都会分配新 id，不做去重）。 */
    fun put(value: Any): String {
        synchronized(handles) {
            val id = "obj:${counter.incrementAndGet()}"
            handles[id] = value
            return id
        }
    }

    /** 按句柄取对象；不存在返回 null。 */
    fun get(handle: String): Any? = synchronized(handles) { handles[handle] }

    /** 清空（热重载用）。 */
    fun clear() {
        synchronized(handles) { handles.clear() }
    }
}
