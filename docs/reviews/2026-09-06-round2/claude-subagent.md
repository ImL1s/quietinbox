# QuietInbox 第二輪 Code Review（獨立唯讀 subagent）

審查對象：`git diff 3ef8fb8..8050e05`（37 檔，+1883/−215），置於整個 codebase 的脈絡中。
審查依據：`.omc/research/dual-review-round2-brief-safe.md`、round-1 三份報告（agy / subagent / fable）、brief 的九條硬性產品規則。

## 執行過的驗證

- `./gradlew :core:reconcile:test :core:model:test --rerun-tasks` → **BUILD SUCCESSFUL, exit 0**。第一次跑是 `UP-TO-DATE`（沒有真的執行），加 `--rerun-tasks` 後由 JUnit XML 逐檔確認：

  | 測試類別 | tests | failures | errors |
  | --- | --- | --- | --- |
  | ReconcilerTest | 11 | 0 | 0 |
  | ReconcilerReviewFixesTest | 3 | 0 | 0 |
  | ReconcilerResyncTest | 1 | 0 | 0 |
  | ReconcilerWindowKeptTest | 1 | 0 | 0 |
  | ReconcilerPropertyTest | 1 | 0 | 0 |
  | SearchNormalizerTest | 5 | 0 | 0 |

- **自建 JVM harness 實際執行 `Reconciler`**：把 `core/reconcile/build/libs/reconcile.jar` 與 `core/model/build/libs/model.jar` 掛上 classpath，用 Java 直接呼叫 `Reconciler.reconcile(...)` 跑了 13 個情境。Harness 檔案放在 session scratchpad，**沒有寫進 repo**。下面標示「harness 實測」的結論都是執行結果，不是推論。
- 實測 `FileInputStream(File(目錄))` 在本機 JVM 的行為（見新發現 2）。
- 用 Python 讀 `platform/storage/schemas/.../2.json`，逐欄比對 `MIGRATION_1_2` 產出的 schema。
- 未執行：instrumented tests（`MigrationTest`、`VaultRoundTripTest`）、裝置安裝、任何 git 寫入。除本報告外未修改任何檔案。

## Verdict

**REQUEST CHANGES**

理由集中在三點，都不是「round-1 的問題沒修」——round-1 的 Critical/Important 絕大多數是真的修好了，而且修得比我預期紮實：

1. **新發現 1（Important）**：`WINDOW_KEPT` 這個為了修 agy round-1 #5 而加的保護，只涵蓋 `STALE_REPLAY`，沒有涵蓋 `AmbiguousRepeat`。同一個 notification key、window 已 closed、新的 postTime、單則內容等於舊 window 尾端時，checkpoint 會從 3 筆縮成 1 筆，下一則多筆通知就把已存在的訊息重新寫入一次。**harness 實測復現**。
2. **新發現 3（Important）**：`docs/adr/0004-identity-and-dedup.md` 在這個 commit 裡新增了一句「`[A]` after `[A,B,C]` cannot make the next `[B,C,D]` duplicate B and C」，而新發現 1 正好是這句話的反例。文件宣稱了程式碼做不到的事，違反 docs-honesty。
3. **round-1 C-新4 在本 commit 尚未修好**：`export` 仍以 `openOutputStream(target, "wt")` 開啟使用者選定的文件，**在開檔當下就 truncate**；catch 區塊的 re-truncate 只是補刀，不是原因。SCOPE.md 卻已寫上「never deletes the user's target document」。

**重要背景**：工作區目前有 5 個未提交檔案，其中 `BackupService.kt` 的 export 已被改寫成「先寫 cacheDir 暫存檔、完成後才 copy 到 SAF target」。**那個改法是正確的**（`FileOutputStream.use` 與 `newEncryptingStream.use` 都在 copy 之前關閉，EOF 認證段確定寫得出來），也真正修掉了 C-新4，但它**不在 `8050e05` 裡**。若現在 push `8050e05`，送出的是還沒修的版本。這點必須讓作者知道。

