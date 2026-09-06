# 第 27 輪獨立審查（迷你再審） — Claude Opus 5 subagent

- **對象**：`/Users/iml1s/Documents/mine/quietinbox`，diff `78e7487..b9b49cc`（`main`，領先 `origin/main` 2 個 commit）
- **範圍**：131 個檔案、+628 / −48。其中 98 張 PNG（49 張 `docs/screenshots/**` + 49 張對應的 fastlane 資產）、33 個文字檔，含 `tools/demo-screenshots.sh`（+72 −11）、`InboxScreen.kt` / `HealthScreen.kt`、五份 `strings.xml`、五份 `changelogs/7.txt` + `whatsnew-*` + `release-notes.json`、`app/build.gradle.kts` 版本跳號、以及 CHANGELOG / CLAUDE.md / CONTRIBUTING / README / TEST_MATRIX / reviews 索引。
- **方式**：唯讀。實際跑過 git 指令、`python3 tools/check-strings.py`、49 張 PNG 的逐檔 `cmp`、changelog 字數統計、`release-notes.json` 與 `whatsnew-*` 的程式化比對、`./gradlew :core:designsystem:lintDebug :feature:inbox:lintDebug :feature:health:lintDebug`（BUILD SUCCESSFUL）、`gh issue list`，並用 Read 目視開過 10 張 PNG（涵蓋 5 個手機語系與 2 個平板語系）。未做任何編輯／stage／commit。

---

## Verdict：**REQUEST CHANGES**

0 Critical、2 Important、7 Minor、5 Observations。

**先講清楚：這個 commit 交付的東西本身沒有一項是錯的。** 兩個字串缺陷確實修好了，而且我在截圖裡親眼看到修好的樣子；49 張截圖張張都是 QuietInbox、語言正確、與 fastlane 資產 byte-identical；`check-strings.py` 全綠；三個改動模組的 lint 全綠；商店文案五語一致、都在 500 字以內、沒有超賣。round-26 兩位審查者合計 16 條發現（Important-1 拆成兩半計）裡，12 條確實修掉了。

給 REQUEST CHANGES 的理由只有兩條，兩條都是**文件對一個不存在的事實做了宣稱**——這正是本專案自己的硬規則（「docs must not run ahead of code」）所禁止的，而且兩條都是一行修正：

1. `CHANGELOG.md:40-41` 宣稱「the rail strip now bounds both edges of a candidate」是一個**行為變化**。它不是：新加的 `box[0] <= K` 在任何合法矩形下都被 `box[2] <= K` 蘊含，是恆真式。註解裡舉的那個例子（平板內容窗格的兩字 CJK 標題）我從截圖量過，改動後**仍然會通過**。這句話會被寫進 `v0.1.3` 的 tag。
2. `CLAUDE.md:135,137` 引用 `docs/reviews/2026-09-06-round{10,…,27}/` 與「rounds 26–27」，但樹上沒有 `round27` 目錄，`docs/reviews/README.md` 也沒有第 27 列。

**建議**：改掉這兩句（或把 rail 規則真的改成有效的版本）後直接 tag + 上傳，不要再開一輪。第 1 條的執行期影響是零——rail 規則和改動前一樣寬鬆，而改動前那一輪 run 是成功的。

---

## 我實際驗證了什麼（供交叉比對）

### 兩個字串修正在截圖裡確實看得到

| 檢查 | 結果 |
| --- | --- |
| `docs/screenshots/phone/en-US/1_inbox.png` | 「128 recognisable messages saved. **Plus 1 observation** with uncertain identity.」單數 ✅ |
| `docs/screenshots/phone/en-US/7_inbox_dark.png` | 同上，深色 ✅ |
| `docs/screenshots/tablet/en-US/1_inbox.png` | 同上，左側 rail、Inbox 選取 ✅ |
| `docs/screenshots/phone/en-US/5_capture.png` | 「**Connected since 11:56 PM, but that does not guarantee** the source posts a notification for every message.」一句話 ✅ |
| `docs/screenshots/tablet/en-US/5_capture.png` | 同上 ✅ |
| `docs/screenshots/phone/ko-KR/5_capture.png` | 「오전 12:11부터 연결되어 있지만, …」一句話 ✅ |

