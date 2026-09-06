# Round 19 獨立審查（Claude subagent）— 第 18 輪在地化修正的迷你再審

- 審查對象：`git diff 7ef07de..ee48710`（單一 commit `ee48710`，branch `main`）。
- 審查方式：唯讀。逐段讀完 diff（`app/build.gradle.kts`、`locales_config.xml`、`Avatars.kt`、五份字串目錄、`DemoDataRepository.kt`、新檔 `DemoLocalisation.kt`、`DemoDataTest.kt`、`tools/check-strings.py`、`tools/demo-screenshots.sh`、五個語系的商店文案／changelog／what's-new／`release-notes.json`、CHANGELOG／SCOPE／TEST_MATRIX／CLAUDE.md／reviews 索引）；對照第 18 輪兩份報告逐項核對；用 `aapt2` 傾印 repo 內既有的 release／debug APK（建於 15:11–15:12，commit 在 15:21，內容與 diff 一致，未重建）；解開 release APK 的 dex 找示範字串；用 Python 比對 `DemoLocalisation` 三張表的 key 與 `DemoDataRepository` 的全部 CJK 字面值；在 scratchpad 複本對 `check-strings.py` 做 10 種突變；逐張看過 ja-JP、ko-KR、zh-CN 的示範截圖（含與 `7ef07de` 版本的比對），並看過 zh-TW／en-US 的搜尋頁。
- 實際跑過的指令：`python3 tools/check-strings.py` → `OK: 0 error(s), 0 warning(s)`（`--locales`：`core/designsystem` 與 `platform/capture` 各四個語系目錄）；`./gradlew :core:designsystem:lintDebug :platform:storage:compileDebugAndroidTestKotlin --console=plain -q` → exit 0，無任何輸出（lint 0 finding；`DemoDataTest` 以新的兩參數建構子編譯通過）。沒有跑 instrumented test、沒有碰裝置或模擬器、沒有改 repo 任何檔案。

## Verdict: APPROVE WITH MINOR FIXES

第 18 輪兩位審查者列出的每一個 Important 都已修正，且修得對：`localeFilters` 補上 `zh-rTW/rCN/rHK` 後 release APK 的 material3 字串真的有三種中文設定；示範金庫在 zh-Hans／ja／ko 的字面值全數覆蓋（59 個 CJK 字面值，三張表各 57 個 key，扣掉兩個 `identityKey` 後 0 缺 0 多）；四種語言的用詞統一有做到位；對照工具的四個缺口都補了且突變測試會確實失敗；release dex 裡沒有任何示範類別或文字。

但有一個第 18 輪沒抓到、這個 commit 也沒抓到、而且 brief 的「七張 md5 互不相同」檢查**原理上抓不到**的商店素材缺陷：**三個新語系的 `3_search.png` 全部是「無結果」畫面**（日文的查詢字串是「めgえt」、韓文是「ㅡㄷㄷ샤ㅜㅎ」、簡中的 "meeting" 卡在拼音輸入法的組字列、畫面是空狀態），而 zh-CN 那張在 `7ef07de` 還是正確的 19 筆結果，是**這個 commit 重跑後的退化**。這是唯一的 Important，會擋 ja-JP／ko-KR／zh-CN 商店清單的上傳，但不影響程式碼與字串本身，所以維持 APPROVE WITH MINOR FIXES。

統計：Critical 0 · Important 1（商店素材／截圖工具）· Minor 10 · 觀察 5。

---

## 一、第 18 輪發現逐項核對

### 1.1 Claude subagent 報告

