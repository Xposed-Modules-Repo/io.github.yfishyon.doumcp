package io.github.yfishyon.doumcp.features.comment.bridge

import io.github.yfishyon.doumcp.core.DexKitSupport
import io.github.yfishyon.doumcp.core.HostRuntime
import io.github.yfishyon.doumcp.core.ModLog
import io.github.yfishyon.doumcp.core.Reflect
import io.github.yfishyon.doumcp.features.comment.resolver.CommentResolver
import io.github.yfishyon.doumcp.features.video.resolver.VideoResolver
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 评论数据桥：给定视频 ID 拉取一级评论 + 楼中楼回复。
 *
 * - 一级评论：评论分页请求类（未混淆类名）上的 4 参执行方法
 *   （请求上下文, cursor, count, 是否刷新 → Observable），阻塞订阅取结果
 * - 楼中楼：挂起方法经 KMP 网络桥发请求，请求参数对象按"类型 + 声明顺序"定位字段
 * - 字段语义：列表模型与评论模型带语义 getter；媒体地址字段名混淆，按类型取 UrlModel
 */
object CommentBridge {
    private const val COMMENT_ITEM_LIST = "com.ss.android.ugc.aweme.comment.model.CommentItemList"
    private const val PAGE_NET_REQUEST = "com.ss.android.ugc.aweme.comment.data.list.CommentListPageNetRequest"

    /** 评论正文相关字段（字段名是服务端协议名，语义稳定）。 */
    private val COMMENT_FIELDS =
        listOf(
            "cid",
            "text",
            "diggCount",
            "createTime",
            "ipLabel",
            "replyCommentTotal",
            "isAuthorDigged",
            "replyId",
            "replyToReplyId",
            "replyToUserId",
            "replyToUserName",
            "rootCommentId",
        )

    /**
     * 获取视频一级评论列表（阻塞走网络，调用方负责后台线程）。
     *
     * @param awemeId 视频 ID
     * @param cursor 分页游标（首页传 0，翻页传上页返回的 nextCursor）
     * @param count 每页条数
     */
    fun getCommentsJson(
        awemeId: String,
        cursor: Long,
        count: Int,
        keyword: String = "",
    ): String {
        if (awemeId.isBlank()) return errorJson("视频 ID 为空")

        val commentItemList =
            fetchCommentListNetwork(awemeId, cursor, count)
                ?: return errorJson("评论列表获取失败")

        return runCatching { extractCommentList(commentItemList, keyword) }
            .getOrElse { errorJson("评论数据解析失败: $it") }
    }

    /**
     * 获取某条一级评论的楼中楼回复列表（阻塞走网络，调用方负责后台线程）。
     *
     * @param awemeId 视频 ID
     * @param commentId 父评论 ID
     * @param cursor 分页游标（首页传 0，翻页传上页返回的 nextCursor）
     * @param count 每页条数
     */
    fun getCommentRepliesJson(
        awemeId: String,
        commentId: String,
        cursor: Long,
        count: Int,
        keyword: String = "",
    ): String {
        if (awemeId.isBlank()) return errorJson("视频 ID 为空")
        if (commentId.isBlank()) return errorJson("评论 ID 为空")

        val commentItemList =
            fetchReplyListNetwork(awemeId, commentId, cursor, count)
                ?: return errorJson("回复列表获取失败")

        return runCatching { extractCommentList(commentItemList, keyword) }
            .getOrElse { errorJson("回复数据解析失败: $it") }
    }

    // ==================== 一级评论网络请求 ====================

    /**
     * 一级评论请求。
     *
     * 构造请求上下文（构造器参数是一个字段袋，内含 aweme_id），调分页请求类的
     * 4 参执行方法（内部一次完成参数构建与请求），阻塞订阅 Observable，
     * 真实列表在响应的 data 字段。
     *
     * 注意不能拆成"构建参数 + 执行"两步调用：执行步骤会把传入参数强转成
     * 内部上下文类型导致类型转换失败——必须走单次入口。
     */
    private fun fetchCommentListNetwork(
        awemeId: String,
        cursor: Long,
        count: Int,
    ): Any? =
        runCatching {
            val pageReqCls = Class.forName(PAGE_NET_REQUEST, false, HostRuntime.requireClassLoader())
            val pageReq = pageReqCls.getDeclaredConstructor().newInstance()

            // 4 参执行方法：(请求上下文, cursor(long), count(int), 是否刷新(boolean)) → Observable
            val fetchMethod =
                pageReqCls.declaredMethods.firstOrNull { m ->
                    m.parameterTypes.size == 4 &&
                        m.parameterTypes[1] == Long::class.javaPrimitiveType &&
                        m.parameterTypes[2] == Int::class.javaPrimitiveType &&
                        m.parameterTypes[3] == Boolean::class.javaPrimitiveType &&
                        m.returnType.name.contains("Observable")
                } ?: run {
                    ModLog.e("一级评论：执行方法未定位（4 参返回 Observable）")
                    return null
                }

            val context =
                buildRequestContext(fetchMethod.parameterTypes[0], awemeId)
                    ?: run {
                        ModLog.e("一级评论：请求上下文构造失败")
                        return null
                    }

            val observable = fetchMethod.invoke(pageReq, context, cursor, count, false) ?: return null
            val baseResponse = blockAndGet(observable) ?: return null
            Reflect.field(baseResponse, "data")
        }.getOrElse {
            ModLog.e("一级评论网络请求异常", it)
            null
        }

