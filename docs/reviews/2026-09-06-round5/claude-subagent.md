# Round 5 獨立複審 — QuietInbox（round-4 修復的 mini re-review）

- **審查標的**：`git diff 7587c73..fa49902`（branch `main`，HEAD `fa49902`），置於全庫脈絡中檢視
- **審查方式**：唯讀。未編輯／新增／刪除任何儲存庫檔案（本報告除外），未執行任何改變 git 狀態的指令，未執行 instrumented test，未接觸任何裝置或模擬器
- **執行過的指令**（皆為 JVM／建置任務，只寫入各模組 `build/` 目錄）：
  - `./gradlew test --console=plain`（brief 授權）
  - `./gradlew test --rerun-tasks --console=plain`（強制實際執行，避免只讀到 up-to-date 的舊結果）
  - `./gradlew :app:assembleRelease :platform:storage:compileDebugAndroidTestKotlin --console=plain` — **超出 brief 明列的指令**，用途是回答 brief 第 2 題（release variant 是否編譯得過、Hilt release graph 是否成立）與驗證兩支新 instrumented test 是否編譯得過。結果為 `601 actionable tasks: 2 executed, 599 up-to-date`，實質未重建任何產物。若判定此舉逾越授權，請以此段為準自行折算。
  - 對既有的 `app/build/outputs/apk/release/app-release.apk` 解壓 `classes.dex` / `resources.arsc` 到 `/private/tmp` 做 `strings` 比對
- **測試結果**：`BUILD SUCCESSFUL`，**157 tests / 0 failures / 0 skipped**（`--rerun-tasks` 實跑）。逐模組：core:model 5、core:parser 10、core:identity 5、core:reconcile 20、core:analytics 32、parsers:apps 43、platform:crypto 3、platform:backup 24、platform:capture 11、app 4。新增／變更的測試檔中無 `@Ignore`、`assertTrue(true)`、空測試或 `TODO(`
- **編譯驗證**：`:platform:storage:compileReleaseKotlin`、`:feature:settings:compileReleaseKotlin`、`:app:compileReleaseKotlin`、`:app:hiltJavaCompileRelease`、`:app:minifyReleaseWithR8` 全部 UP-TO-DATE（即在目前原始碼下成立）；`:platform:storage:compileDebugAndroidTestKotlin` UP-TO-DATE

---

## Verdict：**REQUEST CHANGES**

理由（一句話）：round-4 的 13 項指認幾乎全部真的修好了，**唯獨 I-5 只修了一半** — analytics 的 50,000 筆上限確實生效，但用來揭露這件事的 `analytics_capped` 字串**從未被任何 UI 程式碼引用**，於是超過上限的期間會靜默丟掉最舊的訊息、`quietRate` 把那些日子算成「安靜日」，而 `CHANGELOG.md:16`、`docs/SCOPE.md:21` 與 commit message 三處都寫著「the UI says so when capped」。這同時是**功能未完成**與**三份文件的不實陳述**，直接牴觸本專案「誠實的資料品質標籤」硬規則。

這條的修法很小（在 `AnalyticsScreen` 加一個 `Note()`，約 6 行），但它與 round 4 被判 REQUEST CHANGES 的 I-1（假的媒體計數）屬於完全相同的缺陷類型 — 「數字／標籤對使用者說謊」— 為求判準一致，此輪同樣給 REQUEST CHANGES。除這一項外，本 diff 品質良好，沒有 Critical，其餘皆為 Minor。

---

## Round-4 修復驗證表

### agy（Gemini 3.8 Flash high）

| 發現 | 狀態 | 證據 |
| --- | --- | --- |
| **Important 1** `summaryCountSince` 無結束時間上界 | ✅ 已修復 | `platform/storage/.../db/Daos.kt:366-367` 新增 `summaryCountBetween(since, until)`；`AnalyticsRepository.kt:41-42` 轉呼叫；`AnalyticsViewModel.kt:110` 改傳 `period.startEpochMs, period.endEpochMsInclusive`。舊的 `summaryCountSince` 保留給 `HealthRepository`（語義本就是「自某時間以來」），正確 |
| **Important 2** 中文 TEST_MATRIX 數據過期＋雙語皆漏列 capture／backup 測試 | ✅ 已修復 | `docs/zh-Hant/TEST_MATRIX.md:11` 由 `44（analytics 4）` 更正為 `72（analytics 32）`；兩份 TEST_MATRIX 各新增「備份 staging（21）」與「擷取協調器（11）」兩列。**我逐一點算實測數字驗證**：5+10+5+20+32=72 ✓、backup 24（21 stager + 3 hkdf）✓、capture 11 ✓、parsers 43 ✓、app 4 ✓，總計 157 ✓ |
| **Minor 1** SCOPE.md 測試狀態描述與現況不符 | ✅ 已修復 | `docs/SCOPE.md:25` 補上 `BackupStagerTest covers format and limits (21 JVM tests)`；`:26` 改為 `ReminderSchedulerTest covers delayUntilNext (4 JVM tests)` |
| **Minor 4** ADR-0001 未記載五個 parser 合併為 `:parsers:apps` | ✅ 已修復 | `docs/adr/0001-toolchain-and-module-layout.md` 新增「Addendum (2026-09-06)」段，並誠實載明 `:tools:*` 與 `:benchmark` 尚未建置 |
| **Minor 2** 期間切換無 loading 狀態 | ⛔ 未修（本 commit 未宣稱要修） | `AnalyticsViewModel.kt:84-86` 仍是 `combine → debounce → map`，切期間時前一份 `loading=false` 的狀態會繼續顯示；`debounce(400)` 反而讓這段空窗更長。見 Minor 4 |
| **Minor 3** `deleteConversation` 未清 `notification_checkpoint` | ⛔ 未修（未宣稱） | `InboxRepository.kt:86-97` 不變 |
| **Minor 5** lint `abortOnError = false` | ⛔ 未修（未宣稱） | `build-logic/.../quietinbox.android.library.gradle.kts` 不變 |

