# Review round 14 — Claude subagent（round-13 五個 Minor 修復之迷你再審）

- **審查範圍**：`git diff c6b6645..eae0003`（一個程式碼 commit `eae0003`，中間的 `537ad80` 只是 SCOPE 一列文件）。程式碼改動只在四個檔案（`git diff --numstat`）：`CaptureCoordinator.kt` +36/−6、`CaptureCoordinatorTest.kt` +70/−0、`BackupService.kt` +11/−8、`Daos.kt` +6/−2；其餘是文件與 round-13 報告歸檔。
- **審查基準**：工作區 HEAD = `eae0003`；我讀取程式碼與跑測試時 `git status` 只有未追蹤的 `docs/reviews/2026-09-06-round14/`，所以下面所有 file:line 以 **`eae0003` 的內容**為準。（報告寫完之後、2026-09-06 13:24 起，工作區的 `CaptureCoordinator.kt` 與 `CaptureCoordinatorTest.kt` 出現了另一位 agent 的未 commit 修改——看起來是在回應本報告的 Minor；那些修改**不在本輪審查範圍**，我沒有看過也沒有評價。之後對照行號請用 `git show eae0003:<path>`。）
- **審查性質**：唯讀。沒有改任何檔案、沒有跑會改狀態的 git 指令、沒有碰裝置或 instrumented test。
- **超出 brief 列舉指令的部分**（皆唯讀，且在 repo 之外）：為了驗證「拿掉 guard 測試會紅」這個宣稱，我用 `rsync`（排除 `build`／`.gradle`／`.git`／`.omc`）把 repo 複製到 session scratchpad，在**複本**裡把三個 guard 拿掉再跑 `:platform:capture:testDebugUnitTest`；之後把複本還原成原始碼再連跑六次量 flake。複本已刪除。

## 本地實測與宣稱核對

| 宣稱 | 驗證方式 | 結果 |
| --- | --- | --- |
| capture 27 個 JVM test 全綠 | 主 repo `./gradlew :platform:capture:testDebugUnitTest -q` 一次 + `--rerun` 兩次，彙總 JUnit XML | **3 / 3 綠**，每次 27 tests / 0 failures / 0 errors / 0 skipped |
| 三個新測試各有負面對照、拿掉 guard 會紅 | 在 scratchpad 複本**一次同時**拿掉三個 guard（`:420` 改回無條件清除、`:529-532` 整塊移除、`:487` 移除）再跑整個 suite 一次；不是分三次各拿一個 | 那一次執行恰好紅這三個目標測試，失敗訊息與各自的 guard 路徑吻合（對應關係由訊息與程式碼路徑判定，非隔離執行）：settle-failed `recordGap(…COLD_START, BOUNDED…) One matching call found, but needs exactly 2 calls`；closed-at-once `closeOpenGaps(…[COLD_START]…) 1 matching calls found, but needs exactly 2 calls`；held-across-disconnect `recordGap(…COLD_START, BOUNDED…) was not called`。其餘 23 個綠、**另有 1 個既有測試 flake**（見 Minor-5） |
| 201 JVM tests | 未全量重跑（brief 只允許 capture）；round-13 實測 198 + 本輪 +3 | 算術一致；`CHANGELOG.md:9`、`docs/TEST_MATRIX.md:21`、`docs/zh-Hant/TEST_MATRIX.md:21` 都寫 27 / 201，`grep -c '    test("'` = 27 |
| instrumented storage 15 / crypto 2 / backup 2 | 未執行；`grep -rh @Test */src/androidTest` | 原始碼 15 / 2 / 2；本 diff 沒動 androidTest（所以 Minor-4 的 `maxId` 分頁沒有任何層級的測試，見新發現 Minor-4） |
| lint 0 errors | 未重跑 | 本 diff 只改 Kotlin 邏輯與一個 `@Query`，不引入新的 lint 類別；round-13 實測 14 模組 0 error |
| 文件 | 逐檔讀 | `CHANGELOG.md:7-9` `[Unreleased]` 有五項修復與 +3 (27) / 201；`docs/adr/0007…md:42` 與 `docs/zh-Hant/adr/0007…md:29` 加了「只有寫入成功後才會忘掉」；`CLAUDE.md:125` audit trail 改成 `round{10,11,12,13}`；`docs/reviews/README.md:23-24` 與 `docs/zh-Hant/reviews/README.md:21-22` 第 12、13 列；round-13 四份報告（brief、claude-subagent、gemini、kimi-no-verdict）已歸檔 |

