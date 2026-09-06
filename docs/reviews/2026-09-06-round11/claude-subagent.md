# Review round 11 — Claude subagent（audit wave 2 + round-10 fixes）

Range: `f64ae7b..825d708`（單一 commit）· 75 檔 · +2271 / −265
Reviewer: Claude（Opus 5, 1M）· 唯讀審查，未修改 working tree / index / HEAD / branch
未派任何 subagent；整份 diff 由我自己分兩輪讀完（先 capture/storage/crypto/backup 核心，再 feature/docs/CI）。

實際執行過的驗證：

| 驗證項目 | 指令 | 結果 |
| --- | --- | --- |
| 全部 JVM 測試（強制重跑） | `./gradlew test --rerun-tasks --console=plain` | BUILD SUCCESSFUL，377 tasks executed |
| 測試數統計（JUnit XML） | `find . -path "*/build/test-results/*" -name "TEST-*.xml"` 彙總 | **193 tests / 0 failures / 0 skipped** |
| 各模組測試數 | 同上分模組 | model 5、parser 10、identity 5、reconcile 22、analytics 34、parsers:apps 43、app 5、crypto 3、storage 11、backup 24、capture 20、feature:analytics 8、feature:search 2、feature:conversation 1 |
| Lint | `./gradlew :app:lintDebug` | BUILD SUCCESSFUL；`lint-results-debug.xml` 內 `severity="Error"` 計數 = **0** |
| en/zh-Hant 字串對照（含 plurals） | 自寫 Python 解析兩份 `strings.xml` | 317 / 317，無單邊字串 |

**未執行**（唯讀限制，已於任務說明中被禁止）：instrumented 測試（storage 15 / crypto 2 / backup 1）、任何裝置或模擬器、`adb`、`tools/check-permissions.sh`、release APK 建置、裝置 walkthrough。這些宣稱我一律標為「未驗證」，不當成已驗證。

---

## Verdict

**REQUEST CHANGES**

不是因為架構走錯。維護閘門的回呼化、`BlobCipher` 的 epoch fail-closed、`deleteEverything` 的 `finally { retry() }`、keyset 搜尋迴圈、備份的分頁匯出與 `rebuildProjection` 還原——這些都做對了，而且是有測試撐著的對。193 個 JVM 測試我實跑過，文件數字與程式碼逐一吻合（連 TEST_MATRIX 的 76 / 43 / 5 / 20 / 11 / 15 都對得上，這在前十輪是最常出問題的地方）。

擋下來的原因是：**本輪的旗艦修復（#13 冷啟動保留緩衝）自己引入了兩條「內容被丟掉但不記缺口」的路徑**，而「gaps are shown, never hidden」是這個專案寫在 CLAUDE.md 的硬規則，round 10 也正是以「宣稱與實作不符」判 REQUEST CHANGES。另外兩件：刪除抑制 token 的主鍵碰撞讓已刪除內容可能復活（#9 的修法把保證換了個方向卻沒補回來），以及匯出把整段媒體解密／base64／落檔包在單一 Room 交易裡，會擋住擷取寫入卻不留缺口——後者剛好抵消了「用 `work` 而不是 `exclusive`」這個對 #16 的刻意偏離所要換取的好處。

四件都是局部修改（合計約 30 行 + 一次 schema 決策），修完我會給 APPROVE。

---

## Round-10 verification table

### Gemini 3.8 Flash (high, via agy) — round 10

| # | 發現 | 是否修復 | 證據 |
| --- | --- | --- | --- |
| Important-1 | `replayJournal` 被暫停來源的 200 筆 PENDING 堵死（head-of-line） | ✅ 已修 | `Daos.kt:49-56` 新增 `pendingExcluding(limit, excludedPackages)`；`IngestRepository.kt:94-97` 依 `excludingPackages` 選查詢；`CaptureCoordinator.kt:708-710` 傳入 `pausedPackages`。空集合時走原本的 `pending()`，避免 `NOT IN ()` |
| Important-2 | `BlobCipher.primitive()` 在 epoch 變動時仍回傳已死金鑰的 primitive | ✅ 已修 | `BlobCipher.kt:26-30` 新增 `Build.Stale`；`:75-76` epoch 不符時 `return Build.Stale`，既不快取也不回傳；`:36-42` 重試一次後 `KeyFailure.Unavailable("key epoch changed…")` fail closed。`finally { raw.fill(0) }` 在 Stale 路徑上仍會執行 |
| Important-3 | `VaultMaintenance.active` 用 `StateFlow`，快速 true→false 會被 conflate | ✅ 已修 | `VaultMaintenance.kt:18-26` 新增 `MaintenanceListener`；`:88` `onMaintenanceStarted()` 在 cancel/join **之前**、`:95` `onMaintenanceEnded()` 在 `finally` 內；`CaptureCoordinator.kt:193-198` 改註冊 listener、不再 collect `active`。`VaultMaintenanceTest`「a listener sees start and end exactly once even for an instant exclusive run」連跑三次 `exclusive {}` 斷言 6 個事件——這正是原發現的 negative control |
| Minor-4 | `MediaCopier.store()` 交易 commit 後被取消會刪掉檔案 | ✅ 已修 | `MediaCopier.kt:179-181` `written.clear()` 移進 `withTransaction` 內，成為 block 最後一行 |
| Minor-5 | `CaptureCoordinatorTest` 依賴 `delay(200)` | ✅ 已修 | `CaptureCoordinatorTest.kt:387-390` 改 `CoroutineStart.UNDISPATCHED` + `coVerify(exactly = 0)` 確認尚未進入 `setEnabled`，不再有時間假設 |

### Claude subagent — round 10

