# Round 18 獨立審查（Claude subagent）— 在地化：zh-Hans、ja、ko

- 審查對象：`git diff 69a60b4..7ef07de`（單一 commit `7ef07de`，branch `main`）
- 審查方式：唯讀。讀完五份 `strings.xml` / `strings_analytics.xml` / `plurals.xml`、`platform/capture` 的 listener label、`tools/check-strings.py`、`locales_config.xml`、`localeFilters`、`tools/demo-screenshots.sh`、五個語系的 Play 文案與 what's-new、`docs/zh-Hant/SCOPE.md` 對照 `docs/SCOPE.md`、CHANGELOG／TEST_MATRIX／CLAUDE.md／RELEASE.md 的變更；另外在 scratchpad 複本上對 `check-strings.py` 做了六種突變測試，用 `aapt2` 傾印了 repo 內既有的 debug／release APK（未重新建置），並逐張看過 ja-JP、ko-KR、zh-CN 的示範截圖。
- 我實際跑過的指令：`python3 tools/check-strings.py` → `OK: 0 error(s), 0 warning(s)`；`./gradlew :core:designsystem:lintDebug --console=plain -q` → exit 0（只輸出報表路徑，沒有任何 finding）。沒有跑 instrumented test、沒有碰裝置或模擬器、沒有改 repo 任何檔案。

## Verdict: APPROVE WITH MINOR FIXES

沒有 Critical：三個新目錄的佔位符、`%%`、`\n`、韓文助詞與 `ob_step_of` 的參數順序全部正確；沒有任何一句翻譯把隱私承諾說得比英文多或少。但有兩個**不是措辭**、而是功能／商店素材層級的 Important 缺陷，建議在下一次 Play 上傳（新語言的商店文案與截圖）之前修掉：

1. `localeFilters` 用 `b+zh+Hans` / `b+zh+Hant` 過濾，AndroidX（Compose material3）自帶的 `values-zh-rCN` / `values-zh-rTW` / `values-zh-rHK` 資源全被丟掉，所以兩種中文的使用者在提醒時間選擇器、活動頁自訂日期區間選擇器與所有 AndroidX 內容描述看到的是英文；日文與韓文卻是本地化的。0.1.0 對 zh-Hant 就已如此，這個 commit 把同樣的過濾套到 zh-Hans，且 brief 明確問了這題。
2. ja-JP 的 `5_capture.png` 與 `4_activity.png` 位元組完全相同（md5 `3620b740…`，docs 與 fastlane 兩份都是），日文商店清單沒有「キャプチャ」畫面、「アクティビティ」出現兩次。根因是日文的 `analytics_tile_captured`（"キャプチャ"）與 `nav_health`（"キャプチャ"）字面相同，`tap_text` 取第一個完全相符的節點，點到了活動頁的統計方塊而不是底部分頁。

統計：Critical 0 · Important 13（翻譯 9：zh-Hans 1、ja 4、ko 4；商店素材 3；程式 1）· Minor 36（詳列如下）。

---

## 一、翻譯品質（逐語言）

語域檢查結果：zh-Hans 全程用「你」（與 zh-Hant 一致）；ja 全程敬體（です／ます），短標籤用體言止め；ko 陳述句合니다체、祈使／疑問句 해요체（～하세요／～할까요?），三者各自一致，沒有混用。Android 系統詞彙（通知使用权／受限制的设置／应用信息／工作资料／特殊应用权限／设备和应用通知；通知へのアクセス／制限付き設定／アプリ情報／仕事用プロファイル／特別なアプリアクセス／デバイスとアプリの通知；알림 액세스／제한된 설정／앱 정보／직장 프로필／특별 앱 액세스／기기 및 앱 알림）都對上了 OS 自己的字串。zh-Hans 是道地的大陸用語（应用／设置／消息／软件／存储／崩溃／队列／进程／字符串／视图／标签页／热力图／自定义／工作日），不是機械轉換。

### 1.1 簡體中文（`values-b+zh+Hans`）