| 第 18 輪發現 | 修了？ | 證據 |
| --- | --- | --- |
| **Important** `localeFilters` 丟掉 AndroidX 的 `zh-rCN/rTW/rHK`，中文使用者的 material3 選擇器是英文 | ✅ | `app/build.gradle.kts:76` = `en, b+zh+Hant, b+zh+Hans, zh-rTW, zh-rCN, zh-rHK, ja, ko`。`aapt2 dump resources app-release.apk`：`(zh-rCN)` `(zh-rTW)` `(zh-rHK)` 各 **89** 個設定（debug 各 159），`(b+zh+Hans)`／`(b+zh+Hant)` 各 364、`(ja)`／`(ko)` 各 453；`string/m3c_time_picker_am` = `() "AM"` / `(ja) "AM"` / `(ko) "오전"` / `(zh-rHK) "上午"` / `(zh-rCN) "上午"` / `(zh-rTW) "上午"`。`aapt2 dump badging` 的 `locales:` = `'--_--' 'ja' 'ko' 'zh-CN' 'zh-HK' 'zh-Hans' 'zh-Hant' 'zh-TW'`，release 沒有 `INTERNET`。與 commit 宣稱一致。brief 問的「zh-Hant-TW 裝置誰贏」：資源解析是逐資源做的——App 自己的字串只存在於 `values`（預設）與 `values-b+zh+Hant`，`zh-rTW` 從來不是候選，所以 `b+zh+Hant` 必勝；`m3c_*` 只存在於 `zh-rCN/rTW/rHK` 三種，zh-Hant-TW 命中 `zh-rTW`、zh-Hant-HK 命中 `zh-rHK`、無地區的 zh-Hant 由推定文字（Hant）命中 `zh-rTW` 或 `zh-rHK`（都是繁體）、zh-Hans-SG 命中 `zh-rCN`。沒有重複的 App 字串（App 沒有 `values-zh-rTW`）。badging 同時列 `zh-Hant` 與 `zh-TW` 是預期的。以上是從 APK 傾印推論，未在裝置上驗證 |
| **Important** ja `5_capture.png` 與 `4_activity.png` 位元組相同 | ✅ | 三個新語系 docs 與 fastlane 各 7 張 md5 互不相同，且 docs 與 fastlane 逐張相同；我實際看過 ja `5_capture.png`（キャプチャ 頁：接続済み、キャプチャを一時停止、パイプライン 受理／待機中／ジャーナル待ち）與 `4_activity.png`（活動 頁：キャプチャ済み 34、会話 8）。根因也修了：`analytics_tile_captured`＝「キャプチャ済み」（`values-ja/strings.xml:129`），加上 `tap_tab` 只認畫面底部 15% 的節點（`tools/demo-screenshots.sh:100-117`、`185-195`），五個 NAV 點擊都改用 `tap_tab`（`:331`、`:341`、`:351`、`:356`、`:361`、`:366`） |
| **Important（zh-CN）／Minor（ja、ko）** 示範資料在每種語言都是繁體中文 | ✅ | `DemoLocalisation.kt`（`platform/storage/src/debug/`）；`DemoDataRepository.kt:55` 注入 `@ApplicationContext`、`:67` 以 `context.resources.configuration.locales[0]` 選表、`:134-135` 在 `extras` 之後、insert 之前把每列與標題換掉、`:749-757` 的 `localised()` 換 body／sender.displayName／previousBody。截圖：zh-CN 收件匣 张书豪 Shu-Hao Chang／Book club 读书会／产品团队 Product Team／姐姐 Sis／更正：发布时间改成下周二；ko 김미아 Mia Kim／가족 단톡방／제품팀／이서연 정정: 배포는…；ja 林 美咲 Misaki Hayashi／山本 翔／お姉ちゃん／佐藤 由紀 訂正: リリースは来週火曜。對話頁 ko「소스: demo.quietinbox.chat」「소스 시각」、zh-CN「来源：」「来源时间」都對。App 沒有用 `AppCompatDelegate.setApplicationLocales`／`LocaleManager`，所以 API 33+ 的系統層 per-app 語言會直接反映在 application context 的 configuration，選表方式正確 |
| **Important** ja `analytics_tile_captured`「キャプチャ」＝分頁標籤 | ✅ | 「キャプチャ済み」，見上 |
| **Important** ja `conv_source`「ソース: %1$s」與同頁「送信元アプリ」不一致 | ✅ | `values-ja/strings.xml:73` = 「送信元: %1$s」；擷取頁清單、`search_all_sources`、`analytics_tile_sources`、`ob_sources_title` 維持「ソース」，如第 18 輪建議的分工 |
| **Important** ja `action_remove`／`health_remove_source`／`health_remove_title`「取り除く」 | ✅ | 三條都改「削除」（`:23`、`:173`、`:174`）；全目錄與 ja 商店文案已無「取り除」。附帶：`action_remove` 現在與 `action_delete`（`:9`）同為「削除」，但 `action_remove` 在任何 Kotlin 檔都沒被引用（grep `feature app core platform`），所以不會同畫面出現，無害 |
| **Important** ja／ko `search_empty_hint` 只提中文 | ✅ | en「Chinese, Japanese and Korean text」、zh-Hans「中日韩文字」、zh-Hant「中日韓文字」、ja「日本語・中国語・韓国語のテキスト」、ko「한국어·중국어·일본어 텍스트」；ja／ko 截圖上實際顯示的正是新句 |
| **Important** ko source 三種譯法（소스／출처／원본） | ✅（一條漏網） | `conv_source`／`conv_time_source`／`identity_verified`／`ob_preview_body`／`conv_open_source*`／`inbox_delete_body`／`health_connected_body`／`analytics_disclaimer`／`media_disclosure_body`／`ob_scope_point_2`／`ob_scope_point_3`／`about_limitations_body` 全部改「소스（앱）」；「원본 알림」（`conv_open_source_fallback`）依建議保留。**漏網：`values-ko/strings_analytics.xml:67` `analytics_quiet_formula` 仍是「원본 앱이 그날 알림을 게시하지 않았을 뿐」**（使用者看得到），檔頭註解 `:4` 也還是「원본 앱」。見 Minor 1 |
| **Important** ko archive 與 retention 共用「보관」 | ✅ | `action_archive`「보관처리」、`action_unarchive`「보관처리 취소」、`inbox_filter_archived`「보관처리됨」、`inbox_empty_archived_title`「보관처리된 대화가 없습니다」（`:16-17`、`:50`、`:56`）；「보관 기간」「보관소」保留 |
| **Important** ko `inbox_unviewed`「확인 안 함」 | ✅ | 「미확인」（`:64`） |
| **Important** ja／ko 商店文案「中英文搜尋」 | ✅ | ja `full_description.txt:6`「日本語・中国語・韓国語・英語の全文検索」、ko `:6`「한국어·중국어·일본어·영어 전문 검색」、zh-CN「中日韩英文全文搜索」、zh-TW「中日韓英文全文搜尋」、en-US「Chinese, Japanese, Korean and English」——五份都改 |
| Minor zh-Hans vault 数据库／保险库 | ✅ | `inbox_banner_locked`、`health_vault_locked`、`backup_import_desc`、`backup_failed_vault`、`delete_everything_desc`、`ob_scope_point_4`、`vault_locked_title`、`vault_locked_reset`、`vault_reset_confirm_title`、`vault_reset_confirm_body` 全改「保险库」；唯一殘留「数据库」是 `delete_everything_step_database`「删除保险库数据库」，對應英文 "deleting the vault database"，正確。zh-Hant 同步改「金庫」（十條同名字串） |
| Minor zh-Hans 会话→对话；静读→QuietInbox；对账；恢复捕获；推送；自己的提醒；本次连接 | ✅ | `analytics_tile_conversations`／`analytics_top_conversations`／`reminder_body_count`「对话」；`listener_settings_manual` 兩處「QuietInbox」；`conv_origin_resync`「重连后核对」（zh-Hant「重連後核對」）；`health_resume`「继续捕获」；`analytics_quiet_formula`「发出通知」；`section_reminders`「QuietInbox 的提醒」；`health_no_gaps`「本次运行期间」。zh-Hant 的 會話→對話、靜讀→QuietInbox 也同步；但 zh-Hant 的 `section_reminders`、`health_no_gaps`、`conv_open_source_body` 沒跟，見 Minor 2 |
| Minor `conv_open_source_body` 窄化成「對方看到已讀」 | ✅（zh-Hant 除外） | zh-Hans「可能会在来源应用中把消息标为已读」、ja「送信元アプリでメッセージが既読になる場合があります」、ko「소스 앱에서 메시지가 읽음으로 표시될 수 있습니다」；zh-Hant `:78` 仍是「可能讓對方看到已讀」（commit 訊息也只寫三語） |
| Minor ja `analytics_title`／`theme_system`／`state_degraded`／`health_queue`／`ui_lock_desc`／`section_about`／`gap_reason_paused`／`listener_settings_manual`／`reminders_desc` | ✅ | 活動／システム／一部制限／待機中／UI 上のロック／このアプリについて／自分で一時停止／通知へのアクセスの設定画面／サイレント モード。`6_settings.png` 上「システム」已不貼分隔線 |
| Minor ko `health_dropped`／`health_source_enabled`／`ui_lock_desc`／`health_gap_unknown_time`／`analytics_gaps_note` | ✅ | 폐기됨／사용 설정됨／UI 단계의 잠금／시각 알 수 없음（兩處） |
| Minor en-US changelog 5 少「Activity page」一句 | ✅ | `en-US/changelogs/5.txt` 494 字元（`release-notes.json` 內 493），與 `whatsnew-en-US` 相同；五個語系的 changelog 5、whatsnew、release-notes.json 三者逐一相同 |
| Minor zh 兩版 changelog「刪除全部資料」 | ✅ | zh-CN「删除所有数据」、zh-TW「刪除所有資料」，與 App 內 `delete_everything` 一致 |
| Minor `check-strings.py` 缺 unknown plurals | ✅ | `:72-73`；突變 M4（zh-Hans 多一個 plurals）→ `unknown plurals bogus_extra`，exit 1 |
| Minor 目錄過濾只靠「有沒有字串」 | ✅（見 Minor 6） | `LOCALE_DIR`（`:20`）+ `fullmatch`（`:55`）；突變 M6 `values-v31` 一條字串 → OK；`values-night` 一條字串 → OK；`values-zh-rTW` 整份複本 → 被當成第五個目錄檢查且 OK。但 `values-tv` 一條字串 → 380 個假錯誤（`[a-z]{2,3}` 也吃 UI-mode 限定詞 `tv`／`car`） |
| Minor 只掃兩層模組 | ✅ | `:48` 兩個 glob 聯集；突變 M10 在 `app/src/main/res/values` 放一條字串、`values-ja` 放另一條 → `app: ['values-ja']`、`missing string app_only`、`unknown string other_name` |
| Minor 不看 `translatable="false"` | ✅ | `:32-33`；突變 M7 預設目錄加一條 `translatable="false"` → OK |
| Minor `%f` | ✅（部分，見 Minor 7） | `PLACEHOLDER` 含 `f`；突變 M8 ja `retention_days` 的 `%1$d`→`%1$f` → `placeholders differ … ['%1$d'] vs ['%1$f']`。但 `%.1f`／`%,d` 這類帶旗標的形式仍不會被比對（M9：預設 `Rate %.1f`、ja 漏掉 → `[] vs []` 通過） |
| Minor `el.text` 不看子元素 | ⏸ 未動 | 第 18 輪已標為現況無此類字串，可接受 |
| Minor `locales_config.xml` 註解與 `strings_analytics.xml` 檔頭過時 | ✅ | 兩處都改成「tools/check-strings.py discovers the catalogues itself」／「every values-*/strings_analytics.xml」 |
| Minor `monogram()` 假名／諺文兩字 | ✅（無測試，見 Minor 8） | `Avatars.kt:62` `SINGLE_GLYPH_SCRIPTS = {HIRAGANA, KATAKANA, HANGUL}`、`:70`；截圖上「プロダクトチーム」→「プ」、「デザインレビュー」→「デ」、「가족 단톡방」→「가」、「제품팀」→「제」，行為正確 |
| Minor `release-notes.json` 只有兩語且是 0.1.0 | ✅ | 五語、0.1.1，與各 changelog 5 逐字相同 |
| Minor CLAUDE.md／TEST_MATRIX 的 `<en-US|zh-TW>` | ✅ | 三處（CLAUDE.md、TEST_MATRIX en／zh）都改成五個語系 |
| Minor CHANGELOG 應誠實註記 AndroidX 字串與截圖 | ✅ | CHANGELOG `[Unreleased] Added` 補「they were English in 0.1.0 and 0.1.1」與 `DemoLocalisation`；SCOPE en／zh 補「示範金庫也在地化」「第 18 輪審查」；TEST_MATRIX en／zh 的 `DemoDataTest` 列補在地化；reviews 索引 en／zh 各加第 18 列 |

