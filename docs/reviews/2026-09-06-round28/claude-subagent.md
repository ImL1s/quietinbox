# 第 28 輪獨立審查（迷你再審） — Claude Opus 5 subagent

- **對象**：`/Users/iml1s/Documents/mine/quietinbox`，diff `b9b49cc..fd99784`（`main`，領先 `origin/main` 3 個 commit）
- **範圍**：70 個檔案、+700 / −80。其中 56 張 PNG（28 張 `docs/screenshots/**` + 28 張對應的 fastlane 資產）、14 個文字檔，含 `tools/demo-screenshots.sh`（+87 −67，helper 重寫）、`InboxScreen.kt`、`.github/workflows/release.yml`、`CHANGELOG.md`、中英 `RELEASE.md` / `TEST_MATRIX.md` / `reviews/README.md`，以及第 27 輪四份報告的逐字歸檔。
- **方式**：唯讀。實跑 `python3 tools/check-strings.py`、49 對 PNG 逐檔 `cmp`、五份 `changelogs/7.txt` 字元數、`gplay deobfuscation upload --help`、release.yml 的 glob 展開模擬、**把新舊兩版 python helper 抽出來對合成 UI dump 跑了 9 組對照實驗**、用 PIL 量測真實截圖的導覽列/rail 邊界，並用 Read 目視開過 **14 張 PNG**（含平板 zh-TW 全部 7 張）。未做任何編輯／stage／commit。

---

## Verdict：**APPROVE WITH MINOR FIXES**

0 Critical、1 Important、8 Minor、7 Observations。

**結論先講**：第 27 輪兩位審查者合計 11 條發現（Claude subagent 2 Important + 7 Minor，Gemini 2 Minor），**7 條完全修好、3 條部分修好、1 條未處理**，其中兩條 Important 都是真的修好、不是換句話說。最關鍵的一點我做了實證：新的導覽規則**確實**和「取文件順序第一個」不同（見下方實驗 1，新版回傳 rail 的 `80 750`、舊版回傳內容窗格標題的 `260 310`），`tab-selected` 的 package／導覽帶雙重限縮**確實**擋掉了通知欄與版面上方的 selected 節點（實驗 5、7：舊版 rc=0，新版 rc=1）。收件匣摘要的半形空格修正在四個重拍語系的截圖裡親眼看得到。49 張截圖 byte-identical、`check-strings.py` 全綠、release.yml 的 glob 展開正確、AGP 9 的 mapping 路徑在本機實際存在。

唯一的 Important 是**一個會直接失敗的發行指令**：`docs/RELEASE.md:45` 與 `docs/zh-Hant/RELEASE.md:40` 寫 `--version-code <n>`，但 `gplay deobfuscation upload` 的旗標叫 `--apk-version`（我實際跑 `--help` 確認）。**它不擋 tag**（步驟 6 在 tag 之後才執行），但必須在跑 Play edit 之前改掉——兩個檔案各一個字。

---

## 逐條對照第 27 輪的發現

### Claude subagent（REQUEST CHANGES：0 Critical、2 Important、7 Minor、5 Obs）

