# QuietInbox／靜讀 v0.1 垂直切片 代碼審查報告

## 審查結論（Verdict）
**REQUEST CHANGES（建議修復阻擋性問題後再推送）**

本專案整體架構嚴密，在離線隱私保護（無 INTERNET 權限、無日誌外洩、SAF 加密備份、FLAG_SECURE）、SQLCipher + Keystore 金鑰封裝、Material 3 Expressive 與 Navigation 3 自適應排版、多訊息視窗比對去重等核心設計上具備極高的工程水準。

然而，在深入審查過程中發現了 **3 個 Critical 等級** 與 **3 個 Important 等級** 的具體邏輯缺陷，包括：
1. **資料庫開啟失敗時協程永久凍結**（`DatabaseHolder.db()` 無法結束，卡死管線與 UI）。
2. **備份還原之媒體檔案會在首次 TTL 清理時被全數當成孤兒檔案刪除**（`media_blob.messageId` 遺漏關聯）。
3. **刪除整個會話時防重播（Suppression）機制失效**（重播時建立新會話 ID，導致使用者已刪內容在通知重送時立即復活）。
4. **去重器在混合有 ID 與無 ID 訊息時的視窗比對失序與錯位索引**。
5. **過期舊通知重播（Stale Replay）導致檢查點視窗倒退**，進而使後續正常訊息重複入庫。
6. **使用者暫停擷取時未輪替 Generation**，致使 Commit Fence 無法阻擋已排隊事件入庫。

以上缺陷皆有明確的觸發路徑與代碼位置，修復後即可安全推送。

---

## 嚴重問題（Critical — 必須在 push 前修復）

