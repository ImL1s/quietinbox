# 第 26 輪獨立審查 — Claude Opus 5 subagent

- **對象**：`/Users/iml1s/Documents/mine/quietinbox`，單一 commit `78e7487`（`main`，領先 origin 1 個 commit）
- **範圍**：46 個檔案、+242 / −77，含 `tools/demo-screenshots.sh`（126 行變動）、28 張 PNG（14 張 `docs/screenshots/tablet/` + 14 張 `fastlane/.../tenInchScreenshots/`）、以及 16 份文件／設定
- **方式**：唯讀。實際跑過 git 指令、逐行讀過改動後的 shell 與 python helper、用 Read 開過全部 14 張新 PNG 與 3 張舊 PNG、逐項數過測試檔裡的測試數、逐項比對過文件宣稱與樹上的事實。未做任何編輯／stage／commit。

---

## Verdict：**REQUEST CHANGES**

0 Critical、2 Important、6 Minor、4 Observations。

先講清楚：**這個 commit 交付的東西本身沒有一項是錯的**。14 張新截圖我全部看過，張張都是 QuietInbox 的真實畫面、語言正確、與 fastlane 資產 byte-identical；四個更正的測試數我自己數過，四個全對；diff 引進的每一條事實宣稱我都在樹上驗證過，沒有一條假的。harness 也確實比改動前嚴格得多。

給 REQUEST CHANGES 的理由只有兩點，而且兩點都很便宜（一個約 5 行 shell，一個是一句話文件）：

1. **殘餘的同類缺陷**：`tap_tab` 只驗證「找得到目標」，不驗證「按下去之後真的到了那一頁」；而 `4_activity` / `5_capture` / `6_settings` / `7_inbox_dark` 這四張完全沒有任何內容斷言。一次被吞掉的 tap 仍然會產出一張錯畫面，而且**通過現在所有的關卡**。這正是本次事故的同一類故障，只是往內縮了一層。
2. **Play 商店仍在服務壞掉的截圖**，而樹上沒有任何地方記錄這件事還沒做完。

**強烈建議**：不要因為這兩點而延後把新截圖推上 Play。正確順序是先補上這兩個小修正、再一起推 + 同步商店，而不是把 store 修復壓在 review 迴圈後面 —— 使用者現在每一秒看到的都是 Pixel 桌布。

---

## 我實際驗證了什麼（供交叉比對）

### 缺陷本身確實如 commit 所述

我把 `78e7487~1` 的舊 PNG 取出來看過三張：

- `docs/screenshots/tablet/en-US/6_settings.png`（舊，189,481 bytes）→ **Android 系統「Settings」App**（Search Settings / Google / Network & internet / …），不是 QuietInbox。
- `docs/screenshots/tablet/zh-TW/3_search.png`（舊，189,529 bytes）→ **同一張英文系統 Settings**，卻被歸檔成繁體中文的商店資產。
- `docs/screenshots/tablet/zh-TW/7_inbox_dark.png`（舊，3,438,820 bytes）→ **Pixel 桌布 + Gmail/Photos/YouTube 圖示 + Calendar widget 的啟動器主畫面**，"Sun, Sep 6"。

commit message 與 `CHANGELOG.md:8-14` 的描述屬實，沒有誇大。

### 14 張新 PNG（brief 第 4 點）

全部 2076×2152，全部逐張以 Read 開啟目視：

| 檔案 | 內容 | 語言 |
| --- | --- | --- |
| en-US 1_inbox | 左側 rail + Inbox 清單 + "Pick a conversation" 佔位 | 英文 ✅ |
| en-US 2_conversation | rail + 清單 + 右側對話細節（氣泡、source time / captured） | 英文 ✅ |
| en-US 3_search | rail + "meeting" 查詢 + 13 results | 英文 ✅ |
| en-US 4_activity | rail + Activity 五分頁 + 統計方塊 + by-hour 長條 | 英文 ✅ |
| en-US 5_capture | rail + Capture / Connected / Pipeline 計數 / Sources | 英文 ✅ |
| en-US 6_settings | rail + Settings（Appearance / Privacy & security / Retention） | 英文 ✅ |
| en-US 7_inbox_dark | rail + Inbox 深色 | 英文 ✅ |
| zh-TW 1_inbox | rail + 收件匣 + 「選一個對話」 | 繁中 ✅ |
| zh-TW 2_conversation | rail + 收件匣 + 右側對話（來源時間／擷取時間） | 繁中 ✅ |
| zh-TW 3_search | rail + meeting + 「13 筆結果」／「不限時間」 | 繁中 ✅ |
| zh-TW 4_activity | rail + 活動統計（概觀／排行榜／最佳時段／好聊度／神隱率） | 繁中 ✅ |
| zh-TW 5_capture | rail + 擷取健康 / 已連線 / 管線狀態 | 繁中 ✅ |
| zh-TW 6_settings | rail + 設定（外觀／隱私與安全／保存期限） | 繁中 ✅ |
| zh-TW 7_inbox_dark | rail + 收件匣深色 | 繁中 ✅ |

