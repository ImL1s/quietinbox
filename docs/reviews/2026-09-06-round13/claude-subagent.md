# Review round 13 — Claude subagent（round-12 修復 + CI 抓到的順序修正之迷你再審）

- **審查範圍**：`git diff a3fd01b..c6b6645`（三個 commit：`d409d4b` round-12 修復、`c8e4c9d` release 0.1.1、`c6b6645` CI 抓到的釋放順序修正）。tag `v0.1.1` 指向 `c6b6645`（`git rev-list -n1 v0.1.1` 實查）。
- **審查性質**：唯讀 mini re-review。逐條確認 round-12（subagent 8 Minor + 其他觀察 1/3/4，agy 2 Minor）是否修好，並專找這批修改本身引入的回歸。
- **審查基準**：工作區 HEAD 是 `537ad80`（只多一個 SCOPE 文件 commit），我用 `git diff --quiet c6b6645 -- platform feature core app` 確認**程式碼與 `c6b6645` 逐 byte 相同**，所以下面所有 file:line 都以工作區（= tag）為準。工作區另有三個未 commit 的文件變更（`CHANGELOG.md`、兩份 `docs/reviews/README.md`）與未追蹤的 `docs/reviews/2026-09-06-round13/`，那是本輪（第 13 輪）正在進行的歸檔，**不在審查範圍**。
- **超出 brief 列舉指令的部分**（皆為唯讀）：跑了 `./gradlew lint`（全模組）、用 `gh` 讀了 CI job 結果與 issue 狀態、把 GitHub release `v0.1.1` 的 APK 下載到 scratchpad 驗 sha / 簽章 / versionCode / 權限。沒有碰裝置、沒有跑 instrumented test、沒有改任何檔案。

## 本地實測與宣稱核對

| 宣稱 | 驗證方式 | 結果 |
| --- | --- | --- |
| 198 JVM tests 全綠 | `./gradlew test --console=plain -q`，彙總所有 `TEST-*.xml` | **198 / 0 failures / 0 errors / 0 skipped**（exit 0）。分模組：model 5、parser 10、identity 5、reconcile 22、analytics 34、parsers:apps 43、app 5、crypto 3、storage 12、backup 24、**capture 24**、feature:analytics 8、feature:search 2、feature:conversation 1 |
| capture suite 連續三次通過 | 全量跑一次 + `:platform:capture:testDebugUnitTest --rerun` 兩次 | **3 / 3 綠**（24 tests，各 ~7 s） |
| `CaptureCoordinatorTest` 24、`SuppressionRuleTest` 4 | JUnit XML + `grep -c '    test("'` | 24 / 4，與 `CHANGELOG.md:40`、`docs/TEST_MATRIX.md:21`、`docs/zh-Hant/TEST_MATRIX.md:21` 一致 |
| instrumented storage 15 / crypto 2 / backup 2 | 未執行（brief 禁止）；原始碼 `grep -rh "@Test" */src/androidTest` + CI | 原始碼 15 / 2 / 2；CI run 34011165751（`c6b6645`）四個 job **全部 success**：JVM、Assemble + permission gate、Instrumented (API 29)、Instrumented (API 35) |
| CI 在 `c8e4c9d` 抓到順序問題 | `gh run view` | `c8e4c9d` 的 CI：JVM **failure**，其餘 skipped；`c6b6645` 綠；後續 `537ad80` 也綠 |
| lint 每個模組 0 errors | `./gradlew lint`，解析 14 份 `lint-results*.xml` | **14 個模組 errors=0**（warnings 仍有：designsystem 22、capture 3、app 2、health 1、media 1；`abortOnError` 只擋 error） |
| release APK sha / 簽章 / versionCode / 無 INTERNET | `gh release download v0.1.1` → `shasum`、`apksigner verify --print-certs`、`aapt2 dump badging`、`tools/check-permissions.sh` | sha `0ec7338e…d490bd` **與 `SHA256SUMS.txt` 相同**；signer `CN=QuietInbox Upload, O=CB Studio` SHA-256 `a82bddbe…bedf29` **與 `docs/RELEASE.md:17` 的 upload key 指紋相同**；`versionCode='5' versionName='0.1.1'`；權限只有 POST_NOTIFICATIONS / USE_BIOMETRIC / USE_FINGERPRINT / WAKE_LOCK / RECEIVE_BOOT_COMPLETED / FOREGROUND_SERVICE / 自有 receiver 權限，gate 輸出 `OK: no network permission` |
| en / zh-Hant 字串對照 | 自寫 Python 解析兩份 `strings.xml` | **318 / 318 strings、1 / 1 plurals、零單邊**；本範圍沒有動 `strings.xml` |
| fastlane changelog ≤ 500 chars | Python `len()` | en-US 483、zh-TW 153（`changelogs/5.txt` 與 `whatsnew` 內容相同） |
| issues #1–#17 狀態（`CLAUDE.md` 新增的「Audit trail」段） | `gh issue list --state all` | #1–#16 CLOSED、**#17 OPEN**，與 `CLAUDE.md` 敘述一致 |
| 移除的 `settingsIntent` 包裝沒有殘留呼叫端 | `grep -rn "settingsIntent\|listenerSettingsIntent" --include=*.kt` | 只剩 `ListenerAccess.kt:28` 的 `settingsIntents()` 定義與 `:47` 的內部使用；三個 ViewModel 的包裝與 `ListenerAccess.settingsIntent()` 都消失，編譯與測試通過 |

