# Review Round 19（第 18 輪在地化修正之迷你再審）— 審查報告

**審查範圍**：`git diff 7ef07de..ee48710`（單一 commit `ee48710`，branch `main`）  
**審查模式**：唯讀審查（READ-ONLY Review），無任何檔案變更、無 git 狀態變更、無裝置與儀器化測試。  
**驗證指令**：`python3 tools/check-strings.py`（回報 `OK: 0 error(s), 0 warning(s)`，exit 0）。

---

## 審查結論 (Verdict)

### **APPROVE**（附 2 項極微小 Minor 建議，不阻擋發布）

Commit `ee48710` 針對 Round 18 兩位審查者（Gemini 3.8 Flash High 與 Claude Opus subagent）提出的所有 Important 缺陷與建議進行了精準、徹底且高品質的修復。七大核心驗證項目全部核實，佔位符與格式安全 100% 保持完整，示範資料庫在地化使三語截圖自然呈現，各語系關鍵術語達到了高度的一致性。

---

## 第 18 輪發現驗證表 (Round-18 Verification Table)

| Round-18 發現項目 | 修正狀態 | 具體證據與檔案行號 |
| :--- | :---: | :--- |
| **1. `localeFilters` 丟失 AndroidX 中文資源**<br>（Material3 時間／日期選擇器、無障礙描述在簡繁中文下呈現英文） | **已修復** | [`app/build.gradle.kts:76`](file:///Users/iml1s/Documents/mine/quietinbox/app/build.gradle.kts#L76)：將 `localeFilters` 擴充為 `listOf("en", "b+zh+Hant", "b+zh+Hans", "zh-rTW", "zh-rCN", "zh-rHK", "ja", "ko")`。<br>• 資源解析行為：App 自有字串僅定義於 `values-b+zh+Hant`，無 `values-zh-rTW`，在 `zh-Hant-TW` 裝置上依 script 命中 App 的 `values-b+zh+Hant`；AndroidX Material3 資源則命中 `values-zh-rTW`，不會產生重複字串，且成功避免回退為英文預設值。 |
| **2. ja-JP 截圖重複且缺失擷取頁**<br>（`analytics_tile_captured` 與 `nav_health` 均為「キャプチャ」，腳本點錯位置導致 `5_capture.png` 複製 `4_activity.png`） | **已修復** | 1. [`core/designsystem/src/main/res/values-ja/strings.xml:129`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/res/values-ja/strings.xml#L129)：`analytics_tile_captured` 改為 `キャプチャ済み`。<br>2. [`tools/demo-screenshots.sh:97-118, 185-195`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L97-L118)：新增 `tap_tab` 指令，限制節點 `box[1] >= int(height * 0.85)`（底部 15%），5 個底部導覽分頁全面改用 `tap_tab`。<br>3. 實測 MD5 核驗：ja-JP `4_activity.png` (`a7687af...`) 與 `5_capture.png` (`93352b2...`) 完全不同，7 張截圖在 zh-CN、ja-JP、ko-KR 全數為獨立不重複之圖片，`fastlane` 與 `docs` 目錄截圖同步更新。 |
| **3. 日韓商店文案僅宣稱「中英文搜尋」**<br>（底層假名與諺文皆有 2-gram 索引，商店文案卻只寫中英文） | **已修復** | 1. [`fastlane/metadata/android/ja-JP/full_description.txt:6`](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/metadata/android/ja-JP/full_description.txt#L6)：改為「日本語・中国語・韓国語・英語の全文検索」。<br>2. [`fastlane/metadata/android/ko-KR/full_description.txt:6`](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/metadata/android/ko-KR/full_description.txt#L6)：改為「한국어·중국어·일본어·영어 전문 검색」。<br>3. en-US、zh-CN、zh-TW 說明及五份 `strings.xml` 的 `search_empty_hint` 全數同步更新。 |
| **4. zh-Hans `vault` 術語在「数据库」與「保险库」分裂** | **已修復** | [`core/designsystem/src/main/res/values-b+zh+Hans/strings.xml`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/res/values-b+zh+Hans/strings.xml)：`inbox_banner_locked`、`ob_scope_point_4`、`vault_locked_title`、`vault_locked_reset`、`vault_reset_confirm_title`、`vault_reset_confirm_body`、`health_vault_locked`、`backup_import_desc`、`backup_failed_vault`、`delete_everything_desc` 全數統一為「保险库」。<br>僅 [`delete_everything_step_database`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/res/values-b+zh+Hans/strings.xml#L276) 保留「删除保险库数据库」（明確指涉 SQLite 實體檔案）。zh-Hant 亦同步全數對齊為「金庫」。 |
| **5. 示範資料庫非多語系**<br>（簡中與日韓商店截圖內出現繁體對話內容） | **已修復** | 1. [`platform/storage/src/debug/kotlin/.../DemoLocalisation.kt`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/debug/kotlin/dev/quietinbox/platform/storage/repo/DemoLocalisation.kt)：建立獨立在地化字典，將 `DemoDataRepository` 的 57 個中文原文字串（標題、人名、訊息內容、歷史編輯）在 zh-Hans、ja、ko 建立精確對照。<br>2. 人名在日韓均為自然創作之擬真人名（日：林 美咲、山本 翔、田中 大輔；韓：김미아 Mia Kim、한지훈、박대현 等）。<br>3. 搜尋字詞 `SEARCH_SAMPLE = "meeting"` 在各語系示範訊息中均妥善保留。<br>4. 僅存在於 `src/debug`，release APK 綁定 `NoDemoData`，完全不含展示程式碼或文字。 |
| **6. 日韓與繁簡術語一致性**<br>（ja 送信元/削除/活動；ko 소스/보관처리/미확인；zh 对话/核对 等） | **已修復** | • **日文**：`action_remove` 與 `health_remove_*` 統一為「削除」（全 repo 程式碼中 0 殘留「取り除く」）；`conv_source` 統一為「送信元: %1$s」；`analytics_title` 統一為「活動」；`theme_system` 縮短為「システム」；`state_degraded` 改為「一部制限」；`health_queue` 改為「待機中」；`section_about` 改為「このアプリについて」；`gap_reason_paused` 改為「自分で一時停止」；`reminders_desc` 改為「サイレント モード」。<br>• **韓文**：source 統一為「소스」（全目錄 0 殘留「출처」）；archive 概念統一為「보관처리」以區隔 retention 的「보관」；`inbox_unviewed` 改為「미확인」；`health_dropped` 改為「폐기됨」；`health_source_enabled` 改為「사용 설정됨」；`health_gap_unknown_time` 改為「시각 알 수 없음」；`ui_lock_desc` 改為「UI 단계의 잠금일 뿐」。<br>• **中文**：`analytics_tile_conversations` 等改為「对话/對話」；`conv_origin_resync` 改為「重连后核对/重連後核對」；`listener_settings_manual` 移除「静读/靜讀」改為「QuietInbox」；zh-Hans `health_resume` 改為「继续捕获」；`section_reminders` 改為「QuietInbox 的提醒」。 |
| **7. "May mark messages read on the source side" 窄化問題** | **已修復** | [`conv_open_source_body`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/res/values-b+zh+Hans/strings.xml#L78)：zh-Hans「可能会在来源应用中把消息标为已读」、zh-Hant「可能會在來源 App 中把訊息標為已讀」、ja「送信元アプリでメッセージが既読になる場合があります」、ko「소스 앱에서 메시지가 읽음으로 표시될 수 있습니다」，精確對齊英文原意，佔位符保留無虞。 |
| **8. 商店 changelog 與 `release-notes.json` 同步** | **已修復** | 1. [`fastlane/release-notes.json`](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/release-notes.json)：已擴充包含 en-US、zh-TW、zh-CN、ja-JP、ko-KR 5 國語言，內容全數升級至 0.1.1 說明且字元數均 < 500。<br>2. en-US changelog 5 補齊活動頁文字（長度 494 字元 ≤ 500）。<br>3. zh-CN/zh-TW changelog 統一對齊 App 字串「删除所有数据 / 刪除所有資料」。 |
| **9. `tools/check-strings.py` 檢查漏洞** | **已修復** | [`tools/check-strings.py`](file:///Users/iml1s/Documents/mine/quietinbox/tools/check-strings.py)：<br>1. 補上 `unknown plurals` 檢查（`set(p) - set(base_p)`，通過 M4 突變檢驗）。<br>2. 新增 `LOCALE_DIR` 正則表示式，嚴格限定語系目錄，排除 `values-night`、`values-v31`、`values-sw600dp` 等樣式目錄（通過 M6 突變檢驗）。<br>3. glob 擴充單層模組目錄 `ROOT.glob("*/src/main/res/values")`。<br>4. 略過 `translatable="false"` 標籤。<br>5. 佔位符正則加入 `%f`。 |
| **10. `monogram()` 假名與諺文單字頭像** | **已修復** | [`core/designsystem/.../Avatars.kt:62-70`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/kotlin/dev/quietinbox/core/designsystem/components/Avatars.kt#L62-L70)：定義 `SINGLE_GLYPH_SCRIPTS` 包含 `HIRAGANA`、`KATAKANA`、`HANGUL`，使日文假名與韓文諺文名字同漢字一樣只取首個音節字元作為頭像。 |
| **11. 文件同步更新** | **已修復** | `CHANGELOG.md`、`CLAUDE.md`、`docs/SCOPE.md`、`docs/zh-Hant/SCOPE.md`、`docs/TEST_MATRIX.md`、`docs/zh-Hant/TEST_MATRIX.md`、`docs/reviews/README.md` 及 `docs/zh-Hant/reviews/README.md` 全數同步納入在地化更新細節與第 18 輪報告索引。 |

---

## 本輪新發現事項 (New Findings)

本輪未發現任何 **Critical** 或 **Important** 等級缺陷。以下為 2 項極微小之潤飾建議（**Minor**）：

### Minor 1: 韓文統計說明字串殘留單一「원본 앱」
- **檔案/位置**：[`core/designsystem/src/main/res/values-ko/strings_analytics.xml:67`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/res/values-ko/strings_analytics.xml#L67) 的 `analytics_quiet_formula`。
- **現狀**：
  ```xml
  <string name="analytics_quiet_formula">... 조용한 날은 원본 앱이 그날 알림을 게시하지 않았을 뿐일 수도 있습니다.</string>
  ```
- **說明**：在 `strings.xml` 與商店文案中，代表發送方應用的詞彙已全數由「원본 앱」統一為「소스 앱」（「원본」僅保留給原始通知「원본 알림」）。此處為全 repo 唯一一處在使用者可見字串中仍寫為「원본 앱」的地方。
- **建議**：未來可微調為「... 조용한 날은 소스 앱이 그날 알림을 게시하지 않았을 뿐일 수도 있습니다。」以達成 100% 詞彙純度。

### Minor 2: 韓文商店介紹承諾段落之微小句式差異
- **檔案/位置**：[`fastlane/metadata/android/ko-KR/full_description.txt:11`](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/metadata/android/ko-KR/full_description.txt#L11)
- **現狀**：`2. 사본을 읽어도 원본에 읽음 표시가 되지 않고 답장도 하지 않습니다. QuietInbox는 원본 알림을 절대 건드리지 않습니다.`
- **說明**：App 內的引導頁面字串 [`ob_scope_point_2`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/res/values-ko/strings.xml#L307) 已改為「사본을 읽어도 소스 앱에 읽음 표시가 되지 않고...」。商店說明中的「원본에」（在原始端）文法通順且自然，非錯誤，僅與 App 內的字眼存在微小差異。

---

## 總結

Commit `ee48710` 針對 Round 18 的反饋展現了極高的修復完整度與工程嚴謹性：
1. 解決了 AndroidX 中文資源過濾的系統層級痛點；
2. 完美修復了 UI 自動化截圖腳本中分頁誤擊的問題，並為示範資料庫引入了精緻的多語系模擬機制；
3. 全面梳理並統一了簡中、繁中、日文與韓文的產品專有名詞；
4. CI 字串比對工具 `tools/check-strings.py` 的保護網大幅強化；
5. 所有文件與多語系發布資源維持了嚴密的同步。

本變更符合發布標準，審查結論為 **APPROVE**。
