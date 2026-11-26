package com.aitts.v2.engine.text

import com.aitts.v2.model.EmotionType
import com.aitts.v2.model.SegmentRole

/**
 * Analyzes speaker role and micro-emotion from narrative context preceding a dialogue quote.
 */
object DialogueAnalyzer {

    private val elderKeywords = listOf(
        "老者", "老头", "老汉", "老祖", "太上长老", "魔尊", "宗主", "掌门", "魔头", "老僧", "师尊", "前辈"
    )
    private val femaleKeywords = listOf(
        "她", "少女", "姑娘", "女子", "师妹", "师姐", "娘亲", "夫人", "小姐", "公主",
        "丫头", "仙子", "神女", "母后", "美妇", "皇后", "女帝", "妹妹", "姐姐", "老妇", "大娘"
    )
    private val maleKeywords = listOf(
        "他", "少年", "青年", "男子", "师兄", "师弟", "皇帝", "陛下",
        "兄弟", "父亲", "爹", "大哥", "弟弟", "冷笑", "喝道", "哼道", "怒喝"
    )

    private val emotionRules: List<Pair<EmotionType, List<String>>> = listOf(
        EmotionType.COLD_ARROGANT to listOf("傲慢", "冷漠", "不屑", "居高临下", "睥睨", "漠然", "嗤笑", "冷冷道", "淡淡道", "轻蔑", "高傲"),
        EmotionType.CONFUSED_INQUIRING to listOf("疑惑", "纳闷", "不解", "诧异", "皱眉", "试探", "探寻", "奇道", "惊奇", "茫然", "困惑", "何解"),
        EmotionType.ANGRY to listOf("愤怒", "怒吼", "咆哮", "暴怒", "冷笑", "冷喝", "厉喝", "大怒", "杀意", "怒斥", "咬牙切齿", "厉声", "怒道", "喝道", "找死"),
        EmotionType.SAD to listOf("哭泣", "抽泣", "哽咽", "叹息", "落泪", "哀求", "凄凉", "悲痛", "绝望", "委屈", "轻叹", "叹道", "泪流满面", "心碎", "凄然"),
        EmotionType.FEARFUL to listOf("惊呼", "骇然", "惊骇", "颤抖", "尖叫", "失声", "慌乱", "倒吸凉气", "面色煞白", "大惊", "惊恐", "战战兢兢", "恐惧", "发抖"),
        EmotionType.GENTLE to listOf("温柔", "轻柔", "娇嗔", "娇羞", "软糯", "甜美", "羞涩", "含羞", "轻声细语", "呢喃", "撒娇", "柔声", "浅笑", "温言", "轻唤"),
        EmotionType.WHISPER to listOf("低语", "耳语", "悄声", "附耳", "压低声音", "窃窃私语", "小声", "偷偷", "低声道", "私语", "密语", "传音"),
        EmotionType.EXCITED to listOf("狂喜", "大笑", "兴奋", "激动", "惊喜", "狂热", "欢呼", "哈哈大笑", "欣喜", "若狂", "雀跃", "大喜", "仰天长啸")
    )

    fun detectSpeakerRole(precedingText: String): SegmentRole {
        val tail = precedingText.takeLast(16)
        return when {
            elderKeywords.any { tail.contains(it) } -> SegmentRole.ELDER_DIALOGUE
            femaleKeywords.any { tail.contains(it) } -> SegmentRole.FEMALE_DIALOGUE
            maleKeywords.any { tail.contains(it) } -> SegmentRole.MALE_DIALOGUE
            else -> SegmentRole.DIALOGUE
        }
    }

    fun detectEmotion(precedingText: String, dialogueText: String = ""): EmotionType {
        val tail = precedingText.takeLast(24)
        for ((emotion, keywords) in emotionRules) {
            if (keywords.any { tail.contains(it) }) return emotion
        }
        return when {
            dialogueText.endsWith("？！") || dialogueText.endsWith("?！") || dialogueText.endsWith("？") ->
                if (dialogueText.length <= 24) EmotionType.CONFUSED_INQUIRING else EmotionType.NEUTRAL
            dialogueText.endsWith("！") && dialogueText.length <= 18 -> EmotionType.EXCITED
            else -> EmotionType.NEUTRAL
        }
    }
}
