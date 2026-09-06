# Review round 16 — Claude subagent（round-15 三個 Minor 修復之迷你再審）

- **審查範圍**：`git diff 6225719..a9609e0`（一個 commit `a9609e0`，工作區 HEAD 即為它；`git status` 只有未追蹤的 `docs/reviews/2026-09-06-round16/`，內含 `brief.md` 與 `kimi-blocked.md`）。程式碼改動只在兩個檔案：`CaptureCoordinator.kt` +30/−13（`Held.postId`、`releaseHeld()`）、`CaptureCoordinatorTest.kt` +60/−7（`sbnOf` 成為唯一 mock、兩個新測試）；其餘是 CHANGELOG、TEST_MATRIX（en/zh）、CLAUDE.md、reviews index（en/zh）與 round-15 四份報告歸檔。
- **審查性質**：唯讀。沒有改任何 repo 檔案、沒有跑會改狀態的 git 指令、沒有碰裝置或跑 instrumented test。
- **超出 brief 明列指令的部分**（皆唯讀、在 repo 之外）：為了驗證「兩個新測試拿掉 guard 會紅」，用 `rsync`（排除 `build`／`.gradle`／`.git`／`.omc`／`.kotlin`）把 repo 複製到 session scratchpad 的 `r16-review/copy`，在**複本**裡逐一套用三個突變（A：`postId` 去掉 post time；B：迴圈逐項重讀 `activeGeneration`／`paused`；C：stale 檢查放寬到只看 `enabledPackages` 並拿掉 `queuedPosts` 檢查），各跑一次 `:platform:capture:testDebugUnitTest`，之後刪除複本，只留 log／XML。下面所有 file:line 以 `a9609e0` 的內容為準。

## 本地實測與宣稱核對