`values/strings.xml:51-59`：`inbox_summary_saved` 有 `one`/`other`，`inbox_summary_uncertain` 有 `one`/`other`，`inbox_summary_join` = `%1$s %2$s`。四個 CJK 目錄（`:51-57`）各只有 `other`，`inbox_summary_join` 在 zh-Hant / zh-Hans / ja 是 `%1$s%2$s`（無空格）、ko 是 `%1$s %2$s`（有空格）——排版判斷正確。`one` 只出現在英文，符合 CLDR。

`inbox_summary` / `health_since` 在全樹（`*.xml` + `*.kt`）**沒有任何殘留引用**。`health_connected_body` 仍在用（`HealthScreen.kt:334` 的 `?:` 分支，`connectedSinceEpochMs == null` 時）——不是死字串。

`InboxScreen.kt:219-232` 的 `ambiguous == 0` 分支正確：只回傳 `savedText`，不走 join，不會印出「plus 0 observations」。

### 我實際跑過的關卡

| 指令 | 結果 |
| --- | --- |
| `python3 tools/check-strings.py` | `OK: 0 error(s), 0 warning(s)` |
| `./gradlew :core:designsystem:lintDebug :feature:inbox:lintDebug :feature:health:lintDebug` | `BUILD SUCCESSFUL`，exit 0（lint 是硬關卡、無 baseline，所以新的 `plurals` 沒有踩到 `ImpliedQuantity` 之類） |
| 49 張 `docs/screenshots/**` 逐檔 `cmp` 對 `fastlane/metadata/android/**` | **49/49 identical**（35 張 phone × 5 語系 + 14 張 tablet × 2 語系；fastlane 另有 10 張 icon/featureGraphic，不在此列） |
| 五份 `changelogs/7.txt` 字元數 | en-US 428、ja-JP 155、ko-KR 178、zh-CN 97、zh-TW 97——全部 ≤ 500 ✅ |
| `release-notes.json` vs `whatsnew-<locale>` | 五語**逐字元 IDENTICAL**（用 json 解析後比對，不是目視） |
| `gh issue list` | `CLAUDE.md:132-134` 宣稱的 issues #22–#27、label `audit-2`（描述寫「來自 GPT-5.5 Pro 2026-09-07 複審（base 78e7487）」）**確實存在** ✅ |
| `docs/reviews/2026-09-06-round26/` | brief + claude-subagent + gemini-agy + kimi-blocked 四份都在，`claude-subagent.md` 的 md5 與 blob 相同 → 逐字歸檔 ✅ |

### 我目視開過的 10 張截圖（每一張都是 QuietInbox、語言與目錄相符）

| 檔案 | 內容 | 語言 |
| --- | --- | --- |
| phone/en-US/1_inbox | 底部導覽列、Inbox 選取、"Plus 1 observation" | 英文 ✅ |
| phone/en-US/5_capture | Capture／Connected／Pipeline／Sources | 英文 ✅ |
| phone/en-US/7_inbox_dark | 深色收件匣 | 英文 ✅ |
| phone/zh-TW/1_inbox | 收件匣、全部／chat／family／team | 繁中 ✅ |
| phone/ja-JP/1_inbox | 受信箱、すべて、キャプチャ | 日文 ✅ |
| phone/ko-KR/5_capture | 캡처／연결됨／파이프라인 | 韓文 ✅ |
| phone/ko-KR/2_conversation | 김미아 Mia Kim 對話、底部列已消失 | 韓文 ✅ |
| phone/zh-CN/6_settings | 设置／外观／隐私与安全／保留期限 | 簡中 ✅ |
| tablet/en-US/1_inbox | 左 rail + 清單 + "Pick a conversation" 佔位 | 英文 ✅ |
| tablet/zh-TW/6_settings | 左 rail（設定選取）+ 外觀／隱私與安全／保存期限 | 繁中 ✅ |
| tablet/zh-TW/3_search | 左 rail（搜尋選取）+ meeting + 「14 筆結果」 | 繁中 ✅ |

