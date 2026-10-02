package io.github.yfishyon.doumcp.features.im.bridge

import io.github.yfishyon.doumcp.core.HostRuntime
import io.github.yfishyon.doumcp.core.Reflect
import io.github.yfishyon.doumcp.features.account.bridge.AccountBridge
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * IM 能力桥：会话列表 + 火花（亲密度火焰）信息。
 *
 * 实例链（全部未混淆语义方法）：
 * `SDKManager.INSTANCE.getImSdkClient().getIIMSdkModelService().getConversationListModel()`
 * → `getAllConversationSync()` 返回全部会话。
 *
 * 会话对象同样是语义 getter（getConversationId / getCoreInfo / getUnreadCount / …），
 * 直接调用。火花数据源（逆向确认）：会话 coreInfo.ext 里的
 * `a:consecutive_chat_data` JSON（flame_infos / effect_list / consecutive_relation_info），
 * 小火人则是 ext 里的 pet 相关键。
 */
object ImBridge {
    private const val SDK_MANAGER = "com.bytedance.ies.im.core.sdk.SDKManager"

    /** 会话 ext 里的火花数据键与小火人键（服务端协议字符串，语义稳定）。 */
    private const val EXT_KEY_SPARK = "a:consecutive_chat_data"
    private val EXT_KEYS_PET =
        listOf(
            "a:pet_elf_avatar",
            "a:pet_elf_parenting",
            "a:pet_v2_closeness",
            "a:pet_v2_deploy_status",
        )

    /** 对方资料拉取总预算：超时后剩余单聊不再拉取（资料字段留空），避免冷缓存下整体耗时失控 */
    private const val PROFILE_BUDGET_MS = 15_000L

    /**
     * 获取当前账号的会话列表（阻塞走网络/数据库，调用方负责后台线程）。
     *
     * @param limit 最多返回的会话数
     * @param withSpark 是否附带火花/小火人状态
     */
    fun getConversationsJson(
        limit: Int,
        withSpark: Boolean,
        keyword: String = "",
        isGroup: Boolean? = null,
    ): String {
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录")

        val listModel =
            resolveConversationListModel()
                ?: return errorJson("会话列表模型获取失败（IM 未初始化或链路变动）")

        val conversations =
            Reflect.getter(listModel, "getAllConversationSync") as? List<*>
                ?: return errorJson("会话列表读取失败")

        val ownerUid = currentUid()
        val profileDeadline = System.currentTimeMillis() + PROFILE_BUDGET_MS
        val result = JSONArray()
        for (conv in conversations) {
            conv ?: continue
            if (result.length() >= limit) break
            // 群/单聊先过滤：类型读取不涉及对方资料，不匹配的会话零成本跳过
            if (isGroup != null) {
                val convIsGroup = conversationIsGroup(conv) ?: continue
                if (convIsGroup != isGroup) continue
            }
            runCatching { extractConversation(conv, ownerUid, withSpark, profileDeadline) }
                .getOrNull()
                ?.let { json ->
                    if (matchesConversation(json, keyword, isGroup)) result.put(json)
                }
        }

        return JSONObject()
            .put("ok", true)
            .put("total", conversations.size)
            .put("conversations", result)
            .toString()
    }

    /** SDKManager 语义链获取会话列表模型（宿主未就绪/链路变动时返回 null）。 */
    internal fun resolveConversationListModel(): Any? =
        runCatching {
            val sdkClass = Class.forName(SDK_MANAGER, false, HostRuntime.requireClassLoader())
            val instance = sdkClass.getDeclaredField("INSTANCE").get(null)
            val client = Reflect.getter(instance, "getImSdkClient") ?: return null
            val service = Reflect.getter(client, "getIIMSdkModelService") ?: return null
            Reflect.getter(service, "getConversationListModel")
        }.getOrNull()