### Kimi K3

| 發現 | 狀態 | 證據 |
| --- | --- | --- |
| **I-1** 還原媒體檔洩漏＋計數失真 | ✅ 已修復 | 同 subagent I-1，見下 |
| **I-2** `isSelf` 以顯示名稱比對 | ✅ 已修復 | `SnapshotFactory.kt:163-168` 改為 key → uri → name 的優先序 |
| **Minor** 商店文案「7–365 天」與滑桿實作不符 | ✅ 已修復 | `fastlane/metadata/android/en-US/full_description.txt:16` 與 `zh-TW/full_description.txt:16` 皆改為 `1–365` / `1 到 365 天`，與 `SettingsScreen.kt:149-159` 的滑桿一致 |
| **Minor** `SnapshotFactory` 註解被兩個空行截斷 | ✅ 已修復 | `SnapshotFactory.kt:62-64` 已併回連續三行 |
| **Minor** `DemoDataRepository` 進 release APK | ✅ 已修復 | 見 subagent I-4 |
| **Minor** debug `DemoReceiver` exported 無 permission | ⛔ 未修（未宣稱，debug-only，Kimi 自己也判定可接受） | — |
| **Minor** `deleteGaps` 以毫秒對接刪除 | ⛔ 未修（未宣稱，DAO 註解已誠實說明） | `Daos.kt:433-442` |

### Claude subagent

| 發現 | 狀態 | 證據 |
| --- | --- | --- |
| **I-1** 還原永久遺留加密媒體檔＋假媒體計數 | ✅ 已修復 | `BackupService.kt:215` 新增 `usedFiles: HashSet<String>`；`:304` 只在 blob 真的掛上訊息時 `usedFiles += blob.fileName`；`:337` `Counts(..., usedFiles.size)`；`:340` 交易成功後 `for (f in writtenFiles) if (f !in usedFiles) mediaDir.delete(f)`。同機合併還原的 100% 洩漏路徑已封住，回報數字也回到真實值 |
| **I-2** 預備迴圈與 `try` 之間的清理空窗 | ✅ 已修復（且比我建議的更乾淨） | `BackupService.kt:220-224`：`now` 與 `retentionMs = settings.current()...` **上移到 `return try` 之前**，預備迴圈**下移到 `try` 之內**。因此唯一的 suspend 呼叫 `settings.current()` 在任何檔案落地之前執行，`:343-344` 註解「blobs written outside it are removed on every failure, cancellation included」現在名實相符 |
| **I-3** `summaryOnlyCount` 無期間上界 | ✅ 已修復 | 同 agy Important 1 |
| **I-4** `DemoDataRepository` / `DemoDao` 進入 release build | ✅ 已修復（seeder 與虛構內容） | `DemoDataRepository.kt` 由 `src/main` **rename 到 `src/debug`**；`src/main/.../repo/DemoData.kt` 新增 `interface DemoData` + `object NoDemoData`；`src/debug/.../di/DemoModule.kt` 用 `@Binds` 綁實作，`src/release/.../di/DemoModule.kt` 用 `@Provides` 綁 `NoDemoData`。`SettingsViewModel.kt:59` 與 `DemoReceiver.kt:37` 改依賴介面。**實測 release APK 的 `classes.dex`：`demo.quietinbox` 與 `demo-` 前綴皆為 0 命中**，虛構內容確實不在產物中。殘留項見 Minor 6 |
| **I-5** analytics 無上限載入／無節流 | ⚠️ **部分修復 → 見 Important 1** | 上限與節流都做了（`Daos.kt:224-228`、`AnalyticsRepository.kt:20,48`、`AnalyticsViewModel.kt:84-85`），但**揭露上限的 UI 從未實作**，且三份文件宣稱已實作 |
| **M-1** `isSelf` 名稱比對脆弱 | ✅ 已修復（空白名稱防呆未加） | `SnapshotFactory.kt:163-168`。`else` 分支仍是 `selfName != null && person.name?.toString() == selfName`，未採納「排除空白字串」的建議。見 Minor 5 |
| **M-2** `Period.custom` 無跨度上限 | ✅ 已修復 | `core/analytics/.../Insights.kt:94-99`：`val floor = to.minus(DatePeriod(days = MAX_SPAN_DAYS)); between(maxOf(from, floor), to, ...)`，與 `all()`（`:85`）一致 |
| **M-3** lazy conversation 無測試 | ✅ 已修復 | `VaultRoundTripTest.kt:158-181` 新增 `deletedConversationDoesNotResurrectOnReplay`。**已驗證編譯通過**，且所有 API 簽章正確：`ingest.findConversationId`（`IngestRepository.kt:127`）、`ingest.checkpoint`（`:107`）、`inbox.deleteConversation(id, now, ttl)`（`InboxRepository.kt:86`）、`Reconciler.reconcile`（`Reconciler.kt:107-113`）、`commit(...)` 七參數（`IngestRepository.kt:139-147`）、`CommitOutcome.newMessageIds`（`:39`），`first`／`KnownSources`／`StandardParser`／`IdentityResolver`／`Reconciler` 皆已在檔頭 import。斷言涵蓋 `conversationId == null`、`newMessageIds` 為空、`findConversationId` 為 null、`conversationDao().observeCount() == 0`，語義正確 |
| **M-4** `DemoDataTest` 缺「非 demo 資料存活」反向斷言 | ⚠️ **部分修復** | `DemoDataTest.kt:72-78` 只插入了一列**非 demo 的 source**，`:113` 斷言 `sourceDao().get("com.example.real") shouldNotBe null`。conversation／message／search_token／gap／session／diagnostic 六類都沒有反向斷言 — 而風險最高的正是 `deleteGaps`（以毫秒對接刪除）。見 Minor 7 |
| **M-5** `offerCaptured` 為 `internal` | ✅ 已修復 | `CaptureCoordinator.kt:317` 加上 `@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)` |
| **M-6** GitHub Action 以可變 tag 釘選 | ✅ 已修復（且比建議更完整） | `ci.yml`／`release.yml` 全部改為 40 字元 commit SHA + `# vX.Y.Z` 註解，含最高風險的 `r0adkll/upload-google-play@e738b9dd…`。**額外**：`release.yml:20-21` workflow 層 `permissions` 由 `contents: write` 收斂為 `contents: read`，只在 `github-release` job（`release.yml:75-76`）下放 `contents: write`；`google-play` job 因此只有唯讀權限 |
| **M-7** `keystore.properties` 缺欄位時 configuration 階段 NPE | ✅ 已修復 | `app/build.gradle.kts:25,32` 以 `requireNotNull(...) { "keystore.properties is missing '$name'" }` 與 `requireNotNull(...) { "$name must be set together with QUIETINBOX_KEYSTORE_FILE" }` 包裝，env 分支一併處理 |
| **M-8** `MediaCopier.copyPending` 扇出未受限 | ✅ 已修復（兩件事都做了） | `MediaCopier.kt:47-51` 先把 bitmap 壓縮成一份 immutable `ByteArray`；`:54-56` `parallelism.withPermit { }` 現在包住 `db.messageDao().get(id)`。`Bitmap` 執行緒安全問題與 DB 讀取不受號誌限制的問題同時解決。新增的小代價見 Minor 8 |
| **M-9** 交易提交後被取消，`catch` 會刪掉已掛上的媒體檔 | ⛔ **未修**（本 commit 未宣稱） | `BackupService.kt:342-345` 的 `catch` 仍無條件 `for (f in writtenFiles) mediaDir.delete(f)`。見 Minor 1 |

