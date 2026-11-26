package com.aitts.v2.model

/**
 * Curated static voice catalogs per provider family.
 */
object PresetVoices {

    val edgeVoices = listOf(
        VoiceModel("zh-CN-XiaoxiaoNeural", "晓晓 (女声·温暖自然)", "Female", "zh-CN", "微软经典女声，长篇听书首选"),
        VoiceModel("zh-CN-YunxiNeural", "云希 (男声·阳光沉稳)", "Male", "zh-CN", "微软经典男声，适合都市与玄幻小说"),
        VoiceModel("zh-CN-YunjianNeural", "云健 (男声·影视解说)", "Male", "zh-CN", "厚重大气，适合热血与解说"),
        VoiceModel("zh-CN-XiaoyiNeural", "晓伊 (女声·甜美电台)", "Female", "zh-CN", "甜美活泼，适合轻松甜宠文"),
        VoiceModel("zh-CN-YunyangNeural", "云扬 (男声·专业播音)", "Male", "zh-CN", "新闻播音腔，字正腔圆"),
        VoiceModel("zh-CN-liaoning-XiaobeiNeural", "东北小北 (女声·幽默方言)", "Female", "zh-CN-liaoning", "东北风情"),
        VoiceModel("zh-CN-shaanxi-XiaoniNeural", "陕西小妮 (女声·陕西方言)", "Female", "zh-CN-shaanxi", "西北方言特色"),
        VoiceModel("zh-TW-HsiaoChenNeural", "曉臻 (台灣女聲)", "Female", "zh-TW", "台灣國語女聲"),
        VoiceModel("zh-HK-HiuMaanNeural", "曉曼 (粵語女聲)", "Female", "zh-HK", "廣東話 / 粵語朗讀"),
        VoiceModel("en-US-JennyNeural", "Jenny (English Female)", "Female", "en-US", "Standard US English Female"),
        VoiceModel("en-US-GuyNeural", "Guy (English Male)", "Male", "en-US", "Standard US English Male"),
        VoiceModel("ja-JP-NanamiNeural", "七海 (日本語女声)", "Female", "ja-JP", "日本語朗読")
    )

    val mimoVoices = listOf(
        VoiceModel("茉莉", "茉莉 (女声·知性温婉)", "Female", "zh-CN", "小米 MiMo 招牌女声"),
        VoiceModel("冰糖", "冰糖 (女声·灵动活泼)", "Female", "zh-CN", "清亮少女感"),
        VoiceModel("苏打", "苏打 (男声·清爽少年)", "Male", "zh-CN", "阳光少年男声"),
        VoiceModel("白桦", "白桦 (男声·沉稳磁性)", "Male", "zh-CN", "沉稳旁白男声"),
        VoiceModel("mimo_default", "MiMo 默认音色", "Female", "zh-CN", "官方基准音色"),
        VoiceModel("Mia", "Mia (English Female)", "Female", "en-US", "Natural English Female"),
        VoiceModel("Milo", "Milo (English Male)", "Male", "en-US", "Youthful English Male")
    )

    val geminiVoices = listOf(
        VoiceModel("Puck", "Puck (活力男声)", "Male", "all", "自然生动"),
        VoiceModel("Charon", "Charon (深沉男声)", "Male", "all", "磁性稳重"),
        VoiceModel("Kore", "Kore (温婉女声)", "Female", "all", "温和叙事"),
        VoiceModel("Fenrir", "Fenrir (激昂男声)", "Male", "all", "充满张力"),
        VoiceModel("Aoede", "Aoede (清脆女声)", "Female", "all", "轻快自然"),
        VoiceModel("Leda", "Leda (优雅女声)", "Female", "all", "成熟端庄"),
        VoiceModel("Orus", "Orus (阳光男声)", "Male", "all", "清朗自信"),
        VoiceModel("Zephyr", "Zephyr (轻柔女声)", "Female", "all", "治愈舒缓")
    )

    val openAiVoices = listOf(
        VoiceModel("nova", "Nova (OpenAI 活力女声)", "Female", "all", "亲和力强"),
        VoiceModel("alloy", "Alloy (OpenAI 均衡中性)", "Neutral", "all", "通用均衡"),
        VoiceModel("echo", "Echo (OpenAI 温和男声)", "Male", "all", "沉稳男声"),
        VoiceModel("fable", "Fable (OpenAI 英音叙事)", "Neutral", "all", "叙事风格"),
        VoiceModel("onyx", "Onyx (OpenAI 低沉男声)", "Male", "all", "厚重男低音"),
        VoiceModel("shimmer", "Shimmer (OpenAI 清亮女声)", "Female", "all", "清晰明亮"),
        VoiceModel("FunAudioLLM/CosyVoice2-0.5B:alex", "CosyVoice2·Alex (硅基男声)", "Male", "zh-CN", "CosyVoice2 青年男声"),
        VoiceModel("FunAudioLLM/CosyVoice2-0.5B:anna", "CosyVoice2·Anna (硅基女声)", "Female", "zh-CN", "CosyVoice2 温柔女声"),
        VoiceModel("FunAudioLLM/CosyVoice2-0.5B:bella", "CosyVoice2·Bella (硅基甜美)", "Female", "zh-CN", "CosyVoice2 活泼女声"),
        VoiceModel("cixingnansheng", "阶跃·磁性男声", "Male", "zh-CN", "Step-Audio 磁性男声"),
        VoiceModel("wenrounvsheng", "阶跃·温柔女声", "Female", "zh-CN", "Step-Audio 温柔女声")
    )

    val minimaxVoices = listOf(
        VoiceModel("male-qn-qingse", "青涩青年 (男声)", "Male", "zh-CN", "自然少年感"),
        VoiceModel("male-qn-jingying", "精英青年 (男声)", "Male", "zh-CN", "沉稳自信"),
        VoiceModel("female-shaonv", "甜美少女 (女声)", "Female", "zh-CN", "清脆悦耳"),
        VoiceModel("female-yujie", "知性御姐 (女声)", "Female", "zh-CN", "端庄优雅"),
        VoiceModel("audiobook_male_1", "有声书男声·沉稳", "Male", "zh-CN", "长篇旁白"),
        VoiceModel("audiobook_female_1", "有声书女声·温婉", "Female", "zh-CN", "温婉耐听")
    )

    val doubaoVoices = listOf(
        VoiceModel("zh_female_shuangkuaisisi_moon_bigtts", "爽快思思 (豆包女主播)", "Female", "zh-CN", "欢快自然"),
        VoiceModel("zh_male_cancan_moon_bigtts", "灿灿主播 (豆包男声)", "Male", "zh-CN", "磁性叙事"),
        VoiceModel("zh_female_tianmeixiaoxuan_moon_bigtts", "甜美小萱 (女声)", "Female", "zh-CN", "温柔知性"),
        VoiceModel("zh_male_chunhou_moon_bigtts", "醇厚大叔 (男声)", "Male", "zh-CN", "历史故事")
    )

    val fishAudioVoices = listOf(
        VoiceModel("7f92f8afb8ec43bf81429cc1c9199cb1", "Fish·官方甜美女声", "Female", "zh-CN", "自然甜美"),
        VoiceModel("54a58406560946ea8a70477e682fb10d", "Fish·磁性男声旁白", "Male", "zh-CN", "解说旁白")
    )
}