---

## Verdict

### **APPROVE WITH MINOR FIXES**（0 Critical、0 Important、5 Minor）

round-13 的五個 Minor 全部有對應的程式碼改動，三個新測試的負面對照經我在複本實測**都是真的**（三個 guard 一次拿掉、跑一次，恰好紅這三個目標測試，失敗訊息各自對得上被拿掉的 guard）。沒有發現 Critical 或 Important 的回歸：brief 點名的幾個競態（`vaultGapOpen` 留著會不會漏掉下一次 lock-out、`coldStartLossSince` 會不會永遠清不掉、`dropHeld` 的 close 與 `settleColdStartGap` 互相踩、`coldStartGapId = null` 與下一次 `dropHeld` 的競爭、空 media 表的 `maxId`）我逐一推演，結論都是「不會遺失缺口、重複關閉是冪等的」。

五個新 Minor 裡最值得先處理的是 **Minor-1**（ADR 新加的那句話比程式碼保證的多：`releaseHeld()` 的兩筆 fire-and-forget 缺口寫入失敗就沒了——正是這個專案每輪都被抓的「docs ahead of code」）與 **Minor-2**（Minor-2 的修法把視窗縮小但沒關死，且「closed at once」測試本身依賴同一個視窗的時序）。**Minor-5** 是我實測抓到的既有 flaky 測試（`c6b6645` 的 bitmap 測試斷言依賴 `scope.launch` 的啟動順序，10 次跑 1 次紅），與本 commit 無關但直接牴觸 round-13「已改成確定性」的宣稱。

---

## Round-13 verification table（Claude subagent 的五個 Minor）