### 1.2 agy（Gemini 3.8 Flash）報告

| 發現 | 修了？ | 證據 |
| --- | --- | --- |
| Important ja／ko 商店文案「中英文全文檢索」 | ✅ | 同上 |
| Important zh-Hans vault 用詞 | ✅ | 同上 |
| Important zh-Hans `conv_origin_resync`「对账」 | ✅ | 「重连后核对」（agy 建議「对齐」，subagent 建議「核对」，取後者，可） |
| Minor `release-notes.json` | ✅ | 同上 |
| Minor CLAUDE.md:98／TEST_MATRIX:69 參數 | ✅ | 同上 |

---

## 二、新發現

### Critical

無。

### Important

**I-1 三個新語系的 `3_search.png` 都是「無結果」畫面；zh-CN 是本 commit 的退化；`tools/demo-screenshots.sh` 的 `input text` 被輸入法吃掉且沒有任何驗證**

- 證據（逐張看過，docs 與 fastlane 兩份 md5 相同）：
  - `docs/screenshots/phone/ja-JP/3_search.png`：搜尋框「めgえt」、大字「「めgえt」に一致するものはありません。」，鍵盤區被 Gboard 首次啟動的「ひらがな 入力レイアウトの選択」對話框蓋住。`7ef07de` 版本是「めえちんg」，同樣無結果——第 18 輪也沒抓到。
  - `docs/screenshots/phone/ko-KR/3_search.png`：搜尋框「ㅡㄷㄷ샤ㅜㅎ」（"meeting" 八個按鍵在韓文兩벌식鍵盤上的字母）、「‘ㅡㄷㄷ샤ㅜㅎ’에 대한 결과가 없습니다.」。
  - `docs/screenshots/phone/zh-CN/3_search.png`：搜尋框仍是提示文字「搜索已保存的副本」、畫面是空狀態（放大鏡＋「搜索」＋說明句），"meeting" 卡在拼音輸入法的組字列（候選「meeting／没听／meetings／么」）。**`7ef07de` 的同一張是「meeting」＋「19 条结果」**（Book club 讀書會、林小美 Mia Lin…），所以這是 commit 重跑後從對變成錯。
  - 順帶：`zh-TW/3_search.png`（自 `1f7b182` 起未變，已隨 0.1.0 上 Play）是「meget」＋「找不到「meget」。」——注音鍵盤吞掉了兩個字母。只有 `en-US/3_search.png` 是正確的「meeting」＋「14 results」。
