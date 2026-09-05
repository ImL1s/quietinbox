# QuietInbox v0.1 垂直切片 — 獨立唯讀 Code Review（subagent）

## 重要前提：審查期間程式碼被修改過

本次審查的時間軸必須先講清楚，否則下面的內容會被誤讀：

1. 我依 brief 從 commit `2aa6a62`（`fix: dangling capture session detection, recovery key off main thread, list-detail placeholder`）對應的工作區狀態開始逐檔閱讀，找出 7 項 Critical、13 項 Important。
2. **在我撰寫報告的同時，工作區出現了大量未提交的修改**（21 個檔案、+431/−169，另新增 `schemas/.../2.json` 與 `MigrationTest.kt`）。工具回報「file changed on disk」後我立即以 `git diff` 逐項重新驗證。
3. **這批未提交的修改把我找到的 7 項 Critical 全部修掉了**，並涵蓋大部分 Important。

因此本報告分成三段：**(A) 針對 commit `2aa6a62` 的原始發現**（已被工作區修正，保留下來供追溯與 commit message 參考）、**(B) 對現行工作區的重新驗證結果**、**(C) 現行工作區仍然開放或新引入的問題**。

- 審查範圍與誠實聲明：我**沒有**直接閱讀 `/Users/iml1s/Downloads/QuietInbox_開源專案完整計劃.md` 全文，只針對 C1 查證了 §149 的原文；其餘以 brief 列出的九條硬性規則為契約。`StandardParser` / `AppParser` 由 `parsers-apps` 同伴審查，我未涵蓋。各 Compose 畫面只掃讀未逐行追蹤。作者宣稱的 74 個 JVM 測試我只跑了獲准的兩個模組。
- 我執行過的驗證：`:core:reconcile:test :core:parser:test` 在修改前後各跑一次，**兩次都全綠**（修改後：reconcile 11 + 1 property + 3 新增 = 15，parser 10，exit 0）；檢視 `app/build/outputs/mapping/release/configuration.txt`、KSP 產生的 `QuietInboxDatabase_Impl.kt`、`platform/storage/schemas/.../1.json` 與新增的 `2.json`。
- 未執行：instrumented tests、裝置安裝、任何 git 寫入。除本報告外未修改任何檔案。

---

## Verdict

**REQUEST CHANGES**

不是因為 (A) 段的問題還在——它們都被修好了——而是因為現行工作區的狀態本身還不該 push：

1. **`MIGRATION_1_2` 直接 `DROP TABLE deletion_suppression`**，等於把使用者近 30 天內刪除的抑制權杖全部清空。這正好回退了 C3 所要保護的硬性規則 5：升級後的第一次 active-notification replay 就可能把使用者刪掉的內容救回來。而這些權杖是可以用 JOIN `conversation` 重算的，沒有理由丟棄。
2. **`Reconciler` 被實質重寫**（對齊邏輯從「只對 id-less 項目」改成「對整個 window，id 項目再覆寫」），這是整個產品風險最高、規格條文最細的一段邏輯，而且是在 review 之後才改的，**尚未經過任何獨立審查**。新增 3 個測試不足以覆蓋一次演算法換代。
3. **`System.loadLibrary("sqlcipher")` 的失敗仍會讓行程崩潰**，而不是進入 `Locked`（見 C-新1）。SCOPE 明列 16 KB page size 尚未驗證，這正是最可能觸發 native load 失敗的情境。
4. schema 已從 v1 跳到 v2，對應的 `MigrationTest` 是 instrumented test，本次**未執行也不允許執行**，等於這條升級路徑目前零驗證。

建議：把工作區的修正拆成可審查的 commit、補上 (C) 段的四項、在裝置上跑過 `MigrationTest` 與備份 round-trip 之後再 push。

統計：(A) 原始 Critical 7 / Important 13 / Minor 8；(C) 現行工作區仍開放 4 項（含 1 項 Critical 等級）+ 3 項 Minor。

---

# (B) 重新驗證結果總表（對現行工作區）