| # | round-13 發現 | 是否修復 | 證據（工作區 = `eae0003`） |
| --- | --- | :---: | --- |
| **Minor-1** | 兩處「金庫開了再補寫」在寫入*之前*就清掉記憶 | ✅ 已修 | `CaptureCoordinator.kt:408-421`：`settleColdStartGap()` 先讀 `since`，`written` 只在 `guarded {}` 整塊跑完才變 true，`:420` `if (written && since != null) coldStartLossSince = null`。`:222-245` vault-Ready collector 同型：`:229-234` 寫入、`:237-240` 成功才 `vaultGapOpen = false; vaultGapSince = null`。測試 `CaptureCoordinatorTest.kt:655-678`：`recordGap` 第一次拋 locked、第二次成功（`:662` `throws locked andThen Unit`），第一次 policy 載入 `exactly = 1`、第二次 `exactly = 2`、第三次 `stillHolds { exactly = 2 }`。負面對照實測：把 `:420` 改回無條件清除 → 第二次載入 `since == null` 不再寫 → `exactly = 2` 在 5 s 後紅（`1 matching calls`） |
| **Minor-2** | `dropHeld()` 的 `openGap` 可能在 settle 的 close 之後才 INSERT，留下 open row | ✅ 已修（視窗縮小，未關死，見新發現 Minor-2） | `:529-532`：`openGap` 回來後若 `sourcesLoaded` 已 true，立刻 `closeOpenGaps(COLD_START)` 並 `coldStartGapId = null`。`:117-118` `internal var coldStartTimeoutMs = COLD_START_TIMEOUT_MS`，`:447` `withTimeoutOrNull(coldStartTimeoutMs)`。測試 `:680-704`：timeout 200 ms、`openGap` 卡在 `gapGate`、policy 先載入（`closeOpenGaps` 第 1 次）、放行 `gapGate` 後第 2 次。負面對照實測：移除 `:529-532` → 停在 1 次 → `exactly = 2` 紅 |
| **Minor-3** | 舊 generation／暫停時跳過的 held 項目沒有自己的缺口，註解卻說「已有 gap 覆蓋」 | ✅ 已修 | `:480-489`：stale 項目若 `dropped == 0 && h.packageName in enabledPackages` 就把最早的 `heldAtEpochMs` 記到 `staleSince`；`:501-505` 記一筆 `COLD_START / BOUNDED` `[staleSince, now]`。`dropped == 0` guard 正確：溢位缺口 `:474-479` 的起點 `since` 是第一次驅逐、早於所有倖存者，已覆蓋。測試 `:706-723`：hold → `onDisconnected` → `onConnected`（新 generation）→ 金庫開 → `recordGap(COLD_START, BOUNDED)` 恰 1 次、`factory.create` 0 次。負面對照實測：移除 `:487` → 0 次 → 紅 |
| **Minor-4** | 快照之後 commit 的媒體會被匯出成孤兒 Media 記錄 | ✅ 已修（沒有測試，見新發現 Minor-4） | `Daos.kt:379-381` `SELECT COALESCE(MAX(id), 0) FROM media_blob`；`:383-384` `exportPage(afterId, maxId, limit, now)` 多了 `AND id <= :maxId`。`BackupService.kt:148` `val (expected, mediaMaxId) = db.withTransaction { … }`、`:186` `expected to db.mediaDao().maxId()`（交易內、row 分頁之後）、`:193` 交易外分頁帶 `mediaMaxId`。空表：`maxId() = 0` → `id > 0 AND id <= 0` 空集合 → 第一頁就 `break`（`:194`），`End.actual.media = 0`，正確。`withTransaction` 內沒有其他寫入者能 commit，所以交易尾端的 `MAX(id)` 等於快照當下的值，與 `:155` 的 `exportCount(now)` 一致。KDoc `:132-146` 誠實：「a blob deleted meanwhile is simply missing」 |
| **Minor-5** | 索引第 12 列把 manifest 媒體計數說成已修 | ✅ 已修 | `docs/reviews/README.md:23`「seven Minors … fixed in `d409d4b` … the manifest media count documented instead of changed」；`docs/zh-Hant/reviews/README.md:21`「七個 Minor … manifest 媒體計數改為文件說明而非更改格式」；第 13 列（`:24` / `:22`）也已加入 |

### brief 點名的競態逐一推演