| # | 第 27 輪的發現 | 判定 | 依據 |
| --- | --- | --- | --- |
| **Important-1** | `box[0] <= K and box[2] <= K` 是恆真式，規則一字未改而 CHANGELOG 寫成行為修正 | **已修（真的改了行為）** | `tools/demo-screenshots.sh:103-106` 的 `in_navigation_strip` 只剩 `box[2] <= int(width*0.15)`，無效子句刪除；`:109-132` 新增 `navigation_item`，在**所有**候選中取「最深入導覽帶」者（narrow 取 `box[1]` 最大、wide 取 `box[2]` 最小）。`CHANGELOG.md:40-43` 改寫成這個真實行為。**實驗 1 證明新舊選出不同節點** |
| **Important-2** | `CLAUDE.md:135,137` 引用不存在的 round 27 | **已修（用歸檔讓它成真）** | `docs/reviews/2026-09-06-round27/` 四份檔案落地；`docs/reviews/README.md:43` 與 `docs/zh-Hant/reviews/README.md:41` 新增第 27 列。三份報告與 `.omc/research/` 原檔 **md5 完全相同**（逐字歸檔），`kimi-blocked.md` 無原檔（直接撰寫的封鎖說明） |
| Minor-1 | 三條失敗路徑共用一句誤導的 `die` 訊息 | **已修** | `:365`（讀不到畫面）、`:368`（導覽帶內沒有該標籤）、`:381`（按了但沒變成選取）三句不同的 `warn`；七個呼叫點改成 `die "…(reason above)"` |
| Minor-2 | `tap_tab` 只重試「確認」，從不重試「按」 | **已修** | `:376` 的 `shell input tap` 搬進 `for attempt in 1..5` 迴圈內 |
| Minor-3 | `selected` 清單不分 package／不分視窗／不要求在導覽帶內 | **大部分已修** | `:186` 要求 `package == $APP_ID`、`:189` 要求 selected 節點本身 `in_navigation_strip`、`:191` 才做包含測試。**實驗 5、7 證明有效**。殘留：仍不是祖先關係測試（實驗 6），且 `navigation_item` 本身**仍不分 package**（見 Minor-3 下方） |
| Minor-4 | `InboxScreen.kt:243` 在 CJK 語系留下半形空格 | **已修** | `:245` 改成 `stringResource(R.string.inbox_summary_join, base, gapText)`；zh-TW / zh-CN / ja-JP 手機與 zh-TW 平板四個語系重拍，我目視確認空格消失 |
| Minor-5 | `TEST_MATRIX` 只描述改動前的兩道關卡 | **已修（中文半邊留下矛盾）** | `docs/TEST_MATRIX.md:75-81`、`docs/zh-Hant/TEST_MATRIX.md:66-70` 都補上三道關卡與 dump 重試。但中文 `:70` 仍寫「少了**這兩道**」，與同段 `:66` 的「有**好幾道**關卡」自相矛盾，且與英文 `:81` 的「Without them」不對位 → Minor-4 |
| Minor-6 | CHANGELOG 日期比 commit 早一天 | **已修** | `CHANGELOG.md:9` → `## [0.1.3] — 2026-09-07` |
| Minor-7 | 樹上沒有記錄 Play 端截圖同步還沒跑 | **部分處理** | `docs/RELEASE.md:43` / `docs/zh-Hant/RELEASE.md:38` 補上「平板截圖有換時 `--type tenInchScreenshots` 也要一起刪」。但 `CHANGELOG.md:29` 的「Both locales are re-shot … inspected one by one.」仍未加上「商店端尚未同步」的說明 → Minor-7 |
| Obs-5 | 審查索引第 26 列的 fix commit 仍是 `pending` | **未處理（brief 說是刻意的）** | 見 Observations |
| Obs-4 | README / SCOPE / bug_report 的版本敘述 | **未處理（正確，屬 tag 後工作）** | 這三處目前仍寫 0.1.2，在 `fd99784` 當下是對的 |

### Gemini 3.8 Flash high（APPROVE：2 nits）

| # | 發現 | 判定 | 依據 |
| --- | --- | --- | --- |
| Minor-1 | `box[0]` 那個子句是防禦性冗餘 | **已修** | 整句刪除（`:103-106`） |
| Minor-2 | CHANGELOG 該加一句提醒 Play listing 待同步 | **未處理** | 同 Minor-7 |

---

## 對抗性驗證：導覽規則與 `tab-selected`（brief 第 1、2、3 點）

我把新舊兩版 python helper 從 shell script 的 heredoc 抽出來（`awk` 取 `cat > "$HELPER" <<'PYTHON'` 到 `PYTHON` 之間），對手工構造的 uiautomator dump 逐一比對。**這是本輪最實在的證據，也是裝置 run 從來沒有提供過的負向對照。**