| 名稱 | 目前 | 問題 | 建議 | 嚴重度 |
| --- | --- | --- | --- | --- |
| `inbox_banner_locked`、`health_vault_locked`、`backup_import_desc`、`backup_failed_vault`、`ob_scope_point_4`、`vault_locked_title`、`vault_locked_reset`、`vault_reset_confirm_*`、`delete_everything_desc` vs `delete_everything_step_database`、`delete_everything_step_reopen`、商店文案 | vault 一下是「数据库」（加密数据库／数据库已锁定）、一下是「保险库」（删除保险库数据库／创建新的保险库；商店與 changelog 都用「保险库」） | 同一個概念兩個名字；使用者從商店看到「保险库」，進 App 看到「数据库已锁定」。zh-Hant 也有同樣的 資料庫／金庫 分裂，這裡是照抄過來的 | 全部統一為「保险库」：例如 `vault_locked_title`→「保险库已锁定」、`inbox_banner_locked`→「无法打开加密保险库…」、`ob_scope_point_4`→「…存在加密保险库中」、`backup_failed_vault`→「保险库已锁定。」、`vault_locked_reset`→「重新开始（删除保险库）」。zh-Hant 同步改成「金庫」 | Important |
| `analytics_tile_conversations`、`analytics_top_conversations`、`reminder_body_count` | 「会话」 | 其餘 20 幾處（`inbox_delete_title`、`conv_empty`、`backup_result_ok`、`dev_result_seeded`、`analytics_unknown_conversation`…）都是「对话」。同鏡 zh-Hant 的 會話／對話 分裂 | 統一為「对话」（「最活跃的对话」、「有 %1$d 个对话有新的副本等你查看。」） | Minor |
| `listener_settings_manual` | 「此设备没有静读能打开的…並启用静读」 | 全目錄只有這一條用「静读」稱呼自己，其餘 40 多處都是「QuietInbox」（zh-Hant 同） | 改為「QuietInbox」 | Minor |
| `conv_open_source_body` | 「在那边打开聊天可能让对方看到已读」 | 英文是 "may mark messages as read on the source side"（來源端把訊息標為已讀），翻譯把它窄化成「對方看到已讀」；三種語言（含 zh-Hant）都是同一種讀法。後半句「在这里查看副本则永远不会」的承諾有保住 | 「在那边打开聊天可能会在来源应用中把消息标为已读；在这里查看副本则永远不会。」 | Minor |
| `conv_origin_resync` | 「重连后对账」 | 「对账」是會計用語（zh-Hant「對帳」同） | 「重连后比对」或「重连后核对」 | Minor |
| `health_no_gaps` | 「本次连接没有记录到中断。」 | 英文是 "in this session"；ja／ko 都譯成「セッション／세션」，zh 兩版譯成「连接／連線」 | 「本次运行期间没有记录到中断。」（或保留，但三語一致） | Minor |
| `health_resume` vs `backup_import` / `section_backup` / `gap_reason_maintenance` / `inbox_banner_locked` | 「恢复捕获」 vs 「从备份恢复」「备份与恢复」「重置或恢复进行中」「恢复选项」 | resume 與 restore 與 recovery 三個概念共用「恢复」 | `health_resume`→「继续捕获」 | Minor |
| `health_restricted_hint` | 「点右上角菜单」 | 英文只說 "tap the menu"，「右上角」是翻譯自加；目前 Android 的「应用信息」溢位選單確實在右上，無害 | 可保留；若要嚴格對齊英文則去掉「右上角」 | Minor |
| `section_reminders` | 「你自己的提醒」 | 直譯，語感生硬（zh-Hant「自己的提醒」同） | 「QuietInbox 的提醒」或「本应用的提醒」 | Minor |
| `analytics_quiet_formula` | 「来源应用当天没有推送通知」 | 英文 "posted nothing to the shade"；「推送」在大陸語境容易被理解成伺服器推播 | 「…当天没有发出通知」 | Minor |

### 1.2 日文（`values-ja`）