---

## Verdict

### **APPROVE WITH MINOR FIXES**

round-12 的 8 個 subagent Minor、其他觀察 1 / 3 / 4、agy 的 2 個 Minor 全部有對應的程式碼改動，其中最重要的「金庫鎖定期間的遺失完全記不下來」（觀察 1）有**兩個帶負面對照的新測試**撐著；`c6b6645` 的釋放順序修正確實把 CI 抓到的 `[evt-ok, evt-busy]` 重排修掉，且 `releaseHeld()` 的取出-清空是原子的，**不可能重複釋放**。發布物（sha、簽章、versionCode、無 INTERNET）與 CI 四個 lane 都經實查。

我沒有發現 Critical 或 Important 等級的回歸。五個 Minor 裡最值得在下一個 commit 處理的是 **Minor-1**：round-12 修復把「鎖定期間的遺失」記在記憶體裡等金庫開了再寫，但兩處補寫都在 `guarded {}` **之前**就把記憶清成 null，補寫那一次若失敗（磁碟滿／I/O 錯誤；或 vault-Ready collector 那一側被 reset 的 `collectLatest` 取消），遺失就永久消失——與 round-12 觀察 1 同一類缺陷，只是窗口窄了很多，而且沒有測試覆蓋「補寫本身失敗」。

---

## Round-12 verification table

### Claude subagent — round 12

