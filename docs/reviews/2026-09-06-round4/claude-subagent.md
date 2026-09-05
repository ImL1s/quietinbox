# Round 4 獨立程式碼審查 — QuietInbox 發布前變更集

- **審查範圍**：`git diff 96b0cf9..1f7b182`（branch `main`，HEAD `1f7b182`），置於整個 repo 的脈絡中檢視
- **審查方式**：唯讀。已執行 `./gradlew test --rerun-tasks`（JVM only），未執行 instrumented test，未接觸任何裝置或模擬器
- **測試結果**：`BUILD SUCCESSFUL`，157 tests / 0 failures / 0 skipped；新增測試檔中無 `@Ignore`、`assertTrue(true)`、空測試

---

## Verdict：**REQUEST CHANGES**

理由（一句話）：本次 diff 在 `BackupService.apply` 把媒體加密移到交易之前，卻沒有把「已寫檔」與「已掛上訊息」分開記帳，導致**同機合併還原時 100% 的媒體檔案永久殘留在磁碟上，而且回報給使用者的「已還原 N 個媒體」是假數字** — 這同時是本 diff 引入的回歸、也直接牴觸產品硬規則「誠實的資料品質標籤」，且修法很小。其餘 Important 項目（release build 內含 demo 程式碼、`summaryOnlyCount` 超出期間、analytics 無上限載入）一併修掉後即可放行。

**核心 I1（lazy conversation）的改動我逐條追過，沒有找到缺陷**；問題不在最高風險的那一塊，而在備份還原與發布衛生。

---

## Critical

無。

---

## Important

### I-1. 還原備份會永久遺留加密媒體檔，且回報的媒體數是假的