| # | 情境 | 新版 | 舊版 | 意義 |
| --- | --- | --- | --- | --- |
| 1 | 平板 2076px：內容窗格標題「設定」`[220,280][300,340]` **先** emit，rail 項目 `[0,700][160,800]` 後 emit | `0`，`80 750`（rail） | `0`，`260 310`（**內容標題**） | **新規則確實不等於取第一個匹配**。舊版會把 tap 打到內容窗格 |
| 2 | 同上但 rail 先 emit | `80 750` | `80 750` | 文件順序已正確時兩者一致 |
| 3 | 手機：bar 項目 `[0,2154][216,2400]` 先 emit，另有一個更低的節點 `[100,2300][900,2380]` | `500 2340`（**較低者**） | `108 2277`（bar 項目） | narrow 的「最低者勝」在導覽帶裡有更低節點時會選錯——但 `tab-selected` 隨後會擋下並讓 run 死掉（見 Obs-3） |
| 4 | **負向對照**：Inbox 標記 selected，問「搜尋」是否被選取 | `1` | `1` | 守衛不是恆真的。裝置 run 從未產生過這個對照 |
| 5 | 通知欄下拉：`com.android.systemui` 的 selected 磚 `[0,2100][1080,2400]` 蓋住標籤中心 | `1` | **`0`** | **package 限縮是真的修正** |
| 6 | 同 package 的 selected 節點**完全在導覽帶內**且蓋住標籤中心 | `0` | `0` | 殘留破口，但在本 App 不可達（見 Obs-2） |
| 7 | 同 package 的 selected 節點頂端在導覽帶**之上**（真實的 FilterChip 形狀） | `1` | **`0`** | **導覽帶限縮是真的修正** |
| 8 | 合法導覽項目，但 selected 容器頂端 = 2039（< 15% 線 2040） | `1` | `0` | 假陰性風險，見 Obs-4 的實測邊際 |
| 9 | 平板 rail 容器右緣 192 ≤ 311 | `0` | `0` | 平板合法路徑通過 |

### 「還會不會選錯？」——wide 版面我可以證明它不會

我用 PIL 量了真實截圖：`docs/screenshots/tablet/zh-TW/1_inbox.png` 與 `6_settings.png`（2076×2152）的 rail 底色在 **x = 0…194**，內容窗格從 x = 195 開始。因此任何內容節點的 `box[0] ≥ 195` ⇒ `box[2] ≥ 195`，而任何 rail 節點的 `box[2] ≤ 194`。**取 `box[2]` 最小者永遠落在 rail**——這不只是啟發式，在這台裝置上是可證的。15% 線 = 311，rail 還有 116px 餘裕。

`6_settings.png` 正是舊規則的碰撞案例（rail 有「設定」、內容標題也是「設定」）：我目視確認 rail 的齒輪被選取、內容是真的設定頁；而且該標題實測橫跨 x ≈ 229…397，`box[2]=397 > 311`，連候選都不是。

### `tap_tab` 重複 tap 是安全的（brief 第 3 點）

`app/src/main/kotlin/dev/quietinbox/ui/MainNavigation.kt:82-87`：

```kotlin
fun goTop(route: NavKey) {
    if (backStack.lastOrNull() == route) return
    backStack.clear()
    if (route != InboxRoute) backStack.add(InboxRoute)
    backStack.add(route)
}
```

- **冪等**：目標已在堆疊頂端就直接 return；否則清空重建成最多 `[Inbox, route]`。重按不會疊堆疊、不會 toggle、不會開啟任何東西。
- **不會被判成 double-tap**：`:376-377` 每次 tap 後 `sleep 1` 再 `dump_ui`，兩次 tap 間隔遠大於 300ms。
- 成功路徑只送一次 tap（`:379` 命中就 `return 0`）。
- 唯一的「多送 4 次」情境是規則選錯節點時：那 5 次 tap 會落在內容窗格（例如點開一則對話），但接著 `tab-selected` 一定失敗 → `die`。是大聲失敗，不是安靜拍錯。

---

## 截圖驗證（brief 第 4、5 點）

- **49/49 byte-identical**：`docs/screenshots/**` 對 `fastlane/metadata/android/**`（35 張 phone × 5 語系 + 14 張 tablet × 2 語系）逐檔 `cmp`，`identical=49 differ=0 missing=0`。
- **`python3 tools/check-strings.py` → `OK: 0 error(s), 0 warning(s)`**。
- **我目視開過 14 張**（其餘 35 張是由守衛＋時間戳鏈推得，不是眼睛看過的，特此聲明）：