| # | 發現 | 是否修復 | 證據 |
| --- | --- | --- | --- |
| Important-1 | `deleteEverything` 失敗分支讓 vault 永遠 `Opening`，並卡死 maintenance collector | ✅ 已修（兩處都修） | `VaultRepository.kt:48-62` 整段包 `try { … } finally { if (holder.state.value !is VaultState.Ready) holder.retry() }`；`CaptureCoordinator.kt:456` 與 `:476-482` 的結束記帳與 `replayJournal()` 改成 `scope.launch`，不再在維護呼叫端等待。`VaultRepositoryTest`（3 個）分別覆蓋 database / media 失敗與 happy path，並斷言結束後 `state.value is VaultState.Ready`、`keys.destroyAll()` 未被呼叫 |
| Important-2 | 備份完全不在維護閘門內，但三份文件都說在 | ✅ 已修（且文件已改為精確描述） | `BackupService.kt:84` `export = maintenance.work {}`、`:205` `import = maintenance.exclusive {}`；`docs/ARCHITECTURE.md:85-87` 與 `docs/zh-Hant/ARCHITECTURE.md:72-75` 現在明說「匯出是可取消的金庫工作、匯入是 exclusive 的維護執行」。⚠️ 這偏離了 issue #16 原文的「export / import 都在 `exclusive {}`」，且帶來 Important-3（見下） |
| Important-3 | `allForExport()` 無到期過濾；還原以 `maxOf` 讓到期內容復活 | ⚠️ 一半修、一半改為文件化 | 匯出端已修：`Daos.kt:309-313`（message）、`:328-332`（revision）、`:379-383`（media）都帶 `now` 過濾，`BackupService.kt:132` 取一次 `now` 在同一交易內共用。還原端的 `maxOf(it, now + retentionMs)` **維持原樣**，改為在 `docs/SCOPE.md:47-48` 記為刻意行為（「備份不含已到期副本，因此還原給新保存期是刻意的」）。這個處理我接受：來源端已經不可能匯出到期內容，殘餘語意也寫清楚了 |
| Important-4 | `MediaCopier` 交易後取消的窗口 | ✅ 已修 | 同 Gemini Minor-4 |
| Minor-1 | replay 分頁被暫停來源的列擋住（與 Gemini Important-1 同一個發現，評為 Minor） | ✅ 已修 | 同 Gemini Important-1：`Daos.kt:49-56` `pendingExcluding` + `CaptureCoordinator.kt:708-710` |
| Minor-2 | `MAX_QUEUE_DEPTH` 與 replay 分頁大小的耦合沒寫在任何地方 | ❌ 未動 | `Limits.kt:19-20` 的 `MAX_QUEUE_DEPTH = 512` 旁邊仍沒有提到與 `pendingJournal` 預設 200 的關係。無害，記錄用 |
| Minor-3 | preview 截斷單位不一致（`take(200)` vs `substr`） | ✅ 已修 | `IngestRepository.kt:61-67` 新增 `takeCodePoints`，`:369` 改用它；KDoc 明說與 `substr(body, 1, 200)` 同單位。實作正確（`codePointCount` + `offsetByCodePoints`），不會切半 surrogate pair |
| Minor-4 | `conversation.messageCount` 在到期與 retention 之間會偏高 | ✅ 已文件化 | `docs/SCOPE.md:46`「Conversation list counts between expiry and the retention sweep」，明說最多偏差 12 小時 |
| Minor-5 | reset 失敗把英文步驟名塞進中文 snackbar | ⚠️ 部分修 | `SettingsScreen.kt:110-119` 對 `database` / `media` / `keys` / `reopen` 四個已知步驟做了在地化映射，兩語系字串都有（`delete_everything_step_*`）。但 `SettingsViewModel.kt:155` 的例外路徑仍用 `failure::class.java.simpleName`，落到 `else -> step`，中文 snackbar 仍會出現 `SQLiteException`。剩一個 `else` 分支未收 |
| Minor-6 | ARCHITECTURE 說還原走 `rebuildProjection`，實際不是 | ✅ 已修（改的是程式碼） | `BackupService.kt:364` 改呼叫 `db.conversationDao().rebuildProjection(convMap.values.distinct(), now)`，取代原本手寫的重算迴圈。文件現在是真的 |
| Minor-7 | `BlobCipher.primitive()` epoch 不符仍回傳 | ✅ 已修 | 同 Gemini Important-2 |
| Minor-8 | `deleteForScopePrefix` 的 `substr(...)` 走不到索引 | ❌ 未動 | `Daos.kt:401` 原樣。當初就只是記錄用，我同意不動 |
| Minor-9 | `delay(200)`、註解不精確、`DeletionGraphTest` 的 no-op 行 | ✅ 已修 | `CaptureCoordinatorTest.kt:386-390` 註解改寫為 UNDISPATCHED 的真正理由；`DeletionGraphTest.kt` 刪掉那行 `inbox.deleteMessages(emptyList(), 0L, 0L)` |
| Minor-10 | `VaultRoundTripTest` 的 `retentionMs = null` trade-off | — 記錄項 | 無需動作；`BackupRoundTripTest` 現在有一則 `retentionMs = 1` 的到期 fixture，這條覆蓋補回來了 |

**小結**：round-10 兩份報告合計 7 Important，7 個全部處理（6 個真修、1 個以「匯出端修 + 殘餘語意文件化」處理）；12 個 Minor 處理 9 個，未動的三個（Minor-2 註解、Minor-8 索引、Minor-5 的 `else` 分支）都不影響正確性。

---

## Issue claim table

