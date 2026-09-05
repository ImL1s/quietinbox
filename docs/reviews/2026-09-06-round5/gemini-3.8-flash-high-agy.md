# 程式碼審查報告（第五輪 Round 5 Review）— QuietInbox

- **審查標的**：`<repo>`
- **審查範圍**：`git diff 7587c73..fa49902`（branch `main`，HEAD [`fa49902`](file://<repo>)）
- **審查模式**：獨立唯讀（READ-ONLY），未修改、新增或刪除任何儲存庫檔案，未執行破壞性指令，未接觸實體裝置與模擬器
- **驗證執行**：
  - `./gradlew test --console=plain`（JVM 單元測試）：**BUILD SUCCESSFUL**（361 tasks，全數通過）
  - `./gradlew compileReleaseKotlin :app:compileReleaseKotlin`（正式版本編譯驗證）：**BUILD SUCCESSFUL**

---

## 審查結論（Verdict）

### **APPROVE WITH MINOR FIXES**

第四輪（Round 4）提出的所有重要與次要問題均在此 commit 中獲得紮實的修復與測試守護：
1. 備份還原（`BackupService.apply`）的媒體檔案洩漏與虛假計數問題已由 `usedFiles` 與未採用檔案清理徹底解決，落碟預備亦已納入 `try` 清理範圍。
2. 示範資料（`DemoDataRepository`）成功抽換為 `DemoData` 介面並隔離至 `debug` source set，正式發布版本（release）編譯乾淨且二進位產物完全不含示範文案。
3. 統計查詢加上了 `MESSAGE_CAP = 50_000` 與 `debounce(400)` 防護，且期間結束時間（`until`）精確傳入。
4. GitHub Actions 全部釘選 40 碼 SHA，發布工作流權限限縮，版本號提升為 `versionCode = 2`。

目前僅存一項 **Important 發現**：在 `AnalyticsUiState` 與雙語字串資源中已定義了 `capped` 標記與文案，但在 [`AnalyticsScreen.kt`](../../../feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsScreen.kt) 中**漏接了 UI 渲染**，導致超過 5 萬筆訊息時畫面未向使用者揭露截斷狀態，與 `CHANGELOG.md` 及 `SCOPE.md` 的「誠實標籤」承諾存在落差。補上該 UI 橫幅或提示後即可正式發布。

---

## 第四輪修復查驗對照表（Round-4 Fix Verification Table）

| 第四輪項目與來源 | 狀態 | 修改位置 (File:Line) | 實作與驗證結論 |
| :--- | :---: | :--- | :--- |
| **subagent I-1 / Kimi I-1**<br>還原備份時略過訊息之媒體檔洩漏且回報虛假計數 | **Verified** | [`BackupService.kt:215`](../../../platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L215)<br>[`BackupService.kt:304`](../../../platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L304)<br>[`BackupService.kt:337`](../../../platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L337)<br>[`BackupService.kt:340`](../../../platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L340) | 建立 `val usedFiles = HashSet<String>()`，僅在訊息真正 insert 且 blob 成功掛載時記錄；`Counts` 改用 `usedFiles.size`；交易成功後迴圈執行 `for (f in writtenFiles) if (f !in usedFiles) mediaDir.delete(f)` 清除孤兒與去重略過的實體檔案。 |
| **subagent I-2**<br>預備迴圈與 `try` 之間存在無清理保護之空窗 | **Verified** | [`BackupService.kt:220-223`](../../../platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L220-L223) | `now` 與 `retentionMs` 計算移至最前方；解密寫檔的預備迴圈已完整移入 `return try { ... }` 區塊內。預備期間發生異常或協程取消時，`catch` 區塊保證刪除所有已寫檔之 `writtenFiles`。 |
| **subagent I-3 / agy Important 1**<br>`summaryOnlyCount` 無期間結束上限 | **Verified** | [`Daos.kt:366-367`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L366-L367)<br>[`AnalyticsRepository.kt:42`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/AnalyticsRepository.kt#L42)<br>[`AnalyticsViewModel.kt:110`](../../../feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L110) | `HealthDao` 新增 `summaryCountBetween(since, until)` SQL 範圍查詢；`AnalyticsViewModel` 改傳入 `period.endEpochMsInclusive`，歷史月份不再被後續摘要污染。 |
| **subagent I-4**<br>`DemoDataRepository` 進入 release build | **Verified** | [`DemoData.kt:1-22`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/DemoData.kt#L1-L22)<br>[`DemoDataRepository.kt:47`](../../../platform/storage/src/debug/kotlin/dev/quietinbox/platform/storage/repo/DemoDataRepository.kt#L47)<br>[`debug/DemoModule.kt:10-16`](../../../platform/storage/src/debug/kotlin/dev/quietinbox/platform/storage/di/DemoModule.kt#L10-L16)<br>[`release/DemoModule.kt:10-16`](../../../platform/storage/src/release/kotlin/dev/quietinbox/platform/storage/di/DemoModule.kt#L10-L16)<br>[`SettingsViewModel.kt:59`](../../../feature/settings/src/main/kotlin/dev/quietinbox/feature/settings/SettingsViewModel.kt#L59)<br>[`DemoReceiver.kt:37`](../../../app/src/debug/kotlin/dev/quietinbox/debug/DemoReceiver.kt#L37) | `DemoDataRepository`（800 行）移至 `platform/storage/src/debug`；主目錄抽取 `interface DemoData`；Release source set 提供 `NoDemoData`。經由 `compileReleaseKotlin` 驗證編譯通過，release 產物完全隔離。 |
| **subagent I-5**<br>Analytics 無上限全量載入與重算缺乏節流 | **Partially**（邏輯與資料流已修復，UI 呈現漏接） | [`Daos.kt:224-228`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L224-L228)<br>[`AnalyticsRepository.kt:20-27, 47`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/AnalyticsRepository.kt#L20-L27)<br>[`AnalyticsViewModel.kt:84-88, 145`](../../../feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L84-L88)<br>[`strings_analytics.xml:76`](../../../core/designsystem/src/main/res/values/strings_analytics.xml#L76) | SQL 加上 `ORDER BY m.sortKey DESC LIMIT :limit`；Repository 設定 `MESSAGE_CAP = 50_000` 並透過 `asReversed()` 轉為升序；Flow 增加 `distinctUntilChanged()` 與 `debounce(400)`。**但 `AnalyticsScreen.kt` 漏接 `state.capped` 提示**（詳見後述 Important Finding）。 |
| **subagent M-1 / Kimi I-2**<br>`isSelf` 以名稱比對易同名誤判 | **Verified** | [`SnapshotFactory.kt:163-168`](../../../platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/SnapshotFactory.kt#L163-L168) | 優先依序比對 `person.key == self.key` 與 `person.uri == self.uri`，僅在雙方皆無 key 與 uri 時回退至 `selfName`。 |
| **subagent M-2**<br>`Period.custom` 無跨度上限造成資源放大 | **Verified** | [`Insights.kt:94-99`](../../../core/analytics/src/main/kotlin/dev/quietinbox/core/analytics/Insights.kt#L94-L99) | `custom()` 內起始日箝制為 `to.minus(DatePeriod(days = MAX_SPAN_DAYS))`，與 `all()` 行為一致。 |
| **subagent M-3**<br>Ingest 延遲建立與刪除重播無自動化測試 | **Verified** | [`VaultRoundTripTest.kt:158-180`](../../../platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/VaultRoundTripTest.kt#L158-L180) | 新增 `deletedConversationDoesNotResurrectOnReplay`，覆蓋 commit → deleteConversation → 重播相同通知內容 → 驗證 conversationId 為 null 且資料列數為 0。 |
| **subagent M-4**<br>`DemoDataTest` 缺少非 demo 資料存活反向斷言 | **Verified** | [`DemoDataTest.kt:73-78, 113`](../../../platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/DemoDataTest.kt#L73-L78) | `seed()` 前先插入 `com.example.real` 來源，`clear()` 後斷言該來源仍存在，證明清理作用域精確受限。 |
| **subagent M-5**<br>`offerCaptured` 為 `internal` 無限制 | **Verified** | [`CaptureCoordinator.kt:317`](../../../platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L317) | 加上 `@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)`。 |
| **subagent M-6**<br>GitHub Actions 以可變 tag 釘選與過寬權限 | **Verified** | [`.github/workflows/ci.yml:20-86`](../../../.github/workflows/ci.yml#L20-L86)<br>[`.github/workflows/release.yml:21-108`](../../../.github/workflows/release.yml#L21-L108) | 兩份 workflow 中的所有 Action（含 `checkout`、`setup-java`、`upload-google-play` 等）全數釘選 40 碼 SHA 並以註解註記版本；`release.yml` 預設 permissions 限縮為 `contents: read`，僅發布 release job 指定 `contents: write`。 |
| **subagent M-7**<br>`keystore.properties` 缺欄位時 configuration 階段 NPE | **Verified** | [`app/build.gradle.kts:25-36`](../../../app/build.gradle.kts#L25-L36) | 增加 `requireNotNull` 檢查並給出明確錯誤提示（如 `keystore.properties is missing '$name'`）。 |
| **subagent M-8**<br>`MediaCopier.copyPending` 協程扇出未受限且點陣圖非線程安全 | **Verified**（依 brief 部分收斂） | [`MediaCopier.kt:47-60`](../../../platform/media/src/main/kotlin/dev/quietinbox/platform/media/MediaCopier.kt#L47-L60) | 外部先將 `bitmap` 壓縮為不可變之 `ByteArray?` 再分發；`parallelism.withPermit` 移至 `async` 內部最外層，DB 查詢與複製完整受到信號量保護。 |
| **agy Important 2**<br>雙語測試矩陣數據過期與遺漏測試說明 | **Verified** | [`docs/zh-Hant/TEST_MATRIX.md:11, 19-20`](../../../docs/zh-Hant/TEST_MATRIX.md#L11)<br>[`docs/TEST_MATRIX.md:19-20`](../../../docs/TEST_MATRIX.md#L19-L20) | 中文版更新為 72 個 core 測試；兩份文件均補齊 `BackupStagerTest`（21 tests）與 `CaptureCoordinatorTest`（11 tests）。 |
| **agy Minor 1**<br>`docs/SCOPE.md` 測試狀態與現況不符 | **Verified** | [`docs/SCOPE.md:25-26`](../../../docs/SCOPE.md#L25-L26) | 更新為 `ReminderSchedulerTest covers delayUntilNext (4 JVM tests)` 與 `BackupStagerTest covers format and limits (21 JVM tests)`。 |
| **agy Minor 4**<br>`ADR-0001` 缺少單一 `:parsers:apps` 決策紀錄 | **Verified** | [`docs/adr/0001-toolchain-and-module-layout.md:31-40`](../../../docs/adr/0001-toolchain-and-module-layout.md#L31-L40) | 新增 `Addendum (2026-09-06): one :parsers:apps module instead of five` 完整論述架構考量。 |
| **Kimi Minor**<br>商店說明文案保存期限 7–365 天與滑桿 1–365 不符 | **Verified** | [`en-US/full_description.txt:16`](../../../fastlane/metadata/android/en-US/full_description.txt#L16)<br>[`zh-TW/full_description.txt:16`](../../../fastlane/metadata/android/zh-TW/full_description.txt#L16)<br>[`changelogs/2.txt`](../../../fastlane/metadata/android/zh-TW/changelogs/2.txt) | 中英兩版商店說明均改為 1–365 天；Fastlane changelogs 更名為 `2.txt`，與 `versionCode = 2` 同步。 |

---

## 六大專項深度查驗與回歸分析

### 1. `BackupService.apply` 媒體清理與計數
- **`try` 區塊範圍擴充**：
  原本位於迴圈與 `try` 之間的 `settings.current()` 與時間計算已移至 [`BackupService.kt:220-221`](../../../platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L220-L221)，在任何實體檔案寫入前就已完成。隨後的 `for (media in s.media)` 完整進入 `return try { ... }` 內。若在解密寫檔過程中發生任何 I/O 異常或協程取消，均會跳入 `catch (e: Exception)` 統一執行 `for (f in writtenFiles) mediaDir.delete(f)`，徹底消除空窗。
- **`usedFiles` 精確記帳**：
  在 `s.messages` 迴圈中，重複訊息（`:278` 命中 `preExisting`）與孤兒訊息（`:272` `cid == null`）會提早 `continue`。只有在訊息順利插入資料庫（`:290`）且對應 blob 成功掛載（`:303`）時，才會執行 `usedFiles += blob.fileName`。
- **未採用檔案清理與計數誠實性**：
  交易結束後執行 `for (f in writtenFiles) if (f !in usedFiles) mediaDir.delete(f)`。所有因去重或孤兒而略過的加密檔案立即被物理刪除，不留存於磁碟；最後回傳之 `Counts(..., usedFiles.size)` 回報的是實際掛載的媒體數量，同機還原不再回報膨脹數字。

### 2. `DemoData` 介面與 Hilt 模組隔離
- **編譯期產物徹底隔離**：
  [`DemoDataRepository.kt`](../../../platform/storage/src/debug/kotlin/dev/quietinbox/platform/storage/repo/DemoDataRepository.kt) 現已搬移至 `platform/storage/src/debug/` source set。主代碼中僅保留純介面 [`DemoData`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/DemoData.kt) 與空實作 `NoDemoData`。
- **Hilt 繫結與重複繫結（Duplicate Binding）風險評估**：
  - `src/debug` 下的 `DemoModule` 提供 `@Binds abstract fun bindDemoData(impl: DemoDataRepository): DemoData`。
  - `src/release` 下的 `DemoModule` 提供 `@Provides fun demoData(): DemoData = NoDemoData`。
  - 在 AGP 中，`debug` 與 `release` 為互斥的 variant source set，Gradle 在建置某一 variant 時**絕不會**同時編譯另一個 source set。經執行 `./gradlew compileReleaseKotlin` 與 `:app:compileDebugKotlin`，兩者皆獨立編譯成功，無重複繫結問題。
- **`SettingsViewModel` 與 `DemoReceiver` 注入**：
  - `SettingsViewModel` 建構子參數已改為注入 `DemoData`，在 release 下自動注入 `NoDemoData`。
  - `DemoReceiver` 位於 `app/src/debug/`，其 EntryPoint `demoDataRepository(): DemoData` 亦正確取得 Debug 下的 `DemoDataRepository`。

### 3. `statsBetween` 查詢、上限截斷與重算節流
- **排序與上限**：
  [`Daos.kt:224-228`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L224-L228) 增加了 `ORDER BY m.sortKey DESC LIMIT :limit`。當期間內訊息超過 50,000 筆時，資料庫會取出**最新的 50,000 筆**；在 [`AnalyticsRepository.kt:20`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/AnalyticsRepository.kt#L20) 呼叫 `.asReversed()` 將其轉回時間升序（由舊至新），使下游的熱力圖與趨勢計算維持時序正確。
- **重算節流機制**：
  [`AnalyticsViewModel.kt:84-88`](../../../feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L84-L88) 的 `combine(selection, inbox.observeCounts().catch { }.distinctUntilChanged())` 搭配 `.debounce(400)`。當通知在短時間內爆量湧入時，計數雖然更新，但重算最多每 400 毫秒才觸發一次，避免在後台密集對數萬筆訊息進行 CJK n-gram 與表情符號掃描。
- **首筆發射與切換選取評估**：
  - 首筆發射受 `debounce(400)` 影響，開啟畫面時固定會有約 400ms 的緩衝期，期間呈現 `LoadingScreen()`（見後述 Minor Finding）。
  - 當使用者切換期間（如 7 天切至上月），`selection` 發射新值，`combine` 立即觸發，經 400ms 防抖後正確重新計算新區間數據。

### 4. `SnapshotFactory.isSelf` 判定優先級枚舉
針對 `self`（`MessagingStyle.user`）與 `person`（訊息發送者 `Message.person`），在 [`SnapshotFactory.kt:163-168`](../../../platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/SnapshotFactory.kt#L163-L168) 的判定分支如下：
1. **`person == null`**：
   根據 Android 官方 `MessagingStyle` 契約，無 sender 即為本機使用者發送，回傳 `true`（正確）。
2. **`self?.key != null || person.key != null`**：
   - 雙方皆有 key：比對 `person.key == self.key`，一致為 `true`，不一致為 `false`。
   - 僅一方有 key：`person.key == self.key` 必為 `false`。**真實聯絡人即便與使用者同名，只要聯絡人帶有自身 key，絕不會被誤判為 self**。
3. **雙方無 key，但 `self?.uri != null || person.uri != null`**：
   - 比對 `person.uri == self.uri`。僅一方有 uri 或 uri 不同時判定為 `false`。
4. **雙方皆無 key 且皆無 uri（回退至名稱比對 `else`）**：
   - 只有在通訊軟體極端陽春、未設置任何 key/uri 時，才會執行 `selfName != null && person.name?.toString() == selfName`。
   - 本機使用者發送且 App 僅提供名稱時，`isSelf` 判定為 `true`（正確識別）。

### 5. `Period.custom` 箝制、`MediaCopier` 與真機回歸測試
- **`Period.custom` 跨度防護**：
  [`Insights.kt:94-99`](../../../core/analytics/src/main/kotlin/dev/quietinbox/core/analytics/Insights.kt#L94-L99) 將自訂日期的起始點以 `maxOf(from, to.minus(DatePeriod(days = MAX_SPAN_DAYS)))` 箝制，與 `all()` 的 3,660 天上限一致，避免過長區間導致 `days()` 建立巨量清單。
- **`MediaCopier` 點陣圖單次壓縮與信號量包覆**：
  [`MediaCopier.kt:47-50`](../../../platform/media/src/main/kotlin/dev/quietinbox/platform/media/MediaCopier.kt#L47-L50) 在啟動異步任務前，先在呼叫端協程將 `bitmap` 透過 `ByteArrayOutputStream` 壓縮為不可變的 `ByteArray?`，避免多線程同時對非 thread-safe 的 `Bitmap` 呼叫 `compress`；`parallelism.withPermit` 移至外層將 `db.messageDao().get(id)` 一併包覆，嚴格限制並發 I/O。
- **真機回歸測試（Instrumented Tests）代碼檢核**：
  - [`VaultRoundTripTest.kt:158-180`](../../../platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/VaultRoundTripTest.kt#L158-L180)：測試先提交對話、刪除對話，再以同一 snapshot 重播，斷言 `findConversationId` 為 null 且對話筆數為 0，完整鎖定 lazy conversation 回歸情境。
  - [`DemoDataTest.kt:73-78, 113`](../../../platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/DemoDataTest.kt#L73-L78)：測試在 `seed` 前先寫入真實來源 `com.example.real`，`clear()` 後斷言真實來源仍在，證實清理條件不會殃及池魚。測試邏輯清晰完整，無空斷言。

### 6. 供應鏈安全、權限限縮與文件誠實性
- **GitHub Action SHA 釘選**：
  `ci.yml` 與 `release.yml` 內共 17 處 action 引用全數釘選為 40 碼 commit SHA（含 `r0adkll/upload-google-play@e738b9d... # v1.1.5`），防範上游 tag 劫持。
- **權限最小化**：
  `release.yml` 頂層權限降為 `contents: read`，僅需要建立 release 的 `github-release` job 獨立宣告 `contents: write`。
- **發布中繼資料**：
  `app/build.gradle.kts` 的 `versionCode` 提升至 2，Fastlane changelogs 亦重命名為 `2.txt`，Play Store 說明文案的保存期限修正為 1–365 天。

---

## 新發現事項（New Findings）

### Critical
**無。** 本輪無任何資料外洩、死鎖、崩潰或安全邊界破口。

---

### Important

#### 1. `AnalyticsScreen` 未渲染 `state.capped` 提示，誠實標籤承諾存在 UI 缺口
- **位置**：
  - [`feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsScreen.kt`](../../../feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsScreen.kt)
  - 對照 [`AnalyticsViewModel.kt:64, 145`](../../../feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L64)
  - 對照 [`strings_analytics.xml:76`](../../../core/designsystem/src/main/res/values/strings_analytics.xml#L76)
  - 對照 [`CHANGELOG.md:16`](../../../CHANGELOG.md#L16) 及 [`docs/SCOPE.md:21`](../../../docs/SCOPE.md#L21)
- **問題分析**：
  本 commit 建立了完整的上限機制：`AnalyticsRepository.MESSAGE_CAP = 50_000`、`AnalyticsUiState.capped: Boolean`，並在中英字串中定義了 `analytics_capped`（*「這段期間超過 %1$d 則觀測訊息；只統計最新的 %1$d 則。」*）。同時在 `CHANGELOG.md` 與 `docs/SCOPE.md` 明文承諾：
  > *"at most 50,000 messages per period are loaded (the UI says so when capped)"*
  
  然而檢視 [`AnalyticsScreen.kt`](../../../feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsScreen.kt) 全文，**沒有任何一行代碼讀取 `state.capped` 或使用 `R.string.analytics_capped`**。當使用者的訊息量超過 50,000 筆時，畫面上完全不會出現被截斷的提示，造成文件承諾與實際 UI 行為不符。
- **建議修法**：
  在 `AnalyticsScreen.kt` 的 TopAppBar subtitle 或 `LazyColumn` 頂部加入 banner/卡片：
  ```kotlin
  if (state.capped) {
      Text(
          text = stringResource(R.string.analytics_capped, AnalyticsRepository.MESSAGE_CAP),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
      )
  }
  ```

---

### Minor

#### 1. `AnalyticsViewModel` 的 `debounce(400)` 造成首筆資料載入延遲與選取回饋遲緩
- **位置**：
  - [`feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt:84-88`](../../../feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt#L84-L88)
  - [`feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsScreen.kt:129`](../../../feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsScreen.kt#L129)
- **問題分析**：
  Kotlin Flow 的 `.debounce(400)` 會無差別延遲所有發射：
  1. 使用者剛進入統計頁面時，即便初始查詢極快，首筆 emission 必定被硬性延遲 400ms，期間使用者只能看著 `LoadingScreen()`。
  2. `PeriodRow(selected = state.selection.kind)` 綁定的是 `state`。當使用者點擊其他期間 Chip 時，因需要經過 400ms 的 debounce 才會發射新 `state`，Chip 的視覺選中高光有 400ms 的明顯延遲，缺乏即時觸控回饋。
- **建議修法**：
  可將 `PeriodRow` 的選中狀態在 UI 層使用 `remember` 的臨時 state 立即反應，或改為針對通知計數 Flow 單獨 debounce，而使用者的點擊操作（`selection`）給予立即響應。

#### 2. `BackupService.apply` 交易提交後若協程被取消，`catch` 仍可能誤刪已掛載之媒體檔
- **位置**：[`platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:338-348`](../../../platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L338-L348)
- **問題分析**：
  在 `db.withTransaction { ... }` 提交成功後，若協程在執行 `:340` 清理或準備回傳 `BackupResult.Ok` 的微小空窗中被取消（例如使用者此時退出設定頁），協程會拋出 `CancellationException` 進入 `:342` 的 `catch` 區塊。此時 `:345` 會執行 `for (f in writtenFiles) mediaDir.delete(f)`，把剛提交到資料庫的 `usedFiles` 一併刪除，留下指向不存在檔案的 `MediaBlobEntity`。
- **建議修法**：
  維護一個 `var committed = false` 旗標；在 `withTransaction` 結束後設為 `committed = true`。若進入 `catch` 且 `committed == true`，僅刪除 `f !in usedFiles`，避免誤刪已提交檔案。

#### 3. `SnapshotFactory.isSelf` 在使用者與聯絡人皆為空字串時的邊界判定
- **位置**：[`platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/SnapshotFactory.kt:167`](../../../platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/SnapshotFactory.kt#L167)
- **問題分析**：
  在雙方皆無 key、無 uri 時，若 `selfName` 為 `""`（空字串）且群組內聯絡人名稱亦為 `""`，`person.name?.toString() == selfName` 會評估為 `true`。
- **建議修法**：
  回退至名稱比對時可加入非空判定：`!selfName.isNullOrBlank() && person.name?.toString() == selfName`。

---

### 總結

本 commit 展現了極高的修復品質，前四輪積累的重大架構問題（Lazy conversation 測試保護、備份媒體檔案洩漏、Release APK 示範代碼隔離、CI 供應鏈安全）均已全數收斂到位。只要將 `AnalyticsScreen` 漏接的 `state.capped` 提示補齊，即可安心推進至正式上線。
