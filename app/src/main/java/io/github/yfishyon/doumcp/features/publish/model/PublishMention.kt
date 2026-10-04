package io.github.yfishyon.doumcp.features.publish.model

/** 一条 @提及：正文里的「@昵称」会被标记为提及。 */
data class PublishMention(
    val uid: String,
    val secUid: String,
    val nickname: String,
)
