package io.github.yfishyon.doumcp.features.im.bridge

import io.github.yfishyon.doumcp.core.HostRuntime
import io.github.yfishyon.doumcp.core.ModLog
import io.github.yfishyon.doumcp.core.Reflect
import io.github.yfishyon.doumcp.features.im.resolver.ImResolver
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * IM 用户桥：uid → 会话对方在 IM 里的资料（昵称/备注/头像/secUid）。
 *
 * 走 IM 用户仓库（未混淆类名）的请求方法，结构特征由 功能包 Resolver
 * 定位：请求参数对象（无参构造 + uid 字段 + 场景枚举字段），回调为
 * Kotlin Function1 动态代理。结果按 uid 缓存。
 */
object ImUserBridge {
    private const val CALLBACK_TIMEOUT_SEC = 5L

    /** uid → 资料缓存（容量受限）。 */
    private val cache =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, JSONObject>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, JSONObject>): Boolean = size > 128
            },
        )

    @Volatile
    private var fetchMethod: Method? = null

    @Volatile
    private var repoInstance: Any? = null

    /** 取单个用户资料（内部用，失败返回 null）。 */
    fun resolve(uid: String): JSONObject? {
        if (uid.isBlank()) return null
        cache[uid]?.let { return it }

        val method: Method
        val instance: Any?
        synchronized(this) {
            if (fetchMethod == null) {
                val pair = ImResolver.resolveUserFetch() ?: return null
                fetchMethod = pair.first
                repoInstance = pair.second
            }
            method = fetchMethod ?: return null
            instance = repoInstance
        }

        return runCatching {
            val paramType = method.parameterTypes.first()
            val param = paramType.getDeclaredConstructor().newInstance()

            // 请求参数：String 字段是 uid，枚举字段是查询场景（缓存→DB→网络）
            var uidSet = false
            for (field in Reflect.fieldsOf(paramType).values) {
                if (!uidSet && field.type == String::class.java) {
                    field.set(param, uid)
                    uidSet = true
                } else if (field.type.isEnum) {
                    val scene =
                        field.type.enumConstants
                            ?.firstOrNull { (it as Enum<*>).name == "CACHE_DB_NET" }
                    if (scene != null) field.set(param, scene)
                }
            }

            // 回调（Function1 动态代理）：同步返回无结果时等回调
            val function1 = HostRuntime.requireClassLoader().loadClass("kotlin.jvm.functions.Function1")
            val latch = CountDownLatch(1)
            val callbackBox = arrayOfNulls<Any>(1)
            val callback =
                Proxy.newProxyInstance(
                    function1.classLoader,
                    arrayOf(function1),
                ) { _, m, args ->
                    if (m.name == "invoke") {
                        callbackBox[0] = args?.firstOrNull { it != null }
                        latch.countDown()
                        Unit
                    } else {
                        null
                    }
                }

            val useInstance = repoInstance != null
            val direct =
                if (useInstance) {
                    method.invoke(repoInstance, param, callback)
                } else {
                    method.invoke(null, param, callback)
                }
            val imUser =
                direct ?: run {
                    if (!latch.await(CALLBACK_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                        ModLog.w("im_user uid=$uid 回调超时")
                        return null
                    }
                    callbackBox[0]
                } ?: return null

            extractUserInfo(imUser, uid)?.also { cache[uid] = it }
        }.onFailure { ModLog.w("im_user uid=$uid 失败: $it") }.getOrNull()
    }

    /** IM 用户模型 → JSON（语义 getter）。 */
    private fun extractUserInfo(
        imUser: Any,
        uid: String,
    ): JSONObject? {
        val out = JSONObject()
        out.put("uid", uid)
        out.put("nickname", Reflect.getter(imUser, "getNickname") as? String ?: "")
        (Reflect.getter(imUser, "getRemarkName") as? String)?.let { out.put("remarkName", it) }
        (Reflect.getter(imUser, "getSecUid") as? String)?.let { out.put("secUid", it) }

        val avatar =
            Reflect.getter(imUser, "getDisplayAvatar")
                ?: Reflect.getter(imUser, "getAvatarThumb")
        avatar?.let {
            (Reflect.field(it, "urlList") as? List<*>)?.firstOrNull()?.let { url ->
                out.put("avatarUrl", url.toString())
            }
        }
        return out.takeIf { it.optString("nickname").isNotEmpty() || it.optString("remarkName").isNotEmpty() }
    }

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