| # | 發現 | 是否修復 | 證據（工作區 = `c6b6645`） |
| --- | --- | :---: | --- |
| **Minor-1** | `CHANGELOG.md:21` 寫緩衝 64，程式碼是 256 | ✅ 已修 | `CHANGELOG.md:25`「bounded buffer (256)」，`CaptureCoordinator.kt:869` `MAX_HELD = 256` |
| **Minor-2** | `docs/SCOPE.md:76` 仍描述 #13 之前的冷啟動行為 | ✅ 已修 | `docs/SCOPE.md:76` 改為「held as framework objects only … at most 256, the oldest evicted first … written as a bounded COLD_START gap once the vault can be written」，與 `CaptureCoordinator.kt:419-431 / 489-503` 一致 |
| **Minor-3** | `writeRecords` KDoc 宣稱「只有 row reads 在交易內（milliseconds）」 | ✅ 已修（改為誠實敘述） | `BackupService.kt:125-138`：明說 sources / conversations / messages / revisions 的序列化與加密串流寫入**仍在交易內**（"seconds on a very large vault"），媒體在交易外逐頁 |
| **Minor-4** | `mediaRows` 無上限的記憶體清單 | ✅ 已修 | `BackupService.kt:185-208`：交易外 `while (true) { page = mediaDao().exportPage(after, PAGE, now) … after = page.last().id }`，每頁讀完立即解密串流，沒有整表 list |
| **Minor-5** | 「ids 不同 + 一側沒 post time」翻成 true 但沒測試 | ✅ 已修 | `SuppressionRuleTest.kt:26-30` 補上 `applies("m2", null, "m1", 1_000L) shouldBe true` 與 `applies("m2", 1_000L, "m1", null) shouldBe true` |
| **Minor-6** | `settingsIntent()` 全鏈路死程式碼且比以前更弱 | ✅ 已修 | `ListenerAccess.settingsIntent()`、`HealthViewModel.settingsIntent()`、`InboxViewModel.listenerSettingsIntent()`、`OnboardingViewModel.settingsIntent()` 全部移除；`OnboardingViewModel` 的多餘 `Intent` import 一併拿掉；grep 零殘留 |
| **Minor-7** | manifest 的 media 計數仍是含跳過筆數的值 | ⚠️ **以文件處理，未改格式** | `BackupRecords.kt` 不在 diff 內，`Manifest` 沒有 `skippedMedia` 欄位；`BackupService.kt:132-135` KDoc 改為明說「manifest 的 media 是當下考慮的列數，`End.actual.media` 才是寫入數，stager 檢查 `End`」。`BackupStager.kt:82-83` 仍把 media 從 manifest 檢查排除。這是可接受的處理，但 `docs/reviews/README.md:23` 寫「every Minor … fixed」略為過頭，見其他觀察 6 |
| **Minor-8** | COLD_START gap 起點取自倖存項目，被丟的一定更早 | ✅ 已修 | `CaptureCoordinator.kt:146-147` `heldDroppedSince`；`:424-426` 第一次驅逐時記下 `evicted.heldAtEpochMs`；`:458-463`（releaseHeld）與 `:490-495`（dropHeld）都 `since ?: items.minOfOrNull {…}`，並與 `heldDropped` 一起在 `synchronized(held)` 內原子取出歸零 |
| **觀察 1** | 金庫真的 Locked 時冷啟動丟棄什麼都不留 | ✅ 已修（殘餘窗口見 Minor-1） | `:156-162` `coldStartLossSince`、`:164-166` `vaultGapSince`；`dropHeld` `:496-502` openGap 失敗時 `coldStartLossSince = start ?: now`；`process()` `:689-695` openGap 失敗時 `vaultGapSince = snapshot.observedAtEpochMs`；補寫在 `settleColdStartGap()` `:399-408` 與 vault-Ready collector `:222-232`。測試 `CaptureCoordinatorTest.kt:617-636`（`sources()` 與 `openGap` 都拋 locked → 金庫開後 `recordGap(COLD_START, BOUNDED)` **恰 1 次**，`factory.create` 0 次）與 `:638-653`（journal 拋 locked、`openGap(UNKNOWN)` 拋 → Ready 後 `recordGap(UNKNOWN, BOUNDED)` 恰 1 次 + `closeOpenGaps(UNKNOWN)` 恰 1 次）。兩者把 guard 拿掉都會紅（`exactly = 1` 會變 0） |
| **觀察 3** | job 跑完 `releaseHeld()` 但尚未結束時加入的項目沒人處理 | ✅ 已修（窗口再縮，未完全關閉） | `coldStart()` `:446-447` 結尾補 `if (synchronized(held) { held.isNotEmpty() }) pipelineMutex.withLock { releaseHeld() }`；`loadSourcePolicy()` `:390-391` 在旗標翻轉後也再檢查一次。殘餘見其他觀察 2 |
| **觀察 4** | 工作區那行 `coldStartGapId = null`（維護開始）會讓 restore 後留下永遠關不掉的 gap | ✅ 已修 | `:539` 那行保留，但 `settleColdStartGap()` `:405` 的 `closeOpenGaps(now, GapReason.COLD_START)` 改成**無條件**（依 reason 比對、冪等），restore 後殘留的 open row 在下一次 policy 載入就關掉；reset 後只是一次空 SELECT |

