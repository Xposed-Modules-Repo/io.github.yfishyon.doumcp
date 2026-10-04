package io.github.yfishyon.doumcp.features.comment.bridge

import io.github.yfishyon.doumcp.core.HostRuntime
import io.github.yfishyon.doumcp.core.ModLog
import io.github.yfishyon.doumcp.core.Reflect
import io.github.yfishyon.doumcp.features.account.bridge.AccountBridge
import io.github.yfishyon.doumcp.features.comment.resolver.CommentResolver
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 评论删除桥：按作品 ID + 评论 ID 逐个删除自己发布的评论。
 *
 * 走宿主自己的删除接口（Retrofit 接口，端点写在注解里），实参按参数上的
 * 表单字段名填充；接口实例复用评论域已有的实例。评论场景等参数取宿主自身
 * 删除评论时的基准值（无界面上下文时即这些值）。
 */
object CommentDeleteBridge {
    private const val COMMENT_API = "com.ss.android.ugc.aweme.comment.api.CommentRealApi"
    private const val FIELD_ANNOTATION = "retrofit2.http.Field"

    /** 无界面上下文时宿主自身删除评论所用的基准值。 */
    private const val CHANNEL_ID = 0
    private const val SERVICE_ID = "0"
    private const val COMMENT_SCENE = "0"
    private const val HOTSPOT_ID = ""

    private const val TIMEOUT_SEC = 30L

    /**
     * 批量删除评论（逐个调用，单条失败不影响其余）。
     *
     * @param awemeId 评论所属作品 ID
     * @param cids 评论 ID 列表
     */
    fun deleteCommentsJson(
        awemeId: String,
        cids: List<String>,
    ): String {
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录，无法删除评论")
        if (awemeId.isBlank()) return errorJson("awemeId 不能为空")
        if (cids.isEmpty()) return errorJson("cids 不能为空")

        val api = CommentResolver.api() ?: return errorJson("评论接口创建失败")
        val method = resolveDeleteMethod() ?: return errorJson("评论删除方法未定位")

        val results = JSONArray()
        for (cid in cids) {
            results.put(deleteOne(method, api, awemeId, cid))
        }
        return JSONObject().put("ok", true).put("results", results).toString()
    }

    /** 删除方法：同时带 cid 与 aweme_id 表单字段的接口方法（单条删除唯一的形状）。 */
    private fun resolveDeleteMethod(): Method? =
        HostRuntime.hostClass(COMMENT_API).methods.firstOrNull { method ->
            val fields = fieldNames(method)
            "cid" in fields && "aweme_id" in fields
        }

    /** 方法参数上的表单字段名集合。 */
    private fun fieldNames(method: Method): Set<String> {
        val names = HashSet<String>()
        method.parameterAnnotations.forEach { annotations ->
            fieldName(annotations)?.let { names.add(it) }
        }
        return names
    }

    /** 参数注解里的表单字段名；无 @Field 注解返回 null。 */
    private fun fieldName(annotations: Array<Annotation>): String? {
        val field = annotations.firstOrNull { it.annotationClass.java.name == FIELD_ANNOTATION } ?: return null
        return field.annotationClass.java
            .getMethod("value")
            .invoke(field) as String
    }

    private fun deleteOne(
        method: Method,
        api: Any,
        awemeId: String,
        cid: String,
    ): JSONObject {
        val item = JSONObject().put("id", cid)
        return runCatching {
            val args =
                buildArgs(
                    method,
                    mapOf(
                        "cid" to cid,
                        "channel_id" to CHANNEL_ID,
                        "service_id" to SERVICE_ID,
                        "comment_scene" to COMMENT_SCENE,
                        "hotspot_id" to HOTSPOT_ID,
                        "aweme_id" to awemeId,
                    ),
                )
            val observable =
                method.invoke(api, *args)
                    ?: return@runCatching item.put("ok", false).put("error", "接口返回为空")
            val response =
                awaitObservable(observable)
                    ?: return@runCatching item.put("ok", false).put("error", "请求无响应（超时）")
            val code = (Reflect.field(response, "status_code") as? Number)?.toInt() ?: 0
            if (code != 0) {
                item.put("ok", false).put("error", Reflect.field(response, "status_msg") as? String ?: "status_code=$code")
            } else {
                item.put("ok", true)
            }
        }.getOrElse { item.put("ok", false).put("error", (it.cause ?: it).toString()) }
    }

    /** 按参数上的表单字段名生成实参（未赋值的引用类型传 null，基础类型补零）。 */
    private fun buildArgs(
        method: Method,
        values: Map<String, Any?>,
    ): Array<Any?> {
        val args = arrayOfNulls<Any?>(method.parameterTypes.size)
        method.parameterAnnotations.forEachIndexed { index, annotations ->
            fieldName(annotations)?.let { name -> args[index] = values[name] }
        }
        method.parameterTypes.forEachIndexed { index, type ->
            if (args[index] != null) return@forEachIndexed
            args[index] =
                when (type) {
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
        }
        return args
    }

    /** 阻塞等待 Observable 的下一个结果 */
    private fun awaitObservable(observable: Any): Any? {
        val latch = CountDownLatch(1)
        val result = arrayOfNulls<Any>(1)
        val failure = arrayOfNulls<Throwable>(1)

        val subscribe =
            observable.javaClass.methods.firstOrNull { it.name == "subscribe" && it.parameterCount == 2 }
                ?: observable.javaClass.methods.firstOrNull { it.name == "subscribe" && it.parameterCount == 1 }
                ?: throw IllegalStateException("Observable 订阅方法未找到")

        val consumerClass =
            Class.forName("io.reactivex.functions.Consumer", false, observable.javaClass.classLoader)
        val onNext =
            java.lang.reflect.Proxy.newProxyInstance(consumerClass.classLoader, arrayOf(consumerClass)) { _, _, a ->
                result[0] = a?.firstOrNull()
                latch.countDown()
                null
            }
        val onError =
            java.lang.reflect.Proxy.newProxyInstance(consumerClass.classLoader, arrayOf(consumerClass)) { _, _, a ->
                failure[0] = a?.firstOrNull() as? Throwable
                latch.countDown()
                null
            }

        if (subscribe.parameterCount == 2) {
            subscribe.invoke(observable, onNext, onError)
        } else {
            subscribe.invoke(observable, onNext)
        }

        if (!latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)) {
            ModLog.w("评论删除：等待超时")
            return null
        }
        failure[0]?.let { throw it }
        return result[0]
    }

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