| 問題 | 結論 |
| --- | --- |
| `vaultGapOpen` 在 close 失敗後留著 true，`process()` 會不會漏記下一次 lock-out？ | 不會漏。`process()` `:717-726` 在 `vaultGapOpen` 為 true 時跳過 `openGap`，但此時要嘛 DB 裡有一筆仍 open 的 UNKNOWN row（`openGap` 成功過，`endEpochMs IS NULL`，下一次 Ready 的 `closeOpenGaps` 才關），要嘛 `vaultGapSince` 還記著上一次的起點（`recordGap` 失敗才會留），兩者都以 `[起點, 下一次成功寫入的 now]` 覆蓋第二次 lock-out。代價只是 bounded 缺口比實際長，且一次暫時性的 close 失敗要等下一次 Ready（鎖／解鎖一輪或重啟）才重試——與修改前相同，沒有變差 |
| `coldStartLossSince` 會不會永遠清不掉？ | 只有在 `recordGap` **每一次**都失敗時（磁碟持續滿）才會，這正是想要的重試語意；它是一個 `Long?`，不累積。每次重試 `now` 都更新，所以最後寫下的缺口是 `[since, 成功那次的 now]`——誠實的過度近似（見其他觀察 6） |
| `dropHeld()` 在 lock 外的 close 與 `settleColdStartGap()` 互踩：會不會留 open、會不會關兩次？ | 關兩次是冪等的：`HealthRepository.closeOpenGaps` `:56-59` 只對 `openGaps(reasons)`（`Daos.kt:470-471` `WHERE endEpochMs IS NULL`）做 UPDATE，第二次找不到 row 就是空操作，也不會改動已關閉 row 的 `endEpochMs`。**會留 open 的殘餘視窗**存在，見新發現 Minor-2 |
| `coldStartGapId = null` 會不會與下一次 `dropHeld` 的 `if (coldStartGapId != null) return` 競爭？ | 推演三種交錯：(a) dropHeld#2 看到 7 → return，其項目全部在旗標翻轉前到達，而 row 7 的關閉時間在翻轉之後，`[start7, close]` 覆蓋它們；(b) dropHeld#2 看到 null → 自己開新 row，這是新的 lock-out，正確；(c) `onMaintenance(true)` `:569` 把 id 清空時 dropHeld#1 的 `openGap` 還卡在 `holder.db()`（reset 中）→ 回來時 INSERT 落在新金庫，`sourcesLoaded` 若仍 true（`onMaintenanceEnded` `:580` 還沒把它翻 false）就立刻關掉——缺口仍有界地記下，沒有遺失 |
| stale gap：`paused` 項目 vs `activeGeneration == null`、與 pause／disconnect gap 重疊、`dropped == 0` guard | `activeGeneration == null` 時 `offer()` `:609` 直接 return，所以 held 項目一定帶非 null 的 generation；之後 pause 把 `activeGeneration` 設 null → `h.generation != null` 一定成立，`|| paused` 是雙保險。round-13 曾提醒全域 `paused` 那一支標成 `COLD_START` 可能誤導；實作把它納入了，我認為標籤站得住：那則通知是在擷取開著、金庫還沒開的時候到達的，遺失的原因確實是冷啟動，暫停只是讓它沒機會被放行。重疊：`[heldAt, now]` 與 `[pauseAt/disconnectAt, …]` 重疊，`HealthScreen.kt:229-235` 只是列出 `state.gaps`、沒有加總，所以是外觀問題。`dropped == 0` guard 正確（見上表 Minor-3）。真正的問題是 over-report，見新發現 Minor-3 |
| `maxId` 空表 | 見上表 Minor-4：第一頁空、loop 立即結束 |
| 測試決定論 | 三個新測試都是 latch／`coVerify(timeout)`／`stillHolds`，沒有 `delay()` 排序。兩個殘餘的時序假設見新發現 Minor-2（closed-at-once）與其他觀察 2（settle-failed）；另一個**既有**測試實測 flake，見新發現 Minor-5 |

---

## Issues

### Critical

**無。**

### Important

**無。**

### Minor

#### Minor-1 — ADR-0007 新加的「a loss is only forgotten once that write succeeded」對 `releaseHeld()` 的兩筆缺口不成立（docs ahead of code）

**位置**：`docs/adr/0007-maintenance-gate-and-fail-closed-capture.md:39-42`（「Every loss — an eviction, a vault that does not open within 15 s, a loss the locked vault could not record at the time — is written as a bounded `COLD_START` gap as soon as the vault can be written, and a loss is only forgotten once that write succeeded」）、`docs/zh-Hant/adr/0007…md:27-29`；對照 `CaptureCoordinator.kt:474-479`（溢位缺口）與 `:501-505`（本輪新增的 stale 缺口），兩者都是 `scope.launch { guarded { health.recordGap(…) } }`，失敗就吞掉、**沒有任何保留**。

