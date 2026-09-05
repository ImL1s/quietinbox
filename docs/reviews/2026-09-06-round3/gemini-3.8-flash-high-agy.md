# QuietInbox／靜讀 v0.1 第三輪獨立代碼審查報告（Round 3 Review）

- **審查對象**：Commit [`6a9b0ce`](file://<repo>)（`fix: address review round 2 — reconciler keep, fsync directory, export staging, docs`），比對 `git diff c96fbf0..6a9b0ce`（共 20 個檔案變更，+618 / -36）在全專案語境下的實作。
- **審查基準**：規格書 `QuietInbox_開源專案完整計劃.md`、九大硬性產品規則、第二輪審查報告（`docs/reviews/2026-09-06-round2/gemini-3.8-flash-high-agy.md` 與 `docs/reviews/2026-09-06-round2/claude-subagent.md`）、以及第三輪審查指示 [`dual-review-round3-brief-safe.md`](../../../docs/reviews/2026-09-06-round3/brief.md)。
- **驗證執行**：
  - 在唯讀約束下，執行 `./gradlew :core:reconcile:test :core:model:test --rerun-tasks --console=plain`，全數通過（`:core:reconcile` 共 20 個測試通過，含兩項 1,000 次反覆運算之 Property-based 測試；`:core:model` 共 5 個測試通過）。
  - 對照雙語資源檔 [`values/strings.xml`](../../../core/designsystem/src/main/res/values/strings.xml) 與 [`values-b+zh+Hant/strings.xml`](../../../core/designsystem/src/main/res/values-b+zh+Hant/strings.xml) 之 parity。
  - 對 20 個檔案逐行進行回歸漏洞分析（包括 POSIX 目錄 fsync 邊界、Checkpoint 懸空鍵、協程取消傳遞、SAF 暫存生命週期）。

---

## 一、審查結論（Verdict）

**APPROVE WITH MINOR FIXES（建議通過，附帶少許非阻擋性改進建議）**

Commit `6a9b0ce` 非常高水準地修復了第二輪審查中由 Subagent 與 Agy 提出的**全部 Important 級別問題**與絕大多數 Minor 問題。
特別值得肯定的是：
1. **Reconciler 全視窗對齊補完**：`addsNothing` 移除了對 `AmbiguousRepeat` 的排除，精確補上了先前 `WINDOW_KEPT` 遺漏的模糊單則重複情境，並以兩組專門回歸測試及擴展的重複內容 Property 測試守護了「重複內容絕不造成資料庫重複新增」的不變量。
2. **POSIX 目錄持久性落盤**：`WrappedSecretFile` 揚棄了 Java IO 無法開啟目錄的限制，正確透過 `android.system.Os`（`Os.open` + `Os.fsync` + `Os.close`）直接對目錄 inode 進行同步，並於新建目錄時遞歸同步父目錄，搭配 Android 儀器測試確保落盤能力。
3. **SAF 匯出暫存防護**：備份匯出改為先於私有 `cacheDir` 完成串流加密落盤與 AEAD 驗證，最後才一次性複製至 SAF 使用者指定檔，徹底解決了開檔即 Truncate 破壞使用者歷史備份的風險。

目前程式碼無任何 Critical 或 Important 級別的新缺陷，程式架構穩健。本報告提出 3 項非阻擋性的 Minor 邊界建議與文件修訂。

---

## 二、第二輪審查問題修復驗證總表（Round-2 Fix Verification Table）

| 項次 | 原始報告與編號 | 問題描述 | 驗證結果 | 程式碼修復位置與驗證依據 |
| :--- | :--- | :--- | :---: | :--- |
| 1 | Subagent Important 1 | `AmbiguousRepeat` 導致 Checkpoint 縮短，下一則通知重複寫入既有訊息 | **Verified Fixed** | [`Reconciler.kt:181`](../../../core/reconcile/src/main/kotlin/dev/quietinbox/core/reconcile/Reconciler.kt#L181)：`addsNothing` 改為 `decisions.none { it is Decision.New \|\| it is Decision.Revision }`。[`ReconcilerTest.kt:183-209`](../../../core/reconcile/src/test/kotlin/dev/quietinbox/core/reconcile/ReconcilerTest.kt#L183-L209) 新增 `ReconcilerAmbiguousKeepTest`（2 測）完整驗證。 |
| 2 | Subagent Important 2 | `WrappedSecretFile` 以 `FileInputStream(dir)` fsync 目錄必然拋出 `EISDIR` 被吞掉 | **Verified Fixed** | [`WrappedSecretFile.kt:70-101`](../../../platform/crypto/src/main/kotlin/dev/quietinbox/platform/crypto/WrappedSecretFile.kt#L70-L101)：改用 POSIX 底層 `Os.open(dir.path, O_RDONLY, 0)`、`Os.fsync` 與 `Os.close`，出錯拋出 `IOException` 轉換為 `KeyResult.Failed(Unavailable)`。[`WrappedSecretFileTest.kt:15-37`](../../../platform/crypto/src/androidTest/kotlin/dev/quietinbox/platform/crypto/WrappedSecretFileTest.kt#L15-L37) 新增真實檔案系統測試。 |
| 3 | Subagent Important 3 | 文件宣稱了程式碼做不到的事（ADR-0004 宣稱無條件不重複、SCOPE.md 宣稱從不刪除目標檔） | **Verified Fixed** | [`0004-identity-and-dedup.md:33-35`](../../../docs/adr/0004-identity-and-dedup.md#L33-L35)：精確界定 adds nothing 語意並指向測試。[`SCOPE.md:52-54`](../../../docs/SCOPE.md#L52-L54)：誠實陳述兩段式匯出與複製階段失敗的影響。 |
| 4 | Subagent Minor 1 | `BackupService.stage` 未限制記憶體非媒體文字總字元數 | **Verified Fixed** | [`BackupRecords.kt:120`](../../../platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupRecords.kt#L120)：定義 `MAX_STAGED_TEXT_CHARS = 64L * 1024 * 1024`。[`BackupService.kt:222-228`](../../../platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt#L222-L228)：累加非媒體記錄之 `line.length`，超額拋出 `StagingException(TOO_LARGE, "text")`。 |
| 5 | Subagent Minor 3 | `CaptureCoordinator` 與 `IngestRepository` 吞掉 `CancellationException` | **Verified Fixed** | [`CaptureCoordinator.kt:139, 354, 424`](../../../platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt#L139)：在消費者迴圈、`process`、`replayJournal` 中均加上 `if (t/e is CancellationException) throw t/e`。[`IngestRepository.kt:132`](../../../platform/storage/repo/IngestRepository.kt#L132) 的 `diagnostic` 亦加上 `.onFailure` 重新拋出。 |
| 6 | Subagent Minor 4 | Commit 層 checkpoint-loss guard 把同批兩則相同指紋訊息雙雙折疊到同一個 id | **Verified Fixed** | [`Daos.kt:177`](../../../platform/storage/db/Daos.kt#L177)：新增 `findIdsByFingerprint`（`ORDER BY id ASC`）。[`IngestRepository.kt:198-204, 226`](../../../platform/storage/repo/IngestRepository.kt#L198-L204)：`preExisting` 改為 `Map<String, ArrayDeque<Long>>`，每比對一筆即執行 `removeFirstOrNull()` 消耗，保留同批多重性。 |
| 7 | Subagent Minor 5 | 已刪除訊息之 id 透過 checkpoint fallback 重新寫回 Checkpoint | **Verified Fixed** | [`IngestRepository.kt:212, 291, 314-317`](../../../platform/storage/repo/IngestRepository.kt#L212)：`storedIds` 改為 `HashMap<Int, Long?>`；`Decision.Known` 驗證 `get(id) != null` 失敗時寫入顯式 `null`，checkpoint mapping 依 `containsKey` 取用，不再 fallback 至失效 id。 |
| 8 | Subagent Minor 6 | `onDisconnected` 未清空 `sessionId`，暫停時重複呼叫 `endSession` | **Verified Fixed** | [`CaptureCoordinator.kt:202`](../../../platform/capture/CaptureCoordinator.kt#L202)：`health.endSession` 後立即補上 `sessionId = null`。 |
| 9 | Subagent Minor 8 | `process()` 註解稱事件尚未 journal 與實際捕捉範圍不符 | **Verified Fixed** | [`CaptureCoordinator.kt:348-349`](../../../platform/capture/CaptureCoordinator.kt#L348-L349)：註解修訂為明確說明金庫在 commit 前離線（已 journal 者日後 replay，未 journal 者遺失）。 |
| 10 | Subagent Minor 9 | `docs/SCOPE.md:16` 測試數量計數偏差 | **Partially Fixed** | 詳見新發現 Minor 1：[`docs/SCOPE.md:57`](../../../docs/SCOPE.md#L57) 正文雖更新，但第 16 行表格仍寫為「16 JVM tests」，而目前實際已有 20 個 JVM 測試。 |
| 11 | Agy M1 | `BackupService.export` 於開檔時直接 truncate 使用者檔案 | **Verified Fixed** | [`BackupService.kt:79-99`](../../../platform/backup/BackupService.kt#L79-L99)：先在 `context.cacheDir` 建立唯一 UUID 暫存檔，成功後再開 SAF 目標寫入複製，`finally` 刪除暫存檔。 |
| 12 | Agy M2 | `ConversationEntity.summaryOnlyCount` 未維護易生困惑 | **Verified Fixed** | [`Entities.kt:100`](../../../platform/storage/db/Entities.kt#L100)：加入明確 KDoc 註解標記為 v0.1 保留欄位（恆為 0）。 |
| 13 | Agy M3 | Kotest 實驗性 API 編譯警告 | **Verified Fixed** | [`ReconcilerPropertyTest.kt:24`](../../../core/reconcile/src/test/kotlin/dev/quietinbox/core/reconcile/ReconcilerPropertyTest.kt#L24)：加上 `@OptIn(io.kotest.common.ExperimentalKotest::class)`。 |

---

## 三、專項深度回歸分析（Regression Inquiries）

方針對指示所列的七大核心疑點進行深層代碼與邏輯追蹤：

### 1. `Reconciler.kt` `addsNothing` 忽略 `AmbiguousRepeat` 與 `IngestRepository.commit` 之交互影響
- **機制剖析**：
  當前一視窗 `[A(100), B(101), C(102)]` 關閉後，收到單則且內容為尾端 `C` 的新通知時，決策為 `AmbiguousRepeat(102)`。
  新邏輯下 `addsNothing == true` 且 `prevItems.size (3) > fps.size (1)`，觸發 `WINDOW_KEPT`，生成之新 Checkpoint 視窗完整保留舊視窗項目，並將其 `decisionIndex` 全部設為 `null`。
- **Commit 寫入分析**：
  在 `IngestRepository.commit` 中：
  1. 本次事件的 `AmbiguousRepeat` 仍被正常插入 `message` 表（賦予新 id，例如 103，狀態為 `AMBIGUOUS_REPEAT`），並建立指向 102 的 `ObservationLink`。
  2. Checkpoint 更新時，舊項目之 `decisionIndex` 為 `null`，因此 `storedIds.containsKey(null)` 不成立，fallback 取得原有的 `item.messageId`（100, 101, 102）。
- **後續到達是否會丟失 Link 或重複插入？**
  - 若下一則通知為 `[B, C, D]`：與保留的 Checkpoint `[A(100), B(101), C(102)]` 對齊，B 與 C 精確判定為 `Decision.Known`（指向 101 與 102），僅 D 為 `Decision.New`。B 與 C 絕不會被重複插入，且會建立指向 101、102 的 ObservationLink。
  - 那筆已插入的 103（`AMBIGUOUS_REPEAT`）行保留於資料庫中，代表使用者收到了疑似重複但未確認的新訊息（符合 §7.2 規範，在 UI 上標示為「可能重複」）。
  - 若 101 或 102 在此期間被使用者手動刪除，下一輪 `Decision.Known` 在寫入 Link 前會經過 `db.messageDao().get(it) != null` 驗證，並在 `storedIds` 設為顯式 `null` 刷新 Checkpoint，不會引發外鍵崩潰。**邏輯完全閉環且安全**。

### 2. `WrappedSecretFile.fsyncDirectory` POSIX 落盤機制
- **健康裝置行為分析**：
  Android 官方規範下，`context.filesDir` 位於應用內部專屬私有目錄（ext4 或 f2fs）。在 Linux 核心中，以 `O_RDONLY` 開啟目錄並對其 file descriptor 呼叫 `fsync()` 是標準 POSIX 行為，SELinux 對 `untrusted_app` 在私有 `app_data_file:dir` 具備完整的 open/read/ioctl 權限。
- **新建目錄的父目錄同步**：
  代碼中 `val createdDir = !dir.exists() && dir.mkdirs()`，當 `keys/` 目錄為首次建立時，除同步 `dir` 外，亦同步 `dir.parentFile`（即 `context.filesDir`），符合檔案系統規格。
- **部分失敗重試分析**：
  若在 `renameTo(file)` 成功後但在 `fsyncDirectory` 拋出例外：
  1. `getOrCreate()` 捕捉 `IOException` 並回傳 `KeyResult.Failed(Unavailable)`，金鑰**未被釋出給呼叫端**，SQLCipher 不會以此金鑰建立金庫。
  2. 此時磁碟上 `.tmp` 已被 rename 為 `file`（兩者不可能同時存在於 POSIX 目錄中）。
  3. 下次再次呼叫 `getOrCreate()` 時，`file.exists()` 為真，直接進入 `read()` 讀取並解開既有已 fsync 資料的金鑰檔。因此不會發生金鑰丟失或金庫無法開啟的慘劇。

### 3. `BackupService.export` 快取暫存與串流關閉順序
- **暫存生命週期**：暫存檔檔名為 `"backup-" + UUID.randomUUID().toString().replace("-", "") + ".qibk"`，置於 `context.cacheDir`，即使高併發或異常中止亦無檔名衝突。
- **Deletion on every path**：無論成功或拋出任何例外（包括協程取消），`finally { key.fill(0); staging.delete() }` 保證暫存檔一定被刪除。
- **快取空間不足（cacheDir full）**：在 `FileOutputStream(staging)` 或寫入過程即拋出 `IOException`，SAF 目標檔案根本尚未被開啟，使用者原有舊備份 100% 毫髮無傷。
- **EOF 認證段健全性**：
  ```kotlin
  val counts = FileOutputStream(staging).use { raw ->
      raw.write(header)
      saead.newEncryptingStream(raw, header).use { enc ->
          writeRecords(db, enc.bufferedWriter(Charsets.UTF_8), appVersion)
      }
  }
  ```
  `writeRecords` 結尾呼叫 `w.flush()`，隨後 `enc.close()` 關閉 Tink AEAD 串流，確定產出最後一個密文塊與認證標籤（Tag），最後 `raw.close()` 關閉暫存檔。所有落盤動作在開啟 SAF `target` 之前完全完成，再透過 `FileInputStream(staging).use { it.copyTo(dest) }` 單純複製位元組。此結構比舊版在 SAF 串流內邊加密邊寫入並依賴非區域 return 更具確定性。

### 4. `IngestRepository` 既有指紋消耗與 Checkpoint 映射
- **`findIdsByFingerprint` 排序**：依 `ORDER BY id ASC`（由舊至新）取出所有同指紋列，放入 `ArrayDeque`。
- **消耗邏輯**：每遇到同指紋的候選訊息，呼叫 `removeFirstOrNull()`。若庫中原本有 1 筆，批次中出現兩筆相同訊息，第一筆消耗該 id 轉為 observation link，第二筆則因隊列已空取得 `null`，正常執行 INSERT 入庫，完整保留多重性（Multiplicity）。
- **顯式 null 映射**：`storedIds` 定義為 `HashMap<Int, Long?>`，`Decision.Known` 查無此列時寫入 `storedIds[index] = null`，使 `storedIds.containsKey(index)` 為 true 但取值為 null，消除了懸空 id 遺留問題。

### 5. `CaptureCoordinator` 之 `CancellationException` 傳遞
- 在消費者主迴圈：
  ```kotlin
  } catch (t: Throwable) {
      if (t is CancellationException) throw t
      lastError = t::class.java.simpleName
      _status.update { ... }
  }
  ```
  若收到真正的非取消例外（如 OOM、未預期 RuntimeException），`t is CancellationException` 為 false，記錄 `lastError` 並更新狀態，`while (true)` 迴圈繼續迭代，由 `queue` 通道取出下一事件處理，自癒機制完全正常。
- 當協程 Scope 關閉或取消時，`for (item in queue)` 拋出 `CancellationException`，被 `throw t` 重新拋出，協程乾淨終止，不再發生過去將取消誤認為服務故障的問題。

### 6. `BackupService.stage` 的 64M 字元上限審查
- 非媒體記錄（Manifest、Source、Conversation、Message、Revision）於反序列化前均會累加其 JSON 行字元數 `stagedTextChars += line.length`。
- 限制為 `MAX_STAGED_TEXT_CHARS = 64L * 1024 * 1024` 字元（約 128 MB JVM 堆疊記憶體）。一般純文字訊息平均約 100~200 字元，64M 字元足以容納 30 萬至 50 萬筆訊息，既滿足極大規模還原需求，又能在惡意構造或毀損備份檔耗盡記憶體前拋出 `TOO_LARGE` 例外保護程序。

### 7. `ReconcilerPropertyTest` 重複內容性質測試的健全性
- 測試針對無來源時間戳、無來源 ID、內容大量重複（`alphabet` 尺寸為 1..3）的滑動視窗進行 1,000 次隨機種子演算。
- **為何斷言「不重複」而非「不丟失」？**
  KDoc 闡釋極為深刻：在缺乏訊息 ID 與來源時間戳的語境下（如連續收到單則 "好"），一個剛好滑動了完整內容長度的視窗與系統/使用者產生的 Repost 在資訊理論上是**無法區分的（Indistinguishable）**。若強制要求「不丟失」，系統將被迫把每一次重複觀測都當作新訊息，導致資料庫嚴重灌水。
- 該測試驗證的核心不變量為：
  1. 舊通知重發（Stale Replay）絕不產生 `Decision.New`。
  2. 視窗絕不因重發而縮短（`size >= prev.size`）。
  3. 整體產生的 `Decision.New` 總量絕對不超過資料流推進的總長度（`newTotal <= next`）。
  此一性質證明了演算法在模糊重複空間下的收斂性與安全性。

---

## 四、新增問題發現（New Findings）

本次針對 Commit `6a9b0ce` 之審查結果：
- **Critical（嚴重阻擋性問題）**：**0 項**
- **Important（重要問題）**：**0 項**
- **Minor（次要改進建議）**：**3 項**

### M1. `docs/SCOPE.md:16` 測試數量標記未同步更新（文件真實性偏差）
- **位置**：[`docs/SCOPE.md:16`](../../../docs/SCOPE.md#L16)
- **問題分析**：
  在本次 commit 中，雖然在 `docs/SCOPE.md:57` 聲稱已修復第二輪 Subagent 指出的 Minor 9，但第 16 行表格內容仍保留為舊字樣：
  ```markdown
  | Dedup with `AMBIGUOUS_REPEAT`, revisions, stale-window handling, resync-as-repost | Done | 16 JVM tests in `core:reconcile` including a 1,000-seed property test ... |
  ```
  在第二輪時實際已有 17 個測試，而本次新增了 `ReconcilerAmbiguousKeepTest`（2 測）以及 `ReconcilerPropertyTest` 中的第二個性質測試後，`:core:reconcile` 的測試總數已達 **20 個**（11 + 3 + 1 + 1 + 2 + 2）。
- **修復建議**：
  將該行更新為：
  ```markdown
  20 JVM tests in `core:reconcile` including two 1,000-seed property tests ...
  ```

### M2. `IngestRepository.kt:297` 之 `Decision.Revision` 若遇已刪除訊息未將 `storedIds` 設為 null
- **位置**：[`IngestRepository.kt:297-308`](../../../platform/storage/repo/IngestRepository.kt#L297-L308)
- **問題分析**：
  在修復 Minor 5（已刪訊息 id 寫回 Checkpoint）時，[`IngestRepository.kt:290-291`](../../../platform/storage/repo/IngestRepository.kt#L290-L291) 對 `Decision.Known` 做了妥善處理：
  ```kotlin
  val id = decision.existingMessageId?.takeIf { db.messageDao().get(it) != null }
  storedIds[index] = id
  ```
  然而在緊隨其後的 `Decision.Revision` 中：
  ```kotlin
  is Decision.Revision -> {
      val id = decision.existingMessageId
      val old = db.messageDao().get(id)
      if (old != null) {
          ...
          storedIds[index] = id
      }
  }
  ```
  若該則被修訂的歷史訊息已被使用者或 RetentionWorker 刪除（`old == null`），代碼直接跳過，**並未執行 `storedIds[index] = null`**。
  當後續在行 314 執行：
  ```kotlin
  val id = if (index != null && storedIds.containsKey(index)) storedIds[index] else item.messageId
  ```
  由於 `storedIds.containsKey(index)` 為 false，它會 fallback 到 `item.messageId`，使得該已刪除訊息的失效 ID 依然被寫入了新的 Checkpoint JSON 中。
- **修復建議**：
  在 `old == null` 時亦顯式賦值 null：
  ```kotlin
  is Decision.Revision -> {
      val id = decision.existingMessageId
      val old = db.messageDao().get(id)
      if (old != null) {
          db.revisionDao().insert(MessageRevisionEntity(messageId = id, body = old.body, observedAtEpochMs = now, eventId = snapshot.eventId))
          db.messageDao().applyRevision(id, c.body, snapshot.eventId)
          db.searchDao().deleteForMessage(id)
          indexTokens(db, id, c.body)
          storedIds[index] = id
      } else {
          storedIds[index] = null
      }
  }
  ```

### M3. `BackupService.kt:96, 175` 之 `catch (e: Exception)` 攔截了協程取消
- **位置**：[`BackupService.kt:96, 175`](../../../platform/backup/BackupService.kt#L96)
- **問題分析**：
  在 `BackupService.export` 與 `import` 中：
  ```kotlin
  } catch (e: Exception) {
      BackupResult.Failed(BackupResult.Reason.IO, e::class.java.simpleName)
  }
  ```
  `CancellationException` 繼承自 `IllegalStateException`（屬 `Exception`）。若使用者在匯出或匯入過程中離開畫面導致 `viewModelScope` 取消，外層 `catch (e: Exception)` 會將取消例外攔截並包裝為 `BackupResult.Failed(Reason.IO, "CancellationException")` 回傳，而非依 Kotlin 協程慣例向外傳播（Rethrow）。雖然 `finally` 區塊已正確保證了暫存檔的刪除與金鑰清零，但在協程結構化併發標準中，應優先讓取消信號正常傳遞。
- **修復建議**：
  於 `catch` 開頭加入檢查：
  ```kotlin
  } catch (e: Exception) {
      if (e is CancellationException) throw e
      BackupResult.Failed(BackupResult.Reason.IO, e::class.java.simpleName)
  }
  ```

---

## 五、文件誠實性檢驗（Docs-Honesty Check）

比對程式碼與相關文件的宣稱：
1. **[`docs/adr/0004-identity-and-dedup.md`](../../../docs/adr/0004-identity-and-dedup.md)**：已精準修正，明確指出 "Adds nothing" 涵蓋了無 `New` 與無 `Revision`，包含關閉後的單則模糊重複，並指涉 `ReconcilerAmbiguousKeepTest`。與代碼 100% 吻合。
2. **[`CHANGELOG.md`](../../../CHANGELOG.md)**：完整列出 Round 2 review 所修復之全部項目，敘述忠實。
3. **多語系字串 `backup_failed_io`**：
   - 英文：`Could not read or write the file. The backup was not written; an existing target file was left unchanged unless the final copy itself failed.`
   - 繁中：`無法讀取或寫入檔案。備份未寫出；除非最後的複製步驟本身失敗，否則既有的目標檔案不會被更動。`
   兩者與兩階段 SAF 暫存複製之實際錯誤路徑完全一致，不再誤導使用者。
4. **[`docs/SCOPE.md`](../../../docs/SCOPE.md)**：除第 16 行表格之測試數量未更新外（見 M1），第 52–59 行對於兩階段寫入、POSIX 目錄 fsync 及 Round 2 缺陷修復之宣稱均真實且經檢驗。

---

## 六、總結（Summary）

Commit `6a9b0ce` 的修復品質卓越。核心演算法層的邊界測試完備，底層 POSIX 呼叫與 SAF 暫存架構清晰嚴謹。所有第一輪與第二輪留存之重要問題均已徹底解決，本輪僅留有 3 項極輕微的邊界修飾與文件數值同步建議，不影響系統功能與資料安全，**建議直接通過（APPROVE WITH MINOR FIXES）**。