    /**
     * 构造请求上下文。
     *
     * 其单参构造器接收一个字段袋对象，把字段袋的全部 String 字段设为
     * awemeId（其中目标字段即视频 ID）。
     */
    private fun buildRequestContext(
        contextType: Class<*>,
        awemeId: String,
    ): Any? {
        val bagCtor =
            contextType.declaredConstructors.firstOrNull { it.parameterTypes.size == 1 }
                ?: return null
        val bag = bagCtor.parameterTypes[0].getDeclaredConstructor().newInstance()
        setAllStringFields(bag, awemeId)
        return bagCtor.newInstance(bag)
    }

    // ==================== 楼中楼网络请求 ====================

    /**
     * 楼中楼请求：挂起方法接收一个请求参数对象，内部 POST 到子评论接口。
     *
     * 参数对象关键字段按"类型 + 声明顺序"定位：
     * 第 1 个 String = 评论 ID、第 1 个 Long = cursor、第 1 个 Integer = count、
     * 含视频模型字段的包装对象 = 视频上下文（item_id / 鉴权信息从它取）。
     */
    private fun fetchReplyListNetwork(
        awemeId: String,
        commentId: String,
        cursor: Long,
        count: Int,
    ): Any? =
        runCatching {
            val fetcher =
                CommentResolver.resolveReplyFetcher()
                    ?: return null.also { ModLog.e("子评论：拉取方法未定位") }

            val paramType = fetcher.parameterTypes.first()
            val params = paramType.getDeclaredConstructor().newInstance()

            var commentIdSet = false
            var cursorSet = false
            var countSet = false
            var contextField: java.lang.reflect.Field? = null
            for (field in Reflect.fieldsOf(paramType).values) {
                when {
                    !commentIdSet && field.type == String::class.java -> {
                        field.set(params, commentId)
                        commentIdSet = true
                    }

                    !cursorSet && field.type == java.lang.Long::class.java -> {
                        field.set(params, java.lang.Long.valueOf(cursor))
                        cursorSet = true
                    }

                    !countSet && field.type == java.lang.Integer::class.java -> {
                        field.set(params, java.lang.Integer.valueOf(count))
                        countSet = true
                    }

                    contextField == null && Reflect.fieldsOf(field.type).values.any { it.type.name == DexKitSupport.FEED_AWEME } -> {
                        contextField = field
                    }
                }
            }
            if (!commentIdSet || !cursorSet || !countSet) {
                ModLog.e("子评论：参数字段定位不全 id=$commentIdSet cursor=$cursorSet count=$countSet")
                return null
            }

            // 视频上下文：包装对象里塞详情接口拿到的真实视频对象
            if (contextField != null) {
                val wrapper = contextField.type.getDeclaredConstructor().newInstance()
                val awemeField =
                    Reflect
                        .fieldsOf(wrapper.javaClass)
                        .values
                        .first { it.type.name == DexKitSupport.FEED_AWEME }
                val aweme = fetchAweme(awemeId)
                if (aweme != null) {
                    awemeField.set(wrapper, aweme)
                    contextField.set(params, wrapper)
                } else {
                    ModLog.w("子评论：视频对象获取失败，缺视频上下文继续尝试")
                }
            }

            // 挂起调用（KMP 网络桥）。拉取方法是实例方法（无状态），先构造实例
            val fetcherInstance =
                if (Modifier.isStatic(fetcher.modifiers)) {
                    null
                } else {
                    fetcher.declaringClass.getDeclaredConstructor().newInstance()
                }
            val raw =
                invokeSuspend(fetcher, fetcherInstance, listOf(params))
                    ?: return null.also { ModLog.e("子评论：挂起调用无结果") }
            // resumeWith 直接给评论列表对象；失败时为空
            if (raw.javaClass.name != COMMENT_ITEM_LIST) {
                ModLog.e("子评论：结果类型异常（${raw.javaClass.name}）")
                return null
            }
            raw
        }.getOrElse {
            ModLog.e("子评论网络请求异常", it)
            null
        }

