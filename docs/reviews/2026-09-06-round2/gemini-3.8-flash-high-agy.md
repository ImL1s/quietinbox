# QuietInbox／靜讀 v0.1 垂直切片 第二輪代碼審查報告（Round 2 Review）

- **審查對象**：Commit [`8050e05`](file://<repo>)（`fix: address review round 1 (Gemini 3.8 Flash, Claude subagent, Fable) — pipeline, dedup, vault, backup, lock`）及其相對 `3ef8fb8` 之變更（37 個檔案，+1883 / -215）在全專案語境下的實作。
- **審查基準**：規格書 `QuietInbox_開源專案完整計劃.md`（§2, §5–§9, §11–§12）、九大硬性產品規則、第一輪三份審查報告（`docs/reviews/2026-09-06-round1/gemini-3.8-flash-high.md`、`dual-review-subagent.md`、`dual-review-fable.md`）。
- **驗證執行**：在唯讀與非破壞約束下，執行 `:core:reconcile:test :core:model:test`（全部通過，含 18 個單元測試與 1,000 次反覆運算之 Property-based 測試）；雙語字串 Parity 檢驗（298/298 100% 吻合）；Schema v1 與 v2 遷移定義比對。

---

## 一、審查結論（Verdict）

**APPROVE WITH MINOR FIXES（建議通過，附帶少許非阻擋性改進建議）**

Commit `8050e05` 極為全面且高品質地修復了第一輪審查中由 Gemini、Claude Subagent、Claude Fable 指出的**全部 Critical 與 Important 級別問題**，並解決了多項 Minor 邊界缺陷。

特別是在最具核心風險的兩個部分：
1. **`Reconciler` 全視窗對齊重寫**：徹底修正了先排除 ID 訊息導致的索引漂移與對齊失效，新引入的 `WINDOW_KEPT` 語意與 `samePost` 重連識別完美符合計劃 §7.2 規範，且保留了同視窗重複訊息之 Multiplicity。
2. **資料庫 v1 $\to$ v2 遷移與刪除防復活**：以 `scopeKey`（`SourceScope.key + "#" + identityKey`）重構抑制資料表，並於 `MIGRATION_1_2` 中透過 `JOIN conversation` 完整將舊有權杖轉換遷移，杜絕了第一輪工作區曾出現的「直接 DROP 清空權杖」與「刪除對話後通知重播立即復活」之重大漏洞。
3. **金庫生命週期與連線安全**：`DatabaseHolder` 增加 CoroutineExceptionHandler 與 `Throwable` 捕捉，確保 native link error 與 migration 失敗皆收斂為 `VaultState.Locked`，呼叫端 `db()` 不再掛起，`flowWithDb` 具備自動重連恢復機制。

目前程式碼在庫內無任何 Critical 或 Important 級別的新缺陷，達到可安全發布的工程品質。

---

## 二、第一輪審查問題驗證總表（Round-1 Fix Verification Table）

以下追蹤第一輪三份報告（Gemini `agy`、Claude `subagent`、Claude `fable`）提出之各項發現於 commit `8050e05` 中的落實狀況：

| 項次 | 原始報告與編號 | 問題描述 | 驗證結果 | 程式碼修復位置與驗證依據 |
| :--- | :--- | :--- | :---: | :--- |
| 1 | agy-C1 / fable-I1 / sub-C2 | `DatabaseHolder.db()` 在 Opening $\to$ Locked 永久掛起 | **Verified Fixed** | [`DatabaseHolder.kt:60-64`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/DatabaseHolder.kt#L60-L64)：改用 `_state.first { it !is VaultState.Opening }`，遇 `Locked` 立即拋出 `VaultUnavailableException`。 |
| 2 | agy-C2 / fable-C3 / sub-C4 | 備份還原媒體被 RetentionWorker 當孤兒清理（`messageId == null`） | **Verified Fixed** | [`BackupService.kt:313-328`](../../../platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L313-L328)：先插入訊息取得 `newId`，媒體 blob 寫入時直接綁定 `messageId = newId`，隨後以 `setMedia` 關聯。[`Daos.kt:258`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L258) 孤兒查詢亦改用 LEFT JOIN。 |
| 3 | agy-C3 / fable-C1 / sub-C3 | 刪除整個會話後，活動通知重播會使已刪內容復活 | **Verified Fixed** | [`Entities.kt:196`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Entities.kt#L196)、[`InboxRepository.kt:65-98`](../../../platform/storage/repo/InboxRepository.kt#L65-L98)、[`IngestRepository.kt:189, 214`](../../../platform/storage/repo/IngestRepository.kt#L189)：抑制表主鍵改為穩定 `scopeKey`（`SourceScope.key + "#" + identityKey`），不依賴自增代理鍵。 |
| 4 | agy-I4 / fable-M1 / sub-C-新3 | `Reconciler` 混合 ID 與無 ID 訊息時對齊錯位 | **Verified Fixed** | [`Reconciler.kt:124-173`](../../../core/reconcile/src/main/kotlin/dev/quietinbox/core/reconcile/Reconciler.kt#L124-L173)：全視窗統一進行字串指紋前綴後綴對齊，ID 僅作為單項覆寫判斷。 |
| 5 | agy-I5 / sub-C-新3 | Stale Replay 舊通知導致 Checkpoint 視窗倒退縮小 | **Verified Fixed** | [`Reconciler.kt:177-181`](../../../core/reconcile/src/main/kotlin/dev/quietinbox/core/reconcile/Reconciler.kt#L177-L181)：當 `addsNothing && prevItems.size > fps.size` 時觸發 `WINDOW_KEPT`，保留較長的上一視窗。 |
| 6 | agy-I6 / fable-C2 / sub-C1 | `setPaused(true)` 未輪換 generation，排隊事件仍落盤 | **Verified Fixed** | [`CaptureCoordinator.kt:228-251`](../../../platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L228-L251)：暫停時 `activeGeneration = null`，恢復時重新生成 UUID；`process()` 二次驗證 `paused || item.generation != activeGeneration`。 |
| 7 | sub-C5 / fable-M8 | 備份匯入以 fingerprint 去重靜默丟失合法重複訊息 | **Verified Fixed** | [`BackupService.kt:288-294`](../../../platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L288-L294)：預先提取匯入前既有資料之 `fingerprint|sortKey|observedAtEpochMs`，僅比對既有資料，備份內的多重同值項目完整保留。 |
| 8 | sub-C6 / fable-I5 | `replayJournal` 與線上消費者競態造成重複入庫 | **Verified Fixed** | [`CaptureCoordinator.kt:122, 335, 411`](../../../platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L122)：引入 `pipelineMutex` 序列化兩者，且 replay 於持鎖內再次檢查 `isJournalPending(eventId)`。 |
| 9 | sub-C7 | ObservationLink 外鍵衝突引致整批 Transaction 回滾 | **Verified Fixed** | [`IngestRepository.kt:272, 283`](../../../platform/storage/repo/IngestRepository.kt#L272)：插入 Link 前先驗證 `db.messageDao().get(it) != null`；新增 `markJournalRetryable` 最多重試 3 次。 |
| 10 | sub-C-新1 | `System.loadLibrary` 拋出 `Error` 導致行程崩潰 | **Verified Fixed** | [`DatabaseHolder.kt:48-52, 107`](../../../platform/storage/db/DatabaseHolder.kt#L48-L52)：`scope` 注入 `CoroutineExceptionHandler`，且 `try-catch` 明確捕捉 `Throwable`，崩潰安全降級為 `VaultState.Locked`。 |
| 11 | sub-C-新2 | `MIGRATION_1_2` 直接 DROP TABLE 丟棄使用者既有抑制權杖 | **Verified Fixed** | [`QuietInboxDatabase.kt:57-78`](../../../platform/storage/db/QuietInboxDatabase.kt#L57-L78)：採用 `RENAME` 搭配 `INSERT ... SELECT ... JOIN conversation` 重新依規則換算 `scopeKey`，無權杖遺失。 |
| 12 | fable-I2 | 行程冷啟動在 `enabledPackages` 載入前靜默丟失事件 | **Verified Fixed** | [`CaptureCoordinator.kt:291-294, 337-342`](../../../platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L291-L294)：引入 `sourcesLoaded` 旗標；冷啟動未完成前不於 `offer()` 丟棄，延後至 `process()` 於持鎖下完成清單查詢與過濾。 |
| 13 | fable-I3 | 重連 Resync 誤將單則通知重複記為 `AMBIGUOUS_REPEAT` | **Verified Fixed** | [`CaptureCoordinator.kt:179-188`](../../../platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L179-L188)：關閉視窗與 Resync 合併至單一協程保證順序；`Reconciler.kt:140` 及 `CheckpointEntity` 納入 `postedAtEpochMs` 識別為 REPOST。 |
| 14 | fable-I4 | Commit 層 checkpoint-loss guard 壓制同一視窗內同值 Multiplicity | **Verified Fixed** | [`IngestRepository.kt:195-202`](../../../platform/storage/repo/IngestRepository.kt#L195-L202)：`preExisting` 僅在批次迴圈開始前快照既有資料庫，批次內剛插入的列不加入該集合，同視窗多則相同訊息得以保留。 |
| 15 | fable-I6 | UI 鎖冷啟動 bypass（設定非同步載入） | **Verified Fixed** | [`LockController.kt:32, 49`](../../../app/src/main/kotlin/dev/quietinbox/ui/LockController.kt#L32)：`_locked` 初始值為 `null`，[`QuietInboxApp.kt:90`](../../../app/src/main/kotlin/dev/quietinbox/ui/QuietInboxApp.kt#L90) 在 `locked == null` 時強制停留 `LoadingScreen`，設定發射後依啟用狀態鎖定。 |
| 16 | fable-I7 | 搜尋無法匹配 $\ge 4$ 字母拉丁子字串與單一 CJK 字元 | **Verified Fixed** | [`Normalization.kt:31-43, 70-80`](../../../core/model/Normalization.kt#L31-L43)：`queryTokens` 分流處理，拉丁 $\ge 3$ 字元純產 3-gram；索引端納入 CJK 單字元與 Bigram。 |
| 17 | fable-I8 / sub-I6 | `WrappedSecretFile` 無 fsync 且 fallback 非原子 | **Verified Fixed** | [`WrappedSecretFile.kt:67-76`](../../../platform/crypto/WrappedSecretFile.kt#L67-L76)：以 `FileOutputStream` 寫入後呼叫 `fd.sync()`，rename 後對目錄進行 fsync，移除就地覆寫 fallback。 |
| 18 | fable-I9 / sub-I3 / agy-M1 | 備份匯出宣告交易卻無 Transaction 包裹 | **Verified Fixed** | [`BackupService.kt:102-111`](../../../platform/backup/BackupService.kt#L102-L111)：五個 DAO 查詢包裹於 `db.withTransaction { Snapshot(...) }` 中完成。 |
| 19 | fable-I10 / sub-I4 | 備份還原為 $O(N^2)$ 全表掃描 | **Verified Fixed** | [`BackupService.kt:289-291`](../../../platform/backup/BackupService.kt#L289-L291)：按會話維護 `HashSet` 快取，時間複雜度降為 $O(N)$。 |
| 20 | fable-I11 / sub-I1 | 金庫 Locked 期間遺漏資料無 Gap 紀錄 | **Verified Fixed** | [`CaptureCoordinator.kt:350-354`](../../../platform/capture/CaptureCoordinator.kt#L350-L354)：引入 `vaultGapOpen` 旗標，捕捉 `VaultUnavailableException` 時開闢 Gap，金庫 Ready 時關閉。 |
| 21 | sub-I2 | 金庫鎖定造成 UI Flow 永久終止 | **Verified Fixed** | [`DatabaseHolder.kt:74`](../../../platform/storage/db/DatabaseHolder.kt#L74)：`flowWithDb` 改用 `_state.flatMapLatest`，金庫重新 Ready 時自動重掛 Flow。 |
| 22 | sub-I5 / fable-M5 | 佇列持有過多未限制 Bitmap 導致 OOM | **Verified Fixed** | [`SnapshotFactory.kt:39, 89`](../../../platform/capture/SnapshotFactory.kt#L39)：限制單張 Bitmap $\le 4\text{ MB}$；[`CaptureCoordinator.kt:125, 303`](../../../platform/capture/CaptureCoordinator.kt#L125) 以 AtomicInteger 限制在佇列中最多 8 張 Bitmap，消費者迴圈外層具備 `Throwable` 自我重啟。 |
| 23 | fable-M4 | MediaCopier 吞掉 `CancellationException` | **Verified Fixed** | [`MediaCopier.kt:81`](../../../platform/media/MediaCopier.kt#L81)：明確 `catch (e: CancellationException) { throw e }` 重新拋出。 |
| 24 | fable-M6 | KEK 遺失被誤報為 `Tampered` | **Verified Fixed** | [`KeystoreWrapper.kt:56`](../../../platform/crypto/KeystoreWrapper.kt#L56)：解密時呼叫 `existingKey() ?: throw KeyPermanentlyInvalidatedException()`，不再隱式新建金鑰。 |
| 25 | fable-M7 / sub-I9 | `closeOpenGap` 僅關閉最新一筆 Gap | **Verified Fixed** | [`HealthRepository.kt:56`](../../../platform/storage/repo/HealthRepository.kt#L56)：改為 `closeOpenGaps(now, vararg reasons)` 精確關閉指定原因的 Gap。 |
| 26 | sub-I10 | 備份讀行未限制記憶體長度 | **Verified Fixed** | [`BackupService.kt:247-257`](../../../platform/backup/BackupService.kt#L247-L257)：實作 `readBoundedLine`，逐字元讀取並於超過限制時立即中止。 |
| 27 | sub-I12 | 備份 JSON 解析錯誤歸類為 IO | **Verified Fixed** | [`BackupService.kt:171`](../../../platform/backup/BackupService.kt#L171)：捕捉 `SerializationException` 並映射為 `Reason.CORRUPT`。 |
| 28 | sub-C-新4 / fable-M3 | 匯出失敗清理時清空使用者指定檔案 | **Verified Fixed** | [`BackupService.kt:88`](../../../platform/backup/BackupService.kt#L88)：不再呼叫 `delete`，以 `"wt"` truncate 清空，並於雙語資源中明確向使用者告知覆寫檔案無效。 |
| 29 | fable-M10 | Snapshot 建立失敗靜默忽略 | **Verified Fixed** | [`CaptureCoordinator.kt:297`](../../../platform/capture/CaptureCoordinator.kt#L297)：累計 `captureErrors` 並記錄 `lastError`。 |
| 30 | fable-M11 | `deleteConversation` 失敗仍觸發 `onDone` | **Verified Fixed** | [`ConversationViewModel.kt:96`](../../../feature/conversation/ConversationViewModel.kt#L96)：驗證 `isSuccess` 後才執行 `onDone()`。 |
| 31 | agy-M2 | Android 12 (API 31/32) `Bundle.getParcelable` 異常 | **Verified Fixed** | [`SnapshotFactory.kt:167`](../../../platform/capture/SnapshotFactory.kt#L167)：改用 `BundleCompat.getParcelable`。 |
| 32 | agy-M3 | `currentWindowAdaptiveInfo` 廢棄警告 | **Verified Fixed** | [`MainNavigation.kt:76`](../../../app/src/main/kotlin/dev/quietinbox/ui/MainNavigation.kt#L76)：切換至 `currentWindowAdaptiveInfoV2()`。 |
| 33 | sub-M1 / fable-M2 | CI 遺漏 `:app:testDebugUnitTest` | **Verified Fixed** | [`.github/workflows/ci.yml:31`](../../../.github/workflows/ci.yml#L31)：加入測試指令。 |
| 34 | sub-M3 / fable-M12 | `localeFilters` 包含無效之 `zh-rTW` | **Verified Fixed** | [`app/build.gradle.kts:40`](../../../app/build.gradle.kts#L40)：已移除。 |

---

## 三、Reconciler.kt（計劃 §7.2）深入專項審查

在 Round 1 審查後，`Reconciler.kt` 進行了演算法換代（改為「先對完整視窗進行指紋位置對齊，再以 `sourceMessageId` 覆寫判定」）。針對此重大改動進行逐行分析與規格對照：

### 1. 規格 §7.2 六大字面案例驗證
在 [`ReconcilerTest.kt`](../../../core/reconcile/src/test/kotlin/dev/quietinbox/core/reconcile/ReconcilerTest.kt) 中逐項對照規格書 §7.2 要求：
1. **滑動視窗累加**：`[A] -> [A,B] -> [A,B,C]` 產出決策恰好為 A、B、C 各入庫一次，後續重送識別為 `KnownKind.REPOST`。（L32）
2. **無歷史視窗批量到達**：首次接收 `[A,B,C]`，三個項目皆判定為 `Decision.New`，附帶 `NO_PREVIOUS_WINDOW` 標記。（L46）
3. **視窗滑動推進**：`[A,B,C] -> [B,C,D]`，B、C 正確對齊為 `Decision.Known`，D 判定為 `Decision.New`，既有資料庫 A 不受影響。（L52）
4. **具備 ID 之同值訊息**：`[好(id=1), 好(id=2)]` 產出兩則 `Decision.New(confirmedById = true)`，不會因文字相同而塌陷。（L58）
5. **單則無 ID 同值訊息之模糊觀測**：`[好(?)] -> [好(?)]` 在新發布（或關閉後的通知）情境下判定為 `Decision.AmbiguousRepeat`，妥善儲存、建立關聯，絕不靜默丟棄亦不視為確認新訊息。（L64）
6. **同通知重發不視為歧義**：相同通知 ID 下重複發布單則相同文字，判定為 `Decision.Known(REPOST)` 而非歧義。（L73）

### 2. 演算法細節與邊界分析
- **全視窗對齊消除索引漂移**：
  舊版實作先過濾無 ID 項目形成子序列比對，導致含有 ID 的訊息穿插其間時下標錯位。新版實作（L125）以 `prevFps` 與 `fps` 完整序列進行 `suffixPrefixOverlap`，確保每個項目的絕對拓撲位置精準對應。
- **Stale Replay 與 `WINDOW_KEPT` 守護機制**：
  若來源應用重發縮小或歷史通知（如已達 `[A,B,C]`，隨後收到舊通知 `[A]`）：
  新版於 L129 執行 `containedAt`，檢測到 `stale = true`，判定為 `KnownKind.STALE_WINDOW`。
  接著於 L177 檢查 `addsNothing && prevItems.size > fps.size`，若本次未引入任何新訊息，則觸發 `ReconcileNote.WINDOW_KEPT`，**將 Checkpoint 保留為原有的長視窗 `[A,B,C]`**（並將 `decisionIndex` 清除為 null）。隨後下一筆 `[B,C,D]` 到達時，仍可完美與保留的視窗對齊，徹底根絕了舊版會將 Checkpoint 倒退為 `[A]` 導致 B、C 被重複插入的嚴重缺陷。
- **`decisionIndex` 代理映射保證 Checkpoint 外鍵正確性**：
  新版於 `WindowItem` 中攜帶 `decisionIndex: Int?`。在 `IngestRepository.commit`（L309）中，只有當期批次新插入的項目透過 `storedIds[it]` 賦予新 ID，從舊視窗保留過來的項目則直接延用原 `item.messageId`。此設計徹底取代了舊版脆弱的下標偏移算術 `storedIds[decisions.size - newWindow.items.size + i]`，完全杜絕了 `IndexOutOfBoundsException` 與 ID 錯置風險。
- **重連 Active Resync 識別**：
  在斷線重連時，NotificationListenerService 回呼會重新發送常駐通知。新版將 `postedAtEpochMs` 寫入 Checkpoint，並在 `samePost` 判定中加入 `(!previous.closed || (postedAtEpochMs != null && postedAtEpochMs == previous.postedAtEpochMs))`。這使得重連時雖然上一視窗已被標記為 `closed = true`，但因為發布時間戳一致，仍能精準識別為 `KnownKind.REPOST`，避免了被誤判為 `AmbiguousRepeat` 導致資料庫被重複灌水。

---

## 四、新增問題發現（New Findings）

本次針對 Commit `8050e05` 的全面審查中：
- **Critical（嚴重阻擋性問題）**：**0 項**。
- **Important（重要問題）**：**0 項**。
- **Minor（次要改進建議）**：**3 項**。

### M1. `BackupService.export` 失敗時之 SAF 目標檔案截斷處理（輕微風險）
- **位置**：[`BackupService.kt:88`](../../../platform/backup/BackupService.kt#L88)
- **問題分析**：
  當匯出過程遭遇未預期的 IO 例外時，代碼執行：
  ```kotlin
  runCatching { context.contentResolver.openOutputStream(target, "wt")?.close() }
  ```
  此處將目標檔案截斷為 0 byte，並在多語系字串中提示使用者：「若你選擇覆寫既有檔案，該檔案已被覆寫，不再是有效備份。」
  雖然符合 SAF 不支援任意建立暫存檔並原子重新命名的限制，但若使用者在 SAF 選取了過去的有效備份作為目標，一旦匯出途中磁碟空間不足或出錯，既有備份將變為空檔。
- **改進建議**：
  可考慮先將加密串流寫入應用內部的快取目錄（`context.cacheDir/temp_backup.qibk`），待整體驗證寫入完成且關閉串流後，再以 BufferedStream 複製到使用者選定的 SAF `target` URI。若中途失敗，僅刪除快取檔案，即可保證使用者選定的舊備份完全不受波及。

### M2. `conversation.summaryOnlyCount` 資料庫欄位未於 commit 時同步遞增
- **位置**：[`Entities.kt:100`](../../../platform/storage/db/Entities.kt#L100)、[`IngestRepository.kt:186`](../../../platform/storage/repo/IngestRepository.kt#L186)
- **問題分析**：
  `conversation` 資料表與 `Conversation` 實體中定義了 `summaryOnlyCount: Int` 欄位。但在 `IngestRepository.commit` 中建立新會話時固定填入 `0`，後續亦無 UPDATE 語句維護該欄位。目前的統計功能（`AnalyticsViewModel`）直接從 `diagnosticsDao().countCode("SUMMARY_ONLY", ...)` 查詢整體摘要數，因此對使用者介面無直接負面影響，但欄位維持恆為 0 易對後續開發者產生困惑。
- **改進建議**：
  未來重構時，可考慮於日後版本移除該冗餘欄位，或於解析出純摘要通知時對相應會話遞增該計數。

### M3. `ReconcilerPropertyTest.kt` 缺少 Kotest 實驗性 API Opt-In 標註
- **位置**：[`ReconcilerPropertyTest.kt:25`](../../../core/reconcile/src/test/kotlin/dev/quietinbox/core/reconcile/ReconcilerPropertyTest.kt#L25)
- **問題分析**：
  編譯單元測試時出現編譯警告：
  `w: ... ReconcilerPropertyTest.kt:25:13 This declaration needs opt-in. Its usage should be marked with "@io.kotest.common.ExperimentalKotest" or "@OptIn(io.kotest.common.ExperimentalKotest::class)"`
- **改進建議**：
  在測試類別或檔案頂部加上 `@OptIn(ExperimentalKotest::class)` 以維持編譯器零警告（Clean Build）。

---

## 五、其他正面架構驗證（Other Observations）

1. **嚴格遵守無網路與隱私防護規範（硬性規則 1）**：
   全專案無任何 `android.permission.INTERNET` 與網路連線狀態權限；所有通知內文、發送者名稱、標題與圖片 content URI 皆嚴格限制於本機加密儲存，未輸出至任何 Log、診斷日誌或 Crash 堆疊追蹤。
2. **來源通知無副作用（硬性規則 2）**：
   無任何對來源通知之 `cancelNotification`、`RemoteInput` 或自動標記已讀操作；點擊開啟來源應用一律具備二次確認 Dialog。
3. **金鑰與加密架構嚴謹度（硬性規則 7）**：
   `KeystoreWrapper` 明確設定 `.setUserAuthenticationRequired(false)`，確保螢幕鎖定期間 NLS 仍可在背景加密入庫；SQLCipher 金鑰陣列未在開庫後清零，合理解決了 WAL 模式連線池的 Readers 重建需求。
4. **字串多語系資源 100% 精準對應（硬性規則 9）**：
   驗證 `core/designsystem/src/main/res/values/strings.xml` 與 `values-b+zh+Hant/strings.xml`，所有 298 條字串鍵值完全一致，無任一缺漏。
5. **完整的 Room Schema 版控與遷移安全**：
   Room `exportSchema = true`，v1 與 v2 之結構定義 JSON 皆納入版控，完全未引入 `fallbackToDestructiveMigration()`。

---
*報告完成時間：2026-09-06*