### 其他 brief 指定的確認項

| 項目 | 結果 |
| --- | --- |
| `versionCode = 2` | ✅ `app/build.gradle.kts:50`；`fastlane/.../changelogs/1.txt` 已 rename 為 `2.txt`（兩個 locale）。commit message 說明了原因：「versionCode 1 only reached the Play internal track」。殘留問題見 Minor 9 |
| release variant 是否能在完全不引用 `DemoDataRepository` 的情況下編譯 | ✅ 全庫 grep 確認 `DemoDataRepository` 只出現在 `src/debug/**` 與 `src/androidTest/**`；`:app:compileReleaseKotlin`、`:app:hiltJavaCompileRelease`、`:app:minifyReleaseWithR8` 皆成立 |
| debug `DemoModule` 是否正確供給 `SettingsViewModel` 與 `DemoReceiver` | ✅ 兩者都改依賴 `DemoData` 介面（`SettingsViewModel.kt:59`、`DemoReceiver.kt:37`）。`app` 只有 `debug`／`release` 兩個 build type（`app/build.gradle.kts:56-66`），library 模組預設同名 variant，對應成立 |
| Hilt duplicate-binding 風險 | ✅ 無。debug 與 release 的 `DemoModule` **同 FQN `dev.quietinbox.platform.storage.di.DemoModule`**，同一次建置只會編譯其中一個 source set，不可能同時存在。`@Binds` 綁的 `DemoDataRepository` 本身是 `@Singleton`，release 的 `@Provides` 回傳 `object NoDemoData`，兩者都是單一實例，無 scope 衝突 |
| `androidTest` 能否看到 debug source set | ✅ `testBuildType` 為預設的 `debug`，`:platform:storage:compileDebugAndroidTestKotlin` 編譯通過即為證據 |
| `statsBetween` 排序正確性 | ✅ `ORDER BY m.sortKey DESC LIMIT :limit`（`Daos.kt:224-225`）取最新 N 筆，`AnalyticsRepository.kt:21` 以 `.asReversed()` 還原成「最舊在前」，與 `ActivityAnalytics` 的既有假設一致。效能面見 Minor 3 |
| `debounce(400)` 後首次發射是否仍會抵達／選期間變更是否仍會重算 | ⚠️ 會，但首次延遲 400 ms；且 `distinctUntilChanged()` 只掛在 `observeCounts()` 上，選期間變更一定會通過 `combine` → 一定重算。見 Minor 4 |
| `SnapshotFactory.isSelf` 各案例 | ⚠️ 大致正確，有一個方向性副作用。見 Minor 5 |
| 文件誠實性 | ⚠️ TEST_MATRIX／SCOPE 的測試數字全部核對無誤；**但 SCOPE.md:21 與 CHANGELOG.md:16 的「the UI says so when capped」不實**（Important 1），SCOPE.md:28 的 dex 宣稱不精確（Minor 6） |
| round-4 報告歸檔是否忠實 | ✅ `docs/reviews/2026-09-06-round4/` 三份與 `.omc/research/` 原稿逐行 diff，**唯一差異是把 `file:///Users/iml1s/…` 絕對路徑換成相對路徑**，沒有任何結論、嚴重度或措辭被改寫 |