統計：新 Critical **0** / 新 Important **3** / 新 Minor **10**。

---

# 一、Round-1 發現的修復驗證表

## (A) 三份報告的 Critical

| 來源 / 編號 | 內容 | 結論 | 位置 |
| --- | --- | --- | --- |
| subagent C1 / agy #6 / fable C2 | pause 不是 commit fence | **已修** | `CaptureCoordinator.kt:223-257` `setPaused` 輪換 generation（pause→null，resume→新 UUID）；`:328` `process()` 另加 `paused` 與 `stillCapturable` 二次驗證 |
| subagent C2 / agy #1 / fable I1 | `db()` 在 Opening→Locked 永久掛起 | **已修** | `DatabaseHolder.kt:63` 改為 `_state.first { it !is Opening }`，Locked 丟 `VaultUnavailableException` |
| subagent C3 / agy #3 / fable C1 | 刪除整個對話後抑制權杖失效 | **已修** | `Entities.kt:196` 主鍵改 `(scopeKey, fingerprint)`；`InboxRepository.kt:101-104` `suppressionScopeKey()`；`IngestRepository.kt:192, 214` 一致使用 |
| subagent C4 / agy #2 / fable C3 | 匯入媒體被 retention 當孤兒刪除 | **已修** | `BackupService.kt:309-324` 先 insert message 取 `newId`，blob 以 `messageId = newId` 寫入，再 `setMedia` 回填；`Daos.kt:260` `orphans()` 改 `LEFT JOIN`，不再把 `messageId IS NULL` 一律視為孤兒 |
| subagent C5 / fable Minor 8 | 匯入以 fingerprint 去重造成資料遺失 | **已修** | `BackupService.kt:288-291` 迴圈外一次算出 `preExisting`，鍵為 `fingerprint\|sortKey\|observedAtEpochMs`，且只含匯入前既有列 |
| subagent C6 / fable I5 | replay 與線上消費者競態 | **已修** | `CaptureCoordinator.kt:122` `pipelineMutex`；`:332` 與 `:413` 共用；`:414` 鎖內 re-check `isJournalPending` 阻止重複處理 |
| subagent C7 | ObservationLink FK 違反造成整批回滾 | **已修** | `IngestRepository.kt:273, 285` 插 link 前先 `messageDao().get(id) != null`；`:100-104` `markJournalRetryable` 讓失敗事件維持 PENDING 最多 3 次 |
| subagent C-新1 | `System.loadLibrary` 的 `UnsatisfiedLinkError` 讓行程崩潰 | **已修** | `DatabaseHolder.kt:110` 改 `catch (t: Throwable)`；`:48-51` scope 補上 `CoroutineExceptionHandler` |
| subagent C-新2 | `MIGRATION_1_2` 直接 `DROP TABLE` 丟棄全部抑制權杖 | **已修，且我逐字驗過 SQL** | 見下方「特別查證：MIGRATION_1_2」 |
| subagent C-新3 | `Reconciler` 演算法換代後未經審查 | **本輪已審**，見第二節 | — |
| subagent C-新4 | 匯出失敗清空使用者選定的檔案 | **未修（在 `8050e05`）／已在未提交工作區修好** | `BackupService.kt:78` 仍是 `openOutputStream(target, "wt")` |

## (B) Important / Minor