### Gemini 3.8 Flash (high, via agy) — round 12

| # | 發現 | 是否修復 | 證據 |
| --- | --- | :---: | --- |
| **Minor-1** | `onMaintenance(true)` 可主動把 `coldStartGapId` 置空 | ✅ 已修 | `CaptureCoordinator.kt:538-539`，配合上面的無條件 close 不會再引入觀察 4 的問題 |
| **Minor-2** | `:core:designsystem` 與 `:platform:capture` 單獨跑 lint 有 error | ✅ 已修（實測） | `Formatting.kt:31-33` 透過 composition 讀 locale、空清單才退 `Locale.ENGLISH`；`SyntheticNotifications.kt:62-70 / 86-91` 在 `notify()` 同一個方法內 `checkSelfPermission`，`catch (_: SecurityException)` 取代 `runCatching`；`platform/capture/src/main/AndroidManifest.xml:4-6` 宣告 `POST_NOTIFICATIONS`（app manifest `:11` 本來就有，merged manifest 不多出權限，release APK 實查亦然）。`./gradlew lint` 14 個模組 errors=0 |

### `c6b6645`：釋放順序與 bitmap 測試

| 檢查項 | 結果 |
| --- | --- |
| `releaseHeld()` 移到 `sourcesLoaded = true` **之前** | `CaptureCoordinator.kt:387-391`。修正前的失敗模型：cold-start job 把旗標翻成 true 後才釋放，此時 `offerCaptured("evt-ok")` 直接 `enqueue`，held 的 `evt-busy` 晚一步 → `[evt-ok, evt-busy]`（正是「a journal insert that throws…」`:579-592` 斷言的順序）。修正後 evt-ok 在旗標翻轉前到達 → 進 `hold()` → 由 `:391` 的尾檢查或 `:447` 的 job 尾檢查以到達順序釋放 |
| 「旗標仍為 false、但 `releaseHeld()` 已取走快照之後」才 `hold()` 的項目由誰釋放 | 由**兩者之一**：`loadSourcePolicy()` `:391` 的尾檢查（在同一把 pipeline lock 內），或 cold-start job `:447`（另取 lock）。兩者都先 `synchronized(held)` 判空再釋放 |
| 可否重複釋放 | **不可能**。`releaseHeld()` `:458-460` 在 `synchronized(held)` 內 `held.toList().also { held.clear() }` 原子取出；每個 `Held` 只會被恰一個呼叫者拿到。`releaseHeld()` 的四個呼叫點（`:388`、`:391`、`:438`、`:447`）全部在 `pipelineMutex` 內 |
| bitmap 上限測試 | `CaptureCoordinatorTest.kt:665-669`：每一次 `copyPending` 都 `release.await()`，只有第一次 `copying.complete`。修正前 copy 2..7 可能在 `evt-8` 被 offer 前完成並 `decrementAndGet`，第 9 個不一定被擋 → flaky。現在 8 個 bitmap 確定同時在飛，`:683-685` 斷言 9 筆、前 8 非 null、第 9 為 null；把 `MAX_QUEUED_BITMAPS` 上限拿掉會紅 |
| 三次連跑 | 見上表，3 / 3 綠 |

---

## Issues

### Critical

**無。**

### Important

**無。**

### Minor

#### Minor-1 — 兩處「金庫開了再補寫」都在寫入**之前**清掉記憶，補寫失敗遺失就永久消失，且沒有測試覆蓋

**位置**：`CaptureCoordinator.kt:401-407`（`settleColdStartGap`：`coldStartGapId = null; val since = coldStartLossSince; coldStartLossSince = null; guarded { closeOpenGaps(…); if (since != null) recordGap(…) }`）、`:224-231`（vault-Ready collector：`val since = vaultGapSince; vaultGapSince = null; guarded { closeOpenGaps(…); if (since != null) recordGap(…) }`）