---

## Critical

無。

---

## Important

### I-1. `capped` 誠實標籤只做了一半：資料被靜默截斷，而三份文件宣稱已向使用者揭露

**檔案**
- `feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt:63-64`（`capped` 欄位）、`:145`（`capped = messages.size >= AnalyticsRepository.MESSAGE_CAP`）
- `core/designsystem/src/main/res/values/strings_analytics.xml:76` 與 `values-b+zh+Hant/strings_analytics.xml:75`（`analytics_capped` 字串）
- `feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsScreen.kt`（**完全沒有引用**）
- `CHANGELOG.md:16`、`docs/SCOPE.md:21`、commit `fa49902` 的 message

**問題**

全庫搜尋 `capped` 與 `analytics_capped`，命中只有三處：ViewModel 的欄位宣告、ViewModel 的賦值、以及兩個 locale 的字串資源。`feature/analytics` 只有兩個 `.kt` 檔（`AnalyticsScreen.kt`、`AnalyticsViewModel.kt`），而 `AnalyticsScreen.kt` **一次都沒有讀取 `state.capped`，也沒有引用 `R.string.analytics_capped`**。換句話說：旗標算出來了、字串寫好了、雙語都翻譯了，但沒有任何一個 Composable 會把它畫出來。

而 `AnalyticsRepository.kt:18` 的 KDoc 明文寫著「A page that receives exactly [limit] rows **must** tell the user the period was capped」，`CHANGELOG.md:16` 寫「analytics load at most 50,000 messages per period **(the UI says so when capped)**」，`docs/SCOPE.md:21` 寫「at most 50,000 messages per period are loaded **(the page says when it capped)**」，commit message 寫「**UI states when capped**」。三份對外文件與一份程式碼契約，陳述的是一件沒有發生的事。

**為什麼是 Important 而不是 Minor**

不只是「少了一行提示」。`statsBetween` 取的是**最新的** 50,000 筆（`ORDER BY m.sortKey DESC`），被丟掉的是**最舊的**。但 `ActivityAnalytics.quietRate(messages, period, zone)` 的分母是 `period.days(zone)` — **整個期間的天數**，不是有資料的天數。於是選 `PeriodKind.ALL`（可達 3660 天）而訊息數超過上限時：

- 期間前段那些其實有訊息的日子，因為訊息被 `LIMIT` 丟掉而被算成「安靜日」→ 神隱率被高估
- 熱力圖、排行榜、時段分佈、口頭禪同樣只看得到最新的 50,000 筆，但畫面上的期間標籤（`analytics_range_line`、`analytics_period_days`）仍宣告的是完整期間
- 使用者拿到的是「這個對話在過去三年有 62% 的日子沒理你」這種**看起來精確、實際上是採樣造成的**數字

這正是本專案 `CLAUDE.md` 與 `docs/SCOPE.md` 一再強調的硬規則：**觀測不到就要說觀測不到，不能讓演算法的截斷偽裝成事實**。round 4 判 REQUEST CHANGES 的 I-1 是「回報的媒體數是假的」，本項是「回報的安靜率是假的」，同一類。

**修法**（`AnalyticsScreen.kt`，接在期間列或 `analytics_disclaimer` 附近，兩處都畫更好）

```kotlin
if (state.capped) {
    Note(stringResource(R.string.analytics_capped, AnalyticsRepository.MESSAGE_CAP))
}
```

順帶把三份文件的措辭在同一個 commit 內對齊；若決定暫不做 UI，就必須反過來把 `CHANGELOG.md:16`、`docs/SCOPE.md:21` 與 `AnalyticsRepository.kt:18` 的 KDoc 全部改成不宣稱有揭露 — 但那會留下「靜默截斷」本身的誠實問題，不建議。

**額外一點**：目前只有「有沒有超過上限」這個布林，沒有把「實際涵蓋的最早時間」傳給 UI。若要做到位，`compute()` 可在 capped 時把 `messages.first().sortKey` 當作實際起點，讓期間標籤顯示真正涵蓋的區間。這屬於加分項，非 blocker。

---

## Minor

### M-1. 交易提交後才被取消，`catch` 仍會刪掉已掛上的媒體檔（round-4 M-9 未修）

`platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:342-345`