| 名稱 | 目前 | 問題 | 建議 | 嚴重度 |
| --- | --- | --- | --- | --- |
| `analytics_tile_captured` | 「キャプチャ」 | 與底部分頁 `nav_health`「キャプチャ」字面完全相同。語意上這是 "captured"（已擷取的數量），不是動作名；而且這個相同直接造成 `tools/demo-screenshots.sh` 的 `tap_text "キャプチャ"` 在活動頁點到統計方塊、`5_capture.png` 變成活動頁的複本（見第四節） | 「キャプチャ済み」 | Important |
| `conv_source`（「ソース: %1$s」）vs 同一畫面的 `conv_open_source`／`conv_open_source_title`／`conv_time_source`（「送信元アプリ」「送信元の時刻」）；`identity_verified`「送信元 ID」；`ob_preview_body`「送信元」；`health_connected_body`、`analytics_disclaimer`、`ob_scope_point_3`「送信元（アプリ）」 | source 在「擷取／來源清單」情境是「ソース」，在訊息情境是「送信元」 | 兩詞並存本身可以理解（ソース 偏術語、送信元 偏自然），但對話頁面同時出現「ソース: LINE」與「送信元アプリで開く」，使用者會以為是兩個東西 | 至少把 `conv_source` 改成「送信元: %1$s」，讓對話頁面一致；「ソース」保留給擷取頁的清單、`search_all_sources`、`analytics_tile_sources`、`ob_sources_title`。若要全目錄單一用詞則全改「送信元」（「送信元を追加」「すべての送信元」） | Important |
| `action_remove`、`health_remove_source`、`health_remove_title` | 「取り除く」「ソースを取り除く」「%1$s を取り除きますか？」 | 「取り除く」是物理上「移開」，UI 不會這樣寫；Android／Google 的 Remove 一律是「削除」（例：アカウントを削除）。商店 changelog 5 自己也寫「ソースの…削除」 | 「削除」／「ソースを削除」／「%1$s を削除しますか？」；對話框本文已說明「停止」與「コピーの削除」是兩件事，不會混淆。若想與 `action_delete` 區隔，可用「解除」 | Important |
| `search_empty_hint` | 「中国語のテキストは部分一致、ラテン文字の単語は…」 | `core/model/Normalization.isCjk` 把 HIRAGANA、KATAKANA、HANGUL_SYLLABLES 與漢字一樣做二元組索引，日文（假名＋漢字）同樣是子字串比對，但這句只提「中国語」，日本使用者不知道自己的文字怎麼搜。根因在英文原文 "Chinese text" 就寫窄了 | 「日本語・中国語・韓国語などの CJK テキストは部分一致、ラテン文字の単語は…」；同時把 `values/strings.xml` 改成 "CJK text (Chinese, Japanese, Korean) matches as a substring"，其他目錄跟著改 | Important |
| `analytics_title`「アクティビティ」 vs `nav_analytics`「活動」 | 分頁標籤為了不換行改成「活動」，頁面大標仍是「アクティビティ」 | 同一頁兩個名字（`4_activity.png` 上下同時可見）；商店文案又用「アクティビティ」 | 把 `analytics_title` 也改成「活動」，或改成「活動（アクティビティ）」；商店文案跟著用「活動」 | Minor |
| `theme_system` | 「システムに従う」 | 在 360 dp 的 `SingleChoiceSegmentedButtonRow` 第一格（含勾號）文字貼到分隔線（`6_settings.png` 可見），放大字體會被裁 | 「システム」（與 ko「시스템」對齊） | Minor |
| `state_degraded` | 「低下」 | 單獨作為狀態名太生硬 | 「一部制限」或「機能低下」 | Minor |
| `health_queue` | 「キュー」 | 這是計數標籤 "queued"，「キュー」是名詞「佇列」 | 「キュー待ち」或「待機中」 | Minor |
| `ui_lock_desc` | 「これは UI 上の関門であり」 | 「関門」直譯 "gate" | 「これは UI 上のロックであり」 | Minor |
| `section_about` | 「情報」 | 太籠統 | 「このアプリについて」 | Minor |
| `gap_reason_paused` | 「ユーザーが一時停止」 | 英文 "paused by you"，第二人稱變第三人稱 | 「自分で一時停止」 | Minor |
| `listener_settings_manual` | 「通知アクセスの設定画面」…「通知へのアクセス」 | 同一句裡同一詞兩種寫法 | 前者改「通知へのアクセスの設定画面」 | Minor |
| `reminders_desc` | 「サイレントモード」 | Android ja 系統字串是「サイレント モード」（中間有半形空格） | 「サイレント モード」 | Minor |
| `nav_inbox`／`inbox_title` | 「受信箱」 | 可用；Google 系（Gmail）是「受信トレイ」 | 保留亦可；若要貼近 OS 用「受信トレイ」（4 字，分頁不會換行） | Minor |

### 1.3 韓文（`values-ko`）