| 檔案 | 內容 | 判定 |
| --- | --- | --- |
| tablet/zh-TW/1_inbox | rail「收件匣」選取＋「選一個對話」佔位；摘要**無空格** | ✅ 繁中 |
| tablet/zh-TW/2_conversation | list-detail 兩欄、rail 收件匣選取 | ✅ 繁中 |
| tablet/zh-TW/3_search | 「meeting」查詢、「14 筆結果」、rail 搜尋選取（**第 26 輪的元凶檔**） | ✅ 繁中 |
| tablet/zh-TW/4_activity | 「活動統計」、rail 活動選取 | ✅ 繁中 |
| tablet/zh-TW/5_capture | 「擷取健康」「自 上午12:36 起連線，但…」單句 | ✅ 繁中 |
| tablet/zh-TW/6_settings | rail 設定選取＋內容「設定」標題（**碰撞案例**） | ✅ 繁中 |
| tablet/zh-TW/7_inbox_dark | 深色、rail 收件匣選取、摘要無空格 | ✅ 繁中 |
| phone/zh-TW/1_inbox | 「另有 1 筆身分不明觀測。下午9:48 至…」**無空格** | ✅ 繁中 |
| phone/zh-CN/1_inbox | 「另有 1 条身份不明的观测。21:41 至…」**無空格** | ✅ 簡中 |
| phone/ja-JP/1_inbox | 「観測が 1 件あります。21:44 − 0:44 に…」**無空格** | ✅ 日文 |
| phone/ja-JP/5_capture | 「0:43 から接続していますが、…」單句 | ✅ 日文 |
| phone/en-US/1_inbox | 「Plus 1 observation with uncertain identity.**␣**Possible gap 8:57 PM…」**有空格、讀起來正確** | ✅ 英文 |

**英文與韓文沒有重拍，這是對的**：`inbox_summary_join` 在 `values` 與 `values-ko` 都是 `%1$s %2$s`，與被刪掉的硬編碼空格完全等價，這兩個語系的畫面一個像素都不會變。重拍只會換掉時鐘、製造無意義的 diff。

**新守衛已在裝置上跑過**（不是紙上推論）：`tools/demo-screenshots.sh` 存檔於 `00:34:18`，28 張重拍 PNG 的狀態列時鐘為 12:37–12:49 AM（tablet zh-TW 12:37→12:39、zh-CN 12:41、ja-JP 12:45、zh-TW 12:49），全部落在存檔之後，`fd99784` commit 於 `00:51:39`。也就是說新的 `navigation_item`、package/導覽帶雙限縮的 `tab-selected`、以及重送 tap 的迴圈，在 **narrow（3 個語系）與 wide（1 個語系）兩種版面上都實跑成功**。

---

## Critical（必須在 tag 前修）

無。

---

## Important（必須在跑 Play edit 前修；不擋 tag）

### Important-1 — `gplay deobfuscation upload` 的旗標是 `--apk-version`，不是 `--version-code`；照文件打會直接失敗

`docs/RELEASE.md:45`：

```
`gplay deobfuscation upload --package dev.quietinbox.app --edit <edit> --version-code <n> --file dist/quietinbox-<version>-mapping.txt`
```

`docs/zh-Hant/RELEASE.md:40` 是同一行的中文版，同樣寫 `--version-code <n>`。

我實跑本機的 `gplay`（`/Users/iml1s/.local/bin/gplay`，就是 0.1.2 上傳用的那一支）：

```
USAGE
  gplay deobfuscation upload --package <name> --edit <id> --apk-version <code> --file <path> [--type proguard|nativeCode]
FLAGS
  --apk-version  APK version code
```