**先說清楚什麼不會發生**：整個 codebase 唯一會關閉 DB 的路徑是 `VaultRepository.deleteEverything()` `:47-49` → `maintenance.exclusive {}` → `closeAndDeleteFiles()`，而 `exclusive` 的 block 在 `pipelineMutex` 內執行；`settleColdStartGap()` 也在 `pipelineMutex` 內，所以 reset **不可能**插進它中間。`retry()` 只在非 Ready 時重開，不關 DB。

**故障情境**（兩個都具體）：
1. `settleColdStartGap()`：冷啟動時金庫 Locked，`dropHeld` 記下 `coldStartLossSince = t0`。使用者修好 Keystore → Ready → `observeSources` 發射 → `loadSourcePolicy()` → `sources()`（讀）成功 → `settleColdStartGap()` 先把 `coldStartLossSince` 清成 null，再進 `guarded` → `recordGap` 的 INSERT 因**磁碟已滿**（`SQLiteFullException`）或 I/O 錯誤失敗 → `guarded` 吞掉 → **t0 那段遺失永遠不會再被寫**。之後使用者清出空間、擷取恢復正常，健康頁對這段遺失一無所知；同一時刻的 journal 寫入也會失敗，但那些有 `JOURNAL_FAILED` 診斷 + gap 的路徑（`:705-708`）——同樣會失敗，所以整段期間就是空白。
2. vault-Ready collector（`:219-236`，**不在** pipeline lock 內）：`vaultGapSince = tA` 已記下，金庫 Ready → collector 進到 `guarded` 中間，使用者此刻按下「刪除全部資料」→ `closeAndDeleteFiles()` `:88-89` 關 DB 並把狀態設成 `Opening` → `collectLatest` **取消**這個 block（或 `closeOpenGaps` 直接拋）→ `guarded` 依規則重拋 `CancellationException` → `vaultGapSince` 已是 null，沒有任何地方把它裝回去。專案的慣例是跨越 reset 的 gap 仍要寫進新金庫（`onMaintenance` `:569` 的 MAINTENANCE gap、以及 `coldStartLossSince` 本身都會在 reset 後寫入新 DB），所以這筆遺失依慣例不該消失。

**為什麼是 Minor 而不是 Important**：觸發需要「補寫的那一次」剛好撞上磁碟滿／I/O 錯誤，或 reset 剛好落在 Ready 後的那幾毫秒，比 round-12 觀察 1（任何 Locked 期間的遺失都消失）窄非常多；產品的 gap 記錄本來就是 best-effort（全檔所有 `recordGap` 都在 `guarded` 裡）。但它與 round-12 觀察 1 是**同一類缺陷**，`docs/adr/0007…md:42`「Gaps are shown, never hidden, even when the gap table itself was unreachable」與 `CLAUDE.md:69` 的措辭都比程式碼保證的多一點。兩個新測試（`:617-636`、`:638-653`）用的是 relaxed `health` mock，補寫那一次一定成功，所以「補寫失敗要保留記憶」沒有負面對照。

**修法**（兩行）：只在寫入成功後才清除——
```kotlin
val since = coldStartLossSince
var written = false
guarded {
    health.closeOpenGaps(now, GapReason.COLD_START)
    if (since != null) health.recordGap(since, now, GapReason.COLD_START, GapPrecision.BOUNDED, now)
    written = true
}
if (written) coldStartLossSince = null   // 失敗就留著，下一次 loadSourcePolicy 再試
```
vault-Ready collector 同理（`vaultGapOpen` 也建議一起延後到成功才翻 false，否則下一次 lock-out 不會再 openGap）。補一個測試：第一次 `recordGap` 拋、第二次成功 → 仍恰好寫入一次。

---

#### Minor-2 — `dropHeld()` 在 pipeline lock **外**執行，其 `openGap` 會等金庫開才落地，可能在 `settleColdStartGap()` 關閉之後才 INSERT，留下一筆 open 的 COLD_START row（既有缺陷，本輪改善但未消除）

