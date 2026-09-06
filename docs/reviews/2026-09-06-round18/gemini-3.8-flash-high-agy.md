# Review Round 18 (Localisation: zh-Hans, ja, ko) — 審查報告

**審查範圍**：`git diff 69a60b4..7ef07de`（commit `7ef07de`）  
**審查模式**：唯讀審查（READ-ONLY Review），無任何檔案變更、無 git 狀態變更、無裝置與儀器化測試。

---

## 審查結論 (Verdict)

**APPROVE WITH MINOR FIXES**

本次 commit 成功為 QuietInbox 引入簡體中文（`values-b+zh+Hans`）、日文（`values-ja`）與韓文（`values-ko`）三大語系，覆蓋了 [core/designsystem](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem)（318 個 strings + 3 個 plurals）與 [platform/capture](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture)（1 個 string）。

**核心優勢**：
1. **格式與佔位符安全 100%**：所有佔位符（`%1$s`、`%2$d`、`%%`）數量與型別完全正確，單引號全數透過轉義或雙引號處理，XML 解析無任何語法錯誤。
2. **語序反轉與語法細節嚴謹**：韓語 `ob_step_of` 成功反轉引數順序為 `%2$d단계 중 %1$d단계`，`analytics_quiet_days` 亦正確反轉為 `%2$d일 중 %1$d일`；韓語動態受詞採用 `%1$s을(를)` 優雅處理終聲收音；`about_limitations_body` 的 `\n` 條列結構在各語系均精確對齊。
3. **Android 系統規範術語精確**：通知使用權（zh-CN: 通知使用权 / ja: 通知へのアクセス / ko: 알림 액세스）、受限制的設定（受限制的设置 / 制限付き設定 / 제한된 설정）、應用程式資訊（应用信息 / アプリ情報 / 앱 정보）、工作資料（工作资料 / 仕事用プロファイル / 직장 프로필）均完全契合 AOSP 原生字串標準。
4. **CI 閘門工具完備**：[tools/check-strings.py](file:///Users/iml1s/Documents/mine/quietinbox/tools/check-strings.py) 實測 0 errors 通過；`locales_config.xml`、`localeFilters` 與 APK 資源完全同步。

需修正之處主要集中於：**日韓商店文案殘留「中英文搜尋」宣稱**、**簡體中文對 `vault` 的用詞在「数据库」與「保险库」之間不一致**，以及若干文件指令更新。

---

## 逐語系翻譯問題清單 (Per-Language Translation Issues)

嚴重度分級依 brief 規定：
- **Critical**：語意偏差、隱私承諾破損、佔位符或格式損壞
- **Important**：術語不一致、不自然表達、系統術語或商店文案脫節
- **Minor**：風格潤飾、文字對齊

### 1. 簡體中文 (Simplified Chinese, zh-Hans / zh-CN)

| 字串名稱 (Name) | 目前文字 (Current) | 問題說明 (Problem) | 建議修改 (Suggested) | 嚴重度 |
| :--- | :--- | :--- | :--- | :--- |
| `ob_scope_point_4`<br>`vault_locked_title`<br>`vault_locked_reset`<br>`vault_reset_confirm_title`<br>`vault_reset_confirm_body`<br>`inbox_banner_locked`<br>`health_vault_locked`<br>`backup_import_desc`<br>`backup_failed_vault`<br>`delete_everything_desc` | `所有内容都存在加密数据库中...`<br>`数据库已锁定`<br>`重新开始（删除数据库）`<br>`删除数据库？`<br>`无法打开加密数据库...`<br>`加密数据库已锁定...` | **術語不一致**：在這些字串中將 `vault` 譯為「数据库」，但在同目錄下的 `delete_everything_step_database`（`删除保险库数据库`）、`delete_everything_step_reopen`（`创建新的保险库`）以及 Google Play zh-CN 商店文案（`存进手机内的加密保险库`、`保险库锁定时`）中均使用「保险库」。「保险库」才是資安金庫/隱私儲存（SQLCipher）的精確隱喻，單稱「数据库」會弱化安全感且前後矛盾。 | 將代表 `vault` 實體處統一把「数据库」改為「保险库」或「加密保险库」。例如：<br>• `ob_scope_point_4`: `所有内容都保存在加密保险库中，密钥为本次安装独有。`<br>• `vault_locked_title`: `保险库已锁定`<br>• `vault_locked_reset`: `重新开始（删除保险库）`<br>• `vault_reset_confirm_title`: `删除保险库？`<br>• `inbox_banner_locked`: `无法打开加密保险库，请到“捕获”页查看恢复选项。` | **Important** |
| `conv_origin_resync` | `重连后对账` | **用語偏差**：「对账」在簡體中文多為會計/銀行對帳（financial reconciliation）。在訊息流水線重連後的身分與狀態核對中，此處應使用資料同步或對齊術語，繁中版為「重連後對齊」。 | `重连后对齐` 或 `重连后校对` | **Important** |
| `fastlane/metadata/android/zh-CN/full_description.txt` (Line 16) | `...静读使用 Android 标准的“通知使用权”...` | 內容準確，與繁體中文相比，已確實使用大陸標準詞彙（应用、通知栏、设置、存储空间、截屏/截图），且長度 927 字遠低於 4000 上限。 | 保持現狀 | **Pass** |

---

### 2. 日文 (Japanese, ja / ja-JP)

| 字串名稱 (Name) | 目前文字 (Current) | 問題說明 (Problem) | 建議修改 (Suggested) | 嚴重度 |
| :--- | :--- | :--- | :--- | :--- |
| `fastlane/metadata/android/ja-JP/full_description.txt` (Line 6) | `• 中国語と英語の全文検索、アクティビティの分析...` | **商店承諾脫節（重要缺失）**：在日文 Google Play 商店介紹中，居然宣稱「中国語と英語の全文検索」（中文與英文的全文搜尋）！這會讓日本使用者誤以為該 App 不支援日文搜尋。事實上，底層的 [SearchNormalizer.kt:137-138](file:///Users/iml1s/Documents/mine/quietinbox/core/model/src/main/kotlin/dev/quietinbox/core/model/Normalization.kt#L137-L138) 完整涵蓋了 `HIRAGANA` 與 `KATAKANA`，日文漢字與假名皆會建立二元組索引，實際搜尋完全支援日文。 | `• メッセージの全文検索、アクティビティの分析...` 或 `• 日本語・中国語・英語の全文検索、アクティビティの分析...` | **Important** |
| `analytics_title` vs `nav_analytics` | `analytics_title`: `アクティビティ`<br>`nav_analytics`: `活動` | **標籤與頁面標題微小脫節**：導覽列標籤為了防止在 360dp 螢幕折行而縮減為「活動」（漢字 2 字），但進入畫面後的 TopAppBar 標題仍是「アクティビティ」。雖然頂部欄有足夠寬度顯示片假名，但前後用詞略有出入。 | 建議可維持（頂部空間充裕），或統一標題為 `活動` / `アクティビティ`。 | **Minor** |
| `conv_open_source` vs `conv_source` | `conv_source`: `ソース: %1$s`<br>`conv_open_source`: `送信元アプリで開く` | 日文對於設定實體使用「ソース」（Sources）、對發送方應用程式使用「送信元アプリ」，語境劃分清晰，符合日文原生 App 習慣。敬體（丁寧語: です・ます）貫徹率 100%，無雜揉常體現象。 | 保持現狀 | **Pass** |

---

### 3. 韓文 (Korean, ko / ko-KR)

| 字串名稱 (Name) | 目前文字 (Current) | 問題說明 (Problem) | 建議修改 (Suggested) | 嚴重度 |
| :--- | :--- | :--- | :--- | :--- |
| `fastlane/metadata/android/ko-KR/full_description.txt` (Line 6) | `• 중국어·영어 전문 검색, 활동 인사이트...` | **商店承諾脫節（重要缺失）**：如同日文版，韓文商店說明寫著「중국어·영어 전문 검색」（中文·英文全文檢索），會讓韓國使用者誤以為無法搜尋韓文。底層 [SearchNormalizer.kt:139](file:///Users/iml1s/Documents/mine/quietinbox/core/model/src/main/kotlin/dev/quietinbox/core/model/Normalization.kt#L139) 包含 `HANGUL_SYLLABLES`，實際上完整支援韓文搜尋。 | `• 메시지 전문 검색, 활동 인사이트...` 或 `• 한국어·중국어·영어 전문 검색, 활동 인사이트...` | **Important** |
| `nav_inbox` | `받은편지함` | **長度排版檢視**：`받은편지함` 為 5 個韓文字母（全角寬度），在 360dp 螢幕上（底部 5 個 tab，每 tab 最大 72dp）。經查驗實機截圖 [docs/screenshots/phone/ko-KR/1_inbox.png](file:///Users/iml1s/Documents/mine/quietinbox/docs/screenshots/phone/ko-KR/1_inbox.png)，在標準 12sp label 下單行完全容納，未發生折行或截斷。但若系統字型放大（Display size / Font scale 大於 1.15x），此 5 字會是最先面臨折行風險的項目。若未來有使用者回報折行，可考慮採用常見的簡稱 `수신함`（3 字）。 | 目前可保持，但列為極端字型縮放下之潛在折行注意項目（建議備案: `수신함`）。 | **Minor** |
| `identity_unresolved` | `발신자 불확실` | 語意準確，與通知卡片上的上下文吻合。語體規範一致（陳述句: 합니다/있습니다、引導請求: ~하세요、對話確認: ~할까요?）。 | 保持現狀 | **Pass** |

---

## 程式碼與工具鏈審查 (Code & Tooling Findings)

### 1. `tools/check-strings.py` 正確性驗證
- **缺漏與多餘字串**：第 56–59 行透過集合差集運算 `set(base_s) - set(s)` 與 `set(s) - set(base_s)`，只要翻譯目錄遺漏任何 key 或宣告非預設目錄的未知 key，均會 append 至 `failures` 並回傳 `exit 1`。
- **佔位符校驗**：第 61–62 行提取正規表示式匹配項並排序比較，若佔位符數量、型別或序號不一致必拋出錯誤。
- **複數形 `other` 校驗**：第 68–69 行強制檢查 `"other" in items`，缺乏時即回傳錯誤。
- **潛在邊界限制觀察**：
  1. 第 44 行使用 `ROOT.glob("*/*/src/main/res/values")`，這固定假設了模組層次為兩層（如 `core/designsystem`、`platform/capture`）。若單層模組（如 `app/src/main/res/values`）加入字串資源，該腳本不會走訪到。目前 `app` 僅存放 `themes.xml` 與 `colors.xml`，尚無問題，但未來若擴充需注意 glob 深度。
  2. 第 65–72 行檢查複數形時，檢查了 `missing plurals`，但未檢查 `extra plurals`（即未比對 `set(p) - set(base_p)`），存在輕微的不對稱性。
  3. `PLACEHOLDER` 正規表示式 `r"%(\d+\$)?[sd]|%%"` 僅比對 `%s`、`%d` 與 `%%`，未包含浮點數 `%f` 等其他格式元。目前專案字串資源恰好只用這三者，現狀完全吻合。

### 2. 多語系設定對齊
- [app/src/main/res/xml/locales_config.xml](file:///Users/iml1s/Documents/mine/quietinbox/app/src/main/res/xml/locales_config.xml)：
  正確宣告 `en`、`zh-Hant`、`zh-Hans`、`ja`、`ko` 5 種語言。
- [app/build.gradle.kts:74](file:///Users/iml1s/Documents/mine/quietinbox/app/build.gradle.kts#L74)：
  `localeFilters += listOf("en", "b+zh+Hant", "b+zh+Hans", "ja", "ko")` 精確對齊。
- [app/src/main/AndroidManifest.xml:29](file:///Users/iml1s/Documents/mine/quietinbox/app/src/main/AndroidManifest.xml#L29)：
  `android:localeConfig="@xml/locales_config"` 正確掛載。

### 3. 程式碼中的語系假設檢視
- **[Formatting.kt](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/kotlin/dev/quietinbox/core/designsystem/components/Formatting.kt)**：
  `relativeTime` 與 `TimeFormat` 透過 `LocalConfiguration.current.locales` 取出首選 locale，日期星期格式化呼叫 `DateTimeFormatter.ofPattern("EEE", locale)` 與 `ofLocalizedDate`，完全遵循 Java/Android 標準 ICU 規則，在 zh/ja/ko 均能正確產生對應縮寫（週/曜日/요일），無僅假設雙語系的硬編碼。
- **[DemoDataRepository.kt](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/debug/kotlin/dev/quietinbox/platform/storage/repo/DemoDataRepository.kt)**：
  示範資料為固定之英中雙語合成對話，其僅用於截圖與展示，不依賴系統 Locale，且嚴格隔離於 `debug` source set，不影響 release APK。
- **[ReminderScheduler.kt:148](file:///Users/iml1s/Documents/mine/quietinbox/app/src/main/kotlin/dev/quietinbox/reminders/ReminderScheduler.kt#L148)**：
  呼叫 `context.resources.getQuantityString(R.plurals.reminder_body_count, unviewed, unviewed)`，在全部 5 個語系資源中 `reminder_body_count` 皆存在且正確設定了 `other` 分支與 `%1$d` 佔位符。
- **`DELETE` 確認指令**：
  [SettingsScreen.kt:349](file:///Users/iml1s/Documents/mine/quietinbox/feature/settings/src/main/kotlin/dev/quietinbox/feature/settings/SettingsScreen.kt#L349) 的刪除全部確認輸入硬編碼檢查 `typed == "DELETE"`，所有語系字串均明確指示使用者輸入 ASCII 英文字母 `DELETE`，與實作完全匹配。

---

## 文件與程式碼一致性審查 (Docs Findings)

1. **[docs/zh-Hant/SCOPE.md](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/SCOPE.md) 與 [docs/SCOPE.md](file:///Users/iml1s/Documents/mine/quietinbox/docs/SCOPE.md)**：
   - 逐列核對完成定義表格：英文版共 26 個項目，繁中版精確對應 26 個項目，狀態與證據內容完全對譯，無任何偷渡或多增項目。
   - 「未完成事項」（13 項）、「第 1 輪審查修正項目」、「已知缺陷與粗糙處」所有章節皆忠實對映，雙向超連結 `docs/SCOPE.md` ↔ `docs/zh-Hant/SCOPE.md` 準確無誤。
2. **[CHANGELOG.md:8](file:///Users/iml1s/Documents/mine/quietinbox/CHANGELOG.md#L8)**：
   - 在 `[Unreleased] -> Added` 完整列入繁中、日文、韓文在地化、`locales_config.xml`、Play 商店文案與 `tools/check-strings.py` CI 檢驗，格式符合 Keep a Changelog。
3. **[docs/TEST_MATRIX.md:16](file:///Users/iml1s/Documents/mine/quietinbox/docs/TEST_MATRIX.md#L16) 與 [docs/zh-Hant/TEST_MATRIX.md:16](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/TEST_MATRIX.md#L16)**：
   - 雙語版本皆同步新增了「String catalogues / 字串目錄」列，載明 `tools/check-strings.py` 與 Android lint `MissingTranslation` 的檢驗要求。
4. **[CLAUDE.md:85-87](file:///Users/iml1s/Documents/mine/quietinbox/CLAUDE.md#L85-L87) 與殘留的小疏漏**：
   - 字串完整性規範已於 line 85–87 補上。
   - **殘留疏漏 (Minor)**：[CLAUDE.md:98](file:///Users/iml1s/Documents/mine/quietinbox/CLAUDE.md#L98) 與 [docs/TEST_MATRIX.md:69](file:///Users/iml1s/Documents/mine/quietinbox/docs/TEST_MATRIX.md#L69) 仍寫著 `tools/demo-screenshots.sh <serial> <en-US|zh-TW> <out-dir>`，但該腳本在本次 commit 已擴充支援 `<en-US|zh-TW|zh-CN|ja-JP|ko-KR>`，文件中的說明參數尚未更新。

---

## 商店文案與其他觀察 (Store Texts & Other Observations)

### 1. Google Play 欄位長度檢核
針對各語系之實際字元數（Character Count）進行檢核，全數符合 Google Play Console 限制：
- **標題 (`title.txt`)**（上限 30 字元）：
  - en-US: 13 | zh-TW: 13 | zh-CN: 13 | ja-JP: 10 | ko-KR: 10 （全數通過）
- **簡短說明 (`short_description.txt`)**（上限 80 字元）：
  - en-US: 78 | zh-TW: 36 | zh-CN: 36 | ja-JP: 36 | ko-KR: 40 （全數通過）
- **完整說明 (`full_description.txt`)**（上限 4000 字元）：
  - en-US: 2450 | zh-TW: 942 | zh-CN: 927 | ja-JP: 1260 | ko-KR: 1356 （全數通過）
- **版本更新說明 (`changelogs/{4,5}.txt` 及 `whatsnew-*`)**（上限 500 字元）：
  - `changelogs/4.txt`: en-US: 141 | zh-TW: 39 | zh-CN: 39 | ja-JP: 64 | ko-KR: 57
  - `changelogs/5.txt`: en-US: 483 | zh-TW: 153 | zh-CN: 152 | ja-JP: 231 | ko-KR: 256
  - `whatsnew/*`: 字元數與 `5.txt` 完全一致，全數通過。

### 2. `fastlane/release-notes.json` 未同步更新 (Minor)
- 依據 [docs/RELEASE.md:35](file:///Users/iml1s/Documents/mine/quietinbox/docs/RELEASE.md#L35)，手動發布時可使用 `gplay release ... --release-notes @fastlane/release-notes.json`。
- 目前 [fastlane/release-notes.json](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/release-notes.json) 仍僅包含 `en-US` 與 `zh-TW`，且內容仍為 0.1.0 舊版（"First release: ..."），未補上 `zh-CN`、`ja-JP`、`ko-KR` 與 0.1.1 內容。若維護者依照文檔執行 CLI 指令，新語系將缺少 release notes。

### 3. AVD 截圖排版驗證結果
檢視 [docs/screenshots/phone/](file:///Users/iml1s/Documents/mine/quietinbox/docs/screenshots/phone/) 產出的三套新語系 7 張截圖：
- 日文底部列：`受信箱`、`検索`、`活動`、`キャプチャ`、`設定` 排版舒適，原折行問題已解。
- 韓文底部列：`받은편지함`、`검색`、`활동`、`캡처`、`설정` 雖然第一項為 5 韓文字，但在標準字型下各 tab 邊距均勻，完美單行呈現。
- 對話標籤（例如日文 `ストリームから推定`、韓文 `스트림에서 추정됨`）在訊息氣泡旁的顯示均整齊無破版。

---

## 總結建議動作 (Recommended Next Steps)

在推送或下一次發布前，建議進行以下微幅調整：
1. **修正日韓商店說明**：將 [fastlane/metadata/android/ja-JP/full_description.txt](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/metadata/android/ja-JP/full_description.txt) 與 [ko-KR/full_description.txt](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/metadata/android/ko-KR/full_description.txt) 第 6 行的「中国語と英語の全文検索 / 중국어·영어 전문 검색」改為「メッセージの全文検索」或補上「日本語 / 한국어」。
2. **統一簡中 `vault` 用詞**：將 [values-b+zh+Hans/strings.xml](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/res/values-b+zh+Hans/strings.xml) 中的「数据库」統一對齊為「保险库」，並將 `conv_origin_resync` 由「重连后对账」調整為「重连后对齐」。
3. **補齊 `release-notes.json` 與文檔腳本參數**：同步更新 `fastlane/release-notes.json` 並修正 `CLAUDE.md:98` 中 `demo-screenshots.sh` 的語系參數範例。
