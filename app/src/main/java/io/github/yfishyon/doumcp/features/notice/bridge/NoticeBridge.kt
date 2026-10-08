package io.github.yfishyon.doumcp.features.notice.bridge

import io.github.yfishyon.doumcp.core.HostRuntime
import io.github.yfishyon.doumcp.features.account.bridge.AccountBridge
import io.github.yfishyon.doumcp.features.notice.resolver.NoticeResolver
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Future

/**
 * 互动通知数据桥：@我的信息 / 收到的评论 / 发出的评论（以及赞、粉丝等分组）。
 *
 * 全部走抖音自己的通知接口（由 NoticeResolver 定位），复用宿主建好的 Retrofit 接口实例，
 * 登录态、公共参数与签名由宿主网络层处理。分组 ID 每次调用时按当前账号配置换算。
 *
 * 输出条目按「键值语义」提取：通知结构里各分组共用少数字段名（content/author 等），
 * 未识别的结构原样保留在 extra 里，运行时有什么给什么。
 */
object NoticeBridge {
    private const val GSON_UTIL = "com.ss.android.ugc.aweme.utils.GsonUtil"

    /** 通知分组支持的语义类型（对应通知中心的子页签）。 */
    private val GROUP_TYPES =
        mapOf(
            "at" to 2,
            "comment" to 3,
            "like" to 1,
            "fans" to 0,
            "recommend" to 8,
        )

    /**
     * 获取通知列表（@我 / 收到的评论 / 赞 / 粉丝 / 推荐）。
     *
     * @param type 分组语义名（见 [GROUP_TYPES]）
     * @param cursor 翻页游标（上页返回的 nextCursor；0 表示第一页/取最新）
     * @param count 每页条数
     */
    fun getNoticesJson(
        type: String,
        cursor: Long,
        count: Int,
    ): String {
        if (AccountBridge.getCurrentUserId() == null) return errorJson("未登录")
        val typeInt =
            GROUP_TYPES[type]
                ?: return errorJson("未知分组: $type（支持 ${GROUP_TYPES.keys.joinToString("/")}）")

        val fetchMethod =
            NoticeResolver.resolveFetchNoticeMethod()
                ?: return errorJson("通知列表接口未定位（DexKit 未就绪或特征失效）")
        val api = apiInstance(fetchMethod.declaringClass) ?: return errorJson("通知接口实例未取得")
        val group =
            resolveGroupId(typeInt)
                ?: return errorJson("分组换算失败（分组映射方法未定位）")

        // 参数表与宿主通知页一致；读列表不标记已读
        val future =
            runCatching {
                fetchMethod.invoke(
                    api,
                    cursor,
                    0L,
                    count,
                    group,
                    null,
                    0,
                    null,
                    null,
                    2,
                    "",
                    "",
                    "",
                    0,
                    "",
                    -1,
                    "",
                    0,
                    0,
                    "",
                    0,
                    0,
                    null,
                    0,
                    "",
                    0,
                    0,
                    0,
                )
            }.getOrElse { return errorJson("通知列表请求失败: ${unwrap(it)}") }
        val response =
            runCatching { (future as Future<*>).get() }
                .getOrElse { return errorJson("通知列表响应失败: ${unwrap(it)}") }
                ?: return errorJson("通知列表响应为空")

        return formatResponse(response, type)
    }

    /**
     * 获取发出的评论列表。
     *
     * @param cursor 翻页游标（上页返回的 nextCursor；0 表示第一页/取最新）
     * @param count 每页条数
     */
    fun getSentCommentsJson(
        cursor: Long,
        count: Int,
    ): String {
        if (AccountBridge.getCurrentUserId() == null) return errorJson("未登录")

        val mineMethod =
            NoticeResolver.resolveMineCommentMethod()
                ?: return errorJson("发出的评论接口未定位（DexKit 未就绪或特征失效）")
        val api = apiInstance(mineMethod.declaringClass) ?: return errorJson("通知接口实例未取得")

        val observable =
            runCatching { mineMethod.invoke(api, cursor, count) }
                .getOrElse { return errorJson("发出的评论请求失败: ${unwrap(it)}") }
        val response =
            runCatching { awaitObservable(observable) }
                .getOrElse { return errorJson("发出的评论响应失败: ${unwrap(it)}") }
                ?: return errorJson("发出的评论响应为空")

        return formatResponse(response, "sent_comment")
    }

    // ==================== 宿主结构解析 ====================

    /** 把宿主响应对象序列化后按语义提取。 */
    private fun formatResponse(
        response: Any,
        type: String,
    ): String {
        val text =
            runCatching { gsonToJson(response) }
                .getOrElse { return errorJson("响应序列化失败: $it") }
        val root =
            runCatching { JSONObject(text) }
                .getOrElse { return errorJson("响应解析失败: $it") }

        val status = root.optInt("status_code", -1)
        if (status != 0) return errorJson("服务端返回错误 status_code=$status ${root.optString("status_msg")}")

        val items = root.optJSONArray("comment_list") ?: root.optJSONArray("notice_list_v2") ?: root.optJSONArray("items") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            out.put(extractNoticeItem(item))
        }