| Issue | 宣稱 | 判定 | 證據與未達成的驗收項 |
| --- | --- | --- | --- |
| **#6**（QI-MEDIA-006） | commit 訊息說 `Refs #6` | ⚠️ **不可關閉** | 本輪只補了 round-10 的 `written.clear()` 位置（`MediaCopier.kt:179-181`）。issue #6 的驗收「Instrumented：DB 插入失敗時磁碟上沒有殘留檔案」在本 commit 中**沒有對應測試**（`platform/media` 沒有 androidTest）。CHANGELOG 自己寫的是「#6 (partial)」，與 brief 的「close #6」矛盾——以 CHANGELOG 為準，#6 應維持開啟 |
| **#8**（QI-ID-008） | COMPATIBILITY 中英、inbox work-profile 標示、SCOPE 記錄延後項 | ⚠️ 大致達成，缺一項驗收 | ✅ `docs/COMPATIBILITY.md:26-38` 與 `docs/zh-Hant/COMPATIBILITY.md:26-35` 兩語系對稱且內容一致（personal-profile listener、Device Policy、per-package 限制、低 RAM）；✅ `InboxScreen.kt:344-346` + `:422-423` `profileKey != "user:0"` 顯示 `Work` icon（`UserHandle.hashCode()` 回傳 identifier，owner = 0，判斷成立）；✅ `docs/SCOPE.md:45` 記錄 per-profile 與 non-null accountKey 為延後。❌ 驗收「健康頁在偵測到 work-profile 會話時顯示說明」**未做**：`HealthScreen.kt` 本輪只加了 `settingsMissing` 的手動路徑文字，沒有 work-profile 說明 |
| **#9**（QI-DEDUP-009） | id-aware 對齊 + `SuppressionRule` | ⚠️ 達成但引入回歸 | ✅ `Reconciler.kt:205-207` `aligns()`，`:128` 以 `match` lambda 貫穿 `suffixPrefixOverlap` / `containedAt`（`:210-232`），舊的 `List<String>` 多載保留給既有測試（`:238-244`）；✅ `ReconcilerIdAlignmentTest` 兩則正是驗收要的兩個方向；✅ 六個 §7.2 例題與兩個 1,000 次 property test 我實跑過，`core:reconcile` 22 tests 全綠；✅ `SuppressionRule.kt` + `Daos.kt:391-392` `token()` + `IngestRepository.kt:238-239`；✅ instrumented `SearchPagingTest.aDeletionTokenSuppresses…` 覆蓋「同一 post 抑制／之後新 post 不抑制」。❌ 見 Important-3：`deletion_suppression` 主鍵是 `(scopeKey, fingerprint)`，同指紋多則訊息只留一個 token |
| **#10**（QI-VAULT-010） | 兩個 ViewModel 觀察 `vault.state`，Locked 顯示 + 重試 | ✅ 達成 | `SearchViewModel.kt:52-62`、`SearchScreen.kt:120-125`、`ConversationViewModel.kt:65-95`、`ConversationScreen.kt:162-171`；`SearchViewModelTest`（2）+ `ConversationViewModelTest`（1），我實跑過全綠。`ConversationViewModel` 用 `Loaded<T>?` wrapper 區分「尚未產出」與「產出了 null」，避免鎖定時閃一下空狀態——這是正解 |
| **#11**（QI-SEARCH-011） | keyset 搜尋 + 會話內中位數 + 發送者鍵 | ✅ 達成（一項驗收改以 instrumented 交付） | `Daos.kt:424-425` keyset 條件 `(sortKey < :beforeSortKey OR (sortKey = :beforeSortKey AND id < :beforeId))` + `ORDER BY sortKey DESC, id DESC`，等值 `sortKey` 由 id 破平，不重不漏；`SearchRepository.kt:56-75` 迴圈邏輯我逐步推過（見下方「深入分析 1」）；`ActivityAnalytics.kt:102-109`（senderIdentity 分組）與 `:119-124`（會話內間隔）+ `intervalSampleSize`。⚠️ 驗收寫「JVM：候選前 200 筆全為假陽性」，實際交付為 instrumented `SearchPagingTest`（250 筆假陽性、游標續頁不重疊）。更強，但屬偏離，記錄之。⚠️ `SearchViewModel.run()`（`:85`）仍呼叫 `search.search(limit = 100)` 丟掉游標，UI 沒有接分頁——issue 沒要求，但 repository 的續頁能力目前無人使用 |
| **#12**（QI-CI-012） | wrapper SHA、lint abortOnError、issue forms、PR template、CODEOWNERS、Dependabot | ⚠️ 程式碼部分達成，repo 設定無法驗證 | ✅ `gradle-wrapper.properties:4` `distributionSha256Sum`；✅ `app/build.gradle.kts:88` 與 `build-logic/…/quietinbox.android.library.gradle.kts:20` `abortOnError = true`，我實跑 `:app:lintDebug` → 0 errors；✅ 四個 `.github/ISSUE_TEMPLATE/*.yml`、`PULL_REQUEST_TEMPLATE.md`、`CODEOWNERS`、`dependabot.yml`（僅 actions，理由寫在檔內）。❌ 「main branch protection」與「secret scanning + push protection」是 GitHub repo 設定，**不在 diff 內、我無從驗證**，不能算已完成。⚠️ CI 只跑 `.github/workflows/ci.yml:51` 的 `:app:lintDebug`，library convention 的 `abortOnError` 是否真的擋 CI，取決於 library issue 是否併入 app 報告；沒有獨立的 library lint job |
| **#13**（QI-CAPTURE-013） | `Held` 緩衝、`MAX_HELD=64`、15 s 逾時、`COLD_START` gap | ❌ **實作有兩條靜默丟失路徑**（見 Critical-1、Important-1） | ✅ `offer()`（`CaptureCoordinator.kt:497-500`）在 `!sourcesLoaded` 時 `hold(Held(sbn, …))` 並 `return`，確實沒有讀 extras；✅ `snapshotFactory` 改 `internal var` 供測試注入；✅ `CaptureCoordinatorTest`「before the source list is known…」用 mock factory 斷言 `created shouldBe emptyList()`，是真的在測「沒被呼叫」；✅ `GapReason.COLD_START` + 兩語系字串 + `Labels.kt:75`。❌ 驗收「逾時 → gap」的測試其實走的是 `sources.sources()` **拋例外**的路徑，15 s `withTimeoutOrNull` 到期那條**沒有測試**；❌ 緩衝溢位（`MAX_HELD`）→ CHANGELOG 說「緩衝溢出 → 丟棄並記錄 gap」，程式碼在成功路徑上不記（Critical-1） |
| **#14**（QI-CAPTURE-014） | 移除 `disabled_filter_types`、settings fallback 鏈、手動路徑 | ⚠️ 缺一項 | ✅ `platform/capture/src/main/AndroidManifest.xml` 已移除 `disabled_filter_types`，保留 `default_filter_types`；✅ `ListenerAccess.kt:24-58` 三段 fallback + `openSettings()` 回傳 boolean；✅ 三個畫面（Inbox / Health / Onboarding）都接了 `listener_settings_manual`，兩語系字串齊。❌ issue 的「補 diagnostic 代碼」（ongoing 由 parser 過濾時留一筆診斷）**沒做**：`StandardParser.kt:196` 仍只是 `shape.isOngoing` 併入 `looksLikeSystemNotice` 直接丟掉，全 repo 沒有對應的 diagnostic code |
| **#15**（QI-REMIND-015） | `unviewedCount`、`ReminderPolicy`、數量文案、`rescheduleNow()` 等待、權限檢查 | ⚠️ JVM 驗收達成，instrumented 驗收未做 | ✅ `Daos.kt:169-177` `unviewedCount(allPackages, packages)`；✅ `InboxRepository.kt:59-60`；✅ `ReminderScheduler.kt:97-101` `ReminderPolicy.shouldRemind` + `ReminderSchedulerTest` 4 個斷言；✅ `:118-125` 鎖定/失敗時吞成 0 且不發；✅ `:129` `scheduler.rescheduleNow()` 已 await；✅ `:155` 與 `notify()` 同一方法內再檢查一次 POST_NOTIFICATIONS（lint 硬性要求）+ `SecurityException` 兜底；✅ plurals 兩語系（zh 只有 `other` 是 CLDR 正確，不是缺漏）。❌ 驗收「Instrumented：查詢在有／無未查看會話時的結果」**沒有對應測試** |
| **#16**（QI-BACKUP-016） | export/import 進閘門、分頁匯出、partial media、`rebuildProjection` | ⚠️ 達成但偏離 issue 且引入 Important-2 | ✅ `BackupService.kt:84` / `:205`；✅ `:136-192` 四張表 keyset 分頁（`exportPage(afterId, …)`，id 唯一故無等值鍵問題）；✅ `BackupResult.Ok(counts, skippedMedia)` + `backup_result_partial_media` 兩語系 + `SettingsScreen.kt:411-412` 串接；✅ `:364` `rebuildProjection`；✅ instrumented `BackupRoundTripTest`（到期副本不入備份、跳過數回報、還原後投影重算、媒體以現行金鑰可解密）+ `ci.yml:86` 加入 `:platform:backup:connectedDebugAndroidTest`。❌ issue 原文要求 export **也**在 `exclusive {}`，實作用 `work {}`——這是有意的偏離（讓擷取在匯出期間繼續），但因為整個匯出包在單一 Room 交易裡，擷取其實還是被擋（Important-2），偏離換來的好處落空；❌ issue 的「延後（記錄於 docs/SCOPE.md）」四項（匯入預覽、衝突策略、保留期限選擇、備份內含設定／提醒／suppression）**沒有寫進 SCOPE** |
| **#17**（QI-PARSER-017） | COMPATIBILITY 補匿名 fixture 流程 + issue form；README 不得無限定宣稱支援 | ✅ 達成 | `docs/COMPATIBILITY.md:40-55` 與 zh `:36-47` 五步驟流程對稱；`.github/ISSUE_TEMPLATE/compatibility_report.yml` 存在並被文件引用；`about_limitations_body` 兩語系都保留「只以合成 fixture 測試」。issue 依設計保持開啟 |
| About 字串 | 指向真實 repository | ✅ | `strings.xml` / `values-b+zh+Hant/strings.xml` 的 `about_source` 已改為 `github.com/ImL1s/quietinbox` |
| 193 JVM 綠 | | ✅ 已實測 | `--rerun-tasks` 強制重跑，193 / 0 failures / 0 skipped |
| lint 0 errors | | ✅ 已實測 | `:app:lintDebug` 0 errors |
| instrumented storage 15 / crypto 2 / backup 1、release APK 無 INTERNET、AVD walkthrough | | 🔍 未驗證 | 唯讀限制。原始碼存在且數量吻合（`SearchPagingTest` 2 個 `@Test` 使 storage 13 → 15；`BackupRoundTripTest` 1 個），但我沒跑過 |