| # | (A) 段發現 | 現況 | 修正處 |
| --- | --- | --- | --- |
| C1 | pause 不是 commit fence | **已修** | `CaptureCoordinator.kt` `setPaused` 現在會輪換 `activeGeneration`；`process()` 另加 `paused` 與 `enabledPackages` 二次驗證 |
| C2 | `db()` 在 Opening→Locked 永久掛起 | **已修** | `DatabaseHolder.db()` 改為 `_state.first { it !is Opening }` 並在 Locked 丟例外 |
| C3 | 刪除對話後抑制權杖失效 | **已修** | `deletion_suppression` 主鍵改為 `scopeKey`（`scope.key + "#" + identityKey`），schema 升到 v2 |
| C4 | 匯入媒體被 retention 回收 | **已修** | 先 insert message 取得 `newId`，blob 以 `messageId = newId` 寫入，再 `setMedia` 回填 |
| C5 | 匯入以 fingerprint 去重造成資料遺失 | **已修** | 改為迴圈外一次算出 `preExisting`，鍵為 `fingerprint\|sortKey\|observedAtEpochMs`，且只比對匯入前既有的資料 |
| C6 | replay 與線上消費者競態 | **已修** | 新增 `pipelineMutex`，`process()` 與 `replayJournal()` 共用 |
| C7 | ObservationLink FK 違反造成整批回滾 | **已修** | `Decision.Known` / `AmbiguousRepeat` 插入 link 前先 `messageDao().get(id) != null`；另新增 `markJournalRetryable`，失敗的 journal 保持 PENDING 最多 3 次才轉 FAILED |
| I1 | 金庫上鎖無 gap 紀錄 | **已修** | `vaultGapOpen` 旗標 + `openGap` / `closeOpenGaps` |
| I2 | 金庫錯誤後 UI flow 終止 | **已修** | `flowWithDb` 改為 `_state.flatMapLatest { ... }`，retry 後自動重新掛載 |
| I3 | export 宣稱有交易卻沒有 | **已修** | 五次查詢包進 `db.withTransaction { Snapshot(...) }`；被丟棄的 orphan 訊息也改為寫入 `RESTORE_ORPHAN_MESSAGES` 診斷 |
| I4 | 匯入 O(n²) | **已修** | 同 C5 |
| I5 | 佇列持有 512 個 bitmap、OOM 殺死消費者 | **已修** | `SnapshotFactory` 加 4 MB 上限，`MAX_QUEUED_BITMAPS = 8`；消費者迴圈外層改 `catch (t: Throwable)` 並自我重啟 |
| I6 | 金鑰檔無 fsync | **已修** | `writeAtomically` 改為 `FileOutputStream + fd.sync()`，rename 後 fsync 目錄，並移除「就地覆寫」的 fallback |
| I7 | `onConnected` 兩個 coroutine 順序無保證 | **已修** | 合併為單一 coroutine |
| I8 | `sessionId` 非 volatile | **已修** | 加上 `@Volatile` |
| I9 | `closeOpenGap` 只關最新一筆 | **已修** | 改為 `openGaps(reasons)` / `closeOpenGaps(now, vararg reasons)`，按原因精準關閉 |
| I10 | `readLine()` 先配置再檢查長度 | **已修** | 新增 `readBoundedLine`，超過上限即丟 `TOO_LARGE` |
| I11 | 匯出失敗刪除使用者檔案 | **部分修正** | 改為 truncate 而非 delete，見 (C) 段 |
| I12 | JSON 解析錯誤歸類為 IO | **已修** | 新增 `Reason.CORRUPT` 與專用 catch |
| I13 | `deleteEverything` 後可能掛起 | **已修** | 隨 C2 一併解決 |
| Minor 1 | CI 未跑 `:app:test` | **已修** | 加入 `:app:testDebugUnitTest` |
| Minor 3 | `localeFilters` 含不存在的 `zh-rTW` | **已修** | 已移除 |
| Minor 4 | `"$preset h"` 寫死單位 | **已修** | 改走 string resource（`strings.xml` 兩個語系各 +2 筆，parity 維持） |
| Minor 5 | `orphans()` 用 `NOT IN` 全表掃描 | **已修** | 改為 `LEFT JOIN ... WHERE m.id IS NULL` |
| Minor 2 / 6 / 7 / 8 | 文件計數敘述、重複 `settings.current()`、`QualityTag` 對比、`RecoveryKeyCodec` 註解 | 未動 | 影響輕微，可留待後續 |