（上表 11 列，含 tablet 3 張。）舊 round 26 抓到的那張「英文系統 Settings 被歸檔成 zh-TW `3_search`」已經被真正的中文搜尋頁取代。

### `tab-selected` 的 `selected="true"` 假設：這次 run 已經實證

round 26 明確寫了「這點我沒有在裝置上驗證過」。我從檔案時間戳把它釘死了：

```
core/designsystem/.../values/strings.xml   Sep 6 23:53:28
tools/demo-screenshots.sh                  Sep 6 23:54:13   ← 新 tap_tab 存檔
docs/screenshots/**（全部 49 張）           Sep 7 00:14:56   ← 一次性複製進 repo
commit b9b49cc                             Sep 7 00:15:34
```

而截圖狀態列的時鐘：en-US 11:57–11:59 PM、zh-TW 12:01–12:02 AM、zh-CN 12:04–12:06、ja-JP 12:08、ko-KR 12:11–12:13。**全部落在腳本存檔（23:54:13）之後。** 也就是說七個 `tap_tab` 呼叫點 × 7 個 run（5 手機 + 2 平板）都跑過新的 `tab-selected`，而且全部回傳 0。Compose 的 `NavigationRailItem` / `NavigationBarItem` 確實會把 `selected` 寫進 `AccessibilityNodeInfo` 並出現在 uiautomator dump 裡——這件事現在有經驗證據，不再是假設。

---

## 逐條對照 round 26 的發現

### Claude subagent（REQUEST CHANGES）