| 來源 / 編號 | 內容 | 結論 | 位置 |
| --- | --- | --- | --- |
| agy #4 / fable Minor 1 | 混合 id 與無 id 時視窗對齊錯位 | **已修** | `Reconciler.kt:125` 對整個 window 做 `suffixPrefixOverlap`，`:159-171` id 只作為覆寫；`ReconcilerReviewFixesTest` 有專門案例，harness 實測 `[A, id1, B] → [A, id1, B, C]` 得到 `Known/Known(SAME_ID)/Known/New`，id 對應正確 |
| agy #5 | stale replay 讓 checkpoint 倒退收縮 | **部分修正** | `Reconciler.kt:177-180` `WINDOW_KEPT` 修好 `STALE_REPLAY` 路徑（harness 實測 `[A,B,C]→[A]→[B,C,D]` 為 `Known/Known/New`），但 `AmbiguousRepeat` 路徑沒涵蓋 → **新發現 1** |
| fable I2 | 冷啟動 `enabledPackages` 未載入前事件被靜默丟棄 | **已修**（有取捨） | `CaptureCoordinator.kt:292` `sourcesLoaded` 旗標；`:334-338` `process()` 首次同步載入來源清單。取捨見 Minor 4 |
| fable I3 | reconnect resync 產生假的 `AMBIGUOUS_REPEAT`，且與 `closeAllWindows` 競態 | **已修** | `Reconciler.kt:140-141` `samePost` 納入 `postedAtEpochMs` 比對；`CaptureCoordinator.kt:179-188` 關窗與 resync 合併成單一 coroutine。harness 實測：同 postTime → `Known(REPOST)`，不同 postTime → `AmbiguousRepeat`，符合設計 |
| fable I4 | commit 層的 checkpoint-loss guard 壓掉同窗 multiplicity | **部分修正** | `IngestRepository.kt:195-202` `preExisting` 改為迴圈外預查，全新對話的 `[好, 好]` 已能存兩筆；但同批兩則相同 fingerprint 遇到一筆既有列時仍雙雙映射到同一個 id → 見 Minor 5 |
| fable I6 | UI 鎖冷啟動 bypass | **已修** | `LockController.kt:32` `_locked` 改 `MutableStateFlow<Boolean?>(null)`；`:46-50` 設定載入後才決定；`QuietInboxApp.kt:90` `s == null \|\| locked == null` 顯示 LoadingScreen |
| fable I7 | 4+ 字母拉丁子字串與單一 CJK 查詢必然 0 結果 | **已修** | `Normalization.kt:31-43` 新增 `queryTokens`（拉丁 ≥3 只取 3-gram、CJK 單字取單字）；`SearchRepository.kt:30` 改用 `queryTokens`；`SearchNormalizerTest` 加了「query tokens ⊆ index tokens」的性質測試，5 測全綠 |
| fable I8 / subagent I6 | 金鑰檔無 fsync | **部分修正** | `WrappedSecretFile.kt:68-71` 資料 fsync 有效、非原子 fallback 已移除；`:76` 的目錄 fsync **必然失敗且被吞掉** → **新發現 2** |
| fable Minor 6 | KEK 遺失被誤報為 `Tampered` | **已修** | `KeystoreWrapper.kt:56` `existingKey() ?: throw KeyPermanentlyInvalidatedException()`，映射為 `Invalidated`，unwrap 不再隱式建 key |
| fable Minor 4 | `MediaCopier` 吞掉 `CancellationException` | **已修** | `MediaCopier.kt:80-83` timeout 單獨接、`CancellationException` rethrow。但同一 commit 沒有把這個修法套到 `CaptureCoordinator` → 見 Minor 6 |
| agy Minor 2 | `getParcelable(String, Class)` 在 API 31/32 丟 `NoSuchMethodError` | **已修** | `SnapshotFactory.kt:167` 改用 `BundleCompat.getParcelable` |
| agy Minor 3 | `currentWindowAdaptiveInfo()` 已廢棄 | **已修** | `MainNavigation.kt:76` 改 `currentWindowAdaptiveInfoV2()` |
| agy Minor 4 / subagent I10 | replay 單次只讀 200 筆、`readLine()` 先配置再檢查 | **已修** | `CaptureCoordinator.kt:405` 批次迴圈至清空（上限 100 輪）；`BackupService.kt:247-257` `readBoundedLine` 邊讀邊檢查 |
| subagent I1 / I2 / I3 / I5 / I9 / I12 / I13 | 金庫 gap 紀錄、UI flow 終止、export 交易、bitmap OOM、`closeOpenGap` 只關最新一筆、JSON 錯誤歸類 | **全部已修** | `CaptureCoordinator.kt:127, 152-158, 346-349`；`DatabaseHolder.kt:74-75` `flatMapLatest`；`BackupService.kt:108-116` `withTransaction`；`SnapshotFactory.kt:38, 89` 4 MB 上限 + `MAX_QUEUED_BITMAPS = 8`；`HealthRepository.kt:55-59` `closeOpenGaps(vararg reasons)`；`BackupResult.Reason.CORRUPT` |
| subagent I7 / I8 | `onConnected` 兩 coroutine 順序、`sessionId` 非 volatile | **已修** | `CaptureCoordinator.kt:179`、`:118-119` |
| subagent 其他 1 | `onConnected` 在暫停狀態回報矛盾的 generation | **已修** | `CaptureCoordinator.kt:169, 174` 兩處都是 `if (paused) null else generation` |
| subagent 其他 2 | `setPaused(false)` 的新 generation 沒有 capture session | **已修** | `CaptureCoordinator.kt:235-237` resume 時 `health.startSession(resumedGeneration, ...)` |
| subagent 其他 3 | `replayJournal` 持鎖過長餓死線上擷取 | **已修** | `CaptureCoordinator.kt:413` 改成「每筆事件各自取鎖」，`pendingJournal()` 的查詢也移到鎖外 |
| subagent Minor 1 / 3 / 4 / 5 | CI 未跑 `:app:test`、`zh-rTW`、寫死「h」單位、`orphans()` 全表掃描 | **全部已修** | `ci.yml:31`、`app/build.gradle.kts:40`、`SettingsScreen.kt:156`、`Daos.kt:260` |
| fable Minor 11 | `deleteConversation` 失敗仍呼叫 `onDone` | **已修** | `ConversationViewModel.kt:96-97` |