---

# (C) 現行工作區仍然開放 / 新引入的問題

### C-新1（Critical 等級）`System.loadLibrary` 失敗會讓行程崩潰，而不是進入 Locked

`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/DatabaseHolder.kt:88-105`

```kotlin
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)   // :46，沒有 CoroutineExceptionHandler
...
try {
    System.loadLibrary("sqlcipher")      // :95
    ...
} catch (e: Exception) {                 // :103
    _state.value = VaultState.Locked(KeyFailure.Unavailable("open:${e::class.java.simpleName}"))
}
```

`System.loadLibrary` 失敗丟的是 `UnsatisfiedLinkError`，屬於 `Error` 而非 `Exception`，`catch (e: Exception)` 攔不到。`open()` 是在 `init` 的 `scope.launch` 裡跑的，而這個 scope **沒有掛 `CoroutineExceptionHandler`**（對照 `CaptureCoordinator.kt:76` 就有 `crashGuard`），因此未捕捉的 `Error` 會走到預設處理器 → **App 每次啟動即崩潰**，使用者連 Health 頁的「重試 / 重設」都看不到。

這不是理論問題：`docs/SCOPE.md` 明列「16 KB page-size 驗證」尚未執行，而 16 KB page size 裝置正是 native library 載入失敗最典型的情境；`jniLibs.useLegacyPackaging = false`（`app/build.gradle.kts:50`）也讓解壓與載入行為依賴平台。此外 `.addMigrations(*MIGRATIONS)` 現在也在同一個 try 內，遷移若丟出 `Error` 同樣不被攔。

**修法**：改成 `catch (t: Throwable)`，並替 `scope` 補上 `CoroutineExceptionHandler`，讓任何開啟失敗都收斂成 `VaultState.Locked` — 這正是 C2 修正想達成的語意（「Locked 一定要能被觀察到」），現在只差這一半。

### C-新2（Important）`MIGRATION_1_2` 丟棄全部抑制權杖，等於回退硬性規則 5

`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/QuietInboxDatabase.kt:56-66`

```kotlin
override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("DROP TABLE IF EXISTS deletion_suppression")
    db.execSQL("CREATE TABLE IF NOT EXISTS deletion_suppression (scopeKey TEXT NOT NULL, ...)")
    ...
}
```

註解說「The table only holds short-lived, body-free tokens, so it is recreated; no user content is touched.」——「沒有動到使用者內容」是對的，但**權杖存在的唯一目的就是阻止使用者已刪內容被 replay 救回來**，TTL 是 30 天（`ConversationViewModel.kt:113`、`InboxViewModel.kt:114`）。丟掉它們之後，升級後第一次 `onConnected` 的 active-resync（`captureActiveOnConnect` 預設為 true，`SettingsRepository.kt:39`）就可能把使用者最近 30 天刪除的訊息重新寫入。這與 C3 修正的目的直接矛盾。

而且舊資料**完全可以換算**：`scopeKey = SourceScope.key + "#" + identityKey`，四個欄位都在 `conversation` 表裡。

**修法**：用 `INSERT ... SELECT` 搬移而非 `DROP`：