**故障情境**：金庫開了、`sources.sources()` 成功、`releaseHeld()` 算出溢位（或 stale）缺口並 `scope.launch` 寫入；幾毫秒後那個 INSERT 因磁碟已滿（`SQLiteFullException`）或 I/O 錯誤失敗 → `guarded` 吞掉 → 那段遺失永遠不會再被寫。這與 round-13 Minor-1 的情境 1 是同一個失敗模型，只是這兩條路徑本輪沒有跟著改；ADR 卻用「每一筆遺失……只有寫入成功後才會忘掉」的全稱句涵蓋了「eviction」。（reset 反而不是問題：`holder.db()` `DatabaseHolder.kt:61-66` 會等到新金庫 Ready 再 INSERT，除非重開失敗變 Locked。）

**為什麼是 Minor**：`releaseHeld()` 緊接在 `sources()` 成功之後執行，寫入幾乎一定成功；產品的缺口記錄本來就是 best-effort。但「文件不得跑在程式碼前面」是這個專案每一輪都被指出的事，而這句話是本 commit 新加的。

**修法**（二選一）：
1. 程式碼補齊：兩處改成 `var ok = false; guarded { recordGap(…); ok = true }; if (!ok && coldStartLossSince == null) coldStartLossSince = start`，讓它們走與 `dropHeld` `:526` 相同的保留機制，下一次 policy 載入補寫。
2. 或把 ADR 那句收窄成「a loss the *locked* vault could not record is only forgotten once that write succeeded」（zh 同步）。

---

#### Minor-2 — `dropHeld()` 的「立刻關閉」把視窗縮小但沒有關死；「closed at once」測試本身也靠同一個視窗的時序

**位置**：`CaptureCoordinator.kt:529-532`（`if (written && sourcesLoaded) guarded { closeOpenGaps(…); coldStartGapId = null }`）對照 `loadSourcePolicy()` `:392-401`（順序：`settleColdStartGap()` `:396` → `releaseHeld()` `:397` → `sourcesLoaded = true` `:398`）。

**故障情境**：t=15 s 逾時 → `dropHeld` → `openGap` 卡在 `holder.db()`。金庫 Ready → `loadSourcePolicy()`：settle 的 `closeOpenGaps` SELECT（`:414`）先跑、找不到 row；接著 `releaseHeld()`（有 256 個項目要 snapshot 時是毫秒級）；此時 `openGap` 的 INSERT 落地、`coldStartGapId = 7`、`written = true`、**讀到 `sourcesLoaded` 仍是 false** → 不關；然後 `:398` 才翻成 true。row 7 維持 open，直到下一次 policy 載入（來源變更、維護結束、或 App 重啟時的第一次 `settleColdStartGap`）。使用者在健康頁看到「擷取仍有缺口」而擷取其實正常。與 round-13 Minor-2 同一個現象，視窗從「settle 之後任何時間」縮到「settle 的 SELECT 之後、旗標翻轉之前」。

**修法**（把它關死，且不必拿 pipeline lock）：`coldStartGapId`（`:156-157`）與 `sourcesLoaded`（`:139-140`）都是 `@Volatile`。`dropHeld` 的順序是「寫 `coldStartGapId` → 讀 `sourcesLoaded`」，只要 `loadSourcePolicy()` 在 `:398` 之後補一句「寫 `sourcesLoaded = true` → 讀 `coldStartGapId`，非 null 就 `closeOpenGaps(COLD_START)` 並清空」，JMM 對 volatile 的循序一致性保證兩邊至少有一邊看到對方的寫入（Dekker 模式），兩邊都看到就是冪等的兩次關閉。

**測試決定論**：`CaptureCoordinatorTest.kt:680-704` 在 `coVerify(closeOpenGaps exactly = 1)` `:698` 回來後立刻 `gapGate.complete(Unit)` `:701`；但 `closeOpenGaps` 是在 settle 裡被呼叫的，那一刻 `sourcesLoaded` 還是 false，要等 `releaseHeld()`（空清單，微秒級）之後才翻。測試現在通過是因為「翻旗標」比「`gapGate.complete` → 派發 → `dropHeld` 恢復並讀旗標」快得多，不是因為有 happens-before。用上面的修法後，這條路徑不論誰先都會關閉；但要注意 `exactly = 2` `:702` 在「兩邊都看到對方」的交錯會變成 3 次，斷言應改成 `atLeast = 2`，或改成驗證最後狀態（例如再 emit 一次 policy 後 `stillHolds { closeOpenGaps 次數不再增加 }` 不適用——直接用 `atLeast = 2` 最簡單）。