| # | 發現 | 判定 | 依據 |
| --- | --- | --- | --- |
| Important-1（前半） | `tap_tab` 不驗證導覽結果 | **已修** | `tools/demo-screenshots.sh:344-363` 新增 tap 後 5 次輪詢 `tab-selected`；`:143-174` 是新的 helper。但仍有殘餘邊界，見 Minor-1／Obs-1 |
| Important-1（後半） | 4/5/6/7 四張完全沒有內容斷言 | **未處理** | `:645` `assert_locale_clock "4_activity"`、`:651` `"5_capture"` 對 en-US 仍是 no-op（`:373-378` 只在 `$1 = "1_inbox"` 做正向對照）；`6_settings`（`:655-657`）與 `7_inbox_dark`（`:660-664`）任何語系都沒有斷言。`tab-selected` 部分補上了這個缺口（現在至少確認「選到了正確的分頁」），但「這一頁的內容真的長對」仍然沒有任何檢查 |
| Important-2 | Play 仍在服務壞掉的截圖，樹上無記錄 | **未處理**（但已失去急迫性） | `CHANGELOG.md:23-31` 仍以「Both locales are re-shot from the demo vault and inspected one by one.」收尾，樹上沒有任何 issue／TODO／句子提到 `gplay images sync` 還沒跑。**實務上這條已經自然消解**：0.1.3 上傳會一次同步全部 49 張。因此我把它降為 Minor-7，不再列 Important |
| Minor-1 | `in_bottom_bar` 沒有被 narrow 擋住 | **已修** | `:132`、`:167` 兩處都加上 `layout == "narrow" and` |
| Minor-2 | rail 規則邊際太薄／建議 `box[0] <= 15%` 或取 right 最小者 | **未修（實作了無效的那一半）** | `:137`、`:168` 採用了 `box[0] <= K and box[2] <= K`。**這在邏輯上等價於 `box[2] <= K`**，見 Important-1。這是我這一系譜的 reviewer 自己給錯的建議——round 26 的第一個建議本身就是恆真式，第二個建議（取 `right` 最小者）才會有效。實作者是照建議做的 |
| Minor-3 | `LAYOUT` 前向引用 | **已修** | `:53-55` 移到 `WORK_DIR` 旁的全域區，並附註原因；`:495-497` 只做覆寫 |
| Minor-4 | `dump_ui` 沒有重試 | **已修** | `:314-328` 三次嘗試、每次先 `rm -f`、間隔 1 秒 |
| Minor-5 | `screen_dp_width` 讀實體螢幕、對旋轉無感 | **已修（以文件形式）** | `:487-489` 補上「假設直立、全螢幕；判斷錯會讓 tab tap 大聲失敗而非產生錯截圖」。我驗過這句話成立：寬裝置被判 narrow → 底部 15% 沒有導覽節點 → `tap-tab` 回傳 1 → die；窄裝置被判 wide → 手機底部列第一項的 `box[2]`（1080×5 → 約 216px）> 15%（162px）→ 同樣 die |
| Minor-6 | `TEST_MATRIX` 舊句子只對 narrow 成立 | **已修** | `docs/TEST_MATRIX.md:74`／`docs/zh-Hant/TEST_MATRIX.md:65` 都改成「(on the narrow layout)」／「在窄版面」 |
| Obs-1 | `inbox_summary` 是 `<string>`，英文「plus 1 observations」 | **已修** | 五份目錄 + `InboxScreen.kt:219-232`，截圖已證實 |
| Obs-2 | `HealthScreen` 句號後接小寫子句 | **已修** | `HealthScreen.kt:329-334` + 五份 `health_connected_body_since`，截圖已證實 |
| Obs-3 | 手機 run 沒有留下可驗證的產物 | **已解決** | 35 張手機截圖全部重拍並進了這個 commit，時間戳與時鐘可交叉驗證 |
| Obs-4 | `CONTRIBUTING.md` 第 2 步窄於 CI | **已修** | `CONTRIBUTING.md:21-22`、`docs/zh-Hant/CONTRIBUTING.md:20-21` 都補上了 `:platform:*` 與 `:feature:*`／`:app:` 那一串 |

### Gemini 3.8 Flash high（APPROVE WITH MINOR FIXES）

| # | 發現 | 判定 | 依據 |
| --- | --- | --- | --- |
| Minor-1 | `in_bottom_bar` 未對稱守衛 | **已修** | `:132`、`:167` |
| Minor-2 | 夜間模式失敗只 `warn` | **部分修** | `:661` 已改成 `die`。但它檢查的是**指令的離開碼**，不是**模式真的套用了**：`cmd uimode night yes` 在模式已經是 yes、或 App 自己覆寫主題（設定→外觀→淺色）時仍回 0，`7_inbox_dark` 照樣可能是淺色。gemini 描述的故障情境（淺色被當深色出貨）沒有被關掉，只是關掉了其中一條路徑 |
| Minor-3 | README 裝置指令少了 `:platform:backup` | **已修** | `README.md:85-87`（中文半邊）與 `README.md:188`（英文半邊）都補上，並把註解改成「…、備份容器」 |

---

## Critical（必須在 tag 前修）

無。

---

## Important（必須在 tag 前修）

### Important-1 — `box[0] <= K and box[2] <= K` 是恆真式：CHANGELOG 為一個沒有行為變化的改動寫了行為宣稱

`tools/demo-screenshots.sh:137`（`tap-tab`）與 `:168`（`tab-selected`）：

```python
in_left_rail = layout == "wide" and box[0] <= int(width * 0.15) and box[2] <= int(width * 0.15)
```

`bounds()`（`:78-83`）解析 `bounds="[l,t][r,b]"`，對任何合法節點恆有 `box[0] <= box[2]`。因此 `box[2] <= K` **蘊含** `box[0] <= K`，新加的子句在邏輯上不可能改變任何一次判定的結果。這條規則今天和 `78e7487` 那一版**逐字等效**。

