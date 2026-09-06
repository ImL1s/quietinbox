package dev.quietinbox.platform.storage.repo

import java.util.Locale

/**
 * Demo content in the app's own language. [DemoDataRepository] authors its conversations in
 * Traditional Chinese and English; for a Simplified Chinese, Japanese or Korean app language the
 * Chinese halves (names, group titles, message bodies) are swapped for these equivalents at seed
 * time, so a store screenshot reads naturally in every listed language. Every name is invented.
 *
 * Only exact matches are replaced — an English body, a URL or an emoji-only line passes through —
 * and an unknown language leaves the seed untouched. Debug source set only, like the seeder.
 */
internal object DemoLocalisation {

    fun forLocale(locale: Locale): Map<String, String> = when {
        locale.language == "ja" -> JA
        locale.language == "ko" -> KO
        locale.language == "zh" && (locale.script == "Hans" || (locale.script.isEmpty() && locale.country in SIMPLIFIED_REGIONS)) -> ZH_HANS
        else -> emptyMap()
    }

    private val SIMPLIFIED_REGIONS = setOf("CN", "SG", "MY")

    private val ZH_HANS = mapOf(
        "我" to "我",
        // self
        "好，我等一下回你 👍" to "好，我等一下回你 👍",
        "收到，晚點再確認一次。" to "收到，晚点再确认一次。",
        "我先去吃飯，回來再看 🍜" to "我先去吃饭，回来再看 🍜",
        "已經處理好了，你再看看。" to "已经处理好了，你再看看。",
        // mia
        "林小美 Mia Lin" to "林小美 Mia Lin",
        "早安！今天的 meeting 改到下午三點了 ☕" to "早安！今天的 meeting 改到下午三点了 ☕",
        "剛剛路過那家新開的書店，週末一起去逛好嗎？📚" to "刚刚路过那家新开的书店，周末一起去逛好吗？📚",
        "我把行事曆更新了，你那邊看得到嗎？" to "我把日历更新了，你那边看得到吗？",
        "晚餐想吃什麼？我這邊隨便都可以 🍲" to "晚餐想吃什么？我这边随便都可以 🍲",
        "謝謝你今天幫我看那份文件，真的幫了大忙 🙏" to "谢谢你今天帮我看那份文件，真的帮了大忙 🙏",
        "還記得上次說的那個計畫嗎？我想我們可以先做一個小版本試試看，先不要一次做完，這樣比較容易調整方向，你覺得呢？" to "还记得上次说的那个计划吗？我想我们可以先做一个小版本试试看，先不要一次做完，这样比较容易调整方向，你觉得呢？",
        // wen
        "陳大文 Wen Chen" to "陈大文 Wen Chen",
        "下班了嗎？路上小心 🚲" to "下班了吗？路上小心 🚲",
        "我明天請假，有事打電話給我。" to "我明天请假，有事打电话给我。",
        "那個檔案我放在共用資料夾了。" to "那个文件我放在共享文件夹了。",
        "今天的雨真的很誇張 ☔" to "今天的雨真的很夸张 ☔",
        "您有一則新訊息 You have a new message" to "您有一条新消息 You have a new message",
        // product
        "產品團隊 Product Team" to "产品团队 Product Team",
        "王雅琪 Yaqi Wang" to "王雅琪 Yaqi Wang",
        "黃冠宇 Kuan-Yu Huang" to "黄冠宇 Kuan-Yu Huang",
        "站立會議 standup 十分鐘後開始 ⏰" to "站会 standup 十分钟后开始 ⏰",
        "設計稿更新了，麻煩大家在明天前給回饋 🎨" to "设计稿更新了，麻烦大家在明天前给反馈 🎨",
        "這週的數字比上週好一點，但樣本還太小，先不要下結論。" to "这周的数字比上周好一点，但样本还太小，先不要下结论。",
        "有人可以幫忙 review 這個小改動嗎？只有兩行 🙋" to "有人可以帮忙 review 这个小改动吗？只有两行 🙋",
        "我把待辦清單整理成三類：必須做、可以做、不做。第三類其實最重要，因為寫下來之後大家就不會一直重新討論它了。" to "我把待办清单整理成三类：必须做、可以做、不做。第三类其实最重要，因为写下来之后大家就不会一直重新讨论它了。",
        "更正：發布時間改成下週二 10:00（Correction: release moved to Tuesday 10:00）" to "更正：发布时间改成下周二 10:00（Correction: release moved to Tuesday 10:00）",
        "發布時間是下週一 10:00（Release is Monday 10:00）" to "发布时间是下周一 10:00（Release is Monday 10:00）",
        // design
        "設計評審 Design Review" to "设计评审 Design Review",
        "李哲宇 Che-Yu Li" to "李哲宇 Che-Yu Li",
        "第三版的間距看起來舒服多了 ✨" to "第三版的间距看起来舒服多了 ✨",
        "字級在小螢幕上還是有點擠。" to "字号在小屏幕上还是有点挤。",
        "深色模式的對比通過了嗎？" to "深色模式的对比通过了吗？",
        // family
        "家人群組 Family" to "家人群组 Family",
        "媽媽 Mom" to "妈妈 Mom",
        "爸爸 Dad" to "爸爸 Dad",
        "姊姊 Sis" to "姐姐 Sis",
        "晚餐煮好了，記得回來吃 🍚" to "晚饭做好了，记得回来吃 🍚",
        "禮拜天要不要一起去公園走走？🌳" to "星期天要不要一起去公园走走？🌳",
        "冰箱裡還有水果，記得吃 🍎" to "冰箱里还有水果，记得吃 🍎",
        "今天的夕陽好漂亮 🌇" to "今天的夕阳好漂亮 🌇",
        "我下週三會晚點回家，你們先吃。" to "我下周三会晚点回家，你们先吃。",
        "[照片] Photo from the weekend 🌊" to "[照片] Photo from the weekend 🌊",
        // landlord
        "房東 Landlord" to "房东 Landlord",
        "水電費這個月是 1,240 元，麻煩月底前繳。" to "水电费这个月是 1,240 元，麻烦月底前交。",
        "樓下的垃圾車時間改了，晚上七點半。" to "楼下的垃圾车时间改了，晚上七点半。",
        "好" to "好",
        // book club
        "Book club 讀書會" to "Book club 读书会",
        "張書豪 Shu-Hao Chang" to "张书豪 Shu-Hao Chang",
        "這個月選的書比想像中好看 📖" to "这个月选的书比想象中好看 📖",
        "第三章的論點我不太同意，想聽聽大家怎麼想。" to "第三章的论点我不太同意，想听听大家怎么想。",
        "有人要順便帶咖啡嗎？☕" to "有人要顺便带咖啡吗？☕",
        // classmates
        "舊班級群組 Old classmates" to "老同学群 Old classmates",
        "班長 Class rep" to "班长 Class rep",
        "吳庭安 Ting-An Wu" to "吴庭安 Ting-An Wu",
        "同學會的日期先訂在十一月 🎓" to "同学会的日期先定在十一月 🎓",
        "照片我掃描好了，晚點傳到群組。" to "照片我扫描好了，晚点传到群里。",
    )