- 根因：`tools/demo-screenshots.sh:344` 的 `shell input text "$SEARCH_QUERY"` 送的是按鍵事件，交給當時的 Gboard 語言版面（拼音／12 鍵假名／兩벌식／注音）組字而不是直接提交拉丁字母；腳本沒有在拍照前用 `has_text "$SEARCH_QUERY"` 確認欄位真的是 "meeting"，也沒有處理 Gboard 在乾淨 AVD 上的首次啟動對話框。brief 的「七張 md5 互不相同」與「Activity ≠ Capture」只證明沒重複，證明不了內容正確；commit 訊息「screenshots inspected」對它點名的三項（zh-CN 收件匣人名、ko 群組名、ja 擷取頁）成立，對 `3_search` 不成立。
- 影響：ja-JP／ko-KR／zh-CN 的 Play 清單第三張圖會是一個「搜不到亂碼」的畫面（日文那張還有輸入法的設定對話框），與商店文案剛加上的「日本語・中国語・韓国語の全文検索」承諾直接矛盾；zh-TW 那張現在就在 Play 上。
- 建議（不在本輪實作）：(1) `shot "3_search"` 之前 `has_text "$SEARCH_QUERY" || die`（至少 warn 並讓 exit code 非 0），md5 之外多一道內容斷言；(2) 打字前把輸入法切到拉丁版面（`ime list -s` 後 `ime set`／`settings put secure default_input_method`；AVD 只有 Gboard 時可先 `settings put secure show_ime_with_hard_keyboard 1` 或用 `am broadcast` 走 debug-only 的 `--es op search --es query meeting` 由 `DemoReceiver` 直接導到搜尋頁），並在 `pm clear` 後處理 Gboard 首啟對話框；(3) 重跑 ja-JP、ko-KR、zh-CN **與 zh-TW** 四套，逐張目視後再更新 docs 與 fastlane。