---

## Strengths

先講做對的，這些不是客套，是我逐條追過的：

- **`MaintenanceListener` 的插入點選得很準。** `onMaintenanceStarted()` 放在 `_active = true` 之後、`workers` snapshot 與 `cancel/joinAll` **之前**（`VaultMaintenance.kt:86-89`），所以擷取端在鎖還沒被請求之前就已經輪換 generation、丟掉佇列。`onMaintenanceEnded()` 放在 `finally` 且在 `pipelineMutex` 已釋放之後（`:93-96`），所以 `onMaintenance(false)` 內 `scope.launch` 出去的 `replayJournal()` 立刻就能拿到鎖，不會自我阻塞。順序如果反過來，整個修復就是假的。
- **`onMaintenance` 的兩處 `scope.launch` 是 round-10 Important-1 的正確 root fix。** 因為 listener 現在是**在 `exclusive` 的呼叫端執行緒上同步呼叫**的，任何在裡面 await 金庫的動作都會把 reset 本身卡住；改成 launch 之後，即使金庫重開失敗，維護流程仍會走完 `finally`。
- **`BlobCipher` 的 `Build` sealed interface 讓「重試一次然後 fail closed」變成型別上不可繞過的事。** `Stale` 不帶 `KeyResult`，所以呼叫端不可能拿到一個 dead-epoch 的 primitive；`finally { raw.fill(0) }` 在三條離開路徑（Done / Stale / 例外）上都會執行。
- **keyset 搜尋的游標推進位置是對的。** `SearchRepository.kt:65-69` 的 `if (verified.size == limit) break` 放在 `position = …` **之前**，所以 break 當下 `position` 停在最後一筆「已檢視」的列，未檢視的列留給下一頁——寫反就會漏行。`Long.MAX_VALUE` 的初始游標配合 `sortKey < MAX OR (sortKey = MAX AND id < MAX)` 也涵蓋了 `sortKey == Long.MAX_VALUE` 的邊界。
- **`SearchPagingTest` 是真的在測分頁語意，不是打勾。** 它先斷言「舊的單頁查詢會回 0 筆」的那個情境現在回 1 筆，再用 100/100/50 三頁 + `toSet() shouldHaveSize 250` 同時驗證了「不足額」與「重疊」兩件事。250 > `CANDIDATE_PAGE(200)` 是刻意挑的，會逼迴圈跑第二頁。
- **`ConversationViewModel` 的 `Loaded<T>?` wrapper 是這輪最漂亮的一段。** `flowWithDb` 在鎖定時不 emit，直接 combine 會讓畫面永遠停在初始值；用 `onStart { emit(null) }` + `map { Loaded(it) }` 把「還沒產出」與「產出了 null」分開，`loading` 才能寫成 `v !is Locked && (v is Opening || c == null || m == null)`——沒有閃現空狀態，也沒有無限轉圈。
- **`ActivityAnalytics` 的 `senderIdentity` 用 `packageName|conversationId|(senderKey ?: name)`，顯示仍用 `senderName`。** 這正是 #11 要的：合併鍵與顯示名分離。排序加了 `thenBy { senderName }.thenBy { conversationId }` 讓同票數的順序決定論，測試才敢寫 `shouldBe listOf(...)`。
- **`VaultRepositoryTest` 用真的 `VaultMaintenance()`（非 mock）+ mock 的 `DatabaseHolder`**，所以它同時驗證了「失敗後 `maintenance.isActive` 回到 false」與「`keys.destroyAll()` 沒被呼叫」。後者是關鍵：一個刪不掉的資料庫如果金鑰被銷毀，使用者的資料就永遠打不開了。
- **`takeCodePoints` 與 `substr(m.body, 1, 200)` 的單位真的一致。** SQLite 的 `substr` 對 TEXT 以 code point 計，Kotlin 端用 `offsetByCodePoints`，兩條路徑（即時 commit 與 `rebuildProjection`）現在會給出同樣的預覽。
- **文件與程式碼的數字全部對得上。** TEST_MATRIX 兩語系宣稱 core 76（5/10/5/22/34）、parsers 43、app 5、capture 20、storage 11、feature 2+1+8、instrumented storage 15 / backup 1——我逐模組數過 JUnit XML，一個不差。en/zh COMPATIBILITY 與 ARCHITECTURE 兩兩對稱，`docs/reviews/README.md` 與 zh 版都把 round 10 記為合併後 REQUEST CHANGES（最嚴格者勝）。這一輪沒有「docs ahead of code」。
- **zh-Hant 的 plurals 只有 `other` 是正確的**，不是漏翻。CLDR 對 `zh` 只定義 `other`；補 `one` 反而會被 lint 警告且永不命中。

---

## Issues

### Critical (Must Fix)

#### Critical-1 — 冷啟動緩衝溢位時靜默丟棄通知，成功路徑上不記任何缺口（違反「gaps are shown, never hidden」）

**位置**：`platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:144`（`heldDropped`）、`:370-381`（`hold`）、`:394-409`（`releaseHeld`）、`:411-416`（`dropHeld`）

**問題**：`hold()` 在緩衝滿時丟掉最舊的一筆並 `heldDropped++`：

```kotlin
synchronized(held) {
    if (held.size >= MAX_HELD) {      // MAX_HELD = 64
        held.removeFirst()
        heldDropped++
    }
    held += item
}
```

`heldDropped` 只有一個讀者：`dropHeld()`（`:412`），而 `dropHeld()` **只在逾時／失敗路徑被呼叫**（`:390` `if (!loaded) dropHeld(...)`）。成功路徑走的是 `releaseHeld()`（由 `loadSourcePolicy()` 於 `:358` 呼叫），它把 `held` 清空並派送，**完全不看 `heldDropped`，也不記缺口**。

**具體失敗情境**（不是理論值，是每次 listener 連線的正常路徑）：

1. 使用者的手機通知欄有 100 則活動通知（真機上很常見）。
2. `onConnected()` 啟動一個協程，先 `health.startSession` / `closeAllWindows`（都要等金庫），再 `for (sbn in resync) offer(sbn, ACTIVE_RESYNC)`（`:236-238`）。
3. 此時 `observeSources` 的 collector 也在等金庫，`sourcesLoaded` 仍是 false。
4. `offer()` 對每一則第三方通知走 `hold()`。**注意這裡沒有任何 package 過濾**——過濾要等 policy 載入後才在 `releaseHeld()` 做。所以 100 則全部進緩衝，包含使用者根本沒啟用的 App。
5. 第 65 則起，每進一則就把**最舊的一則**擠掉。`activeNotifications` 的順序不保證把使用者啟用的來源排在後面，所以被擠掉的很可能正是要保存的內容。
6. policy 載入 → `releaseHeld()` 派送剩下的 64 則 → **36 則被丟掉、沒有 gap、沒有 diagnostic、`captureErrors` 也不加**。健康頁上這段時間看起來完全正常。

