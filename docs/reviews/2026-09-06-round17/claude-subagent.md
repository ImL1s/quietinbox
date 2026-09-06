# Review round 17 — Claude subagent（round-16 兩個 Minor 與三個觀察之迷你再審）

- **審查範圍**：`git diff a9609e0..69a60b4`（一個 commit `69a60b4`，工作區 HEAD 即為它；`git status` 只有未追蹤的 `docs/reviews/2026-09-06-round17/`，內含 `brief.md`（與 `.omc/research/dual-review-round17-brief-safe.md` 逐字相同）與 `kimi-blocked.md`）。程式碼改動只在兩個檔案：`CaptureCoordinator.kt` +12/−12（`releaseHeld()` 改回逐項讀取、`enqueue()` 回傳 `Boolean`）、`CaptureCoordinatorTest.kt` +16/−9（冷啟動測試加 `verify(exactly = 0)`、釋放途中斷線的測試改斷言）；其餘是 CHANGELOG、CLAUDE.md、SCOPE、TEST_MATRIX（en/zh）、reviews index（en/zh）與 round-16 四份報告歸檔。
- **審查性質**：唯讀。沒有改任何 repo 檔案、沒有跑會改狀態的 git 指令、沒有碰裝置或跑 instrumented test。
- **超出 brief 明列指令的部分**（皆唯讀、在 repo 之外）：為了實測 brief 宣稱的兩個負面對照（I、J），用 `rsync`（排除 `build`／`.gradle`／`.git`／`.omc`／`.kotlin`／`.idea`）把 repo 複製到 session scratchpad，在**複本**裡各套用一個突變、各跑一次 `:platform:capture:testDebugUnitTest --offline`，之後刪除複本，只留 log／XML／diff。下面所有 file:line 以 `69a60b4` 的內容為準。

## 本地實測與宣稱核對