### Minor

| # | 項目 | 位置 | 說明 | 建議 |
| --- | --- | --- | --- | --- |
| 1 | ko「원본 앱」漏網 | `values-ko/strings_analytics.xml:67` `analytics_quiet_formula`；檔頭註解 `:4` | commit 說「ko 소스 everywhere」，這條使用者看得到的說明仍是「원본 앱이 그날 알림을 게시하지 않았을 뿐」 | 「소스 앱이 그날 알림을 게시하지 않았을 뿐일 수도 있습니다」；註解同改 |
| 2 | zh-Hant 三處沒跟上 zh-Hans | `values-b+zh+Hant/strings.xml:78` `conv_open_source_body`「可能讓對方看到已讀」；`:181` `health_no_gaps`「本次連線」；`:236` `section_reminders`「自己的提醒」 | 兩份中文目錄一向對稱，這次 zh-Hans 改成「在来源应用中把消息标为已读」「本次运行期间」「QuietInbox 的提醒」，zh-Hant 留在舊句 | 「可能會在來源 App 中把訊息標為已讀」「本次執行期間沒有記錄到中斷。」「QuietInbox 的提醒」 |
| 3 | ja 商店文案的 Activity 仍叫「アクティビティ」 | `ja-JP/full_description.txt:6`「アクティビティの分析」；`ja-JP/changelogs/5.txt`、`whatsnew-ja-JP`、`release-notes.json:16`「アクティビティ画面」 | App 的 `analytics_title` 與 `nav_analytics` 現在都是「活動」，商店與 what's-new 還在用舊名；第 18 輪建議是一起改 | 「活動の分析」「活動画面」；`values-ja/strings_analytics.xml:3` 註解順手改 |
| 4 | ja 商店「サイレントモード」 | `ja-JP/full_description.txt:19` | App 內 `reminders_desc` 已改成系統寫法「サイレント モード」（半形空格），商店文案沒改 | 「サイレント モード」 |
| 5 | ko 商店「원본에 읽음 표시」 | `ko-KR/full_description.txt:11` | App 的 `ob_scope_point_2` 已是「소스 앱에 읽음 표시가 되지 않고」，商店的四個承諾第 2 條仍是「원본에」；同句的「원본 알림」保留即可 | 「사본을 읽어도 소스 앱에 읽음 표시가 되지 않고…」 |
| 6 | `LOCALE_DIR` 把 `values-tv`／`values-car` 當語系 | `tools/check-strings.py:20` | `[a-z]{2,3}` 也符合 UI-mode 限定詞 `tv`、`car`；突變 M6c（`values-tv` 放一條 `app_name`）→ `FAIL: 380 error(s)`。今天 repo 沒有這種目錄，只是把第 18 輪的地雷從 `values-v31` 挪到 `values-tv` | 排除清單 `{"tv","car","desk","watch"}`，或改用 `(?!tv$|car$)` |
| 7 | `%.1f`／`%,d` 仍不在 `PLACEHOLDER` 內 | `tools/check-strings.py:18` | 突變 M9：預設 `Rate %.1f`、ja 完全沒有佔位符 → `[] vs []` 通過。目前五份目錄都沒有帶旗標的格式（grep `%[0-9$]*[.,]` 為空），`AnalyticsScreen` 的 `%.1f` 在程式裡 | `r"%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdf]|%%"` |
| 8 | `monogram()` 沒有任何測試 | `core/designsystem/src/main/kotlin/dev/quietinbox/core/designsystem/components/Avatars.kt:62-70` | grep 整個 repo 只有 `Avatars.kt` 與 `Color.kt` 提到 `monogram`；這次新增的假名／諺文分支只靠截圖目視。TEST_MATRIX 的 206 個 JVM 測試沒有增加 | 四筆 JVM 測試：「林小美 Mia Lin」→「林」、「さくら」→「さ」、「김민수」→「김」、「Diego Ramos」→「DR」 |
| 9 | reviews 索引第 18 列的修正 commit 是佔位字 | `docs/reviews/README.md:29`、`docs/zh-Hant/reviews/README.md:27` | 最後一欄寫「follow-up commit」／「後續 commit」；其他列都是 hash | 第 19 輪修正 commit 一併改成 `ee48710`，並補第 19 列 |
| 10 | 示範帳單金額在日韓不寫實 | `DemoLocalisation.kt:139`「今月の光熱費は 1,240 円です」；`:199`「이번 달 공과금은 12,400원입니다」 | 台幣 1,240 元換成日圓 1,240 円、韓元 12,400원，都是一杯咖啡的錢，不像一個月水電費；截圖裡若滑到房東對話會露出 | 「12,400 円」／「124,000원」左右 |

