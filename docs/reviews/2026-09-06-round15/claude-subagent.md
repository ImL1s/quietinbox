# Review round 15 — Claude subagent（round-14 五個 Minor 修復之迷你再審）

- **審查範圍**：`git diff eae0003..6225719`（一個 commit `6225719`，工作區 HEAD 即為它；`git status` 只有未追蹤的 `docs/reviews/2026-09-06-round15/`，其中目前只有 `brief.md` 與 `kimi-blocked.md`）。程式碼改動只在三個檔案：`CaptureCoordinator.kt` +34/−11、`CaptureCoordinatorTest.kt` +88/−4、新增 `platform/storage/src/androidTest/.../MediaExportBoundTest.kt`（97 行）；其餘是 CHANGELOG、SCOPE、TEST_MATRIX（en/zh）、CLAUDE.md、reviews index（en/zh）與 round-14 四份報告歸檔。
- **審查性質**：唯讀。沒有改任何 repo 檔案、沒有跑會改狀態的 git 指令、沒有碰裝置或跑 instrumented test。
- **超出 brief 明列指令的部分**（皆唯讀、在 repo 之外或只是編譯）：(1) `./gradlew :platform:storage:compileDebugAndroidTestKotlin`——只編譯 androidTest 原始碼，不跑、不接裝置，用來確認新的 `MediaExportBoundTest` 至少能編譯；(2) 為了驗證「三個新測試拿掉 guard 會紅」，用 `rsync`（排除 `build`／`.gradle`／`.git`／`.omc`／`.kotlin`）把 repo 複製到 session scratchpad，在**複本**裡一次拿掉三個 guard 再跑 `:platform:capture:testDebugUnitTest`，之後刪除複本。下面所有 file:line 以 `6225719` 的內容為準。

## 本地實測與宣稱核對