| 宣稱 | 驗證方式 | 結果 |
| --- | --- | --- |
| capture 32 個 JVM test 全綠、連跑三次穩定 | 主 repo `./gradlew :platform:capture:testDebugUnitTest --rerun -q` 三次，讀 JUnit XML 的 `timestamp` 確認三次都真的重跑 | **3 / 3 綠**，每次 32 tests / 0 failures / 0 errors / 0 skipped（UTC 06:13:17、06:14:58、06:15:08）。四個相關測試各 0.71 s（冷啟動）／0.02 s（同 key 較舊 post）／0.32 s（釋放途中斷線）／0.32 s（跨斷線保留）——都是 `stillHolds` 的 300 ms，沒有等到逾時 |
| 負面對照 I：抑制集合改成迴圈前從所有項目預測 → 釋放途中斷線的測試與另外兩個 stale-gap 測試變紅 | scratchpad 複本，突變 = 迴圈前對每個 capturable 項目 `queuedPosts += postId`、`:513` 改回不看 `enqueue` 回傳值（round-15 的形狀） | **恰好 3 紅、29 綠**（XML 06:13:45Z）：`a stale copy with the same key but an older post time is a loss of its own`、`a disconnect landing while held notifications are released gives the later ones a gap…`、`a notification held across a disconnect is recorded as a gap, not dropped silently`，三個訊息都是 `recordGap(…COLD_START, BOUNDED…)` 的 `Verification failed: call 1 of 1`。round-14 的「已暫停或 resync 再擷取時不記」維持綠——預測式集合仍抑制得到，正確 |
| 負面對照 J：迴圈前讀每個項目的 `postId` → 冷啟動測試變紅 | 複本，突變 = 迴圈前 `val allPosts = items.mapNotNull { it.postId }`（含未列出的那則） | **恰好 1 紅、31 綠**（XML 06:14:11Z）：`before the source list is known a notification is held unread…`，訊息 `call 1 of 3: StatusBarNotification(#298).getKey()) should not be called  Calls: 1) StatusBarNotification(#298).getKey()`。這同時回答了 brief 的 mockk 問題：relaxed mock 上被 `every {}` stub 過的屬性讀取仍會被 call recorder 記下，`verify(exactly = 0)` 是真的負面對照 |
| 206 JVM tests | brief 只允許跑 capture；我把各模組 `build/test-results` 的 JUnit XML 加總（排除 `connected`） | **加總 = 206，0 fail / 0 error / 0 skip**。capture（32）是我這次跑的；app／feature/* 的 XML 是今天 06:06、storage／backup 05:02、core/*／parsers／crypto 03:07——這些模組本 commit 沒動，數字仍有效，但「206 全綠」是加總不是我一次跑出來的 |
| lint 0 errors | 未重跑 | 本 diff 只把一個 `Unit` 函式改成回傳 `Boolean`（`:695`／`:714`）並在 `:513` 用它；`offer()` `:668` 與 `offerCaptured()` `:691` 忽略回傳值，Kotlin 沒有 `@CheckResult` 就不會有 lint／warning（`warningsAsErrors = false`，`build-logic/src/main/kotlin/quietinbox.android.library.gradle.kts:19`） |
| 文件 | 逐檔讀 | `CHANGELOG.md:11`（round-16 一項，含「`enqueue` now reports a full queue」與「SCOPE names the resync exception」）、`:12`（`+8 (32)`、206、storage 16 未變——本 commit 沒加測試，32 正確）；`docs/TEST_MATRIX.md:21` 與 `docs/zh-Hant/TEST_MATRIX.md:21`（32 個；釋放途中斷線那句改成「gives the later notifications a gap instead of letting them suppress themselves」／「會給較晚的通知自己的缺口、而不是讓它被自己抑制」，新增「an app the user never enabled has only its package name read」／「從未啟用的 app 只會被讀取套件名稱」，en／zh 對齊）；`CLAUDE.md:125`（`round{10,…,16}`）；`docs/SCOPE.md:76`（補「(unless the reconnect's resync offered the same post again)」）；`docs/reviews/README.md:26-27` 與 `docs/zh-Hant/reviews/README.md:24-25`（第 15 列修復欄填成 `a9609e0`，第 16 列新增、修復欄為「follow-up commit」——見觀察 1）；round-16 四份報告已歸檔 |

---

## Verdict

### **APPROVE**（0 Critical、0 Important、0 Minor）

round-16 的兩個 Minor 與三個觀察全部有對應的改動，兩個負面對照在複本逐一實測都是真的（各突變恰好紅 brief 宣稱的那幾個目標測試，其餘全綠）；brief 點名的五個回歸點（逐項讀取後自我抑制是否真的不可能、釋放途中斷線測試的決定論、relaxed mockk 是否記錄屬性讀取、是否還有「既不記缺口也不計數」的路徑、文件計數）逐一推演，結論在下表。沒有新的發現需要修；三個觀察都不阻擋、不需要 re-release。

---

## Round-16 verification table

| # | round-16 發現 | 是否修復 | 證據（工作區 = `69a60b4`） |
| --- | --- | :---: | --- |
| **Minor-1** | 整批只讀一次 generation 之後，釋放途中的 disconnect／pause 讓整批 held 通知走圍籬、只留 `droppedAfterRevoke`，held 視窗沒有缺口列 | ✅ 已修（採 round-16 列的修法 1） | `CaptureCoordinator.kt:500` `if (h.generation != activeGeneration \|\| paused)` 每個項目在**它自己的那一刻**讀 volatile；`:498` `queuedPosts` 只在 `:513` `if (enqueue(…)) h.postId?.let { queuedPosts += it }` 填——在 `enqueue` 回傳 true 之後、永遠不在迴圈前預測；被 disconnect 追上的項目走 `:501-502` 進 `stale`，`:519-527` 對 capturable 且 `postId !in queuedPosts` 的 stale 項目取最早 `heldAtEpochMs` 呼叫 `recordColdStartLoss`（`:536-546`，寫入失敗保留在 `coldStartLossSince`）。註解 `:490-492`／`:494-497` 把「逐項判定」與「只由實際入列者組成、所以不可能自我抑制」的理由寫在程式碼旁。測試 `CaptureCoordinatorTest.kt:819-848`：`factory.create` 對 `id == 1` 同步呼叫 `onDisconnected()`（`:826`）→ `droppedAfterRevoke == 1`（`:842`，第一則入列後被圍籬）、`recordGap(COLD_START, BOUNDED)` 恰好 1 次（`:843`，第二則）、無 journal、`create` 恰好 1 次（`:844-847`）。負面對照 I 實測：預測式集合 → 第二則被自己的 `postId` 抑制 → `recordGap` 沒被呼叫 → 紅（連同另外兩個 stale-gap 測試，恰好 3 紅） |
| **Minor-2** | round-15 Minor-3（從未啟用的套件不讀 `key`／`postTime`）沒有行為測試 | ✅ 已修 | `CaptureCoordinatorTest.kt:549` 把未列出的 mock 存成 `unlisted`，`:560-564` `verify(exactly = 0) { unlisted.key; unlisted.postTime; unlisted.notification }`，放在 `awaitUntil { journaled == [evt-enabled] }`（`:557`）與 `stillHolds { created == [ENABLED] }`（`:559`）之後——held 是 FIFO，unlisted 在 enabled 之前被決定，enabled 被 journal 時 unlisted 早已離開緩衝，之後沒有任何路徑再碰它，所以 verify 的時機是 happens-after，不是猜。負面對照 J 實測：迴圈前讀每個項目的 `postId` → `getKey()) should not be called` 紅（恰好 1 紅） |
| 觀察 1（round-16） | `docs/SCOPE.md:76` 沒提 resync 例外 | ✅ | `:76` 補「(unless the reconnect's resync offered the same post again)」；per-source pause 的例外已含在「capturable source」（capturable = enabled 且未 paused，`:523`）裡；`dropped > 0` 時跳過 stale 缺口的情況由溢位的 `COLD_START` 缺口涵蓋，所以「written as a bounded `COLD_START` gap」在整體上仍為真 |
| 觀察 2（round-16） | index 第 15 列修復欄「follow-up commit」、round-16 報告未追蹤 | ✅ | `docs/reviews/README.md:26`、zh `:24` 改為 `a9609e0`；第 16 列新增（`:27`、zh `:25`）；round-16 四份報告在本 commit 歸檔 |
| 觀察 3（round-16） | `queuedPosts` 註解說「actually queued」，實際是「offered」：`trySend` 失敗仍加入集合 | ✅ | `:694-715` `enqueue` 回傳 `ok`（`:704` `trySend(...).isSuccess`、`:714` `return ok`），KDoc `:694` 說明 false = 佇列滿、已記 `QUEUE_OVERFLOW` EXACT 缺口（`:713`）；`:513` 只在 true 時加入。**是否重複計算**：不是。溢位缺口是 `[now, now]` 的**點**缺口，`now` 是 live 那份的到達時間（`:513` 傳 `h.heldAtEpochMs`）；stale 那份沒被抑制而記的是 `[heldAt_stale, now]` 的有界缺口，`heldAt_stale` 早於 live 那份的到達（它是上一個 generation 的副本），這段視窗沒有任何別的列涵蓋。兩列各說一件真的事——一則在佇列門口被丟、一則跨斷線被保留後從未入列——方向是 over-report 不是 double counting，而且只在「釋放時佇列已滿（512）且同一 post 有跨代副本」才會發生 |

### brief 點名的回歸點逐一回答

| 問題 | 結論 |
| --- | --- |
| 逐項讀取恢復後，round-15 Minor-1（自我抑制）是否真的不可能？ | **不可能，可寫成不變式**：一個 stale 項目 `h` 是在 `:500` 命中 `continue` 的項目，它在本次迴圈裡**從未到達** `:513` 的 `enqueue`；而 `queuedPosts` 只在 `:513`、`enqueue` 回傳 true 之後、對**那一個**項目加入。所以 `h.postId ∈ queuedPosts` 成立當且僅當**另一個** `Held` 物件 `h'`（同 key、同 postTime）在本次迴圈入列——那正是 resync 再次 offer 同一次 post 的情況，也就是 round-14 想抑制的東西。round-15 的洞需要「集合在 `h` 被判定之前就含 `h.postId`」，而集合在 `h` 被判定之前對 `h` 什麼都不知道。這與 generation／pause 何時翻轉無關：翻轉只決定 `h` 走 `:501` 還是 `:513`，兩條路都不會讓 `h` 抑制 `h` |
| 釋放途中斷線測試的決定論 | **happens-before，沒有 timing guess**。兩次 `onPosted` 在 `vaultOpen.complete` 前同步 hold 在 G1 下（`coldStart()` `:463` 持 pipeline lock 卡在 `sources.sources()` 的 `vaultOpen.await()`）；釋放跑在 coldStart 的協程上，`factory.create` 的 answer 在**同一執行緒同步**呼叫 `onDisconnected()`（`:826`），它在 `:283` 同步把 `activeGeneration = null`——這發生在第一則的 `:500` 判定之後、`:513` `enqueue` 之前，所以第一則在死 generation 下入列，consumer 的 `admitted()` `:726` 圍籬掉它（`:739-741`，`droppedAfterRevoke = 1`）；第二則在 `:500` 讀到 null ≠ G1 → stale → `:524` 不在 `queuedPosts`（集合只有第一則）→ `:527` 記缺口。`onDisconnected()` 自己的簿記是 `openGap(LISTENER_DISCONNECTED)`（`:293`）不是 `recordGap`，`requestRebind()` 是 relaxed mock，`sessionId` 可能還沒設但 `endedSession?.let` 容忍 null，`activeNotifications` 回 null 所以沒有 resync——都干擾不了 `recordGap(COLD_START, BOUNDED)` 恰好 1 次。`awaitUntil { droppedAfterRevoke == 1 }` 是 poll，`coVerify(timeout)` 等 `recordColdStartLoss` 的 launch，`stillHolds` 300 ms 抓第二次 `create` 與誤 journal。**這個測試現在能分辨兩個前身**：`6225719` 跑它會是 0 缺口（自我抑制）→ `:843` 紅；`a9609e0` 跑它會是 `droppedAfterRevoke == 2`、0 缺口、`create` 2 次 → `:842`／`:843`／`:846` 之一紅（計數 0 → 1 → 2，10 ms 的 poll 可能剛好在 1 時通過 `:842`，接著 `:843` 等不到缺口而紅；round-16 實測 B 就是這個交錯的反向訊息 `expected:<2L> but was:<1L>`）。三次實跑各 0.32 s |
| relaxed mockk 記錄屬性讀取嗎？負面對照 J 是真的嗎？ | **是**。mockk 的 `MockKStub.handleInvocation` 對 mock 上的每次呼叫都進 call recorder，與是否 relaxed、是否被 `every {}` stub 過無關；`every {}`／`verify {}` 區塊裡的呼叫在 recording 模式下不計。`verify(exactly = 0) { a; b; c }` 對區塊裡每個 call **各自**驗 min = max = 0（`UnorderedCallVerifier` 逐 call `matchCall`）。實測 J 的訊息 `call 1 of 3: StatusBarNotification(#298).getKey()) should not be called` 正是這個機制在講話：三個 call 各自檢查，第一個（`getKey()`）被記到一次 |
| 是否還有 held 通知的遺失「既不記缺口也不計數」的路徑？ | **沒有**。逐條列 `releaseHeld` 對 capturable 來源的 held 項目：(a) stale 且 `dropped == 0` 且不在 `queuedPosts` → `COLD_START` 有界缺口（`:527`，寫失敗保留）；(b) stale 且 `dropped > 0` → 溢位缺口 `[since, now]`，`since` 是第一個被驅逐者的到達（`:451`），FIFO 保證涵蓋每個倖存者；(c) stale 且被抑制 → 同 post 的另一份在本次迴圈入列，由 pipeline 自己的簿記負責（commit／圍籬計數／journal 失敗的 UNKNOWN 缺口）；(d) live、`create` 失敗 → `captureErrors++`（`:509`，計數；與 `offer()` `:664` 同一慣例），且它不進 `queuedPosts`，所以它的 stale 副本若有會記缺口——round-16 稱讚過的姊妹洞修補仍在；(e) live、`enqueue` false → `QUEUE_OVERFLOW` EXACT 缺口 + `overflowCount`（`:710`、`:713`）；(f) live、入列 → consumer 收下或圍籬計數。`hold()` 驅逐 → (b)；`dropHeld` 逾時 → open `COLD_START` 列（`:562`）。剩下的只有 (c) 裡「另一份入列後才被圍籬」的殘餘——見觀察 2，它有計數，而且與 `a9609e0` 相同 |
| 文件 vs 程式碼計數（32／206） | 32：`grep -c '^    test("'` = 32，XML = 32，三次都是。206：XML 加總 = 206（見上表的時間戳說明）。TEST_MATRIX 的清單描述的是行為不是測試數——「從未啟用的 app 只會被讀取套件名稱」是既有測試裡加的斷言，計數維持 32 是對的 |

---

## Issues

### Critical

**無。**

### Important

**無。**

### Minor

**無。**

---

## 其他觀察

1. **index 第 16 列修復欄是「follow-up commit」**（`docs/reviews/README.md:27`、zh `:25`）：這是自我引用的必然狀態——本 commit 無法寫出自己的 hash——不算「docs ahead of code」。歸檔 round-17 報告的 commit 請把第 16 列填成 `69a60b4`、補第 17 列，跟前幾輪一樣。
2. **殘餘視窗（與 `a9609e0` 相同，不是本 commit 的回歸）**：同一 post 的兩份 `Held`（G1 stale、G2 live）在同一批釋放，G2 那份 `:513` 入列**之後**才被 disconnect 追上、由 consumer 圍籬（`droppedAfterRevoke`）；此時 `queuedPosts` 已含它的 `postId`，G1 那份在 `:524` 被抑制。結果：這則 post 有一個計數、`LISTENER_DISCONNECTED` 缺口從兩次 disconnect 的時刻起算，但 `[heldAt_G1, t_disconnect1]` 沒有缺口列。要發生需要兩次 disconnect、第二次落在釋放迴圈的毫秒級視窗內、而且通知在下一次 rebind 的 resync 之前被滑掉。有計數、有 resync 兜底、`a9609e0` 行為相同，所以不列為發現。若哪天想關死，round-16 的修法 2（`Queued` 帶 `heldAtEpochMs`，`process()` 圍籬掉帶時間的項目時 `recordColdStartLoss`）同時關掉本輪測試裡「第一則只有計數」的那一半；純選配。
3. **`verify(exactly = 0)` 可以再收緊一格（選配）**：現在列的三個屬性（`key`、`postTime`、`notification`）正好是協調器在 `SnapshotFactory` 之外會讀的三個（`postId` `:84`、`isCapturable` `:372`），對應真實風險。若想把「只讀 `packageName`」變成字面上的斷言，`verify(atLeast = 1) { unlisted.packageName }` 後接 `confirmVerified(unlisted)` 會對 mock 上**任何**沒被驗過的呼叫報錯，未來加讀 `id`／`tag`／`user` 也逃不掉。純測試層，不動 main。
4. **`enqueue` 的 `Boolean` 在 `offer()` `:668` 與 `offerCaptured()` `:691` 被忽略**：兩處的溢位已由 `enqueue` 自己記缺口與計數，呼叫端沒有可做的事，忽略是對的；沒有 `@CheckResult`，不會有 lint。
5. **`Held` KDoc `:83` 的「read only for a capturable source」仍為真**：`postId` 的兩個讀點 `:513`／`:524` 各在 `:505`／`:523` 的 capturable 檢查之後。負面對照 J 守的就是這句。
6. **值得肯定的地方**：
   - 修法選了 round-16 列的兩個裡較小的那個（逐項讀取 + 只由入列者組成集合），main 只動 12 行，卻讓同一個測試同時分辨 `6225719`（0 缺口）與 `a9609e0`（2 計數、0 缺口）兩個前身。
   - `enqueue` 回傳值把「actually queued」從註解變成程式碼事實，順手讓 `queuedPosts` 的語意在佇列溢位時也正確。
   - `verify(exactly = 0)` 放在 FIFO 決定順序保證的 happens-after 點上，而且 relaxed mock 的屬性讀取確實會被記錄（實測 J 的訊息），不是裝飾性斷言。
   - 冷啟動測試的註解 `:558` 從「without ever being read」改成「only its package name was touched」，與程式碼事實一致——這輪沒有 docs ahead of code。

---

## Assessment

**APPROVE。** round-16 的兩個 Minor 與三個觀察全部修好；兩個負面對照在複本逐一實測為真（I 恰好 3 紅、J 恰好 1 紅，其餘全綠）；32 個 capture 測試三次獨立重跑全綠（時間戳各異）；文件計數（32／206）與程式碼一致，en／zh TEST_MATRIX 對齊，SCOPE 的 resync 例外補上。沒有新的 Minor。歸檔 round-17 時把 index 第 16 列的 commit 填成 `69a60b4` 即可。