**位置**：`CaptureCoordinator.kt:442-445`（`if (!loaded) { dropHeld(now); return }`，在 `withTimeoutOrNull { pipelineMutex.withLock {…} }` 之外）、`:497-498`（`openGap` → `HealthRepository.kt:52` → `holder.db()` → `DatabaseHolder.db()` 在 `Opening` 時 **suspend 直到終態**）

**故障情境**：金庫開啟超過 15 s（慢裝置 + migration）。t=15 s 逾時 → `dropHeld` 取走 held → `openGap` 在 `holder.db()` 上懸掛。t=20 s Ready：(a) `dropHeld` 恢復並 INSERT open row；(b) `observeSources` 發射 → `loadSourcePolicy()` → `settleColdStartGap()` → `closeOpenGaps` 的 SELECT。若 (a) 的 INSERT 落在 (b) 的 SELECT 之後，這筆 row 維持 open，`HealthScreen` 顯示「擷取仍有缺口」直到下一次 policy 載入（來源變更、下一次維護），而擷取其實正常。

**為什麼是 Minor**：a3fd01b 已有同一競態（而且當時 close 是條件式、更糟）；本輪把 close 改成無條件之後，任何後續 policy 載入都會把它關掉，是**改善**。(a) 只需一個 INSERT、(b) 要走 Room 初次發射 + `sources()` + `openGaps` 三個查詢，(a) 先到的機率高得多。

**修法**：`dropHeld` 在 `openGap` 回來後重讀 `sourcesLoaded`，若已 true 就立刻 `closeOpenGaps(now, COLD_START)`（三行）。**不建議**順手拿掉 open-row 機制：雖然金庫 `Opening`／`Locked` 期間健康頁讀不到 DB（`flowWithDb` 靜默），但 `dropHeld` 也會在「金庫 Ready、只是 pipeline lock 被 `exclusive {}` 的 reset／restore 佔超過 15 s」時觸發，那時 `openGap` 立刻成功、row 在維護結束後的 policy 重載之前是真的可見且有意義的。

---

#### Minor-3 — 新增的註解宣稱「舊 generation／暫停時跳過的 held 項目，各自的事件已記了 gap」，但那些 gap 的起點晚於 held 項目的到達時間

**位置**：`CaptureCoordinator.kt:468-470`（本 diff 新增的註解 + 既有的 `if (h.generation != activeGeneration || paused) continue`）；對照 `:280`（disconnect gap 從 `now` 開始）、`:334`（pause gap 從 `now` 開始）、`:569`（maintenance gap 從 `startedAt` 開始）

**故障情境**：t1 通知到達、被 hold（generation G1，金庫仍在開）。t2 > t1 listener 被系統 rebind（開機後幾秒常見）→ `onDisconnected` 開一筆 `[t2, …]` 的 LISTENER_DISCONNECTED gap → reconnect 後 G2。金庫開 → `releaseHeld()` → G1 的項目 `continue`。若使用者在 t2 之前就滑掉了那則通知，reconnect 的 active resync 撿不回來，**t1 的遺失沒有任何 gap 覆蓋**——註解說的「already records its own gap for that window」對 t1 不成立。

**為什麼是 Minor**：這是 round-12 觀察 2 的既有行為，且與 `admitted()` `:667-669 / :674-676` 對佇列中事件的既有慣例一致（`droppedAfterRevoke++`，不記 gap）；大多數情況 resync 會撿回仍在通知欄的項目。本輪新增的是**註解**，而註解把它說成已經覆蓋。

**修法**：優先把註解改成誠實敘述（「與佇列中被 `admitted()` fence 掉的事件同一慣例：不另記 gap，依賴 reconnect 的 active resync 撿回仍在通知欄的項目」）。若要真的補 gap，只對 `generation != activeGeneration` 的項目記一筆 `recordGap(min heldAt, disconnectAt, COLD_START, BOUNDED)`——`paused` 那一支是使用者主動暫停，標成 COLD_START 會誤導；且要注意起點取 held 到達時間、終點取 disconnect 時間，否則會與既有的 LISTENER_DISCONNECTED gap 重疊計算。

---

