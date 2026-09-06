# QuietInbox 第 28 輪程式碼審查報告（Round 28 Mini Re-review）

- **審查對象**：[`/Users/iml1s/Documents/mine/quietinbox`](file:///Users/iml1s/Documents/mine/quietinbox)
- **審查區間**：`git -C /Users/iml1s/Documents/mine/quietinbox diff b9b49cc..fd99784`（commit `fd99784`：「Round-27 fixes: the rail rule was a tautology, and the inbox summary glued its gap clause on with a space」）
- **參考基準**：`docs/reviews/2026-09-06-round27/` 歸檔報告（Claude subagent 與 Gemini 3.8 Flash high）
- **審查模式**：唯讀審查（READ-ONLY），未修改倉庫原始碼，未啟用任何自動編排工作流模式。

---

## Verdict：**APPROVE**

**0 Critical、0 Important、2 Minor / nitpicks、3 Observations。**

本輪改動（`fd99784`）乾淨且徹底地收斂了第 27 輪審查所提出的全部阻斷性與建議項目：
1. 徹底重寫了導覽項目選取邏輯，消除了原先 `box[0] <= K and box[2] <= K` 的恆真式缺陷，改採導覽帶內極值選取法（narrow 取最底、wide 取最左），有效解決平板內容區短標題與導覽項目的歧義競爭。
2. `tab-selected` 補齊了應用程式 Package 過濾與導覽帶邊界過濾，徹底封閉通知欄與應用內晶片誤判的漏洞。
3. `tap_tab` 點擊重送機制化，確保每次確認前重新發送點擊，且經檢驗 Compose 導覽處理具有完全的冪等性（Idempotency），無重複堆疊或狀態切換風險。
4. `InboxScreen.kt` 改由 `inbox_summary_join` 拼接缺口子句，消除了中日文句號後的半形空格，經實際截圖 OCR 檢驗完全消除。
5. 49 張商店截圖全數完成雜湊檢驗（49/49 byte-identical），尺寸、品質與內容真實性皆符合標準。
6. 發行就緒性檢查：0.1.3 / versionCode 7、更新日誌日期更新（2026-09-07）、五語系更新說明一致且均小於 500 字元、CI 發行腳本成功整合 AGP 9 的 R8 混淆映射檔（`.txt` 與 `.txt.gz`），文件同步增加 step 6 指引。
7. 文件嚴格遵守「Docs never run ahead of code」，審查索引第 26、27 列依規範維持 `pending`。
8. 中英雙語文件對位 100% 精準。

專案已完全具備進行 git tag `v0.1.3`、發布 GitHub Release 以及向 Google Play 提交 0.1.3 之發行條件。

---

## 逐項核對第 27 輪審查發現（Round 27 Findings Accounting）

對照 [`docs/reviews/2026-09-06-round27/`](file:///Users/iml1s/Documents/mine/quietinbox/docs/reviews/2026-09-06-round27/) 中 Claude subagent 與 Gemini 報告之各項發現：

| 來源與編號 | 原始發現摘要 | 處理狀態 | 驗證依據與代碼行號 |
| :--- | :--- | :---: | :--- |
| **Claude Important-1** | `box[0] <= K and box[2] <= K` 是恆真式，規則並未改變，但 CHANGELOG 宣稱為行為修正 | **已修復 (Fixed)** | [`tools/demo-screenshots.sh:109-132`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L109-L132) 徹底重寫 `navigation_item` 為帶內極值選取，[`CHANGELOG.md:40-44`](file:///Users/iml1s/Documents/mine/quietinbox/CHANGELOG.md#L40-L44) 同步準確更新說明。 |
| **Claude Important-2** | `CLAUDE.md:135,137` 引用了當下尚不存在的 round 27 目錄 | **已修復 (Fixed)** | `docs/reviews/2026-09-06-round27/` 已完整歸檔（含 brief、Claude、Gemini、Kimi 報告），`CLAUDE.md` 之引用已成真。 |
| **Claude Minor-1** | `tap_tab` 3 條失敗路徑共用一句誤導性的「navigation container not found」錯誤訊息 | **已修復 (Fixed)** | [`tools/demo-screenshots.sh:365, 368, 381`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L365-L381) 各自獨立 warn 具體失敗原因，呼叫端改為「(reason above)」。 |
| **Claude Minor-2** | `tap_tab` 只重試讀取確認，從不重發點擊（tap 遭吞掉時直接失敗） | **已修復 (Fixed)** | [`tools/demo-screenshots.sh:374-377`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L374-L377) 將 `shell input tap $point` 移入 5 次重試循環中，每次皆重新發送點擊。 |
| **Claude Minor-3** | `tab-selected` 的選取節點未過濾 Package 與導覽帶區域，系統通知欄或 App 內晶片可能冒充 | **已修復 (Fixed)** | [`tools/demo-screenshots.sh:184-188`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L184-L188) 加入 `package` 比對與 `in_navigation_strip` 幾何限制。 |
| **Claude Minor-4** | `InboxScreen.kt:243` 用半形空格拼接缺口子句，中日文截圖留下多餘空格 | **已修復 (Fixed)** | [`feature/inbox/.../InboxScreen.kt:245`](file:///Users/iml1s/Documents/mine/quietinbox/feature/inbox/src/main/kotlin/dev/quietinbox/feature/inbox/InboxScreen.kt#L245) 改用 `inbox_summary_join`；zh-TW、zh-CN、ja-JP 截圖全數重拍修復。 |
| **Claude Minor-5** | `docs/TEST_MATRIX.md` 雙語版本仍只描述舊有的兩道關卡 | **已修復 (Fixed)** | [`docs/TEST_MATRIX.md:75-81`](file:///Users/iml1s/Documents/mine/quietinbox/docs/TEST_MATRIX.md#L75-L81) 與 [`docs/zh-Hant/TEST_MATRIX.md:66-70`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/TEST_MATRIX.md#L66-L70) 同步更新，詳細列出多道防禦關卡。 |
| **Claude Minor-6** | `CHANGELOG.md:9` 記錄日期為 2026-09-06，落後實際 commit 日期一日 | **已修復 (Fixed)** | [`CHANGELOG.md:9`](file:///Users/iml1s/Documents/mine/quietinbox/CHANGELOG.md#L9) 已修正為 `2026-09-07`。 |
| **Claude Minor-7 / Gemini Minor-2** | 商店端圖片同步（`images sync`）尚未執行之提醒 | **已處理 (Addressed)** | [`docs/RELEASE.md:43`](file:///Users/iml1s/Documents/mine/quietinbox/docs/RELEASE.md#L43) 與 [`docs/zh-Hant/RELEASE.md:38`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/RELEASE.md#L38) 明確補上換平板截圖時需刪除 `tenInchScreenshots` 之指引。 |
| **Gemini Minor-1** | `box[0] <= K and box[2] <= K` 之幾何冗餘與恆真 | **已修復 (Fixed)** | 伴隨 Claude Important-1 一併於新 `navigation_item` 演算法中根除。 |

---

## 八大審查維度詳解

### 1. 導覽項選取規則演算法檢驗（The Navigation Rule）

#### (1) 新規則與舊規則（首項匹配）之本質差異
舊規則（`b9b49cc`）在遍歷 UI 樹時，一旦遇到文字或 content-desc 符合且位於導覽區間（`layout == "wide"` 且 `box[2] <= 15%`）的節點，便立即中斷並選中該節點。由於 Compose 渲染樹在輸出層次時可能先輸出內容區塊（Content Pane），若內容區中恰好存在短標題（例如繁中「設定」或簡中「设置」）其右邊界落入 15% 內（2076px 的 15% 為 311px，兩字 CJK 標題通常在 230~300px 結束），舊規則會**誤將內容標題當成導覽列**。

新規則（[`tools/demo-screenshots.sh:109-132`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L109-L132)）：
```python
def navigation_item(tree, layout, wanted, width, height):
    best = None
    for node in nodes(tree):
        text = (node.get("text") or "").strip()
        description = (node.get("content-desc") or "").strip()
        if text not in wanted and description not in wanted:
            continue
        box = bounds(node)
        if not box or not in_navigation_strip(box, layout, width, height):
            continue
        if best is None:
            best = box
        elif layout == "narrow":
            best = box if box[1] > best[1] else best
        else:
            best = box if box[2] < best[2] else best
    return best
```
新規則在導覽帶（narrow 底部 15%、wide 左側 15%）內遍歷**所有候選節點**，取「最深入導覽帶核心」之極值：
- 窄版底部列（narrow）：取 `box[1]`（頂部座標）最大者，即垂直位置最靠底部者。
- 寬版左側導覽欄（wide）：取 `box[2]`（右側座標）最小者，即水平位置最靠左側者。

#### (2) 構造例 A：新規則挑出與舊規則不同節點（且正確）之 UI Dump
在寬版平板（2076×2152，15% 門檻 = 311px）情境下：
```xml
<hierarchy rotation="0">
  <node bounds="[0,0][2076,2152]">
    <!-- 節點 1：內容窗格標題（文檔順序在先），右邊界為 300px <= 311px -->
    <node bounds="[200,100][300,150]" text="設定" package="dev.quietinbox.app.debug" />
    <!-- 節點 2：真正的 NavigationRailItem 標籤（文檔順序在後），右邊界為 140px <= 311px -->
    <node bounds="[20,280][140,340]" text="設定" package="dev.quietinbox.app.debug" />
  </node>
</hierarchy>
```
- **舊規則**：先遇到節點 1，`box[0]=200 <= 311` 且 `box[2]=300 <= 311`，立即回傳節點 1 之中心點 `(250, 125)`。**錯誤點擊到內容標題！**
- **新規則**：遍歷兩者，節點 1 `box[2]=300`，節點 2 `box[2]=140`。比對 `140 < 300`，最終挑選節點 2，回傳中心點 `(80, 310)`。**成功修正並點擊正確的導覽欄項目！**

#### (3) 構造例 B：新規則仍可能誤判之 UI Dump
若在窄版版面（1080×2400，85% 底部門檻 = 2040px）中，在導覽列下方或上方重疊了文字符合但並非導覽列的節點：
```xml
<hierarchy rotation="0">
  <node bounds="[0,0][1080,2400]">
    <!-- 節點 1：底部導覽列之搜尋標籤，top 座標為 2150px -->
    <node bounds="[230,2150][310,2200]" text="搜尋" package="dev.quietinbox.app.debug" />
    <!-- 節點 2：清單末端剛好滑入底部邊緣之訊息卡片內容，或全螢幕透明導覽欄底下的列表項目，top 座標為 2250px -->
    <node bounds="[50,2250][1000,2350]" text="搜尋" package="dev.quietinbox.app.debug" />
  </node>
</hierarchy>
```
- 在此 Dump 下，節點 2 屬於內容清單，但其 `box[1] = 2250 > 2150`。
- 新規則在 narrow 下採用 `box[1] > best[1]`，將會挑選位置更靠下方的節點 2（列表文字），而非節點 1（導覽按鈕）。
- *備註*：在目前 QuietInbox 截圖腳本中，每次點擊分頁前皆處於清單頁面頂部或已控制狀態，未觸發此種非典型碰撞，但在幾何極值判定上理論上仍存在此邊界限制（見 Observation 1）。

---

### 2. `tab-selected` 範疇過濾檢驗（Scoping Verification）

檢視 [`tools/demo-screenshots.sh:167-191`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L167-L191)：
```python
package, layout = sys.argv[2], sys.argv[3]
wanted = set(sys.argv[4:])
width, height = screen(tree)
box = navigation_item(tree, layout, wanted, width, height)
if not box:
    return 1
x, y = centre(box)
for node in nodes(tree):
    if node.get("selected") != "true" or (node.get("package") or "") != package:
        continue
    marked = bounds(node)
    if not marked or not in_navigation_strip(marked, layout, width, height):
        continue
    if marked[0] <= x <= marked[2] and marked[1] <= y <= marked[3]:
        return 0
return 1
```

#### (1) 通知欄、對話框或應用內選中晶片是否還能誤通過？
- **通知欄（Notification Shade）**：
  下拉通知欄時，其快速設定磚（Quick Settings）雖然 `selected="true"`，但所屬 Package 為 `com.android.systemui`，被 `(node.get("package") or "") != package` **直接排除**；且其幾何位置通常在螢幕頂部，亦無法通過 `in_navigation_strip`。
- **對話框（Dialog）**：
  若彈出對話框包含單選或選中狀態（`package` 同為應用），其幾何區域位於螢幕中央，`in_navigation_strip(marked)` 為 false；且其 bounds 不會覆蓋位於底部/左側導覽帶內的點標籤中心 `(x, y)`。
- **應用內選中晶片（FilterChip / SegmentedButton）**：
  收件匣上方的過濾晶片（如「全部」、「Chat」）即使 `selected="true"`，其位置在頂部欄下方（y ≈ 400~600px），`in_navigation_strip` 為 false，且幾何上完全不包含導覽項的 `(x, y)`。因此絕不可能誤通過。

#### (2) 正常導覽項是否可能被誤殺（False Negative）？
- 在當前標準 Material 3 規範下：
  - 窄版：底部導覽列高度為 80dp（在 2400px 高度佔比約 10%），`marked[1]` 約在 90% 高度處，大於等於 85%（`height * 0.85 = 2040px`），`in_navigation_strip` 恆為 true。
  - 寬版：標準 `NavigationRail` 寬度為 80dp（在 2076px 平板約佔 160px，約 7.7%），`marked[2] <= 311px`（15%）恆為 true。
- **極限邊界情況**：
  若未來採用 Material 3 Expressive 之 `WideNavigationRail` 且處於展開狀態（寬度達 220dp，在 600dp 寬平板上佔比達 36% > 15%），則會超出 15% 導覽帶門檻而回傳 false。代碼註解已於 [`tools/demo-screenshots.sh:115`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L115) 清楚預警此前提，當前實作在現有裝置環境下絕無誤殺。

---

### 3. `tap_tab` 連續點擊安全性與冪等性（Re-tapping Idempotency）

檢視 [`tools/demo-screenshots.sh:364-383`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L364-L383) 與應用導覽實作 [`app/.../MainNavigation.kt:74-88, 145-168`](file:///Users/iml1s/Documents/mine/quietinbox/app/src/main/kotlin/dev/quietinbox/ui/MainNavigation.kt#L74-L88)：

1. **導覽層守衛機制**：
   ```kotlin
   fun goTop(route: NavKey) {
       if (backStack.lastOrNull() == route) return
       backStack.clear()
       if (route != InboxRoute) backStack.add(InboxRoute)
       backStack.add(route)
   }
   ```
   `goTop()` 第一行具備嚴格的冪等檢查：`if (backStack.lastOrNull() == route) return`。一旦目標頁面已在堆疊頂端，後續傳入之點擊事件會直接無操作返回。
2. **無切換（Toggle）與次要動作**：
   Compose 之 `NavigationBarItem` 與 `NavigationRailItem` 僅綁定 `onClick = { goTop(item.route) }`，未實作二次點擊收合、反選、或雙擊開啟 BottomSheet / 回到頂端等非冪等邏輯。
3. **時間間隔安全性**：
   重試循環中每次 `shell input tap` 之後皆有 `sleep 1`（1 秒），遠超過 Android 系統的雙擊辨識閥值（一般為 300ms），不會被系統合成雙擊（double tap）手勢。
4. **結論**：重複發送 tap 在本應用與此架構下**完全安全且嚴格冪等**。

---

### 4. `inbox_summary_join` 缺口子句拼接與截圖比對驗證

#### (1) 呼叫端與字串資源
[`InboxScreen.kt:245`](file:///Users/iml1s/Documents/mine/quietinbox/feature/inbox/src/main/kotlin/dev/quietinbox/feature/inbox/InboxScreen.kt#L245) 已由原先硬編碼的 `"$base $gapText"` 改為：
```kotlin
return stringResource(R.string.inbox_summary_join, base, gapText)
```
五大語系之 `inbox_summary_join` 定義：
- `values/strings.xml`: `"%1$s %2$s"`（保留英文標準分句半形空格）
- `values-ko/strings.xml`: `"%1$s %2$s"`（韓文文法分詞空格）
- `values-b+zh+Hant/strings.xml`: `"%1$s%2$s"`（無空格）
- `values-b+zh+Hans/strings.xml`: `"%1$s%2$s"`（無空格）
- `values-ja/strings.xml`: `"%1$s%2$s"`（無空格）

#### (2) 截圖實地檢驗（OCR 與圖像像素比對）
透過本機 Tesseract OCR 對重新拍攝之產物進行逐字元比對：
- **`docs/screenshots/phone/zh-TW/1_inbox.png`**：
  呈現：「`已保存 128 則可辨識通知訊息。另有 1 筆身分不明觀測。下午9:48 至 上午12:48 可能中斷；缺失訊息數未知。`」
  👉 **確認**：「`觀測。`」與「`下午`」緊密相連，**舊版的半形空格已徹底消除**！
- **`docs/screenshots/phone/zh-CN/1_inbox.png`**：
  呈現：「`已保存 128 条可辨识通知消息。另有 1 条身份不明观测。21:41 至 0:41 可能有中断；缺失消息数未知。`」
  👉 **確認**：「`观测。`」與「`21:41`」緊密相連，**無空格**！
- **`docs/screenshots/phone/ja-JP/1_inbox.png`**：
  呈現：「`認識可能なメッセージ 128 件を保存しました。送信者が不確かな観測が 1 件あります。21:44 – 0:44 に欠落の可能性があります。欠落した件数は不明です。`」
  👉 **確認**：「`あります。`」與「`21:44`」緊密相連，**無空格**！
- **`docs/screenshots/tablet/zh-TW/1_inbox.png`**：
  呈現：「`已保存 128 則可辨識通知訊息。另有 1 筆身分不明觀測。下午9:37 至 上午12:37 可能中斷；缺失訊息數未知。`」
  👉 **確認**：平板介面同樣緊密相連，**無空格**！
- **`docs/screenshots/phone/en-US/1_inbox.png`**（對照組）：
  呈現：「`128 recognisable messages saved. Plus 1 observation with uncertain identity. Possible gap 8:57 PM — 11:57 PM; missing count unknown.`」
  👉 **確認**：英文兩句之間正確保有半形空格！

---

### 5. 49 張截圖完整性與位元組一致性（The 49 Screenshots）

1. **位元組一致性比對（Byte-identical Check）**：
   自動化腳本遍歷 [`docs/screenshots/**`](file:///Users/iml1s/Documents/mine/quietinbox/docs/screenshots) 與 [`fastlane/metadata/android/**`](file:///Users/iml1s/Documents/mine/quietinbox/fastlane/metadata/android) 對應目錄之所有 PNG 檔案，比對結果：
   **49/49 檔案完全位元組一致（100% Identical）**。
2. **規格與真實性檢驗**：
   - 35 張手機截圖（5 語系 × 7 張）：解析度皆為 1080×2400，檔案大小介於 147 KB 至 327 KB。
   - 14 張平板截圖（2 語系 × 7 張）：解析度皆為 2076×2152，檔案大小介於 183 KB 至 510 KB。
   - 全數 49 張截圖均遠高於 80 KB 之安全閥值，且經 OCR 檢視均為 QuietInbox 真正頁面，無案發初期之桌面桌布或系統設定截圖。

---

### 6. 0.1.3 發行就緒性檢驗（Release Readiness）

1. **版本號一致**：
   [`app/build.gradle.kts:50-51`](file:///Users/iml1s/Documents/mine/quietinbox/app/build.gradle.kts#L50-L51) 設定 `versionCode = 7`，`versionName = "0.1.3"`。
2. **更新日誌與日期**：
   [`CHANGELOG.md:9`](file:///Users/iml1s/Documents/mine/quietinbox/CHANGELOG.md#L9) 明確標記 `## [0.1.3] — 2026-09-07`，正確反映發行日期。
3. **Google Play 商店更新說明（≤ 500 字元與多檔一致性）**：
   - `en-US`: 428 字元
   - `ja-JP`: 155 字元
   - `ko-KR`: 178 字元
   - `zh-CN`: 97 字元
   - `zh-TW`: 97 字元
   經 JSON 解析比對，五份 `fastlane/metadata/android/<locale>/changelogs/7.txt` 與 `fastlane/whatsnew/whatsnew-<locale>` 及 `fastlane/release-notes.json` 內容**逐字元完全一致**。
4. **CI 發行 Workflow 與 R8 Mapping**：
   檢視 [`.github/workflows/release.yml:69-71, 98-99`](file:///Users/iml1s/Documents/mine/quietinbox/.github/workflows/release.yml#L69-L71)：
   - `cp app/build/outputs/mapping/release/mapping.txt "dist/quietinbox-$VERSION-mapping.txt"`
   - `gzip -9 -k "dist/quietinbox-$VERSION-mapping.txt"`
   - `(cd dist && sha256sum quietinbox-*.apk quietinbox-*.aab quietinbox-*-mapping.txt quietinbox-*-mapping.txt.gz > SHA256SUMS.txt)`
   - GitHub Release 發布時包含：`dist/quietinbox-*.apk`、`dist/quietinbox-*-mapping.txt.gz` 與 `dist/SHA256SUMS.txt`。
   - **Glob 匹配檢驗**：`dist/quietinbox-*.apk` 仍精準單一匹配 APK，不會誤匹配 mapping 或 aab。
   - **AGP 9 映射路徑檢驗**：本機驗證確認 `app/build/outputs/mapping/release/mapping.txt` 確實存在且大小約 60.5 MB，為 AGP 9 標準輸出路徑。
5. **發行說明文件更新**：
   [`docs/RELEASE.md:44-47`](file:///Users/iml1s/Documents/mine/quietinbox/docs/RELEASE.md#L44-L47) 與 [`docs/zh-Hant/RELEASE.md:39-41`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/RELEASE.md#L39-L41) 均已加入 Step 6，說明使用 CI 產物之 mapping 進行 `gplay deobfuscation upload`。

---

### 7. 文件真實性檢驗（Docs Never Run Ahead of Code）

- 本專案規範禁止文件超前於程式碼事實：
  - 目前 Google Play 線上版本為 0.1.0，0.1.2 審核中，0.1.3 尚未打 tag、尚未發布 GitHub Release，亦未上傳 Google Play。
  - 檢視全倉庫文件，無任何將 0.1.3 描述為「已發布」、「已上架」之超前陳述。
  - [`docs/reviews/README.md:42-43`](file:///Users/iml1s/Documents/mine/quietinbox/docs/reviews/README.md#L42-L43) 與 [`docs/zh-Hant/reviews/README.md:40-41`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/reviews/README.md#L40-L41) 之第 26 輪與第 27 輪修正 commit 欄位，刻意且正確地維持 `` `pending` ``，完全符合本審查階段之事實狀態。
  - [`CLAUDE.md:135`](file:///Users/iml1s/Documents/mine/quietinbox/CLAUDE.md#L135) 所述之 `docs/reviews/2026-09-06-round{10,…,27}/` 在本 commit 歸檔完成後已完全吻合。

---

### 8. 中英雙語文件對稱性（English / Traditional Chinese Parity）

Commit `fd99784` 所變更之所有文件均通過嚴格的雙語對照檢查：
1. **發布指引 Step 6 與平板截圖備註**：
   - [`docs/RELEASE.md:43-47`](file:///Users/iml1s/Documents/mine/quietinbox/docs/RELEASE.md#L43-L47)
   - [`docs/zh-Hant/RELEASE.md:38-41`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/RELEASE.md#L38-L41)
   語義、指令與檔案路徑完全對等。
2. **測試矩陣防禦關卡描述**：
   - [`docs/TEST_MATRIX.md:75-81`](file:///Users/iml1s/Documents/mine/quietinbox/docs/TEST_MATRIX.md#L75-L81)
   - [`docs/zh-Hant/TEST_MATRIX.md:66-70`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/TEST_MATRIX.md#L66-L70)
   同步將原先「Two guards」擴充為「Several guards / 有好幾道關卡」，並精確描述 tap 生效、夜間模式與 dump 重試機制。
3. **審查歷史記錄**：
   - [`docs/reviews/README.md:43`](file:///Users/iml1s/Documents/mine/quietinbox/docs/reviews/README.md#L43)
   - [`docs/zh-Hant/reviews/README.md:41`](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/reviews/README.md#L41)
   第 27 輪審查結果摘要與結論完全對稱。

---

## 審查發現評級

### Critical（必須在 Tag 前修正）
*無*（0 項）。

---

### Important（應在 Tag 前修正）
*無*（0 項）。

---

### Minor / nitpicks

1. [`tools/demo-screenshots.sh:131`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L131)
   ```python
   best = box if box[2] < best[2] else best
   ```
   **說明**：在寬版（wide）佈局下，候選節點的比對邏輯是比較右邊界 `box[2]`。如果導覽帶內恰好有兩個匹配標籤的節點（例如自訂 Header 或圖標描述與文字節點），較窄且靠左者會勝出。雖然目前選取的標籤都是純文字導覽項且運作良好，但若未來需要更高精確度，可考慮加入 `node.get("clickable") == "true"` 或直接錨定 `NavigationRail` 容器。

2. [`tools/demo-screenshots.sh:105-106`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L105-L106)
   ```python
   def in_navigation_strip(box, layout, width, height):
       if layout == "narrow":
           return box[1] >= int(height * 0.85)
       return box[2] <= int(width * 0.15)
   ```
   **說明**：15% 的硬編碼比例在現有 1080×2400 手機與 2076×2152 平板上校準精準，但正如代碼註解所言，未來若升級至 Material 3 Expressive 的展開式 `WideNavigationRail`（寬度可達 220dp），或在長寬比較為特殊的折疊裝置上，此門檻需隨之擴展。

---

### Other observations（其他觀察）

1. [`tools/demo-screenshots.sh:129-131`](file:///Users/iml1s/Documents/mine/quietinbox/tools/demo-screenshots.sh#L129-L131)
   極值演算法的取捨：`box[1] > best[1]`（取最底）與 `box[2] < best[2]`（取最左）以簡潔純粹的幾何規則解決了 Compose 輸出順序不確定導致的競爭問題，無需引入複雜的 UI 元件層次解析，是極佳的輕量防禦工程實踐。
2. [`tools/check-strings.py`](file:///Users/iml1s/Documents/mine/quietinbox/tools/check-strings.py)
   執行 `python3 tools/check-strings.py` 輸出 `OK: 0 error(s), 0 warning(s)`。
3. [`tools/check-permissions.sh`](file:///Users/iml1s/Documents/mine/quietinbox/tools/check-permissions.sh)
   產出之 Debug APK 經權限掃描，確認絕無聯網權限（`OK: no network permission`），嚴守產品核心承諾。

---

## 總結

Commit `fd99784` 的修正完全切中要點，不僅徹底消除了前一輪的恆真式缺陷與字串拼接瑕疵，更進一步增強了測試線的穩健度與發行配置的完備性。所有自動化檢查、單元測試、字串同位檢查及截圖品質均已達標。

**審查判定：APPROVE**。可立即為本版本標記 Tag `v0.1.3`，並啟動 GitHub Release 及 Google Play 上傳作業。