---

## 三、翻譯品質（`DemoLocalisation` 三張表）

- **覆蓋**：`DemoDataRepository.kt` 含 CJK 的字面值 59 個；三張表各 57 個 key、無重複、無死 key；沒對上的兩個是 `identityKey = "title:房東 Landlord"`、`"title:舊班級群組 Old classmates"`（`:651`、`:727`），是 UNRESOLVED 對話的鍵而非顯示文字，不需翻。`SEARCH_SAMPLE`「meeting」在三種語言下都保留在 9 個字面值裡（`早安！今天的 meeting…` 的三種譯文都留著 meeting，另外 8 句英文原樣通過），足夠讓搜尋截圖有結果——只要 I-1 修好。`SELF_NAME`「我」→ ja「自分」、ko「나」、zh-Hans「我」都對。
- **zh-Hans**（57 條）：道地的大陸用語轉換而非字轉——日历／共享文件夹／站会／反馈／字号／屏幕／晚饭做好了／星期天／交（繳）／老同学群／传到群里／定在／一条新消息／想象中；人名簡化正確（陈大文、黄冠宇、张书豪、吴庭安、姐姐）。無錯字。觀察：拉丁名字仍是台式威妥瑪（Shu-Hao Chang、Kuan-Yu Huang、Che-Yu Li、Ting-An Wu），大陸使用者會覺得該是 Zhang Shuhao；「早安」在大陸口語較常用「早上好」。都不影響可讀性。
- **ja**（57 條）：全部常體（親友群組）或丁寧體（工作群組）各自一致——プロダクトチーム／デザインレビュー 用「更新しました」「お願いします」「してもらえますか」，家族／朋友用「食べてね」「行かない？」；人名（林 美咲、田中 大輔、佐藤 由紀、高橋 健、中村 悠、山本 翔、小林 花）都是常見且虛構的組合，姓名間半形空格與 Google 日文風格一致；「大家さん」「学級委員」「同窓会」「課題本」「光熱費」「ごみ収集」用詞自然；訂正／リリース 那對（`previousBody` 與 body）配對正確；數字與單位間留半形空格（「10 分後」「2 行」「第 3 章」）全表一致。無錯字。
- **ko**（57 條）：語體分工正確——親友反말（「알았어」「먹어」「보여?」）、工作群組 해요체（「부탁드려요」「미루죠」「생각하세요?」）、系統句 합니다체（「새 메시지가 있습니다」「시작합니다」）；「가족 단톡방」「반장」「집주인」「공과금」「동창회」「단톡방에 올릴게」都是道地口語；人名（김미아、박대현、이서연、최준호、정우진、한지훈、오수빈）自然。數字與單位不留空（「10분」「3시」「12,400원」）全表一致。無錯字。觀察：「姊姊 Sis」→「언니」把「我」設定成女性（男性會叫「누나」）；ja 的「お姉ちゃん」則中性。若想中性可用「누나」以外的稱呼很難，維持亦可，只是要知道。