    /** 单个会话 → JSON（身份 + 状态 + 最后消息 + 火花）。profileDeadline 限定对方资料的网络拉取窗口。 */
    private fun extractConversation(
        conv: Any,
        ownerUid: String?,
        withSpark: Boolean,
        profileDeadline: Long,
    ): JSONObject {
        val json = JSONObject()
        json.put("conversationId", Reflect.getter(conv, "getConversationId")?.toString() ?: "")
        json.put("conversationShortId", readLong(conv, "getConversationShortId"))
        val type = (Reflect.getter(conv, "getConversationType") as? Number)?.toInt() ?: 0
        json.put("conversationType", type)
        val isGroup = (Reflect.getter(conv, "isGroupChat") as? Boolean) ?: (type == 2)
        json.put("isGroup", isGroup)

        // 对方身份（单聊取对方资料：uid/昵称/备注/secUid/头像）。
        // 成员列表第一个非本人 uid 就是对方（模型上没有对方 ID 的直接访问器）
        if (!isGroup) {
            val peer =
                (Reflect.getter(conv, "getMemberIds") as? List<*>)
                    ?.mapNotNull { it?.toString() }
                    ?.firstOrNull { it != ownerUid && it.isNotBlank() && it != "0" }
                    ?: ""
            json.put("peerUid", peer)
            peer.takeIf { it.isNotEmpty() }?.let { uid ->
                if (System.currentTimeMillis() < profileDeadline) {
                    ImUserBridge.resolve(uid)?.let { user ->
                        json.put("name", user.optString("nickname"))
                        (user.optString("remarkName")).takeIf { it.isNotEmpty() }?.let { json.put("remarkName", it) }
                        (user.optString("secUid")).takeIf { it.isNotEmpty() }?.let { json.put("peerSecUid", it) }
                        (user.optString("avatarUrl")).takeIf { it.isNotEmpty() }?.let { json.put("peerAvatar", it) }
                    }
                }
            }
        }

        // 会话信息（名字对群聊有效/置顶/免打扰/未读）
        Reflect.getter(conv, "getCoreInfo")?.let { coreInfo ->
            if (isGroup) {
                json.put("name", Reflect.getter(coreInfo, "getName")?.toString() ?: "")
            }
            if (!json.has("peerAvatar")) {
                (Reflect.getter(coreInfo, "getIcon") as? String)?.let { json.put("peerAvatar", it) }
            }
        }
        (Reflect.getter(conv, "getUnreadCount") as? Number)?.let { json.put("unreadCount", it.toInt()) }
        (Reflect.getter(conv, "getMuted") as? Boolean)?.let { json.put("muted", it) }
        (Reflect.getter(conv, "getStickTop") as? Boolean)?.let { json.put("stickTop", it) }

        // 最后一条消息（时间 + 摘要文本）
        (Reflect.getter(conv, "getLastMessageCreateTime") as? Number)?.takeIf { it.toLong() > 0 }?.let {
            json.put("lastMessageTime", formatTime(it.toLong() / 1000))
        }
        Reflect.getter(conv, "getLastHintMessage")?.let { hint ->
            val text = hint?.toString()?.takeIf { it.isNotEmpty() && it != "null" }
            if (text != null) json.put("lastMessage", text)
        }

        // 会话扩展（服务端 ext / 本地 localExt）
        mapToJson(Reflect.getter(conv, "getExt"))?.let { json.put("ext", it) }
        mapToJson(Reflect.getter(conv, "getLocalExt"))?.let { json.put("localExt", it) }

        if (withSpark) {
            extractSpark(conv)?.let { json.put("spark", it) }
        }
        return json
    }