    /** 视频上下文缓存：同一视频翻页复用，省掉每页一次的详情请求。 */
    private val awemeContextCache =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, Any>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, Any>): Boolean = size > 32
            },
        )

    /** 通过视频详情接口拿真实视频对象（子评论请求的上下文）。 */
    private fun fetchAweme(awemeId: String): Any? {
        awemeContextCache[awemeId]?.let { return it }
        val method = VideoResolver.resolveDetailMethod() ?: return null
        val aweme = runCatching { method.invoke(null, awemeId, "") }.getOrNull() ?: return null
        awemeContextCache[awemeId] = aweme
        return aweme
    }

    /**
     * 阻塞调用宿主挂起方法（签名：(..., Continuation) → Object）。
     *
     * 宿主与模块各自打包了 Kotlin 运行时，模块侧的 Continuation 实现过不了
     * 宿主方法的参数类型检查——必须动态代理**宿主 classloader** 的 Continuation，
     * 协程上下文取宿主侧的空上下文。挂起标记不能用引用或类名比较
     * （宿主侧被 R8 改名），改用枚举 name 属性判定。
     */
    private fun invokeSuspend(
        method: java.lang.reflect.Method,
        instance: Any?,
        args: List<Any?>,
    ): Any? {
        val classLoader = method.declaringClass.classLoader
        val continuationType = method.parameterTypes.last()

        val hostEmptyContext =
            runCatching {
                classLoader
                    .loadClass("kotlin.coroutines.EmptyCoroutineContext")
                    .getField("INSTANCE")
                    .get(null)
            }.getOrNull()

        var outcome: Any? = null
        val latch = java.util.concurrent.CountDownLatch(1)

        val continuation =
            java.lang.reflect.Proxy.newProxyInstance(
                continuationType.classLoader,
                arrayOf(continuationType),
            ) { _, m, a ->
                when (m.name) {
                    "resumeWith" -> {
                        outcome = a[0]
                        latch.countDown()
                        null
                    }

                    "getContext" -> {
                        hostEmptyContext
                    }

                    else -> {
                        null
                    }
                }
            }

        val direct =
            runCatching { method.invoke(instance, *args.toTypedArray(), continuation) }
                .getOrElse {
                    ModLog.e("子评论：挂起方法调用异常", it.cause ?: it)
                    return null
                }
        val suspended = direct is Enum<*> && direct.name == "COROUTINE_SUSPENDED"
        if (!suspended) return direct

        if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
            ModLog.e("子评论：挂起调用超时")
            return null
        }
        return outcome
    }

    // ==================== 反射工具 ====================

    /** 设置对象及其父类所有 String 字段。 */
    private fun setAllStringFields(
        target: Any,
        value: String,
    ) {
        var current: Class<*>? = target.javaClass
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                if (field.type == String::class.java) {
                    field.isAccessible = true
                    runCatching { field.set(target, value) }
                }
            }
            current = current.superclass
        }
    }

    /** 阻塞订阅 RxJava Observable 的下一个结果（最多 30 秒）。 */
    private fun blockAndGet(observable: Any): Any? {
        val latch = java.util.concurrent.CountDownLatch(1)
        val result = arrayOfNulls<Any>(1)

        val subscribeMethod =
            observable.javaClass.methods.firstOrNull { m ->
                m.name == "subscribe" && m.parameterTypes.size == 2
            } ?: observable.javaClass.methods.firstOrNull { m ->
                m.name == "subscribe" && m.parameterTypes.size == 1
            } ?: return null

        val consumerClass =
            Class.forName(
                "io.reactivex.functions.Consumer",
                false,
                observable.javaClass.classLoader,
            )
        val onNext =
            java.lang.reflect.Proxy.newProxyInstance(
                consumerClass.classLoader,
                arrayOf(consumerClass),
            ) { _, _, args ->
                result[0] = args[0]
                latch.countDown()
                null
            }
        val onError =
            java.lang.reflect.Proxy.newProxyInstance(
                consumerClass.classLoader,
                arrayOf(consumerClass),
            ) { _, _, args ->
                ModLog.e("Observable error: ${args[0]}")
                latch.countDown()
                null
            }

        if (subscribeMethod.parameterTypes.size == 2) {
            subscribeMethod.invoke(observable, onNext, onError)
        } else {
            subscribeMethod.invoke(observable, onNext)
        }

        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        return result[0]
    }

    // ==================== 数据提取 ====================

    /** 从评论列表模型提取 JSON（翻页信息 + 评论数组）。 */
    private fun extractCommentList(
        commentItemList: Any,
        keyword: String,
    ): String {
        val json = JSONObject()
        json.put("ok", true)

        // 翻页信息（语义字段）
        Reflect.field(commentItemList, "cursor")?.let { json.put("nextCursor", it.toString().toLongOrNull() ?: it) }
        Reflect.field(commentItemList, "hasMore")?.let { json.put("hasMore", it) }

        val items = Reflect.field(commentItemList, "items") as? List<*>
        val arr = JSONArray()
        items?.forEach { item ->
            item
                ?.let { runCatching { extractComment(it) }.getOrNull() }
                ?.let { json ->
                    if (keyword.isEmpty() || json.optString("text").contains(keyword, ignoreCase = true)) {
                        arr.put(json)
                    }
                }
        }
        json.put("comments", arr)
        return json.toString()
    }

    /**
     * 提取单条评论为 JSON。
     * 模型字段名是语义的（服务端协议名），按名读取；只取评论数据本身的字段。
     */
    private fun extractComment(comment: Any): JSONObject {
        val json = JSONObject()
        for (name in COMMENT_FIELDS) {
            when (val value = Reflect.field(comment, name)) {
                null -> {
                    Unit
                }

                is String -> {
                    if (value.isNotEmpty()) json.put(name, value)
                }

                is Number -> {
                    if (name == "createTime") {
                        json.put(name, formatTime(value.toLong()))
                    } else {
                        json.put(name, value)
                    }
                }

                is Boolean -> {
                    json.put(name, value)
                }
            }
        }

        // 图片（地址字段名混淆，按字段类型取）、表情
        extractImageUrls(Reflect.field(comment, "imageList"))?.let { json.put("imageList", it) }
        Reflect.field(comment, "emoji")?.let { emoji ->
            firstUrlModelUrls(emoji)?.let { json.put("emoji", it) }
        }

        // 作者身份（扁平输出）
        Reflect.field(comment, "commentUser")?.let { author ->
            for (name in listOf("nickname", "uid", "secUid")) {
                (Reflect.field(author, name) as? String)?.takeIf { it.isNotEmpty() }?.let { json.put(name, it) }
            }
        }

        // 被 @ 的用户
        extractMentionedUsers(comment)?.let { json.put("mentionedUsers", it) }

        // 内嵌的子回复预览
        (Reflect.field(comment, "replyComments") as? List<*>)?.takeIf { it.isNotEmpty() }?.let { replies ->
            val arr = JSONArray()
            for (reply in replies) reply?.let { arr.put(extractComment(it)) }
            json.put("replyComments", arr)
        }
        return json
    }

    /**
     * 评论图片的 URL 列表。
     *
     * 图片结构体的地址字段名是混淆的，按**字段类型**识别
     * ——类型是 UrlModel 且 urlList 非空的那个字段就是图片地址。
     */
    private fun extractImageUrls(images: Any?): JSONArray? {
        val list = images as? List<*> ?: return null
        val arr = JSONArray()
        for (image in list) {
            image ?: continue
            firstUrlModelUrls(image)?.let { arr.put(it) }
        }
        return arr.takeIf { it.length() > 0 }
    }

    /** 对象里第一个非空 UrlModel 字段的完整 urlList（字段表走缓存）。 */
    private fun firstUrlModelUrls(holder: Any): JSONArray? {
        for (field in Reflect.fieldsOf(holder.javaClass).values) {
            if (!field.type.name.endsWith("UrlModel")) continue
            val media = runCatching { field.get(holder) }.getOrNull() ?: continue
            val urls = Reflect.field(media, "urlList") as? List<*> ?: continue
            if (urls.isEmpty()) continue
            val arr = JSONArray()
            for (url in urls) arr.put(url.toString())
            return arr
        }
        return null
    }

    /**
     * 评论里被 @ 的用户。
     *
     * textExtra 列表里 type=0 的项是 @用户（type=1 是话题标签），
     * 项内含被 @ 用户的 id 与名字。
     */
    private fun extractMentionedUsers(comment: Any): JSONArray? {
        val extras = Reflect.field(comment, "textExtra") as? List<*> ?: return null
        val arr = JSONArray()
        for (extra in extras) {
            extra ?: continue
            if ((Reflect.field(extra, "type") as? Number)?.toInt() != 0) continue
            val item = JSONObject()
            for (name in listOf("userId", "secUid", "userUniqueId", "nickname")) {
                (Reflect.field(extra, name) as? String)?.takeIf { it.isNotEmpty() }?.let { item.put(name, it) }
            }
            if (item.length() > 0) arr.put(item)
        }
        return arr.takeIf { it.length() > 0 }
    }

    /** unix 秒 → 年月日时分秒。 */
    private fun formatTime(seconds: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(seconds * 1000))

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