    private val JA = mapOf(
        "我" to "自分",
        "好，我等一下回你 👍" to "了解、あとで返事するね 👍",
        "收到，晚點再確認一次。" to "受け取ったよ。あとでもう一度確認する。",
        "我先去吃飯，回來再看 🍜" to "先にご飯食べてくる、戻ったら見るね 🍜",
        "已經處理好了，你再看看。" to "対応しておいたから、確認してみて。",
        "林小美 Mia Lin" to "林 美咲 Misaki Hayashi",
        "早安！今天的 meeting 改到下午三點了 ☕" to "おはよう！今日の meeting は午後 3 時に変更になったよ ☕",
        "剛剛路過那家新開的書店，週末一起去逛好嗎？📚" to "さっき新しくできた本屋の前を通ったんだ。週末いっしょに行かない？📚",
        "我把行事曆更新了，你那邊看得到嗎？" to "カレンダーを更新したよ。そっちで見える？",
        "晚餐想吃什麼？我這邊隨便都可以 🍲" to "夕飯なに食べたい？こっちはなんでもいいよ 🍲",
        "謝謝你今天幫我看那份文件，真的幫了大忙 🙏" to "今日は書類を見てくれてありがとう。本当に助かった 🙏",
        "還記得上次說的那個計畫嗎？我想我們可以先做一個小版本試試看，先不要一次做完，這樣比較容易調整方向，你覺得呢？" to "この前話した計画、覚えてる？まず小さい版を作って試してみて、一度に全部やらないほうが方向を直しやすいと思うんだけど、どう？",
        "陳大文 Wen Chen" to "田中 大輔 Daisuke Tanaka",
        "下班了嗎？路上小心 🚲" to "仕事終わった？気をつけて帰ってね 🚲",
        "我明天請假，有事打電話給我。" to "明日は休みを取るから、何かあったら電話して。",
        "那個檔案我放在共用資料夾了。" to "あのファイルは共有フォルダーに置いておいたよ。",
        "今天的雨真的很誇張 ☔" to "今日の雨、ほんとにすごいね ☔",
        "您有一則新訊息 You have a new message" to "新着メッセージがあります You have a new message",
        "產品團隊 Product Team" to "プロダクトチーム Product Team",
        "王雅琪 Yaqi Wang" to "佐藤 由紀 Yuki Sato",
        "黃冠宇 Kuan-Yu Huang" to "高橋 健 Ken Takahashi",
        "站立會議 standup 十分鐘後開始 ⏰" to "standup は 10 分後に始まります ⏰",
        "設計稿更新了，麻煩大家在明天前給回饋 🎨" to "デザイン案を更新しました。明日までにフィードバックをお願いします 🎨",
        "這週的數字比上週好一點，但樣本還太小，先不要下結論。" to "今週の数字は先週より少し良いけど、サンプルがまだ小さいので結論は急がないで。",
        "有人可以幫忙 review 這個小改動嗎？只有兩行 🙋" to "この小さな変更、誰か review してもらえますか？2 行だけです 🙋",
        "我把待辦清單整理成三類：必須做、可以做、不做。第三類其實最重要，因為寫下來之後大家就不會一直重新討論它了。" to "やることリストを 3 つに整理しました。必須、できれば、やらない。実は 3 つ目がいちばん大事で、書いておけば同じ議論を繰り返さずに済みます。",
        "更正：發布時間改成下週二 10:00（Correction: release moved to Tuesday 10:00）" to "訂正: リリースは来週火曜 10:00 に変更（Correction: release moved to Tuesday 10:00）",
        "發布時間是下週一 10:00（Release is Monday 10:00）" to "リリースは来週月曜 10:00 です（Release is Monday 10:00）",
        "設計評審 Design Review" to "デザインレビュー Design Review",
        "李哲宇 Che-Yu Li" to "中村 悠 Yu Nakamura",
        "第三版的間距看起來舒服多了 ✨" to "第 3 版の余白はずっと見やすくなったね ✨",
        "字級在小螢幕上還是有點擠。" to "小さい画面だと文字サイズがまだ少し窮屈。",
        "深色模式的對比通過了嗎？" to "ダークモードのコントラストは基準を通った？",
        "家人群組 Family" to "家族グループ Family",
        "媽媽 Mom" to "お母さん Mom",
        "爸爸 Dad" to "お父さん Dad",
        "姊姊 Sis" to "お姉ちゃん Sis",
        "晚餐煮好了，記得回來吃 🍚" to "夕飯できたよ、帰ってきて食べてね 🍚",
        "禮拜天要不要一起去公園走走？🌳" to "日曜日、いっしょに公園を散歩しない？🌳",
        "冰箱裡還有水果，記得吃 🍎" to "冷蔵庫に果物があるから食べてね 🍎",
        "今天的夕陽好漂亮 🌇" to "今日の夕焼け、きれいだね 🌇",
        "我下週三會晚點回家，你們先吃。" to "来週の水曜は帰りが遅くなるから、先に食べてて。",
        "[照片] Photo from the weekend 🌊" to "[写真] Photo from the weekend 🌊",
        "房東 Landlord" to "大家さん Landlord",
        "水電費這個月是 1,240 元，麻煩月底前繳。" to "今月の光熱費は 1,240 円です。月末までにお願いします。",
        "樓下的垃圾車時間改了，晚上七點半。" to "ごみ収集の時間が夜 7 時半に変わりました。",
        "好" to "はい",
        "Book club 讀書會" to "Book club 読書会",
        "張書豪 Shu-Hao Chang" to "山本 翔 Sho Yamamoto",
        "這個月選的書比想像中好看 📖" to "今月の課題本、思ったより面白い 📖",
        "第三章的論點我不太同意，想聽聽大家怎麼想。" to "第 3 章の主張にはあまり賛成できない。みんなはどう思う？",
        "有人要順便帶咖啡嗎？☕" to "ついでにコーヒー買ってくる人いる？☕",
        "舊班級群組 Old classmates" to "同窓会グループ Old classmates",
        "班長 Class rep" to "学級委員 Class rep",
        "吳庭安 Ting-An Wu" to "小林 花 Hana Kobayashi",
        "同學會的日期先訂在十一月 🎓" to "同窓会の日程はひとまず 11 月で 🎓",
        "照片我掃描好了，晚點傳到群組。" to "写真をスキャンしたので、あとでグループに送るね。",
    )