**為什麼是 Critical**：這是 CLAUDE.md 明列的硬規則（「Only content the source app puts in a notification is captured; **gaps are shown, never hidden**」）在最日常的路徑上被打破，而且是**本輪新引入的回歸**——修改前，冷啟動事件是進 512 格的 queue、由 `process()` 在鎖內過濾，溢位時 `enqueue()`（`:544`）會記 `QUEUE_OVERFLOW` gap 並把 listener 標成 `DEGRADED`。新的 64 格緩衝比舊佇列小 8 倍、在 package 過濾**之前**、而且沒有對應的溢位缺口。CHANGELOG 自己寫「緩衝溢出或逾時 → 丟棄並記錄 gap」，程式碼只做到後半。

**修法**（與 Important-1 同一處，一起改）：

```kotlin
private fun releaseHeld() {
    val (items, dropped) = synchronized(held) {
        held.toList().also { held.clear() } to heldDropped.also { heldDropped = 0 }
    }
    val now = System.currentTimeMillis()
    if (dropped > 0) scope.launch {
        guarded { health.recordGap(items.minOfOrNull { it.heldAtEpochMs }, now, GapReason.COLD_START, GapPrecision.BOUNDED, now) }
    }
    for (h in items) { /* 原邏輯 */ }
}
```

另外建議把 `MAX_HELD` 提高到與 `Limits.MAX_QUEUE_DEPTH` 同量級（或至少 256），因為它現在承擔的是整個 resync 的量。

**建議測試**：`CaptureCoordinatorTest` 加一則——`sources.sources()` 由 `CompletableDeferred` 卡住，連續 `onPosted` 70 次，釋放後斷言 `health.recordGap(any(), any(), GapReason.COLD_START, …)` 至少一次，且 `journaled` 只包含啟用來源的事件。

---

### Important (Should Fix)

#### Important-1 — `coldStart()` 在「policy 已載入」時既不派送也不丟棄，被保留的通知永久卡在緩衝裡

**位置**：`CaptureCoordinator.kt:383-391`（`coldStart`）、`:370-381`（`hold`）、`:353-359`（`loadSourcePolicy`）

**問題**：

```kotlin
private suspend fun coldStart() {
    val loaded = withTimeoutOrNull(COLD_START_TIMEOUT_MS) {
        pipelineMutex.withLock {
            if (!sourcesLoaded) guarded { loadSourcePolicy() }   // ← releaseHeld() 只在這裡面
            sourcesLoaded
        }
    } == true
    if (!loaded) dropHeld(System.currentTimeMillis())
}
```

`releaseHeld()` 全 repo 只有一個呼叫點：`loadSourcePolicy()` 的最後一行（`:358`）。當 `coldStart()` 拿到鎖時 `sourcesLoaded` 已經是 true，`loadSourcePolicy()` 被跳過 → `releaseHeld()` 不執行 → `loaded = true` → `dropHeld()` 也不執行。**緩衝裡的項目既沒被派送、也沒被丟棄、也沒記缺口。**

**具體失敗情境**（兩種，都在冷啟動的 resync 迴圈中會遇到）：

- **競態 A**：`offer()` 讀到 `sourcesLoaded == false`（`:497`），還沒進到 `hold()`；同一瞬間 `observeSources` 的 collector 在另一條執行緒上完成 `loadSourcePolicy()`（設 `sourcesLoaded = true`，`releaseHeld()` 掃到空緩衝）。接著 `offer()` 才把項目 append 進 `held`，`coldStartJob` 已結束 → 啟一個新的 `coldStart()` → 看到 `sourcesLoaded == true` → 直接回 true。該則通知永遠留在 `held` 裡。
- **競態 B**：`hold()` 的 `if (coldStartJob?.isActive != true) { coldStartJob = scope.launch {...} }`（`:378-379`）不是原子操作，且 `offer()` 可能同時從 listener callback 執行緒與 `onConnected` 的 `scope.launch`（`Dispatchers.Default`）被呼叫。兩條執行緒可能各啟一個 `coldStart()`；job 1 載入 policy 並派送，job 2 看到已載入 → 什麼都不做。兩者之間 append 進去的項目同樣被遺留。

被遺留的項目要等下一次 `loadSourcePolicy()`（來源清單變動或維護結束）才會被 `releaseHeld()` 掃到，那時 `h.generation != activeGeneration` 多半已成立（`:397`），於是**靜默 `continue`**，連 gap 都沒有。

**為什麼重要**：與 Critical-1 同一條產品規則，而且這條在單機測試裡幾乎不會被抓到（要撞上毫秒級的競態）。實務上每次冷啟動 resync 的 100 次 `offer()` 中，只要有一次落在窗口裡就會丟一則。

**修法**（單行，與 Critical-1 合併）：

```kotlin
pipelineMutex.withLock {
    if (!sourcesLoaded) guarded { loadSourcePolicy() } else releaseHeld()
    sourcesLoaded
}
```

順帶把 `coldStartJob` 的檢查與指派移進 `synchronized(held)` 內，消掉競態 B 產生的重複 job。

---

#### Important-2 — 匯出把「解密每個媒體 → base64 → 寫檔」整段包在單一 Room 交易裡：擷取被擋住，而且擋住時不記缺口

**位置**：`platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:84`（`export = maintenance.work`）、`:136`（`return db.withTransaction {`）、`:161-186`（媒體迴圈在交易內）

**問題**：實作**刻意偏離** issue #16（原文要求 export 也走 `exclusive {}`），改用 `work {}`，理由應該是「讓擷取在匯出期間繼續」。但 `writeRecords()` 的整個主體在 `db.withTransaction { … }` 裡，包含：

- 對每一列媒體 `blobCipher.decryptFile(...)`（讀檔 + AES-GCM 解密）
- `Base64.encodeToString(...)`（整份位元組陣列）
- `w.write(...)` → BufferedWriter → Tink streaming AEAD → `FileOutputStream(staging)`（實體寫檔）

Room 的 `withTransaction` 在單一連線池上開一個交易並把整個 block 綁在交易的 dispatcher 上。**以下鎖語意我沒有實測**（唯讀限制，也沒有翻 Room / SQLCipher 的實作），是依機制推導的預期：一個橫跨數十秒檔案 IO 的交易會延後同一個金庫上的併發寫入。實際的等待上限與逾時例外型別，請以裝置實測確認。

**具體失敗情境**：

1. 使用者在一個有 300 張圖的金庫上按「匯出備份」。整趟解密＋base64＋落檔要數十秒。
2. 同一時間收到一則通知。`process()` 拿到 `pipelineMutex`，`admitted()` 通過（`maintenance.isActive` 是 false——`work` 不設這個旗標），呼叫 `ingest.journal(snapshot, …)`。
3. 該 INSERT 被匯出的交易擋住。最好的情況是等到匯出結束（擷取停擺數十秒，佇列在 512 格內累積）；最壞的情況是等待逾時並丟出例外。
4. **只要走到丟例外那條**，它就落到 `CaptureCoordinator.kt:583-586` 的 `catch (e: Exception)` → `ingest.markJournalRetryable(snapshot.eventId, ...)`。但這一列**從來沒被插入**，`markJournalRetryable` 是對不存在的 row 做 UPDATE，等於 no-op。這一段的因果我已從程式碼確認；觸發它所需的鎖等待逾時則屬上述未實測的部分。
5. **事件消失，沒有 journal 列可以 replay，沒有 gap，健康頁上什麼都看不到。** 同時 `pipelineMutex` 被這個等待占著，後續事件在 512 格佇列裡累積。

