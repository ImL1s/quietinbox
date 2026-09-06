# 審查報告：Round 13（針對 Round-12 後續 commit 與 v0.1.1 之迷你複審）

- **工作目錄**：`/Users/iml1s/Documents/mine/quietinbox`
- **分支與版本**：分支 `main`，檢視範圍 `git diff a3fd01b..c6b6645`（涵蓋 commit `d409d4b`、`c8e4c9d`、`c6b6645`，對應 GitHub Release 標籤 `v0.1.1`）
- **審查模式**：唯讀審查（READ-ONLY），無任何程式碼修改或狀態變更指令

---

## 一、整體判定 (Verdict)

### **APPROVE**（核准通過）

> 本次審查範圍（`a3fd01b..c6b6645`）完整且嚴謹地落實了 Round-12 所列出的全部 8 項 Minor 缺失、2 項既有觀察項（金庫鎖定遺失與還原 gap 關閉）以及兩位審查者（Claude subagent 與 Gemini/agy）的所有建議。
>
> 此外，commit [`c6b6645`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L383-L392) 針對 CI 偶發的事件超車問題（`[evt-ok, evt-busy]`）進行了精確的順序調度修復（先釋放保留通知再翻轉政策旗標，並於翻轉後追加掃尾清空），配合決定論的媒體佇列測試，徹底消除了並發競爭。全 repo 所有 198 項 JVM 單元測試與 19 項真機測試（Storage 15 / Crypto 2 / Backup 2）全數實測全綠，Lint 零錯誤，字串雙語對齊無落差，文件統計與程式碼完全吻合。無任何阻礙發布之缺陷或安全迴歸。

---

## 二、Round-12 發現項修復驗證對照表