### 特別查證：`MIGRATION_1_2`（brief 明確要求比對 `SourceScope.key`）

我逐字元比對過，**結論是正確的**：

- Kotlin `SourceScope.key`（`SourceScope.kt:21-24`）：`packageName + "|" + profileKey`，`accountKey != null` 時再 `+ "|" + accountKey`；`suppressionScopeKey` 再 `+ "#" + identityKey`。
- SQL（`QuietInboxDatabase.kt:70-71`）：`c.packageName || '|' || c.profileKey || CASE WHEN c.accountKey IS NULL THEN '' ELSE '|' || c.accountKey END || '#' || c.identityKey`。兩者完全一致。
- **NULL 傳染風險不存在**：`ConversationEntity.packageName / profileKey / identityKey` 都是非 null（`Entities.kt:86-89`），只有 `accountKey` 可為 null，而它正好被 `CASE WHEN` 擋掉。否則 SQLite 的 `||` 會讓整個 `scopeKey` 變 NULL，寫進 `NOT NULL` 欄位直接讓遷移失敗。
- **索引順序正確**：SQLite 的 `ALTER TABLE ... RENAME TO` 會把既有索引一併帶到 `deletion_suppression_old`，索引名稱不變。程式碼是 `DROP TABLE deletion_suppression_old`（`:75`）**之後**才 `CREATE INDEX IF NOT EXISTS ...`（`:76`）；順序若相反，`IF NOT EXISTS` 會因為名稱被舊表佔用而變成 no-op，新表就沒有索引。這裡寫對了。
- 我用 Python 讀了 `schemas/.../2.json`，`deletion_suppression` 的欄位、主鍵 `(scopeKey, fingerprint)`、索引 `index_deletion_suppression_expiresAtEpochMs`，以及 `notification_checkpoint` 新增的 `postedAtEpochMs INTEGER`（nullable），都與遷移 SQL 產出的形狀一致，`runMigrationsAndValidate` 應可通過。
- 但 `MigrationTest` 是 instrumented test，本次不允許執行，**這條升級路徑仍屬零實測**。

---

# 二、新發現

## Important 1 — `AmbiguousRepeat` 會讓 checkpoint 縮短，下一則通知重複寫入已存在的訊息

`core/reconcile/src/main/kotlin/dev/quietinbox/core/reconcile/Reconciler.kt:177`