```kotlin
} catch (e: Exception) {
    for (f in writtenFiles) mediaDir.delete(f)   // 仍不分「已掛上」與「未採用」
    if (e is CancellationException) throw e
```

`db.withTransaction { }`（`:225`）是 `withContext(transactionContext)`；若外層 job 在交易 **已 commit、`withContext` 尚未回傳** 的瞬間被取消（使用者離開設定頁、ViewModel scope 結束），`withContext` 會在恢復時丟出 `CancellationException`，落進這個 `catch`，把 `usedFiles` 裡那些**資料列已經寫進資料庫**的檔案一併刪掉。結果是一批 `MediaBlobEntity` 指向不存在的檔案 — 圖片永遠顯示不出來，而 `RetentionWorker.orphans()` 也清不掉（那些列的 `messageId` 有效，不算孤兒）。

視窗確實很窄（`:340` 的清理迴圈與 `MediaDirectory.delete`（`RetentionWorker.kt:107-109`）都不是 suspend，commit 之後到 `try` 結束沒有任何 suspension point，唯一的取消點在 `withContext` 的恢復路徑），但 I-1 的修法讓後果從「多留幾個沒用的檔」變成「資料列與檔案不一致」，而修法只有兩行：

```kotlin
var committed = false
return try {
    val counts = db.withTransaction { ... }
    committed = true
    for (f in writtenFiles) if (f !in usedFiles) mediaDir.delete(f)
    BackupResult.Ok(counts)
} catch (e: Exception) {
    for (f in writtenFiles) if (!committed || f !in usedFiles) mediaDir.delete(f)
    ...
```

### M-2. `BackupService.apply` 的預備迴圈未隨 `try` 重新縮排，作用域在視覺上是錯的

`platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:222-238`

`return try {`（`:222`，12 空格）之後，`for (media in s.media) {`（`:224`）與整段迴圈維持在 **8 空格**，看起來像在 `try` 外面；真正在 `try` 內的 `val counts = db.withTransaction {`（`:239`）又回到 12 空格。Kotlin 不在意，但這正是 round-4 I-2 出錯的那個位置 — 下一個維護者很可能只看縮排就重演同一個誤判。整段內縮四格即可（不影響行為，diff 也乾淨）。

### M-3. `ORDER BY m.sortKey` 沒有可用索引，記憶體有上限但 CPU 成本反而上升

`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:218-228`

`message` 表的索引（`platform/storage/schemas/…/2.json`）只有 `index_message_conversationId_sortKey`、`…_sourceMessageId`、`…_fingerprint`、`…_expiresAtEpochMs`、`…_observedAtEpochMs`、`…_mediaState` — **沒有以 `sortKey` 為首欄的索引**。複合索引的前導欄是 `conversationId`，無法服務 `WHERE m.sortKey BETWEEN ? AND ? ORDER BY m.sortKey DESC`。

因此新加的 `ORDER BY` 會讓 SQLite 全表掃描 `message` 後再建暫存 B-tree 排序（`LIMIT` 只能把 sorter 壓在約 50,000 筆，省不掉掃描本身）。改動前沒有 `ORDER BY`，是純串流掃描。所以這次是「用 CPU 換記憶體」：OOM 風險換掉了，但每次重算的資料庫工作量變大 — 對一個主打省電的 App 值得記一筆。搭配 `debounce` 之後淨效果多半仍是改善，故列 Minor。

**修法選項**（權衡請自行決定，都不擋發布）：
1. 在 v3 migration 加 `@Index(value = ["sortKey"])`。純新增索引、非破壞性，但要動 schema 與遷移測試，發布前未必想做。
2. 改成 `ORDER BY m.id DESC`（PK 可反向走）。省掉排序，但語義從「來源時間序」變成「寫入序」，對亂序抵達的通知會取到不同的 50,000 筆 — 需先確認可接受。
3. 什麼都不做，但把上述取捨寫進 `Daos.kt` 該查詢的註解，讓下一個人知道這是刻意的。

### M-4. `debounce(400)` 與宣稱的「at most every 400 ms」語義不符，且會延遲首次繪製

`feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt:81-86`

三件事：

1. **首次發射被延後 400 ms**。`debounce` 對每一次發射都等靜默 400 ms，包含第一次。`stateIn` 的初值是 `AnalyticsUiState()`（`loading = true`，`:50`），所以畫面會多轉 400 ms 的載入。`SharingStarted.WhileSubscribed(5_000)` 意味著使用者離開分頁超過 5 秒再回來，又要再吃一次這 400 ms。
2. **持續高頻發射時會餓死**。`debounce` 是 trailing-edge 延遲，不是速率限制：只要 `observeCounts()` 的間隔穩定小於 400 ms（收訊高峰），就**永遠不會**重算。程式碼註解（`:81-82`）寫「never more than twice a second」— 400 ms 其實是 2.5 次/秒，而且描述的是 `sample`／`throttleLatest` 的行為，不是 `debounce`。`CHANGELOG.md:16` 的「recompute at most every 400 ms」倒是成立（頻率上界確實成立），只是它沒說會餓死。
3. `distinctUntilChanged()` 只掛在 `observeCounts()` 上，用的是 `InboxCounts` data class 的 `equals`（`InboxRepository.kt:43-50`），確實濾掉了「不影響計數的更新」（例如 `setMedia`），這是正確的改善。但反過來也代表**訊息內文被修訂（revision）而計數不變時不會重算** — 對口頭禪／emoji 統計是小失真。