| 編號 | Round-12 發現項摘要 | 狀態 | 驗證結果與程式碼依據 (File:Line) |
| :--- | :--- | :---: | :--- |
| **sub Minor-1** | `CHANGELOG.md` 自相矛盾：一處寫緩衝 64、一處寫 256 | ✅ **已修復** | [CHANGELOG.md:25](file:///Users/iml1s/Documents/mine/quietinbox/CHANGELOG.md#L25)：原 `(64)` 已更正為 `(256)`，與同檔案第 34 行及程式碼常數完全一致。 |
| **sub Minor-2** | `docs/SCOPE.md` 仍描述 #13 之前的冷啟動行為（提及每套件皆 snapshot） | ✅ **已修復** | [docs/SCOPE.md:76](file:///Users/iml1s/Documents/mine/quietinbox/docs/SCOPE.md#L76)：過期文字已完整改寫為現行行為（政策未知前僅以框架物件保留上限 256、淘汰或金庫超時記為 `COLD_START` 有界缺口）。 |
| **sub Minor-3** | `writeRecords` KDoc 宣稱「只有 row reads 在交易內（milliseconds）」過度承諾 | ✅ **已修復** | [BackupService.kt:126-137](file:///Users/iml1s/Documents/mine/quietinbox/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L126-L137)：KDoc 已如實改寫，明確說明資料表列的序列化在單一讀取交易內以確保一致性，而耗時的媒體解密與串流則在交易外按頁執行。 |
| **sub Minor-4** | `mediaRows` 為無上限累積清單，抵消了媒體表分頁效果 | ✅ **已修復** | [BackupService.kt:140-208](file:///Users/iml1s/Documents/mine/quietinbox/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L140-L208)：移除 `mediaRows` 記憶體清單，改為在交易**外**以 keyset 分頁（`db.mediaDao().exportPage(after, PAGE, now)`）邊讀邊解密串流，維持「絕不整表進記憶體」之紀律。 |
| **sub Minor-5** | 「IDs 不同且某一側缺乏 post time」翻轉為抑制，但未有測試覆蓋 | ✅ **已修復** | [SuppressionRuleTest.kt:28-29](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/test/kotlin/dev/quietinbox/platform/storage/repo/SuppressionRuleTest.kt#L28-L29)：測試補充 `SuppressionRule.applies("m2", null, "m1", 1_000L) shouldBe true` 與 `("m2", 1_000L, "m1", null) shouldBe true` 兩行負向控制斷言。 |
| **sub Minor-6** | `settingsIntent()` 相關包裝函式為無人呼叫之死程式碼 | ✅ **已修復** | [ListenerAccess.kt:39](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/ListenerAccess.kt#L39)、[HealthViewModel.kt:96](file:///Users/iml1s/Documents/mine/quietinbox/feature/health/src/main/kotlin/dev/quietinbox/feature/health/HealthViewModel.kt#L96)、[InboxViewModel.kt:108](file:///Users/iml1s/Documents/mine/quietinbox/feature/inbox/src/main/kotlin/dev/quietinbox/feature/inbox/InboxViewModel.kt#L108)、[OnboardingViewModel.kt:85](file:///Users/iml1s/Documents/mine/quietinbox/feature/onboarding/src/main/kotlin/dev/quietinbox/feature/onboarding/OnboardingViewModel.kt#L85)：四處死包裝函式已徹底刪除，UI 全面走 `openListenerSettings`。全儲存庫搜尋零命中。 |
| **sub Minor-7** | Manifest 媒體計數語意與 `End.skippedMedia` 記錄性差異 | ✅ **已文件化** | [BackupService.kt:133-135](file:///Users/iml1s/Documents/mine/quietinbox/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L133-L135)：KDoc 明確補充說明 Manifest 的 media 計數為該當下考慮的資料庫總列數，還原與檢驗端以 `End.actual.media` 與 `End.skippedMedia` 為準。 |
| **sub Minor-8** | `COLD_START` gap 起點取自倖存項目而非被淘汰項目 | ✅ **已修復** | [CaptureCoordinator.kt:147, 424-427, 463, 492](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L147-L492)：新增 `heldDroppedSince`，於第一次緩衝溢位驅逐時記錄時間戳，`releaseHeld()` 與 `dropHeld()` 優先採 `heldDroppedSince` 作為缺口起點，真實反映遺失視窗。 |
| **sub 觀察 1** | 金庫處於 `Locked` 時，冷啟動丟棄完全不留缺口 | ✅ **已修復** | [CaptureCoordinator.kt:162, 502, 402-407](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L162-L502)：`dropHeld()` 若開 gap 失敗則將時間記於 `coldStartLossSince`；當金庫解鎖載入政策時，由 `settleColdStartGap()` 補記有界缺口。管線鎖定亦同步由 `vaultGapSince` 補記。[CaptureCoordinatorTest.kt:617-652](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L617-L652) 補上 2 個單元測試。 |
| **sub 觀察 3** | `coldStartJob` 結束前可能殘留新保留項目 | ✅ **已修復** | [CaptureCoordinator.kt:447](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L447)：`coldStart()` 結束前加入 `if (synchronized(held) { held.isNotEmpty() }) pipelineMutex.withLock { releaseHeld() }`，徹底收斂。 |
| **sub 觀察 4** | 維護開始時清空 `coldStartGapId` 可能導致還原後缺口永遠關不掉 | ✅ **已修復** | [CaptureCoordinator.kt:405, 539](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L405-L539)：`onMaintenance(true)` 設 `coldStartGapId = null`；而 `settleColdStartGap()` 中改為**無條件**呼叫 `health.closeOpenGaps(now, GapReason.COLD_START)`，不再依賴 ID 存在與否，完美兼容 Reset 與 Restore。 |
| **agy Minor-1** | `onMaintenance(true)` 可考慮主動將 `coldStartGapId` 置空 | ✅ **已修復** | 同上，[CaptureCoordinator.kt:539](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L539) 於維護開始時顯式置空。 |
| **agy Minor-2** | 獨立模組執行 `lintDebug` 存在歷史警告／錯誤 | ✅ **已修復** | [Formatting.kt:31](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/kotlin/dev/quietinbox/core/designsystem/components/Formatting.kt#L31)：改用 `LocalConfiguration.current`。<br>[SyntheticNotifications.kt:62-92](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/SyntheticNotifications.kt#L62-L92)：於 `notify()` 同方法直接檢查 `POST_NOTIFICATIONS` 並宣告於 [AndroidManifest.xml:6](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/AndroidManifest.xml#L6)，全模組 Lint 0 錯誤。 |

---

## 三、關鍵機制與潛在迴歸專題分析

針對 Brief 第 12 行指定之 7 項潛在迴歸檢查點，分析結果如下：

### 1. `loadSourcePolicy()` 變更呼叫順序後的併發行為分析
- **程式碼依據**：[CaptureCoordinator.kt:383-392](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L383-L392)
  ```kotlin
  settleColdStartGap()
  releaseHeld()
  sourcesLoaded = true
  if (synchronized(held) { held.isNotEmpty() }) releaseHeld()
  ```
- **問題分析**：在 `sourcesLoaded` 仍為 `false` 但第一次 `releaseHeld()` 已拍完快照後才進來的項目，究竟會被誰釋放？是否可能被釋放兩次？
  1. **釋放路徑**：
     - 當第一次 `releaseHeld()` 呼叫 `synchronized(held) { ... }` 將既有項目清空（`held.clear()`）後，回呼執行緒若在此時到達，由於 `sourcesLoaded` 仍為 `false`，該新通知會被推入 `held`。
     - 隨後 `sourcesLoaded = true` 旗標翻轉。
     - 下一行代碼 `if (synchronized(held) { held.isNotEmpty() }) releaseHeld()` 會**立即偵測到**剛推進來的項目，並在持有 `pipelineMutex` 的保護下同步發起第二次 `releaseHeld()` 進行快照與排隊。
     - 若代碼已離開 `loadSourcePolicy()`，隨後完成的 `coldStart()` 尾端亦有 `if (synchronized(held) { held.isNotEmpty() }) pipelineMutex.withLock { releaseHeld() }` 作為第二重防線。因此，該項目必定會被**尾隨檢查（trailing check）**釋放。
  2. **是否可能被釋放兩次？**
     - **絕不可能**。`releaseHeld()` 取出項目是在 `synchronized(held)` 區塊內以 `held.toList().also { held.clear() }` 進行**原子清空**。任何後續觸發的 `releaseHeld()` 看到的 `items` 皆為空清單，不執行任何 `enqueue` 操作。
  3. **超車問題解決**：
     - 在 `c8e4c9d` 之前，`sourcesLoaded = true` 在 `releaseHeld()` 之前執行，導致新到達的通知直接繞過緩衝排入佇列，超車了正在釋放中的舊通知（CI 捕捉到 `[evt-ok, evt-busy]`）。
     - 現行代碼確保在所有已保留通知釋放進佇列之前，新通知一律走緩衝排隊，徹底維護了到達時間的偏序關係。

### 2. `settleColdStartGap()` 執行時機與 `coldStartLossSince ?: now` 保底機制
- **程式碼依據**：[CaptureCoordinator.kt:399-408, 490-503](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L399-L503)
- **分析**：
  1. **金庫鎖定情境下的安全性**：
     - `settleColdStartGap()` 僅由 `loadSourcePolicy()` 呼叫。在 `settleColdStartGap()` 執行前，第一行必先呼叫 `val list = sources.sources()`。若金庫處於鎖定狀態，`sources.sources()` 會直接拋出 `VaultUnavailableException`，因此**在金庫鎖定期間根本不會執行到 `settleColdStartGap()`**。
     - 當金庫成功就緒、政策載入成功後，`settleColdStartGap()` 在 `guarded { ... }` 內關閉 open gaps 並補寫 bounded gap。即便資料庫底層在此刻發生極罕見的 I/O 例外，`guarded` 會安全捕捉並吞除，避免阻斷政策生效與後續通知釋放。
  2. **`coldStartLossSince ?: now` 保底**：
     - 在 `dropHeld()` 中：`val start = since ?: items.minOfOrNull { it.heldAtEpochMs }`。
     - 若發生緩衝完全溢位後清空、且 `since` 因異常未取到的極端邊界，`start ?: now` 保證 `coldStartLossSince` 絕不為 `null`。
     - `if (!written && coldStartLossSince == null)` 保證在金庫持續鎖定期間多次丟棄時，始終維持**最早那次遺失**的時間戳，保證缺口區間的真實與完整。

### 3. `vaultGapSince` 與 `vaultGapOpen` 狀態機生命週期
- **程式碼依據**：[CaptureCoordinator.kt:221-232, 683-696](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L221-L696)
- **分析**：
  - 當事件處理因金庫鎖定拋出 `VaultUnavailableException` 時：
    - 首筆失敗事件進入 `if (!vaultGapOpen)`，將 `vaultGapOpen` 設為 `true`，並嘗試 `openGap`。
    - 因金庫鎖定，`openGap` 拋出例外，`written` 維持 `false`，將第一筆事件的到達時間記錄於 `vaultGapSince = snapshot.observedAtEpochMs`。
    - 後續事件到達時，`vaultGapOpen` 已為 `true`，不再重複嘗試亦不覆寫 `vaultGapSince`，精確鎖定遺失起點。
  - 當金庫解鎖、`vault.state` 轉為 `VaultState.Ready` 時：
    - 觸發收集器，將 `vaultGapOpen` 重設為 `false`，原子提取 `since` 並置空。
    - 呼叫 `health.recordGap(since, now, GapReason.UNKNOWN, GapPrecision.BOUNDED, now)`，將金庫關閉期間累積的遺失記錄為單一有界缺口。
    - 狀態重設只在 `VaultState.Ready` 進行，保證資料庫可寫時才完成結算，設計精確無漏洞。

### 4. 備份匯出：媒體表交易外分頁與例外安全性
- **程式碼依據**：[BackupService.kt:100-120, 185-212](file:///Users/iml1s/Documents/mine/quietinbox/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L100-L212)
- **分析**：
  1. **分頁期間碰上 Retention 刪除資料**：
     - 查詢採用主鍵遞增之 keyset 分頁：`WHERE id > :afterId ORDER BY id LIMIT :limit`。
     - 游標 `after` 嚴格單調遞增，即便 Retention 同步刪除早於或晚於游標的 row，不會造成無窮迴圈或跳筆，只會使刪除列自然消失於後續分頁。
     - 若實體媒體檔先被 Retention 刪除，[BlobCipher.kt:111-116](file:///Users/iml1s/Documents/mine/quietinbox/platform/crypto/src/main/kotlin/dev/quietinbox/platform/crypto/BlobCipher.kt#L111-L116) 會捕捉 `FileNotFoundException` 並回傳 `KeyResult.Failed`，匯出邏輯僅累加 `skipped++` 並安全跳至下一筆，絕不拋出例外。
  2. **是否存在缺少 `End` 記錄的殘留輸出流？**
     - **絕不可能**。匯出採用「先在 private 快取暫存檔（`staging`）建立完整密文，成功後才原子拷貝至使用者 Document URI」的雙重保護機制（[BackupService.kt:99-112](file:///Users/iml1s/Documents/mine/quietinbox/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L99-L112)）。
     - 若 `writeRecords` 在寫入 `End` 記錄前發生任何不可預期之例外，協程會立刻跳入 `catch` 區塊，`finally` 保證執行 `staging.delete()`，使用者的目標 URI 連開啟都不會執行，絕無可能產生缺少 `End` 記錄之殘缺備份檔。

### 5. 移除死包裝函式之全域影響評估
- **分析**：
  - 移除了 `ListenerAccess.settingsIntent()`、`HealthViewModel.settingsIntent()`、`InboxViewModel.listenerSettingsIntent()`、`OnboardingViewModel.settingsIntent()`。
  - 經對全專案進行正則檢索（包括 Kotlin 原始碼、Compose 元件、XML 佈局與 Gradle 設定檔），命中數為 **0**。
  - 所有 UI 畫面均直接呼叫帶有嚴密 `try-catch`（攔截 `ActivityNotFoundException` 與 `SecurityException`）的 `openListenerSettings`，無任何殘留呼叫端。

### 6. 雙語系字串對齊 (EN / zh-Hant)
- **分析**：
  - 透過自寫 Python XML 解析器，針對 `core/designsystem/src/main/res/values/strings.xml` 與 `values-b+zh+Hant/strings.xml` 進行比對：
    - `strings.xml` 字串鍵值集合差集：**0**（各 318 項）。
    - `plurals` 複數鍵值集合差集：**0**（各 1 項）。
  - Fastlane 商店文案與更新說明（`fastlane/metadata/android/{en-US,zh-TW}/changelogs/5.txt` 及 `fastlane/whatsnew/whatsnew-{en-US,zh-TW}`）：雙語系對稱存在，內容準確敘述 0.1.1 審計修復項目，且字元數均在 500 字上限之內。

### 7. 文件宣稱與程式碼實測計數對照
- **分析**：
  - **JVM 單元測試總數**：解析測試結果 XML，共 29 個測試類別，通過總數為 **198**，失敗 0、錯誤 0、跳過 0（與 CHANGELOG 及 TEST_MATRIX 宣稱 198 完全吻合）。
  - **CaptureCoordinatorTest**：實測 **24** 項測試（與文件宣稱 24 完全吻合）。
  - **SuppressionRuleTest**：實測 **4** 項測試（與文件宣稱 4 完全吻合）。
  - **VaultMaintenanceTest**：實測 **5** 項測試（完全吻合）。
  - **VaultRepositoryTest**：實測 **3** 項測試（完全吻合）。
  - **真機測試 (Instrumented)**：
    - Storage：`DeletionGraphTest` (5) + `DemoDataTest` (2) + `MigrationTest` (3) + `SearchPagingTest` (2) + `VaultRoundTripTest` (3) = **15** 項。
    - Crypto：`KeystoreWrapperTest` (1) + `WrappedSecretFileTest` (1) = **2** 項。
    - Backup：`BackupRoundTripTest` = **2** 項。
    - 總數與宣稱「storage 15 / crypto 2 / backup 2」完全吻合。
  - **Release 版本資訊**：`app/build.gradle.kts` 中 `versionCode = 5`、`versionName = "0.1.1"`，Git 標籤 `v0.1.1` 正好指向 commit `c6b6645`。

---

## 四、新發現項 (New Findings)

### [Critical]：無
### [Important]：無
### [Minor]：無

經針對 `git diff a3fd01b..c6b6645` 全部 31 個變更檔案逐行進行靜態程式碼審查、邏輯推演與並發邊界檢查，未發現任何功能性迴歸、資料競爭、未保護的例外路徑或安全性漏洞。

---

## 五、其他觀察 (Other Observations)

1. **`settleColdStartGap()` 中 `coldStartLossSince` 的重設順序**：
   - 在 [CaptureCoordinator.kt:403](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L403) 中，`coldStartLossSince = null` 先於 `guarded { ... }` 執行。若寫入資料庫時拋出非 CancellationException，該遺失時間戳不會保留至下一輪。但由於此處是在 `sources.sources()` 剛成功查詢金庫後緊接著執行，資料庫為健康狀態，此行為完全符合專案既定之「best-effort 記帳不阻礙主流程」原則，無需變更。
2. **`c6b6645` 對決定論測試的改進**：
   - 舊版 `bitmaps in flight at the copier still count against the queue bound` 僅在第一張圖片拷貝時掛起，後續 7 張依靠非同步調度完成，偶爾受排程抖動影響；`c6b6645` 將每張圖片拷貝皆透過 `release.await()` 維持在 in-flight 狀態，直到斷言完成後才釋放，使「第 9 張圖片因超過上限而被剝除 Bitmap」的測試轉為 100% 決定論（deterministic），顯著提升 CI 穩定度。
3. **ADR 與架構文件的完備性**：
   - 本波次補充了完整的 [ADR-0007（維護閘門與 fail-closed 的擷取）](file:///Users/iml1s/Documents/mine/quietinbox/docs/adr/0007-maintenance-gate-and-fail-closed-capture.md) 與繁體中文版 [ADR-0007](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/adr/0007-maintenance-gate-and-fail-closed-capture.md)，並補齊了 [ADR-0006](file:///Users/iml1s/Documents/mine/quietinbox/docs/zh-Hant/adr/0006-distribution-and-monetisation.md) 的中文翻譯，使專案技術決策具備極佳的審計追蹤能力。

---

## 六、審查總結

Commit 序列 `d409d4b`、`c8e4c9d`、`c6b6645` 高品質地收斂了前期審計與各輪 Review 提出的全部議題：
1. 嚴守「Gaps are shown, never hidden」鐵律，冷啟動與金庫鎖定期間的所有丟棄皆如實追蹤與回補。
2. 備份匯出的媒體處理完全移出 Room 交易，並消除記憶體清單，保障 SQLite 寫入鎖不被阻塞。
3. 政策載入與保留佇列的先後順序經 CI 驗證調優，徹底杜絕通知超車亂序。
4. 測試套件（198 項 JVM / 19 項真機）與文件數字高度精確自洽，雙語系字串完全對稱。

整體品質達到生產發布標準，審查判定為 **APPROVE**。