**為什麼列 Important 而不是 Minor**：它不會出貨錯誤資產（CLI 會直接報錯），但它會**重演第 24 輪那個一模一樣的缺口**——第 24 輪就抓到「R8 mapping 不在 CI 產物裡」，結果 0.1.2 的 Play Vitals 至今無法還原（`CHANGELOG.md:56-57` 自己寫了）。這一輪好不容易把 mapping 送進 `dist/`，卻在最後一哩給了一個打不通的指令；維護者在步驟 6 撞牌、順手跳過，0.1.3 就會和 0.1.2 一樣是混淆的堆疊。修正成本是兩個檔案各一個字。

**同一個修正順手處理步驟編號**：`docs/RELEASE.md:44` 的步驟 6 排在步驟 5 之後，但步驟 5 的指令鏈以 `edits validate` → `edits commit` 收尾，而步驟 6 自己說「inside the same edit, **before `edits commit`**」。照編號順序執行的人會先 commit 掉那個 edit，步驟 6 就無從執行。建議把 mapping 上傳折進步驟 5 的指令鏈（`tracks update` 之後、`edits validate` 之前），中英兩側一起改。

---

## Minor / nitpicks

### Minor-1 — `CHANGELOG.md:11` 的「Two user-visible string defects」現在少算一項

這個 commit 修掉的是**第三個**使用者可見的文案缺陷（缺口子句的半形空格），而且它正是讓 zh-TW／zh-CN／ja-JP 三個語系的截圖必須重拍的那一項。`CHANGELOG.md:49-51` 有寫這件事，但導言 `:11` 仍寫「兩處」。

同一個少算也在五份商店文案裡：`fastlane/metadata/android/en-US/changelogs/7.txt:1`「0.1.3 fixes two wordings.」、zh-TW／zh-CN「修正兩處文案」、ja「文言を 2 か所直しました」、ko「문구 두 곳을 고쳤습니다」。這些檔案在 `b9b49cc` 就寫好、這一輪沒有動過，所以不是新引入的錯誤，但**上架文案講「兩處」而實際改了三處**。它不是虛假宣稱（沒有多說 App 能做什麼），只是少算；「擷取、儲存與你的資料都沒有改變」仍然成立。要改的話五份都要動，且長度都還遠低於 500（en 429、ja 156、ko 179、zh 各 98 字元，我實測）。

### Minor-2 — `docs/zh-Hant/TEST_MATRIX.md:70` 的「少了這兩道」與同段 `:66` 的「有好幾道關卡」自相矛盾

英文半邊 `docs/TEST_MATRIX.md:75` 改成「Several guards…」、`:81` 改成「Without them…」，中文 `:66` 也改成「有好幾道關卡」，但 `:70` 的「少了**這兩道**，第一批平板截圖拍到的是桌布與系統設定」沒有跟著改。中英對位缺口 ＋ 中文段落內部矛盾。

（順帶：英文 `:81` 的「Without them」現在指涉「several guards」，但當初讓平板截圖出事的其實只有 `app-foreground` 與「tap 找不到目標就失敗」兩道；夜間模式與 tap 確認是後來加的。中文改成「少了前兩道」之類會比兩邊都更精確。）

### Minor-3 — `navigation_item` 仍不分 package，只有確認那一步分

`tools/demo-screenshots.sh:109-132` 不接受 package 參數，因此 `tap-tab`（`:151-166`）算出來的座標**可以來自 SystemUI 或輸入法的節點**——只要它帶著同樣的標籤且落在導覽帶內。`tab-selected`（`:167-192`）已經 package 限縮，所以錯誤座標會在確認階段被擋下、run 大聲死掉，不會產出錯截圖。但把 `package` 一併傳進 `navigation_item` 是同一個 helper、同一個迴圈的兩行改動，可以讓「選錯座標」這件事根本不發生（第 27 輪 Minor-3 的另一半）。

### Minor-4 — `tap_tab` 的第四條（無聲）失敗路徑

`:378` 的 `dump_ui || continue`：若五次迴圈裡每次 dump 都失敗，迴圈跑完後 `:381` 會印「tapped 5 times but the item never became the selected one」——但實際上一次都沒檢查過。`dump_ui` 自己已有三次重試（`:335`），所以要連續 15 次失敗才會踩到，機率極低；不過既然這個 commit 的主題就是「每條失敗路徑都要說清楚是哪一條」，補一個計數器讓訊息說「五次都讀不到畫面」會更一致。

