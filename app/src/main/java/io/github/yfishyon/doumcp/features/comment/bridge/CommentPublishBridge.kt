package io.github.yfishyon.doumcp.features.comment.bridge

import io.github.yfishyon.doumcp.core.HostRuntime
import io.github.yfishyon.doumcp.core.ModLog
import io.github.yfishyon.doumcp.core.Reflect
import io.github.yfishyon.doumcp.features.account.bridge.AccountBridge
import io.github.yfishyon.doumcp.features.comment.resolver.CommentResolver
import org.json.JSONObject
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 一条 @提及：文本里的「@昵称」会被标为提及。 */
data class Mention(
    val uid: String,
    val secUid: String,
    val nickname: String,
)

/**
 * 评论发布桥。
 *
 * 走宿主自己的发布接口（Retrofit 接口，端点在注解里写死），实参按参数上的
 * 表单字段名填充：只给业务字段赋值，其余留空——Retrofit 会跳过空值字段，
 * 服务端收到的字段与宿主自身发评论时一致。
 */
object CommentPublishBridge {
    private const val FIELD_ANNOTATION = "retrofit2.http.Field"
    private const val TEXT_EXTRA_STRUCT = "com.ss.android.ugc.aweme.model.TextExtraStruct"
    private const val GSON_UTIL = "com.ss.android.ugc.aweme.utils.GsonUtil"

    /** 提及类型（话题标签是 1） */
    private const val TYPE_MENTION = 0

    private const val EMPTY_TEXT_EXTRA = "[]"
    private const val TIMEOUT_SEC = 30L

    /** 宿主发布参数对象的默认值（界面上下文未覆盖时即这些值）。 */
    private val DEFAULT_FIELDS =
        mapOf<String, Any?>(
            "is_self_see" to 0,
            "need_risk_check" to 1,
            "publish_scene" to "unknown",
            "is_commerce" to "0",
            "action_type" to 0,
            "enter_from" to "homepage_hot",
            "from_search_keyword" to "",
        )

    /**
     * 发表评论（阻塞走网络，调用方负责后台线程）。
     *
     * @param awemeId 作品 ID
     * @param text 评论文本（云表情直接用 [表情名] 语法）
     * @param replyCommentId 母评论 ID（回复一级评论时传；楼中楼时传根评论 ID）
     * @param replyToReplyId 子评论 ID（楼中楼回复子评论时传）
     * @param replyToUid 被回复评论的作者 uid（决定「回复 @某人」的展示与通知）
     * @param replyUid 被回复评论自身所回复的用户 uid
     * @param mentions @ 提及的用户
     */
    fun postCommentJson(
        awemeId: String,
        text: String,
        replyCommentId: String = "",
        replyToReplyId: String = "",
        replyToUid: String = "",
        replyUid: String = "",
        mentions: List<Mention> = emptyList(),
    ): String {
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录，无法发评论")
        if (awemeId.isBlank() || text.isBlank()) return errorJson("awemeId 与 text 不能为空")

        val api = CommentResolver.api() ?: return errorJson("评论接口创建失败")
        val publish = CommentResolver.resolvePublishMethod() ?: return errorJson("评论发布方法未定位")

        val args =
            runCatching {
                buildArgs(
                    publish,
                    linkedMapOf(
                        "aweme_id" to awemeId,
                        "text" to text,
                        "reply_id" to replyCommentId.ifEmpty { null },
                        "reply_to_reply_id" to replyToReplyId.ifEmpty { null },
                        "reply_uid" to replyUid.ifEmpty { null },
                        "reply_to_reply_uid" to replyToUid.ifEmpty { null },
                        "text_extra" to (buildMentions(text, mentions) ?: EMPTY_TEXT_EXTRA),
                    ) + DEFAULT_FIELDS,
                )
            }.getOrElse { return errorJson("发布参数构建失败: ${it.cause ?: it}") }

        val observable =
            runCatching { publish.invoke(api, *args) }
                .getOrElse { return errorJson("发布调用失败: ${it.cause ?: it}") }
                ?: return errorJson("发布返回为空")

        val response =
            runCatching { awaitObservable(observable) }
                .getOrElse { return errorJson("发布请求失败: ${it.cause ?: it}") }
                ?: return errorJson("发布无响应（超时）")

        val statusCode = (Reflect.field(response, "status_code") as? Number)?.toInt() ?: 0
        if (statusCode != 0) {
            val message = Reflect.field(response, "status_msg") as? String ?: ""
            return errorJson("发布失败 status_code=$statusCode $message")
        }

        val comment = Reflect.field(response, "comment")
        val result =
            JSONObject()
                .put("ok", true)
                .put("cid", comment?.let { Reflect.field(it, "cid") as? String } ?: "")
        comment
            ?.let { Reflect.field(it, "text") as? String }
            ?.takeIf { it.isNotEmpty() }
            ?.let { result.put("text", it) }
        (comment?.let { Reflect.field(it, "createTime") as? Number }?.toLong() ?: 0L)
            .takeIf { it > 0 }
            ?.let { result.put("createTime", formatTime(it)) }
        return result.toString()
    }