而 `CHANGELOG.md:40-41` 把它寫成一個修好的東西：

> the rail strip now bounds both edges of a candidate (a two-character CJK heading in a tablet content pane ends near the 15% line)

`tools/demo-screenshots.sh:133-136` 的註解是同一句話。**這句話舉的那個例子改動後仍然會通過**：我從 `docs/screenshots/tablet/zh-TW/6_settings.png` 量過，內容窗格的「外觀」在 2076px 原圖上左緣約 236px、右緣約 311px；15% = 311。`box[0]=236 <= 311` ✅、`box[2]=311 <= 311` ✅ → `in_left_rail` 為真，和改動前一模一樣。

本專案的硬規則是「Docs must not run ahead of the code」，而這句話會隨 `v0.1.3` 的 tag 一起發出去。

**修法（擇一，都是一行）**：
- 最省事：刪掉 `CHANGELOG.md:40-41` 的「and the rail strip now bounds both edges of a candidate…」子句與 `:133-136` 的註解，把 `box[0] <= …` 也一併刪掉（它沒有作用）。
- 真的修：把 rail 判定改成「在所有候選中取 `right` 最小的那一個」，或用 dump 裡真正的 `NavigationRail` 節點寬度當上界，而不是 `width * 0.15`。600dp × 15% = 90dp 對上 `NavigationRail` 預設 80dp——邊際只有 10dp，若日後換成 Expressive 的 `WideNavigationRail`（展開態可達 220dp）這條規則會直接失效（`:135-136` 的註解已經自己寫了這件事）。

補一句公平說明：round 26 的 Minor-2 給了兩個建議，第一個（`box[0]`）本身就是錯的，第二個（取 right 最小者）才有效；實作者是照第一個做的。這是我這一系譜的 reviewer 的錯，不是實作的錯。

### Important-2 — `CLAUDE.md:135,137` 引用不存在的 round 27

```
and its review round (`docs/reviews/2026-09-06-round{10,…,27}/`; …
…both approved with no finding; rounds 26–27 the tablet screenshot blocker and 0.1.3). ADR-0007
```

樹上 `docs/reviews/` 最後一個目錄是 `2026-09-06-round26`（`ls` 確認），`docs/reviews/README.md` 與 `docs/zh-Hant/reviews/README.md` 的最後一列都是第 26 列。第 27 輪就是這一份報告，在 `b9b49cc` 當下**還不存在**。同一段還把「rounds 26–27」寫成已完成的事實。

同段其餘宣稱我都查證屬實：GPT-5.5 Pro 2026-09-07 複審 → issues #22–#27、label `audit-2`（`gh issue list` 確認，label 描述逐字寫著「來自 GPT-5.5 Pro 2026-09-07 複審（base 78e7487）」）。

**修法**：把 `{10,…,27}` 改回 `{10,…,26}`、把「rounds 26–27」改成「round 26」，等第 27 輪歸檔後再一起改；或在歸檔第 27 輪的同一個 commit 裡才寫這句話。

---

## Minor / nitpicks

### Minor-1 — `tap_tab` 的三條失敗路徑共用一句會誤導人的 `die` 訊息

`tools/demo-screenshots.sh:348-363` 現在有三個 `return 1`：
- `:349` `dump_ui` 三次都失敗
- `:351-352` dump 裡找不到導覽節點（**舊的唯一原因**）
- `:362` 按了，但五次輪詢都沒看到該項被選取（**新原因**）

七個呼叫點（`:573,605,643,649,655,660`）一律印同一句「could not reach the X tab — **the navigation container was not found in the dump**」。第三條路徑的維護者會去找「導覽容器為什麼不見了」，但真正的原因是「按下去沒生效」或「Compose 不再輸出 `selected`」。建議讓 `tap_tab` 用不同離開碼（或先 `warn` 出實際原因）再 `return 1`。