### Minor-5 — `release.yml:68` 說 gzip「is a tenth of the size」，實測是十四分之一

我用本機 `app/build/outputs/mapping/release/mapping.txt`（63,490,617 bytes）跑 `gzip -9`，得到 4,462,805 bytes = **7.0%**。「~60 MB」是準的，「a tenth」偏保守。一個字的措辭。

### Minor-6 — `SHA256SUMS.txt` 現在列了四個檔案，但 GitHub release 只掛得到其中兩個

`release.yml:71` 對 `.apk` / `.aab` / `mapping.txt` / `mapping.txt.gz` 四個檔案產生雜湊，但 `:98-99` 只上傳 `.apk`、`mapping.txt.gz` 與 `SHA256SUMS.txt`。下載 release 的人會看到兩行對應不到任何可下載資產的雜湊。`.aab` 這條是既有行為（刻意不發佈 bundle），這次新增了 `mapping.txt` 這第三行。要嘛在檔案裡註明「.aab / mapping.txt 只在 CI 產物裡」，要嘛只對實際發佈的檔案產生雜湊。

### Minor-7 — 樹上仍沒有一句話說「Play 目前還在服務壞掉的平板截圖」

第 26／27 輪都提過。`CHANGELOG.md:29` 的「Both locales are re-shot from the demo vault and inspected one by one.」嚴格說沒說謊（只講 repo 內的資產），但讀者會讀成整件事處理完了。實務上 0.1.3 的上傳會一次同步全部 49 張，所以這條會在 tag 後幾分鐘自然消失——前提是上傳流程確實跑到 `images delete-all --type tenInchScreenshots --confirm` ＋ `images sync`（`docs/RELEASE.md:38-43` 現在有寫了）。

### Minor-8 — CHANGELOG 把第 27 輪的修正掛在「Round-26 review fixes」那一顆 bullet 底下

`CHANGELOG.md:37` 的 bullet 開頭是 `Round-26 review fixes (docs/reviews/2026-09-06-round26/)`，但 `:40-43`（最深入導覽帶者勝）、`:44-45`（selected 節點限縮）、`:47-48`（重送 tap、三條失敗訊息）、`:49-51`（收件匣空格）全都是第 27 輪抓到的。整份 CHANGELOG 也從未引用 `docs/reviews/2026-09-06-round27/`。純結構性、不影響正確性，但「每個修正引用它的 issue 與它的審查輪次」是本專案自己的規則（`CLAUDE.md:132-137`）。

---

## Other observations

### Obs-1 — 發行就緒項目我逐條查過，沒有超前宣稱（brief 第 6、7 點）

| 檢查 | 結果 |
| --- | --- |
| `app/build.gradle.kts:50-51` | `versionCode = 7` / `versionName = "0.1.3"` ✅（本輪未動，`b9b49cc` 就正確） |
| `CHANGELOG.md:9` 日期 | `2026-09-07` = 今天 = 實際發行日 ✅ |
| 五份 `changelogs/7.txt` 字元數 | en 429、ja 156、ko 179、zh-CN 98、zh-TW 98——全部 ≤ 500 ✅ |
| `changelogs/7.txt` ↔ `whatsnew-*` ↔ `release-notes.json` | 本輪 `fastlane/` 底下**只有 PNG 變動**（`git diff --stat` 確認），三者的逐字元一致性由第 27 輪在 `b9b49cc` 已實測建立，遞移成立 ✅（我沒有重跑這個比對） |
| AGP 9 的 mapping 路徑 | `app/build/outputs/mapping/release/mapping.txt` 在本機**實際存在**（63.5 MB，Sep 6 19:07），路徑正確 ✅ |
| R8 是否啟用 | `app/build.gradle.kts:57-59` `isMinifyEnabled = true`，所以 mapping 一定會產生，`cp` 不會撲空 ✅ |
| release.yml glob | 我在暫存目錄放了四個同名檔案實跑展開：`sha256sum` 四個引數各對應一個檔、無重複；`gh release create` 只拿到 `.apk` 與 `mapping.txt.gz`。**新增的檔案沒有污染原本的 APK glob** ✅ |
| 「0.1.3 已 tag／已發佈／已上傳」的句子 | 全樹 grep `0.1.3`：只有 CHANGELOG 的版本標題、`build.gradle.kts` 的版號、CLAUDE.md 的輪次說明、審查索引第 27 列。**沒有任何一句假設 tag／release／Play 上傳已經發生** ✅ |
| README:29,132 / `docs/SCOPE.md:37` / `bug_report.yml:14` | 仍寫「Play 上架 0.1.0、0.1.2 審查中」——在 `fd99784` 當下正確（文件落後而非超前），與 0.1.2 當時「tag → 上傳 → 補 docs commit」的慣例一致 ✅ |
| `CLAUDE.md:135,137` | `round{10,…,27}` 與「rounds 26–27」現在都成立（round27 目錄已存在） ✅ |
| 第 27 輪歸檔逐字性 | `brief.md`／`claude-subagent.md`／`gemini-3.8-flash-high-agy.md` 與 `.omc/research/` 原檔 **md5 三份全等** ✅；`kimi-blocked.md` 無對應原檔（是為封鎖狀況直接寫的說明，非轉錄） |