| 名稱 | 目前 | 問題 | 建議 | 嚴重度 |
| --- | --- | --- | --- | --- |
| `conv_source`「출처: %1$s」、`conv_time_source`「출처 시각」、`identity_verified`「확인된 출처 ID」、`ob_preview_body`「출처」；`conv_open_source*`「원본 앱」、`health_connected_body`／`analytics_disclaimer`／`ob_scope_point_2`／`ob_scope_point_3`／`inbox_delete_body`／`media_disclosure_body`／`about_limitations_body`／`analytics_quiet_formula`「원본（앱）」；`health_sources_title`／`health_add_source`／`search_all_sources`／`analytics_tile_sources`／`ob_sources_title`「소스」 | source 有三種譯法：소스／출처／원본 | 同一概念三個詞，跨頁面對不上（擷取頁「소스 추가」、對話頁「출처:」、對話框「원본 앱」） | 統一為「소스」：`conv_source`→「소스: %1$s」、`conv_time_source`→「소스 시각」、`identity_verified`→「확인된 소스 ID」、`ob_preview_body`「소스, 캡처 시각, … 소스 시각」；「원본 앱」→「소스 앱」。「원본」只留給「원본 알림」（原始通知）。商店文案的「출처 시각」「원본 앱」同步 | Important |
| `action_archive`「보관」、`action_unarchive`「보관 해제」、`inbox_filter_archived`「보관됨」、`inbox_empty_archived_title`「보관된 대화가 없습니다」 vs `section_retention`「보관 기간」、`retention_days`「사본을 %1$d일 동안 보관」、`journal_ttl`「…보관」、`app_tagline`／`ob_scope_title`／`ob_sources_body`「보관」、vault「보관소」 | archive、retention、keep、vault 全部落在「보관」這個詞根 | 收件匣篩選「보관됨」（已封存）與設定「보관 기간」（保留期限）在使用者眼中會混成一件事；「보관소」又是金庫 | archive 改用 Gmail 韓文的「보관처리」：`action_archive`→「보관처리」、`action_unarchive`→「보관처리 취소」、`inbox_filter_archived`→「보관처리됨」、`inbox_empty_archived_title`→「보관처리된 대화가 없습니다」。其餘「보관」保留 | Important |
| `inbox_unviewed` | 「확인 안 함」 | 這是收件匣列上的狀態標籤 "Unviewed"，「확인 안 함」讀起來像動作（不確認／未勾選） | 「미확인」 | Important |
| `search_empty_hint` | 「중국어 텍스트는 부분 문자열로…」 | 同日文：Hangul 音節也是二元組索引，韓文本身就是子字串比對，但句子只提中文 | 「한국어·중국어·일본어 등 CJK 텍스트는 부분 문자열로, 라틴 문자 단어는…」 | Important |
| `health_dropped` | 「권한 철회 후 삭제됨」 | "dropped" 是佇列丟棄，「삭제」與 `action_delete` 同字，會被讀成「副本被刪了」 | 「권한 철회 후 폐기됨」 | Minor |
| `health_source_enabled` | 「사용 중」 | "Enabled"；「사용 중」是「使用中」 | 「사용 설정됨」（與 `backup_import_desc` 的「사용 설정하세요」、`inbox_empty_body`「사용 설정한 소스」一致） | Minor |
| `theme_system` | 「시스템」 | Android ko 是「시스템 기본값」；「시스템」可接受 | 保留或「시스템 기본값」 | Minor |
| `ui_lock_desc` | 「이는 UI 관문일 뿐」 | 「관문」直譯 | 「이는 UI 단계의 잠금일 뿐」 | Minor |
| `health_gap_unknown_time` | 「시각 불명」 | 「불명」偏書面 | 「시각 알 수 없음」 | Minor |
| `conv_open_source_body` | 「상대방에게 읽음 표시가 될 수 있습니다」 | 同 zh-Hans 該條的窄化 | 「그곳에서 채팅을 열면 소스 앱에서 메시지가 읽음으로 표시될 수 있습니다. 여기서 사본을 보는 것만으로는 절대 그렇게 되지 않습니다.」 | Minor |

---

## 二、格式安全

- 佔位符：`tools/check-strings.py` 對三個新目錄回報 0 error；我另外逐條看過含 `%` 的字串，`%1$s`／`%1$d`／`%2$d`／`%3$d`／`%%`（`analytics_share`、`analytics_quiet_value`、`analytics_band_dominant`）全部保留。韓文 `ob_step_of`「%2$d단계 중 %1$d단계」、`analytics_quiet_days`「%2$d일 중 %1$d일」、日文 `analytics_band_dominant`「観測 %3$d 件中 %2$d%%」的重排都正確（腳本用排序後比較，所以重排不會誤判）。
- 韓文助詞：`conv_open_source_body`「%1$s을(를) 실행합니다」、`health_remove_title`「%1$s을(를) 제거할까요?」正確；`health_since`「%1$s부터」、`conv_source`「출처: %1$s」不需要助詞處理。
- `about_limitations_body`：三個目錄都保留五個 `\n`。
- XML：三個目錄沒有 ASCII 撇號需要跳脫；`section_privacy`／`section_backup`／`listener_settings_manual` 的 `&amp;` 在譯文裡變成「与／と／및」，合理。韓文用 ‘ ’ 彎引號、日文用「」、簡中用 “ ”，都不需跳脫。`ET.parse` 全部成功。
- 複數形：zh／ja／ko 只有 `other` 是 CLDR 正確做法；三個目錄的 `reminder_body_count`、`ambiguous_count_plural`、`messages_count_plural` 都只有 `other` 且佔位符與英文 `other` 一致。`ReminderScheduler.kt:148` 用 `getQuantityString(R.plurals.reminder_body_count, unviewed, unviewed)` 取值，OK。
- `delete_everything_confirm_body` 三語都保留字面「DELETE」，`SettingsScreen.kt:349` 比對的就是 `"DELETE"`，一致。

## 三、長度／版面（360 dp）

看過 ja-JP／ko-KR／zh-CN 的 `1_inbox`、`4_activity`、`5_capture`（ko）、`6_settings`（ja）、`2_conversation`（zh-CN）：