### Minor-2 — `tap_tab` 只重試「確認」，從不重試「按」

`:355-363`：`shell input tap` 只送一次，之後 5 次迴圈只是重新 dump 再檢查。而這個守衛的整個立論就是「tap 會被吞掉」（`:345-347` 的註解自己這樣寫）。一次真的被吞掉的 tap，現在的結果是整個 run 死掉，而不是補按一次。以 7 張 × 5 語系 × 2 裝置的手動流程來說，重按一次幾乎免費（把 `shell input tap $point` 搬進迴圈、每兩次輪詢補按一次即可），而現在一個忙碌的畫格會讓整個語系重跑。

### Minor-3 — `tab-selected` 的 `selected` 清單不分 package、不分視窗、不要求是祖先

`:157-158` 把**整棵 dump 裡**所有 `selected="true"` 的節點收進來，`:170-173` 只做純幾何包含測試。因此：
- SystemUI 的節點也算數。通知欄被下拉、快速設定磚（`selected="true"`）蓋住畫面時，App 的導覽標籤仍在 dump 裡，只要某個 SystemUI 的 selected 節點在幾何上蓋住那個標籤的中心點，關卡就過——而畫面上是通知欄。
- App 內任何 `selected="true"` 的元件（`FilterChip`、`SegmentedButton`、被選取的清單列）只要壓到導覽帶內同一個點，也算數。

**修法（兩行）**：`selected` 只收 `n.get("package") == <APP_ID>` 的節點（helper 需要多收一個 argv），並要求命中的 selected 節點本身也落在導覽帶內。

### Minor-4 — `InboxScreen.kt:243` 在 CJK 語系留下一個半形空格

```kotlin
return "$base $gapText"
```

這正是這個 commit 剛在 `HealthScreen` 修掉的同一類缺陷。`inbox_summary_join` 特意為 zh-Hant / zh-Hans / ja 做成無空格（`strings.xml:57`），但 `$base` 與 `$gapText` 之間仍硬編一個 ASCII 空格。結果在**已經進了商店資產的截圖裡看得到**：
- `docs/screenshots/phone/zh-TW/1_inbox.png`：「另有 1 筆身分不明觀測。**␣**下午9:01 至 上午12:01 可能中斷；…」
- `docs/screenshots/phone/ja-JP/1_inbox.png`：「送信者が不確かな観測が 1 件あります。**␣**21:08 − 0:08 に欠落の可能性があります。…」

英文與韓文需要這個空格，中文與日文不需要。修法與 `inbox_summary_join` 同構：加一個 `inbox_summary_gap_join`（en/ko `%1$s %2$s`、zh/ja `%1$s%2$s`），或直接重用 `inbox_summary_join`。

### Minor-5 — `docs/TEST_MATRIX.md:75-78`（中文 `:66-67`）仍只描述改動前的兩道關卡

```
Two guards decide whether a file is written at all. … a navigation tap
that matches nothing fails the run rather than warning.
```

這一段是全 repo 對這個 harness 最詳細的說明，但它沒有提到本次新增的三件事：tap 後必須確認該項被選取、夜間模式切換失敗會 die、UI dump 會重試。CHANGELOG 有寫，TEST_MATRIX 沒有——這是 docs 落後於 code（方向與 Important-1 相反，但同樣讓兩份文件互相矛盾：讀者會以為只有兩道關卡）。中英兩側要一起補。

### Minor-6 — `CHANGELOG.md:9` 的日期比 commit 早一天

`## [0.1.3] — 2026-09-06`，但 `b9b49cc` 的 author/committer date 是 `2026-09-07 00:15:34 +0800`，而且 tag 與上傳都還沒發生。Keep a Changelog 的日期是「發行日」，實際會是 2026-09-07。一個字元的修正。

### Minor-7 — 樹上仍然沒有任何地方記錄 Play 端的截圖同步還沒跑（round-26 Important-2）

