# QuietInbox 全庫 Code Review（第四輪 pre-publication 審查報告）

- **審查標的**：`<repo>`
- **審查範圍**：`git diff 96b0cf9..1f7b182`（共變更 118 個檔案，新增 10,295 行，刪除 412 行）在全庫上下文下的表現
- **審查模式**：獨立唯讀（READ-ONLY），未改動任何儲存庫檔案，未執行破壞性指令，未操作任何實體裝置與模擬器
- **對照規範**：`dual-review-round4-brief-safe.md`、`CLAUDE.md`、`docs/SCOPE.md`、`docs/ARCHITECTURE.md`、`docs/adr/*`、全庫前三輪審查歸檔與 `.omc/research/code-review-whole-repo.md`

---

## 審查結論（Verdict）

### **APPROVE WITH MINOR FIXES**

本次 pre-publication diff 紮實地解決了上一輪全庫審查提出的所有重要缺陷（I1～I7 及多項 Minor），並完成了 Google Play 與 GitHub 雙軌發布所需的自動化管線與示範資料隔離。全庫硬性規則（無 `INTERNET` 權限、不觸碰或代行來源通知、資料品質誠實標示、無破壞性資料庫遷移、正式發布版本嚴格隔離除錯鉤子）均獲機制級遵循。本次審查未發現任何 Critical 等級的安全或資料毀損破口，僅有 2 項在統計區間過濾與中英文件數據同步上的 Important 事項，以及若干 Minor 事項建議於正式上線前微調修復。

---

## 七大專項審查分析（Focus Areas）