- 底部分頁：受信箱／検索／活動／キャプチャ／設定、받은편지함／검색／활동／캡처／설정、收件箱／搜索／活动／捕获／设置 都單行，沒有換行；「받은편지함」與「キャプチャ」（各 5 字）是最長的，還有餘裕。
- 活動頁的 tab row 是可捲動的，「最適な時間帯」「おしゃべり度」「최적 시간대」「수다스러움」「健谈度」都沒問題；期間 chip（7 日間／今月／先月／3 か月／すべて、7일／이번 달／지난달／3개월／전체、近 7 天／本月／上个月／近 3 个月／全部）也正常。
- 唯一貼邊的是 ja `theme_system`「システムに従う」（見 1.2）。
- 對話框雙按鈕「停止してコピーは残す」／「停止してコピーも削除」、「중지하고 사본 유지」／「중지하고 사본 삭제」各 10 字：Material AlertDialog 的按鈕列會自動換行，不會被截。`vault_locked_reset` 日文 16 字、英文 30 字，長度相當。
- 收件匣摘要（`inbox_summary` + `inbox_summary_gap`）三語都在第二行以「…」截斷，與英文行為相同。

## 四、商店文案（`fastlane/metadata/android/{zh-CN,ja-JP,ko-KR}`、`fastlane/whatsnew`）

Play 上限全部通過（字元數，Python `len()`）：

| 語系 | title（≤30） | short（≤80） | full（≤4000） | changelog 4（≤500） | changelog 5 / whatsnew（≤500） |
| --- | --- | --- | --- | --- | --- |
| zh-CN | 13 | 36 | 927 | 39 | 152 |
| ja-JP | 10 | 36 | 1260 | 64 | 231 |
| ko-KR | 10 | 40 | 1356 | 57 | 256 |

發現：

| 項目 | 問題 | 建議 | 嚴重度 |
| --- | --- | --- | --- |
| `ja-JP/images/phoneScreenshots/5_capture.png`（與 `docs/screenshots/phone/ja-JP/5_capture.png`） | 與 `4_activity.png` 位元組相同（md5 `3620b7406f7efa9f8aced428c048d53d`，四個檔案一樣）。日文清單沒有擷取頁截圖，活動頁重複兩張；brief 說「截圖已逐張檢視、只發現分頁換行」與此矛盾。根因：`analytics_tile_captured`＝`nav_health`＝「キャプチャ」，`demo-screenshots.sh` 的 `tap_text` 取 uiautomator dump 第一個完全相符節點，在活動頁點到了統計方塊而非分頁，`shot "5_capture"` 因此拍到同一頁而且沒有 warn | 改 `analytics_tile_captured`→「キャプチャ済み」後重跑 `tools/demo-screenshots.sh <serial> ja-JP <out>`；同時讓 `tap_text` 在找分頁時限制在 NavigationBar（用 bounds 落在畫面底部 15% 或 `resource-id`／`class` 過濾），或在 `shot` 前比對前一張的 md5 並 warn | Important |
| zh-CN／ja-JP／ko-KR 的所有截圖 | 示範資料（`DemoDataRepository`，debug source set）沒有語系分支，內容固定是繁體中文＋英文（「我把行事曆更新了，你那邊看得到嗎？」「產品團隊」「讀書會」）。簡中商店的截圖裡聊天內容全是繁體字，大陸使用者第一眼會覺得這是台灣 App 或沒在地化；日韓商店裡是中文聊天內容 | 讓 `DemoDataRepository` 依 `Locale`（或 seed 參數 `--es lang`）提供 zh-Hans／ja／ko 的示範對話，或至少為 zh-CN 出一組簡體示範；SCOPE 的在地化列可補一句「示範資料仍為 zh-Hant/en」 | Important（zh-CN）／Minor（ja、ko） |
| ja-JP `full_description`「中国語と英語の全文検索」、ko-KR「중국어·영어 전문 검색」 | 與 App 內 `search_empty_hint` 同一問題：實作對假名／諺文一樣做子字串索引，日韓商店文案卻只承諾中英文 | 「日本語・中国語・英語の全文検索」／「한국어·중국어·영어 전문 검색」（英文原文也建議改成 "Full-text search in Chinese, Japanese, Korean and English"） | Important |
| zh-CN／ja-JP／ko-KR `changelogs/5.txt` 與 `whatsnew-*` | 最後一句「活动页在保险库锁定时不再无限转圈／保管庫ロック中に…／보관소가 잠긴 동안…」在 en-US 版沒有。內容屬實（CHANGELOG 第 56 行 round 7），是從 zh-TW 抄來的既有漂移，但五種語言對同一版本說的事不一樣 | 在 en-US changelog 5／whatsnew 補上 "the Activity page no longer spins forever while the vault is locked"，或從四個譯本刪掉 | Minor |
| zh-CN `changelogs/5.txt`「“删除全部数据”」 | App 內 `delete_everything` 是「删除所有数据」（zh-TW 的「刪除全部資料」vs「刪除所有資料」同樣不一致） | changelog 改「删除所有数据」 | Minor |
| ja-JP `changelogs/5.txt`「ソースの停止・一時停止・削除」 | App 內是「取り除く」；修 1.2 的 `action_remove` 後自然一致 | 見 1.2 | Minor |
| zh-CN `full_description` 用「保险库」 | App 內大多是「数据库」；修 1.1 第一條後一致 | 見 1.1 | 併入 1.1 |
| zh-CN 用語 | 「即时通讯应用」「通知使用权」「崩溃报告」「源代码」「自由软件」「聊天已静音」「权限查看工具」「1 到 365 天」都是大陸慣用語，沒有殘留台灣用語 | 無 | 通過 |
| ko-KR `full_description`「출처 시각」「원본 앱」、`short_description` | 與 1.3 第一條同步修 | 見 1.3 | 併入 1.3 |