    private val KO = mapOf(
        "我" to "나",
        "好，我等一下回你 👍" to "알았어, 조금 있다가 답할게 👍",
        "收到，晚點再確認一次。" to "받았어. 이따가 한 번 더 확인할게.",
        "我先去吃飯，回來再看 🍜" to "밥 먼저 먹고 와서 볼게 🍜",
        "已經處理好了，你再看看。" to "처리해 뒀으니까 한번 봐 줘.",
        "林小美 Mia Lin" to "김미아 Mia Kim",
        "早安！今天的 meeting 改到下午三點了 ☕" to "좋은 아침! 오늘 meeting은 오후 3시로 바뀌었어 ☕",
        "剛剛路過那家新開的書店，週末一起去逛好嗎？📚" to "방금 새로 생긴 서점 앞을 지났는데, 주말에 같이 가 볼래? 📚",
        "我把行事曆更新了，你那邊看得到嗎？" to "캘린더 업데이트했어. 거기서 보여?",
        "晚餐想吃什麼？我這邊隨便都可以 🍲" to "저녁 뭐 먹고 싶어? 난 아무거나 괜찮아 🍲",
        "謝謝你今天幫我看那份文件，真的幫了大忙 🙏" to "오늘 그 문서 봐 줘서 고마워. 정말 큰 도움이 됐어 🙏",
        "還記得上次說的那個計畫嗎？我想我們可以先做一個小版本試試看，先不要一次做完，這樣比較容易調整方向，你覺得呢？" to "지난번에 얘기한 그 계획 기억나? 먼저 작은 버전을 만들어서 시험해 보고, 한 번에 다 하지 않는 게 방향 잡기 쉬울 것 같은데, 어떻게 생각해?",
        "陳大文 Wen Chen" to "박대현 Daehyun Park",
        "下班了嗎？路上小心 🚲" to "퇴근했어? 조심히 와 🚲",
        "我明天請假，有事打電話給我。" to "내일 휴가야. 무슨 일 있으면 전화해.",
        "那個檔案我放在共用資料夾了。" to "그 파일은 공유 폴더에 넣어 뒀어.",
        "今天的雨真的很誇張 ☔" to "오늘 비 진짜 엄청나다 ☔",
        "您有一則新訊息 You have a new message" to "새 메시지가 있습니다 You have a new message",
        "產品團隊 Product Team" to "제품팀 Product Team",
        "王雅琪 Yaqi Wang" to "이서연 Seoyeon Lee",
        "黃冠宇 Kuan-Yu Huang" to "최준호 Junho Choi",
        "站立會議 standup 十分鐘後開始 ⏰" to "standup 10분 뒤에 시작합니다 ⏰",
        "設計稿更新了，麻煩大家在明天前給回饋 🎨" to "디자인 시안 업데이트했어요. 내일까지 피드백 부탁드려요 🎨",
        "這週的數字比上週好一點，但樣本還太小，先不要下結論。" to "이번 주 수치는 지난주보다 조금 낫지만 표본이 아직 작으니 결론은 미루죠.",
        "有人可以幫忙 review 這個小改動嗎？只有兩行 🙋" to "이 작은 변경 review 해 줄 사람? 두 줄뿐이에요 🙋",
        "我把待辦清單整理成三類：必須做、可以做、不做。第三類其實最重要，因為寫下來之後大家就不會一直重新討論它了。" to "할 일을 세 가지로 정리했어요: 꼭 할 것, 해도 되는 것, 안 할 것. 사실 세 번째가 제일 중요한데, 적어 두면 같은 논의를 반복하지 않게 되거든요.",
        "更正：發布時間改成下週二 10:00（Correction: release moved to Tuesday 10:00）" to "정정: 배포는 다음 주 화요일 10:00으로 변경（Correction: release moved to Tuesday 10:00）",
        "發布時間是下週一 10:00（Release is Monday 10:00）" to "배포는 다음 주 월요일 10:00입니다（Release is Monday 10:00）",
        "設計評審 Design Review" to "디자인 리뷰 Design Review",
        "李哲宇 Che-Yu Li" to "정우진 Woojin Jung",
        "第三版的間距看起來舒服多了 ✨" to "세 번째 버전의 간격이 훨씬 편안해 보여요 ✨",
        "字級在小螢幕上還是有點擠。" to "작은 화면에서는 글자 크기가 아직 좀 답답해요.",
        "深色模式的對比通過了嗎？" to "다크 모드 대비는 기준을 통과했나요?",
        "家人群組 Family" to "가족 단톡방 Family",
        "媽媽 Mom" to "엄마 Mom",
        "爸爸 Dad" to "아빠 Dad",
        "姊姊 Sis" to "언니 Sis",
        "晚餐煮好了，記得回來吃 🍚" to "저녁 다 됐어, 들어와서 먹어 🍚",
        "禮拜天要不要一起去公園走走？🌳" to "일요일에 같이 공원 산책할래? 🌳",
        "冰箱裡還有水果，記得吃 🍎" to "냉장고에 과일 있으니까 챙겨 먹어 🍎",
        "今天的夕陽好漂亮 🌇" to "오늘 노을 정말 예쁘다 🌇",
        "我下週三會晚點回家，你們先吃。" to "다음 주 수요일엔 늦게 들어가니까 먼저 먹어.",
        "[照片] Photo from the weekend 🌊" to "[사진] Photo from the weekend 🌊",
        "房東 Landlord" to "집주인 Landlord",
        "水電費這個月是 1,240 元，麻煩月底前繳。" to "이번 달 공과금은 12,400원입니다. 월말까지 부탁드려요.",
        "樓下的垃圾車時間改了，晚上七點半。" to "쓰레기 수거 시간이 저녁 7시 반으로 바뀌었어요.",
        "好" to "네",
        "Book club 讀書會" to "Book club 독서 모임",
        "張書豪 Shu-Hao Chang" to "한지훈 Jihoon Han",
        "這個月選的書比想像中好看 📖" to "이번 달 책은 생각보다 재밌네요 📖",
        "第三章的論點我不太同意，想聽聽大家怎麼想。" to "3장의 주장에는 잘 동의가 안 돼요. 다들 어떻게 생각하세요?",
        "有人要順便帶咖啡嗎？☕" to "오는 길에 커피 사 올 사람? ☕",
        "舊班級群組 Old classmates" to "옛 반 친구들 Old classmates",
        "班長 Class rep" to "반장 Class rep",
        "吳庭安 Ting-An Wu" to "오수빈 Subin Oh",
        "同學會的日期先訂在十一月 🎓" to "동창회 날짜는 일단 11월로 🎓",
        "照片我掃描好了，晚點傳到群組。" to "사진 스캔했어. 이따가 단톡방에 올릴게.",
    )
}