## 四、其他觀察（不需動作）

1. `tap_tab` 的「底部 15%」門檻在 `QuietInbox_Phone`（1080×2400）足夠：3 鍵導覽列＋NavigationBar＋標籤偏移後標籤頂端仍在 85% 線之下；但在平板／折疊 AVD 的 `NavigationRail`（側邊）上 `tap_tab` 必定失敗並只 `warn`，之後每張都會是同一頁。`docs/screenshots/tablet/` 只有 en-US／zh-TW 且不是這次產出的；若日後要為新語系拍平板圖，需要一個 rail 版本的 `tap_tab`（例如「最左 15%」）或用 `content-desc`／`resource-id` 過濾。
2. `DemoLocalisation.forLocale`（`:16-21`，`SIMPLIFIED_REGIONS` 在 `:23`）對 `zh` 的判斷：有 `script` 看 script，沒有就看地區 `CN/SG/MY`；`zh-HK`／`zh-TW`／`zh-MO`／純 `zh` 都留繁體。`cmd locale set-app-locales --locales zh-CN` 走地區分支，截圖證實。
3. release APK（15:12 建，commit 前 9 分鐘、內容同 diff）的 `classes*.dex` 找不到 `DemoLocalisation`、`DemoDataRepository`、`NoDemoData`、「Misaki Hayashi」「Mia Kim」「林小美」任何一個；debug dex 有。`platform/storage/src/release/.../DemoModule.kt` 仍綁 `NoDemoData`，release 不會因新建構子參數受影響。
4. `docs/RELEASE.md` 在 `7ef07de` 就已列五個語系（`:21`），這次沒動也不需要動；commit 訊息「RELEASE.md locale lists」略多說。
5. 對話截圖（ja／ko／zh-CN 的 `2_conversation.png`）中同一句「カレンダーを更新したよ…」在 09/01 與 09/03 各出現一次，是 `bulkMessage` 以 `index % bodies.size` 輪流取句的既有行為，非本次新增；ja `1_inbox.png` 第一個篩選 chip「ア…」被切，是所有語系共有的可捲動 chip 列，與 zh-CN「已归…」、ko「보관…」相同。

## 建議的修正順序

1. `tools/demo-screenshots.sh`：打字前切拉丁輸入法（或改走 debug intent 設查詢），`shot "3_search"` 前 `has_text "$SEARCH_QUERY"` 硬性斷言；重跑 zh-CN、ja-JP、ko-KR、zh-TW 四套並逐張目視（I-1）。
2. ko `analytics_quiet_formula`；zh-Hant 三條；ja 商店「活動」「サイレント モード」；ko 商店「소스 앱에」（Minor 1–5）。
3. `check-strings.py` 的 `tv/car` 排除與帶旗標佔位符；`monogram()` 四筆測試（Minor 6–8）。
4. reviews 索引第 18 列改 `ee48710`、補第 19 列；示範金額（Minor 9–10）。