**修法**：把 `.debounce(400)` 換成 `kotlinx.coroutines.flow.sample(400)`（首次立即、之後每 400 ms 最多一次、不餓死），或用 `selection.flatMapLatest { s -> counts.sample(400).map { s } }` 讓選期間變更走即時路徑、資料變動走節流路徑。並修正 `:81-82` 的註解。

### M-5. `isSelf` 的 key/uri 分支會讓「user 只有名字、訊息 Person 有 key」的自己被判成非自己

`platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/SnapshotFactory.kt:163-168`

```kotlin
isSelf = when {
    person == null -> true
    self?.key != null || person.key != null -> self?.key != null && person.key == self.key
    self?.uri != null || person.uri != null -> self?.uri != null && person.uri == self.uri
    else -> selfName != null && person.name?.toString() == selfName
}
```

逐案例列舉：

| `messaging.user` | 訊息的 `person` | 結果 | 評價 |
| --- | --- | --- | --- |
| 任意 | `null` | **self** | ✅ 符合 MessagingStyle 語義 |
| 有 key K | 有 key K | **self** | ✅ |
| 有 key K | 有 key K′≠K | 非 self | ✅ 同名的真實聯絡人不會再被誤判 — 這正是 M-1／Kimi I-2 要解決的 |
| 有 key | **無 key** | 非 self | ⚠️ 即使名字相同也判非 self |
| **無 key** | 有 key | 非 self | ⚠️ **brief 第 4 題的情境**：App 只給 `user` 設了名字，卻替自己的外送訊息掛了帶 key 的 `Person` → 擁有者被標成非自己 |
| 無 key、有 uri | 無 key、同 uri | self | ✅ |
| 兩邊都無 key 無 uri | 名字相同 | self | ✅ |

「真實聯絡人被誤標為自己」的方向已經封死（回答 brief 第 4 題的前半），這是好事；剩下的是反方向的**低估**（擁有者被標成非自己）。低估的後果是自己的訊息混進 `topSenders`／`catchphrases`，比高估溫和，但仍是誠實標籤問題。

另外 round-4 M-1 建議的「退回名稱比對時排除空白字串」**沒有採納**：`else` 分支只檢查 `selfName != null`，兩邊名字都是 `""` 時仍會判成 self。

**修法**：在 key/uri 分支不成立時再退回名稱，而不是直接判死；並補上空白防呆。

```kotlin
person == null -> true
self == null -> false
self.key != null && person.key != null -> person.key == self.key
self.uri != null && person.uri != null -> person.uri == self.uri
else -> !selfName.isNullOrBlank() && person.name?.toString() == selfName
```

**且此改動完全沒有測試**：`platform:capture` 有 11 個 JVM 測試，全部在 `CaptureCoordinatorTest`，沒有一個碰 `SnapshotFactory.bound`。我理解 `NotificationCompat.MessagingStyle.Message` 與 `Person` 在純 JVM 下不易建構（需要 Robolectric 或 instrumented），若確實如此請在 `docs/TEST_MATRIX.md` 誠實記一筆「isSelf 判定僅由人工／裝置驗證」。

### M-6. 「release dex 不含任何 demo class 或 text」的宣稱不精確

`docs/SCOPE.md:28`、commit `fa49902` message

我實際解開 `app/build/outputs/apk/release/app-release.apk` 比對：

- **`classes.dex` 中 `demo.quietinbox` 與 `demo-` 前綴：0 命中** — 801 行 seeder 的虛構內容與標籤前綴確實不在產物中，I-4 的核心目標達成 ✅
- 但 `classes.dex` 的 `strings` 仍有 `!, developerTools=false, lastDemo=` — 這是 `SettingsUiState`（`SettingsViewModel.kt:37` 的 `lastDemo` 欄位）自動產生的 `toString()`。字面上「no demo … text」不成立
- 更值得說清楚的是 **`resources.arsc` 含四個使用者可見的 demo 字串**：`Fill with demo data`、`Remove demo data`、`Demo data removed.`、`Demo data written: %1$d conversations, %2$d messages.`（`core/designsystem/src/main/res/values/strings.xml:279-282`，zh-Hant 同位置）。`isShrinkResources = true` 沒有移除它們，因為 `SettingsScreen.kt` 引用它們的程式碼在 release 仍被編譯，只是靠 `BuildInfo.debug` 在**執行期**關掉
- `DemoData`／`NoDemoData`／`DemoDao`／`QuietInboxDatabase.demoDao()`（`Daos.kt:401`、`QuietInboxDatabase.kt:49`）本來就設計成留在 main。R8 開啟混淆後我無法從 `strings` 判斷它們是被移除還是只是被改名 — 但 `DemoDao` 的 SQL 全部以參數帶入前綴（`Daos.kt:403-445` 的 `:packagePrefix` / `:generationPrefix`），所以就算保留也不含任何 demo 字面值，這個設計是對的

**修法**（純文件）：把 `docs/SCOPE.md:28` 與 commit 敘述改成可驗證的說法，例如「release 的 dex 不含 seeder 類別，也不含任何 demo 內容或 `demo.quietinbox.` / `demo-` 標籤；開發者區塊的字串與其執行期閘控的 UI 仍留在 resources」。