    /**
     * 按参数上的表单字段名生成实参。
     *
     * 未赋值的引用类型传 null（不发送该字段），基础类型补零。
     */
    private fun buildArgs(
        method: Method,
        values: Map<String, Any?>,
    ): Array<Any?> {
        val types = method.parameterTypes
        val args = arrayOfNulls<Any?>(types.size)
        val indexes = fieldIndexes(method)
        for ((name, value) in values) {
            val index = indexes[name] ?: throw IllegalStateException("接口无此字段: $name")
            args[index] = value
        }
        for (i in types.indices) {
            if (args[i] != null) continue
            args[i] =
                when (types[i]) {
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    Double::class.javaPrimitiveType -> 0.0
                    Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
        }
        return args
    }

    /** 参数下标 → 表单字段名。 */
    private fun fieldIndexes(method: Method): Map<String, Int> {
        val indexes = HashMap<String, Int>()
        method.parameterAnnotations.forEachIndexed { index, annotations ->
            val field = annotations.firstOrNull { it.annotationClass.java.name == FIELD_ANNOTATION } ?: return@forEachIndexed
            indexes[
                field.annotationClass.java
                    .getMethod("value")
                    .invoke(field) as String,
            ] = index
        }
        return indexes
    }

    /**
     * 文本提及列表 → text_extra 字段。
     *
     * 用宿主的提及模型与序列化器生成，字段名与宿主请求一致；
     * 文本里找不到「@昵称」的提及会被丢弃。
     */
    private fun buildMentions(
        text: String,
        mentions: List<Mention>,
    ): String? {
        if (mentions.isEmpty()) return null
        val structClass = HostRuntime.hostClass(TEXT_EXTRA_STRUCT)
        val items = ArrayList<Any>(mentions.size)
        for (mention in mentions) {
            val start = text.indexOf("@${mention.nickname}")
            if (start < 0) continue
            val item = structClass.getDeclaredConstructor().newInstance()
            setField(item, "start", start)
            setField(item, "end", start + 1 + mention.nickname.length)
            setField(item, "type", TYPE_MENTION)
            setField(item, "userId", mention.uid)
            setField(item, "secUid", mention.secUid)
            items.add(item)
        }
        if (items.isEmpty()) return null
        val toJson = HostRuntime.hostClass(GSON_UTIL).getMethod("toJson", Any::class.java)
        return toJson.invoke(null, items) as? String
    }

    private fun setField(
        target: Any,
        name: String,
        value: Any,
    ) {
        Reflect.fieldsOf(target.javaClass)[name]?.set(target, value)
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
            ModLog.w("评论发布：等待超时")
            return null
        }
        failure[0]?.let { throw it }
        return result[0]
    }

    private fun formatTime(seconds: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(seconds * 1000))

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