    /**
     * 解析火花/小火人状态。
     *
     * 数据源：coreInfo.ext["a:consecutive_chat_data"]，
     * 结构 {flame_infos:[{start,end,state,days,text,level}], effect_list:[{kind}],
     * consecutive_relation_info:{type}, biz_flame_infos:[引导条目]}。
     */
    private fun extractSpark(conv: Any): JSONObject? {
        val coreInfo = Reflect.getter(conv, "getCoreInfo") ?: return null
        val ext = Reflect.getter(coreInfo, "getExt") as? Map<*, *> ?: return null

        val hasPetElf = EXT_KEYS_PET.any { ext[it]?.toString()?.isNotBlank() == true }
        val raw = ext[EXT_KEY_SPARK]?.toString()
        if (raw.isNullOrBlank()) {
            // 无火花数据但有小火人
            if (!hasPetElf) return null
            return JSONObject().put("hasSpark", false).put("petElf", true)
        }

        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val out = JSONObject()
        out.put("petElf", hasPetElf)
        out.put("relationType", root.optJSONObject("consecutive_relation_info")?.optString("type") ?: "")

        // 生效中的火花段（当前时间落在 start~end 内的那个）
        val flames = root.optJSONArray("flame_infos")
        val guideFlames = root.optJSONArray("biz_flame_infos")
        val infos =
            when {
                flames != null && flames.length() > 0 -> flames

                guideFlames != null && guideFlames.length() > 0 -> guideFlames

                // "可点亮"引导
                else -> null
            }
        if (infos == null) {
            out.put("hasSpark", false)
            return out
        }
        out.put("hasSpark", flames != null && flames.length() > 0)

        val nowSec = System.currentTimeMillis() / 1000
        var current: JSONObject? = null
        for (i in 0 until infos.length()) {
            val fi = infos.optJSONObject(i) ?: continue
            if (fi.optLong("start") <= nowSec && nowSec <= fi.optLong("end")) {
                current = fi
                break
            }
        }
        current = current ?: infos.optJSONObject(0) ?: return out
        val state = current.optInt("state", -1)
        if (state >= 0) {
            out.put("days", current.optInt("days"))
            out.put("state", state)
            out.put("stateText", current.optString("text"))
            out.put("level", current.optString("level"))
            out.put("needsRenewal", state == 2 || state == 3)
        }
        // 特效（冻结/保留）
        root.optJSONArray("effect_list")?.optJSONObject(0)?.let { effect ->
            out.put("effectKind", effect.optString("kind"))
        }
        return out
    }

    /** Map<String,String> → JSON 对象（空返回 null）。 */
    private fun mapToJson(map: Any?): JSONObject? {
        val source = map as? Map<*, *> ?: return null
        val json = JSONObject()
        for ((k, v) in source) {
            k ?: continue
            json.put(k.toString(), v?.toString() ?: "")
        }
        return json.takeIf { it.length() > 0 }
    }

    private fun currentUid(): String? =
        runCatching {
            AccountBridge.getCurrentUserId()
        }.getOrNull()

    private fun readLong(
        target: Any,
        getter: String,
    ): Long =
        when (val v = Reflect.getter(target, getter)) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: 0L
            else -> 0L
        }

    private fun formatTime(seconds: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(seconds * 1000))

    /** 会话群/单聊标记（仅读类型字段，不涉及对方资料） */
    private fun conversationIsGroup(conv: Any): Boolean? =
        runCatching {
            val type = (Reflect.getter(conv, "getConversationType") as? Number)?.toInt() ?: 0
            (Reflect.getter(conv, "isGroupChat") as? Boolean) ?: (type == 2)
        }.getOrNull()

    /** 会话过滤：keyword 按名字/备注/会话ID 模糊；isGroup 精确匹配群/单聊 */
    private fun matchesConversation(json: JSONObject, keyword: String, isGroup: Boolean?): Boolean {
        if (isGroup != null && json.optBoolean("isGroup") != isGroup) return false
        if (keyword.isEmpty()) return true
        val text = buildString {
            append(json.optString("name")).append(' ')
            append(json.optString("remarkName")).append(' ')
            append(json.optString("conversationId"))
        }
        return text.contains(keyword, ignoreCase = true)
    }

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