### 1. `DatabaseHolder.db()` 在 Vault 處於 Opening 且開啟失敗時永久掛起（Hang）
- **檔案與行號**：[`DatabaseHolder.kt:L57-L66`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/DatabaseHolder.kt#L57-L66)
- **問題原因**：
  ```kotlin
  suspend fun db(): QuietInboxDatabase {
      when (val s = _state.value) {
          is VaultState.Ready -> return s.db
          is VaultState.Locked -> throw VaultUnavailableException(s.failure)
          VaultState.Opening -> Unit
      }
      return when (val s = _state.filterIsInstance<VaultState.Ready>().first()) {
          else -> s.db
      }
  }
  ```
  當呼叫 `db()` 時若 `_state.value` 恰好是 `VaultState.Opening`，方法會進入 `_state.filterIsInstance<VaultState.Ready>().first()`。
  若非同步開啟金鑰／資料庫失敗（例如金鑰損毀、Keystore 未解鎖等），`_state` 會轉換為 `VaultState.Locked(failure)`。
  然而，`filterIsInstance<VaultState.Ready>()` **只會過濾 Ready 狀態**，永遠不會匹配 `Locked`！這導致此協程 **永久掛起（Indefinitely frozen）**，永遠不會拋出 `VaultUnavailableException`！
  這會進一步導致 [`CaptureCoordinator.kt:L245-L263`](../../../platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L245-L263) 中的 `process(item)` 佇列處理協程卡死，以及所有 UI / Flow 載入協程無響應。
- **具體修復方案**：
  等待任一終態（非 `Opening`）並分支處理：
  ```kotlin
  suspend fun db(): QuietInboxDatabase {
      return when (val s = _state.first { it !is VaultState.Opening }) {
          is VaultState.Ready -> s.db
          is VaultState.Locked -> throw VaultUnavailableException(s.failure)
          VaultState.Opening -> error("unreachable")
      }
  }
  ```

---

### 2. 還原備份之媒體檔案將在首次 Retention 清理時被全數抹除（Data Loss）
- **檔案與行號**：
  - [`BackupService.kt:L258-L284`](../../../platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L258-L284)
  - [`Daos.kt:L254-L255`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L254-L255)
  - [`RetentionWorker.kt:L58-L63`](../../../platform/storage/retention/RetentionWorker.kt#L58-L63)
- **問題原因**：
  在 `BackupService.apply` 還原訊息與媒體時：
  ```kotlin
  blobId = db.mediaDao().insert(MediaBlobEntity(messageId = null, ...))
  ...
  val newId = db.messageDao().insert(MessageEntity(..., mediaBlobId = blobId, ...))
  ```
  `MediaBlobEntity` 先以 `messageId = null` 寫入資料庫，隨後訊息插入取得 `newId`，但代碼中 **完全沒有將 `MediaBlobEntity.messageId` 更新為 `newId`**！
  而在 [`Daos.kt:L254-L255`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt#L254-L255) 中：
  ```kotlin
  @Query("SELECT * FROM media_blob WHERE messageId NOT IN (SELECT id FROM message) OR messageId IS NULL")
  suspend fun orphans(): List<MediaBlobEntity>
  ```
  `orphans()` 明確包含 `OR messageId IS NULL`！
  當由 WorkManager 每 12 小時觸發的 [`RetentionWorker`](../../../platform/storage/retention/RetentionWorker.kt#L58-L63) 執行時，它會將所有剛從備份還原的 `media_blob`（其 `messageId` 為 null）**視為孤兒檔案直接呼叫 `mediaDir.delete(blob.fileName)` 並刪除資料表記錄**！
  使用者辛苦還原的所有媒體檔案在下一次排程清理時將永久消失，訊息內的 `mediaBlobId` 全數變成懸空引用。
- **具體修復方案**：
  在 `MediaDao` 中新增更新 `messageId` 的方法：
  ```kotlin
  @Query("UPDATE media_blob SET messageId = :messageId WHERE id = :blobId")
  suspend fun setMessageId(blobId: Long, messageId: Long)
  ```
  並在 `BackupService.apply` 插入訊息取得 `newId` 後立即更新：
  ```kotlin
  if (blobId != null) {
      db.mediaDao().setMessageId(blobId, newId)
  }
  ```

---

### 3. 刪除整個會話時防重播復活失效（違反硬性規範 5）
- **檔案與行號**：
  - [`InboxRepository.kt:L82-L89`](../../../platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/InboxRepository.kt#L82-L89)
  - [`IngestRepository.kt:L153-L174`](../../../platform/storage/repo/IngestRepository.kt#L153-L174)
  - [`Daos.kt:L269-L270`](../../../platform/storage/db/Daos.kt#L269-L270)
- **問題原因**：
  規範 §7.3 與評審簡報要求：「使用者刪除訊息後，活動通知重播不能把內容復活。」
  在 `InboxRepository.deleteConversation` 中：
  ```kotlin
  suspend fun deleteConversation(conversationId: Long, now: Long, suppressionTtlMs: Long) {
      val db = holder.db()
      db.withTransaction {
          for (row in db.messageDao().forConversation(conversationId)) {
              db.suppressionDao().upsert(DeletionSuppressionEntity(conversationId, row.fingerprint, now + suppressionTtlMs))
          }
          db.conversationDao().delete(conversationId)
      }
  }
  ```
  `DeletionSuppressionEntity` 使用了資料庫自增代理鍵 `conversationId: Long` 作為關聯鍵。當會話被刪除後，該 `conversationId`（例如 5）的資料列便不存在。
  此時若系統常駐通知被重播（Active Notification Replay，如重連或重啟）：
  在 `IngestRepository.commit`（L153）：
  ```kotlin
  val existing = convDao.find(identity.scope.packageName, identity.scope.profileKey, identity.scope.accountKey, identity.identityKey)
  val conversationId = existing?.id ?: convDao.insert(...) // 產生新的 ID，例如 6
  ```
  接著比對抑制標記（L187）：
  ```kotlin
  if (db.suppressionDao().isSuppressed(conversationId, decision.fingerprint, now) > 0)
  ```
  此時傳入的是 `conversationId = 6`，而抑制表只存有 `conversationId = 5` 的紀錄，查詢結果為 0！
  **被使用者刪除的整組會話及訊息立刻被完整重新入庫復活！**
- **具體修復方案**：
  `deletion_suppression` 資料表不應依賴容易變動的內部自增 `conversationId`，而應綁定於會話的穩定身分範圍（或記錄 `packageName` + `identityKey`）：
  修改 `DeletionSuppressionEntity`：
  ```kotlin
  @Entity(tableName = "deletion_suppression", primaryKeys = ["packageName", "identityKey", "fingerprint"], indices = [Index("expiresAtEpochMs")])
  data class DeletionSuppressionEntity(
      val packageName: String,
      val identityKey: String,
      val fingerprint: String,
      val expiresAtEpochMs: Long,
  )
  ```
  在檢查與寫入時以 `(identity.scope.packageName, identity.identityKey, fingerprint)` 進行查詢與判定。

---

## 重要問題（Important — 建議在 push 前修復）

### 4. `Reconciler` 在同視窗內混合「具備 ID」與「無 ID」訊息時的比對失序與錯位索引
- **檔案與行號**：[`Reconciler.kt:L123-L164`](../../../core/reconcile/src/main/kotlin/dev/quietinbox/core/reconcile/Reconciler.kt#L123-L164)
- **問題原因**：
  在 `Reconciler.reconcile` 中：
  ```kotlin
  // 1. id path
  val decisions = arrayOfNulls<Decision>(working.size)
  for ((i, c) in working.withIndex()) {
      val sid = c.sourceMessageId ?: continue
      ...
  }
  // 2. window alignment for id-less items
  val prevItems = previous?.items.orEmpty()
  val prevFps = prevItems.map { it.fingerprint } // 包含前一個視窗的所有項目（有 ID 與無 ID）
  val idless = working.indices.filter { decisions[it] == null }
  val newFps = idless.map { fps[it] } // 只包含「無 ID」的指紋！
  val overlap = suffixPrefixOverlap(prevFps, newFps)
  ```
  1. `prevFps` 是完整列表，而 `newFps` 抽掉了有 ID 的項目。若前一視窗中含有 ID 訊息（特別是穿插在中間或尾部），`prevFps` 與 `newFps` 的字串序列長度與結構不同，`suffixPrefixOverlap` 比對將直接失敗（`overlap = 0`），導致本應識別為既有訊息的項目被誤判為 `Decision.New` 重複存入。
  2. 更嚴重的是行 151：
     ```kotlin
     prevItems.getOrNull(prevItems.size - overlap + k)?.messageId
     ```
     此處 `k` 是 `newFps` 的局部索引，但 `prevItems` 卻是完整列表！一旦前一視窗有 ID 訊息被跳過，`k` 與 `prevItems` 的下標將產生位移（Offset Mismatch），導致取得 **錯誤的上一則訊息 ID**！
- **具體修復方案**：
  視窗序列對齊應在所有項目的指紋序列上統一進行對齊（保留整體拓撲），或者在對齊前統一將 ID 與無 ID 的對帳上下文進行相應的序列投影；取得 `messageId` 時應映射回前一視窗對應項目的位置，而非假設 `newFps` 與 `prevItems` 具有相同的連續下標。

---

### 5. 過期重播（Stale Replay）導致檢查點視窗倒退收縮，引發後續訊息重複
- **檔案與行號**：
  - [`Reconciler.kt:L133-L140`](../../../core/reconcile/src/main/kotlin/dev/quietinbox/core/reconcile/Reconciler.kt#L133-L140)
  - [`Reconciler.kt:L178`](../../../core/reconcile/src/main/kotlin/dev/quietinbox/core/reconcile/Reconciler.kt#L178)
  - [`IngestRepository.kt:L193`](../../../platform/storage/repo/IngestRepository.kt#L193)
- **問題原因**：
  假設已收到通知 `[A, B, C]`，檢查點為 `[A, B, C]`。
  若隨後收到一則舊通知重播 `[A]`：
  1. `containedAt(prevFps, newFps)` 匹配成功，`stale = true`，標記 `STALE_REPLAY`。
  2. 但在行 178：
     ```kotlin
     val window = MessageWindow(notificationKey, newItems.takeLast(maxWindow), closed = false)
     ```
     `newItems` 僅由本次傳入的候選訊息（即 `[A]`）組成！
  3. `IngestRepository.commit` 將這個只有 `[A]` 的視窗存入 `CheckpointEntity`。
  4. 隨後真正的下一筆通知 `[B, C, D]` 到達時，拿到的前一視窗是 `[A]`。`suffixPrefixOverlap([A], [B, C, D]) == 0`，`containedAt` 亦為 -1！
  5. 此時 `B` 和 `C` 被判定為 `Decision.New`！
  6. 且在 `IngestRepository.kt:L193` 的指紋保護：
     `if (decision is Decision.New && !decision.confirmedById && ReconcileNote.NO_PREVIOUS_WINDOW in reconcile.notes)`
     因為此時 `previous` 不是 null（內含 `[A]`），`NO_PREVIOUS_WINDOW` 不在 notes 內，保護 **完全不觸發**！`B` 與 `C` 被再次寫入資料庫造成重複！
- **具體修復方案**：
  當檢測到 `ReconcileNote.STALE_REPLAY in notes`（且新視窗完全包含於舊視窗內、未有新訊息加入）時，檢查點視窗應保留原有的 `previous` 視窗（或合併視窗），不應將檢查點覆寫回退為縮小的舊視窗。

---

### 6. `CaptureCoordinator.setPaused(true)` 未輪替 Generation，無法阻擋佇列中的已排隊事件入庫
- **檔案與行號**：
  - [`CaptureCoordinator.kt:L181-L200`](../../../platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L181-L200)
  - [`CaptureCoordinator.kt:L247-L251`](../../../platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L247-L251)
- **問題原因**：
  規範 §5 要求：「撤權／暫停／刪除來源時切換 generation、取消媒體工作、關閉接受新事件；已排隊工作提交前再檢查，防止撤權後繼續落盤。」
  代碼註釋亦寫明：`// Commit fence: anything queued before a revoke/pause is discarded, never persisted.`
  但是在 `setPaused(true)` 中，代碼僅執行 `paused = true`，**完全沒有重設或輪替 `activeGeneration`**！
  而在 `process(item: Queued)` 中，檢查條件是：
  ```kotlin
  if (item.generation != activeGeneration) {
      _status.update { it.copy(droppedAfterRevoke = it.droppedAfterRevoke + 1) }
      return
  }
  ```
  在使用者點擊暫停時，已經在 `queue` 中的事件其 `item.generation` 仍然等於 `activeGeneration`，且 `process` 未檢查 `if (paused)`，因此這些事件仍會正常寫入 EventJournal 並提交到資料庫。
- **具體修復方案**：
  在 `setPaused(true)` 時將 `activeGeneration` 設為 null 或輪替為新的隨機 UUID；在 `setPaused(false)` 且 Listener 處於連線狀態時再產生新的 generation。同時在 `process(item)` 處增加 `if (paused || item.generation != activeGeneration)` 檢查。

---

## 次要問題與改進建議（Minor / Nitpicks）

1. **`BackupService.writeRecords` 缺少唯讀交易包裝**：
   - 檔案：[`BackupService.kt:L100-L106`](../../../platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L100-L106)
   - 註釋註明 `// Snapshot inside one read transaction so counts and rows agree.`，但未包裹 `db.withTransaction { ... }`，五個 DAO 查詢分別獨立執行。若匯出時有通知併發入庫，Manifest 計數與實際行數可能不一致。建議補上 `db.withTransaction`。
2. **`SnapshotFactory.pictureUri` 在 Android 12 (API 31/32) 上的相容性問題**：
   - 檔案：[`SnapshotFactory.kt:L156-L161`](../../../platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/SnapshotFactory.kt#L156-L161)
   - 代碼：`extras.getParcelable(Notification.EXTRA_PICTURE_ICON, Icon::class.java)`
   - `Bundle.getParcelable(String, Class<T>)` 是 Android 13 (API 33) 新增 API。在 API 31 和 32 上執行時會拋出 `NoSuchMethodError`（被外層 `runCatching` 吞掉），導致在 Android 12 上永遠無法讀取圖示 URI。
   - 建議改用 `androidx.core.os.BundleCompat.getParcelable(extras, Notification.EXTRA_PICTURE_ICON, Icon::class.java)`。
3. **`MainNavigation.kt` 使用廢棄之 `currentWindowAdaptiveInfo()`**：
   - 檔案：[`MainNavigation.kt:L76`](../../../app/src/main/kotlin/dev/quietinbox/ui/MainNavigation.kt#L76)
   - 編譯時期警告：建議改用支援 L 與 XL 寬度斷點的 V2 API。
4. **`CaptureCoordinator.replayJournal()` 批次上限未設迴圈**：
   - 檔案：[`CaptureCoordinator.kt:L310-L321`](../../../platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L310-L321)
   - `ingest.pendingJournal(limit = 200)` 單次最多只讀 200 筆。若在鎖庫期間積壓超過 200 筆事件，只會重播前 200 筆。建議以 `while (true)` 迴圈批次處理至佇列清空。

---

## 驗證為正確之關鍵設計（Other Observations）
以下為經過仔細驗證、符合規格且實作嚴謹的模組，讀者或後續維護者可放心：

1. **無網路權限與隱私邊界**：
   - `app/src/main/AndroidManifest.xml` 中以 `tools:node="remove"` 明確移除 `INTERNET` 與 `ACCESS_NETWORK_STATE`。
   - 經實測執行 `./gradlew assembleRelease` 並透過 `tools/check-permissions.sh` 檢驗生成的 `app-release-unsigned.apk`，完全不含任何聯網權限與 `QUERY_ALL_PACKAGES`。
   - 程式碼中全面無 `Log.` 輸出，無任何標題、內文、發送者名稱或 URI 滲漏至日誌或診斷匯出。
2. **多語系資源字串 100% 對齊**：
   - 檢驗 `values/strings.xml` 與 `values-b+zh+Hant/strings.xml`（296 條字串）、`plurals.xml`（2 條複數字串），兩者鍵值完全 1:1 對應，無遺漏或缺鍵。
3. **無障礙色彩與狀態展示**：
   - 所有資料品質標籤（`identityLabel`、`mediaLabel`、`QualityTag`）皆嚴格落實「文字 + 圖示 + 色彩」三重指示，絕無純靠顏色辨別狀態之情事。
4. **金鑰生命週期與 SQLCipher 連線池**：
   - `DatabaseHolder` 中明智地保留了傳給 `SupportOpenHelperFactory` 的金鑰位元組陣列，避免清除陣列導致 WAL 連線池的 Readers 無法開啟連線。
   - `KeystoreWrapper` 明確設定 `.setUserAuthenticationRequired(false)`，確保螢幕鎖定期間 NLS 仍可持續在背景執行加密入庫。
5. **R8 / ProGuard 建置完整性**：
   - Release 建置成功通過 R8 混淆與資源縮減（ShrinkResources），所有 Room Entity、DAO、Kotlinx Serialization DTO 及 SQLCipher Native 庫均未遭錯誤剔除。

---
*報告產生時間：2026-09-06*
