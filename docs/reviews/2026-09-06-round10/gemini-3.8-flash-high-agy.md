# 程式碼審查報告：QuietInbox 審計 P0 Wave (`3685469..f64ae7b`)

**審查範圍**：Commit `f64ae7b`（相對於 `3685469`）  
**審查焦點**：GitHub Issues #1, #2, #3, #4, #5, #7 以及 #6（部分），包含交易安全性、併發圍籬、金鑰與金庫重設、資料刪除圖完整性與邊界競態。

---

## 一、審查結論 (Verdict)

### **APPROVE WITH MINOR FIXES**（核准但建議修復次要與邊界問題）

**總體評價**：
本次 commit 針對 GPT-5.5 Pro 審計提出的 5 個 P0 漏洞及相關資料完整性問題給出了非常紮實且結構清晰的修復：
1. `CaptureCoordinator` 引入雙層 `admitted()` 圍籬與 `commitFenced()`，徹底阻斷了停用來源或暫停期間的非法 commit；
2. `VaultMaintenance` 採用「立旗 → 取消並等待 Worker → 持有 Pipeline 鎖」的屏障架構，解決了金庫重設與擷取管線的衝突；
3. 刪除圖在 SQLite 交易內完整涵蓋了 Message、Media Blob、Suppression Token、Journal Payload 清理，並由 `rebuildProjection` 重建會話狀態；
4. `KeystoreWrapper` 的行程級建立鎖修復了 KEK 併發覆寫覆滅金鑰的致命隱患。

然而，在深入分析邊界與極端併發情境後，發現了 **3 項 Important 級別的邊界問題**（包括 Journal 重播的 Head-of-Line 餓死、`BlobCipher` 競態下的無效金鑰回傳、以及 `StateFlow` 對快速維護狀態變化的 Conflation 合併）與 **2 項 Minor 觀測事項**，建議在下一版小幅補強。

---

## 二、宣稱驗證表 (Claim Verification Table)