### 1. `IngestRepository.commit` 與會話延遲建立（I1 驗證）
- **延遲建立機制**：[`IngestRepository.kt`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/IngestRepository.kt#L175-L197) 改以 `conversationIdOrCreate()` 取代原先在迴圈前直接呼叫的 `convDao.insert(...)`。此函式僅在真的有 `Decision.New` 或 `Decision.AmbiguousRepeat` 且未被 suppression 攔截、未被既有 ID 抵銷時（即確定有新訊息寫入）才會觸發插入。
- **重播與刪除情境審核**：當使用者刪除整個會話後，舊訊息重播時因皆命中 `suppressionDao.isSuppressed(...) > 0`，`conversationIdOrCreate()` 完全不會被呼叫，`conversationId` 維持 `null`。
- **投影與 Checkpoint 安全**：
  - Checkpoint 寫入時，對於已刪除訊息一律寫入 `null`，不再保留 dangling id。
  - 會話投影更新改為 `val current = conversationId?.let { convDao.get(it) }`，若無新寫入且無會話，完全不觸發更新，徹底杜絕了先前空會話帶對方名稱復活（I1）的臭蟲。
  - `ownerId = existing?.id` 守衛在會話不存在時安全回退為 `emptyMap()`，邏輯自洽。

### 2. `SnapshotFactory.bound` `isSelf` 語意（I3）與 `CaptureCoordinator` 測試縫隙
- **`isSelf` 修正**：[`SnapshotFactory.kt`](../../../platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/SnapshotFactory.kt#L66-L68,L163) 提取了 `messaging?.user?.name?.toString()` 作為 `selfName`，並將判斷修正為 `isSelf = person == null || (selfName != null && person.name?.toString() == selfName)`。這完全符合 Android `MessagingStyle` 的官方語意，解除了先先生產路徑永遠為 `false` 的缺陷（I3）。
- **`offerCaptured` 測試縫隙**：[`CaptureCoordinator.kt`](../../../platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L316-L327) 抽出了 `internal fun offerCaptured` 與 `enqueue`。
  - **回呼執行緒**：`offer()` 依舊保持極輕量，僅解析快照並透過 `queue.trySend` 入列，無任何資料庫或點陣圖解碼耗時運算。
  - **Commit 圍籬**：`offerCaptured` 進入相同的 `Queued` 佇列，受控於相同的 `item.generation`；`process()` 內的代次檢查（`item.generation != activeGeneration`）、暫停狀態與來源白名單檢查毫無弱化。
  - **測試完備度**：新增的 [`CaptureCoordinatorTest.kt`](../../../platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt)（共 11 個測試）完整覆蓋了 generation 輪換、暫停／恢復、冷啟動白名單過濾、重試機制與 Coroutine 取消傳播。

### 3. `BackupService` 與 `BackupStager`（I2、I6、M5、M13 驗證）
- **Staging 解耦**：抽離出獨立的 [`BackupStager.kt`](../../../platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupStager.kt)，並由 [`BackupStagerTest.kt`](../../../platform/backup/src/test/kotlin/dev/quietinbox/platform/backup/BackupStagerTest.kt)（21 個測試）針對 manifest 首筆檢查、EOF 截斷、計數衝突、單行／總字元／媒體總量超限等邊界全面守護。
- **過期重算（I2）**：[`BackupService.kt`](../../../platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L298) 實作了 `expiresAtEpochMs = m.expiresAtEpochMs?.let { maxOf(it, now + retentionMs) }`，確保還原較舊備份時，訊息到期日至少以當前裝置設定的保留期重新定錨，避免在 12 小時內被 `RetentionWorker` 默默清空。
- **交易外加密（I6）**：媒體檔案在進入 Room 寫入交易前，先於 [`BackupService.kt:219-233`](../../../platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L219-L233) 循序進行 Base64 解碼與 Tink AES-GCM 加密寫檔；DB 交易內部僅做純粹的 SQL Insert，若交易拋出異常則在 catch 區塊統一清理暫存檔，徹底解決 SQLite 長時間持鎖問題。
- **重複項計數抵銷（M5）**：`preExisting` 改以 `HashMap<String, Int>` 計數消耗，重複備份項在資料庫已有對應筆數時精確遞減消耗，並於結果正確回報 `restoredRevisions`。
- **還原來源狀態（M13）**：還原未知來源時固定以 `enabled = false` 寫入，且介面提示字串同步載明「還原的來源會保持停用，請到擷取頁重新啟用」。

### 4. Demo 模式與安全隔離
- **編譯產物隔離**：`DemoReceiver.kt` 與其 Manifest 宣告置於 `app/src/debug/` source set。AGP 在執行 `assembleRelease` 時不會將其編譯進 APK，正式版本不含該 Receiver。
- **介面入口隔離**：設定頁的「開發者」區塊由 `SettingsUiState.developerTools` 控管，注入源為 `AppModule.kt` 中的 `BuildInfo(debug = BuildConfig.DEBUG, flavor = "")`。在 Release 建置下固定為 `false`，UI 完全不渲染該區塊。
- **廣播安全性**：`DemoReceiver` 僅接受 `op == "seed"` 與 `op == "clear"`，不接收任何外部自訂參數，無代碼注入或越權執行風險。
- **清理純粹性**：[`DemoDao.kt`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L398-L440) 內的所有刪除 SQL 均嚴格限定 `packageName LIKE "demo.quietinbox.%"` 與 `generation LIKE "demo-%"`，真實通訊軟體資料絕不可能被誤刪。[`DemoDataTest.kt`](../../../platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/DemoDataTest.kt) 已在真機測試驗證 seed/clear 全生命週期。

### 5. 活動統計（Insights & Analytics）
- **演算法純度**：[`Insights.kt`](../../../core/analytics/src/main/kotlin/dev/quietinbox/core/analytics/Insights.kt) 與 [`ActivityAnalytics.kt`](../../../core/analytics/src/main/kotlin/dev/quietinbox/core/analytics/ActivityAnalytics.kt) 的所有函式皆為純 Kotlin 計算。熱力圖、時段分佈（五區間）、排行、好聊度、神隱率、口頭禪（CJK 2/3-gram、英文字詞 bigram、停用詞過濾）皆排除 `AMBIGUOUS_REPEAT` 與使用者自己的發言（`isSelf`）。單元測試達 32 題，覆蓋率充足。
- **用字與誠實性**：`strings_analytics.xml`（中英雙語）的所有次標題與公式說明皆明言「僅計算觀測到的訊息」，不推測已讀率、回覆率或未送達情況。
- **大資料庫效能**：
  - 期間選擇器除了「全部」外，均透過 `statsBetween(since, until)` 在 SQL 層精確縮小讀取範圍。
  - 對話標籤載入（`analytics.labels(ids)`）僅查詢排行榜與統計摘要涵蓋的去重 ID（至多 ~100 個），而非全表掃描。
  - 所有重度計算（口頭禪與 Emoji 掃描）皆在 `Dispatchers.Default` 執行，不卡頓主執行緒。

### 6. 發布管線與金鑰規範
- **簽章配置**：[`app/build.gradle.kts`](../../../app/build.gradle.kts#L11-L38) 僅從 `keystore.properties`（受 `.gitignore` 排除）或環境變數讀取。未設定時 `signingConfig` 為 `null`，無任何私鑰或密碼洩漏至 Git 歷程。
- **CI 發布工作流**：[`.github/workflows/release.yml`](../../../.github/workflows/release.yml) 在 tag 推送或手動觸發時，先執行 JVM 測試、產出 Release APK/AAB，強制執行 `tools/check-permissions.sh` 嚴格檢驗 APK 是否混入網路權限，確認安全後才產生 `SHA256SUMS.txt`、建立 GitHub Release 並上傳 Google Play Internal Track。
- **依賴校驗**：[`gradle/verification-metadata.xml`](../../../gradle/verification-metadata.xml) 已建立（4,910 行），涵蓋全庫依賴之 SHA-256 Checksum，並將具跨平台 host 特性的 `aapt2` 列入 `trusted-artifacts`，解決 I5。
- **截圖工具**：`tools/demo-screenshots.sh` 以 shell + Python uiautomator 輔助解析，純以除錯示範資料走完整個流程並截圖，不需真實通知。

### 7. 文件誠實性核對
- **雙語 README**：中英文雙語內容高度對稱，清楚說明 Google Play（付費買便利/支援開發）與 GitHub Releases（免費 GPLv3）同一 binary 且同一功能承諾（[ADR-0006](../../../docs/adr/0006-distribution-and-monetisation.md)）。
- **商店文案**：`fastlane/metadata/android/` 下的 `en-US` 與 `zh-TW` 說明文案均載明「無網路權限、不代已讀、離線加密儲存」，與產品實作完全相符。
- **安全回報**：[`SECURITY.md`](../../../SECURITY.md) 已填入 GitHub Private Vulnerability Reporting 連結與指定通報信箱（解決 I7）。
- **歷史路徑清理**：前三輪審查歸檔中的本機絕對路徑與 `.omc/` 參照已完成清理替換（解決 M10）。

---

## 發現事項（Findings）

### Critical (Must Fix)
**無。** 本輪審查未發現資料損毀、金鑰洩漏、網路權限逃逸或安全邊界破口等級的缺陷。

---

### Important (Should Fix)

#### 1. `HealthDao.summaryCountSince` 缺少結束時間上限過濾，導致歷史期間統計中的摘要數虛高
- **位置**：
  - [`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:361-362`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L361-L362)
  - [`feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt:102`](../../../feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L102)
- **原因**：
  在 `AnalyticsViewModel.compute` 中，訊息與中斷區間都有同時過濾起訖時間（`messagesBetween(period.startEpochMs, period.endEpochMsInclusive)` 與 `gap.start < endExclusive && gap.end >= start`）。然而摘要計數呼叫的是 `analytics.summaryCountSince(period.startEpochMs)`，對應的 SQL 僅有 `WHERE observedAtEpochMs >= :since`。
  當使用者選取「上個月」（`LAST_MONTH`）或自訂的過去日期區間時，此查詢會把從該時間點一路到「今天現在」的所有摘要通知全部算進去，導致歷史期間回報的 `summaryOnlyCount` 被後續產生的摘要嚴重灌水。
- **具體修正**：
  在 `HealthDao` 新增範圍查詢：
  ```kotlin
  @Query("SELECT COUNT(*) FROM summary_observation WHERE observedAtEpochMs >= :since AND observedAtEpochMs <= :until")
  suspend fun summaryCountBetween(since: Long, until: Long): Int
  ```
  並在 `AnalyticsRepository` 與 `AnalyticsViewModel.kt:102` 傳入 `period.startEpochMs` 與 `period.endEpochMsInclusive`。

#### 2. 中文測試矩陣數據過期，且雙語測試矩陣均漏列新補的 `platform:capture` 與 `platform:backup` 測試
- **位置**：
  - [`docs/zh-Hant/TEST_MATRIX.md:11`](../../../docs/zh-Hant/TEST_MATRIX.md#L11)
  - [`docs/TEST_MATRIX.md:11-18`](../../../docs/TEST_MATRIX.md#L11-L18)
- **原因**：
  1. 英文版 `docs/TEST_MATRIX.md:11` 已校正為 `72 tests in core:* (model 5, parser 10, identity 5, reconcile 20, analytics 32)`；但中文版 `docs/zh-Hant/TEST_MATRIX.md:11` 仍停留在舊版 `core:* 44 個測試（model 5、parser 10、identity 5、reconcile 20、analytics 4）`。
  2. 為了補齊 I4 而新增的 `CaptureCoordinatorTest`（11 個測試）與 `BackupStagerTest`（21 個測試，加上 `HkdfTest` 共 24 個測試）已在 `platform:capture` 與 `platform:backup` 正常運行，全庫 JVM 測試已達 154 題，但在兩份 `TEST_MATRIX.md` 的測試摘要表格中完全沒有列出這兩個模組的單元測試。這使文件呈現的自動化覆蓋率低於真實代碼水準。
- **具體修正**：
  同步更新中文版 `docs/zh-Hant/TEST_MATRIX.md` 第 11 行之數字，並在兩份文件的表格中補充 `platform:capture`（11 tests）與 `platform:backup`（24 tests）的 JVM 測試說明。

---

### Minor (Nice to Have)

#### 1. `docs/SCOPE.md` 部分測試狀態描述與程式碼現況不符
- **位置**：[`docs/SCOPE.md:25-26`](../../../docs/SCOPE.md#L25-L26)
- **原因**：
  - 第 26 行對 Own reminders 依然寫著 `ReminderScheduler.delayUntilNext pure function; no unit test yet`，但 `app/src/test/kotlin/dev/quietinbox/reminders/ReminderSchedulerTest.kt` 早已存在且貢獻 4 個測試。
  - 第 25 行備份測試描述仍為 `BackupService + HKDF RFC vectors; no round-trip instrumented test yet`，未提及新抽出的 `BackupStagerTest` 21 個針對 Staging 限制與格式錯誤的完整測試。
- **具體修正**：
  將第 26 行修正為 `ReminderSchedulerTest covers delayUntilNext (4 JVM tests)`，並在第 25 行補註 `BackupStagerTest covers format/limits (21 JVM tests)`。

#### 2. `AnalyticsViewModel` 在切換期間時未觸發中途載入狀態（Loading State）
- **位置**：[`feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt:77-80`](../../../feature/analytics/AnalyticsViewModel.kt#L77-L80)
- **原因**：
  `state` 係由 `selection` flow 透過 `map { s -> compute(s) }` 衍生。當使用者點擊「全部」（`ALL`）等可能包含數萬則訊息的大區間時，背景掃描與口頭禪提取需要數百毫秒至 1 秒。期間 `state.value` 維持前一個狀態且 `loading == false`。雖然 Compose 不會閃爍或崩潰，但介面無法即時給予載入指示。
- **具體修正**：
  在切換期間前可先發射一個 `loading = true` 的過渡狀態，或在 ViewModel 內明確維護 `isComputing` 狀態。

#### 3. 刪除會話時未一併清理 `notification_checkpoint`（殘留衛生問題）
- **位置**：[`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/InboxRepository.kt:86-97`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/InboxRepository.kt#L86-L97)
- **原因**：
  `deleteConversation` 刪除會話與訊息並寫入 suppression token，但未刪除對應串流的 `notification_checkpoint`。雖然 `IngestRepository.commit` 現在會把失效的 message ID 轉換為 `null` 且不復活會話（I1 已修復），但該列 checkpoint 會一直留存到 14 天後的清理排程。
- **具體修正**：
  可在 `deleteConversation` 交易內加入 `db.checkpointDao().deleteByStreamKey(...)`，保持資料庫狀態乾淨。

#### 4. `ADR-0001` 尚未補述五個 Parser 模組合併為 `:parsers:apps` 的架構決策
- **位置**：[`docs/adr/0001-toolchain-and-module-layout.md`](../../../docs/adr/0001-toolchain-and-module-layout.md)
- **原因**：
  計畫書原始規劃五個獨立 parser 模組，專案實作上整併為單一 `:parsers:apps` 模組，並透過 `AppParser` 的 `final` hook 機制達到安全擴充。這是一項合理的簡化，但 `ADR-0001` 尚未記載此偏離。
- **具體修正**：
  在 `ADR-0001` 的 Decision 與 Consequences 章節補上一段關於整合 `:parsers:apps` 的架構考量。

#### 5. Lint 設定維持 `abortOnError = false`
- **位置**：[`build-logic/src/main/kotlin/quietinbox.android.library.gradle.kts:19-21`](../../../build-logic/src/main/kotlin/quietinbox.android.library.gradle.kts#L19-L21)
- **原因**：
  CI 雖然執行了 `:app:lintDebug`，但 `abortOnError = false` 會導致即便出現 Lint 錯誤也無法阻斷 CI。建議在正式開源發布後建立 baseline 並將重要安全性規則設為 `abortOnError = true`。

---

## 其他觀察（Other Observations）

1. **`MediaCopier` 平行化修復（M3 已銷案）**：
   [`MediaCopier.kt:45-58`](../../../platform/media/src/main/kotlin/dev/quietinbox/platform/media/MediaCopier.kt#L45-L58) 已改用 `coroutineScope { messageIds.map { id -> async { ... } }.awaitAll() }` 搭配 `parallelism.withPermit`，原本有名無實的 `Semaphore(2)` 現在確實具備並行複製能力。
2. **`onRemoved` Tag 長度截斷（M6 已銷案）**：
   [`CaptureCoordinator.kt:220`](../../../platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L220) 已加入 `sbn.tag?.take(Limits.MAX_KEY_CHARS)`，徹底防止長 tag 導致的 streamKey 計算不一致。
3. **單元測試增長顯著**：
   本輪 diff 新增了 `InsightsTest`（28 題）、`BackupStagerTest`（21 題）、`CaptureCoordinatorTest`（11 題），使全庫 JVM 測試總數大幅提升至 154 題，先前測試防護網偏弱的 `platform:backup` 與 `platform:capture` 核心邏輯現在均有扎實的測試保護。
4. **示範資料的擬真度與邊界設計**：
   `DemoDataRepository` 不僅提供了截圖所需的中英會話，還特意塞入了 `AMBIGUOUS_REPEAT` 關聯、修訂歷史、佔位符圖片與隱藏預覽等邊界資料，使整個 App 的誠實標籤設計在示範模式下能被完整展示。

---

### 審查總結
本 repository 在歷經四輪嚴謹的代碼審查與迭代修復後，程式碼品質、安全性、架構約束與開源透明度皆已達到極高水準。只要針對上述 `summaryCountSince` 的時間範圍過濾與 `TEST_MATRIX.md` 的數字做簡單修正，即可安心推進至公開發布階段。