```sql
ALTER TABLE deletion_suppression RENAME TO deletion_suppression_old;
CREATE TABLE deletion_suppression (scopeKey TEXT NOT NULL, fingerprint TEXT NOT NULL, expiresAtEpochMs INTEGER NOT NULL, PRIMARY KEY(scopeKey, fingerprint));
INSERT OR REPLACE INTO deletion_suppression (scopeKey, fingerprint, expiresAtEpochMs)
SELECT <依 SourceScope.key 規則拼出的字串> || '#' || c.identityKey, s.fingerprint, s.expiresAtEpochMs
FROM deletion_suppression_old s JOIN conversation c ON c.id = s.conversationId;
DROP TABLE deletion_suppression_old;
```

拼字串的規則必須與 Kotlin 端的 `SourceScope.key` 逐字一致，建議同時在 `MigrationTest` 補一個「刪除 → 升級 → replay 不復活」的案例。目前的 `MigrationTest.kt` 是 instrumented test，本次無法執行，這條路徑等於零驗證。

### C-新3（Important）`Reconciler` 演算法換代後未經審查

`core/reconcile/src/main/kotlin/dev/quietinbox/core/reconcile/Reconciler.kt`（+138/−? 大幅改寫）

原本是「先跑 id 路徑 → 剩下的 id-less 項目再做 window 對齊」，現在改成「先對整個 window 做位置對齊 → 有 `sourceMessageId` 的項目再覆寫位置判定」，另外新增 `ReconcileNote.WINDOW_KEPT`：當這一批沒有任何新增時，保留較長的舊 window 而不是縮短它。

方向上我認同（舊版把 id 項目排除在對齊之外，確實會讓位置漂移），但這是全專案語意最密、規格條文最細的一段（plan §7.2 的六個範例都是它的驗收條件），而且是在 review 之後才換掉的。有幾個點需要專門確認：

- 新的 `singleIdentical` 多了 `c.sourceMessageId == null` 條件（`:130` 附近），與舊版由 `idless` 集合隱含保證的行為是否等價？
- `WINDOW_KEPT` 分支保留舊 window 時把 `decisionIndex` 全部設為 null（`prevItems.map { it.copy(decisionIndex = null) }`），`IngestRepository` 的新對映 `item.decisionIndex?.let { storedIds[it] } ?: item.messageId` 因此會退回舊 id——這是對的，但這條路徑沒有測試覆蓋。
- `addsNothing && prevItems.size > fps.size` 的條件下，`notificationKey` 換成新的但 window 內容沿用舊的，之後 `samePost` 的判定會如何？

**修法**：這段應該獨立走一次 review + property test 的種子數從 1,000 拉高，並補上針對 `WINDOW_KEPT` 與「id 與 id-less 混合」的案例。目前 `ReconcilerTest` 只增加了 3 個測試。

### C-新4（Minor→Important）匯出失敗仍會清空使用者選定的檔案

`platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:87-90`

```kotlin
} catch (e: Exception) {
    // Never delete a document the user chose (it may pre-exist); truncate the partial output.
    runCatching { context.contentResolver.openOutputStream(target, "wt")?.close() }
```

比原本的 `delete()` 好，但 `"wt"` 就是 truncate：若使用者在 SAF 選了一個既有檔案（例如上一份備份）當覆寫目標，任何 IO 例外之後那個檔案會變成 0 byte，舊備份一樣沒了。正確做法是寫到暫存 URI / 暫存檔成功後才覆蓋，或至少在失敗訊息中明確告知「原檔案已被覆寫」。

### 其他較小的新問題

1. **`onConnected` 在暫停狀態下回報矛盾的 generation。** `CaptureCoordinator.kt:161-168`：欄位設為 `activeGeneration = if (paused) null else generation`，但緊接著 `_status.update { it.copy(activeGeneration = generation) }` 無條件公布 `generation`。Health 頁與 `diagnosticsSummary()` 會顯示一個實際上並未生效的世代權杖。
2. **`setPaused(false)` 產生的新 generation 沒有對應的 capture session。** `CaptureCoordinator.kt:219-222` 直接 `activeGeneration = UUID.randomUUID().toString()`，但沒有呼叫 `health.startSession(...)`；`capture_session` 表因此不會有這個世代的紀錄，Health 頁的工作階段清單與實際擷取世代對不起來。
3. **`replayJournal` 持鎖時間過長會餓死線上擷取。** `CaptureCoordinator.kt:371-388`：每一輪都在 `pipelineMutex.withLock` 內處理整批（最多 200 筆）journal，最多 100 輪。長時間鎖庫之後的首次解鎖，可能讓線上消費者被擋住夠久而觸發 `QUEUE_OVERFLOW`（佇列 512）。建議縮小每次持鎖的粒度（逐筆取鎖），或在輪與輪之間 `yield()`。