## 五、程式碼／工具

### 5.1 `tools/check-strings.py`

在 scratchpad 複本（`core/designsystem` + `platform/capture` + 腳本）上做的突變測試：

| 突變 | 結果 |
| --- | --- |
| 基準 | `OK: 0 error(s), 0 warning(s)`，exit 0 |
| M1 刪掉 ko `action_ok` | `missing string action_ok`，exit 1 ✓ |
| M2 ja `retention_days` 的 `%1$d`→`%1$s` | `placeholders differ … ['%1$d'] vs ['%1$s']`，exit 1 ✓ |
| M3 zh-Hans `messages_count_plural` 的 `other`→`one` | ``plurals messages_count_plural has no `other` item``，exit 1 ✓ |
| M5 ko `analytics_share` 的 `%%`→`%` | `placeholders differ … ['%%', '%1$d'] vs ['%1$d']`，exit 1 ✓ |
| M4 在 zh-Hans 加一個英文沒有的 `plurals`（無佔位符） | `OK`，exit 0 ✗（字串有 unknown 檢查，複數形沒有） |
| M6 新增 `values-v31/strings.xml` 只覆寫一個 `app_name` | `FAIL: 380 error(s)`（把 `values-v31` 當成語系目錄） |

結論：brief 問的三件事（缺字串、改佔位符、複數形沒有 `other`）都會確實失敗，CI 步驟放在 assemble 之前、只用標準函式庫，沒問題。其餘為 Minor：

- 缺「unknown plurals」檢查（M4）：在 `for name, items in p.items()` 前加 `for extra in sorted(set(p) - set(base_p)): failures.append(...)`。
- 目錄過濾只靠「有沒有字串」（M6）：`values-night` 現在沒字串所以沒事，但將來任何 `values-v31`／`values-sw600dp`／`values-night` 放一條字串就會炸出幾百個假錯誤；建議只認語系限定詞，例如 `re.fullmatch(r"values-(b\+[A-Za-z0-9+]+|[a-z]{2,3}(-r[A-Z]{2})?)", d.name)`。
- `ROOT.glob("*/*/src/main/res/values")` 只掃兩層深的模組，`app/src/main/res/values` 掃不到。今天 `app` 沒有字串（我用 find 確認只有 `core/designsystem` 與 `platform/capture` 有 `<string>`／`<plurals>`），但 docstring 說 "for every module"；加一個 `*/src/main/res/values` 的 glob 即可。
- 不看 `translatable="false"`（目前 repo 沒有任何一條），將來加了會被要求翻譯；lint 的 `MissingTranslation` 反而會放過。
- `el.text` 只取第一個子元素之前的文字，帶 `<b>`／`<xliff:g>` 標記的字串會比對不完整（目前沒有）。
- `locales_config.xml` 的註解寫「Keep in step with … tools/check-strings.py」，但腳本是自動發現目錄、沒有語系清單；`values/strings_analytics.xml` 檔頭仍寫 "Keys must stay in sync with values-b+zh+Hant/strings_analytics.xml"，已經過時。

### 5.2 `localeFilters`、`locales_config.xml` 與 APK

