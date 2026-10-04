package io.github.yfishyon.doumcp.features.publish.tool

import io.github.yfishyon.doumcp.core.addDouMcpTool
import io.github.yfishyon.doumcp.core.boolArg
import io.github.yfishyon.doumcp.core.intArgOrNull
import io.github.yfishyon.doumcp.core.jsonArg
import io.github.yfishyon.doumcp.core.stringArg
import io.github.yfishyon.doumcp.core.toolCall
import io.github.yfishyon.doumcp.features.publish.bridge.PublishMusicBridge
import io.github.yfishyon.doumcp.features.publish.bridge.PublishNoteBridge
import io.github.yfishyon.doumcp.features.publish.bridge.PublishVideoBridge
import io.github.yfishyon.doumcp.features.publish.model.PublishMention
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray

/**
 * 发布（作品）相关 MCP 工具注册。
 *
 * 两个发布工具直接吃本地文件：图文（publishNote，图片/视频均可）与普通视频
 * （publishVideo）；配乐先经 searchMusic 搜 id。
 */
internal fun Server.registerPublishTools() {
    addDouMcpTool(
        name = "publishNote",
        description =
            "直接发布图文作品：给本地文件路径，模块上传素材后用最小必需参数发送。" +
                "参数：imagePaths（本地文件绝对路径的 JSON 数组，最多 9 个，图片或视频均可，" +
                "如 [\"/sdcard/a.jpg\"]；整批图片发为图文图集，整批视频发为图集里的动图/实况形态，不能混传）、" +
                "text（正文/简介；正文里写 #话题 会生成话题，写 @昵称 会生成提及）、title（可选，标题）、" +
                "mentions（可选，JSON 数组 [{\"uid\":\"..\",\"secUid\":\"..\",\"nickname\":\"..\"}]，" +
                "正文里写「@昵称」对应片段会被标为 @提及）、" +
                "musicId（可选，配乐 ID；不传则不带配乐）、" +
                "isStory（可选，默认 false；true 发为 24 小时动态）。" +
                "返回 ok 时含新作品 aweme_id。",
    ) { request ->
        val imagePathsArg = request.arguments.jsonArg("imagePaths")
        val text = request.arguments.stringArg("text") ?: ""
        val title = request.arguments.stringArg("title") ?: ""
        val mentionsArg = request.arguments.jsonArg("mentions")
        val musicId = request.arguments.stringArg("musicId")
        val isStory = request.arguments.boolArg("isStory", false)
        withContext(Dispatchers.IO) {
            toolCall {
                PublishNoteBridge.publishJson(
                    imagePaths = parseFiles(imagePathsArg),
                    text = text,
                    title = title,
                    mentions = parseMentions(mentionsArg),
                    musicId = musicId,
                    isStory = isStory,
                )
            }
        }
    }

    addDouMcpTool(
        name = "publishVideo",
        description =
            "直接发布普通视频作品：给本地视频路径，模块上传素材后按完整视频参数发送。" +
                "参数：videoPath（本地视频绝对路径）、text（正文/简介；写 #话题 会生成话题、写 @昵称 会生成提及）、" +
                "title（可选，标题）、musicId（可选，配乐 ID；不传则不带配乐）、" +
                "isStory（可选，默认 false；true 发为 24 小时动态）、" +
                "mentions（可选，JSON 数组 [{\"uid\":\"..\",\"secUid\":\"..\",\"nickname\":\"..\"}]）。" +
                "返回 ok 时含新作品 aweme_id。",
    ) { request ->
        val videoPath = request.arguments.stringArg("videoPath") ?: ""
        val text = request.arguments.stringArg("text") ?: ""
        val title = request.arguments.stringArg("title") ?: ""
        val musicId = request.arguments.stringArg("musicId")
        val isStory = request.arguments.boolArg("isStory", false)
        val mentionsArg = request.arguments.jsonArg("mentions")
        withContext(Dispatchers.IO) {
            toolCall {
                PublishVideoBridge.publishJson(
                    videoPath = videoPath,
                    text = text,
                    title = title,
                    mentions = parseMentions(mentionsArg),
                    musicId = musicId,
                    isStory = isStory,
                )
            }
        }
    }

    addDouMcpTool(
        name = "searchMusic",
        description =
            "按关键词搜索配乐，返回音乐列表（含 id）。把 id 传给 publishNote / publishVideo 的 musicId 即可用该配乐。" +
                "参数：keyword（关键词）、cursor（可选，翻页游标，首页传 0）、count（可选，每页条数，默认 20）。",
    ) { request ->
        val keyword = request.arguments.stringArg("keyword") ?: ""
        val cursor = (request.arguments.intArgOrNull("cursor") ?: 0).coerceAtLeast(0)
        val count = (request.arguments.intArgOrNull("count") ?: 20).coerceIn(1, 50)
        withContext(Dispatchers.IO) {
            toolCall { PublishMusicBridge.searchJson(keyword, cursor, count) }
        }
    }
}

/** 文件路径参数（JSON 数组，或内容是 JSON 数组的字符串）→ 路径列表。 */
private fun parseFiles(element: kotlinx.serialization.json.JsonElement?): List<String> {
    val array =
        when (element) {
            is JsonArray -> JSONArray(element.toString())
            is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }?.let { JSONArray(it) }
            else -> null
        } ?: return emptyList()
    val paths = ArrayList<String>(array.length())
    for (i in 0 until array.length()) {
        val path = array.optString(i)
        if (path.isNotBlank()) paths.add(path)
    }
    return paths
}

/** mentions 参数（JSON 数组，或内容是 JSON 数组的字符串）→ 提及列表。 */
private fun parseMentions(element: kotlinx.serialization.json.JsonElement?): List<PublishMention> {
    val array =
        when (element) {
            is JsonArray -> JSONArray(element.toString())
            is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }?.let { JSONArray(it) }
            else -> null
        } ?: return emptyList()
    val mentions = ArrayList<PublishMention>(array.length())
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val nickname = item.optString("nickname")
        if (nickname.isBlank()) continue
        mentions.add(
            PublishMention(
                uid = item.optString("uid"),
                secUid = item.optString("secUid"),
                nickname = nickname,
            ),
        )
    }
    return mentions
}