---

# (A) 針對 commit `2aa6a62` 的原始發現（已被工作區修正，保留供追溯）

以下七項在我開始審查時的工作區確實成立，我逐一追蹤過程式碼路徑；現在都已修好，內容保留下來是為了讓修正 commit 有可引用的理由，以及讓後續回歸測試知道該測什麼。

### C1. `pause` 不是 commit fence — 違反硬性規則 4

`CaptureCoordinator.kt:181`（`setPaused`）、`:248`（fence 判斷）。`setPaused(true)` 只設 `paused = true`，沒有更動 `activeGeneration`，而 `process()` 的 fence 只比對 generation，註解卻寫著 `revoke/pause`。使用者按下暫停後，佇列中最多 512 筆事件仍會全數落地。同樣地 `process()` 不重驗 `enabledPackages`，停用來源後已排隊的通知照樣寫入。

計畫原文 §149 明確要求：「撤權／暫停／刪除來源時**切換 generation**、取消媒體工作、關閉接受新事件；已排隊工作提交前再檢查」——三個觸發條件裡實作只做到一個。**這是唯一一項我直接對照規格原文確認過的發現。**

### C2. `DatabaseHolder.db()` 在 `Opening → Locked` 時永久掛起

`DatabaseHolder.kt:57-66`。只在進入函式的瞬間是 `Locked` 才丟例外；初始值是 `Opening`，`open()` 在另一個 coroutine 跑。任何在 `open()` 完成前呼叫 `db()` 的 caller，若結果是 `Locked` 就永遠停在 `filterIsInstance<Ready>().first()`，拿不到例外也不會恢復。受害最深的是 `CaptureCoordinator.kt:108` 的單一消費者迴圈：整條 pipeline 停擺、佇列灌滿到 512 後開始 overflow，而唯一的解除途徑 `retry()` 只有使用者手動點擊才會觸發（`HealthViewModel.kt:122`），沒有自動重試。

### C3. 刪除整個對話後抑制權杖永遠比對不到 — 違反硬性規則 5

`InboxRepository.kt:82-90`。`deletion_suppression` 主鍵是 `(conversationId, fingerprint)`，而 schema 確認 `conversation.id` 是 `AUTOINCREMENT`，id 不會重複配發。刪除對話的同時把 conversationId 一併刪掉，下次同一對話被重建時拿到新 id，`isSuppressed` 恆為 0。結果：使用者刪掉整個對話 → 來源 App 重 post 同一則通知 → `findConversationId` 回傳 null → `lookupById` 回傳 null → 所有帶 `sourceMessageId` 的訊息一律判為 `Decision.New(confirmedById = true)` → 已刪內容原封不動被寫回。

### C4. 匯入的媒體在 12 小時內被 retention 當成孤兒刪掉

`BackupService.kt:265` 以 `messageId = null` 寫入 blob 且從未回填，而孤兒定義（`Daos.kt:254`）包含 `messageId IS NULL`，`RetentionWorker.kt:58-63` 每 12 小時清一次。同時 `message.mediaBlobId` 仍指向已刪的列，UI 顯示壞掉的媒體。

### C5. 匯入以 fingerprint 去重，即使匯入到空金庫也會靜默丟掉重複訊息