- `localeFilters = en, b+zh+Hant, b+zh+Hans, ja, ko`、`locales_config.xml = en, zh-Hant, zh-Hans, ja, ko`、`aapt2 dump badging` 的 `locales: '--_--' 'ja' 'ko' 'zh-Hans' 'zh-Hant'`（debug 與 release 都是，versionCode 5）：三者一致。manifest 加了 `android:localeConfig`。
- **Important**：`aapt2 dump resources app/build/outputs/apk/debug/app-debug.apk` 顯示 material3 的每一條 `m3c_*` 字串只有 `()`、`(ja)`、`(ko)` 三種設定（例：`m3c_time_picker_dialog_title` = "Select Time" / 「時刻の選択」 / 「시간 선택」；`m3c_time_picker_am` = "AM" / 「AM」 / 「오전」；`m3c_date_picker_title` = "Select date" / 「日付の選択」 / 「날짜 선택」）。整個 APK 裡 AndroidX 來源的 zh 設定數為 0，`(ja)` 與 `(ko)` 各 541 條；而 `~/.gradle/caches/.../material3-android/1.5.0-alpha27/material3.aar` 明明帶有 `res/values-zh-rCN/`、`values-zh-rTW/`、`values-zh-rHK/`。原因是 `b+zh+Hans`／`b+zh+Hant` 這兩個 BCP-47 限定詞在 aapt2 的設定過濾裡不等於 `zh-rCN`／`zh-rTW`，所以兩種中文使用者在「提醒時間」的 TimePicker（Select Time、AM/PM、Hour/Minute）、活動頁「自訂」期間的 DateRangePicker（Select date、Start date、End date、切換輸入模式）、以及所有 AndroidX 的無障礙內容描述（Dropdown menu、Dismiss、Close sheet…）看到的是英文，日韓使用者卻是本地化的。0.1.0 對 zh-Hant 就是這樣出貨的，這個 commit 把同一過濾套到 zh-Hans。
  - 修法：`localeFilters += listOf("en", "b+zh+Hant", "b+zh+Hans", "zh-rTW", "zh-rCN", "zh-rHK", "ja", "ko")`。裝置語言 zh-Hant-TW 會同時命中 App 的 `b+zh+Hant` 與 AndroidX 的 `zh-rTW`，zh-Hans-CN 命中 `b+zh+Hans` + `zh-rCN`，zh-Hant-HK 命中 `b+zh+Hant` + `zh-rHK`。
  - 驗證：重建後 `aapt2 dump resources … | grep -c "(zh-CN)"` 應為非零，`aapt2 dump badging` 的 `locales:` 應多出 `zh-CN zh-TW zh-HK`；在 AVD 把語言切到「中文（简体）」打開設定 → 提醒 → 時間，標題應為「选择时间」。
- 順帶：release APK 的 badging 沒有 `uses-permission: android.permission.INTERNET`（brief 的宣稱與現有產物相符；我沒有重建）。

### 5.3 只假設兩個語系的程式碼

grep `zh-TW|Hant|Hans|Locale.*|getQuantityString|isIdeographic|UnicodeBlock|UnicodeScript`（排除 build／test）：

- `Formatting.kt`：`TimeFormat.*` 用 `Locale.getDefault()`、`relativeTime` 從 `LocalConfiguration.current.locales[0]` 讀（`EEE`／`M/d` pattern 與 `FormatStyle.SHORT` 都是 locale-aware）；`dayLabel` 用 `date_today`／`date_yesterday` 字串。截圖裡日文「2026/08/31」、韓文「2026. 8. 31.」「오전 7:33」、簡中「2026年9月1日」都對。沒有語系硬編碼。
- `weekday_1..7`（`SettingsScreen.kt:436-442`，提醒的星期選擇）與 `analytics_weekday_*`（`AnalyticsScreen.kt:733-`，熱區圖）都是字串資源；zh-Hans 選「一…日」（同 zh-Hant）、ja「月…日」、ko「월…일」，語意都對。單字「一」「日」在星期 chip 裡對簡中使用者稍嫌簡略，但與 zh-Hant 一致，非本輪新增問題。
- `Avatars.kt:67 monogram()`：`Character.isIdeographic` 只對漢字為真；假名或諺文名字走拉丁分支，`t.take(2).uppercase()` 對「さくら」給「さく」、「김민수」給「김민」（兩個音節），不會壞，只是漢字名給一字、假名／諺文名給兩字，頭像視覺不一致。Minor；若要一致可把假名／諺文也視為單字頭像（`UnicodeScript.of(cp) in {HAN, HIRAGANA, KATAKANA, HANGUL}`）。
- `Normalization.isCjk`／`Insights.isCjk`：都含 HIRAGANA、KATAKANA、HANGUL_SYLLABLES（`Insights` 用 `UnicodeScript`，還含 BOPOMOFO），搜尋與口頭禪對日韓文本都可用——這正是 1.2／1.3 `search_empty_hint` 與商店文案要改寬的依據。
- `DemoDataRepository.kt`：沒有任何 `Locale` 分支（見第四節）。
- `AnalyticsScreen.kt:722`：`String.format(Locale.getDefault(), "%.1f", …)` 正確。
- 沒有殘留 `zh-TW`／`Hant` 硬編碼；`tools/demo-screenshots.sh` 的 `cmd locale set-app-locales … --locales zh-CN` 會由系統對應到 `b+zh+Hans`（截圖證實）。

