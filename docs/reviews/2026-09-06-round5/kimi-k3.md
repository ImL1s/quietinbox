## Verdict:**APPROVE WITH MINOR FIXES**

Round-4 三份報告的 Critical/Important 項目幾乎全部正確落地，且我以實際建置與網路查證補強了「release 產物無 demo 程式碼」與「SHA 釘選真實性」兩項關鍵宣稱。唯一未完成的，是 I-5 修法中「觸頂時在 UI 標示」這一條腿：`capped` 旗標與雙語字串都建好了，但 **`AnalyticsScreen.kt` 從未讀取它**，而 CHANGELOG 與 SCOPE.md 已宣稱「the UI says so when capped」— 文件先於實作，違反本專案的誠實標籤鐵律。修法是一行 Composable，補上即可放行。

---

## Round-4 修復驗證表

| Round-4 發現 | 狀態 | 驗證位置 |
| --- | --- | --- |
| subagent **I-1** 還原媒體檔洩漏＋假計數 | ✅ 已修復 | `BackupService.kt:215`(`usedFiles`)、`:304`（掛上時記錄）、`:337`(`Counts(..., usedFiles.size)`)、`:339-340`（交易成功後刪除未採用 blob)。`Counts` 欄位順序與 `BackupRecords.kt:105-111` 定義相符。同機合併還原情境下重複訊息的 blob 現在會被刪除，計數只算實際掛上的。 |
| subagent **I-2** 預備迴圈與 `try` 之間的清理空窗 | ✅ 已修復 | `BackupService.kt:220-221`(`now`/`retentionMs` 移到任何落檔之前）、`:222-238`（預備迴圈整段搬入 `try`)。`settings.current()` 拋例外或被取消時，磁碟上尚無任何檔案，無洩漏可能。 |
| subagent **I-3** `summaryOnlyCount` 無期間上界 | ✅ 已修復 | `Daos.kt:366-367`(`summaryCountBetween`)、`AnalyticsRepository.kt:42`、`AnalyticsViewModel.kt:110`（傳入 `endEpochMsInclusive`)。舊 `summaryCountSince` 保留給 `HealthRepository.kt:69`，該處語義本來就是「自某時以來」，正確。 |
| subagent **I-4** demo 程式碼進入 release build | ✅ 已修復 | `DemoData` 介面 + `NoDemoData` 在 main source set(`repo/DemoData.kt`);`DemoDataRepository` 移至 `platform/storage/src/debug/`;debug/release 各有自己的 `di/DemoModule.kt`（變體互斥，無 duplicate-binding 風險）。我實際跑了 `:app:assembleRelease`:R8 後的 `classes.dex` 中無 `demo.quietinbox`、無 `DemoDataRepository`、無任何示範文案（僅存 `SyntheticNotifications.kt:90` 一句合法的 onboarding 自測說明與 `SettingsUiState` 的 toString 殘片）。`DemoDao` 留在 main 是有意為之且已在 `Daos.kt:393-397` 註解說明（Room DAO 無法按變體拆分，且不含 demo 內容）。 |
| subagent **I-5** analytics 無上限載入／無節流 | ⚠️ **部分修復** | 上限：`Daos.kt:224-228`(`ORDER BY sortKey DESC LIMIT :limit`)+ `AnalyticsRepository.kt:17-21`(`.asReversed()` 還原為由舊到新，方向正確）、`:46-48`(`MESSAGE_CAP = 50_000`)。節流：`AnalyticsViewModel.kt:84-86`(`distinctUntilChanged()` 作用於 data class `InboxCounts` ✓;`debounce(400)` 為 trailing,400ms 後必重算，首次發射只晚 400ms，可接受）。**但未完成的半邊：觸頂標示未接上 UI，詳見下方新發現 N-1。** |
| subagent **M-1** `isSelf` 名稱比對誤判 | ✅ 已修復（優於建議） | `SnapshotFactory.kt:163-168`:key → uri → name 三級優先序。逐情境列舉：`person == null` → true(MessagingStyle 語義）;**任一邊有 key 就由 key 決定**，單邊有 key → false（真實聯絡人不可能被誤標）；雙方無 key 但任一方有 uri → 由 uri 決定；皆無 → 退回名稱。殘留邊界見 N-3。 |
| subagent **M-2** `Period.custom` 無跨度上限 | ✅ 已修復 | `Insights.kt:94-99`:`maxOf(from, to - MAX_SPAN_DAYS)`，與 `all()` 的 `:85` 一致；`maxOf` 對 kotlinx-datetime `LocalDate`(Comparable）成立。 |
| subagent **M-3** 刪除對話重播無測試 | ✅ 已修復 | `VaultRoundTripTest.kt:159-180`：建立 → `deleteConversation` → 斷言 `findConversationId == null` → 同內容重播 → 斷言 `conversationId == null`、`newMessageIds` 空、對話數為 0。所用 API(`commit`、`checkpoint`、`s.copy(eventId=)`）與同檔既有測試一致。無法執行（instrumented)，但逐行核對無誤。 |
| subagent **M-4** `clear()` 缺非 demo 存活斷言 | ✅ 已修復 | `DemoDataTest.kt:73-78`（先插入 `com.example.real` 來源）、`:113`(`clear()` 後 `shouldNotBe null`)。 |
| subagent **M-5** `offerCaptured` 可見性 | ✅ 已修復 | `CaptureCoordinator.kt:317` 加上 `@VisibleForTesting(otherwise = PRIVATE)`。 |
| subagent **M-6** workflow 未釘 SHA | ✅ 已修復並查證 | 7 個 action 全部改為 40 碼 SHA + 版本註解；我逐一與上游 repo 核對：checkout=11d5960(v4.4.0)、setup-java=cf277c6(v4.9.1)、setup-gradle=748248d(v4.4.4,annotated deref)、upload-artifact=ea165f8(v4.6.2)、download-artifact=d3f86a1(v4.3.0)、android-emulator-runner=a421e43(v2.38.0,annotated deref)、upload-google-play=e738b9d(v1.1.5),**全部相符**。`permissions` 亦從 workflow 層 `contents: write` 收窄為 `read`,`write` 只留給 `github-release` job(`release.yml:21`、`:75-76`)。 |
| subagent **M-7** keystore 缺欄位 NPE | ✅ 已修復 | `app/build.gradle.kts:25`、`:31` 皆改為 `requireNotNull` 並附明確訊息。 |
| subagent **M-8** `MediaCopier` 扇出與 bitmap | ✅ 已修復 | `MediaCopier.kt:54-55`(permit 包住 `get` 全程）、`:47-50`(bitmap 只壓縮一次）、`:104-108`（共享不可變 bytes，壓縮失敗歸為 FAILED)。 |
| subagent **M-9** 提交後取消誤刪已掛上媒體 | ❌ **未修復（commit 亦未宣稱修復）** | `BackupService.kt:342-348` 的 `catch` 仍無條件刪除整份 `writtenFiles`。詳見 N-2。 |
| agy **Important-1**(= I-3 期間上界） | ✅ 已修復 | 同上 I-3 列。 |
| agy **Important-2**（中英文件數據同步） | ✅ 已修復 | `docs/zh-Hant/TEST_MATRIX.md` 改為 72 個測試（analytics 32)，與英文版及實測一致。 |
| agy Minor 1/4、Kimi Minor（註解截斷、商店文案） | ✅ 已修復 | `SnapshotFactory.kt:62-64` 註解已併回一段；商店文案「7–365 天」→「1–365 天」(en-US/zh-TW `full_description.txt`)，與 `SettingsScreen.kt:154` 的滑桿 `1f..365f` 一致。 |
| Kimi 待確認事項：`keystore.properties` 是否入庫 | ✅ 已澄清 | `git ls-files` 無追蹤；`.gitignore:16-17` 涵蓋。根目錄的實體檔是本機簽章用，未進 git。 |
| `versionCode = 2` + changelog 檔名 | ✅ 一致 | `app/build.gradle.kts:50`;fastlane `changelogs/{1.txt → 2.txt}`(en-US/zh-TW）同步更名。 |

---

## 新發現

### Critical

無。

### Important

**N-1. `capped` 觸頂標示沒有接上 UI，但文件已宣稱它存在**

- `feature/analytics/.../AnalyticsViewModel.kt:64`、`:145`（旗標已計算）
- `core/designsystem/.../values/strings_analytics.xml:76` 與 `values-b+zh+Hant/...:75`(`analytics_capped` 雙語字串已就位）
- `feature/analytics/.../AnalyticsScreen.kt` — **全檔無任何對 `capped` 或 `analytics_capped` 的參照**(grep 全庫僅命中 ViewModel 與字串定義）

I-5 的原始修法（round-4 subagent 提出、三方共識）包含「達到 cap 時在 UI 標示『僅統計最近 N 則』」。現在資料層的截斷是真的、旗標是真的、字串是真的，唯獨使用者永遠看不到。期間內訊息超過 50,000 則的使用者（`ALL` 期間的重度使用者並非不可能）會看到以子集計算的排行與神隱率，且無任何提示 — 而 `CHANGELOG.md:16` 與 `docs/SCOPE.md:21` 已白紙黑字宣稱「the UI says so when capped」。文件先於實作，正是誠實標籤鐵律要防的狀況。修法：在 `AnalyticsScreen` 於 `state.capped` 時渲染 `analytics_capped`(`MessageCap` 作為 `%1$d` 參數），或撤回兩處文件宣稱；前者才是原案的承諾。

### Minor

**N-2.(round-4 M-9 留存）交易提交後被取消，`catch` 會刪掉剛掛上的媒體檔** — `BackupService.kt:342-348`。`withTransaction` 提交成功後、協程在其回傳暫停點被取消時，`catch` 仍無條件刪除全部 `writtenFiles`（含已掛上的），留下指向不存在檔案的 `MediaBlobEntity`。此問題非本 diff 引入、commit 也未宣稱修復；但 I-1 引入 `usedFiles` 後修法已變得一行可及：`catch` 內改刪 `writtenFiles - usedFiles` 在「已提交」情境下即為正確（未提交時 `usedFiles` 對應列已回滾，刪除亦正確），或採 M-9 原議的 `committed` 旗標。視窗很窄，維持 Minor。

**N-3. `isSelf` 名稱退回未排除空白字串** — `SnapshotFactory.kt:167`。`selfName == ""` 且群組成員 `person.name == ""` 時仍會誤標為自己。M-1 的建議含 `isNullOrBlank` 守衛，實作未採納。實務上來源 app 極少讓 user 名稱為空字串（null 則已被 `selfName != null` 擋下），殘留風險極低。另補一個方向安全的邊界：若 app 給 user 設了 key、卻用無 key 的 `Person` 表示自己發出的訊息，該訊息會被判為非自己（優先序讓 key「一票否決」)— 結果不會冤枉聯絡人，只會讓自己的訊息混入 `topSenders`，屬可接受的保守方向。

**N-4. 文件小瑕疵（不影響行為）**:
- `SnapshotFactory.kt:62-64` 註解說「雙方都沒有 key 才退回名稱比對」，實際條件是「雙方都沒有 key **也沒有 uri**」。
- `AnalyticsViewModel.kt:82-83` 註解稱「never more than twice a second」，`debounce(400)` 的頻率上限其實是 2.5 次/秒。
- `BackupService.kt:224-238` 預備迴圈搬入 `try` 後未重排縮排（純外觀）。
- `capped = messages.size >= MESSAGE_CAP`(`AnalyticsViewModel.kt:145`）在恰好 50,000 則時會過度申報觸頂 — 方向是誠實的（多報不少報），知悉即可。`statsBetween` 的 `LIMIT` 在 `sortKey` 相同的多列間切齊時不具決定性，只影響剛好壓線的那幾則，可忽略。

---

## 驗證通過（本次複審額外確認、無問題）

- **release 產物隔離（實測）**:`:app:assembleRelease` 成功；`classes.dex` 內 `demo.quietinbox`／`DemoDataRepository` 零命中；release `DemoModule` 以 `@Provides` 綁 `NoDemoData`,debug 以 `@Binds` 綁實作，兩個 `DemoModule` 分屬互斥變體 source set，無重複綁定。`DemoReceiver` 改走 `DemoData` 介面（`DemoReceiver.kt:37`),`SettingsViewModel.kt:59` 注入介面、`:117` 的 `seed()` 用到介面預設參數，皆可編譯（debug + release 皆隨 `test`/`assembleRelease` 驗過）。
- **`statsBetween` 排序語義**:DESC + `LIMIT` 取最新 N 筆、`asReversed()` 後維持下游預期的由舊到新，與 `messagesBetween` 的 KDoc 一致。
- **debounce 行為**：首次發射僅延遲 400ms；期間切換是 `combine` 的新發射，trailing debounce 保證 400ms 後重算；通知爆量時重算頻率被封頂。`distinctUntilChanged` 作用於 data class `InboxCounts`(`InboxRepository.kt:18`)，等值比較成立。
- **兩個新 instrumented 測試（純閱讀）**：斷言方向正確、使用既有 API、無 `@Ignore`／空測試；`DemoDataTest` 的反向斷言插在 `clear()` 之後、demo 計數歸零斷言之前，順序正確。
- **文件誠實性（除 N-1 外）**:CHANGELOG 其餘每條宣稱皆對得上程式碼；SCOPE.md 關於 release dex 的宣稱經我實測成立；TEST_MATRIX 新增兩列（BackupStager 21、CaptureCoordinator 11）與 round-4 報告的點算一致；中英版同步。
- **CI 權限收窄**:`release.yml` 的 `contents: write` 現在只存在於實際建立 GitHub Release 的 job;build 與 play-upload job 繼承 workflow 層的 `contents: read`，且後者不需要寫權限。

**給維護者的一句話**：補上 `AnalyticsScreen` 對 `capped` 的一行渲染（或先撤下文件的對應宣稱），本變更集即可放行。