`BackupService.kt:252-255`。`existingFingerprints` 每輪重新查詢，因此會包含本次迴圈剛插入的訊息。`Fingerprint.of`（`Fingerprint.kt:12-23`）只在 `timestampQuality == SOURCE_MESSAGE` 時才納入時間戳，所以**所有無來源時間戳的重複文字**（BigText / Inbox 樣板同一發送者的重複行）以及**所有 `AMBIGUOUS_REPEAT` 列**（依定義就與原訊息同指紋）都會塌陷成一筆。匯出→匯入到全新裝置不是無損 round-trip，且沒有任何提示。這與 Reconciler 明文保證的「equal items inside one window keep their multiplicity」及「`AMBIGUOUS_REPEAT` stored, linked, never silently dropped」直接矛盾。

### C6. `replayJournal` 與線上消費者競態，產生重複訊息

`CaptureCoordinator.kt:114-119`、`:310-321`。啟動時消費者 coroutine 與 vault.state collector 同時被 `init` 啟動，兩者都停在 `holder.db()`，金庫 Ready 的瞬間一起被喚醒。此時消費者剛 `journal()` 完成、尚未 commit 的事件其 journal 列仍是 PENDING，會被 replay 同時取走。兩條路徑都在**交易外**讀 `checkpoint()` 與 `lookupById()`（`:283-288`），因此讀到同一份舊 window、都判為 `Decision.New`，兩個交易依序寫入 → 同一則訊息插入兩次。`onConnected` 的 active-resync 會放大這個時間窗。

### C7. ObservationLink 的外鍵違反讓整筆 commit 回滾

`IngestRepository.kt:246`、`:259`。已驗證 KSP 產生的 `QuietInboxDatabase_Impl.kt:140` 會執行 `PRAGMA foreign_keys = ON`，且 `observation_link.messageId` 對 `message(id)` 有外鍵。`notification_checkpoint.windowJson` 內的 `mid` 在訊息被刪除後不會更新（使用者刪除或 retention 到期都一樣），因此 `Decision.Known(kind = STALE_WINDOW)` 與 `Decision.AmbiguousRepeat` 都可能拿已刪的 id 去 insert link → `SQLiteConstraintException` → 整筆交易回滾 → 冒泡到 `process()` 的 `catch (e: Exception)` → journal 標記為 `FAILED`，而 `pendingJournal()` 只撈 `PENDING`，**永遠不會被重放**。

精確地說，回滾掉的是：該筆 `AMBIGUOUS_REPEAT` 列本身（違反規則 5 的「stored, never dropped」）、同一批中帶 id 的 `New` 項目，以及 checkpoint 更新——因為 checkpoint 沒更新，過期的 window 會保留下來，**同樣的失敗會在每一次 repost 重演**。`retentionDays` 可低到 1 天（`SettingsRepository.kt:93`）而 checkpoint 要 14 天不更新才清（`RetentionWorker.kt:72`），這個窗口在低保留天數下相當容易命中。

### (A) 段的 Important 與 Minor

見 (B) 段總表的 I1–I13 與 Minor 1–8，每一項的位置與修正狀態都列在表中。

---

# Other observations（已驗證正確，讀者可能會懷疑的部分）