`cmp` 逐檔比對 `docs/screenshots/tablet/{en-US,zh-TW}/*.png` 與 `fastlane/metadata/android/{en-US,zh-TW}/images/tenInchScreenshots/*.png`：**14/14 IDENTICAL**。

commit message 宣稱的「186-498 KB」也對得上（en-US 186,619–478,732；zh-TW 220,635–497,958）。

### 四個更正的測試數（brief 第 5 點）—— 我自己數過

| 宣稱 | 我數到的 | 位置 |
| --- | --- | --- |
| `CaptureCoordinatorTest` (32) | **32** 個 `test(` | `platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt:59` 起（`FunSpec`） |
| `VaultMaintenanceTest` (5) | **5** | `platform/storage/src/test/kotlin/dev/quietinbox/platform/storage/db/VaultMaintenanceTest.kt:21` |
| `core:reconcile` 22 | **20 + 2 = 22** | `ReconcilerTest.kt:29` 的 `ReconcilerTest` 與 `:214` 的 `ReconcilerIdAlignmentTest` 合計 20，`ReconcilerPropertyTest.kt:25` 2 |
| `core:analytics` 34 | **28 + 6 = 34** | `InsightsTest.kt:33` 28、`ActivityAnalyticsTest.kt:13` 6 |

四項全對（`docs/SCOPE.md:18,20,21,27` 與 `docs/zh-Hant/SCOPE.md:16,18,19,25`）。

順帶驗了 `docs/zh-Hant/TEST_MATRIX.md:11` 新加的「含 `ReminderPolicy`」：`app/src/test/.../ReminderSchedulerTest.kt` 有 5 個 `@Test`，其中 `remindsOnlyWhenEnabledAllowedAndSomethingIsUnviewed`（`:26-33`）確實呼叫 `ReminderPolicy.shouldRemind`。屬實。

### 其他事實宣稱

| 宣稱 | 驗證 | 結論 |
| --- | --- | --- |
| `docs/SCOPE.md:44`「發行簽章已完成」 | `.github/workflows/release.yml:29,42-51` 還原 upload keystore、簽 APK/AAB | ✅ |
| `docs/SCOPE.md:54` / `CONTRIBUTING.md:37` package id 已固定 | `app/build.gradle.kts:46` `applicationId = "dev.quietinbox.app"`、`:18` `namespace = "dev.quietinbox"` | ✅ |
| `CONTRIBUTING.md:24` 三套 instrumented suite | `.github/workflows/ci.yml:86` 正是 storage + crypto + backup | ✅ 逐字相符 |
| `.github/PULL_REQUEST_TEMPLATE.md:10` 五份字串目錄 | `ci.yml:48-49` 有 `python3 tools/check-strings.py` | ✅ |
| `.github/PULL_REQUEST_TEMPLATE.md:15` `lintDebug` 是硬關卡 | `ci.yml:52` `:app:assembleDebug :app:assembleRelease :app:lintDebug` | ✅ |
| `gradle/libs.versions.toml:11` ADR 檔名 | `docs/adr/0002-material3-expressive-alpha.md` 存在 | ✅ |
| `docs/RELEASE.md:28` `fastlane/whatsnew/whatsnew-<locale>` | 目錄存在且含 5 個語系檔；中文版 `docs/zh-Hant/RELEASE.md:27` 本來就是對的（英文版才是走偏的那一側） | ✅ |
| `README.md:161-175` 英文模組表 | 與 `settings.gradle.kts` 完全對應，也與中文半邊 `README.md:59-73` 逐行一致 | ✅ |
| `README.md:17-20`／`:119-122` 截圖路徑 | `docs/screenshots/phone/{zh-TW,en-US}/` 七張都在 | ✅ |
| ADR 雙語互鏈 | `docs/adr/0001-0007` 全部第 1 行有繁中連結、`docs/zh-Hant/adr/0001-0007` 全部有 English 連結 | ✅ 補齊 |
| 審查索引對位 | `docs/reviews/README.md:19` 與 `docs/zh-Hant/reviews/README.md:17` 的 whole-repo 列現在一致，兩邊 26 個資料列全數對齊 | ✅ |
| `docs/zh-Hant/ARCHITECTURE.md:92-93` 活動頁段落 | 與 `docs/ARCHITECTURE.md` 末段（五分頁／共用期間選擇器／`core:analytics` 純函式／僅限已觀測）語意等價 | ✅ |
| `.github/ISSUE_TEMPLATE/bug_report.yml:14` `0.1.2 (GitHub) / 0.1.0 (Play)` | 與 brief 描述的實際狀態一致 | ✅ |