```kotlin
val addsNothing = decisions.none { it is Decision.New || it is Decision.Revision || it is Decision.AmbiguousRepeat }
val window = if (addsNothing && prevItems.size > fps.size) { ... WINDOW_KEPT ... }
```

`WINDOW_KEPT` 是這次為了修 agy round-1 #5 而加的保護，但條件把 `AmbiguousRepeat` 也算成「有新增」，於是這條路徑照樣把 window 覆寫成本批內容。

**觸發條件（全部可在真機達成）**：同一個 notification key、checkpoint 的 `closed == true`（`onRemoved` 或 reconnect 的 `closeAllWindows` 造成，`CaptureCoordinator.kt:183, 201, 215`）、新的 `postTime`、本次只有一則且內容等於舊 window 尾端、無來源時間戳、無 `sourceMessageId`。此時 `samePost == false`（`Reconciler.kt:140-141`），`singleIdentical == true`（`:149`）→ `AmbiguousRepeat`。

**harness 實測輸出**（同一 key `k1`、`closed=true`、舊 window `A/B/C` = id 100/101/102、postTime 5000）：

```
== same key, closed, NEW postTime, single item equal to the tail ==
post1 AmbiguousRepeat(C) | window ids=[102,] notes=[FULL_OVERLAP]
post2 New(B) New(C) New(D) | window ids=[null,null,null,] notes=[]

== control: identical postTime (true resync) ==
post1 Known(C) | window ids=[100,101,102,] notes=[FULL_OVERLAP, WINDOW_KEPT]
post2 Known(B) Known(C) New(D) | window ids=[101,102,null,] notes=[]
```

差別只在 `postedAtEpochMs`：真正的 resync（同 postTime）走 `Known` + `WINDOW_KEPT`，一切正確；新 post 走 `AmbiguousRepeat`，checkpoint 從 3 筆縮成 1 筆，**下一則 `[B,C,D]` 就把 B 和 C 當成 `Decision.New` 重新入庫**。

commit 層也攔不住：`IngestRepository.kt:195` 的 fingerprint guard 只在 `ReconcileNote.NO_PREVIOUS_WINDOW in notes` 時啟用，而這裡 `previous != null`，guard 完全不觸發。結果是使用者收件匣裡出現兩份 B 和兩份 C。

**修法**：把 `AmbiguousRepeat` 從 `addsNothing` 的排除清單移走——ambiguous 依定義是「同一個位置的再觀測」，不佔新位置：

```kotlin
val addsNothing = decisions.none { it is Decision.New || it is Decision.Revision }
```

並補一個回歸測試，直接用上面的序列（`[A,B,C]` closed → `[C]` 新 postTime → `[B,C,D]`）斷言第三步是 `Known/Known/New`。

一句補充、不擴大範圍：單則 `New`（例如收到全新的 `[D]`）同樣會把 window 從 3 縮成 1，那是設計取捨而非缺陷，不在本次審查範圍內，但值得作者一併想過。

## Important 2 — `WrappedSecretFile` 的目錄 fsync 必然失敗，KDoc 與 CHANGELOG 宣稱的耐久性沒有實現

`platform/crypto/src/main/kotlin/dev/quietinbox/platform/crypto/WrappedSecretFile.kt:63-77`

```kotlin
/** Durable write: data fsync'd before the rename, directory fsync'd after; never overwrites in place. */
...
runCatching { FileInputStream(dir).use { it.fd.sync() } }   // :76
```

在 Linux/Android 上，Java 不允許用 `FileInputStream` 開啟目錄：Android 的 `IoBridge.open` 對 `S_ISDIR` 明確丟 `ErrnoException(EISDIR)`，OpenJDK 的 `handleOpen` 也一樣。**我在本機 JVM 實測確認**：

```
FileInputStream(dir): java.io.FileNotFoundException: <scratchpad path> (Is a directory)
```