| 宣稱 | 驗證方式 | 結果 |
| --- | --- | --- |
| capture 30 個 JVM test 全綠、連續多次穩定 | 主 repo `./gradlew :platform:capture:testDebugUnitTest -q` 一次（全新編譯）+ `--rerun` 兩次，讀 JUnit XML | **3 / 3 綠**，每次 30 tests / 0 failures / 0 errors / 0 skipped（時間戳 05:29:57、05:37:33、05:37:43，確認三次都真的重跑） |
| 三個新測試各有負面對照 | 在 scratchpad 複本**一次同時**拿掉三個 guard（`:529` 的 `coldStartLossSince` 保留、`:405-409` 旗標翻轉後的重檢、`:498-499` 的 paused／`liveKeys` 排除）再跑整個 suite 一次（不是分三次各拿一個） | 30 tests / **恰好 3 failures**，紅的正是三個新測試，訊息與被拿掉的 guard 一一吻合（對應關係由訊息判定，非隔離執行）：flag-flip 測試 `closeOpenGaps(…[COLD_START]…) 1 matching calls found, but needs at least 2 calls`；overflow-kept 測試 `recordGap(…COLD_START, BOUNDED…) One matching call found, but needs exactly 2 calls`；no-gap 測試 `recordGap(…COLD_START…) should not be called`。其餘 27 個綠——包括原本的「closed at once」測試，因為 `dropHeld` 自己的關閉（`:554-557`）還在，這正是 round-14 說「視窗縮小但沒關死」的那一半 |
| 204 JVM tests | brief 只允許跑 capture；我把各模組 `build/test-results` 的 JUnit XML 加總 | **加總 = 204，0 fail / 0 error / 0 skip**。注意：capture（30）是我這次跑的；storage／backup／feature／app 的 XML 時間戳是今天 05:02–05:27，其餘模組（core/*、parsers、crypto）是 03:07——這些模組本 commit 沒有動，數字仍有效，但「204 全綠」是加總不是我一次跑出來的 |
| instrumented storage 16 / crypto 2 / backup 2 | 未執行（brief 禁止）；`grep -rh @Test */src/androidTest` + 編譯 | 原始碼 **16 / 2 / 2**；`compileDebugAndroidTestKotlin` exit 0，所以 `MediaExportBoundTest` 能編譯。**AVD 上 16 綠是 lead 的宣稱，不是我的驗證** |
| lint 0 errors | 未重跑 | 本 diff 只改 Kotlin 邏輯、加一個 `@VisibleForTesting`、一個 androidTest；不引入新的 lint 類別 |
| 文件 | 逐檔讀 | `CHANGELOG.md:9-10`（round-14 五項 + `+6 (30)`；0.1.1 那段 `:44` 是 24，24+3+3=30 算術一致；204；storage 16）、`docs/SCOPE.md:76`（加了「held across a disconnect / pause / maintenance run」與「forgotten only once that write succeeded」）、`docs/TEST_MATRIX.md:16`／`:21` 與 `docs/zh-Hant/TEST_MATRIX.md:16`／`:21`（16 個、30 個，逐項描述與三個新測試對得上）、`CLAUDE.md:125`（`round{10,11,12,13,14}`）、`docs/reviews/README.md:24-25` 與 `docs/zh-Hant/reviews/README.md:22-23`（第 13 列補上 `eae0003`，第 14 列新增）、round-14 四份報告已歸檔 |

---

## Verdict

### **APPROVE WITH MINOR FIXES**（0 Critical、0 Important、3 Minor）

round-14 的五個 Minor 全部有對應的程式碼改動，三個新測試的負面對照經我在複本實測**都是真的**（一次拿掉三個 guard、跑一次，恰好紅這三個目標測試，訊息各自對得上）。brief 點名的幾個競態（`coldStartLossSince` 的 lost update、`liveKeys` 在全域暫停下的行為、讀 `sbn.key` 是否違規、旗標翻轉後重檢的成本、兩處 `coldStartGapId = null` 的冪等性、三個新測試的決定論、文件計數）我逐一推演與實測，結論在下面。

沒有 Critical 或 Important。三個新 Minor 都集中在 `releaseHeld()` 的 `liveKeys` 抑制（本 commit 新加的邏輯）：**Minor-1** 是真正的回歸——`liveKeys` 在迴圈前用當時的 `activeGeneration`／`paused` 算一次，迴圈裡卻逐項重讀；一個 disconnect 或 pause 落在這兩點之間，該項目會被**自己的 key** 抑制，既不入列、也不記缺口。在 `eae0003` 同一交錯會記一筆缺口。視窗只有 `releaseHeld` 迴圈那幾毫秒，而且若通知還在通知欄，rebind 的 resync 會再把它撿回來，所以我評 Minor 而非 Important；但它是往「隱藏遺失」方向走的回歸，這是專案唯一不能退讓的規則，建議下一個 commit 先修（修法很小）。**Minor-2**（同 key 不等於同一則：app 用同一個 key 覆寫內容後，舊內容的遺失被當成「已再次擷取」）與 **Minor-3**（`liveKeys` 對從未啟用的 app 也讀 `key`）各是一行的事。

---

## Round-14 verification table（Claude subagent 的五個 Minor + 觀察 4、5）

| # | round-14 發現 | 是否修復 | 證據（工作區 = `6225719`） |
| --- | --- | :---: | --- |
| **Minor-1** | `releaseHeld()` 的溢位與 stale 缺口寫入是 fire-and-forget，失敗就沒了；ADR 的「只有寫入成功後才會忘掉」對它們不成立 | ✅ 已修 | `CaptureCoordinator.kt:483-486`（溢位）與 `:513`（stale）都改走 `recordColdStartLoss(start)`；`:521-531`：`scope.launch { guarded { recordGap(…); written = true }; if (!written && coldStartLossSince == null) coldStartLossSince = start ?: now }`——`written` 在 `guarded {}` 內最後一行，`CancellationException` 仍往上拋（`:903-910`）。下一次 `settleColdStartGap()` `:420` 讀到它、`:424` 寫、`:429` 成功才清。測試 `CaptureCoordinatorTest.kt:740-764`：300 則 → 44 則驅逐 → `recordGap` 第一次拋 `IllegalStateException("full")` 再成功（`:750`）→ `exactly = 1` `:756` → 256 則入列 `:757` → 第二次 policy 載入 `exactly = 2` `:761` → 第三次 `stillHolds { exactly = 2 }` `:763`。負面對照實測：拿掉 `:529` → 第二次載入 `since == null` 不寫 → `exactly = 2` 紅（`One matching call found, but needs exactly 2 calls`）。ADR `docs/adr/0007…md:42` 那句現在對每一種冷啟動遺失都成立 |
| **Minor-2** | `dropHeld()` 的立即關閉把視窗縮小但沒關死（row 落在 settle 的 SELECT 之後、旗標翻轉之前就留 open） | ✅ 已修 | `:399` `sourcesLoaded = true`（volatile 寫）→ `:405` `val late = coldStartGapId`（volatile 讀）→ `:406-409` 非 null 就 `closeOpenGaps(COLD_START)` 並清空；`dropHeld` `:547` 先寫 `coldStartGapId`（volatile）再 `:554` 讀 `sourcesLoaded`（volatile）。兩邊都是「寫 A → 讀 B」對「寫 B → 讀 A」，JMM 對 volatile 的 SC 保證至少一邊看到對方（Dekker）；兩邊都看到就是兩次 by-reason 關閉，`HealthRepository.kt:56-59` 只對 `openGaps`（`Daos.kt:470-471` `endEpochMs IS NULL`）UPDATE，冪等。測試 `:709-738`：timeout 200 ms、`openGap` 卡 `gapGate`、settle 的第一次 `closeOpenGaps` 卡 `closeGate`（`:719-721`）；`:731` 放行 row → `stillHolds { exactly = 1 }` `:732`（此刻旗標必為 false：翻轉在 `:399`，排在被卡住的 settle 之後，鎖又被 settle 持有，沒有別的路徑能翻它）→ `:734` 放行 settle → `atLeast = 2` `:736`。負面對照實測：拿掉 `:405-409` → 停在 1 次 → 紅。原「closed at once」測試 `:705` 改 `atLeast = 2`，正確（兩邊都看到對方時是 3 次） |
| **Minor-3** | stale 缺口 over-report：enabled-but-paused 來源也算；rebind 的 resync 讓同一則既被擷取又被記缺口 | ✅ 已修（引入新的 Minor-1／2／3，見下） | `:498` `capturable = pkg in enabledPackages && pkg !in pausedPackages`；`:489` `liveKeys` 收集目前 generation、未暫停項目的 `sbn?.key`；`:499` stale 項目要 `dropped == 0 && capturable && key !in liveKeys` 才計入 `staleSince`。`sbnOf` `:524-526` 給每個 mock 不同的 key。測試 `:766-788`：hold(ENABLED, key 42) → hold(PAUSED_PKG) → `onDisconnected` → `onConnected` → 同一個 sbn 物件再 `onPosted`（新 generation）→ 金庫開 → 只 journal `evt-live`、`recordGap(COLD_START)` 0 次。負面對照實測：拿掉兩個排除 → G1 那份與 paused 那份都計入 → `should not be called` 紅 |
| **Minor-4** | `MediaDao.maxId()` 與帶上限的 `exportPage` 沒有任何層級的測試 | ✅ 已修（我只驗到編譯） | `MediaExportBoundTest.kt:380-409`：空表 `maxId() shouldBe 0L`、`exportPage(0, 0, 10, now)` 空（`:388-389`）；一則訊息（`retentionMs = null`，所以 `expiresAtEpochMs IS NULL` 通過 `Daos.kt:383` 的子查詢；`mediaAllowed = false`，所以表裡只有測試插的兩筆）；`bound = maxId() = first`（`:398-399`）；第二筆插入後 `maxId() = second`（`:401-402`）；`exportPage(0, bound)` 只回 first、`exportPage(first, bound)` 空、`exportPage(0, second)` 回兩筆（`:404-406`）——與 `id > :afterId AND id <= :maxId` 的語意逐一對應。`@After` 的 `runBlocking` 最後是 `wipe()`（Unit），`@Test` 以 `Unit` 結尾（`:408`），符合 CLAUDE.md 的 JUnit 4 規則。`compileDebugAndroidTestKotlin` 通過；AVD 執行結果是 lead 的宣稱。`docs/TEST_MATRIX.md:16` 與 zh `:16` 同步為 16 |
| **Minor-5** | bitmap 上限測試依賴 `scope.launch` 的啟動順序（10 跑 1 紅） | ✅ 已修 | `:837-839`：`awaitUntil { bitmaps.size shouldBe 9 }` 後只斷言 `bitmaps.count { it == null } shouldBe 1`，與順序無關；「第 9 個是被降級的那一個」由 `enqueue()` `:681-687` 的計數邏輯保證。三次實跑皆綠 |
| 觀察 4 | `coldStartTimeoutMs` 沒有 `@VisibleForTesting` | ✅ | `:118-119` |
| 觀察 5 | `docs/SCOPE.md` 冷啟動一列沒列 stale-held 來源 | ✅ | `docs/SCOPE.md:76` 加了「a notification of a capturable source held across a disconnect / pause / maintenance run」與「forgotten only once that write succeeded」 |

### brief 點名的競態逐一回答

| 問題 | 結論 |
| --- | --- |
| `recordColdStartLoss` 在鎖外設 `coldStartLossSince`，會不會與 `settleColdStartGap()` 的 `if (written && since != null) coldStartLossSince = null` 形成 lost update？ | **交錯存在，但不會遺失任何缺口。** 情境：settle 讀到 `since = t0`、`recordGap(t0, now_s)` 成功、在 `:429` 清空之前，某個先前 launch 的寫入 W（起點 t1、launch 時的 `now_w`）失敗，看到非 null 就不設；settle 清空 → t1 沒人記。關鍵是**單調性**：`held` 在每次 release／drop 都清空（`:480-482`、`:539-541`），所以任何較晚 launch 的 W，其項目都在較早那批之後才到達，t1 ≥ t0；而 settle 的 `now_s` 一定晚於 W 在 launch 時取的 `now_w`（W 先 launch 才有機會與後來的 settle 並行）。於是 settle 已寫下的 `[t0, now_s]` ⊇ `[t1, now_w]`，W 的遺失窗早就被涵蓋。另一個變體——兩個並行失敗的 W 都看到 null、後設的贏——需要兩個 launch 的寫入同時失敗，而同一次 policy 載入最多 launch 一筆（stale 缺口只在 `dropped == 0` 時算，`:499`），跨兩次載入的 W 要在磁碟滿的快速失敗下還活著才行；就算發生，也只是起點晚了同一次冷啟動內兩批到達時間的差。**不是回歸**（修改前是整筆忘掉）。若要絕對嚴謹，`AtomicReference<Long?>` + `updateAndGet { old -> if (old == null) start else minOf(old, start) }`、settle 用 `compareAndSet(since, null)`——選配的衛生，不列為發現 |
| `liveKeys` 在全域 `paused == true` 時是否正確？全域暫停該不該抑制 stale 缺口？ | 正確，**不該抑制**。全域 `paused` 只存在記憶體（`HealthViewModel.kt:103` → `setPaused`，`SettingsRepository` 沒有對應的持久化鍵），process 起來永遠是 false，所以一則在全域暫停下被釋放的 held 通知，到達時擷取一定是開著的；它的遺失原因是金庫沒開（冷啟動），`PAUSED_BY_USER` 缺口的起點在暫停那一刻、晚於它的到達。`liveKeys` 為空也對：暫停下什麼都不會入列，沒有任何一份是「live」的。相對地，per-source pause 是持久化狀態，policy 載入時已暫停的來源在到達當下就是暫停的，不算遺失——這個不對稱是合理的 |
| 讀 `sbn.key` 是否違反「policy 未知前不讀第三方通知」？ | **接受。** `releaseHeld()` 只在 `loadSourcePolicy()` 設好 `enabledPackages`／`pausedPackages` 之後才跑（`:395-398`），policy 已知。`key` 是 framework 組出來的 `user|pkg|id|tag|uid`——`tag`／`id` 是 app 自選的**識別子**，不是訊息內容；`SnapshotFactory.kt:139` 建 snapshot 時本來就讀同一個欄位（`:144` 也讀 `postTime`）；`liveKeys` 只活在這個函式的 HashSet 裡，不落地。但範圍比需要的大，見 Minor-3 |
| 旗標翻轉後的重檢會不會在每次 policy 載入時都跑 `closeOpenGaps`？ | 不會。`:405-406` 只在 `coldStartGapId != null` 時進 `guarded {}`；正常路徑（沒有 lock-out）是一次 volatile 讀，成本為零。非 null 時是一次 SELECT + 至多幾筆 UPDATE（正常只有 1 列 open），在鎖內但與 settle 的 `closeOpenGaps` 同量級 |
| `loadSourcePolicy` 的 `coldStartGapId = null` 與 `dropHeld` 自己關閉後的 null 是否冪等？ | 是。兩邊都只寫 null、都先做 by-reason 關閉。唯一的交叉影響是 `:408` 可能蓋掉一個**更晚**的 `dropHeld` 寫進去的新 id——但更晚的 `dropHeld` 需要 `sourcesLoaded` 再次為 false（只有 `onMaintenance(false)` `:605` 會做）、再等 15 s 逾時，而 maintenance 是 exclusive 持鎖，`loadSourcePolicy` 又在鎖內，時間上排不到一起；就算排到，結果是多一列 open row、下一次 settle 的 by-reason 關閉會收掉，不是遺失 |
| 三個新測試的決定論 | 全部是 latch／`coVerify(timeout)`／`stillHolds`，沒有 `delay()` 排序。flag-flip 測試的 `stillHolds { exactly = 1 }`（`:732`）是**真的有 happens-before**：旗標翻轉在被 `closeGate` 卡住的 settle 之後，鎖又在 settle 手上，沒有第二條路徑能翻它。overflow-kept 測試有一個與 round-13 settle-failed 測試相同的時序假設：`coldStartLossSince = since`（`:529`，`recordGap` 拋出後的下一行、奈秒級）要在第二次 policy 載入的 settle 讀 `:420` 之前落地，中間隔著 256 則入列（`:757`，毫秒級以上），實務上穩定。no-gap 測試三次 `onPosted` 都是同步的，`held` 在 `vaultOpen.complete` 前就定型；`recordColdStartLoss` 在迴圈之後才 launch（`:513`），`journaled == [evt-live]` 之後再 `stillHolds` 300 ms 足以抓到誤記。三次實跑 30/30 |
| 文件 vs 程式碼計數（30／204／16） | 30：`grep -c '    test("'` = 30，XML = 30。204：XML 加總 = 204（見上表的時間戳說明）。16：`grep @Test` = 16、編譯通過。en／zh TEST_MATRIX 逐項描述與三個新測試對得上 |

---

## Issues

### Critical

**無。**

### Important

**無。**

### Minor

#### Minor-1 — `liveKeys` 用迴圈前那一刻的 `activeGeneration`／`paused` 算，迴圈裡逐項重讀：一個 disconnect 或 pause 落在中間，該項目被自己的 key 抑制，既不入列也不記缺口（相對 `eae0003` 是往「隱藏遺失」方向的回歸）

**位置**：`CaptureCoordinator.kt:489`（`liveKeys = items.mapNotNullTo(HashSet()) { if (it.generation == activeGeneration && !paused) it.sbn?.key else null }`）對照 `:496`（`if (h.generation != activeGeneration || paused)`，每個項目各讀一次 volatile）與 `:499`（`h.sbn?.key !in liveKeys`）。`onDisconnected()` `:280` 與 `setPaused()` `:314`／`:319` 都是不拿 pipeline lock 的 callback，可以落在 `:489` 與 `:496` 之間；`releaseHeld()` 雖在鎖內（`:398`、`:401`、`:460`、`:469` 的呼叫端），鎖擋不住這兩個寫入。

**故障情境**：通知 N（啟用來源、目前 generation G1）被 hold；金庫開 → `releaseHeld()`；`:489` 把 N.key 放進 `liveKeys`；此刻 listener rebind（`onDisconnected` → `activeGeneration = null`）——冷啟動那幾秒內的 rebind flap 正是 round-13／14 一直在處理的情境；迴圈走到 N：`G1 != null` → stale 分支 → `capturable` 為 true、但 `N.key ∈ liveKeys` → 不算 `staleSince` → `continue`。N 沒有被 snapshot、沒有入列、也沒有缺口。在 `eae0003`（沒有 key 抑制）同一交錯會記一筆 `[heldAt, now]` 的 `COLD_START` 缺口。若是 `setPaused(true)` 落在中間，效果放大到**整批**：`liveKeys` 在 `paused == false` 時已含所有目前 generation 的 key，迴圈看到 `paused == true` 把每一則都判 stale，每一則都被自己的 key 抑制，256 則的遺失一筆缺口都不留（`eae0003` 會記一筆涵蓋整批的缺口）。

**為什麼是 Minor 而不是 Important**：視窗只有 `releaseHeld` 迴圈那幾毫秒（`SnapshotFactory.create` 不解碼 bitmap，`SnapshotFactory.kt:95-97`）；disconnect 之後如果 N 還在通知欄，`onConnected` 的 resync（`:263`、`:271-273`）會在 `sourcesLoaded == true` 下直接把它擷取回來，真正遺失的是「在 rebind 前就被滑掉」那一種；round-13 把視窗大得多的同型漏洞（整個 hold 期間的 stale 通知沒有缺口）評為 Minor，這裡按同一尺度。但它是本 commit 新引入、往專案唯一不能退讓的方向走的回歸，建議下一個 commit 第一個修。

**修法**（二選一，都很小）：
1. **正解**：不要用「迴圈前的快照」推論「下面會被擷取」——改成兩趟：第一趟照現在的分類走，把**實際 `enqueue` 成功**的項目的 key 收進 `enqueuedKeys`，並記下每個項目在第一趟被判為 stale 與否；第二趟只對第一趟判為 stale 的 capturable 項目，`key !in enqueuedKeys` 才計 `staleSince`。這同時補掉一個小姊妹洞：live 那份 `snapshotFactory.create` 失敗（`:505-510` → `captureErrors++` → `continue`，沒有入列）時，stale 那份的缺口現在也會被抑制。
2. **最小修**：在 `:489` 之前把 `activeGeneration` 與 `paused` 各讀一次進 local，`:489` 與 `:496` 都用同一組 local。這樣中途翻轉的項目會被當 live 入列，由 consumer 的 `admitted()` `:709` 以 generation 圍籬丟掉（`droppedAfterRevoke`，與現有排隊中事件遇到 disconnect 的行為一致），不會再被自己的 key 抑制。

**測試**：現有測試用同步的 `onDisconnected()` 在 `vaultOpen.complete` 之前呼叫，抓不到這個交錯。可用一個卡在 `factory.create` 的 latch：hold 兩則（同來源、不同 key），`create` 第一則時 `onDisconnected()`，放行後斷言第二則得到 `recordGap(COLD_START)` 一次（修法 1）或 `droppedAfterRevoke` 增加且不記缺口（修法 2）。

---

#### Minor-2 — 同一個 `key` 不代表同一則：app 用同一個 key 覆寫內容後，舊內容的遺失被當成「已再次擷取」

**位置**：`CaptureCoordinator.kt:489`／`:499`（只比 `sbn.key`）。`StatusBarNotification.key` 是 `user|pkg|id|tag|uid`，app 更新同一則通知時 key 不變；`postTime` 才會變（系統在每次 post／update 時設定，`SnapshotFactory.kt:144` 已把它當 framework metadata 讀）。

**故障情境**：BigText 通知 K「Alice: hi」在 G1 被 hold；listener 斷線期間 app 把 K 更新成「Alice: are you there?」（內容整個替換，BigText 沒有 MessagingStyle 的累積）；rebind 的 resync 在 G2 再 offer K（新內容）。`releaseHeld()`：G2 那份入列，G1 那份因 `key ∈ liveKeys` 被判「無遺失」。可是「hi」這則 app 確實 post 過、若當時 listener 正常會被擷取成自己的事件，現在既沒擷取也沒缺口。MessagingStyle 的 app 通常會把舊訊息一起帶在更新裡，所以主要影響 BigText／單行樣式，且要 rebind 剛好落在冷啟動 15 s 內；但這是「缺口被隱藏」，方向與 Minor-1 相同。

**修法**：`liveKeys` 收 `key to postTime` 的 pair（或字串串接），stale 項目要 key **與** postTime 都相同才視為同一次 post。`postTime` 與 `key` 同屬 framework metadata，不多讀任何內容；測試裡 relaxed mock 的 `postTime` 都是 0、同一物件再 offer 也相等，現有 no-gap 測試不需改。

---

#### Minor-3 — `liveKeys` 對從未啟用的 app 也讀 `sbn.key`，超出 `Held` KDoc 承諾的範圍

**位置**：`CaptureCoordinator.kt:489`——`items.mapNotNullTo(HashSet()) { if (it.generation == activeGeneration && !paused) it.sbn?.key … }` 沒有套件過濾；`Held` 的 KDoc `:69-73`「Only the framework object is kept — nothing is read from it — so a notification from an app the user never enabled is never materialised」。

**情境**：使用者從未啟用的 app 的通知在冷啟動被 hold，policy 載入後 `:502-503` 只讀 `packageName` 就丟掉——但 `:489` 已先讀了它的 `key`（含 app 自選的 `tag`）。我接受 `key` 是 metadata 而非內容（見上表），這只是把 stale 排除用的集合限制在真正會被比對的範圍：stale 項目只有 `capturable` 才會查 `liveKeys`（`:498-499`），所以 `liveKeys` 只需要 capturable 套件的 key。

**修法**：`:489` 加上 `it.packageName in enabledPackages && it.packageName !in pausedPackages` 的條件（與 `:498` 相同）。零成本，讓「從未啟用的 app 只被讀 `packageName`」這句話繼續為真。

---

## 其他觀察

1. **`docs/reviews/README.md:25` 與 zh `:23` 第 14 列已寫「re-reviewed in round 15」、修復 commit 欄是「follow-up commit」**：round-15 目前只有 `brief.md` 與 `kimi-blocked.md`（未追蹤）。歸檔 round-15 報告時請一起把第 14 列的 commit 改成 `6225719`，並補第 15 列——這是每輪都被點名的「docs ahead of code」型態，趁同一個 commit 收掉。
2. **`coldStartLossSince` 的 check-then-set**：如上表所述，單調性讓 lost update 不遺失任何區間；若要把它做成明顯正確，`AtomicReference` + `updateAndGet(min)` / `compareAndSet` 是幾行的事，選配。
3. **stale 缺口的終點仍是 `now`**（`:522`、`:526`），與 `LISTENER_DISCONNECTED`／`PAUSED_BY_USER` 缺口重疊。健康頁只列表不加總，可接受；round-14 已記錄，維持觀察。
4. **測試裡沒 stub `key` 的 sbn mock**（`:570`、`:754`）：relaxed mock 的 `key` 是空字串，300 個 mock 共用同一個 key。這兩個測試都沒有 stale 項目（沒有 disconnect／pause），所以 `liveKeys` 沒有作用；但未來若有人在這兩個測試裡加 disconnect，stale 項目會被空字串 key 抑制而不自知。建議 `sbnOf` 成為唯一的 sbn mock 入口（把 `id` 也做成參數），避免這個陷阱。
5. **`recordColdStartLoss` 在鎖內只 launch 不等待**（`:523`），符合「不要在 `work {}`／鎖內做長事」的規則；`liveKeys` 是 O(n)、n ≤ 256。
6. **`@VisibleForTesting(otherwise = PRIVATE)` 加在 `internal var coldStartTimeoutMs`**（`:118-119`）：main 程式碼只有 `:456` 一處用它、在同一個 class 內，lint 不會報；測試 `:691`、`:722` 從 test source set 設定，允許。
7. **值得肯定的地方**：
   - 旗標翻轉後的重檢用的是「寫旗標 → 讀 id」對「寫 id → 讀旗標」的 Dekker 型式，而不是再拿一把鎖；註解 `:402-404` 把推理寫在程式碼旁邊。
   - `recordColdStartLoss` 把三條遺失路徑（溢位、stale、lock-out 的 `dropHeld` `:551`）收斂成同一個「失敗就留在 `coldStartLossSince`」機制，ADR-0007 `:42` 那句從此不再說過頭。
   - flag-flip 測試用兩層 latch（`gapGate` + `closeGate`）把「row 落在 settle 的 close 之後、旗標之前」這個微秒級視窗做成確定性的，`stillHolds { exactly = 1 }` 那一步有真正的 happens-before。
   - `MediaExportBoundTest` 用 `mediaAllowed = false` 讓表裡只有測試自己插的兩筆，斷言直接對到 SQL 的 `>`／`<=` 邊界。
   - bitmap 測試改成與順序無關的斷言，沒有削弱它證明的上限。

---

## Assessment

**APPROVE WITH MINOR FIXES。** round-14 的五個 Minor（加觀察 4、5）都修好了、負面對照經實測為真、30 個 capture 測試 3 / 3 綠、androidTest 能編譯、文件計數（30 / 204 / 16）與程式碼一致。三個新 Minor 都在 `liveKeys` 這十行裡：**Minor-1**（分類用同一組 local，或改兩趟以實際入列的 key 為準）是回歸、先修；**Minor-2**（key + postTime）與 **Minor-3**（只收 capturable 套件的 key）各一行，順手一起做。這些都不需要 re-release；修完補一個「disconnect 落在 release 中途」的協調器測試（有負面對照）與 index 第 14／15 列即可。