### 手機（narrow）路徑回歸風險（brief 第 2 點）—— 我的結論是「不會壞」

brief 點名的四項我逐一查過：

1. **`LAYOUT` 在 `set -u` 下的前向引用**：`tap_tab` 在 `tools/demo-screenshots.sh:294-304` 讀 `$LAYOUT`，但 `LAYOUT` 在 `:436` 才賦值。第一次呼叫 `tap_tab` 在 `:512`，晚於 `:436`，**執行期安全**。onboarding 用的是 `tap_text`（`:495,501`），不碰 `LAYOUT`。（維護性風險見 Minor-3。）
2. **python helper 的 `sys.argv` 位移**：`tap-tab` 改成 `layout = argv[2]` / `wanted = argv[3:]`（`:114-115`），shell 端 `:297` 同步傳 `"$LAYOUT" "$@"`，一致。`conversation-ready` 改成三參數（`:166`），shell 端 `:526` 同步。**沒有錯位**。
3. **narrow 的判定邏輯是否被改動**：`in_bottom_bar = box[1] >= int(height * 0.85)`（`:129`）與舊版逐字相同；`in_left_rail` 被 `layout == "wide"` 擋掉（`:130`）。`conversation-ready` narrow 分支 `titles >= 1`（`:184`）與舊版 `title_seen` 布林等價，bottom-bar 否決條件（`:178-181`）也只在 narrow 生效。**narrow 行為零變化**。
4. **`warn`→`die`**：`:512,544,582,588,594,599` 六處 tab tap 與 `:546` 搜尋欄。在一次會成功的手機 run 上這些分支本來就不會觸發，所以不改變成功案例。唯一值得看的是 `:546`：我在 `feature/search/` 裡 grep 不到任何 `FocusRequester` / `requestFocus`，代表搜尋欄不會自動取得焦點，因此「點不到 hint」必然導致後面 `query_shown`（`:574`）也失敗 —— 只是現在提早約 10 秒失敗、訊息更準確。**不是回歸**。

另外我驗證了寬窄門檻本身一致：`app/src/main/kotlin/dev/quietinbox/ui/MainNavigation.kt:77` 用 `WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND`（= 600dp），與 `tools/demo-screenshots.sh:438` 的 `-ge 600` 相符；`:141-154` 是 rail、`:155-171` 是 bottom bar、`:80` `showChrome = current !is ConversationRoute || wide` 正好對應 `conversation-ready` 兩個分支的假設。

`titles >= 2` 的寬版規則我也確認為健全：`feature/inbox/src/main/kotlin/dev/quietinbox/feature/inbox/InboxScreen.kt` 的清單列沒有任何等於標題字串的 `contentDescription`，也沒有 `mergeDescendants`；而從我看的 `1_inbox` 截圖可見預覽文字是「林小美 Mia Lin: I finished…」（≠ 標題），所以單看收件匣時 `titles == 1`，細節窗格打開後才變 2。

---

## Critical（必須在推送前修）

無。

---

## Important（應在推送前修）

### Important-1 — `tap_tab` 不驗證導覽結果，且 4/5/6/7 四張截圖完全沒有內容斷言：同一類缺陷仍可產出錯畫面並通過全部關卡

`tools/demo-screenshots.sh:294-304` 的 `tap_tab` 一旦 python helper 印出座標就 `shell input tap` 並 `return 0`，**從不回頭確認畫面真的換了**。接著：