**檔案**
- `platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:219-232`（交易前預先加密寫檔）
- 同檔 `:276-279`（重複訊息 `continue`）、`:270-273`（孤兒訊息 `continue`）
- 同檔 `:334`（`Counts(..., writtenFiles.size)`）
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/retention/RetentionWorker.kt:58-63`（回收只掃資料列）
- `core/designsystem/src/main/res/values/strings.xml:251`（`backup_result_ok` 把該數字顯示給使用者）

**問題**

交易前的預備迴圈對 `s.media` 中**每一筆**都解碼、加密、落檔，並無條件把檔名加進 `writtenFiles`：

```kotlin
for (media in s.media) {
    ...
    if (blobCipher.encryptToFile(bytes, mediaDir.file(name)) is KeyResult.Ok) {
        writtenFiles += name          // :228 — 此時還不知道這則訊息會不會被插入
        prepared[oldId] = Prepared(name, bytes.size.toLong())
    }
}
```

但交易內部有兩條路徑會在「掛上 blob 之前」就跳過該訊息：

```kotlin
if (remaining > 0) {                              // :276 重複訊息（去重命中）
    preExisting.getValue(cid)[dupKey] = remaining - 1
    continue
}
```
以及 `:270-273` 的 `skippedOrphans++ ; continue`。這兩條 `continue` 之後，`prepared[m.id]` 指向的檔案**永遠不會有對應的 `MediaBlobEntity`**。

而回收機制只掃資料列、不掃目錄：

```kotlin
val orphans = db.mediaDao().orphans()   // RetentionWorker.kt:58 — 來源是 media_blob 資料表
for (blob in orphans) { mediaDir.delete(blob.fileName) ... }
```

沒有任何一處會列舉 `MediaDirectory.dir` 去比對「檔案存在但沒有資料列」。因此這些檔案是**永久洩漏**：`MediaDirectory.totalBytes()` 會把它們算進「已使用空間」，但除了「刪除全部」之外沒有任何途徑清掉。

**為什麼嚴重**：合併還原最常見的情境就是「在同一支手機上還原自己的備份」。此時 `preExisting` 會對**每一則**訊息命中去重，於是**每一個** blob 都走 `:276` 的 `continue`，磁碟上留下全部媒體副本、資料庫裡一個都沒掛上。同時 `:334` 用 `writtenFiles.size` 當媒體計數，`backup_result_ok`（「完成：%1$d 個對話、%2$d 則訊息、**%3$d 個媒體**」）就會告訴使用者「還原了 N 個媒體」，實際是 0。這是本 diff 造成的回歸 — 改動前 `writtenFiles` 只在交易內、blob 真的掛上時才 append，計數是準的。

**修法**：把「回滾清單」和「實際掛上」分開記帳。

```kotlin
val linked = HashSet<String>()
// ... 交易內，成功 insert MediaBlobEntity 之後：
linked += blob.fileName
// ...
Counts(s.sources.size, convMap.size, inserted, restoredRevisions, linked.size)
```
交易成功回傳後，再刪掉沒被採用的檔案：
```kotlin
for (f in writtenFiles) if (f !in linked) mediaDir.delete(f)
```
`writtenFiles` 維持原樣供 `catch` 區塊整批回滾使用（`:340`）。

---

### I-2. 預備迴圈與 `try` 之間有一段沒有清理保護的區間

**檔案**：`platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:234-236`，對照 `:338-339` 的註解

**問題**：`:340` 的清理只在 `try` 內拋例外時才會執行，但預備迴圈（`:219-232`）在 `try` **之外**，而且兩者之間還夾了一個 suspend 呼叫：

```kotlin
}                                              // :233 預備迴圈結束，檔案已落磁碟
val now = System.currentTimeMillis()           // :234
val retentionMs = settings.current().retentionDays * ...   // :235 ← suspend，可拋例外／可被取消
return try {                                   // :236 清理保護從這裡才開始
```

若 `settings.current()` 拋例外，或協程正好在此處被取消（使用者離開設定頁），**所有已寫入的檔案都不會被清掉**。`:338-339` 的註解寫著「blobs written outside it are removed on every failure, cancellation included」，這句話在目前的排列下是不成立的。

**修法**：把 `val now` / `val retentionMs` 移到預備迴圈**之前**，或直接把預備迴圈搬進 `try` 區塊內（後者較穩妥，兩個問題一次解決）。

---

### I-3. `summaryOnlyCount` 沒有期間上界，誠實標籤被灌水

**檔案**
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:361-362`
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/AnalyticsRepository.kt:33`
- `feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt`（`compute()` 內 `analytics.summaryCountSince(period.startEpochMs)`）

**問題**：查詢只有下界。

```kotlin
@Query("SELECT COUNT(*) FROM summary_observation WHERE observedAtEpochMs >= :since")
suspend fun summaryCountSince(since: Long): Int
```

`ActivityReport.summaryOnlyCount` 是 UI 用來說明「有多少觀測只是摘要、沒有內文」的排除量。對 `LAST_7_DAYS`／`THIS_MONTH`／`ALL` 影響不大（期間本來就到今天），但對 **`LAST_MONTH` 和任何過去的 `CUSTOM` 區間**，它會把期間**結束之後**累積的所有摘要觀測都算進去。使用者拿「上個月」跟「這個月」對比時，看到的排除數字是錯的 — 而這正是誠實標籤最需要準確的地方。

**修法**：加上界並傳入期間結束時間。

```kotlin
@Query("SELECT COUNT(*) FROM summary_observation WHERE observedAtEpochMs >= :since AND observedAtEpochMs <= :until")
suspend fun summaryCountBetween(since: Long, until: Long): Int
```
`AnalyticsViewModel` 改呼叫 `analytics.summaryCountBetween(period.startEpochMs, period.endEpochMsInclusive)`。

---

### I-4. `DemoDataRepository` / `DemoDao` 會進入 release build

**檔案**
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/DemoDataRepository.kt`（801 行，位於 **main** source set）
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/QuietInboxDatabase.kt:48-49`（`abstract fun demoDao()`）
- `feature/settings/src/main/kotlin/dev/quietinbox/feature/settings/SettingsViewModel.kt:59`（建構子參數）
- `app/proguard-rules.pro`（只 keep SQLCipher）

**問題**：`DemoReceiver` 與 debug manifest 確實只在 `app/src/debug/**`，UI 也用 `BuildInfo.debug` 擋住了 — 這兩層我確認無誤。但**資料層本身在 main source set**，而且是 release 模組 `SettingsViewModel` 的建構子參數，Hilt 會為 release build 產生引用它的 factory。`BuildInfo.debug` 是透過 `@Provides` 在**執行期**注入的（`AppModule.provideBuildInfo()`），R8 無法把 `if (state.developerTools)` 證明為死碼，因此整個 801 行 demo 資料集與 `DemoDao`（含 `DELETE FROM conversation WHERE packageName LIKE …` 這類語句，經由 Room 產生的 `QuietInboxDatabase_Impl` 保留）都會留在 release 產物裡。

實際可觸發性：不能。release 沒有 receiver，Settings 也不顯示該區塊。但 brief 的硬規則是「nothing in a release build may expose demo/debug hooks」，`DemoDataRepository` 的 KDoc 也寫著「Debug affordance only」— 以產物而論這句話不精確，同時白白增加 APK 體積與攻擊面。

**修法**：把 `DemoDataRepository`、`DemoDao` 移到 `platform/storage` 的 `debug` source set（或獨立成一個以 `debugImplementation` 引入的模組），並在 debug-only 的 Hilt module 提供繫結；`SettingsViewModel` 改注入 `Optional<DemoDataRepository>` 或可為 null 的型別，release 端繫結為 `Optional.empty()`。這樣 release 位元組碼裡不會有任何引用。

---

### I-5. Analytics 每次期間切換都把整段期間的訊息全文載入記憶體，且每收一則通知就整批重算

**檔案**
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:219-226`（`statsBetween` 無 `LIMIT`）
- `platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/AnalyticsRepository.kt:16-28`
- `feature/analytics/src/main/kotlin/dev/quietinbox/feature/analytics/AnalyticsViewModel.kt`（`state` 的 `combine`、`compute()`）

**問題**：兩件事疊在一起。

其一，載入量無上限。`statsBetween` 沒有 `LIMIT`，`messagesBetween` 把每一列（**含完整 `body`**）轉成 `ObservedMessage` 全部留在記憶體。選 `PeriodKind.ALL` 時期間可達 `MAX_SPAN_DAYS = 3660` 天，等於把整個 vault 的訊息內文一次拉進 heap。大量使用者（數十萬則）會直接 OOM。

其二，重算沒有節流。

```kotlin
combine(selection, inbox.observeCounts().catch { }) { s, _ -> s }.map { s -> compute(s) }
```

`observeCounts()` 是 Room 對 `message` 表的 Flow，**每插入一則訊息就會發射**。只要 Analytics 畫面開著且選了「全部」，每來一則通知就會重跑一次完整的 `messagesBetween` + `catchphrases`（對每則 body 做 CJK n-gram 掃描）+ `emojiRanking`。沒有 `debounce`，也沒有對計數做 `distinctUntilChanged`。雖然跑在 `Dispatchers.Default` 不卡 UI 執行緒，但在收訊高峰會持續燒 CPU 與電力 — 對一個以「安靜、省電」為賣點的產品尤其不合適。

**修法**（兩者都要）：
1. 給 `statsBetween` 加上界並在超過時降級：`LIMIT :cap`（例如 200_000），回傳列數達到 cap 時在 UI 標示「僅統計最近 N 則」，維持誠實標籤原則；或改為「`ALL` 期間走 SQL 聚合」而非全量載入。
2. 在 `combine` 之後加 `.debounce(500)`，並把 `observeCounts()` 換成只取影響統計的欄位再 `distinctUntilChanged()`。

---

## Minor

### M-1. `isSelf` 以顯示名稱比對，同名會誤判
`platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/SnapshotFactory.kt:163`

```kotlin
isSelf = person == null || (selfName != null && person.name?.toString() == selfName)
```

`person == null` 判定為自己符合 MessagingStyle 語義，正確。但用顯示名稱比對很脆弱：群組裡若有參與者與 `MessagingStyle.user` 同名（或雙方名稱都是空字串），該參與者的訊息會被標成自己，於是從 `topSenders` 與 `catchphrases` 中整個消失（兩者都 `filter { !it.isSelf }`）。

**修法**：優先比 `Person.key`，兩邊皆非 null 時只用 key 判斷；退回名稱比對時排除空白字串。

```kotlin
val selfKey = messaging?.user?.key
isSelf = person == null ||
    (selfKey != null && person.key != null && person.key == selfKey) ||
    (selfKey == null && !selfName.isNullOrBlank() && person.name?.toString() == selfName)
```

### M-2. `Period.custom` 沒有跨度上限
`core/analytics/src/main/kotlin/dev/quietinbox/core/analytics/Insights.kt:96-98`

`all()` 有 `MAX_SPAN_DAYS = 3660` 的箝制，`custom()` 沒有。使用者若在日期選擇器挑了一個極長區間，`Period.days()` 會建出對應長度的 `List<LocalDate>`，`quietRate` 再為每個對話跑 `for (i in 0 until total)`。若 UI 的日期選擇器沒有自行設限，這是一個可從介面觸發的資源放大。

**修法**：`custom()` 內把起始日 `coerceAtLeast(endInclusive.minus(DatePeriod(days = MAX_SPAN_DAYS)))`，與 `all()` 一致。

### M-3. 本次最高風險的改動沒有任何新測試
`platform/storage/.../IngestRepository.kt` 的 lazy conversation 是 brief 列為第一順位的改動，但全 repo 搜尋 `conversationIdOrCreate`／「已刪除對話重播」只命中實作檔本身，`VaultRoundTripTest` 在本 diff 未被修改。我理解 Room + SQLCipher 的原生程式庫無法在 JVM 上跑（`DemoDataTest` 的註解也說明了這點），所以測試只能放在 `androidTest`；但目前連 instrumented 測試也沒有涵蓋「刪除對話 → 重播 → 不得復活成空列」這個情境。

**修法**：在 `VaultRoundTripTest` 增一個案例：commit 建立對話 → `deleteConversation` → 用同一份 snapshot/reconcile 重播 → 斷言 `findConversationId()` 仍為 null 且 `CommitOutcome.conversationId == null`。

### M-4. `DemoDataTest` 缺少「非 demo 資料存活」的反向斷言
`platform/storage/src/androidTest/kotlin/dev/quietinbox/platform/storage/DemoDataTest.kt`

兩個測試都只驗證「demo 資料被清乾淨」（各種 `count…(packages) shouldBe 0`），沒有任何一個先寫入一筆**非** demo 的對話／訊息／來源，再呼叫 `clear()` 並斷言它還在。`clear()` 的核心承諾正是「只刪 demo 列」，這一半沒有測到。（此為 instrumented 測試，依 brief 規定我未執行。）

**修法**：在 `seedsEveryScreenThenClearsCompletely` 中先插入一筆 `packageName = "com.example.real"` 的對話與訊息，`clear()` 後斷言其仍存在。

### M-5. `offerCaptured` 是 `internal`，對整個模組可見
`platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:317`

目前只有測試呼叫（已確認生產程式碼無呼叫端），准入規則也與 `offer` 一致、commit fence 未被削弱（見下方驗證章節）。但 `internal` 讓整個 `platform:capture` 模組都能繞過 `isCapturable(sbn)` 的 `EXTRA_SYNTHETIC` 檢查而直接送入佇列。

**修法**：加上 `@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)` 標註意圖，讓未來的模組內誤用會被 lint 攔下。

### M-6. 第三方 GitHub Action 以可變 tag 釘選，同時持有 Play 服務帳號金鑰
`.github/workflows/release.yml`

`r0adkll/upload-google-play@v1` 拿到 `secrets.PLAY_SERVICE_ACCOUNT_JSON`，卻是用可變的 `@v1` tag 釘選；`actions/checkout@v4` 等官方 action 同理。上游 tag 被移動即可取得發布金鑰。

**修法**：全部改為 commit SHA 釘選（`uses: r0adkll/upload-google-play@<40-char-sha> # v1.1.3`），並考慮把 `permissions: contents: write` 從 workflow 層下放到只有 `github-release` job 需要它。

### M-7. `keystore.properties` 缺欄位時會在 configuration 階段 NPE
`app/build.gradle.kts:23`

```kotlin
storeFile = file(keystoreProps.getProperty("storeFile"))
```

`getProperty` 找不到 key 時回傳 null，`file(null)` 會拋出訊息難以理解的例外，而且是在 Gradle configuration 階段炸掉整個建置。env 分支同理（`QUIETINBOX_KEYSTORE_PASSWORD` 未設時 `storePassword = null`，錯誤要到簽章階段才浮現）。

**修法**：讀取後檢查並給出明確訊息，例如 `requireNotNull(keystoreProps.getProperty("storeFile")) { "keystore.properties 缺少 storeFile" }`。

### M-8. `MediaCopier.copyPending` 的協程扇出未受限
`platform/media/src/main/kotlin/dev/quietinbox/platform/media/MediaCopier.kt:46-62`

`messageIds.map { async { ... } }` 會為每個 id 各開一個協程，`db.messageDao().get(id)` 在取得 `parallelism` 許可**之前**執行，因此資料庫讀取不受號誌限制。另外多個訊息共用同一張 `bitmap` 時會有多個協程並行呼叫 `copyBitmap(id, bitmap)` → `Bitmap.compress`，而 `Bitmap` 並未保證執行緒安全。

**修法**：把 `parallelism.withPermit { }` 移到 `async` 內最外層（包住 `get`），或改用 `messageIds.chunked(n)` 限制扇出；bitmap 路徑則先壓縮一次成 `ByteArray` 再分發給各訊息。

### M-9. 交易提交後才被取消，`catch` 會刪掉剛掛上的媒體檔
`platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:336-341`（1f7b182 行號 `:336-343`）

`catch` 區塊無條件刪除 `writtenFiles` 的全部內容，而 `writtenFiles` 包含**已成功掛到訊息上**的 blob：

```kotlin
} catch (e: Exception) {
    for (f in writtenFiles) mediaDir.delete(f)   // 不分「已掛上」與「未採用」
    if (e is CancellationException) throw e
```

若 `db.withTransaction { … }` 已經**提交成功**，但協程在其回傳的暫停點被取消（使用者離開設定頁、ViewModel scope 結束），`CancellationException` 會進到 `catch`，把剛剛掛上的媒體檔全部刪掉後才往上拋。資料庫的 `MediaBlobEntity` 資料列已經提交、不會回滾，於是留下一批指向不存在檔案的資料列 — 媒體永遠顯示不出來，而 `RetentionService.orphans()` 也清不掉它們（那些資料列的 `messageId` 是有效的，不算孤兒）。

視窗很窄，且此行為在 `96b0cf9` 與 `1f7b182` 皆存在、非本 diff 新增，故列為 Minor。但它與 I-1 的修法直接相關：**回滾清單必須只在交易未提交時才整批刪除**。

**修法**：用一個 `committed` 旗標區分兩種情境。

```kotlin
var committed = false
return try {
    val counts = db.withTransaction { ... }
    committed = true
    ...
} catch (e: Exception) {
    if (!committed) for (f in writtenFiles) mediaDir.delete(f)
    else for (f in writtenFiles) if (f !in usedFiles) mediaDir.delete(f)
    ...
}
```

（附記：我原本懷疑「清理迴圈本身拋例外會觸發 `catch` 誤刪」，實際查證後**不成立** — `MediaDirectory.delete` 是 `runCatching { File(dir, name).delete() }`，`RetentionWorker.kt:109-111`，吞掉所有例外，該迴圈不可能拋出。真正的風險路徑只有上述的「提交後取消」。）

---

## 驗證通過（已檢查並確認無問題）

**I1 — `IngestRepository.commit` 的 lazy conversation**（`IngestRepository.kt:169-192, 197-206, 247, 341-343, 362`）
逐條追過所有原本使用 eager id 的路徑，全部正確：
- `preExisting` 改用 `ownerId = existing?.id` 並加上 `ownerId != null` 前置條件（`:197-199`）。新對話沒有既有訊息，回傳空 map 是正確語義；lazy 建立發生在迴圈中也不會污染這個先前計算的結果。
- `Decision.Known`（`:307`）與 `Decision.Revision`（`:319`）都**不**呼叫 `conversationIdOrCreate()`，且都以 `db.messageDao().get(id) != null` 先驗證，避免 FK 違規回滾整批。對話被刪除時訊息會隨 `ForeignKey.CASCADE`（`Entities.kt:109`）一併消失，因此這些守衛必然生效。
- 投影改為 `conversationId?.let { convDao.get(it) }`（`:341`），null 時整段跳過，不會 NPE。
- `CommitOutcome(conversationId, …)`（`:362`）讀的是被區域 `suspend fun` 捕獲的 `var`，能正確反映延遲建立出來的 id。
- 刪除路徑（`InboxRepository.kt:86-96`）會為該對話每一則訊息寫入 suppression token，所以重播時全部走 `suppressed++` 分支、一列都不插入 → 對話不會復活。這正是 I1 想要的行為。

**I2 — 到期時間重新定錨不會造成資料遺失**
`expiresAtEpochMs = m.expiresAtEpochMs?.let { maxOf(it, now + retentionMs) }`（`BackupService.kt:296-298`）。`SettingsRepository.setRetentionDays` 以 `coerceIn(1, 3650)` 箝制、預設 30（`SettingsRepository.kt:26, 93`），不存在「0 代表永久」的哨兵值，因此 `retentionMs` 至少一天，不會出現「還原完立刻過期」。原本 `expiresAtEpochMs == null`（備份時未設保留期）會維持 null，永不過期，正確。

**`offerCaptured` 沒有削弱回呼執行緒規則或 commit fence**
`CaptureCoordinator.kt:317-330` 的准入規則與 `offer`（`:289-298`）逐條對應：自家 package 必須是 `SYNTHETIC`，其他 package 僅在 `sourcesLoaded` 後才過濾。真正的 fence 在 `process()`（`:353-359`）完全未被本 diff 修改，仍會重新檢查 `paused`、`generation != activeGeneration` 與 `stillCapturable`。生產程式碼無呼叫端（僅 `CaptureCoordinatorTest` 使用）。

**`BackupStager` 與原本的內嵌 `stage()` 語義完全相同**
逐行比對 `BackupStager.kt` 與 diff 中被刪除的 `BackupService.stage()`／`readBoundedLine()`：邏輯一字不差，唯一差異是四個上限改為建構子參數並預設為 `BackupLimits` 常數。manifest 必須最先、重複 manifest、end 之後有資料、兩組計數檢查、`schemaVersion > VERSION` 全部保留。

**無網路權限**
`app/src/main/AndroidManifest.xml:7` 以 `tools:node="remove"` 移除 INTERNET；實際檢查 `app/build/intermediates/merged_manifest/{release,debug}/…/AndroidManifest.xml`，兩者的 `uses-permission` 清單皆無 INTERNET／網路類權限。CI 另有 `tools/check-permissions.sh` 對 release APK 做 aapt2 gate（含 `QUERY_ALL_PACKAGES`），在 `release.yml` 的 build job 中執行。

**無機密外洩**
`git ls-files` 未追蹤任何 `.jks`／`.keystore`／`keystore.properties`；`.gitignore:16-17` 已涵蓋。`docs/RELEASE.md` 公布的是簽章憑證 SHA-256 指紋（本來就可從任何 APK 讀出），非機密。CI 的 keystore 寫在 `$RUNNER_TEMP`，由 runner 逐 job 清除。

**依賴驗證中繼資料**
`gradle/verification-metadata.xml:6-9` 的信任範圍限縮在 `group="com.android.tools.build" name="aapt2"` 且 `regex="false"`，沒有寬鬆的萬用字元信任。

**Demo 標籤的作用域正確**
`PACKAGE_LIKE = "demo.quietinbox.%"`、`GENERATION_LIKE = "demo-%"`（`DemoDataRepository.kt:412, 416`），皆不含 SQL `LIKE` 的單字元萬用字元 `_`。`deleteSuppression` 比對的 `scopeKey` 由 `SourceScope.key`（`SourceScope.kt:21-24`，格式為 `packageName|profileKey[|accountKey]`）加上 `#identityKey` 組成，開頭必為 package name，故前綴比對確實限定在 demo 範圍內。

**Analytics 期間過濾一致**
`heatmap`／`bestTime`／`chattiness`／`catchphrases`／`emojiRanking` 都不接受 `Period` 參數，但 `AnalyticsViewModel.compute()` 只載入 `messagesBetween(period.start, period.endInclusive)`，因此所有函式看到的都是已按期間過濾的同一份資料，與 `rankings`／`quietRate` 的結果一致，不會出現「某些分頁忽略期間」的錯亂。

**誠實標籤齊備（雙語）**
`strings_analytics.xml`（`values/` 與 `values-b+zh+Hant/`）五個分頁都有「Observed messages only／僅計算觀測到的訊息」副標；quiet 分頁明確寫出「a quiet day may just mean nothing was captured／沒有訊息也可能只是沒被捕捉到」與完整公式說明（含「包含對話第一次出現之前的日子」），符合 `quietRate` KDoc 要求的「說期間，不說 ghosted」。還原會停用來源一事也已在 `strings.xml:249`（`backup_import_desc`）向使用者揭露。

**商店文案在 Play 限制內且與程式碼相符**

| 檔案 | 字元數 | Play 上限 |
| --- | --- | --- |
| `title.txt`（en-US／zh-TW） | 13 | 30 |
| `short_description.txt`（en-US） | 78 | 80 |
| `short_description.txt`（zh-TW） | 36 | 80 |
| `full_description.txt`（en-US） | 2449 | 4000 |
| `full_description.txt`（zh-TW） | 941 | 4000 |
| `changelogs/1.txt`（en-US） | 141 | 500 |
| `changelogs/1.txt`（zh-TW） | 39 | 500 |

`changelogs/1.txt` 的檔名與 `app/build.gradle.kts:48` 的 `versionCode = 1` 相符。文案四項承諾（無 INTERNET、不觸碰原通知、只能擷取通知內容、SQLCipher AES-256）逐條對得上程式碼；「activity insights (heat map, rankings, best time, quiet days, emoji and catchphrases)」與實作的五個分頁一致，未誇大。

**歷史審查文件只做路徑去識別**
`git diff` 顯示 `docs/reviews/2026-09-06-round{1,2,3}/` 的變更全部是把 `file:///Users/iml1s/…` 絕對路徑換成相對路徑，沒有任何結論或嚴重度被改寫 — 對公開 repo 而言這是正確的處理。

**`tools/demo-screenshots.sh`**
裝置序號是必填的第一個位置參數（`:16, :20`），沒有硬編碼任何實體裝置序號。`set -euo pipefail` 齊備，唯一的 `rm -rf` 是 `trap 'rm -rf "$WORK_DIR"' EXIT`，作用於自建的暫存目錄。

**新增相依皆為測試範圍**
`gradle/libs.versions.toml` 新增 `mockk 1.14.11`；`platform/backup` 與 `platform/capture` 的 `build.gradle.kts` 只新增 `testImplementation`，沒有任何新的 runtime 相依進入產品程式碼。

---

## 其他觀察（非缺陷）

- `PhraseScanner.emitGrams` 對長度 N 的 CJK 連續段會產生約 `2N` 個 n-gram（size 2 與 3 各一輪）。目前受訊息長度上限保護，但若日後放寬 `Limits`，`catchphrases` 的成本是二次成長的來源之一，值得留意。
- `RetentionService.runOnce` 的 `emptyOlderThan(now - 7 天)` 原本是「空對話」的兜底清理。I1 改為延遲建立後，這條路徑的觸發機會大幅降低，但仍應保留 — 因為刪光訊息（而非刪對話）仍會留下空對話列。
- `release.yml` 的 `github-release` job 用 `awk` 從 `CHANGELOG.md` 抓 `## [ver]` 區段。目前 CHANGELOG 頂端是 `## [Unreleased]`，若打 tag 時忘了把它改成版本號，發布說明會靜默退回 `"QuietInbox $TAG — see CHANGELOG.md"`。`docs/RELEASE.md` 的步驟 1 已要求先補版本區段，屬流程約束而非程式缺陷。
- `check-permissions.sh` 只驗 APK，未驗上傳到 Play 的 AAB。兩者來自同一份合併 manifest，實務上等價，但若要做到滴水不漏可一併檢查 AAB 的 `base/manifest/AndroidManifest.xml`。

---

## 附錄：工作目錄在本次審查期間的變動（非審查標的）

本報告的審查標的是 **commit `1f7b182`**，上述所有 file:line 與結論都以該 commit 為準。撰寫期間有另一條工作流在**未提交的工作目錄**中同步修改了部分檔案（`git status` 顯示 6 個 source 檔與 6 個 docs／fastlane 檔為 modified）。為避免報告一落地就過時，以下是我對這些 in-flight 變更的核對結果 — **這些變更尚未提交、不在本次審查標的內，仍需獨立複審**。

| 本報告發現 | 未提交工作目錄的狀態 |
| --- | --- |
| **I-1** 媒體檔洩漏＋假計數 | **已處理**。新增 `usedFiles: HashSet<String>`，在 blob 真正掛上訊息時記錄；`Counts` 改用 `usedFiles.size`；交易成功後執行 `for (f in writtenFiles) if (f !in usedFiles) mediaDir.delete(f)`。方向與我建議的修法一致。**但清理迴圈被放在 `try` 之內、`catch` 仍無條件刪除整份 `writtenFiles`，因此 M-9 的「提交後取消」視窗依然存在，且現在後果更明顯（已掛上的檔案會被誤刪）。請一併採用 M-9 的 `committed` 旗標修法。** |
| **I-2** 預備迴圈與 `try` 之間的清理空窗 | **仍未處理**。`BackupService.kt:235-237` 的 `val now` 與 `val retentionMs = settings.current()…` 依然在 `return try` **之外**；`settings.current()` 是 suspend 呼叫，一旦拋例外或被取消，所有已落檔的 blob 仍然不會被清除，`:338-339` 的註解依舊名實不符。**請一併修掉**（把這兩行移到預備迴圈之前，或把預備迴圈搬進 `try`）。 |
| **I-3** `summaryOnlyCount` 無上界 | **已處理**。`Daos.kt:365` 新增 `summaryCountBetween(since, until)`，`AnalyticsRepository.kt:38` 轉呼叫，`AnalyticsViewModel.kt:102` 已改用 `summaryCountBetween(period.startEpochMs, period.endEpochMsInclusive)`。註：舊的 `summaryCountSince` 仍保留給 `HealthRepository.kt:69` 使用，該處語義上本來就是「自某時間以來」，無誤。 |
| **M-1** `isSelf` 以名稱比對 | **已處理**，且比我建議的更完整：改為 key → uri → name 的優先序（`SnapshotFactory.kt:163-168`），只有雙方都沒有 key 也沒有 uri 時才退回名稱比對。 |
| **I-4** demo 程式碼進入 release build | 未見對應變更。 |
| **I-5** analytics 無上限載入／無節流 | 未見對應變更（`statsBetween` 仍無 `LIMIT`，`combine` 仍無 `debounce`）。 |
| **M-2 ~ M-8** | 未見對應變更。 |

我未執行、也未被授權執行這些未提交變更的驗證（唯讀審查）。上表僅為狀態核對，不構成對這些變更的核可。