也就是說：偏離 #16 想換到的「匯出期間擷取不中斷」並沒有拿到（SQLite 那層照樣擋），卻放棄了 `exclusive` 會自動記錄的 `MAINTENANCE` 缺口。兩頭皆空。

**為什麼重要**：這是靜默資料遺失，而且發生在使用者主動做「保護資料」的動作時。

**修法**（擇一）：

- **照 issue #16 走 `exclusive {}`**：擷取在此期間留在 journal，結束後 replay，窗口以 `MAINTENANCE` 缺口誠實記錄。最小改動，語意最乾淨。
- **或者把交易切小**：每張表用短的讀取交易分頁（`exportPage` 已經是 keyset，天生適合），媒體的解密與 base64 在交易**外**做。`End(actual)` 記錄已經帶著實際筆數，不需要靠單一交易保證一致性；`stage()` 也已經以 `End` 為準（`BackupStager.kt:82`）。

另外附帶一項：`maintenance.work` 在維護進行中回 `null`，`:84` 把它翻成 `BackupResult.Failed(VAULT_UNAVAILABLE, "maintenance")`。`SettingsScreen` 的 `backupResultText` 會把 `VAULT_UNAVAILABLE` 顯示成一般的金庫錯誤，使用者看不出「因為正在重設所以沒匯出」。建議給它一個專屬字串。

---

#### Important-3 — 抑制 token 主鍵是 `(scopeKey, fingerprint)`：同指紋的多則刪除只留一個 token，`SuppressionRule` 因此讓已刪除的內容復活

**位置**：`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Entities.kt:203`（`primaryKeys = ["scopeKey", "fingerprint"]`）、`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/InboxRepository.kt:79` 與 `:97`（每則訊息一次 `upsert`）、`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/SuppressionRule.kt:16-20`

**問題**：`deleteMessages` / `deleteConversation` 對每一則被刪的訊息 `upsert` 一個 token，但主鍵只有 `(scopeKey, fingerprint)`，所以**同一會話中指紋相同的 N 則訊息最後只留下一個 token，`sourceMessageId` 與 `postedAtEpochMs` 是最後寫入的那一則**。

在 round-10 之前這沒關係：`isSuppressed()` 只看有沒有列。現在 `SuppressionRule.applies()` 會拿 token 的 id 去比對候選的 id，**id 不同就不抑制**。

`Fingerprint.of()`（`core/reconcile/Fingerprint.kt:13-22`）由 `senderKey/displayName + body + sourceTimestamp（僅當 quality == SOURCE_MESSAGE）+ kind + media.uri` 組成。對於**不提供逐訊息時間戳的來源**（很多 MessagingStyle 以外的形狀就是如此），同一個人在同一會話講兩次同樣的話 → 指紋相同、source id 不同。

**具體失敗情境**：

1. Alice 在群組裡先後傳了兩則「好」（來源給了 id `m1`、`m2`，但沒有逐訊息時間戳）。
2. 使用者把整個會話刪掉。`deleteConversation` 對兩則各 upsert 一次 → 只剩一列，`sourceMessageId = "m2"`。
3. 手機重開機 / listener 重連，來源 App 把該通知重新 post，視窗裡兩則「好」都在。
4. 會話列已被刪，`Reconciler` 沒有 checkpoint，兩則都是 `Decision.New`。
5. `IngestRepository.kt:238-239` 對第一則呼叫 `SuppressionRule.applies("m2", …, "m1", …)` → 兩側都有 id 且不相等 → **false → 不抑制 → 已被使用者刪除的訊息寫回金庫**。第二則（`m2`）才被正確抑制。

**為什麼重要**：「使用者刪掉的東西不會被重播帶回來」是這個 App 最直接的隱私承諾，`InboxRepository` 的 KDoc 也是這樣寫的。#9 修的是反方向（新訊息不該被吞），修法把保證換了個方向，但沒有回頭補上原本那一側。`SuppressionRule` 的 KDoc 只把「重開機後以新 post 時間重貼」列為殘餘限制，沒有涵蓋這個 id 碰撞情境。

**修法**（擇一）：

- **把 `sourceMessageId` 併入主鍵**：`primaryKeys = ["scopeKey", "fingerprint", "sourceMessageId"]`。需要 schema v4；但 `deletion_suppression` 沒有任何 FK 指向它，重建成本低，且它是純快取（有 TTL），必要時 v3→v4 直接 `DROP TABLE` + `CREATE TABLE` 也不違反「不做破壞性遷移」（沒有使用者內容）。
- **或者退一步保守**：`SuppressionRule.applies` 在「兩側都有 id 但不相等」時，仍以 post 時間做第二道判斷（`postedAtEpochMs <= tokenPostedAtEpochMs → 抑制`）。這樣同一則 post 的重播（不論 id 對不對）都會被擋，之後的新 post 才放行。改動只有一行，不需要 schema 變更，但會犧牲「同一 post 內出現新 id 的新訊息」這個較罕見的情境。

無論選哪個，`SuppressionRule` 的 KDoc 與 CHANGELOG 的「殘餘限制」段落都要補上這一條。

---

### Minor (Nice to Have)

#### Minor-1 — 被保留的通知以「釋放時間」而非「到達時間」記錄 `observedAtEpochMs`

`CaptureCoordinator.kt:395`（`val now = System.currentTimeMillis()`）→ `:401` `snapshotFactory.create(h.sbn!!, h.origin, h.generation, now)`。`SnapshotFactory.kt:42` 的第四個參數 `nowEpochMs` 在 `:141` 直接成為 `observedAtEpochMs`。`Held` 明明已經帶了 `heldAtEpochMs`（`:76`）卻沒有用。

結果：同一批被保留的通知全部共用同一個觀測時間，最多偏差 15 秒（`COLD_START_TIMEOUT_MS`），而且批內順序在 `observedAtEpochMs` 上完全喪失。`sortKey`（`IngestRepository.kt:386-390`）在有 `postedAtEpochMs` 時不受影響，但 `rebuildProjection` 的 `lastActivityEpochMs = MAX(observedAtEpochMs)`、活動頁的觀測時間軸、以及 `#15` 的 `unviewedCount` 都會吃到這個偏移。對一個以「誠實標示」為賣點的 App，這是不必要的失真。

修法：`snapshotFactory.create(h.sbn!!, h.origin, h.generation, h.heldAtEpochMs)`。

#### Minor-2 — `Held` 保留 `StatusBarNotification` 強引用，繞過既有的 bitmap 上限紀律

`CaptureCoordinator.kt:143`。`MAX_QUEUED_BITMAPS = 8`（`:753`）限制的是進入佇列的 bitmap；`held` 最多 64 個 `StatusBarNotification`，每個都可能持有 BigPicture / LargeIcon 的 `Bitmap`，最長 15 秒不放。實際增量風險有限（`onConnected` 的 `resync` list 本來就同時持有這些物件），但 `docs/COMPATIBILITY.md` 這一輪才剛加上「低記憶體裝置」章節，兩者放在一起看值得留一行註解說明為何這裡不套 bitmap 上限。

#### Minor-3 — `ListenerAccess.openSettings()` 以 `resolveActivity` 當門檻，可能在能開的裝置上退回手動說明