        return JSONObject()
            .put("ok", true)
            .put("type", type)
            .put("total", root.optInt("total", out.length()))
            .put("hasMore", root.optBoolean("has_more", false))
            .put("nextCursor", root.optLong("min_time", 0L))
            .put("items", out)
            .toString()
    }

    /** 单条通知/评论的语义提取（通用键扫描，运行时有多少给什么）。 */
    private fun extractNoticeItem(item: JSONObject): JSONObject {
        val out = JSONObject()
        takeTime(item)?.let {
            out.put("time", formatTime(it))
            out.put("timestamp", it)
        }
        out.put("read", item.optBoolean("has_read", false))
        item.optString("nid").takeIf { it.isNotEmpty() }?.let { out.put("id", it) }

        val extra = JSONObject()
        for (key in item.keys()) {
            val value = item.opt(key) ?: continue
            if (value is JSONObject) {
                // 各分组的语义结构（评论/艾特/点赞通知等），抽公共字段（仅字符串值）
                for (semantic in listOf("content", "text", "title", "sender_name", "cid", "aweme_id", "sub_type")) {
                    (value.opt(semantic) as? String)?.takeIf { it.isNotEmpty() && !out.has(semanticKey(semantic)) }?.let {
                        out.put(semanticKey(semantic), it)
                    }
                }
                // 作者/对方资料：字符串名直取，对象取昵称
                for (who in listOf("author", "user")) {
                    if (out.has("nickname")) break
                    val person = value.opt(who)
                    if (person is String && person.isNotEmpty()) {
                        out.put("nickname", person)
                    } else if (person is JSONObject) {
                        person.optString("nickname").takeIf { it.isNotEmpty() }?.let { out.put("nickname", it) }
                    }
                }
                value.optJSONObject("avatar_url")?.optJSONArray("url_list")?.let { urls ->
                    urls.optString(0).takeIf { it.isNotEmpty() }?.let { out.put("avatar", it) }
                }
                // 语义结构里也可能带时间（发出的评论在 comment 里）
                if (!out.has("time")) {
                    takeTime(value)?.let {
                        out.put("time", formatTime(it))
                        out.put("timestamp", it)
                    }
                }
            } else if (value !is JSONArray) {
                when (key) {
                    "create_time", "has_read", "nid", "type", "notice_from" -> extra.put(key, value)
                }
            }
        }
        if (extra.length() > 0) out.put("extra", extra)
        return out
    }

    /** 取时间字段（秒级时间戳；0 或缺失返回 null）。 */
    private fun takeTime(target: JSONObject): Long? {
        val time = target.optLong("create_time", 0L)
        return time.takeIf { it > 0 }
    }

    private fun semanticKey(raw: String): String =
        when (raw) {
            "aweme_id" -> "awemeId"
            "sub_type" -> "subType"
            "sender_name" -> "sender"
            else -> raw
        }

    // ==================== 宿主设施 ====================

    /** 持有者类静态字段里类型即接口类的那个字段，取现成的 Retrofit 接口实例。 */
    private fun apiInstance(interfaceClass: Class<*>): Any? {
        val holderClass = NoticeResolver.resolveApiHolderClass() ?: return null
        val field =
            staticFields(holderClass).firstOrNull { it.type == interfaceClass } ?: return null
        return runCatching { field.get(null) }.getOrNull()
    }

    /** 语义类型 → 当前账号的服务端分组 ID（分组由服务端配置下发，逐次换算）。 */
    private fun resolveGroupId(typeInt: Int): Int? {
        val method = NoticeResolver.resolveGroupMapMethod() ?: return null
        return runCatching { method.invoke(null, typeInt) as? Int }.getOrNull()
    }

    private fun staticFields(clazz: Class<*>): List<Field> {
        val result = ArrayList<Field>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) {
                    field.isAccessible = true
                    result.add(field)
                }
            }
            current = current.superclass
        }
        return result
    }

    /** 宿主 rxjava Observable 阻塞取首个结果（网络在调用线程上完成）。 */
    private fun awaitObservable(observable: Any): Any? {
        val method =
            observable.javaClass.methods.firstOrNull {
                it.name == "blockingFirst" && it.parameterCount == 0
            } ?: throw IllegalStateException("Observable 缺少 blockingFirst")
        return method.invoke(observable)
    }

    /** 宿主 Gson 工具序列化（未混淆固定类名）。 */
    private fun gsonToJson(target: Any): String {
        val gsonUtil = Class.forName(GSON_UTIL, false, HostRuntime.requireClassLoader())
        val method: Method =
            gsonUtil.declaredMethods.firstOrNull {
                it.name == "toJson" && it.parameterCount == 1 && it.parameterTypes[0] == Any::class.java
            } ?: throw IllegalStateException("GsonUtil 缺少 toJson")
        return method.invoke(null, target) as String
    }

    private fun formatTime(epochSeconds: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(epochSeconds * 1000))

    private fun unwrap(throwable: Throwable): Throwable =
        if (throwable is InvocationTargetException) throwable.cause ?: throwable else throwable

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