#### Minor-4 — 媒體改在交易外逐頁讀之後，快照之後才寫入的訊息其媒體會被匯出成「孤兒 Media 記錄」

**位置**：`BackupService.kt:185-208`（交易外 `mediaDao().exportPage(after, PAGE, now)`）、`Daos.kt:380-381`（查詢只以 `id > :afterId` 與到期過濾，**不限制 messageId 屬於快照內的訊息**）

**故障情境**：匯出交易在 t0 結束（messages 已序列化）。擷取繼續：t0 之後一則新訊息附圖 commit → `media_blob` 新列 id 比快照時的最大 id 大 → 媒體分頁把它讀出來、解密、base64 寫進備份。備份裡於是有一筆 `Media(messageId = X)`，但 `Message X` 不在備份裡。還原時 `BackupStager.kt:82-83` 不會拒絕（media 不納入 manifest 檢查，`End.actual.media = mediaWritten` 一致）；`apply()` `:280-294` 會把它解碼、**加密寫成檔案**，然後 `:384` 因沒人引用再刪掉。淨效果：備份多了最多 `MAX_MEDIA_BYTES` × N 的無用位元組，還原多做一次 AEAD 加密與刪檔，並占用 `maxStagedMediaBytes`（256 MB）的額度。

**brief 問的例外路徑**：一頁讀取時若金庫被鎖／retention 刪列，`exportPage` 拋例外 → `writeRecords` 不會寫 `End` → 例外一路傳到 `exportNow` `:114-116` 回 `Failed(IO)`，`:117-120` `finally` 刪掉 staging，**目標文件從未被開啟**，不會留下沒有 `End` 的串流。retention 在兩頁之間刪掉的列只會讓後續頁少幾筆，`End.actual.media` 仍等於實際寫入數；其訊息在備份內會是 `LOCAL_COPY` 但無 Media → 還原時 `:344` 誠實標成 `FAILED`。這部分**正確**。

**修法**：交易內多讀一個 `SELECT MAX(id) FROM media_blob`（一個查詢），交易外分頁加 `AND id <= :maxId`；這樣媒體集合除了刪除以外與訊息集合一致，也不再需要「manifest 的 media 是當下考慮的列數」這種說明。

---

#### Minor-5 — `docs/reviews/README.md:23` 的「every Minor … fixed」把 Minor-7 說成已修

**位置**：`docs/reviews/README.md:23`（`c6b6645`）、`docs/zh-Hant/reviews/README.md:21`

**故障情境**：round-12 Minor-7（manifest 媒體計數）的兩個修法選項都沒採用，`BackupRecords.kt` 不在 diff 內；實際處理是把限制寫進 KDoc（`BackupService.kt:132-135`）。這是合理的處置，但索引列寫「fixed」會讓下一位 reviewer 以為格式改了。

**修法**：改成「7 Minor fixed, the manifest media count documented instead」一類的措辭。工作區未 commit 的版本已在重寫這一列，順手處理即可。

---

## 其他觀察