---

#### Minor-3 — stale 缺口會 over-report：enabled-but-paused 來源也算，而且真機上 rebind 的 active resync 會讓同一則通知既被擷取又被記成缺口

**位置**：`CaptureCoordinator.kt:487`（`h.packageName in enabledPackages`，沒有排除 `pausedPackages`）；`onConnected` `:262-272`（每次連線都把 `service.activeNotifications` 以 `ACTIVE_RESYNC` 重新 `offer`，`SettingsRepository.kt:44` `captureActiveOnConnect` 預設 true）。

**故障情境 A（paused 來源）**：來源 X 啟用但使用者按了「暫停」（`pausedPackages` 含 X）。冷啟動時 X 的通知被 hold，中途 listener rebind → generation 換新 → 金庫開 → `releaseHeld()`：這則是 stale、`X in enabledPackages` 成立 → 記一筆 `COLD_START` 缺口。可是正常路徑 `:491` `pkg !in pausedPackages` 本來就不會擷取 X，這段「遺失」根本不是遺失。

**故障情境 B（resync 重複）**：t1 通知 N 被 hold（G1）。t2 系統 rebind（`onDisconnected` → `requestRebind` → `onConnected`），通知欄裡 N 還在 → resync 把 **同一個 sbn** 再 `offer` 一次 → `sourcesLoaded` 仍 false → 再 hold 一份（G2）。金庫開 → `releaseHeld()`：G1 那份 stale → 記缺口 `[t1, now]`；G2 那份被 snapshot、擷取成功。結果是：通知確實被擷取了，健康頁卻多一筆「可能漏掉」的冷啟動缺口。新測試 `:706-723` 把 `activeNotifications` stub 成 null（`Harness:130`），只涵蓋「rebind 前已被滑掉」的情境，沒有涵蓋真機上更常見的「還在通知欄」。

**為什麼是 Minor**：bounded 缺口的語意本來就是「這段期間**可能**漏掉」，多報一筆比少報安全；rebind 剛好落在金庫開啟那幾秒內也不常見。

**修法**：(a) `:487` 的條件改成 `h.packageName in enabledPackages && h.packageName !in pausedPackages`；(b) 同一批 `items` 裡先收集 `activeGeneration` 項目的 `sbn?.key`（`key` 內含 app 自訂的 `tag`，所以不能單純說它「不是內容」；這個建議之所以安全，是因為 `releaseHeld()` 跑在 policy 已知之後，而且 stale 項目只在 `packageName in enabledPackages` 時才會被考慮——只對啟用來源、在 policy 之後讀 key，不違反「policy 未知前不讀第三方通知」），stale 項目若 key 在集合裡就跳過不計；(c) 另外，round-13 建議缺口終點取 disconnect／pause 時間而非 `now`，現在用 `now` 會與 `LISTENER_DISCONNECTED`／`PAUSED_BY_USER` 缺口重疊——健康頁只列表不加總，可接受，但如果之後要做「缺口總時長」統計就會重複計算。

---

#### Minor-4 — `MediaDao.maxId()` 與帶上限的 `exportPage` 沒有任何層級的測試

**位置**：`Daos.kt:379-384`、`BackupService.kt:186 / :193`。`grep -rn "maxId\|exportPage(" */src/androidTest */src/test`：`BackupRoundTripTest.kt:124-134` 只用 `conversationDao` / `messageDao` 的 `exportPage`；instrumented backup 維持 2 個、storage 維持 15 個，本 diff 沒動 androidTest。