`CHANGELOG.md:23-31` 的收尾「Both locales are re-shot from the demo vault and inspected one by one.」嚴格說沒有說謊（它只宣稱 repo 內的資產重拍了），但讀者會合理讀成「這件事處理完了」。依 `docs/RELEASE.md:38-39`，商店端還需要 `images plan` → `images delete-all --type tenInchScreenshots --confirm` → `images sync`。

**降為 Minor 的理由**：0.1.3 的上傳會一次同步全部 49 張，這個待辦會在 tag 後幾分鐘內自然消失。只要 0.1.3 的上傳確實包含 `images sync`，就不必為此改任何文字。

---

## Other observations

### Obs-1 — `tab-selected` 通過但畫面沒換的三個可構造情境（brief 明確要求）

`MainNavigation.kt:79` 是關鍵：

```kotlin
val currentTop = backStack.lastOrNull { key -> topLevel.any { it.route == key } } ?: InboxRoute
```

而 `:146` / `:162` 都是 `selected = currentTop == item.route`。由此：

1. **`1_inbox`（`:573-574`）的關卡是恆真的。** 引導流程結束後 backStack 就是 `[InboxRoute]`，Inbox 本來就 `selected=true`。這次 tap 有沒有生效，`tab-selected` 都會在第一次輪詢回傳 0。若此時畫面上蓋著一個對話框或系統彈窗（tap 打到 scrim），`app-foreground`（`:136-143`，只要 dump 裡有一個 App 節點）也會過、80 KB 下限也會過，`1_inbox.png` 就會拍到彈窗。en-US 有 `:374-376` 的時鐘正向對照，但被遮住的收件匣節點仍在 dump 裡，一樣會過。
2. **寬版面下 `tap_tab "$NAV_INBOX"` 天生驗不出來。** `currentTop` 只看 top-level route，所以 `ConversationRoute` 在堆疊頂端時 Inbox **仍然是 selected**。一次被吞掉的「回收件匣」tap 會讓 detail pane 留在畫面上，而關卡照過。目前的流程順序沒有踩到這一格（`:660` 的 inbox tap 是從 settings 過來的），但只差一次重排。
3. **`selected` 清單不分 package**（見 Minor-3）。

這三條**不是缺陷**，是這道守衛的設計邊界：它確認的是「路由變了」，不是「畫面對了」。相對於本次事故（tap 完全打空、無聲、直接拍錯畫面），它確實往內縮了一大層——那一類故障現在會 fail loud。值得寫進 `docs/TEST_MATRIX.md` 的一句話，讓後人知道它保證什麼、不保證什麼。

### Obs-2 — 「一個本來會成功的 run 現在可能失敗」的三條新路徑

1. `:661` 夜間模式 `die`：`cmd uimode night yes` 在某些映像檔（或 uimode 服務暫時不可用時）回非零卻其實有效／或本來就已是 yes；此時 6 張好截圖之後整個 run 死掉。改動前只是 `warn`。（可接受的取捨，但值得知道。）
2. 任何讓 Compose 停止輸出 `selected` 的升級（AGP / Compose BOM / material3 alpha），會讓**七個** `tap_tab` 全部 die，harness 完全無法產圖，而錯誤訊息還指向錯的方向（Minor-1）。
3. 一次被吞掉的 tap 現在直接 die 而不重按（Minor-2）。

值得對照的是：這三條都是「大聲失敗」，不是「安靜拍錯」。以本專案的取捨（gaps are shown, never hidden）來說方向是對的。

### Obs-3 — 商店文案的語言判斷是對的，沒有超賣

英文 `changelogs/7.txt` 講了兩件事（單複數 + 為零時整句省略），四個 CJK 語系只講了「為零時整句省略」——因為 CJK 沒有單複數，講單複數會是憑空捏造。五語都以「擷取、儲存與你的資料都沒有改變」收尾，沒有把截圖修復寫成 App 改動（截圖不是 App 行為）。`release-notes.json` 與 `whatsnew-*` 逐字元相同。這一輪的商店文案我沒有任何意見。

