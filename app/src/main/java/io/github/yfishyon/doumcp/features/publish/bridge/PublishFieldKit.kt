package io.github.yfishyon.doumcp.features.publish.bridge

import io.github.yfishyon.doumcp.features.publish.model.PublishMention
import org.json.JSONArray
import org.json.JSONObject

/**
 * 发布字段装配：文案（含话题/提及区间实体）、动态标记与配乐。
 *
 * 区间实体的形状内置为宿主真实写出的结构，只填区间与内容；未指定配乐时
 * 按宿主「未选配乐」的原生表达清空。
 */
object PublishFieldKit {
    private const val TYPE_MENTION = 0
    private const val TYPE_HASHTAG = 1
    private const val EMPTY_ARRAY = "[]"

    /** 话题：正文里的「#xxx」（到空白/#/@ 为止）。 */
    private val HASHTAG = Regex("#([^\\s#@]+)")

    /**
     * 区间实体的宿主形状（取自真实发布捕获，仅含形状字段；内容字段由装配时填）。
     *
     * 键集合与宿主写出的实体一致，缺失会导致话题/提及不渲染。
     */
    private const val HASHTAG_SHAPE =
        """{"brand_status":0,"caption_end":0,"caption_start":0,"color":0,"custom_span_size":0,"is_hide_icon":false,"icon_horizontal_position":0,"icon_length":0,"icon_vertical_position":0,"extraParams":{"tag_source":"search"},"isBoldText":false,"isClickable":true,"is_commerce":false,"isIndexModified":false,"isInsertedLocally":false,"is_prior_hashtag":false,"star_atlas_tag":false,"music_id":0,"search_hide_words":0,"search_rank":0,"sticker_source":0,"sub_type":0,"tag_mention_need_fold":0,"type":1,"user_follow_status":0}"""

    private const val MENTION_SHAPE =
        """{"at_user_type":"","brand_status":0,"caption_end":0,"caption_start":0,"color":0,"custom_span_size":0,"is_hide_icon":false,"icon_horizontal_position":0,"icon_length":0,"icon_vertical_position":0,"isBoldText":false,"isClickable":true,"is_commerce":false,"isIndexModified":false,"isInsertedLocally":false,"is_prior_hashtag":false,"star_atlas_tag":false,"music_id":0,"search_hide_words":0,"search_rank":0,"sticker_source":0,"sub_type":0,"tag_mention_need_fold":0,"type":0,"user_follow_status":0}"""

    /** 写入文案相关字段（text/create_aweme_text/caption/标题/text_extra）。 */
    fun applyText(
        fields: MutableMap<String, String>,
        text: String,
        title: String,
        mentions: List<PublishMention>,
    ) {
        val fullText = if (title.isBlank()) text else "$title $text"
        fields["text"] = fullText
        fields["create_aweme_text"] = text
        fields["caption"] = text
        // 标题无论有没有都给值，不残留旧内容
        fields["create_aweme_title"] = title
        fields["item_title"] = title
        fields["text_extra"] = buildTextExtra(fullText, mentions)
    }

    /**
     * 写入动态（24 小时可见）标记。
     *
     * 参数取自真实动态发布的捕获：与普通发布的差异是 is_story/is_25_story/
     * story_comment_permission 及下载、合拍、分享开关与入口标记；普通发布时不带这些字段。
     */
    fun applyStory(
        fields: MutableMap<String, String>,
        isStory: Boolean,
    ) {
        if (!isStory) {
            fields.remove("is_story")
            fields.remove("is_25_story")
            fields.remove("story_comment_permission")
            return
        }
        fields["is_story"] = "1"
        fields["is_25_story"] = "1"
        fields["story_comment_permission"] = "1"
        fields["download_type"] = "3"
        fields["item_duet"] = "1"
        fields["item_share"] = "1"
        fields["publish_insert_tab"] = "2"
    }

    /**
     * 写入配乐字段。
     *
     * 未指定配乐时按宿主「未选配乐」的原生写法（无 music_id 字段、来源标记为原声），
     * 已随最小骨架直发验证可用。
     */
    fun applyMusic(
        fields: MutableMap<String, String>,
        musicId: String?,
    ) {
        if (musicId.isNullOrBlank()) {
            fields.remove("music_id")
            fields["music_selected_from"] = "original"
            fields["music_edited_from"] = "none"
            fields["music_begin_time"] = "0"
            fields["music_end_time"] = "0"
            fields["is_music_looped"] = "0"
            fields["image_album_music_info"] = """{"begin_time":0,"end_time":0,"volume":0}"""
            fields.remove("music_volume")
            fields.remove("real_music_volume")
            fields.remove("origin_volume")
            fields.remove("real_origin_volume")
            return
        }
        fields["music_id"] = musicId
        fields["music_selected_from"] = "edit_page_auto_load"
    }

    /** 正文 → 区间实体列表 JSON（话题 + 提及）；无实体时返回空数组。 */
    private fun buildTextExtra(
        fullText: String,
        mentions: List<PublishMention>,
    ): String {
        val located = ArrayList<Pair<Int, JSONObject>>()
        for (match in HASHTAG.findAll(fullText)) {
            val entity = JSONObject(HASHTAG_SHAPE)
            entity.put("hashtag_name", match.groupValues[1])
            entity.put("start", match.range.first)
            entity.put("end", match.range.last + 1)
            located.add(match.range.first to entity)
        }
        for (mention in mentions) {
            val token = "@${mention.nickname}"
            val start = fullText.indexOf(token)
            if (start < 0) continue
            val entity = JSONObject(MENTION_SHAPE)
            entity.put("user_id", mention.uid)
            entity.put("sec_uid", mention.secUid)
            entity.put("start", start)
            entity.put("end", start + token.length)
            located.add(start to entity)
        }

        if (located.isEmpty()) return EMPTY_ARRAY
        located.sortBy { it.first }
        val result = JSONArray()
        for ((_, entity) in located) result.put(entity)
        return result.toString()
    }
}