外層 `runCatching` 把它整個吞掉，所以每一台裝置上這行都是 no-op，而且沒有任何訊號。資料本身的 `out.fd.sync()`（`:70`）確實有效，但 **rename 這個目錄 metadata 操作從未被 fsync**。這正是 fable I8 要防的情境：`getOrCreate()` 回傳 secret → SQLCipher 立刻用它建庫 → 此時掉電且 rename 未落盤 → 重開機後 key 檔不存在 → `getOrCreate()` 產生**新的** secret → 舊 vault 永遠打不開。`CHANGELOG.md` 已寫上「key files were not fsync'd」為已修復。

**修法**：走 `android.system.Os`，它不做 `S_ISDIR` 檢查（API 21+，本專案 `minSdk = 26`，可直接使用）：

```kotlin
runCatching {
    val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
    try { Os.fsync(fd) } finally { Os.close(fd) }
}
```

註：`androidx.core.util.AtomicFile` 不是替代方案——它同樣只 fsync 資料、不 fsync 目錄。專案目前也沒有用到它。

或者接受現狀，但把 KDoc 與 CHANGELOG 的措辭改成只宣稱「資料已 fsync」。不能兩者都不做。

## Important 3 — 文件宣稱了程式碼做不到的事（docs-honesty）

1. `docs/adr/0004-identity-and-dedup.md`（本 commit 新增）：

   > a replay that adds nothing keeps the previous checkpoint window (`WINDOW_KEPT`), so `[A]` after `[A,B,C]` cannot make the next `[B,C,D]` duplicate B and C

   Important 1 的 harness 輸出就是這句話的反例。應改成「a replay judged `Known` keeps…」，並在修好 Important 1 後才恢復無條件的說法。

2. `docs/SCOPE.md:53`：「Restore … never deletes the user's target document」。在 `8050e05`，`BackupService.kt:78` 用 `openOutputStream(target, "wt")` 開檔，`"wt"` 的 truncate **發生在開檔當下**，不是在 catch 裡。也就是說使用者若選了既有的舊備份當覆寫目標，匯出中途只要出任何 IO 例外，舊備份就已經是 0 byte。`catch` 區塊（`:89`）再開一次 `"wt"` 只是重複同一個動作，既沒造成額外損害也沒有修復任何東西。同一份 commit 的 `strings.xml` 老實寫著「該檔案已被覆寫，不再是有效備份」，與 SCOPE.md 互相矛盾。
   **未提交的工作區已用正確方法修好**（先寫 `cacheDir` 暫存檔，`FileOutputStream.use` 與 `newEncryptingStream.use` 都關閉後才 `copyTo(dest)`），字串也同步改了。請把它一起提交，或先把 SCOPE.md 改回誠實敘述。

---

# 三、Minor