| 宣稱 | 驗證方式 | 結果 |
| --- | --- | --- |
| capture 32 個 JVM test 全綠、連跑三次穩定 | 主 repo `./gradlew :platform:capture:testDebugUnitTest --rerun -q` 三次，讀 JUnit XML 的 `timestamp` 確認三次都真的重跑 | **3 / 3 綠**，每次 32 tests / 0 failures / 0 errors / 0 skipped（UTC 05:52:37、05:57:24、05:57:40）。註：第一輪嘗試的第 2、3 次 rerun 在 `testDebugUnitTest` 任務丟 `java.io.EOFException`（同一時間另一個 round-16 reviewer 也在跑同一個 build 目錄，二進位結果檔被搶寫），不是測試失敗；隔開後重跑即正常。兩個新測試各 0.02 s／0.32 s，沒有等到逾時 |
| 兩個新測試各有負面對照 | scratchpad 複本，三個突變**各自單獨**套用、各跑整個 suite 一次 | **A**（`:84` 的 `postId` 改成只有 `sbn.key`）→ 32 tests／**恰好 1 紅**：`a stale copy with the same key but an older post time is a loss of its own`，訊息 `recordGap(…COLD_START, BOUNDED…) was not called`。**B**（`:501` 改回 `h.generation != activeGeneration \|\| paused`）→ **恰好 1 紅**：`a disconnect landing while held notifications are released…`，`expected:<2L> but was:<1L>`（第二則被判 stale、沒入列，`droppedAfterRevoke` 停在 1，`awaitUntil` 5 s 逾時）。**C**（`:525` 放寬成 `pkg !in enabledPackages`、拿掉 `:526`）→ **恰好 1 紅**：round-14 的 `…gets no gap when it is paused or captured again by the resync`，`recordGap(…COLD_START…) should not be called`。三次其餘 31 個全綠，沒有任何突變弄紅別的測試 |
| 206 JVM tests | brief 只允許跑 capture；我把各模組 `build/test-results` 的 JUnit XML 加總 | **加總 = 206，0 fail / 0 error / 0 skip**。capture（32）是我這次跑的；app／feature/* 的 XML 是今天 05:50、storage／backup 05:02、core/*／parsers／crypto 03:07——這些模組本 commit 沒動，數字仍有效，但「206 全綠」是加總不是我一次跑出來的 |
| lint 0 errors | 未重跑 | 本 diff 只加一個 `val` getter 與幾個 local，沒有新的 lint 類別；`-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi` 已在 `build-logic/…/quietinbox.android.library.gradle.kts:27`，所以測試用的 `getCompleted()`（`:819`）不會產生警告 |
| 文件 | 逐檔讀 | `CHANGELOG.md:10-11`（round-15 一項 + `+8 (32)`；`:45` 0.1.1 是 24，24+3+3+2=32 算術一致；206；storage 16 未變）、`docs/TEST_MATRIX.md:21` 與 `docs/zh-Hant/TEST_MATRIX.md:21`（32 個，兩個新測試的描述 en／zh 都有）、`CLAUDE.md:125`（`round{10,…,15}`）、`docs/reviews/README.md:25-26` 與 `docs/zh-Hant/reviews/README.md:23-24`（第 14 列補上 `6225719`，第 15 列新增、修復欄為「follow-up commit」——見觀察 2）、round-15 四份報告已歸檔 |

---

## Verdict

### **APPROVE WITH MINOR FIXES**（0 Critical、0 Important、2 Minor）

round-15 的三個 Minor 全部有對應的程式碼改動，兩個新測試的負面對照經我在複本**逐一**實測都是真的（各突變恰好紅一個目標測試）；brief 點名的六個回歸點（live twin 的 snapshot 失敗、`captured != null` 的項目、`dropped == 0` 的涵蓋論證、一次讀取 generation 的取捨、讀 `sbn.postTime`、兩個新測試的決定論）逐一推演，結論在下表。

沒有 Critical 或 Important。兩個新 Minor 都不阻擋、也不需要 re-release：**Minor-1** 是本 commit 選的「最小修」留下的一個窄視窗（釋放途中的斷線讓整批入列的通知走圍籬、只留記憶體計數，沒有缺口列——round-13 那個洞的窄版本），我**接受**它與既有排隊事件的慣例一致，但把更精確的修法記下來；**Minor-2** 是測試層：round-15 Minor-3（從未啟用的套件不讀 `key`）沒有任何行為測試守著，而測試檔 `:523` 的註解正好承諾這件事，一行 `verify(exactly = 0)` 就能把承諾變成測試。

---

## Round-15 verification table

| # | round-15 發現 | 是否修復 | 證據（工作區 = `a9609e0`） |
| --- | --- | :---: | --- |
| **Minor-1** | `liveKeys` 在迴圈前用當時的 `activeGeneration`／`paused` 算，迴圈裡卻逐項重讀；disconnect／pause 落在中間，該項目被自己的 key 抑制，既不入列也不記缺口 | ✅ 已修（採 round-15 的「最小修」） | `CaptureCoordinator.kt:494-495` `val liveGeneration = activeGeneration; val livePaused = paused` 整批只讀一次；`:501` 分類只用這兩個 local；stale 項目先收進 `:496` 的 `stale` 清單（`:502`），迴圈結束後才在 `:521-530` 決定。中途翻轉的項目會被當 live 入列（`:514`），由 consumer 的 `admitted()` `:726` 以 generation 圍籬丟掉（`:739-741`／`:746-748`，`droppedAfterRevoke`），不再被自己的 key 抑制。新測試 `CaptureCoordinatorTest.kt:812-841`：`factory.create` 對 `id == 1` 那則同步呼叫 `onDisconnected()`（`:819`，在釋放執行緒上、`releaseHeld` 迴圈中間）→ 第二則仍被 snapshot 並入列 → 兩則都被圍籬 → `droppedAfterRevoke == 2`（`:835`）、`create` 恰好 2 次（`:836`）、沒有 journal、沒有 `COLD_START` 缺口（`:837-840`）。負面對照 B 實測：改回逐項重讀 → 第二則判 stale → `expected:<2L> but was:<1L>` 紅 |
| **Minor-2** | 同一個 `key` 不代表同一則：app 用同 key 覆寫內容後，舊內容的遺失被當成「已再次擷取」 | ✅ 已修 | `:83-84` `Held.postId = sbn.key + "\|" + sbn.postTime`（getter，惰性求值）；`:515` 只把實際入列項目的 `postId` 收進 `queuedPosts`，`:526` stale 項目要 key **與** post time 都相同才被抑制。新測試 `:792-810`：同 `id = 42`、`postTime` 1 000 → disconnect → reconnect → `postTime` 2 000 → 金庫開 → 只 journal `evt-live`（`:808`）、`recordGap(COLD_START, BOUNDED)` 恰好 1 次（`:809`）。負面對照 A 實測：`postId` 去掉 post time → 舊的那份被抑制 → `was not called` 紅 |
| **Minor-3** | `liveKeys` 對從未啟用的 app 也讀 `sbn.key`，超出 `Held` KDoc 的承諾 | ✅ 已修（結構保證，沒有測試——見新 Minor-2） | `queuedPosts` 只在 `:515` 填、而 `:515` 在 `:506` 的 capturable 檢查與 `:514` 的 `enqueue` 之後；stale 項目要先過 `:525` 的 capturable 檢查才在 `:526` 讀 `postId`。所以 `key`／`postTime` 只對 `enabledPackages && !pausedPackages` 的套件讀；從未啟用的 app 仍只被讀 `packageName`（`:505`、`:524`、`offer()` `:656`），`Held` KDoc `:69-73` 那句繼續為真。負面對照 C 實測弄紅的是 round-14 的測試（`:768`），不是 Minor-3 的行為——這一點沒有測試守著 |
| 觀察 4（round-15） | 兩個 300 則迴圈的 mock 沒 stub `key`，300 個 mock 共用空字串 key | ✅ | `:525-531` `sbnOf(pkg, id, key, postTime)` 是唯一的 sbn mock；`:573`、`:756` 兩個迴圈都改用它、各給不同 `id`（因此不同 key）。`grep -c 'mockk(relaxed = true) { every { packageName }'` 在測試檔為 0 |
| 觀察 1（round-15） | index 第 14 列修復欄「follow-up commit」 | ✅ | `docs/reviews/README.md:25`、zh `:23` 改為 `6225719`；第 15 列新增 |

### brief 點名的回歸點逐一回答

| 問題 | 結論 |
| --- | --- |
| stale 項目的 live 雙胞胎 `snapshotFactory.create` 失敗（`:508-513` → `captureErrors++`、`continue`、沒入列、不在 `queuedPosts`）→ stale 那份記缺口。正確嗎？ | **正確，而且是本 commit 相對 `6225719` 的改善**。這一則兩份都沒進金庫（live 那份只計 `captureErrors`，沒有缺口列），stale 那份的 `[heldAt, now]` 是真實的遺失視窗；round-15 把這叫「小姊妹洞」，「以實際入列者為準」正好把它補上。缺口原因標成 `COLD_START` 而非「snapshot 失敗」稍微失真，但方向是 fail closed，可接受 |
| `captured != null` 的項目（`sbn == null`、`postId == null`）：`null in queuedPosts` 為 false → capturable 來源的 stale 預建項目記缺口。可接受嗎？ | **可接受，而且生產路徑不會發生**。`sbn == null` 的 `Held` 只來自 `offerCaptured()` `:680-694`（`@VisibleForTesting(otherwise = PRIVATE)`，main 程式碼只有宣告處一個引用）；自家 synthetic 通知走 `offer()` `:656-657`，永遠不進 held。就算發生，一個沒入列的 stale 項目記缺口是對的（沒有東西被隱藏）。`String? in HashSet<String>` 走 `Iterable<T>.contains` 擴充（`@OnlyInputTypes` 允許 `T = String?`），`contains(null)` 回 false，不會 NPE |
| `dropped == 0` 才算 stale 缺口：溢位缺口是否涵蓋每個 stale 倖存者？ | **是**。`held` 是 FIFO（`:449` `removeFirst`），`heldDroppedSince` 是第一個被驅逐者的到達時間（`:451`，與 `heldDropped++` 在同一個 `synchronized` 內一起設，所以 `dropped > 0` 時 `since` 不可能為 null）；每個倖存者都在它之後到達，`[since, now]` ⊇ `[heldAt_stale, now]`。就算溢位缺口那次寫入失敗，`recordColdStartLoss` `:546` 會把 `since` 留在 `coldStartLossSince`，下一次 settle 補寫，仍然涵蓋。兩個 `now` 差的是迴圈那幾毫秒，都是 BOUNDED，無妨 |
| `liveGeneration` 只讀一次：在剛好接著死掉的 generation 下入列的項目由 `admitted()` 圍籬、計入 `droppedAfterRevoke`、沒有缺口。接受嗎？ | **接受（不阻擋）**，但記為 Minor-1：這與排隊中事件遇到 disconnect／pause 的既有慣例一致（`:174-198` 那個測試就是這個慣例），也是 round-15 明列的兩個修法之一。可是 `LISTENER_DISCONNECTED` 缺口從 `onDisconnected()` 的 `now` 開始（`:293`），**不涵蓋** held 項目的到達時間——這正是 round-13 給 held 通知自己缺口的理由。所以這是 round-13 那個洞的窄版本，詳見 Minor-1 |
| 讀 `sbn.postTime`（framework metadata，`SnapshotFactory` 已讀） | **接受**。`SnapshotFactory.kt:144` 建 snapshot 時本來就讀 `postTime`（`:139` 讀 `key`）；`postId` 是惰性 getter，只在 `:515`／`:526` 被讀，兩處都在 capturable 檢查之後、policy 已知之後；不落地、只活在 `releaseHeld` 的 HashSet 裡。`postTime` 由 `NotificationManagerService` 在每次 post／update 時設定，所以「key + postTime 相同」是 framework 自己對「同一次 post」的定義；resync 回來的 `activeNotifications` 物件帶的就是最新一次 post 的 `postTime`，沒被更新過的通知兩份相等、被更新過的不等——正好是要的語意 |
| 兩個新測試的決定論 | **都是 happens-before，沒有 timing guess**。`:792`：三次 `onPosted` 與 `onDisconnected`／`onConnected` 都是同步呼叫，`held` 在 `vaultOpen.complete` 前就定型（`coldStart()` 卡在 `sources.sources()` 的 `vaultOpen.await()` 裡持鎖）；釋放時 `liveGeneration = G2`，G1 那份必為 stale、G2 那份必入列；`resync` 是空的（`activeNotifications` 回 null）。`:812`：`onDisconnected()` 在 `factory.create` 的 answer 裡**同步**跑在釋放執行緒上，所以第一則 `enqueue`（`:514`）發生在 `activeGeneration = null` 之後，consumer 看到的兩則都已經是死 generation；`awaitUntil { droppedAfterRevoke == 2 }` 是 poll，`coVerify(exactly = 2) { create }` 在它之後（create 先於 enqueue 先於 consumer，所以此時兩次 create 必已發生）；`stillHolds` 300 ms 抓誤記。沒有 `onConnected` 跟在後面（`requestRebind()` 是 relaxed mock），沒有 resync 干擾。三次實跑各 0.02 s／0.32 s |
| 文件 vs 程式碼計數（32／206） | 32：`grep -c '^    test("'` = 32，XML = 32。206：XML 加總 = 206（見上表的時間戳說明）。en／zh TEST_MATRIX `:21` 的逐項描述與兩個新測試對得上（en「a stale copy with the same key but an older post time is a loss of its own; a disconnect landing during the release never lets a later notification suppress itself」、zh「同 key 但 post 時間較舊的過期副本是獨立的遺失；釋放途中的斷線不會讓較晚的通知被自己抑制」） |

---

## Issues

### Critical

**無。**

### Important

**無。**

### Minor

#### Minor-1 — 整批只讀一次 generation 之後，釋放途中的 disconnect／pause 讓整批 held 通知走圍籬、只留 `droppedAfterRevoke`，held 視窗沒有缺口列（round-13 那個洞的窄版本；接受，不阻擋）

**位置**：`CaptureCoordinator.kt:494-495`（一次讀取）、`:501`（整批用同一組 local 分類）、`:514`（入列）；`:726` `admitted()` 的 generation 圍籬、`:739-741`／`:746-748` 只加 `droppedAfterRevoke`；`:293` `LISTENER_DISCONNECTED` 缺口從 disconnect 的 `now` 開始。

**故障情境**：冷啟動期間 N 則（最多 256）啟用來源的通知在 G1 被 hold，到達時間 `heldAt_i`，最早可比釋放早 15 s。金庫開 → `releaseHeld()` 讀到 `liveGeneration = G1` → 逐則 snapshot、入列。此時 listener rebind（`onDisconnected()` → `activeGeneration = null`）落在 `:494` 之後、consumer 對第 i 則做 `:739` 之前——視窗是「釋放開始到 consumer commit 到該則」，256 則的批次是幾十到上百毫秒。結果：N 則全部被圍籬丟掉、`droppedAfterRevoke += N`（記憶體計數，健康頁看得到，但不持久），`LISTENER_DISCONNECTED` 缺口從 `t_d` 開始，`[heldAt_i, t_d]` 這段沒有任何缺口列。若通知還在通知欄，rebind 的 resync（`:266`、`:274-276`）會在 `sourcesLoaded == true` 下直接再擷取；真正遺失的是「在 rebind 前就被滑掉」那一種。

**為什麼接受、為什麼仍列 Minor**：這與排隊中的 live 事件遇到 disconnect／pause 的既有慣例完全一致（`CaptureCoordinatorTest.kt:174-198`：`droppedAfterRevoke == 1`、無缺口），而且是 round-15 明列的兩個修法之一；相對 `6225719`（同一交錯會被自己的 key 抑制、連計數都沒有）是嚴格的改善。但相對 `eae0003`（逐項重讀），disconnect 之後才輪到的那些項目原本會得到 `[heldAt, now]` 的缺口，現在變成計數；差別在 held 視窗可長達 15 s，而排隊延遲是毫秒級——round-13 給 held 通知自己缺口的理由（到達早於 disconnect 缺口的起點）在這裡同樣成立。視窗窄、有 resync 兜底、有計數可見，所以是 Minor 而非 Important。

**若要關死（二選一，都不需要 re-release）**：
1. round-15 的「正解」：保留逐項讀取，第一趟把**實際入列**者的 `postId` 收進 `queuedPosts`，第二趟只對第一趟判 stale 的 capturable 項目做 `postId !in queuedPosts` 判斷。disconnect 之後才輪到的項目回到「記缺口」，之前入列的仍走圍籬（與現在相同）。
2. 更根本：讓 `Queued` 帶一個 `heldAtEpochMs: Long?`（`:696` `enqueue` 由 `releaseHeld` 呼叫時傳 `h.heldAtEpochMs`，`offer()` 傳 null），`process()` 在 `:739`／`:746` 圍籬丟掉一個 `heldAtEpochMs != null` 的項目時 `recordColdStartLoss(heldAt)`。這同時把「入列後才遇到 disconnect」這個兩種版本都有的殘餘視窗一起關掉；live 事件維持現狀。
測試：現有 `:812` 改斷言為 `recordGap(COLD_START, BOUNDED)` 恰好 1 次（修法 2）或第二則 1 次（修法 1），並保留 `create exactly 2`（修法 2）作為「沒有被自己抑制」的證據。

---

#### Minor-2 — round-15 Minor-3（從未啟用的套件不讀 `key`／`postTime`）沒有行為測試；測試檔 `:523` 的註解承諾了這件事，一行 `verify(exactly = 0)` 就能守住

**位置**：`CaptureCoordinatorTest.kt:523`（「only `packageName` is ever touched before the policy is known」）與 `:533-558`（QI-CAPTURE-013 測試，只斷言 `created`——即 `factory.create` 的呼叫——不驗 mock 上哪些屬性被讀過）。brief 列的第三個負面對照（「stale 檢查放寬到 `enabledPackages`、拿掉 queued-post 檢查」）弄紅的是 round-14 的 `:768` 測試（我實測確認），與 Minor-3 無關；Minor-3 目前靠的是 `:515`／`:526` 在 capturable 檢查之後這個結構事實。

**故障情境**：未來有人為了「先算好整批的 key 集合」把 `postId` 的讀取搬回迴圈前（就是 `6225719` 的寫法），或在 `:502` 收 stale 時順手記 `postId`——32 個測試全綠，`Held` KDoc `:69-73` 與 `:523` 的承諾卻悄悄失效。

**修法**：在 `:533` 的測試裡把 unlisted 的 mock 存成變數，最後加 `verify(exactly = 0) { unlisted.key; unlisted.postTime; unlisted.notification }`（relaxed mockk 會記錄每次屬性讀取，`packageName` 不列入）。若想把 Minor-3 的正向面也測到：在 `:792` 或 `:768` 的測試裡 `verify(atLeast = 1) { stale.key }` 對照 unlisted 的 0 次。純測試層，不動 main。

---

## 其他觀察

1. **`docs/SCOPE.md:76` 落後於程式碼（方向可接受）**：冷啟動那一行說「a notification of a capturable source held across a disconnect / pause / maintenance run … is written as a bounded `COLD_START` gap」，沒提 round-14／15 加的例外——resync 再次 offer 的同一次 post（key + post time 相同）不記缺口。docs 落後於 code 不違反「docs must not run ahead」，但下次動 SCOPE 時補一句「unless the reconnect's resync offered the same post again」。
2. **index 第 15 列修復欄仍是「follow-up commit」**（`docs/reviews/README.md:26`、zh `:24`），`docs/reviews/2026-09-06-round16/` 未追蹤：歸檔 round-16 報告的 commit 請一起把第 15 列改成 `a9609e0`、補第 16 列——每輪都被點名的「docs ahead of code」型態。
3. **`queuedPosts` 的註解說「actually queued」，實際是「offered to the queue」**：`:514` `enqueue()` 的 `trySend` 失敗（`:705`，`MAX_QUEUE_DEPTH` 512）時 `:515` 仍把 `postId` 收進集合。不是洞：溢位那則已記 `QUEUE_OVERFLOW` EXACT 缺口（`:714`），stale 那份被抑制是同一次 post 的第二份，沒有東西被隱藏。若要字面精確，讓 `enqueue` 回傳 `ok` 再決定是否加入；選配。
4. **第二次 `releaseHeld()`（`:404`）的 `queuedPosts` 是各自一批**：stale 那份在第一批、live 那份在第二批的交錯需要 `offer()` 在 `:651` 讀完 generation 後被搶佔、等 disconnect＋reconnect＋resync 的那份先 hold——奈秒級，而且結果是多一筆有界缺口（over-report），不是隱藏。不列為發現。
5. **`enqueue(captured, h.generation, h.heldAtEpochMs)` 的 `now` 是到達時間**（`:514`）：`lastEventAtEpochMs` 與 `QUEUE_OVERFLOW` 缺口都以到達時間為準，符合「observed when it arrived」的註解（`:507`）。維持觀察。
6. **`stale` 每次釋放都配置一個 `ArrayList`**（`:496`）：在 pipeline lock 內的協程上，不在 callback 執行緒，n ≤ 256，無妨。
7. **值得肯定的地方**：
   - 「分類整批只讀一次」的註解（`:490-493`）把 round-15 的交錯和「圍籬會接手」的理由寫在程式碼旁邊，下一個人不會再把它改回逐項讀取。
   - 以「實際入列的 post」而非「迴圈前的預測」做抑制，讓 live twin snapshot 失敗那個姊妹洞順手補上。
   - `sbnOf` 成為唯一的 mock 入口並帶 `id`／`key`／`postTime` 四個參數，round-15 觀察 4 的陷阱（300 個 mock 共用空 key）從此消失。
   - `:812` 的測試把「斷線落在釋放中途」做成確定性的：在 `factory.create` 的 answer 裡同步呼叫 `onDisconnected()`，不靠 sleep 或執行緒交錯。

---

## Assessment

**APPROVE WITH MINOR FIXES。** round-15 的三個 Minor 與觀察 1、4 都修好了；兩個新測試的負面對照逐一在複本實測為真（三個突變各恰好紅一個目標測試、其餘 31 個綠）；32 個 capture 測試三次獨立重跑全綠（時間戳各異）；文件計數（32／206）與程式碼一致。**Minor-1**（一次讀取 generation 的取捨）我接受，但更精確的修法記在上面，任一種都是小改、不需 re-release；**Minor-2**（Minor-3 的一行 `verify(exactly = 0)`）純測試層。歸檔 round-16 時順手把 index 第 15 列的 commit 填成 `a9609e0`、SCOPE.md:76 補上 resync 例外即可。