`ListenerAccess.kt:47`。Android 11+ 的 package visibility 會過濾 `resolveActivity`；本專案 `app/src/main/AndroidManifest.xml:22-25` 的 `<queries>` 帶了 MAIN/LAUNCHER 的 `<intent>`，一般 build 的 Settings 有 launcher entry，所以實務上看得到。但既然三個 intent 都已經包在 `try/catch (ActivityNotFoundException)` 裡，`resolveActivity` 這道 `continue` 是多餘的保守，而且比改動前的行為更容易誤判成「這台裝置沒有設定畫面」。建議拿掉 `:47` 的 `continue`，只靠 try/catch。

另外 `:44` 的 `settingsIntent()` 會呼叫 `settingsIntents()` 兩次（各建三個 Intent），純浪費。

#### Minor-4 — reset 失敗的例外路徑仍會把英文類別名塞進中文 snackbar（round-10 Minor-5 的殘留 `else`）

`SettingsViewModel.kt:155` 用 `failure::class.java.simpleName` 當 step，落到 `SettingsScreen.kt:117` 的 `else -> step`。四個已知步驟已在地化，但 `SQLiteException` 這類仍會原樣出現。建議例外路徑統一映射到一個通用字串（例如「未預期的錯誤」），把類別名寫進 diagnostics 而不是 UI。

#### Minor-5 — `unviewedCount` 沒有排除「已無可見訊息」的會話，提醒可能為空會話而發

`Daos.kt:169-177` 的條件是 `archived = 0 AND lastActivityEpochMs > COALESCE(lastViewedEpochMs, 0)`，沒有 `messageCount > 0`。而 `rebuildProjection`（`:194`）在會話沒有任何可見訊息時把 `lastActivityEpochMs` 設成 `createdAtEpochMs`（不是 0），所以一個訊息全被刪光／全到期、且從未被開啟過的會話仍然算「未查看」。使用者會收到「有 1 個會話有新的副本」然後打開發現什麼都沒有。加一個 `AND messageCount > 0` 即可。

#### Minor-6 — `VaultRepositoryTest` 有一行同義反覆的斷言

`platform/storage/src/test/kotlin/…/VaultRepositoryTest.kt`：

```kotlin
h.state.value shouldBe h.state.value.also { (it is VaultState.Ready) shouldBe true }
```

外層 `x shouldBe x` 恆真；真正的檢查在 `.also {}` 裡（會執行，所以測試是有效的），但寫法會誤導下一位讀者。第二則測試已經用了乾淨的 `(h.state.value is VaultState.Ready) shouldBe true`，統一成那個寫法就好。

#### Minor-7 — `BackupRoundTripTest` 用全新的 `VaultMaintenance()`，等於沒有測到閘門互動

`BackupRoundTripTest.kt:62` `BackupService(..., VaultMaintenance())`。這讓 `work` / `exclusive` 都變成無競爭的直通，所以 #16 的「匯出可被 reset 取消」「匯入期間擷取被擋」兩個核心保證在這個測試裡沒有被驗證。測試本身（到期過濾、skippedMedia、投影重算、媒體可解密）都是紮實的真斷言，只是覆蓋範圍要誠實看待。

#### Minor-8 — 匯出 manifest 的 media 計數是已知不準的

`BackupService.kt:139-145` 的 `expected.media = mediaDao().exportCount(now)`（含之後被跳過的），而檔案裡只有 `mediaWritten` 筆；`BackupStager.kt:83` 的 `m.expected.copy(media = actual.media) != actual` 刻意把 media 排除在 manifest 檢查之外。這是設計如此（串流 AEAD 已保證完整性），但 manifest 對外是「這份備份有幾筆」的宣告，寫一個明知不符的數字不太乾淨。建議把 `expected.media` 也改成實際寫入數（`End` 已經是了），或在 manifest 加一個 `skippedMedia` 欄位。

#### Minor-9 — `VaultMaintenance` 的 KDoc 仍把 backup 整體歸在 `work`

`VaultMaintenance.kt:44-46`：「Background work that touches the vault (media copies, journal replay, retention, **backup**) runs inside `work`」。實際上只有 export 走 `work`，import 走 `exclusive`。ARCHITECTURE 兩語系已經寫對了，這段 KDoc 沒跟上。

#### Minor-10 — `SearchViewModel` 的 `distinctUntilChanged` 用 `va::class == vb::class` 比對金庫狀態

`SearchViewModel.kt:60`。`VaultState.Ready` 換了另一個 `db` 實例（例如還原之後）時類別相同，查詢不會重跑。目前 `retryOpen()` 會經過 `Opening` 所以實務上會觸發，但這個判準是脆的；用 `VaultState` 本身的 `equals`（`Ready` 是 data class，會比較 `db` 參考）更直接。

#### Minor-11 — repository 的搜尋游標目前無人使用

`SearchRepository.searchPage` 回傳的 `next` 在 UI 端被丟掉（`SearchViewModel.kt:85` 呼叫 `search.search(limit = 100)`）。#11 沒有要求 UI 分頁，功能上沒問題；但 `MAX_CANDIDATE_PAGES = 200` 用盡時回傳的游標沒有任何呼叫者去續，那個情境等於還是會少結果。至少在 KDoc 註明「UI 目前只取第一頁」。

#### Minor-12 — `reminder_body` 字串成為死字串

被 `reminder_body_count` plurals 取代後，`strings.xml` 與 zh 版的 `reminder_body` 已無呼叫點。留著無害，但下一輪清理時可以移除（兩語系一起）。

---

## 深入分析與驗證（供下一輪 reviewer 對照）

### 1. keyset 搜尋迴圈的邊界，逐條推過

`SearchRepository.kt:56-75`。`pageSize = maxOf(limit, CANDIDATE_PAGE)`。

- **最後一頁剛好等於 `pageSize`**：`rows.size < pageSize` 為 false → `exhausted` 維持 false → 迴圈續行（若 `verified < limit`）→ 下一次查詢回空 → `:62` `exhausted = true`。正確，只是多一次往返。
- **`rows.size < pageSize` 但 `verified.size == limit`**：`:70` 的 `&& verified.size < limit` 讓 `exhausted` 維持 false，回傳一個游標；呼叫者續頁會拿到空頁。這是 keyset 分頁的標準行為（無法在不多查一次的情況下確定是否還有下一頁），不是缺陷。
- **等值 `sortKey`**：`ORDER BY m.sortKey DESC, m.id DESC` 與 `(sortKey < :before OR (sortKey = :before AND id < :beforeId))` 是嚴格對應的，不會重複也不會跳過。
- **`MAX_CANDIDATE_PAGES` 用盡**：回傳非 null 游標，呼叫者可續（見 Minor-11）。
- **初始游標 `(Long.MAX_VALUE, Long.MAX_VALUE)`**：涵蓋 `sortKey == Long.MAX_VALUE` 的極端列。

### 2. 死結與自我死結檢查