### 5.4 `tools/demo-screenshots.sh`

- 五個語系的分頁標籤、搜尋提示、onboarding 三顆按鈕都與目錄逐字相符（我逐一核對：下一步／开始使用／跳过、次へ／開始／スキップ、다음／시작／건너뛰기）。
- `tap_text` 取第一個完全相符節點的問題見第四節；`OB_*_ZH` 變數名對 ja／ko 仍叫 `_ZH`，只是命名。

## 六、文件 vs 程式

- `docs/zh-Hant/SCOPE.md` 對照 `docs/SCOPE.md`：五個標題一一對應；表格 26 列＝26 列；項目符號 28＝28；反引號程式碼片段集合完全相同（雙向差集皆空）；數字只差在「12h periodic」→「12 小時週期」、「one more than」→「多 1」的斷詞，沒有多出或少掉任何宣稱。在地化列的「三種新語言由專案內部翻譯並在 API 36 AVD 上檢查，尚未經母語審閱」與英文一致。唯一多出的字是連結列的「（英文版為準；本文隨每次變更同步翻譯）」，是流程說明而非功能宣稱，可接受（Minor：英文版沒有對等句）。
- CHANGELOG `[Unreleased] Added`：每一句都對得上程式（全部 UI 字串＋listener label、`locales_config.xml`、三語商店文案／changelog／what's-new、`check-strings.py` 在 CI、截圖來自同一示範金庫）。需要補的誠實註記：「every UI string」指的是 App 自己的字串，AndroidX 提供的字串在兩種中文下目前是英文（5.2）；ja-JP 的擷取頁截圖其實不存在（第四節）。
- TEST_MATRIX 新列：`check-strings.py` 涵蓋範圍（`core:designsystem` 與 `platform:capture` 的五個語系）與 `--locales` 輸出相符；「Android lint `MissingTranslation` is an error too」：lint 預設嚴重度為 Error 且 `build-logic` 設 `abortOnError = true`，屬實。zh-Hant 版同步。
- CLAUDE.md 新規則：清單正確；路徑 `app/res/xml/locales_config.xml` 是與 `core/designsystem/res/values*` 同款的簡寫（實際是 `app/src/main/res/xml/`），SCOPE 同樣簡寫，可接受。
- RELEASE.md：「The release workflow uploads only the what's-new texts」——`release.yml:116` 的 `r0adkll/upload-google-play` 只設 `whatsNewDirectory: src/fastlane/whatsnew`，沒有 metadata／images 參數，屬實；「a new listing language is added once in the Play Console」與此一致。
- README 模組列與文件連結：正確。
- `docs/reviews/README.md` 第 16 列改成 `69a60b4`、新增第 17 列；zh-Hant 版同步。

## 七、其他觀察

- `app_name`：zh-Hans「静读 QuietInbox」與 zh-Hant「靜讀 QuietInbox」同構；商店 title 是「QuietInbox 静读」（順序相反，zh-TW 亦然）。ja／ko 只用「QuietInbox」，合理。
- `analytics_emoji_title` 在 `SHARED_OK` 裡，但 ja「絵文字」、ko「이모지」其實有翻譯，只有 zh 兩版保留「Emoji」，皆可。
- 日文全形／半形：數字與單位間留半形空格（「128 件」「7 日間」）是 Google 日文風格，全目錄一致；韓文不留空（「128개」「7일」）也一致。
- 三個目錄的 `strings_analytics.xml` 檔頭註解都翻了，並保留「不得宣稱回覆／已讀／真實總量」的守則。
- 未變動且不在本輪範圍、但受影響的英文原文：`search_empty_hint`（"Chinese text"）、en-US changelog 5 少一句、`values/strings_analytics.xml` 檔頭過時。

## 建議的修正順序

1. `app/build.gradle.kts` `localeFilters` 補 `zh-rTW`、`zh-rCN`、`zh-rHK`，重建後用 `aapt2` 驗證（5.2）。
2. ja `analytics_tile_captured`→「キャプチャ済み」，重跑 ja-JP 截圖，確認 `5_capture.png` 是擷取頁（第四節）；順手讓 `tap_text` 對分頁只在 NavigationBar 內找。
3. `search_empty_hint`（en 與三語）與 ja／ko 商店文案把「中文」改成「CJK／日中韓」。
4. 用詞統一：zh-Hans 保险库（連 zh-Hant 金庫）、ja 送信元／削除、ko 소스／보관처리／미확인。
5. 示範資料的語系版本（至少 zh-Hans），否則 zh-CN 商店截圖維持繁體內容。
6. 其餘 Minor 依表逐條處理；docs 只需在 CHANGELOG 補 AndroidX 字串與截圖的註記。
