package io.github.yfishyon.doumcp.features.publish.bridge

import io.github.yfishyon.doumcp.features.account.bridge.AccountBridge
import io.github.yfishyon.doumcp.features.publish.model.PublishMention
import io.github.yfishyon.doumcp.features.publish.resolver.PublishResolver
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.UUID

/**
 * 视频发布桥：本地视频 → 上传拿 videoId/封面 → 完整视频参数骨架 → 发送。
 *
 * 骨架取自真实视频发布的静态常量（保留全部，会话态/埋点字段不内置）；媒体字段
 * 替换成新上传的视频，文案/配乐/动态标记按调用方给的替换。
 */
object PublishVideoBridge {
    /** 视频分片类型 */
    private const val CLIP_TYPE_VIDEO = 4

    fun publishJson(
        videoPath: String,
        text: String,
        title: String,
        mentions: List<PublishMention>,
        musicId: String?,
        isStory: Boolean,
    ): String {
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录")
        if (videoPath.isBlank()) return errorJson("缺少 videoPath（本地视频路径）")
        val caller = PublishResolver.resolvePublishCaller() ?: return errorJson("发布接口未定位")

        val video =
            runCatching { PublishUploader.uploadVideo(videoPath) }
                .getOrElse { return errorJson("视频上传失败: ${it.cause ?: it}") }
        val durationMs = PublishUploader.videoDurationMs(videoPath)

        val fields = PublishDefaults.video()
        buildFields(fields, video, durationMs, text, title, mentions, musicId, isStory)

        return runCatching { PublishReplayBridge.sendJson(caller, fields) }
            .getOrElse { errorJson("发布失败: ${it.cause ?: it}") }
    }

    private fun buildFields(
        fields: LinkedHashMap<String, String>,
        video: UploadedVideo,
        durationMs: Long,
        text: String,
        title: String,
        mentions: List<PublishMention>,
        musicId: String?,
        isStory: Boolean,
    ) {
        fields["video_id"] = video.videoId
        fields["video_cover_uri"] = video.coverUri
        fields["video_width"] = video.width.toString()
        fields["video_height"] = video.height.toString()
        fields["clip_data"] = buildClipData(video)
        fields["import_video_info"] = "[{\"h\":${video.height},\"w\":${video.width},\"b\":0,\"e\":$durationMs}]"
        fields["resolution_for_log_param"] =
            "[{\"video_source_width\":${video.width},\"video_source_height\":${video.height}," +
            "\"video_publish_width\":${video.width},\"video_publish_height\":${video.height}}]"
        fields["creation_id"] = UUID.randomUUID().toString()
        fields["publish_trace_id"] = UUID.randomUUID().toString()
        PublishFieldKit.applyStory(fields, isStory)
        PublishFieldKit.applyText(fields, text, title, mentions)
        PublishFieldKit.applyMusic(fields, musicId)
    }

    /** 视频分片数据（单片段：视频号 + 封面 + 宽高）。 */
    private fun buildClipData(video: UploadedVideo): String =
        JSONObject()
            .put(
                "clips",
                JSONArray().put(
                    JSONObject()
                        .put("clip_type", CLIP_TYPE_VIDEO)
                        .put("cover_tsp", 0.0)
                        .put("width", video.width)
                        .put("height", video.height)
                        .put("cover_uri", video.coverUri)
                        .put("video_id", video.videoId),
                ),
            ).toString()

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
