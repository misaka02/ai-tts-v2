package com.aitts.v2.model

import kotlinx.serialization.Serializable

@Serializable
enum class SegmentRole {
    NARRATOR,
    DIALOGUE,
    MALE_DIALOGUE,
    FEMALE_DIALOGUE,
    ELDER_DIALOGUE
}

@Serializable
enum class EmotionType(val displayName: String, val promptInstruction: String) {
    ANGRY("愤怒冷酷", "【语气要求：极其愤怒激昂、语气冷酷严厉带有压迫感】"),
    SAD("哀伤哽咽", "【语气要求：语气哀伤低沉、带有轻微哭腔与叹息】"),
    FEARFUL("惊恐慌乱", "【语气要求：情绪惊恐慌乱、呼吸急促带有急迫颤抖感】"),
    GENTLE("娇柔温婉", "【语气要求：语气轻柔温婉、声线甜美细腻带有柔情】"),
    WHISPER("悄声耳语", "【语气要求：压低声线、悄声耳语带有神秘感】"),
    EXCITED("激动狂喜", "【语气要求：情绪极其激动兴奋、语调高昂充满狂喜】"),
    COLD_ARROGANT("傲慢冷漠", "【语气要求：语调高傲冷漠、不屑一顾居高临下】"),
    CONFUSED_INQUIRING("疑惑探寻", "【语气要求：充满困惑疑惑、语调上扬带着试探探寻】"),
    NEUTRAL("自然生动", "")
}

@Serializable
data class SentenceSegment(
    val text: String,
    val role: SegmentRole = SegmentRole.NARRATOR,
    val emotion: EmotionType = EmotionType.NEUTRAL
)