### Obs-2 — `tab-selected` 的殘留破口在本 App 不可達

實驗 6 顯示：一個**同 package、完全落在導覽帶內、且蓋住目標標籤中心**的 selected 節點仍能冒充導覽項目。但在 QuietInbox 裡構造不出來：`MainNavigation.kt:146,162` 的 `selected = currentTop == item.route` 只掛在 `NavigationBarItem` / `NavigationRailItem` 上，這些項目在 narrow 是水平不相交、在 wide 是垂直不相交，所以 A 項目的 selected 容器不可能蓋住 B 項目標籤的中心（實驗 4 的負向對照就是這件事）。真正的修法是把「命中的 selected 節點必須是標籤節點的祖先」寫出來，但那需要 `ET` 建 parent map，成本高於收益。

### Obs-3 — narrow 的「最低者勝」不像 wide 的「最左者勝」那樣可證

wide 有結構保證（內容窗格永遠在 rail 右邊，見上面的量測）；narrow 只有「導覽列在畫面最底」這個經驗事實。實驗 3 顯示，只要導覽帶內存在一個比 bar 項目更低的節點（底部工作表的一列、輸入法建議列、某些 overlay），`tap-tab` 就會選它。後果是 `tab-selected` 失敗 → `die`，屬於大聲失敗；但值得知道這一側沒有結構保證。

### Obs-4 — 新的「selected 節點也要在導覽帶內」條件把邊際收得很緊，實測數字如下

我用 PIL 量真實截圖：

- **手機**（`phone/zh-TW/1_inbox.png`，1080×2400）：導覽列底色從 **y = 2127** 開始，15% 線 = 2040 → **87px 餘裕**。
- **平板**（`tablet/zh-TW/1_inbox.png`，2076×2152）：rail 底色到 **x = 194**，15% 線 = 311 → **116px 餘裕**。

注意這個條件比舊版嚴格：舊版只要求**標籤節點**在導覽帶內（標籤在容器底部，天然安全），新版要求**可選取容器**也在導覽帶內（容器頂端高得多）。實驗 8 量出的臨界值是容器頂端 ≥ 2040。目前用的模擬器是手勢導覽（截圖裡看得到 pill）；若換成三鍵導覽（系統列 48dp ≈ 132px），容器頂端會落在 ≈ 2048，**餘裕只剩約 8px**。屆時任何導覽列高度增加（Compose Material3 alpha 調整、大字級）都會讓七個 `tap_tab` 全部 `die`、整個 harness 產不出圖。註解已警告 `WideNavigationRail`，但沒警告 narrow 這一側；`in_navigation_strip` 對 selected 節點放寬到例如 25%（或改用「與導覽帶相交」而非「頂端在導覽帶內」）會便宜很多。

### Obs-5 — `1_inbox` 的 tab 確認仍然是恆真的（沿自第 27 輪 Obs-1）