**故障情境**：下一次有人改 `exportPage` 的 SQL（例如把 `id <= :maxId` 改成 `<`，或把 `COALESCE` 拿掉讓空表回 null 而 Room 對 `Long` 回傳型別拋例外）沒有任何測試會紅；而 `maxId()` 在**空表**上回 0 是整個「第一頁就結束」邏輯的前提。

**修法**：在 `platform/storage` 的 androidTest 加一個 DAO 級測試（幾行）：空表 `maxId() shouldBe 0`、`exportPage(0, 0, 10, now)` 為空；插兩筆 blob 後 `exportPage(0, firstId, 10, now)` 只回第一筆。或在 `BackupRoundTripTest` 的匯出前後各插一筆媒體，驗證備份裡沒有第二筆 `Media`。記得同步 `docs/TEST_MATRIX.md` 的 15 / 2。

---

#### Minor-5 — 既有的「bitmaps in flight」測試斷言依賴 `scope.launch` 的啟動順序，實測 10 次紅 1 次（與本 commit 無關，但牴觸 round-13「已改成確定性」的宣稱）

**位置**：`CaptureCoordinatorTest.kt:727-756`，斷言 `:754` `bitmaps.take(8).all { it != null } shouldBe true` 與 `:755` `bitmaps[8] shouldBe null`；被測路徑 `CaptureCoordinator.kt:816-825`（每個事件 commit 後 `scope.launch { mediaCopier.copyPending(ids, bitmap) }`）。

**實測**：主 repo 3 次綠；scratchpad 複本第一次（剛編譯完、CPU 仍忙）**紅在 `:754`**（`expected:<true> but was:<false>`），之後複本還原成原始碼連跑 6 次綠。合計 10 跑 1 紅。那一次紅與我拿掉的三個 guard 無關（此測試不經過 stale／dropHeld／settle-since 任何一條路徑）。

**根因**：`bitmaps += secondArg()` `:736` 是在被 `scope.launch` 的 copy 協程裡執行；九個協程由 consumer 依序 launch，但多執行緒的 `Dispatchers.Default` 對 launched 協程的**啟動順序**沒有 FIFO 保證——這一點就足以說明「對共用 list 做順序相關的斷言」在構造上就是 flaky。consumer 處理 mock 事件只要微秒，九個協程排在一起時，第九個（placeholder、bitmap 為 null）可能先於前面某個跑到 `bitmaps +=`，於是 null 落在 index < 8（scheduler 內部怎麼挑下一個任務只是合理的解釋，我沒有在這裡驗證）。`c6b6645` 修的是「copy 2..7 可能提前完成並遞減計數」那個 flake，這是另一個、順序相關的 flake。

**修法**：斷言改成與順序無關：`bitmaps.size shouldBe 9; bitmaps.count { it == null } shouldBe 1`（上限「8 個在飛、第 9 個降級」的證明力不變；「第 9 個是被降級的那一個」由 `enqueue()` `:656-661` 的邏輯保證，不需要靠 list 順序證明）。

---

## 其他觀察