- `:582-585`（activity）：`assert_locale_clock "4_activity"` —— 但 `:314-318` 顯示 en-US 只在 `$1 = "1_inbox"` 時做正向對照，其餘一律 `return 0`，**對 en-US 而言 4/5 這兩張的斷言是 no-op**；對 CJK 而言它只斷言「沒有英文時鐘」，一張錯畫面同樣滿足。
- `:594-596`（settings）與 `:598-602`（dark inbox）：**連 `assert_locale_clock` 都沒有**，只剩 `shot()` 裡的 `assert_app_foreground`（`:384`）與 80 KB 下限（`:391-393`）。

而 `app-foreground`（`:136-143`）只要求「dump 裡有任何一個 `package == dev.quietinbox.app.debug` 的節點」。以下三種畫面**全部通過現有關卡**：

1. **App 內的錯頁面**。tap 被吞掉（app 正忙、ripple 尚未結束、rail 剛重排導致座標過期）時，`6_settings.png` 會是「擷取健康」那一頁，`app-foreground` 通過、size floor 通過、沒有任何斷言會抓到。這與本次事故是同一類故障（一次無聲的導覽失敗 → 錯畫面 → 全部關卡放行）。
2. **蓋在 App 上的 heads-up 通知**。`:483` 主動 `pm grant POST_NOTIFICATIONS`，而 emulator 常見的「Android System · USB debugging connected」或 App 自己的提醒都會畫在 App 上方；`uiautomator dump` 會同時輸出 App 與 SystemUI 兩個 window 的節點，因此 App 節點仍在 → 關卡全過，但截圖裡多了一條通知橫幅。同理適用於被下拉的通知欄與任何系統對話框（ANR、權限提示）。
3. **種子失敗後的空收件匣**（CJK 語系）。`:507` 的 seed 廣播失敗時，`1_inbox` 是空狀態，仍是 QuietInbox、仍可能過 80 KB。en-US 靠 `:315-317` 的時鐘正向對照擋下，四個 CJK 語系沒有等價的正向對照。

**建議修法（locale-free、約 5 行）**：`tap_tab` 成功後重新 dump，要求剛才匹配到的導覽節點（或其父節點）帶 `selected="true"` 再回傳 0。Compose 的 `NavigationRailItem` / `NavigationBarItem` 走 `Modifier.selectable(role = Role.Tab)`，`AccessibilityNodeInfo.isSelected` 應會被 uiautomator 寫成 `selected` 屬性 —— **這點我沒有在裝置上驗證過，請實跑一次 dump 確認**。若不成立，退而求其次用既有的 `has-text`（`:186-191`，目前只在 `:568` 用於搜尋查詢）斷言每頁自己的標題字串。

### Important-2 — Play 商店此刻仍在服務那 10 張錯截圖，而樹上沒有任何地方記錄這件事還沒做完

`CHANGELOG.md:8-14` 的 `### Fixed` 開頭寫「Ten of the fourteen tablet store screenshots were not QuietInbox… the `tenInchScreenshots` **uploaded to Google Play** for en-US and zh-TW held the launcher home screen」，結尾寫「Both locales are re-shot from the demo vault and inspected one by one.」

嚴格說這句話沒有說謊 —— 它只宣稱 repo 內的資產重拍了。但一個讀者（包含未來的維護者自己）會合理讀成「這件事已經處理完」。實際上依 `docs/RELEASE.md:38-39`，商店端還需要一趟 `images plan` → `images delete-all --type tenInchScreenshots --confirm` → `images sync`，而樹上**沒有任何 issue、TODO、或 CHANGELOG 句子**記錄這個待辦。

本專案自己的硬規則是「文件絕不超前程式碼」；這裡是它的鏡像 —— CHANGELOG 超前了商店。修法是一句話：在 `CHANGELOG.md:14` 後面補上「the Play listing still serves the old assets until `gplay images sync` is run (`docs/RELEASE.md:38-39`)」，中文側同理。

---

## Minor / nitpicks

### Minor-1 — `in_bottom_bar` 沒有對稱地被 `narrow` 擋住

`tools/demo-screenshots.sh:129-130`：

```python
in_bottom_bar = box[1] >= int(height * 0.85)
in_left_rail = layout == "wide" and box[2] <= int(width * 0.15)
```