1. **`BackupService.stage()` 的 staging 沒有文字總量上限。** `BackupService.kt:203-244`：每行有 `MAX_LINE_CHARS`（16 M chars）、媒體有 `MAX_STAGED_MEDIA_BYTES`（256 MB），但 message / conversation / revision 記錄只受 `MAX_RECORDS = 2,000,000` 筆數限制，全部堆在 heap 的 `ArrayList` 裡。刻意構造的備份檔可以在 `apply` 之前就把 import OOM 掉。金庫本身不受影響（不違反硬性規則 8），但「bounded reader」這個修正只做了一半。建議加一個累計字元數上限，或改成分批 staging 到暫存檔。
2. **冷啟動期間 `offer()` 不再過濾套件。** `CaptureCoordinator.kt:292`：`sourcesLoaded` 為 false 時，任何套件的通知都會被 `snapshotFactory.create` 完整解析並排進佇列，之後才在 `process()` 被丟掉。內容不會落盤、不會進 log，佇列 512 也不太可能被一般數量的 active notification 塞爆；真正的成本是 `MAX_QUEUED_BITMAPS = 8` 這個預算會被非來源通知吃掉，讓同一時間真正的來源通知退化成 placeholder。建議 `onConnected` 的 resync 迴圈先 await 第一次 `sources.sources()`。
3. **`CaptureCoordinator` 仍在吞 `CancellationException`。** `:350` 與 `:418` 的 `catch (e: Exception)`、`:402` 的 `runCatching`、`IngestRepository.kt:130` 的 `runCatching` 都會攔下 `CancellationException`——正是同一個 commit 在 `MediaCopier.kt:80-83` 修掉的模式。至少 `:418` 應該先 `if (e is CancellationException) throw e`。
4. **commit 層的 guard 仍可能壓掉一筆 multiplicity。** `IngestRepository.kt:195-227`：`preExisting` 是 fingerprint → 單一 id 的 map，同一批裡兩則相同 fingerprint 的 `New` 會**雙雙**命中同一個既有 id，兩則都轉成 link，實際插入 0 筆，正確答案是 1 筆。建議把 `preExisting` 改成可消耗的計數（用掉一次就移除），讓多餘的那則正常插入。
5. **已刪訊息的 id 會被寫回 checkpoint。** `IngestRepository.kt:285` 對 `Decision.Known` 驗證 `get(id) != null`，驗不過就不寫 `storedIds`；但 `:311` 的 `item.decisionIndex?.let { storedIds[it] } ?: item.messageId` 會 fallback 回原本那個**已失效的 id**，於是懸空參照永遠留在 window 裡。建議 `Known` 驗證失敗時顯式寫入 null。
6. **`onDisconnected` 沒有清掉 `sessionId`。** `CaptureCoordinator.kt:199` 只 `sessionId?.let { endSession(...) }`。之後若使用者按暫停，`:233` 會對同一個已結束的 session 再 `endSession` 一次。加一行 `sessionId = null` 即可。
7. **`closeAllWindows` / `closeWindow` 不在 `pipelineMutex` 內。** `CaptureCoordinator.kt:183, 201, 215` 與 `IngestRepository.commit` 的 checkpoint upsert（固定寫 `closed = false`）互相競態，關窗可能被緊接著的 commit 立刻覆蓋。`samePost` 現在多了 postTime 這條路，影響已比 round-1 小，但競態本身還在。
8. **`process()` 的註解與行為不符。** `CaptureCoordinator.kt:345` 寫「The event was not journaled」，但同一個 `catch (e: VaultUnavailableException)` 也涵蓋 `processJournaled` 之後才丟出的情況，那時 journal 其實已經寫成功（並會被 replay 撿回，行為是對的，只是註解會誤導）。
9. **`docs/SCOPE.md:16` 的測試數字差一。** 寫「16 JVM tests in `core:reconcile`」，JUnit XML 實際是 **17**（11 + 3 + 1 + 1 + 1）。
10. **property test 沒有進入問題所在的空間。** `ReconcilerPropertyTest.kt:40-41` 每個 candidate 都帶 `TimestampQuality.SOURCE_MESSAGE` 的來源時間戳，`Fingerprint.of` 因此把時間戳納入雜湊，所有 fingerprint 必然互異，也就永遠不會產生無 id、無時間戳、內容重複的項目——Important 1 正好活在那個區域。1,000 seed 的數字很好看，但覆蓋的是最容易的情況。建議加一組「無來源時間戳 + 可重複字串 + 隨機 closed 旗標」的 generator。

---

# 四、其他觀察（已驗證正確，讀者可能會懷疑的部分）