1. **`coldStartLossSince ?: now` 是死的 fallback，無害。** `dropHeld` `:493` 已排除 `items.isEmpty() && dropped == 0`；`dropped > 0 ⟹ heldDroppedSince != null`（兩者在 `hold()` `:425-426` 同時設定、`:490-492` 同時歸零），`items` 非空 ⟹ `minOfOrNull` 非 null，所以 `start` 到 `:502` 時永遠非 null。
2. **殘餘的孤兒 hold 窗口（round-12 觀察 3 的最後一截）。** `offer()` `:586` 讀到 `sourcesLoaded == false` 後被搶占，`loadSourcePolicy()` 的 `:389-391` 與 cold-start job 的 `:447` 都跑完、job 仍 `isActive`（協程收尾中）的這幾微秒內 `hold()` 進來：不啟新 job（`:429`），項目留在 `held` 直到下一次 `loadSourcePolicy()`（來源變更、維護結束後的第一個事件）。不會遺失、`heldAtEpochMs` 保留，但若中間發生 reconnect 就落入 Minor-3。窗口是微秒級，且只影響一則。要收乾淨可在 `hold()` 內把「`sourcesLoaded` 已 true」也當成啟動條件：`if (sourcesLoaded || coldStartJob?.isActive != true) coldStartJob = scope.launch { coldStart() }`。
3. **釋放順序的殘餘：** 旗標翻成 true **之後**、`:391` 的第二次 `releaseHeld()` 仍在 snapshot 期間到達的通知會直接 `enqueue`，可能超前那批。跨通知的順序不是產品保證（每則各自以 `heldAtEpochMs` 為 observedAt、各自對帳），只影響像 CI 那種對 journal 順序有斷言的測試；現在的測試都在旗標翻轉前就把兩則都送進去，所以穩定。記錄用。
4. **`settleColdStartGap()` 無條件 `closeOpenGaps`：** 每次 policy 載入多一個 `SELECT … WHERE endEpochMs IS NULL AND reason IN ('COLD_START')`（`Daos.kt:467`），沒有 row 時零寫入、不觸發 Room invalidation；有 row 時只動 `gap_interval`，不會反饋到 `observeSources` 形成迴圈。成本可忽略。
5. **`SyntheticNotifications`：** `:63 / :86` 權限不足時直接 `return id` 而**不張貼**，回傳值看起來像成功。三個呼叫端（`InboxScreen.kt:112`、`HealthScreen.kt:117`、`OnboardingScreen.kt:90`）都先以 `canPost()` 擋在前面，所以 UI 不會誤判；只是這個 API 的契約值得在 KDoc 說一句。
6. **`releaseHeld()` 在 pipeline lock 內以 `scope.launch` 記 gap（`:465`）** 與 round-12 相同：fire-and-forget、被 launch 的協程不碰 lock，安全。
7. **文件與程式碼計數**：`CHANGELOG.md:35` 的「`./gradlew lint` passes for every module」、`:40` 的 +13 (24)、`:41` 的 198、`docs/SCOPE.md:76`、兩份 `TEST_MATRIX.md:21` 的 24、`docs/adr/0007…md:37-42`、`CLAUDE.md` 新增的工作規則與「Audit trail」段（#17 open）全部與程式碼、測試與 GitHub 狀態吻合；唯一過頭的是 Minor-1 指出的「even when the gap table itself was unreachable」（少了「補寫那一次仍可能失敗」的但書）與 Minor-5 的索引措辭。
8. **值得肯定的地方**：
   - 兩個新測試都讓 `openGap` **也**拋 locked——這正是 round-12 觀察 1 指出「測試編碼了一個現實中不成立的模型」的修正，而且 `exactly = 1` 在 guard 拿掉時會變 0，是真的負面對照。
   - `c6b6645` 不是把 flaky 測試放寬，而是修正產品順序（`releaseHeld()` 先於旗標）並把 bitmap 測試改成確定性；三次連跑全綠。
   - `heldDroppedSince` 與 `heldDropped` 在同一個 `synchronized(held)` 內設定與原子取出，沒有引入新的可見性問題。
   - 媒體分頁把 `mediaRows` 整表 list 拿掉的同時，KDoc 反而變得**更誠實**（明說交易內仍有序列化），沒有為了好看而過度承諾。
   - 發布物可獨立驗證：sha、upload key 指紋、versionCode、permission gate 四項都對得上文件。

---

## Assessment

**Ready as shipped; the five Minors go into the next commit, not a re-release.** `v0.1.1`（`c6b6645`）的程式碼與宣稱全部經實測或 CI 佐證：198 JVM 全綠、capture 24 三連綠、lint 14 模組 0 error、字串 318/318、CI 四 lane 綠（含 API 29 / 35 instrumented）、release APK sha／簽章／versionCode／無 INTERNET 相符。round-12 的所有發現都有對應改動，其中 Minor-7 以文件處理。沒有 Critical／Important 回歸。建議下一個 commit 先做 **Minor-1**（兩行 + 一個測試），順手做 Minor-2 的三行重檢與 Minor-3 的註解改寫；Minor-4、Minor-5 排進下一輪即可。