- **`IngestRepository` 原本 `:280` 的索引運算是正確的**（雖然現在已改寫成 `decisionIndex`）。`storedIds[decisions.size - newWindow.items.size + i]` 看似脆弱，但 `Reconciler` 已先把候選截到 `maxWindow`，因此 `newItems.size == decisions.size`，`takeLast(maxWindow)` 是 no-op，偏移量恆為 0。新的 `decisionIndex` 寫法更穩健，但原本並沒有 off-by-one。
- **`BackupService.export` 的非區域 return 不會漏關串流。** `return@withContext` 位於兩層 `use` 之內，Kotlin 的 `use` 是 inline 且帶 `finally`，Tink 的 `newEncryptingStream` 仍會被關閉、EOF 認證段仍會寫出。
- **R8 規則其實是足夠的。** `app/proguard-rules.pro` 只有 SQLCipher 三行，但 `app/build/outputs/mapping/release/configuration.txt` 確認 kotlinx-serialization（第 904 行起）、Tink shaded protobuf（第 1033 行起）、Room（第 819 行起）、DataStore protobuf 的 consumer rules 都由 AAR 自動注入。journal payload 與備份記錄的序列化在 release 建置下不會被破壞。
- **Room 的外鍵確實啟用**：`QuietInboxDatabase_Impl.kt:140` 產生 `PRAGMA foreign_keys = ON`。這是 C7 成立的前提，已實際確認而非推測。
- **零日誌。** 全專案 grep 不到任何 `android.util.Log`、`Log.d/e/w/i/v`、`println`、`printStackTrace`。`HealthViewModel.diagnosticsSummary()` 只含狀態列舉、計數、parser 版本與機型，沒有任何訊息內容。
- **沒有任何會回寫來源通知的呼叫。** grep 不到 `cancelNotification`、`setNotificationsShown`、`snoozeNotification`，也沒有觸發 `contentIntent`/`deleteIntent` 或送出 `RemoteInput`。唯一的 `PendingIntent` 在 `ReminderScheduler.kt:110`，是 App 自己的提醒且用了 `FLAG_IMMUTABLE`。開啟來源 App 走 `getLaunchIntentForPackage`（`ConversationViewModel.kt:106`），註解也誠實說明可能導致來源標記已讀。
- **備份／裝置轉移排除規則完整。** `app/src/main/res/xml/data_extraction_rules.xml` 的 `<cloud-backup>` 與 `<device-transfer>` 兩個區塊都逐一排除 `root/file/database/sharedpref/external/device_*`，並加了 `disableIfNoEncryptionCapabilities="true"`。這點很重要，因為 `allowBackup="false"` 在 API 31+ 並不涵蓋裝置對裝置轉移——這裡處理對了。
- **自我迴圈防護正確。** `isCapturable` 對自身套件只接受帶 `EXTRA_SYNTHETIC` 的通知；提醒通知不帶該 extra，不會被自己捕捉。
- **字串雙語齊備。** `core/designsystem` 的 `values/` 與 `values-b+zh+Hant/` 逐一 diff 完全一致（修改前 296/296，修改後兩邊同步各 +2）；`platform/capture` 各 1 筆亦一致。
- **Schema 已匯出並提交**，`exportSchema = true`，v1 與新增的 v2 JSON 都在版控中，全專案沒有任何 `fallbackToDestructiveMigration`。
- **金鑰未綁定使用者驗證**：`KeystoreWrapper.kt:81` 明確 `setUserAuthenticationRequired(false)`，符合「listener 需在鎖定狀態下寫入」的要求；UI 鎖是純 UI 閘門，不影響加密。
- **`DatabaseHolder` 刻意不清零 SQLCipher 金鑰陣列**並附註原因（WAL pooled connection 會重用該陣列）。這個取捨是對的，清零反而會讓額外連線開啟失敗。
- **統計只描述觀測資料。** `ActivityReport` 沒有回覆率、已讀率或召回率，每個數字都附帶 `sampleSize`、`ambiguousCount`、`summaryOnlyCount`、時區與區間。
- **`tools/check-permissions.sh` 比計畫要求更嚴**，除 INTERNET 外也擋 `ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_NETWORK_STATE` 與 `QUERY_ALL_PACKAGES`，且已接進 CI 的 assemble job。
- **`RecoveryKeyCodec` 的 52+4 編碼可正確 round-trip**：32 bytes → ⌈256/5⌉ = 52 個 Crockford Base32 字元，解碼時多餘的 4 bit 被丟棄，`DATA_CHARS` 與實際輸出長度一致。
- **`accountKey` 目前恆為 null**（`SnapshotFactory.kt:131`），因此硬性規則 6 的「不跨帳號合併」目前完全仰賴 stream / shortcut key 的區隔。這在單帳號情境下沒問題，但多帳號來源上線前需要補上真正的 accountKey 來源。
- **JVM 測試在修改前後都全綠**：修改前 reconcile 11+1、parser 10；修改後 reconcile 11+1+3、parser 10，兩次 exit code 皆為 0。