`in_left_rail` 有 `layout == "wide"` 的守衛，`in_bottom_bar` 卻沒有 `layout == "narrow"` 的對稱守衛。依 `MainNavigation.kt:141-154`，寬版面**根本不存在** bottom bar，所以在平板上，畫面最下 15% 任何帶著同名文字的節點都是合法的 tap 目標。今天沒出事是因為 `Row` 把 `NavigationRail` 排在內容之前、helper 又回傳第一個符合的節點 —— 但這是**沒有被寫下來、也沒有被刻意依賴的**文件順序。一行修法：`in_bottom_bar = layout == "narrow" and box[1] >= int(height * 0.85)`。

### Minor-2 — rail 規則在垂直方向完全不設限，安全邊際比註解說的薄

`:130` 只檢查 `box[2] <= int(width * 0.15)`（右緣落在最左 15%），**沒有任何垂直範圍限制**。`:108-111` 的註解說「rail 只在有 rail 的版面上被接受 —— 否則手機螢幕左上角一個短的 CJK 標題會通過同樣的寬度測試」，但同一個論證在平板上同樣成立：2076px 寬時 15% = 311px，而我從 `zh-TW/6_settings.png` 量到內容窗格的兩字段落標題（「外觀」）右緣約在 300–310px，**就落在門檻上**。今天不會誤觸只因為 rail 節點在 dump 中排在前面。

建議：同時要求左緣也在最左 15%（`box[0] <= int(width * 0.15)`），或在所有候選中取 `right` 最小的那一個，而不是取第一個。600dp × 15% = 90dp 對上 `NavigationRail` 的 80dp 預設寬 —— 邊際只有 10dp，若日後改用 M3 Expressive 的 `WideNavigationRail`（展開態可達 220dp）這條規則會直接失效，值得在註解裡寫明。

### Minor-3 — `LAYOUT` 是前向引用，`screen_dp_width` 也放在 run 區塊裡

`tap_tab`（`:294`）讀 `$LAYOUT`，但 `LAYOUT` 在 `:436` 才第一次賦值，`screen_dp_width` 也定義在 `:428`（run 區塊中段）而非其他 helper 旁邊。目前安全，但在 `set -euo pipefail` 下，任何把 `tap_tab` 呼叫往上搬的改動都會直接 `LAYOUT: unbound variable` 中止。建議把 `LAYOUT="narrow"` 移到 `:52` `WORK_DIR` 附近的全域區，`:436-442` 只做覆寫。

### Minor-4 — `shot()` 每張多一次沒有重試的 `dump_ui`

`assert_app_foreground`（`:375-379`）在 `shot()` 開頭（`:384`）呼叫 `dump_ui || die`。`dump_ui`（`:273-278`）沒有任何重試，而 `uiautomator dump` 在畫面尚未 idle 時會失敗。這替每次 run 增加 7 次新的硬失敗機會，是**一條原本會成功的手機 run 現在可能失敗的新路徑**。它會大聲失敗、重跑即可，所以只算 Minor；但既然 `:570-573` 的 `query_shown` 已經有重試模式，這裡加一次重試幾乎免費。

### Minor-5 — `screen_dp_width` 讀的是實體螢幕、對旋轉無感

`:428-435` 用 `wm size` / `wm density` 算 dp 寬。這取的是實體顯示器尺寸，不是 App 視窗尺寸，因此：(a) 橫置的手機（實際上是寬版面）會被判成 narrow；(b) 分割畫面／自由視窗下判斷會偏離 App 實際拿到的 `windowSizeClass`（`MainNavigation.kt:76-77` 用的是 `currentWindowAdaptiveInfoV2()`）。誤判後 tab tap 會 `die`（fail loud，不會再產生錯截圖），所以不嚴重；建議在 `:424-427` 的註解補一句「假設直立、全螢幕」。

### Minor-6 — `TEST_MATRIX` 舊句子現在只對 narrow 成立

`docs/TEST_MATRIX.md:74`（中文 `docs/zh-Hant/TEST_MATRIX.md:65`）仍寫「the conversation shot waits until the pinned title is on screen **and the bottom bar is gone** (and fails the run otherwise)」。這在寬版面已不成立 —— `:79-82`（中文 `:68-71`）新加的段落有正確說明，但兩段落緊鄰而互相矛盾。建議把舊句子改成「on the narrow layout …」再讓新段落接續。

---

## Other observations（既有問題，不在本 diff 範圍內，但已被烘進剛上傳的商店資產）

### Obs-1 — `inbox_summary` 是 `<string>` 而非 `<plurals>`，英文商店截圖裡是「plus 1 observations」