### Obs-4 — tag 之後必須立刻補的 docs（不是這個 commit 的缺陷，是發版清單）

以下三處在 `b9b49cc` 仍以 0.1.2 為最新版本，**在 tag 前它們是正確的**（docs 沒有超前），但 tag 一打就變成落後：

- `README.md:29`（中）／`README.md:132`（英）：「目前上架的是 0.1.0；0.1.2 … 已送 Google 審查」「For 0.1.2 today, use the GitHub release」
- `docs/SCOPE.md:37`：「Play: 0.1.0 published 2026-09-06, 0.1.2 in review; GitHub: 0.1.2」
- `.github/ISSUE_TEMPLATE/bug_report.yml:14`：`placeholder: 0.1.2 (GitHub) / 0.1.0 (Play) or commit SHA`

0.1.2 當時走的就是「tag → 上傳 → 補 docs commit（`31fb2ad` / `5b114ba` / `671e52d`）」，所以這是既有慣例、不是新缺陷。只是提醒：`v0.1.3` 的 tag 會在這三處都說「最新是 0.1.2」的狀態下打出去。

### Obs-5 — `docs/reviews/README.md` 第 26 列的 fix commit 仍是 `pending`

`docs/reviews/README.md:42` 與 `docs/zh-Hant/reviews/README.md:40` 的最後一欄都是 `` `pending` ``，但 `b9b49cc` **就是**第 26 輪的修正 commit。其他列（22 → `800f65b`、24 → `1fbf693`）都填了。純記錄用途，一併補上即可。

---

## 英中對位（brief 第 6 點）

本輪改動的雙語對位我逐項比過，**沒有缺口**：

- `CONTRIBUTING.md:21-22` ↔ `docs/zh-Hant/CONTRIBUTING.md:20-21`：同一串 gradle 指令、同樣的「或 CI 實際跑的那一組」語氣 ✅
- `README.md:85-87`（中文半邊）↔ `README.md:188`（英文半邊）：都補上 `:platform:backup`，註解語意等價 ✅
- `docs/TEST_MATRIX.md:74` ↔ `docs/zh-Hant/TEST_MATRIX.md:65`：「(on the narrow layout)」↔「在窄版面」✅
- `docs/reviews/README.md:42` ↔ `docs/zh-Hant/reviews/README.md:40`：第 26 列的兩位審查者、verdict、發現摘要逐項對應（含「plus 1 observations」與「since 11:18 PM」兩個 observation）✅
- `CHANGELOG.md` 沒有中文對應檔（`docs/zh-Hant/` 下不存在 `CHANGELOG.md`），所以不是對位缺口，是既有結構
- 五語 `strings.xml` 的三個新資源名稱、placeholder、plurals quantity 由 `tools/check-strings.py` 保證，實跑通過 ✅

唯一「兩邊一起落後」的是 Minor-5（`TEST_MATRIX` 兩側都沒提新守衛）——不是對位問題。

---

## 建議的下一步

1. `CHANGELOG.md:40-41` 刪掉 both-edges 那個子句（並順手刪掉 `tools/demo-screenshots.sh:133-137,168` 裡沒有作用的 `box[0] <= …`），或改成取 `right` 最小者 → Important-1。
2. `CLAUDE.md:135,137` 把 round 27 改回 round 26，等這一輪歸檔後再一起前進 → Important-2。
3. 順手收掉 Minor-4（`InboxScreen.kt:243` 的 CJK 空格，一個新 join 字串）、Minor-5（TEST_MATRIX 一句話 ×2）、Minor-6（日期）、Obs-5（第 26 列的 commit 欄）。Minor-1/2/3 可以留到下一輪。
4. 然後 tag `v0.1.3`、上傳，**上傳時務必包含 `images sync`**（`docs/RELEASE.md:38-39`），並在上傳後補 Obs-4 的三處版本敘述。