1. **`coldStartLossSince` 的 check-then-clear 理論上有 lost update。** `settleColdStartGap()` `:411/:420` 讀 `since` → 寫入 → 清成 null；`dropHeld()` `:526` 只在 `coldStartLossSince == null` 時設值。若 settle 讀到 t0、`recordGap(t0)` 成功、**在清成 null 之前** dropHeld#2 的 `openGap` 失敗（看到 t0 非 null → 不設），然後 settle 清成 null → dropHeld#2 的起點沒人記。需要金庫在 settle 寫入成功後的微秒內從 Ready 變成不可寫，現實上不會發生；若要絕對嚴謹，改 `AtomicReference<Long?>` 用 `compareAndSet(since, null)`，dropHeld 用 `compareAndSet(null, start)`。
2. **settle-failed 測試的一個時序假設**（`:666-671`）：`coVerify(openGap atLeast 1)` 在 mock **被呼叫**時就回來，`coldStartLossSince = start` `:526` 是那之後的下一行（奈秒）；測試接著 emit policy → 派發 → `sources()` → settle 讀 `since`（微秒以上）。實務上穩定，但沒有 happens-before；round-12 的既有測試 `:617-636` 用同一個模式。可接受，記錄用。
3. **vault-Ready collector 的既有微秒級競態**：`process()` `:717-726` 先 `vaultGapOpen = true` 再 `openGap`；若金庫剛好在 `journal` 拋 `VaultUnavailableException` 與 `openGap` 之間從 Locked 變 Ready，`openGap` 會成功 INSERT 一筆 open 的 UNKNOWN row，而同一個 Ready 觸發的 collector `:230` 的 `closeOpenGaps` SELECT 可能在 INSERT 之前跑 → row 留 open 到下一次 lock-out 的 Ready。Locked→Ready 只由使用者重試觸發，落在那幾微秒的機率可忽略；本 commit 沒有改變這一點。
4. **`coldStartTimeoutMs` 沒有 `@VisibleForTesting(otherwise = PRIVATE)`**（`:117-118`），同檔的 `snapshotFactory` `:114-115` 有。nit，加上去可讓 lint 在 main 程式碼誤用時報 `VisibleForTests`。
5. **`docs/SCOPE.md:76`** 列舉冷啟動缺口的三種來源（eviction、15 s 沒開、鎖定期間記不下來），沒有提到本輪新增的「跨越 disconnect／pause／maintenance 仍被保留的通知」。CHANGELOG 有寫；這是文件落後於程式碼（安全方向），下次順手補一句即可。
6. **補寫失敗後缺口會變長**：每次重試 `now` 都更新，最後寫下的是 `[since, 成功那次的 now]`；中間若金庫其實可用（磁碟滿但 journal 也會失敗），這段本來就是空白，bounded 精度下是誠實的過度近似。
7. **`releaseHeld()` 內 `h.packageName`**（`:487`）對 sbn 項目只讀 `sbn.packageName`，與 `offer()` `:614` 已有的用法一致，沒有違反「policy 未知前不讀內容」。
8. **`exportPage` 四參數版本沒有其他呼叫端**（grep 全 repo），改簽章不影響別處；`@Query` 改動不影響 Room schema JSON。
9. **值得肯定的地方**：
   - 三個負面對照都是**真的**：在複本裡一次拿掉三個 guard 跑一次，紅的正是那三個目標測試、訊息各自對得上，其他 23 個不受影響（除了 Minor-5 那個既有 flake）。
   - `written` 旗標放在 inline `guarded {}` 內最後一行，`CancellationException` 仍會往上拋（`:878-885`），所以 `collectLatest` 取消或 reset 打斷時記憶會保留、下一次重試——與 CHANGELOG 的描述一致。
   - `dropHeld` 的立即關閉用的是 by-reason 冪等 close，沒有引入「關錯 row」的可能。
   - `maxId` 放在交易**尾端**而不是開頭：因為 row 分頁在交易內、沒有其他寫入者能 commit，兩者等價，但放尾端讓讀者一眼看出它與分頁同一快照。
   - KDoc `:132-146` 沒有為了好看而過度承諾（明說「a blob deleted meanwhile is simply missing」）。

---

## Assessment

**APPROVE WITH MINOR FIXES。** round-13 的五個 Minor 都修好了、負面對照經實測為真、27 個 capture 測試 3 / 3 綠、文件計數（27 / 201）與程式碼一致。建議下一個 commit 先做 **Minor-1**（兩處各三行，或改一句 ADR）與 **Minor-2**（`loadSourcePolicy()` 翻旗標後補一個 volatile 重檢，測試改 `atLeast = 2`），順手做 **Minor-5**（一行斷言）與 Minor-3 的 (a)；Minor-3 的 (b) 與 Minor-4 排進下一輪即可。這些都不需要 re-release。