### M-7. `DemoDataTest` 的反向斷言只覆蓋 source 一類

`platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/DemoDataTest.kt:72-78, 113`

新增的斷言只插入一列 `com.example.real` 的 `SourceConfigurationEntity` 並斷言它在 `clear()` 後仍在。`clear()` 的承諾是「只刪 demo 列」，但 conversation／message／search_token／diagnostic／summary_observation／capture_session／gap_interval 七類都沒有反向斷言 — 而風險最高的正是 `deleteGaps`（`Daos.kt:433-442`，以 `createdAtEpochMs IN (SELECT startedAtEpochMs …)` 對接刪除）。

同時請注意：`:122` 的 `dao.countAllGaps() shouldBe 0` 是**全域**計數，所以要補一列真實 gap 的話得同時把這個斷言改成「等於 1」，否則測試會反過來失敗。建議至少補 conversation + message 一組（`packageName = "com.example.real"`），並把 gap 那條一起處理。

### M-8. `MediaCopier` 現在無條件先壓縮 bitmap，即使沒有任何訊息用得到

`platform/media/src/main/kotlin/dev/quietinbox/platform/media/MediaCopier.kt:47-51`

```kotlin
val bitmapBytes: ByteArray? = bitmap?.let { b ->
    val out = ByteArrayOutputStream()
    if (runCatching { b.compress(Bitmap.CompressFormat.PNG, 100, out) }.getOrDefault(false)) out.toByteArray() else null
}
```

壓縮從「用得到時才做」變成「進函式就做」。當 `messageIds` 為空、所有列都不是 `PENDING`（`:56`）、或每一列都走 `row.mediaUri != null` 分支（`:58`）時，這次 PNG 100 品質的壓縮完全白做，而且產出的 `ByteArray` 會在整個 `coroutineScope` 期間佔著記憶體。修掉 `Bitmap` 執行緒安全問題是對的，但可以順手改成 lazy 一次：

```kotlin
val bitmapBytes: Deferred<ByteArray?>? = bitmap?.let { b -> async(start = CoroutineStart.LAZY) { compressOnce(b) } }
```
或最簡單的 `by lazy { }` 搭配一個 `Mutex`／`AtomicReference` 保證只壓一次。非 blocker。

### M-9. `versionCode` 已跳到 2，但 CHANGELOG 沒有對應的版本區段，商店 release notes 也還是「First release」

- `app/build.gradle.kts:50` `versionCode = 2`，`versionName` 仍是 `"0.1.0"`
- `CHANGELOG.md` 頂端仍是 `## [Unreleased]`（第 5 行），round 3／4／whole-repo 的所有修復都在裡面；`## [0.1.0] — 2026-09-06`（第 18 行）那段寫著「Not published to any store」，而 commit message 說 versionCode 1 已經上過 Play internal track
- `.github/workflows/release.yml:89` 以 `awk -v ver="${TAG#v}" '/^## \[/{p = index($0, "[" ver "]") > 0} p' CHANGELOG.md` 抓 release notes。**現在若打 `v0.1.0` tag，抓到的會是那段說「Not published to any store」的舊文字**，`[Unreleased]` 裡的所有修復一個都不會出現在 GitHub Release 說明
- `fastlane/metadata/android/{en-US,zh-TW}/changelogs/2.txt` 是 `1.txt` 原封不動 rename 過來的，內容仍是「First release: …」；`fastlane/whatsnew/whatsnew-{en-US,zh-TW}`（`release.yml:115` 實際上傳的目錄）也是同一份

`docs/RELEASE.md:26-27` 的步驟 1 本來就要求「先在 CHANGELOG 加版本區段、再加 `changelogs/<versionCode>.txt`」，所以流程上有規範；但目前 repo 的狀態是「versionCode 已經動了、CHANGELOG 與 changelog 文字都還沒動」。發布前請補齊，否則 GitHub Release 與 Play 的「更新內容」都會與實際內容不符 — 這同樣落在誠實揭露的範圍內。

### M-10. 本次全部行為變更沒有任何新的 JVM 測試

`--rerun-tasks` 實測 **157 tests**，與 round 4 完全相同（core:analytics 仍是 32）。本 commit 改了：`Period.custom` 的箝制、`statsBetween` 的排序與上限、`isSelf` 的判定優先序、`MediaCopier` 的 bitmap 路徑、`BackupService` 的記帳 — 新增的兩支測試都是 instrumented（需要裝置，本輪未執行）。

其中 **`Period.custom` 是純 Kotlin、零相依、所在模組已經有 32 個 JVM 測試**，加一個「起訖顛倒 + 超過 3660 天要被箝制」的測試成本大約五行，卻沒有加。建議至少補這一個，讓 `core:analytics` 的 JVM lane 涵蓋本次改動。

---

## 已逐項驗證、確認無問題

