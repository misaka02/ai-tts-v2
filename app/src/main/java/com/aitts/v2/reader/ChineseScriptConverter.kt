package com.aitts.v2.reader

import android.icu.text.Transliterator
import android.os.Build
import com.aitts.v2.reader.model.ReaderScriptConvert

/**
 * Zero-dependency Simplified/Traditional Chinese script converter powered by Android ICU Transliterator
 * on API 29+, with a clean fallback character mapping on API 26-28.
 */
object ChineseScriptConverter {

    private val toSimplifiedIcu: Any? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { Transliterator.getInstance("Traditional-Simplified") }.getOrNull()
        } else null
    }

    private val toTraditionalIcu: Any? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { Transliterator.getInstance("Simplified-Traditional") }.getOrNull()
        } else null
    }

    fun convert(text: String, mode: ReaderScriptConvert): String {
        if (text.isEmpty() || mode == ReaderScriptConvert.NONE) return text
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val trans = (if (mode == ReaderScriptConvert.TO_SIMPLIFIED) toSimplifiedIcu else toTraditionalIcu) as? Transliterator
            if (trans != null) return runCatching { trans.transliterate(text) }.getOrDefault(text)
        }
        val map = if (mode == ReaderScriptConvert.TO_SIMPLIFIED) TRAD_TO_SIMP else SIMP_TO_TRAD
        val sb = StringBuilder(text.length)
        for (ch in text) sb.append(map[ch] ?: ch)
        return sb.toString()
    }

    private const val SIMP_CHARS = "万与丑专业丛东丝丢两严丧个丰临为丽举么义乌乐乔习乡书买乱争于亏云亚产亲亵" +
        "亿仅从仑仓仪们价众优伙会伞伟传伤伦伪体余佣佩佳使侠供依侦侧侨侵便促俄俊俗保信修俱仓个备" +
        "儿兑党兰关兴兹养兽内冈册写军农冠冬冯冰冲决况冻净凉凌减凑凝几凤凭凯出击函凿划刘则刚创" +
        "删别判利删刮到制刷券刹刺刻剂剪副割力劝办功加务动助努劳势勇勉勋勤匀包化北医汇区协南" +
        "单卖卢卫却卷厂历厉压厌厕厢去县参又双发变叙叠叶号叹吃各合吉吊同后向吓吗君吟否吧听启" +
        "吴呆呈告员周味呼命和咏咬哀品哇哈响哎哑哄哇哥哦哨哭唤售唯唱商啊问啡啤啥啦喂善喇喊喜" +
        "喝喧喷喻喽嗅吗嗓呜嗜叹嘉嘱尝嘛器噩喷团园困围图圆圈土圣在场坂均坐坚坛坝坟坠坡坤坦坪" +
        "垂垃型垒埃埋城域执培基堂堆堕堡堪塑塔涂塘塞填尘境增墨壁壮声处备复夏外多夜够梦大天太" +
        "夫央失头夸夹夺奇奈奉奋奔奖套奥女妆妇妈妖妙妥妨妹妻姆姐姑姓委姿威娃娄娇娘娜婚婴婶媒" +
        "媚子孔孕字存孙学孪宁宅宇守安宋完宏宗官定宛宜宝实宠审客宣室宫害宴宵家容宽宾宿寂寄密" +
        "富寒寓察寡寝寥实宁寨对寻导寿将尊小少尔尖尚尝尤就尺尼尽尾局层居屈屋屎屏展属山岁岂岗" +
        "岛岩岭岳岸峡峰崇崎崖崩崭嵌工左巧巨差己已巴巷币师希帐帕帖帝帅带帧席帮常帽幅幕干平年" +
        "并幸幻幼幽广庄庆庇床序库应底店庙府庞废度座庭庶康庸廉廊廓延建开异弃弄式弓引张弥弦弯" +
        "弱弹强归当录形彩影役彻彼往征径待很律后徒得徘徙从御微德心必忆忌忍忘忙忠忧快念忽怀态" +
        "怒怕怜思急总恋恐恒恕恩息恳恶恼悄悦悉悔悟悠患悲闷情惊惜惠恶惧惨惩惯想意愚感爱愧慈态" +
        "慌慎慕慢慰庆忧懂宪法应懒怀悬成我戒或战截戏户房所扁扇手才扎扑打托扛扣执扩扫扬扰扳扶" +
        "批找承技抄把抑抓投抗折抚抛抢护报披抬抱抵抹押抽担拆拉拌拍拐拒拓拔拖拘招拜拟拥拦拧拨" +
        "择括拭拯拱拳拷拼拾拿持挂指按挑挖挨挪振挺挽捂捆捉捏捐捕损捡换捧据挨掌排掘挂掠探接控" +
        "推掩措掷揉描提插握揣援搁搂搅搏搓搜搞搬搭携摄摆摇摊摔摘摧摩摸撑撒撕撞撤拨抚播撰扑"

    private const val TRAD_CHARS = "萬與醜專業叢東絲丟兩嚴喪個豐臨為麗舉麼義烏樂喬習鄉書買亂爭於虧雲亞產親褻" +
        "億僅從侖倉儀們價眾優夥會傘偉傳傷倫偽體餘傭佩佳使俠供依偵側僑侵便促俄俊俗保信修俱倉個備" +
        "兒兌黨蘭關興茲養獸內岡冊寫軍農冠冬馮冰衝決況凍淨涼凌減湊凝幾鳳憑凱出擊函鑿劃劉則剛創" +
        "刪別判利刪刮到制刷券剎刺刻劑剪副割力勸辦功加務動助努勞勢勇勉勳勤勻包化北醫匯區協南" +
        "單賣盧衛卻卷廠歷厲壓厭廁廂去縣參又雙發變敘疊葉號嘆吃各合吉吊同後向嚇嗎君吟否吧聽啟" +
        "吳呆呈告員周味呼命和詠咬哀品哇哈響哎啞哄哇哥哦哨哭喚售唯唱商啊問啡啤啥啦餵善喇喊喜" +
        "喝喧噴喻嘍嗅嗎嗓嗚嗜嘆嘉囑嘗嘛器噩噴團園困圍圖圓圈土聖在場坂均坐堅壇壩墳墜坡坤坦坪" +
        "垂垃型壘埃埋城域執培基堂堆墮堡堪塑塔塗塘塞填塵境增墨壁壯聲處備複夏外多夜夠夢大天太" +
        "夫央失頭誇夾奪奇奈奉奮奔獎套奧女妝婦媽妖妙妥妨妹妻姆姐姑姓委姿威娃婁嬌娘娜婚嬰嬸媒" +
        "媚子孔孕字存孫學孿寧宅宇守安宋完宏宗官定宛宜寶實寵審客宣室宮害宴宵家容寬賓宿寂寄密" +
        "富寒寓察寡寢寥實寧寨對尋導壽將尊小少爾尖尚嘗尤就尺尼盡尾局層居屈屋屎屏展屬山歲豈崗" +
        "島岩嶺岳岸峽峰崇崎崖崩嶄嵌工左巧巨差己已巴巷幣師希帳帕帖帝帥帶幀席幫常帽幅幕干平年" +
        "並幸幻幼幽廣莊慶庇床序庫應底店廟府龐廢度座庭庶康庸廉廊廓延建開異棄弄式弓引張彌弦彎" +
        "弱彈強歸當錄形彩影役徹彼往征徑待很律後徒得徘徙從禦微德心必憶忌忍忘忙忠憂快念忽懷態" +
        "怒怕憐思急總戀恐恆恕恩息懇惡惱悄悅悉悔悟悠患悲悶情驚惜惠惡懼慘懲慣想意愚感愛愧慈態" +
        "慌慎慕慢慰慶憂懂憲法應懶懷懸成我戒或戰截戲戶房所扁扇手才扎撲打托扛扣執擴掃揚擾扳扶" +
        "批找承技抄把抑抓投抗折撫拋搶護報披抬抱抵抹押抽擔拆拉拌拍拐拒拓拔拖拘招拜擬擁攔擰撥" +
        "擇括拭拯拱拳拷拼拾拿持指按挑挖挨挪振挺挽捂捆捉捏捐捕損撿換捧據挨掌排掘掛掠探接控" +
        "推掩措擲揉描提插握揣援擱摟攪搏搓搜搞搬搭攜攝擺搖攤摔摘摧摩摸撐撒撕撞撤撥撫播撰撲"

    private val SIMP_TO_TRAD: Map<Char, Char> by lazy {
        SIMP_CHARS.indices.associate { idx -> SIMP_CHARS[idx] to TRAD_CHARS[idx] }
    }

    private val TRAD_TO_SIMP: Map<Char, Char> by lazy {
        TRAD_CHARS.indices.associate { idx -> TRAD_CHARS[idx] to SIMP_CHARS[idx] }
    }
}