| 審計議題 / 宣稱項目 | 狀態 | 驗證結果與證據（檔案與行號） |
| :--- | :---: | :--- |
| **#1 `CaptureCoordinator` 策略與圍籬**<br>- 雙層 `admitted()` 圍籬（鎖前 + 鎖內）<br>- `commitFenced()` 雙重檢查<br>- 來源策略變更走協調器統一排程<br>- `observeSources` 僅於鎖內觸發重載<br>- 停用/移除來源清空 PENDING journal<br>- `replayJournal` 於 `work {}` 執行且暫停時不重播 | **已修復** | • 鎖前/鎖內檢查：[`CaptureCoordinator.kt:480, 487`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L480-L487)<br>• commit 圍籬檢查：[`CaptureCoordinator.kt:540, 570`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L540-L570)<br>• 來源策略變更在 `pipelineMutex` 內完成寫入並更新快取：[`CaptureCoordinator.kt:316-348`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L316-L348)<br>• Flow 收集端僅於鎖內呼叫 `loadSourcePolicy()`：[`CaptureCoordinator.kt:162-164`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L162-L164)<br>• 停用即清空：[`CaptureCoordinator.kt:337, 347`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L337)、[`Daos.kt:61-62`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L61-L62)<br>• `replayJournal` 在 `maintenance.work` 內，遇暫停不前進，遇停用來源標 `DISCARDED`：[`CaptureCoordinator.kt:602-635`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L602-L635) |
| **#2 `SettingsRepository` 媒體揭露閘門**<br>- `mediaCopyEnabled` 預設 false<br>- 有效值為 Switch 開關 且 揭露已同意<br>- `MediaCopier.copyPending` 重新確認設定，關閉時降級為 `DISABLED_BY_USER` | **已修復** | • 設定模型預設值改為 `false`：[`SettingsRepository.kt:37`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/settings/SettingsRepository.kt#L37)<br>• 讀取時計算有效值 `(switch && disclosure)`：[`SettingsRepository.kt:82`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/settings/SettingsRepository.kt#L82)<br>• `copyPending` 開頭重新讀取，若關閉則逐筆將 `PENDING` 更新為 `DISABLED_BY_USER`：[`MediaCopier.kt:59-65`](file:///Users/iml1s/Documents/mine/quietinbox/platform/media/src/main/kotlin/dev/quietinbox/platform/media/MediaCopier.kt#L59-L65) |
| **#3 `VaultMaintenance` 維護屏障**<br>- `pipelineMutex`、`active`、`work {}`、`exclusive {}`<br>- `VaultRepository.deleteEverything()` 步驟驗證<br>- 關閉資料庫/刪除實體檔案/媒體全清<br>- `KeyMaterial.epoch` 銷毀時遞增，`BlobCipher` 快取 epoch<br>- 協調器 `onMaintenance` 輪換 generation、記 `MAINTENANCE` 缺口<br>- UI 呈現失敗步驟 | **已修復** | • 維護閘門架構完整：[`VaultMaintenance.kt:36-76`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/VaultMaintenance.kt#L36-L76)<br>• `deleteEverything` 依序校驗各步驟回傳 `ResetResult`：[`VaultRepository.kt:47-56`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/VaultRepository.kt#L47-L56)<br>• 資料庫實體檔與媒體檔真實驗證刪除：[`DatabaseHolder.kt:86-98`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/DatabaseHolder.kt#L86-L98)、[`RetentionWorker.kt:134-137`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/retention/RetentionWorker.kt#L134-L137)<br>• `KeyMaterial.epoch` 遞增與 `BlobCipher` 關聯：[`KeyMaterial.kt:32, 54`](file:///Users/iml1s/Documents/mine/quietinbox/platform/crypto/src/main/kotlin/dev/quietinbox/platform/crypto/KeyMaterial.kt#L32)、[`BlobCipher.kt:24, 30-58`](file:///Users/iml1s/Documents/mine/quietinbox/platform/crypto/src/main/kotlin/dev/quietinbox/platform/crypto/BlobCipher.kt#L24)<br>• 記錄 `MAINTENANCE` 缺口與字串定義：[`CaptureCoordinator.kt:358-394`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L358-L394)、[`CaptureHealth.kt:21`](file:///Users/iml1s/Documents/mine/quietinbox/core/model/src/main/kotlin/dev/quietinbox/core/model/CaptureHealth.kt#L21)、[`strings.xml`](file:///Users/iml1s/Documents/mine/quietinbox/core/designsystem/src/main/res/values/strings.xml#L190)<br>• `SettingsViewModel` 捕捉並在 UI 顯示失敗步驟：[`SettingsViewModel.kt:149-158`](file:///Users/iml1s/Documents/mine/quietinbox/feature/settings/src/main/kotlin/dev/quietinbox/feature/settings/SettingsViewModel.kt#L149-L158) |
| **#4 刪除圖 (Deletion Graph) 完整性**<br>- `JournalDao.setState` 清除非 PENDING 的 payload<br>- `ConversationDao.rebuildProjection` 正確重算<br>- `InboxRepository` 交易內刪除媒體列，交易後刪除實體檔案<br>- `SourceRepository.remove` 級聯清理（`substr` 前綴比對）<br>- `RetentionService` 納入維護閘門、移除電池限制<br>- `MediaDao.orphans()` 納入未連結 blob | **已修復** | • SQL `CASE WHEN state = 'PENDING'` 清空 payload：[`Daos.kt:50-58`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L50-L58)<br>• `rebuildProjection` 依現存未到期訊息重算投影：[`Daos.kt:167-182`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L167-L182)<br>• 交易內刪列、交易後刪實體檔：[`InboxRepository.kt:77-83, 96-101`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/InboxRepository.kt#L77-L83)<br>• 移除來源清理 suppression, summaries, diagnostics：[`SourceRepository.kt:66-78`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/SourceRepository.kt#L66-L78)、前綴比對：[`Daos.kt:367-368`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L367-L368)<br>• `RetentionService.runOnce` 受 `work {}` 保護並移除 `requiresBatteryNotLow`：[`RetentionWorker.kt:55, 103`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/retention/RetentionWorker.kt#L55)<br>• `orphans()` 查詢條件包含 `m.mediaBlobId IS NULL OR m.mediaBlobId != b.id`：[`Daos.kt:325`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L325) |
| **#5 `KeystoreWrapper` 行程級建立鎖**<br>- 消除首次建立 KEK 的併發覆寫競態 | **已修復** | • Companion 內定義 `private val createLock = Any()`，`getOrCreateKey()` 加鎖：[`KeystoreWrapper.kt:84, 114`](file:///Users/iml1s/Documents/mine/quietinbox/platform/crypto/src/main/kotlin/dev/quietinbox/platform/crypto/KeystoreWrapper.kt#L84) |
| **#7 到期過濾**<br>- 讀取端全面加入 `expiresAtEpochMs IS NULL OR > :now` | **已修復** | • `observeForConversation`：[`Daos.kt:204`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L204)<br>• `observeCount` / `observeAmbiguousCount`：[`Daos.kt:259, 262`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L259)<br>• `statsBetween` / `earliestSortKey`：[`Daos.kt:281, 289`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L281)<br>• `search`：[`Daos.kt:389`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L389)、[`SearchRepository.kt:33`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/SearchRepository.kt#L33) |
| **#6 部分：`MediaCopier` 交易性與 Bitmap 計數**<br>- 實體檔寫入後單一交易關聯<br>- 任一步驟失敗清理已寫入實體檔<br>- 縮圖加密失敗視為 null 不中斷主圖<br>- Bitmap 計數維持到複製完成 | **已修復** | • 交易整合與寫入清理：[`MediaCopier.kt:147-185`](file:///Users/iml1s/Documents/mine/quietinbox/platform/media/src/main/kotlin/dev/quietinbox/platform/media/MediaCopier.kt#L147-L185)<br>• 縮圖失敗僅刪除暫存並留 null：[`MediaCopier.kt:159-166`](file:///Users/iml1s/Documents/mine/quietinbox/platform/media/src/main/kotlin/dev/quietinbox/platform/media/MediaCopier.kt#L159-L166)<br>• `queuedBitmaps` 在 `MediaCopier` 協程的 `finally` 中扣除：[`CaptureCoordinator.kt:448, 509, 587`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L587) |
| **Schema v3 遷移**<br>- `MIGRATION_2_3`<br>- Nullable `ADD COLUMN` | **已修復** | • 遷移腳本與 schema JSON：[`QuietInboxDatabase.kt:90-96`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/QuietInboxDatabase.kt#L90-L96)、[`3.json`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/schemas/dev.quietinbox.platform.storage.db.QuietInboxDatabase/3.json) |

---

## 三、新發現問題與風險 (New Findings)

### 1. [Important] `replayJournal` 在暫停來源達到或超過 200 筆時產生 Head-of-Line Blocking（重播管線永久餓死）
* **具體位置**：[`Daos.kt:46-47`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L46-L47) 與 [`CaptureCoordinator.kt:607-635`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L607-L635)
* **故障情境**：
  1. `JournalDao.pending(limit: Int)` 的查詢為：
     ```sql
     SELECT * FROM event_journal WHERE state = 'PENDING' ORDER BY receivedAtEpochMs LIMIT :limit
     ```
     此查詢**完全沒有排除已暫停來源**（`pausedPackages`）。
  2. 使用者暫停了來源 A（例如高訊息量的群組聊天 Telegram/WhatsApp），來源 A 在暫停前或冷啟動金庫鎖定期間累積了 200 筆以上的 `PENDING` 記錄。
  3. 來源 B（正常啟用且未暫停的來源）隨後也產生了待重播的 `PENDING` 記錄（時間戳晚於來源 A）。
  4. 當金庫解鎖或恢復重播時，`replayJournal()` 呼叫 `ingest.pendingJournal(200)`，回傳的 200 筆全部是來源 A 的記錄。
  5. 迴圈遍歷這 200 筆記錄，呼叫 `processJournaled()`。因為來源 A 在 `pausedPackages` 中，`commitFenced()` 回傳 `true`，該筆記錄**被刻意維持在 `PENDING` 狀態**（設計意圖是等恢復暫停時再 commit）。
  6. 由於這 200 筆記錄都沒有改變狀態，`progressed` 變數維持為 `false`。
  7. `while (progressed && rounds++ < 100 && !paused)` 條件不成立，**重播迴圈在第 1 輪後直接終止**！
  8. **後果**：只要來源 A 的暫停記錄佔滿前 200 個名額，來源 B 以及所有其他來源的待重播訊息將**永遠無法被載入執行**（被前 200 筆暫停記錄堵死），直到使用者手動解除來源 A 的暫停或刪除來源 A。
* **建議修復**：
  在 `JournalDao.pending` 查詢中支援排除名單（傳入 `pausedPackages`），或者在 `pending` 查詢時帶上游標分頁/排除名單，確保未暫停來源的事件可以跨過暫停事件繼續重播。

---

### 2. [Important] `BlobCipher.primitive()` 在與 `destroyAll()` 競態時仍回傳已失效金鑰的 AEAD 實體
* **具體位置**：[`BlobCipher.kt:53-58`](file:///Users/iml1s/Documents/mine/quietinbox/platform/crypto/src/main/kotlin/dev/quietinbox/platform/crypto/BlobCipher.kt#L53-L58)
* **故障情境**：
  1. `primitive()` 執行，取得 `epoch = keyMaterial.epoch`（假設為 0），並從 `keyMaterial.media.getOrCreate()` 取得舊金鑰建立 Tink AEAD 實體 `p`。
  2. 同一時間，使用者執行重設，`keyMaterial.destroyAll()` 執行完成，刪除了 `media.key` 並將 `_epoch` 遞增為 1。
  3. `primitive()` 繼續執行到第 56 行：
     ```kotlin
     // A reset that raced this build wins: do not cache a primitive of a dead epoch.
     if (epoch == keyMaterial.epoch) cached = Cached(epoch, p)
     KeyResult.Ok(p)
     ```
     程式碼發現 `epoch != keyMaterial.epoch`，因此沒有快取 `p`。
  4. **但是它依然直接回傳了 `KeyResult.Ok(p)`！**
  5. 呼叫端（例如 [`BlobCipher.kt:68-81`](file:///Users/iml1s/Documents/mine/quietinbox/platform/crypto/src/main/kotlin/dev/quietinbox/platform/crypto/BlobCipher.kt#L68-L81) 的 `encryptToFile`）拿到這個由已銷毀金鑰產生的 `p`，將檔案加密並寫入磁碟。
  6. **後果**：寫入磁碟的這份檔案是使用**已經被銷毀的金鑰**加密的，在新金庫的生命週期中，這份媒體檔案將**永遠無法被解密**。
* **建議修復**：
  若偵測到 `epoch != keyMaterial.epoch`，應回傳 `KeyResult.Failed(KeyFailure.Unavailable("epoch changed during cipher initialization"))` 或重新迴圈以新 epoch 重建，絕不可直接回傳過期金鑰的 `p`。

---

### 3. [Important] `VaultMaintenance.active` 使用 `MutableStateFlow` 導致快速維護狀態轉換被 Conflate（漏掉 `MAINTENANCE` Gap 與 generation 輪換）
* **具體位置**：[`VaultMaintenance.kt:39, 67, 74`](file:///Users/iml1s/Documents/mine/quietinbox/platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/VaultMaintenance.kt#L39)、[`CaptureCoordinator.kt:168, 358-394`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L168)
* **故障情境**：
  1. `VaultMaintenance._active` 是 `MutableStateFlow(false)`。
  2. 在 `exclusive { block() }` 中：
     ```kotlin
     _active.value = true
     try {
         ...
         pipelineMutex.withLock { block() }
     } finally {
         _active.value = false
     }
     ```
  3. `CaptureCoordinator.init` 中使用協程監聽：
     ```kotlin
     // Plain collect: the start-of-maintenance bookkeeping must not be cancelled by a fast end.
     maintenance.active.collect { active -> onMaintenance(active) }
     ```
     作者在註解中特別強調使用 `collect` 而非 `collectLatest` 是為了防止快速結束取消開始流程。
  4. **然而，Kotlin Coroutines 的 `StateFlow` 在發布端本身就會發生 Conflation（值合併）**：如果 `block()` 執行極快（例如單元測試，或在極快速的 I/O 上重設完成），`_active.value` 從 `false` → `true` → `false`。若協程切換尚未被排程執行，`StateFlow` 會直接丟棄中途的 `true`，只發送最新的 `false`！
  5. 當協程喚醒收到 `false` 時：
     ```kotlin
     val startedAt = maintenanceStartedAt ?: return
     ```
     因為從未執行過 `onMaintenance(true)`，`maintenanceStartedAt` 為 `null`，函式直接 `return`！
  6. **後果**：
     - `sourcesLoaded = false` 沒被執行。
     - `activeGeneration` 沒有輪換。
     - 本次維護期間的 `GapReason.MAINTENANCE` 缺口**完全沒有被記錄**。
* **建議修復**：
  狀態事件應使用 `Channel` 或 `MutableSharedFlow(extraBufferCapacity = 64)`，而非會自動 Conflate 的 `StateFlow`；或者由 `VaultMaintenance.exclusive` 直接與 Coordinator 進行顯式的生命週期回呼通知。

---

### 4. [Minor] `MediaCopier.store()` 在 Room 交易完成時若遇協程取消可能導致 DB 與實體檔案不一致
* **具體位置**：[`MediaCopier.kt:170-184`](file:///Users/iml1s/Documents/mine/quietinbox/platform/media/src/main/kotlin/dev/quietinbox/platform/media/MediaCopier.kt#L170-L184)
* **故障情境**：
  ```kotlin
  db.withTransaction {
      val id = db.mediaDao().insert(...)
      db.messageDao().setMedia(messageId, MediaState.LOCAL_COPY.name, id)
  }
  written.clear()
  return MediaState.LOCAL_COPY
  ```
  在 Room 的 `withTransaction` 中，若 SQLite 交易已 commit，但協程在自 dispatcher 恢復時剛好被取消（例如維護任務強制取消 Worker），`withContext/withTransaction` 可能在回傳點拋出 `CancellationException`。此時執行流會跳過 `written.clear()` 直接進入 `finally { for (f in written) dir.delete(f) }`，導致實體檔案被刪除，但資料庫中已寫入 `LOCAL_COPY` 與對應的 `media_blob` 列，留下指向不存在檔案的幽靈記錄。
* **建議修復**：可在 `withTransaction` 的 lambda 內部最後一行立即將 `written.clear()` 清空，避免在交易結束與函式返回之間的切換縫隙受取消例外影響。

---

### 5. [Minor] `CaptureCoordinatorTest` 測試中依賴 `delay(200)` 與 Mutex 公平性假設
* **具體位置**：[`CaptureCoordinatorTest.kt:384-386`](file:///Users/iml1s/Documents/mine/quietinbox/platform/capture/src/test/kotlin/dev/quietinbox/platform/capture/CaptureCoordinatorTest.kt#L384-L386)
* **說明**：
  測試 `"a source disabled while an event waits for the pipeline lock is never journaled"` 依賴 `delay(200)` 來確保 `launch { coordinator.setSourceEnabled(...) }` 一定先於 `releaseJournal.complete(Unit)` 排入 `pipelineMutex` 的等待佇列。雖然在大多數情況下 200ms 足夠，但在極端 CPU 負載或 CI 虛擬機卡頓情境下，基於真實時間延遲的同步依然存在潛在 Flaky 機率。

---

## 四、專題深入分析與驗證 (Detailed Observations)

### 1. 鎖順序與死鎖風險分析
* **涉及鎖**：`pipelineMutex`（管線/維護）、`VaultMaintenance.exclusiveMutex`（重設互斥）、`DatabaseHolder.mutex`（資料庫開啟/關閉）、SQLite/Room 連線事務。
* **分析結果：無死鎖風險**。
  - `exclusive()` 執行順序為：取得 `exclusiveMutex` → 設定 `active = true` → 取消並等待所有 `work` 協程結束（此時尚未持有 `pipelineMutex`）→ 取得 `pipelineMutex` → 執行重設區塊。
  - 當重設區塊執行時，它持有 `pipelineMutex`，隨後呼叫 `holder.closeAndDeleteFiles()` 取得 `DatabaseHolder.mutex`。取得順序始終為：`pipelineMutex` → `DatabaseHolder.mutex`。
  - 在一般處理管線中，`process()` 或 `changeSourcePolicy` 持有 `pipelineMutex` 後進行 Room 存取，而 `DatabaseHolder.db()` 並不持有 `DatabaseHolder.mutex`。
  - 全專案中沒有任何路徑是在持有 `DatabaseHolder.mutex` 或 SQLite 交易時反向請求 `pipelineMutex` 的情況，鎖階層方向單向固定，不會產生循環等待死鎖。

### 2. `scopeKey` 前綴比對正確性 (`substr` vs `LIKE`)
* **分析結果：完全正確**。
  - `SourceRepository.remove` 傳入前綴 `"$packageName|"`。
  - Android 的套件名稱（Package Name）遵循 Java 命名規範（英數字與底線），絕對不會包含垂直線 `|`。
  - SQLite 查詢採用 `substr(scopeKey, 1, length(:prefix)) = :prefix`，成功避免了 `LIKE` 運算子將套件名稱中的底線 `_` 視為任意單一字元萬用字元的潛在誤刪問題（例如 `com.example_app` 與 `com.example1app` 的碰撞）。

### 3. `mediaCopyEnabled` 有效值與 UI 開關連動
* **分析結果：安全無旁路**。
  - DataStore 中若舊版本已存在 `media_copy = true`，但在升級後因 `media_disclosure` 未記錄，`AppSettings.mediaCopyEnabled` 會計算為 `false`。
  - UI 開關綁定 `s.mediaCopyEnabled`，因此舊用戶升級後開關顯示為關閉；當用戶點擊打開時，`SettingsScreen` 偵測到 `!s.mediaDisclosureAccepted`，會彈出強制揭露對話框，同意後才一併寫入 `mediaDisclosure` 與 `mediaCopy`。
  - 後台 `IngestRepository.commit` 與 `MediaCopier.copyPending` 均讀取計算後的有效值，有效阻絕未經揭露即複製媒體的行為。

### 4. 讀取端到期過濾的 `now` 固定機制
* **分析結果：設計符合規範**。
  - 在 `MessageDao.observeForConversation(conversationId, now)` 等 Flow 查詢中，`now` 參數在 Flow 被收集（Collection）時計算並固定。
  - 這意味著在單一畫面上開著跨越過期邊界時，資料庫已過期的訊息在畫面未重新收集或未觸發重組時會繼續顯示，直到下一次重讀或背景 `RetentionService` 進行清理。程式碼在註解中明確載明了此一設計取捨，符合行動端常見的穩定性考量。

### 5. 文件與多語系對照
* **分析結果：雙語高度一致**。
  - `CHANGELOG.md` 完整記錄了各項 P0 修正與對應的 Issue 編號。
  - 繁體中文與英文文件（`ARCHITECTURE.md`、`TEST_MATRIX.md`、`strings.xml`）在所有新列出的專案、狀態標籤及測試矩陣計數上均嚴格對齊，無任何疏漏。

---

## 五、建議行動清單 (Actionable Recommendations)

1. **修正 `replayJournal` 的 Head-of-Line Blocking**：
   在 `JournalDao` 新增支援排除套件的查詢：
   ```sql
   @Query("SELECT * FROM event_journal WHERE state = 'PENDING' AND (packageName IS NULL OR packageName NOT IN (:excludedPackages)) ORDER BY receivedAtEpochMs LIMIT :limit")
   suspend fun pendingExcluding(limit: Int, excludedPackages: Set<String>): List<EventJournalEntity>
   ```
   使 `replayJournal` 在重播時跳過處於 `pausedPackages` 的事件，避免阻斷後續正常套件的重播。
2. **修正 `BlobCipher.primitive()` 的過期金鑰回傳**：
   在 `BlobCipher.kt` 中，若發現 `epoch != keyMaterial.epoch`，應拋出錯誤或拒絕回傳該 AEAD 實體，避免以已銷毀的金鑰加密資料。
3. **改進 `VaultMaintenance.active` 的狀態通知**：
   將 `_active` 由 `MutableStateFlow` 改為帶緩衝的廣播通知（例如 `Channel`），或在 `VaultMaintenance.exclusive` 開始與結束時顯式觸發 `Coordinator` 介面，以消除 `StateFlow` 合併值導致的漏通知風險。
