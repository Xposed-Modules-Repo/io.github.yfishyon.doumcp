package io.github.yfishyon.doumcp.features.comment.bridge

import io.github.yfishyon.doumcp.core.ModLog
import io.github.yfishyon.doumcp.core.Reflect
import io.github.yfishyon.doumcp.features.account.bridge.AccountBridge
import io.github.yfishyon.doumcp.features.comment.resolver.CommentStickerResolver
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 表情集桥：列出用户添加的表情集，以及某个表情集里的表情。
 *
 * 列表与明细都走宿主自己的取数入口（列表入口内部完成凭证与公共参数拼装，
 * 明细接口按资源 ID 取），表情集与表情模型都未混淆，字段即协议名——
 * 表情集里的每个表情带名字，推荐列表里的表情没有名字。
 */
object CommentStickerSetBridge {
    /** 场景名：表情商店（宿主按该场景区分商店与其它入口） */
    private const val SCENE_STORE = "STORE"

    private const val RESOURCES = "com.ss.android.ugc.aweme.emoji.emojichoose.model.Resources"

    private const val TIMEOUT_MS = 20_000L

    /**
     * 表情集清单，或某个表情集的表情（阻塞走网络，调用方负责后台线程）。
     *
     * @param setId 表情集 ID；为空列清单，非空列该表情集里的表情
     */
    fun getSetsJson(setId: String): String {
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录")
        return if (setId.isBlank()) listSets() else setStickers(setId)
    }

    private fun listSets(): String {
        val method = CommentStickerResolver.resolveSetListMethod() ?: return errorJson("表情集列表入口未定位")
        val response =
            runCatching { method.invoke(null, SCENE_STORE) }
                .getOrElse {
                    ModLog.e("表情集列表：请求异常", it.cause ?: it)
                    return errorJson("表情集列表获取失败")
                } ?: return errorJson("表情集列表获取失败")

        val sets = (Reflect.field(response, "storeList") as? List<*>)?.filterNotNull() ?: emptyList()
        val array = JSONArray()
        for (set in sets) {
            val item =
                JSONObject()
                    .put("id", Reflect.field(set, "id")?.toString() ?: "")
            (Reflect.field(set, "displayName") as? String)
                ?.takeIf { it.isNotEmpty() }
                ?.let { item.put("name", it) }
            (Reflect.field(set, "description") as? String)
                ?.takeIf { it.isNotEmpty() }
                ?.let { item.put("description", it) }
            (Reflect.field(set, "stickerType") as? Number)?.toInt()?.let { item.put("stickerType", it) }
            (Reflect.field(set, "stickerList") as? List<*>)
                ?.takeIf { it.isNotEmpty() }
                ?.let { item.put("count", it.size) }
            array.put(item)
        }
        return JSONObject()
            .put("ok", true)
            .put("count", array.length())
            .put("sets", array)
            .toString()
    }

    private fun setStickers(setId: String): String {
        val method = CommentStickerResolver.resolveDetailMethod() ?: return errorJson("表情集明细方法未定位")
        val api = CommentStickerResolver.api(method) ?: return errorJson("表情接口创建失败")
        // 资源 ID 按宿主自己的写法传：单元素列表的字面量形式
        val future =
            runCatching { method.invoke(api, "[$setId]") }
                .getOrElse {
                    ModLog.e("表情集明细：请求异常", it.cause ?: it)
                    return errorJson("表情集明细获取失败")
                } ?: return errorJson("表情集明细获取失败")

        val response =
            runCatching { awaitFuture(future) }
                .getOrElse {
                    ModLog.e("表情集明细：请求失败", it.cause ?: it)
                    return errorJson("表情集明细获取失败")
                } ?: return errorJson("表情集明细获取失败")

        val wrapper =
            (Reflect.field(response, "resourcesList") as? List<*>)
                ?.firstOrNull()
                ?: return errorJson("表情集不存在: $setId")
        // 明细响应里每项是"表情集 + 表情列表"的包装对象（字段名混淆），按类型分别取
        val set = resourcesOf(wrapper) ?: return errorJson("表情集不存在: $setId")
        val emojis = stickersOf(wrapper)
        StickerCatalog.remember(emojis)

        val array = JSONArray()
        emojis.forEachIndexed { index, emoji -> array.put(CommentStickerBridge.stickerJson(emoji, index)) }

        val setJson = JSONObject().put("id", Reflect.field(set, "id")?.toString() ?: setId)
        (Reflect.field(set, "displayName") as? String)
            ?.takeIf { it.isNotEmpty() }
            ?.let { setJson.put("name", it) }
        return JSONObject()
            .put("ok", true)
            .put("set", setJson)
            .put("count", array.length())
            .put("stickers", array)
            .toString()
    }

    /** 包装对象里的表情集（包装类未混淆前的取法：按类型取无参取值方法）。 */
    private fun resourcesOf(wrapper: Any): Any? {
        val resourcesClass = Class.forName(RESOURCES, false, wrapper.javaClass.classLoader)
        val getter =
            wrapper.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && it.returnType == resourcesClass
            } ?: return null
        return runCatching { getter.invoke(wrapper) }.getOrNull()
    }

    /** 包装对象里的表情列表（按类型取无参返回列表的方法）。 */
    private fun stickersOf(wrapper: Any): List<Any> {
        val getter =
            wrapper.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && it.returnType == List::class.java
            } ?: return emptyList()
        return runCatching {
            (getter.invoke(wrapper) as? List<*>)?.filterNotNull()
        }.getOrNull() ?: emptyList()
    }

    /** 阻塞等待宿主的异步结果（宿主用 ListenableFuture）。 */
    private fun awaitFuture(future: Any): Any? =
        Class
            .forName("java.util.concurrent.Future", false, future.javaClass.classLoader)
            .getMethod("get", Long::class.javaPrimitiveType, TimeUnit::class.java)
            .invoke(future, TIMEOUT_MS, TimeUnit.MILLISECONDS)

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
