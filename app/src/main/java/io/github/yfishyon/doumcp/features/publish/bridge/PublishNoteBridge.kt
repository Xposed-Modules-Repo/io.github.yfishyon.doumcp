package io.github.yfishyon.doumcp.features.publish.bridge

import android.media.MediaMetadataRetriever
import io.github.yfishyon.doumcp.features.account.bridge.AccountBridge
import io.github.yfishyon.doumcp.features.publish.model.PublishMention
import io.github.yfishyon.doumcp.features.publish.resolver.PublishResolver
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.UUID

/**
 * 图文发布桥：本地图片（或视频文件）→ 上传拿对象键 → 最小参数骨架 → 发送。
 *
 * 图片走图文形态（轮播图集）；视频文件走**动图/实况**形态（呈现在图集类型里）。
 * 要发普通视频作品用 [PublishVideoBridge]。
 * 最小骨架经二分实测：仅类型标记（图文 aweme_type / 通用 new_sdk）为必需，
 * 其余由本桥按素材与文案现算。
 */
object PublishNoteBridge {
    /** 单次最多文件数（宿主发布图文自身限制） */
    private const val MAX_FILES = 9

    /** 图文分片类型（图片） */
    private const val CLIP_TYPE_IMAGE = 2

    /** 动图分片类型（视频） */
    private const val CLIP_TYPE_VIDEO = 4

    /** 图文轮播每张停留秒数 */
    private const val SLIDE_SECONDS = 2.0

    fun publishJson(
        imagePaths: List<String>,
        text: String,
        title: String,
        mentions: List<PublishMention>,
        musicId: String?,
        isStory: Boolean,
    ): String {
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录")
        if (imagePaths.isEmpty()) return errorJson("缺少 imagePaths（本地图片或视频路径）")
        if (imagePaths.size > MAX_FILES) return errorJson("最多 $MAX_FILES 个文件")
        val caller = PublishResolver.resolvePublishCaller() ?: return errorJson("发布接口未定位")

        val videoFiles = imagePaths.filter { PublishUploader.isVideoFile(it) }
        val imageFiles = imagePaths - videoFiles.toSet()
        if (videoFiles.isNotEmpty() && imageFiles.isNotEmpty()) {
            return errorJson("图片与视频文件不能混传：视频走动图形态，请整批传图片或整批传视频")
        }

        val fields = PublishDefaults.note()
        runCatching {
            if (videoFiles.isNotEmpty()) {
                buildVideoNote(fields, videoFiles, text, title, mentions, musicId, isStory)
            } else {
                buildImageNote(fields, imageFiles, text, title, mentions, musicId, isStory)
            }
        }.getOrElse { return errorJson(it.message ?: "素材处理失败") }

        return runCatching { PublishReplayBridge.sendJson(caller, fields) }
            .getOrElse { errorJson("发布失败: ${it.cause ?: it}") }
    }

    /** 图文形态：图片逐张上传，装进轮播图集。 */
    private fun buildImageNote(
        fields: LinkedHashMap<String, String>,
        imagePaths: List<String>,
        text: String,
        title: String,
        mentions: List<PublishMention>,
        musicId: String?,
        isStory: Boolean,
    ) {
        val images =
            runCatching { PublishUploader.uploadImages(imagePaths) }
                .getOrElse { throw IllegalStateException("图片上传失败: ${it.cause ?: it}", it) }
        fields["images"] = buildImages(images)
        fields["clip_data"] = buildImageClipData(images)
        applyCommon(fields, text, title, mentions, musicId, isStory)
    }

    /** 动图/实况形态：视频逐个上传，装进视频分片（最小参数，实测可发布）。 */
    private fun buildVideoNote(
        fields: LinkedHashMap<String, String>,
        videoPaths: List<String>,
        text: String,
        title: String,
        mentions: List<PublishMention>,
        musicId: String?,
        isStory: Boolean,
    ) {
        val clips = JSONArray()
        val importInfo = JSONArray()
        for (path in videoPaths) {
            val video =
                runCatching { PublishUploader.uploadVideo(path) }
                    .getOrElse { throw IllegalStateException("视频上传失败: ${it.cause ?: it}", it) }
            val durationMs = videoDurationMs(path)
            clips.put(
                JSONObject()
                    .put("clip_type", CLIP_TYPE_VIDEO)
                    .put("cover_tsp", 0)
                    .put("width", video.width)
                    .put("height", video.height)
                    .put("cover_uri", video.coverUri)
                    .put("video_id", video.videoId),
            )
            importInfo.put(
                JSONObject()
                    .put("h", video.height)
                    .put("w", video.width)
                    .put("b", 0)
                    .put("e", durationMs),
            )
        }
        fields["video_id"] = runCatching { clips.getJSONObject(0).getString("video_id") }.getOrDefault("")
        fields["video_cover_uri"] = runCatching { clips.getJSONObject(0).optString("cover_uri") }.getOrDefault("")
        fields["clip_data"] = JSONObject().put("clips", clips).toString()
        fields["import_video_info"] = importInfo.toString()
        // 动图形态不带图文类型标记（与视频分片冲突）
        fields.remove("aweme_type")
        applyCommon(fields, text, title, mentions, musicId, isStory)
    }

    /** 幂等键、动态标记、文案、配乐（图文/动图共用）。 */
    private fun applyCommon(
        fields: LinkedHashMap<String, String>,
        text: String,
        title: String,
        mentions: List<PublishMention>,
        musicId: String?,
        isStory: Boolean,
    ) {
        fields["creation_id"] = UUID.randomUUID().toString()
        fields["publish_trace_id"] = UUID.randomUUID().toString()
        PublishFieldKit.applyStory(fields, isStory)
        PublishFieldKit.applyText(fields, text, title, mentions)
        PublishFieldKit.applyMusic(fields, musicId)
    }

    /** 图片列表字段（每张：对象键 + 宽高）。 */
    private fun buildImages(images: List<UploadedMedia>): String {
        val array = JSONArray()
        for (image in images) {
            array.put(
                JSONObject()
                    .put("uri", image.uri)
                    .put("width", image.width)
                    .put("height", image.height)
                    .put("is_aigc_media", false)
                    .put("is_new_text_mode", 0)
                    .put("read_text", false),
            )
        }
        return array.toString()
    }

    /** 图文分片数据（每张一个片段，首图作封面，含轮播时长）。 */
    private fun buildImageClipData(images: List<UploadedMedia>): String {
        val clips = JSONArray()
        for ((position, image) in images.withIndex()) {
            clips.put(
                JSONObject()
                    .put("clip_type", CLIP_TYPE_IMAGE)
                    .put("uri", image.uri)
                    .put("width", image.width)
                    .put("height", image.height)
                    .put("is_cover", position == 0)
                    .put("is_aigc_media", false)
                    .put("is_new_text_mode", 0)
                    .put("read_text", false),
            )
        }
        return JSONObject()
            .put("clips", clips)
            .put(
                "slides_duration",
                JSONObject()
                    .put("auto_slide_duration", SLIDE_SECONDS)
                    .put("total_duration", SLIDE_SECONDS * images.size),
            ).toString()
    }

    /** 视频时长（毫秒）。 */
    private fun videoDurationMs(path: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}
