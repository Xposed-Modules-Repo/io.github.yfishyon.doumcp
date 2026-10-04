package io.github.yfishyon.doumcp.features.publish.bridge

import java.util.LinkedHashMap

/**
 * 发布参数的内置默认骨架（图文/视频统一的最小集，全部经二分实测）。
 *
 * 原始捕获 124~133 个字段，逐批删除实验后：埋点、美化、定位、计数、隐私标记、
 * 编辑态派生等全部可删；图文缺失 [AWEME_TYPE]/[NEW_SDK]、视频缺失 [NEW_SDK]
 * 会被服务端以「参数不合法」拒绝。其余字段由各发布桥按本次素材现算。
 */
object PublishDefaults {
    private const val NEW_SDK = "new_sdk"
    private const val AWEME_TYPE = "aweme_type"

    /** 图文骨架（images / clip_data / 文案 / 幂等键 由桥现算） */
    fun note(): LinkedHashMap<String, String> {
        val fields = LinkedHashMap<String, String>()
        fields[AWEME_TYPE] = "68"
        fields[NEW_SDK] = "1"
        return fields
    }

    /** 视频骨架：真实视频发布的静态常量（会话态/埋点/编辑态字段不内置；媒体/文案/幂等键/音乐/动态标记由桥现算）。 */
    fun video(): LinkedHashMap<String, String> {
        val fields = LinkedHashMap<String, String>()
        fields.putAll(
            mapOf(
                "video_width" to "720",
                "video_height" to "1280",
                "permission_sync_decouple" to "1",
                "new_sdk" to "1",
                "mixed_type" to "0",
                "duet_ignore_visibility" to "true",
                "download_ignore_visibility" to "true",
                "share_ignore_visibility" to "true",
                "aigc_metadata" to "",
                "is_diy_prop" to "0",
                "remove_background" to "0",
                "is_text_reading" to "0",
                "video_cnt" to "1",
                "pic_cnt" to "0",
                "live_cnt" to "0",
                "is_multi_content" to "0",
                "original" to "0",
                "original_type" to "0",
                "need_event_check" to "1",
                "following_trends_id" to "null",
                "tab_name" to "photo",
                "location_permission" to "true",
                "entry_type" to "0",
                "is_ai_expand_used" to "0",
                "camera" to "0",
                "prettify" to "2",
                "is_upload_audio_track" to "false",
                "is_multi_video_upload" to "false",
                "use_camera_type" to "1",
                "h264_high_profile" to "1",
                "tanning" to "0",
                "is_draft" to "0",
                "initial_privacy_status" to "public",
                "download_type" to "0",
                "item_duet" to "0",
                "item_share" to "0",
                "comment_permission_status" to "0",
                "danmaku_privilege" to "0",
                "song_category_id" to "",
                "is_text_mode" to "0",
                "category_da" to "0",
                "cover_tsp" to "0.0",
                "text_fonts" to "",
                "text_font_effect_ids" to "",
                "is_subtitled" to "0",
                "has_text" to "0",
                "filter_value" to "-1.0",
                "is_original_filter" to "1",
                "beautify_info" to "",
                "beautify_used" to "0",
                "shoot_beautify_info" to
                    "{\"beauty\":{\"name\":[\"磨皮\",\"瘦脸\",\"小头\",\"大眼\",\"眼妆\",\"瞳孔大小\",\"眼睑下至\",\"清晰\",\"美白\",\"微笑\",\"瘦颧骨\",\"下颌\",\"瘦鼻\",\"鼻翼\",\"鼻梁\",\"嘴巴位置\",\"丰上唇\",\"口红\",\"腮红\",\"立体\",\"白牙\",\"黑眼圈\",\"法令纹\"],\"value\":[\"90\",\"40\",\"50\",\"65\",\"45\",\"5\",\"10\",\"75\",\"65\",\"10\",\"20\",\"10\",\"45\",\"10\",\"10\",\"15\",\"15\",\"50\",\"40\",\"60\",\"20\",\"90\",\"40\"]}}",
                "is_beautify" to "1",
                "edit_beautify_info" to "{\"beauty\":{\"name\":[],\"value\":[]}}",
                "is_edit_beautify" to "0",
                "is_composer" to "1",
                "fast_import" to "1",
                "improve_status" to "0",
                "is_trimmed" to "0",
                "is_from_avatar" to "0",
                "activity_video_type" to "-1",
                "is_share_post" to "0",
                "shoot_enter_from" to "homepage_familiar",
                "shoot_enter_method" to "",
                "is_meteor" to "0",
                "is_item_rounded_corner" to "false",
                "creation_source_from" to "album",
                "is_infini" to "1",
                "is_long_text" to "1",
                "is_private" to "0",
                "dont_share" to "0",
                "video_hide_search" to "0",
                "shoot_way" to "direct_shoot",
                "content_source" to "upload",
                "is_hard_code" to "11",
                "file_fps" to "24",
                "item_comment" to "0",
                "stickers" to "",
                "anchor_business_type" to "-1",
                "anchor_content" to "",
                "anchor" to "{\"type\":-1,\"id\":\"\",\"content\":\"\",\"source\":0}",
                "publish_insert_tab" to "1",
                "is_task_path_item" to "0",
            ),
        )
        return fields
    }
}