- `import` = `maintenance.exclusive {}`，呼叫端是 `SettingsViewModel.viewModelScope`（`SettingsViewModel.kt:108`），**不在 `workers` 集合裡**，`joinAll` 不會 join 到自己。無自我死結。
- `export` = `maintenance.work {}`，`work` 用 `coroutineScope` 註冊自己的 job，是呼叫端的子協程，結構化併發完整。
- `exclusive` 的 `finally` 在 `pipelineMutex` 釋放**之後**才呼叫 `onMaintenanceEnded()`，所以 `onMaintenance(false)` 內 launch 出去的 `replayJournal()`（要 `work` + `pipelineMutex`）不會與持鎖者互卡。
- `MaintenanceListener` 的兩個回呼都是 `suspend` 且在 `exclusive` 的呼叫端同步執行。目前唯一的實作 `CaptureCoordinator.onMaintenance` 沒有真正的 suspension point（全部 launch 出去），所以不會把 reset 卡住。**但這是一個沒有被型別保護的約定**：未來若有人在 listener 裡 await 金庫，reset 就會死鎖。建議 KDoc 明寫「實作不得 suspend 於金庫」，或在 `exclusive` 內用 `withTimeout` 包住回呼。
- listener 拋例外：`onMaintenanceStarted()` 拋出 → `block()` 不執行、`finally` 仍會跑 `_active = false` 與 `onMaintenanceEnded()`；`onMaintenanceEnded()` 拋出會遮蔽原始例外。目前實作不會拋，記錄用。

### 3. `aligns()` 對六個 §7.2 例題與 property test 的影響

`Reconciler.kt:205-207`：兩側都有 id → 比 id；否則比 fingerprint。舊的 `List<String>` 多載（`:238-244`）保留，`ReconcilerTest` 既有案例不受影響。我實跑 `core:reconcile` 22 tests 全綠，其中包含六個 §7.2 例題與兩個 1,000 次固定 seed 的 property test（20260905 / 20260906）。

一個值得記錄的語意細節：`aligns()` 不是等價關係（A 無 id 與 B 有 id 可能對齊，A 有 id 與 C 有不同 id 則否），所以 `suffixPrefixOverlap` 找到的最大 k 未必唯一「自然」。在目前的規則下這是刻意的（id 是更強的證據），property test 也涵蓋了「重播絕不可縮小視窗」這條不變量，我認為可以接受。

### 4. `MaintenanceListener` 與 `sourcesLoaded` 的互動

`onMaintenance(false)`（`CaptureCoordinator.kt:462`）把 `sourcesLoaded` 設回 false，這是對的（金庫可能是全新的）。維護期間 `activeGeneration == null`，`offer()` 第一行就 return，所以維護窗口內不會有東西進 `held`——那段由 `MAINTENANCE` 缺口誠實涵蓋。維護結束後新到的通知走 `hold()` → `coldStart()`，此時 `pipelineMutex` 已釋放，可以立即取得。這條路徑設計是對的；壞在 Critical-1 / Important-1 那兩個共通缺陷上。

### 5. `deleteEverything` 的 `finally` 與非局部 return

`VaultRepository.kt:48-62`。Kotlin 的 `return@exclusive` 會先執行 `finally`，所以四條失敗分支與 happy path 都會經過 `if (state !is Ready) holder.retry()`。`closeAndDeleteFiles()` 自己拋例外時 `finally` 一樣執行、例外照樣往外傳（`VaultRepositoryTest` 沒有覆蓋這條，但語意上成立）。happy path 上 `retry()` 已把狀態設為 `Ready`，`finally` 不會重複呼叫——測試以 `coVerify(exactly = 1)` 鎖住了這點。

---

## 我未能驗證的部分（明列，避免被當成已驗證）

- instrumented 測試：storage 15（含新的 `SearchPagingTest` 2）、crypto 2、backup 1（`BackupRoundTripTest`）。原始碼我讀過並認為斷言是實質的，但**沒有在裝置上跑過**。
- `tools/check-permissions.sh`、release APK 是否無 `INTERNET`。
- 裝置 / AVD walkthrough（擷取、搜尋、對話）。
- `#12` 的 branch protection 與 secret scanning（GitHub repo 設定，不在 diff 內）。
- CI 上 `abortOnError = true` 對 library 模組是否真的會擋（本機 `:app:lintDebug` 0 errors，但 CI 沒有獨立的 library lint job）。

---

## Recommendations

1. **先修 Critical-1 與 Important-1**，它們共用同一個修改位置（`coldStart` 的 else 分支 + `releaseHeld` 讀取並歸零 `heldDropped` 並記 gap），合計不到 15 行。順手把 `MAX_HELD` 調到能吃下一次 `activeNotifications` resync 的量級。
2. **Important-2 建議直接回到 issue #16 的原文**（export 也走 `exclusive`）。這比切小交易改動更少、語意更乾淨，而且能把匯出窗口變成一個誠實的 `MAINTENANCE` 缺口——正是這個專案的產品哲學。
3. **Important-3 建議走 schema v4 把 `sourceMessageId` 併入抑制 token 主鍵**。`deletion_suppression` 沒有任何 FK 指向它、內容是帶 TTL 的快取，重建成本最低。若這一輪不想動 schema，就先用一行的保守 fallback，並把限制寫進 `SuppressionRule` 的 KDoc 與 CHANGELOG。
4. **補三個測試**，都很短：(a) 冷啟動緩衝溢位 → 有 `COLD_START` 缺口；(b) `withTimeoutOrNull` 真的到期（不是 `sources()` 拋例外）→ 有缺口；(c) 同一會話刪兩則同指紋不同 id 的訊息 → 重播時兩則都被抑制（instrumented，接在 `SearchPagingTest` 旁邊）。
5. **把未達成的驗收項回填 issue**，不要在 commit message 裡宣稱關閉：#6（缺 instrumented 媒體失敗測試，CHANGELOG 自己寫 partial）、#8（健康頁 work-profile 說明）、#14（ongoing 的 diagnostic code）、#15（instrumented 查詢測試）、#16（SCOPE 未記錄四項延後）、#12（branch protection / secret scanning）。這一輪的文件紀律整體很好，這幾項是唯一會讓下一輪 reviewer 再抓一次「docs ahead of code」的地方。
6. **`docs/SCOPE.md` 補上 #16 的四項延後**（匯入預覽、衝突策略、保留期限選擇、備份內含設定／提醒／suppression），issue 原文明確要求記錄於此。

---

## Assessment

**Ready to merge? — No（With fixes）。**

核心設計我逐條追過，方向都是對的：維護閘門從 conflating 的 `StateFlow` 改成明確回呼、`BlobCipher` 以型別強制 fail closed、`deleteEverything` 不再讓 App 變磚、keyset 搜尋的游標推進位置正確、備份納入閘門且匯出不再一次載入全表。193 個 JVM 測試我實跑過全綠，lint 0 errors，兩語系字串 317/317，TEST_MATRIX 的每一個數字都與實測吻合——文件紀律是這九輪以來最好的一次。

擋下來的是四件與 commit 自身宣稱牴觸的事，其中兩件出在本輪的旗艦修復裡：#13 的冷啟動緩衝在溢位時、以及在一個真實存在的競態下，會丟掉通知而**不記錄缺口**，直接違反「gaps are shown, never hidden」；#9 的抑制規則因為 token 主鍵碰撞，讓使用者刪除的內容可能被重播帶回來；#16 的匯出用單一長交易擋住擷取寫入，讓被擋掉的事件連 journal 列都沒有。四件合計約 30 行加一次 schema 決策，修完並補上三個測試之後我會給 APPROVE。