`core/designsystem/src/main/res/values/strings.xml:51`：

```xml
<string name="inbox_summary">%1$d recognisable messages saved, plus %2$d observations with uncertain identity.</string>
```

`en-US/1_inbox.png` 與 `en-US/7_inbox_dark.png` 因此顯示「128 recognisable messages saved, plus **1 observations** with uncertain identity.」；`%1$d = 1` 時也會是「1 recognisable **messages**」。這是既有的 App 缺陷（手機版英文截圖同樣有），不是本 commit 造成的，但本 commit 正是把它重新烘進 Play 的十吋資產。CJK 語系無此問題。

### Obs-2 — `HealthScreen` 把句號後面接小寫子句

`feature/health/src/main/kotlin/dev/quietinbox/feature/health/HealthScreen.kt:331-332` 把 `health_connected_body`（`strings.xml:157`，以句號結尾）與 `health_since`（`strings.xml:156`，`since %1$s`）用一個空格串起來，產出「…for every message. **since** 11:18 PM」。en-US 與 zh-TW 的 `5_capture.png` 都看得到（中文為「…每則訊息都會發通知。 自 下午11:21 起」，同樣多一個空格）。既有問題。

### Obs-3 — 手機回歸 run 沒有留下任何可驗證的產物

commit message 宣稱「an en-US phone regression on QuietInbox_Phone, which still produces the bottom-bar layout correctly」，但 `docs/screenshots/phone/**` 在這 46 個檔案裡完全沒被動到。我因此**無法從樹上驗證**這句話 —— 我只能從程式碼推導 narrow 路徑不變（見上文回歸分析），推導結果支持這句宣稱。純屬記錄。

### Obs-4 — `CONTRIBUTING.md:22` 的 JVM 測試指令仍窄於 CI

`CONTRIBUTING.md:22` / `docs/zh-Hant/CONTRIBUTING.md:21` 只列 `:core:*` 與 `:parsers:apps`，而 `ci.yml:28-32` 另外還跑 `:platform:{crypto,storage,backup,capture}:testDebugUnitTest` 與 `:feature:{analytics,search,conversation}:testDebugUnitTest`、`:app:testDebugUnitTest`。本 commit 已經把第 4 步（instrumented）對齊 CI，第 2 步（JVM）卻沒有一起對齊。既有落差，順手可補。

---

## 英中對位（brief 第 6 點）

本輪改動的雙語對位我逐項比過，**全部一致**：

- `SCOPE.md` 四個測試數、簽章句、package id 句 → `docs/zh-Hant/SCOPE.md:16,18,19,25,42,52` 語意等價 ✅
- `CONTRIBUTING.md:22-24,30-32,36-38` → `docs/zh-Hant/CONTRIBUTING.md:21-23,28-30,33-35` ✅
- `TEST_MATRIX.md:75-84` 新段落 → `docs/zh-Hant/TEST_MATRIX.md:66-71` ✅（含 3.3 MB、600dp、`Foldable_Test` 2076×2152 等所有數字）
- `README.md:59-73`（中）vs `:161-175`（英）模組表逐行對應 ✅
- `docs/reviews/README.md:19` vs `docs/zh-Hant/reviews/README.md:17` whole-repo 列 ✅
- ADR 0001–0007 雙向連結齊全 ✅
- `docs/RELEASE.md:28` 的 `fastlane/` 前綴補上後與 `docs/zh-Hant/RELEASE.md:27` 一致 ✅
- `.github/PULL_REQUEST_TEMPLATE.md` 全部條目已改為 `English / 中文` 並列 ✅

唯一沒對齊的是 Obs-4 的 `CONTRIBUTING` 第 2 步 —— 但兩邊**一起**沒對齊，所以不是對位問題，是兩邊都落後 CI。

---

## 建議的下一步

1. `tools/demo-screenshots.sh`：`tap_tab` 加 `selected="true"` 驗證（先在裝置上確認 uiautomator 有輸出該屬性）→ Important-1。
2. `CHANGELOG.md:14` 後補一句「Play listing 仍待 `gplay images sync`」，中文側同步 → Important-2。
3. 順手收掉 Minor-1（一行）、Minor-3（搬變數）、Minor-6（一句話）。
4. 然後推送，**並立刻同步商店**（`docs/RELEASE.md:38-39`）。不要讓 Play 繼續服務 Pixel 桌布。