- **`BackupService` I-1／I-2 的修法本身是正確的**：`usedFiles` 在交易內累加（`:304`），`Counts` 在交易的最後一個運算式讀它（`:337`），時序無誤；交易若中途拋出，Room 回滾且 `catch` 會清掉全部檔案，`usedFiles` 殘值不影響結果。`settings.current()` 移到 `try` 之外是**安全的**，因為此時尚未寫入任何檔案
- **Hilt 變體切換無 duplicate binding**：debug／release 兩個 `DemoModule` 同 FQN、同 `@InstallIn(SingletonComponent::class)`，同一次建置只編譯其中一個；`:app:hiltJavaCompileRelease` 與 `:app:minifyReleaseWithR8` 成立即為 release graph 可解的證據
- **`DemoData` 介面的預設參數用法正確**：`interface DemoData { suspend fun seed(now: Long = System.currentTimeMillis()) }`（`DemoData.kt:12`）帶預設值，兩個實作的 `override` 都不帶預設值（Kotlin 規定如此）。`SettingsViewModel` 透過介面型別呼叫，預設值解析得到
- **`DemoDao` 留在 main 是必要且無害的**：Room 的 DAO 不能是 variant-specific，且所有刪除語句都以 `:packagePrefix` / `:generationPrefix` 參數帶入前綴（`Daos.kt:403-445`），字面值全在 debug source set 的 `DemoDataRepository` companion 裡。`Daos.kt:393-399` 的註解已誠實說明這個取捨
- **兩支新 instrumented 測試編譯通過且斷言正確**：`:platform:storage:compileDebugAndroidTestKotlin` UP-TO-DATE；`VaultRoundTripTest` 用到的六個 API 簽章與五個 import 我逐一比對過（詳見上表 M-3）；`DemoDataTest` 用的 `SourceConfigurationEntity` 八個具名參數與 `Entities.kt:11-21` 完全對應
- **`statsBetween` 的 `asReversed()` 用法正確**：`asReversed()` 回傳的是視圖，後面接 `.map { }` 產生新 list，沒有洩漏可變視圖
- **Workflow SHA 釘選與權限收斂確實到位**：`ci.yml` 與 `release.yml` 共 13 處 `uses:` 全部是 40 字元 SHA + 版本註解，含持有 `PLAY_SERVICE_ACCOUNT_JSON` 的 `r0adkll/upload-google-play`；workflow 層降為 `contents: read`，只有 `github-release` job 拿 `contents: write`，`google-play` job 現在無寫入權限
- **`fastlane/whatsnew/` 目錄真的存在**（`whatsnew-en-US`、`whatsnew-zh-TW`），`release.yml:115` 的 `whatsNewDirectory: src/fastlane/whatsnew` 不會落空
- **round-4 報告歸檔忠實**：三份與 `.omc/research/` 原稿逐行 diff，只有絕對路徑改相對路徑
- **測試品質**：157 tests、0 failures、0 skipped；`platform/storage/src/androidTest`、`platform/backup/src/test`、`platform/capture/src/test`、`core/analytics/src/test` 全部掃過，無 `@Ignore`、`assertTrue(true)`、`TODO(`

---

## 其他觀察（非缺陷）

- `docs/zh-Hant/` 底下**沒有 SCOPE.md**（只有 ARCHITECTURE、COMPATIBILITY、CONTRIBUTING、PRIVACY、RELEASE、SECURITY、TEST_MATRIX、adr、reviews）。這是既有狀態、非本 commit 造成，所以 SCOPE.md 本次的兩處改動沒有製造新的雙語漂移。但 SCOPE.md 是誠實揭露最密集的一份文件，長期缺中文版值得補
- `capped = messages.size >= MESSAGE_CAP`（`AnalyticsViewModel.kt:145`）在**恰好** 50,000 筆時會回報 capped，而 `analytics_capped` 字串寫的是「more than %1$d」。`AnalyticsRepository.kt:18` 的 KDoc 說這是刻意的保守作法（寧可多提醒），可接受；若要精確，把 `limit` 傳成 `MESSAGE_CAP + 1` 再判 `> MESSAGE_CAP` 即可
- `analytics_capped` 一次用了兩個 `%1$d`，Android 允許重複位置參數，不會觸發 `StringFormatMatches`。但目前它是未使用資源，`UnusedResources` 會出 lint warning — 因為 `abortOnError = false`，CI 不會擋（這也再次說明 agy Minor 5 值得處理）
- `distinctUntilChanged()` 之後，訊息**修訂**（revision，內文變了但計數不變）不會觸發 analytics 重算。對口頭禪／emoji 是小失真，實務上可忽略
- `Period.custom` 現在會靜默把起始日往後夾到 3660 天內，UI 沒有告知。`all()` 的行為一樣，屬既有一致性，非新問題

---

## 發布前建議動作

1. **（blocker）** 在 `AnalyticsScreen` 實際畫出 `analytics_capped`，或反過來把 `CHANGELOG.md:16`、`docs/SCOPE.md:21`、`AnalyticsRepository.kt:18` 三處宣稱改成事實 — 前者遠優於後者
2. 補 `BackupService` 的 `committed` 旗標（M-1，兩行）
3. 補 `Period.custom` 的 JVM 測試（M-10，五行）
4. 發布前補齊 `CHANGELOG.md` 的版本區段與 `changelogs/2.txt`／`whatsnew-*` 的真實更新內容（M-9）
5. 修正 `docs/SCOPE.md:28` 的 dex 宣稱措辭（M-6）與 `AnalyticsViewModel.kt:81-82` 的節流註解（M-4）
6. 其餘 Minor 可排入下一輪