- **`Reconciler` 重寫方向正確，混合 id 的錯位確實修好了。** harness 實測 `[A, id1, B]` → `[A, id1, B, C]`：得到 `Known(10)/Known(SAME_ID)/Known(12)/New`，id 對應無偏移。round-1 agy #4 描述的 `k` 與 `prevItems` 下標錯位已不存在。
- **`WINDOW_KEPT` 的 id 對映是對的。** 保留舊 window 時 `decisionIndex` 全設為 null（`Reconciler.kt:180`），`IngestRepository.kt:311` 因此 fallback 到 `item.messageId`，harness 實測連續兩次 stale replay 後 window 仍是 `[100,101,102]`，沒有退化。
- **重複的 stale replay 不會累積副作用。** harness 實測同一筆 `[A]` 連續 replay 兩次，第二次仍是 `Known` + `WINDOW_KEPT`，window 內容不變。
- **`process()` 與 `replayJournal()` 不會重複處理同一事件。** `process()` 在同一個鎖區間內完成 journal → commit（commit 交易內把狀態設為 COMMITTED），`replayJournal` 每筆取鎖後重新查 `isJournalPending`，因此鎖外那次 `pendingJournal()` 撈到的過期資料會被正確跳過。
- **`replayJournal` 不會餓死線上擷取，也不會無限迴圈。** 每筆事件各自取鎖，kotlinx `Mutex` 對已掛起的等待者是 FIFO 的；`processJournaled` 的每條路徑都會把 journal 狀態改成 COMMITTED / FAILED / SKIPPED，`markJournalRetryable` 最多讓一筆事件重試 3 次，外層另有 100 輪上限。
- **`export` 的非區域 return 不會漏寫 EOF 認證段。** `use` 是 inline 且帶 finally，`enc.close()` 會在函式真正返回前執行；若 close 本身丟例外，也會被外層 `catch` 收成 `Failed(IO)`。未提交工作區的暫存檔改法更明確：兩層串流都在 `copyTo` 之前關閉。
- **import 的完整性驗證是真的。** `stage()` 讀到 EOF 才結束，Tink 的 decrypting stream 在最後一段未通過認證時會丟 `IOException`，因此「讀完全部」等於「全部 tag 驗過」；manifest 與 End 的雙重 counts 比對（`:240-242`）在 `apply` 之前完成，錯 key / 截斷 / 竄改都不會碰到既有 vault。
- **`DatabaseHolder` 的 `catch (t: Throwable)` 是必要的，不是過度防禦。** `System.loadLibrary` 丟的是 `UnsatisfiedLinkError`，屬 `Error`；`.addMigrations` 之後的遷移失敗同理。搭配 `:48-51` 的 `CoroutineExceptionHandler`，`keyMaterial.database.getOrCreate()`（在 try 之外）丟出的非受檢例外也會收斂成 `Locked`。
- **`LockController` 的 null 三態確實堵住冷啟動 bypass。** `_locked` 初值 null，`onForeground()` 在 `enabled` 尚未載入時提早返回不會造成漏洞，因為 UI 對 null 顯示 LoadingScreen（`QuietInboxApp.kt:90`），設定載入後才決定鎖或不鎖。停用鎖時 `!enabled -> false` 也不會把使用者關在外面。
- **雙語字串仍然齊備。** `core/designsystem` 兩個 locale 各 298 條，`name` 逐一 diff 完全一致；`platform/capture` 各 1 條亦同。未提交工作區同時改了兩邊，parity 沒有破。
- **`postedAtEpochMs` 在生產環境幾乎不會是 null**（`SnapshotFactory.kt:137` `sbn.postTime.takeIf { it > 0 }`），所以「repost 沒帶 postTime 會抹掉 window 上的 postTime」只是理論上的路徑，我沒有把它列為發現。
- **CI 已補上 `:app:testDebugUnitTest`**（`ci.yml:31`），`localeFilters` 的 `zh-rTW` 已移除且不影響 `values-b+zh+Hant` 的解析。

---

# 五、建議的處理順序

1. 修 Important 1（`addsNothing` 排除清單）＋ 補回歸測試——這是唯一會產生使用者可見資料重複的問題。
2. 修 Important 2（改用 `Os.open` + `Os.fsync`），或把 KDoc / CHANGELOG 的宣稱降級。
3. 把未提交的 `BackupService` 暫存檔改法一起提交，並修正 ADR-0004 與 SCOPE.md 的兩處宣稱（Important 3）。
4. 在真機跑一次 `MigrationTest` 與 `VaultRoundTripTest`；schema v1→v2 這條路徑目前仍是零實測。
5. Minor 1 / 3 / 4 可在同一輪順手處理；其餘可開 issue 追蹤。
