# 審查報告：Round 12（針對 Round-11 修復之迷你複審）

- **工作目錄**：`/Users/iml1s/Documents/mine/quietinbox-wave2`
- **分支與版本**：分支 `wave2`，檢視範圍 `git diff 825d708..a3fd01b`（HEAD `a3fd01b`）
- **審查模式**：唯讀審查（READ-ONLY），無任何程式碼修改或狀態變更指令

---

## 一、整體判定 (Verdict)

### **APPROVE**（核准通過）

> 本次 commit（`a3fd01b`）完整且精確地修復了 Round-11 所提出的全部 19 項缺失（包括 1 項 Critical、3 項 Important、12 項 Minor 及 agy 提出之 3 項重點）。核心併發鎖階層安全無死鎖、資料流邊界保護嚴密、向下相容性完整、字串雙語對齊無遺漏，所有 196 項 JVM 單元測試皆實測全綠。無阻礙發布之迴歸或安全缺陷。

---

## 二、Round-11 發現項修復驗證對照表

| 編號 | Round-11 原始問題摘要 | 狀態 | 驗證結果與程式碼依據 (File:Line) |
| :--- | :--- | :---: | :--- |
| **agy I-1 / sub Important-2** | `BackupService.writeRecords` 在 Room 交易內進行耗時的媒體解密與磁碟 IO，佔用 SQLite 寫入鎖阻塞即時擷取 | **已修復** | [BackupService.kt:140-209](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L140-L209)：交易內僅以 keyset 分頁收集 `MediaBlobEntity` 中繼資料至 `mediaRows`，交易結束後才在交易外逐一進行 `blobCipher.decryptFile` 與 Base64 寫檔串流。<br>[BackupRecords.kt:102](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupRecords.kt#L102)：`BackupRecord.End` 新增 `skippedMedia: Int = 0`。<br>[BackupService.kt:84](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L84)：維護期間拒絕匯出回傳專屬 `Reason.MAINTENANCE`，配有專屬雙語字串。<br>[BackupRoundTripTest.kt:140-149](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/backup/src/androidTest/kotlin/dev/quietinbox/platform/backup/BackupRoundTripTest.kt#L140-L149)：新增 `exportIsRefusedWhileAnExclusiveMaintenanceRunIsActive` 測試。 |
| **sub Important-2 (b)** | `process()` 在 journal 寫入拋出例外時呼叫 `markJournalRetryable`，但該列從未被寫入，導致事件靜默丟失無缺口 | **已修復** | [CaptureCoordinator.kt:602, 617, 630-640](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L602-L640)：引入 `journaled` 旗標；若在 journal 寫入完成前拋例外，不會做無效的 retryable 更新，而是記錄 `JOURNAL_FAILED` 診斷並呼叫 `health.recordGap(..., GapReason.UNKNOWN, GapPrecision.EXACT, ...)`。<br>[CaptureCoordinatorTest.kt:578-592](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L578-L592)：新增 `a journal insert that throws is recorded as a gap...` 單元測試。 |
| **sub Critical-1** | 冷啟動緩衝溢位在成功載入政策的路徑上靜默丟棄通知，未記錄任何缺口（違反 gaps are shown 紀律） | **已修復** | [CaptureCoordinator.kt:380-388](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L380-L388)：溢位時累加 `heldDropped++`。<br>[CaptureCoordinator.kt:409-417](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L409-L417)：`releaseHeld()` 取出 `dropped`，若 `dropped > 0` 則透過協程記錄 `COLD_START` 有界缺口。<br>[CaptureCoordinator.kt:798](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L798)：`MAX_HELD` 提升至 256。<br>[CaptureCoordinatorTest.kt:552-576](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L552-L576)：新增 300 則通知溢位測試，斷言存活 128 則並記錄缺口。 |
| **sub Important-1** | `coldStart()` 取得鎖時若 `sourcesLoaded == true`，跳過載入亦未呼叫 `releaseHeld()`，使被保留通知永久卡在緩衝 | **已修復** | [CaptureCoordinator.kt:395](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L395)：改為 `if (!sourcesLoaded) guarded { loadSourcePolicy() } else releaseHeld()`。<br>[CaptureCoordinator.kt:380-388](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L380-L388)：將 `coldStartJob` 的檢查與啟動收斂至 `synchronized(held)` 內，杜絕重複啟動競爭。 |
| **sub Important-3** | 抑制 token 主鍵為 `(scopeKey, fingerprint)`，同指紋的多則刪除僅存一筆，IDs 不等時放行導致已刪除訊息復活 | **已修復** | [SuppressionRule.kt:23-27](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/SuppressionRule.kt#L23-L27)：當雙方皆有 source ID 且相同時抑制；若 IDs 不同，則回退至發布時間判斷（`postedAtEpochMs <= tokenPostedAtEpochMs` 視為同一批重播而抑制）。並於 KDoc/CHANGELOG/SCOPE 記錄限制邊界。<br>[SuppressionRuleTest.kt:11-16](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/test/kotlin/dev/quietinbox/platform/storage/repo/SuppressionRuleTest.kt#L11-L16)：新增兩個不同 IDs 回退至 post time 的測試。 |
| **agy M-3** | 金庫鎖定時，新進通知多次觸發 `coldStart()` 造成產生重複碎裂的 `COLD_START` 缺口列 | **已修復** | [CaptureCoordinator.kt:151, 362-364, 439-442](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L151-L442)：`dropHeld()` 以 `coldStartGapId` 保證單次鎖定只開啟一個 open gap；`loadSourcePolicy()` 載入完成後關閉 open gaps 並清空 ID。<br>[CaptureCoordinatorTest.kt:603-614](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L603-L614)：測試驗證第二則通知不會新增缺口列，金庫開啟後正常關閉缺口。 |
| **sub Minor-1** | 被保留通知在釋放時以 release 時間而非 arrival 時間作為 `observedAtEpochMs` | **已修復** | [CaptureCoordinator.kt:420, 427](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L420-L427)：傳入 `h.heldAtEpochMs` 至 `snapshotFactory.create` 與 `enqueue()`，保持時間戳為實際到達時間。 |
| **sub Minor-2** | `Held` 保留 framework `StatusBarNotification` 強引用繞過佇列 bitmap 上限紀律 | **已處理** | [CaptureCoordinator.kt:403-408](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L403-L408)：KDoc 詳述理由（與系統 `getActiveNotifications()` 引用相同，不提早解析 bitmap 佔用記憶體），且上限設定為合理的 `MAX_HELD = 256`。 |
| **agy M-2 / sub Minor-3** | `ListenerAccess.openSettings()` 使用 `resolveActivity`，在 Android 11+ 套件可見性限制下容易誤判 | **已修復** | [ListenerAccess.kt:39-55](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/ListenerAccess.kt#L39-L55)：移除 `resolveActivity` 預檢，改為在 `try-catch (ActivityNotFoundException | SecurityException)` 中直接嘗試啟動 Intent；`settingsIntent()` 簡化為回傳首項。 |
| **sub Minor-4** | 重設失敗的未預期例外會把 Java 類別名直接塞入 UI snackbar | **已修復** | [SettingsViewModel.kt:157](file:///Users/iml1s/Documents/mine/quietinbox-wave2/feature/settings/src/main/kotlin/dev/quietinbox/feature/settings/SettingsViewModel.kt#L157)：包裝為 `unexpected:...`。<br>[SettingsScreen.kt:116](file:///Users/iml1s/Documents/mine/quietinbox-wave2/feature/settings/src/main/kotlin/dev/quietinbox/feature/settings/SettingsScreen.kt#L116)：`else` 分支對應至在地化字串。<br>[strings.xml:281](file:///Users/iml1s/Documents/mine/quietinbox-wave2/core/designsystem/src/main/res/values/strings.xml#L281) & [values-b+zh+Hant/strings.xml:280](file:///Users/iml1s/Documents/mine/quietinbox-wave2/core/designsystem/src/main/res/values-b+zh+Hant/strings.xml#L280)：加入 `delete_everything_step_unexpected`（"未預期的錯誤"）。 |
| **sub Minor-5** | `unviewedCount` 統計未排除無訊息之空會話，導致提醒可能發送空通知 | **已修復** | [Daos.kt:173](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L173)：查詢條件加入 `AND messageCount > 0`。 |
| **sub Minor-6** | `VaultRepositoryTest` 包含同義反覆的斷言 | **已修復** | [VaultRepositoryTest.kt:46](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/test/kotlin/dev/quietinbox/platform/storage/repo/VaultRepositoryTest.kt#L46)：修正為直接明確斷言 `(h.state.value is VaultState.Ready) shouldBe true`。 |
| **sub Minor-7** | `BackupRoundTripTest` 注入全新無關聯的 `VaultMaintenance()`，未真正測到閘門互動 | **已修復** | [BackupRoundTripTest.kt:50, 63-64, 140-149](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/backup/src/androidTest/kotlin/dev/quietinbox/platform/backup/BackupRoundTripTest.kt#L50-L149)：注入共用 `maintenance` 實例，並新增 exclusive 維護期間拒絕匯出之測試。 |
| **sub Minor-8** | Manifest media 計數在部分媒體檔案損壞被跳過時造成混淆 | **已修復** | [BackupRecords.kt:101-102](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupRecords.kt#L101-L102)：`End` 明確標記 `skippedMedia`，KDoc 說明 Manifest 的 media 計數為資料庫總列數，實作以 `End.actual` 為準。 |
| **sub Minor-9** | `VaultMaintenance` KDoc 將備份整體劃入 `work`，忽略還原是 `exclusive` | **已修復** | [VaultMaintenance.kt:34-36](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/VaultMaintenance.kt#L34-L36)：KDoc 修正為註明匯出為 `work`，全金庫寫入（重設、還原匯入）為 `exclusive`。 |
| **sub Minor-10** | `SearchViewModel` 的 `distinctUntilChanged` 以 `va::class == vb::class` 比對，狀態更新被忽略 | **已修復** | [SearchViewModel.kt:60](file:///Users/iml1s/Documents/mine/quietinbox-wave2/feature/search/src/main/kotlin/dev/quietinbox/feature/search/SearchViewModel.kt#L60)：改為精確比對 `va == vb`。 |
| **sub Minor-11** | `SearchRepository` 的 `SearchCursor` 尚未被 UI 分頁使用 | **已處理** | [SearchRepository.kt:15-18](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/SearchRepository.kt#L15-L18)：KDoc 明確補充搜尋畫面目前僅載入第一頁（100 筆），游標為保留架構。 |
| **sub Minor-12** | `reminder_body` 成為無用死字串 | **已修復** | [strings.xml](file:///Users/iml1s/Documents/mine/quietinbox-wave2/core/designsystem/src/main/res/values/strings.xml) 與 [values-b+zh+Hant/strings.xml](file:///Users/iml1s/Documents/mine/quietinbox-wave2/core/designsystem/src/main/res/values-b+zh+Hant/strings.xml)：雙語系皆已移除該字串。 |

---

## 三、關鍵機制與潛在迴歸專題分析

針對 Round-12 Brief 第 18 行特別指定深入檢驗的 8 個潛在風險點，分析如下：

### 1. `synchronized(held)` 內啟動協程 (`scope.launch { coldStart() }`) 是否有鎖持有風險？
- **分析**：
  - `scope.launch` 僅是建立協程物件並加入 Dispatcher 排程佇列，**不會**在當前執行緒上同步執行 lambda 區塊。
  - 鎖層級順序分析：
    - `coldStart()` 執行時會獲取 `pipelineMutex.withLock`，接著內部呼叫 `releaseHeld()` 時才短暫進入 `synchronized(held)`。
    - `hold()` 僅獲取 `synchronized(held)`，期間**絕不**呼叫 `pipelineMutex` 相關操作。
    - 全系統中「兩鎖同時涉及」的持有順序嚴格為 `pipelineMutex` $\rightarrow$ `synchronized(held)`，不存在反向鎖定，**完全無死鎖風險**。

### 2. `releaseHeld()` 從管線鎖內部發起缺口記錄
- **分析**：
  - `releaseHeld()` 透過 `scope.launch { guarded { health.recordGap(...) } }` 非同步排程 DB 寫入。
  - 避免了在持有 `pipelineMutex` 期間執行資料庫磁碟 IO，有效防止後續即時通知被短暫阻塞，設計正確。

### 3. `coldStartGapId` 重設路徑與維護期間邊界情況
- **分析**：
  - 維護結束時（`onMaintenance(false)`）將 `sourcesLoaded = false`，後續當金庫就緒並呼叫 `loadSourcePolicy()` 時，會執行：
    ```kotlin
    val gap = coldStartGapId
    coldStartGapId = null
    if (gap != null) guarded { health.closeOpenGaps(System.currentTimeMillis(), GapReason.COLD_START) }
    ```
  - 若在維護前曾開啟過 `coldStartGapId`，維護結束載入政策時依然會安全關閉 open gap 並清空欄位；即使用戶執行「重設金庫」，新資料庫中雖無此 open gap，但 `closeOpenGaps` 僅發送 UPDATE 0 列，無任何例外或狀態殘留。

### 4. `CaptureCoordinator.process()` 中 `journaled` 旗標與 `ingest.journal` 語意
- **分析**：
  - `ingest.journal(...)` 回傳 `false`：代表重複的事件 ID（例如重覆推播或同一 post 重複事件），程式碼執行 `if (!ingest.journal(...)) return` 直接返回，不會進入 `catch` 區塊，也不會重複遞增 `acceptedCount`。
  - `ingest.journal(...)` 拋出例外：`journaled` 維持 `false`，進入 `catch` 後判定未寫入成功，記錄 `JOURNAL_FAILED` 診斷與 `UNKNOWN` 精確缺口，不再對不存在的 row 執行無效的 `markJournalRetryable`。
  - `ingest.journal(...)` 成功後 commit 拋出例外：`journaled` 為 `true`，標記既有 row 為 retryable，確保後續 replay 可正確重試。

### 5. 備份匯出：`mediaRows` 記憶體佔用與備份相容性
- **分析**：
  - **記憶體佔用**：`mediaRows` 僅保留 `MediaBlobEntity` 中繼資料（id, fileName, mimeType, 寬高等），單一物件在 ART 64-bit 堆疊上約佔 250~300 bytes。即便金庫存有 10,000 個媒體項目，中繼資料總計僅約 2.5~3.0 MB，對現代 Android 裝置極其安全；解密後的實體檔案內容仍在交易外逐一串流寫出，並在迴圈迭代中即時釋放。
  - **Manifest vs End 語意**：Manifest 的 `expected.media` 記錄資料庫中的總列數，若有損壞或過大被略過的媒體，`End.actual.media` 為實際寫入數，`End.skippedMedia` 記錄跳過數。
  - **向下相容性**：`BackupRecord.End` 中的 `skippedMedia: Int = 0` 設有預設值，`BackupStager` 使用 `ignoreUnknownKeys = true` 與 `encodeDefaults = true`，讀取無 `skippedMedia` 欄位的舊版備份檔時可無縫相容。

### 6. 抑制規則回退（`tokenPostedAtEpochMs == null` 但 IDs 不同）
- **分析**：
  - 當候選與 token 的 sourceMessageId 不同，但 token 缺乏發布時間戳時（例如由舊版資料庫移轉或未提供時間戳的通知產生），[SuppressionRule.kt:25](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/SuppressionRule.kt#L25) 的 `tokenPostedAtEpochMs == null || postedAtEpochMs == null -> true` 會回退至保守的抑制行為。
  - 此為已知且在 KDoc、CHANGELOG、SCOPE 明確記錄之設計權衡：保護使用者不因同一會話中刪除同內文訊息而意外讓舊訊息復活。

### 7. 雙語系字串對齊 (EN / zh-Hant)
- **分析**：
  - 經指令針對 `values/strings.xml` 與 `values-b+zh+Hant/strings.xml` 進行全量比對：
    - `strings.xml` 鍵值集合差異：**0**（完全對齊）。
    - `plurals` 鍵值集合差異：**0**（完全對齊；zh-Hant 依 CLDR 規範僅需定義 `other`）。
    - 本次新增的 `backup_failed_maintenance` 與 `delete_everything_step_unexpected` 皆具備精確自然的中英文文案。

### 8. 文件宣稱與程式碼實測計數對照
- **分析**：
  - **JVM 單元測試總數**：實跑 `./gradlew test` 解析 `TEST-*.xml`，實際通過數為 **196**（與 CHANGELOG 宣稱 196 完全一致）。
  - **CaptureCoordinatorTest**：實測 **22** 則測試（與宣稱 22 完全一致）。
  - **SuppressionRuleTest**：實測 **4** 則測試（與宣稱 4 完全一致）。
  - **VaultMaintenanceTest**：實測 **5** 則測試（完全一致）。
  - **VaultRepositoryTest**：實測 **3** 則測試（完全一致）。
  - **BackupRoundTripTest (Instrumented)**：實測 **2** 則測試（完全一致）。
  - **Storage Instrumented 測試**：共 **15** 則（完全一致）。
  - **Crypto Instrumented 測試**：共 **2** 則（完全一致）。

---

## 四、新發現與次要觀察 (New Findings & Observations)

### [Critical]：無
### [Important]：無

### [Minor / 建議防禦項目]

#### 1. `onMaintenance(true)` 可考慮主動將 `coldStartGapId` 置空
- **位置**：[CaptureCoordinator.kt:471-511](file:///Users/iml1s/Documents/mine/quietinbox-wave2/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L471-L511)
- **情境**：若冷啟動時金庫打不開（開啟了一個 `coldStartGapId`），此時使用者恰好在同一進程內觸發金庫維護（如重設或還原）；維護結束後雖然載入政策時會將 `coldStartGapId = null`，但在 `onMaintenance` 開始時顯式將 `coldStartGapId = null` 能讓狀態機更加防禦性。
- **影響評估**：極低邊界情境，且目前邏輯已在 `loadSourcePolicy()` 正確重設，不影響正確性。

#### 2. 模組獨立執行 `lintDebug` 時存在歷史 Warning/Error（不影響 CI）
- **位置**：`:core:designsystem:lintDebug`（`Formatting.kt:31` 之 `NonObservableLocale`）與 `:platform:capture:lintDebug`（`SyntheticNotifications.kt:58` 之 `MissingPermission`）。
- **說明**：CI 工作流程（[ci.yml:51](file:///Users/iml1s/Documents/mine/quietinbox-wave2/.github/workflows/ci.yml#L51)）執行的是 `:app:lintDebug`，在 `:app:lintDebug` 中為 0 errors 通過；上述兩處為非 app 模組之既有項目，建議後續可補上 Compose 的 `LocalLocale.current` 替換與對應的 lint baseline。

---

## 五、審查結論

本次 Commit（`a3fd01b`）執行非常扎實，不僅逐一排除了 Round-11 提出的所有缺陷，並以嚴密的單元測試補齊了邊界情境；針對多執行緒並發、交易鎖安全、備份相容性與國際化字串皆做到了完整防護。

審查判定為 **APPROVE**。