`:593` 的 `tap_tab "$NAV_INBOX"` 緊接在引導流程之後，此時 `backStack = [InboxRoute]`，`goTop` 直接 return，Inbox 本來就 selected，第一次輪詢必定回 0。若當下畫面上蓋著彈窗（tap 打到 scrim），`app-foreground` 與 80 KB 下限也都會過，`1_inbox.png` 就會拍到彈窗。`:680`（7_inbox_dark 前）那次是從設定頁過來的，確認是真的。這是守衛的設計邊界，不是缺陷。

### Obs-6 — 審查索引第 26／27 列的 `pending`

brief 說是刻意的，我不把它算成 findings。純記錄：第 27 列是雞生蛋（它的 fix commit 就是 `fd99784` 本身），但**第 26 列的 fix commit `b9b49cc` 早已存在**，本可在這個 commit 裡填上。建議在歸檔第 28 輪的那個 commit 裡一次補上 26 → `b9b49cc`、27 → `fd99784`（中英兩側 `docs/reviews/README.md:42-43`、`docs/zh-Hant/reviews/README.md:40-41`）。

### Obs-7 — CHANGELOG 對 0.1.2 Vitals 的宣稱可以更精確

`CHANGELOG.md:56-57`：「Play Vitals stack traces for 0.1.2 are obfuscated because the mapping for that build was never uploaded and cannot be reconstructed.」後半（無法重建）完全正確：本機重跑 R8 的混淆結果與 CI 那次不會相同。前半預設 0.1.2 已經有當機資料——0.1.2 是 2026-09-06 才送審、目前仍在審查，實際上可能一筆 Vitals 都還沒有。改成「0.1.2 的任何 Vitals 堆疊都將無法還原」會更嚴謹。屬於措辭。

---

## 英中對位（brief 第 8 點）

本輪改動的雙語對位逐項比過：

- `docs/RELEASE.md:43` ↔ `docs/zh-Hant/RELEASE.md:38`（tenInchScreenshots）✅
- `docs/RELEASE.md:44-48` ↔ `docs/zh-Hant/RELEASE.md:39-42`（步驟 6 mapping）✅ 語意等價——**包括同一個 `--version-code` 錯誤，兩邊都要改**
- `docs/TEST_MATRIX.md:75-81` ↔ `docs/zh-Hant/TEST_MATRIX.md:66-70` ⚠️ 中文 `:70` 的「少了這兩道」未同步（Minor-2）
- `docs/reviews/README.md:43` ↔ `docs/zh-Hant/reviews/README.md:41`：第 27 列的兩位審查者、verdict、兩條 Important 與那條 Minor 的摘要逐項對應 ✅
- `CHANGELOG.md` 無中文對應檔（既有結構，非對位缺口）
- `.github/workflows/release.yml`、`InboxScreen.kt` 無雙語需求

---

## 建議的下一步

1. **`docs/RELEASE.md:45` 與 `docs/zh-Hant/RELEASE.md:40`：`--version-code` → `--apk-version`**，並把步驟 6 折進步驟 5 的指令鏈（`edits commit` 之前）→ Important-1。**這一項不擋 tag，但一定要在跑 Play edit 之前改掉。**
2. 順手收掉 Minor-2（中文 TEST_MATRIX 一句話）、Minor-5（「a tenth」→「a fourteenth」，或直接寫「~4.5 MB」最省事）、Minor-6（SHA256SUMS 說明）。
3. Minor-1（「兩處/三處」）值得改，但要動五份商店文案；若不想在發版當下動它，至少改掉 `CHANGELOG.md:11`。
4. Minor-3／Minor-4／Obs-4 可以留到下一輪（都是「大聲失敗」方向的健壯性，不影響本次出貨）。
5. 然後 tag `v0.1.3`、上傳，**上傳時務必包含 `images delete-all --type tenInchScreenshots --confirm` ＋ `images sync`** 與步驟 6 的 mapping 上傳；上傳後補 README:29,132／`docs/SCOPE.md:37`／`bug_report.yml:14` 的版本敘述，以及審查索引第 26／27 列的 fix commit 欄。
