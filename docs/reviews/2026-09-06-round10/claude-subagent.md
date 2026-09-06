# Review round 10 — Claude subagent（audit P0 wave）

Range: `3685469..f64ae7b`（單一 commit）· 44 檔 · +2577 / −223
Reviewer: Claude（Opus 5, 1M）· 唯讀審查，未修改 working tree / index / HEAD
執行過的驗證：`:platform:capture:testDebugUnitTest` 與 `:platform:storage:testDebugUnitTest`（含 `--rerun-tasks` 重跑）、
`:feature:onboarding:compileDebugKotlin :feature:health:compileDebugKotlin :feature:settings:compileDebugKotlin :app:compileDebugKotlin :platform:backup:compileDebugKotlin`、
en/zh-Hant string parity script、schema `3.json` 版本檢查。**未執行**：instrumented 測試、裝置操作、`adb`、完整 174 個 JVM 測試、negative control。

---

## Verdict

**REQUEST CHANGES**

不是因為有 Critical 等級的資料損毀或安全退步——沒有。是因為兩件事直接牴觸這個 commit 自己的宣稱，而本專案的 review 慣例是「docs 不得超前 code」且「最嚴格的判定勝出」：

1. `VaultMaintenance` 與 `VaultRepository` 的 KDoc、以及 ARCHITECTURE（en + zh）都寫「備份／還原也在維護閘門內」，但 `platform/backup` 對 `VaultMaintenance` 的引用數是 **0**。
2. #3 宣稱「verified reset」，但四條失敗分支裡有三條會讓 App 停在無法使用的狀態（vault 永遠 `Opening`），而且會連帶把 `maintenance.active` 的 collector 卡死。

兩件都是小改動可修（一段文件 + 一個 `finally { holder.retry() }` + 把 collector 的 bookkeeping 改成 launch）。修掉之後我認為可以 merge。

---

## Claim verification table

| Issue | 宣稱 | 結果 | 證據 |
|---|---|---|---|
| #1 | `admitted()` 在等 `pipelineMutex` 前後各評估一次 | ✅ | `CaptureCoordinator.kt:466-472`；`process()` 於 `:480` 與 `:487` 兩次呼叫，`:486` 先 `loadSourcePolicy()` 再做第二道 fence |
| #1 | `commitFenced()` 在 `processJournaled` 開頭與 `ingest.commit` 之前 | ✅ | `CaptureCoordinator.kt:518-528`；呼叫點 `:540` 與 `:570`，`:571` 才 `ingest.commit` |
| #1 | source add/enable/pause/remove 走 coordinator，vault 寫入 + `loadSourcePolicy()` 同在鎖內 | ✅ | `CaptureCoordinator.kt:316-347`；`HealthViewModel.kt:104-115`、`OnboardingViewModel.kt:101` 已改走 coordinator；grep 確認 main source set 內已無其他直接呼叫 `sources.setEnabled/setPaused/remove/enable` 的地方 |
| #1 | `observeSources` collector 只在鎖內觸發 reload | ✅ | `CaptureCoordinator.kt:162-164`，emit 只當 trigger，policy 由 `sources.sources()` 於鎖內重讀 |
| #1 | 停用／移除來源會丟棄 PENDING journal（新 `packageName` 欄） | ✅ | `Daos.kt:59-60` `discardPending`、`Entities.kt:62-63`、`CaptureCoordinator.kt:337`、`SourceRepository.kt:68` |
| #1 | `replayJournal` 在 `VaultMaintenance.work` 內、暫停時停住、在 resume / vault ready / 維護結束 / 來源取消暫停時觸發，並把已停用來源的列標為 `DISCARDED` | ✅ | `CaptureCoordinator.kt:601-639`；觸發點 `:178`、`:282`、`:342`、`:392`；`:608` 的 `!paused`；`commitFenced` `:522` 標 `DISCARDED` |
| #1 | 暫停中的來源其列維持 PENDING | ✅ | `CaptureCoordinator.kt:525` `return true` 而不標記 |
| #2 | `mediaCopyEnabled` 預設 false，effective = switch && disclosure | ✅ | `SettingsRepository.kt:37`、`:82` |
| #2 | `MediaCopier.copyPending` 重讀設定並把 PENDING 降級為 `DISABLED_BY_USER` | ✅ | `MediaCopier.kt:59-65` |
| #3 | `VaultMaintenance`：`pipelineMutex` / `active` / `work {}` / `exclusive {}`（flag → cancel → join → lock） | ✅ | `VaultMaintenance.kt:36-76`，順序正確 |
| #3 | `deleteEverything(): ResetResult` 每步驗證 | ⚠️ 部分 | `VaultRepository.kt:47-56` 有驗證，但失敗分支不重開 vault（見 Important-1） |
| #3 | `closeAndDeleteFiles(): Boolean`、`MediaDirectory.deleteAll(): Boolean` | ✅ | `DatabaseHolder.kt:86-98`、`RetentionWorker.kt:133-137` |
| #3 | `KeyMaterial.epoch` 由 `destroyAll()` 遞增，`BlobCipher` 快取 `(epoch, aead)` | ✅ | `KeyMaterial.kt:32-40`、`:54`；`BlobCipher.kt:24-60` |
| #3 | coordinator `onMaintenance()` 輪換 generation、結束／開始 session、記錄 `MAINTENANCE` 缺口（新 `GapReason` + 兩語系字串 + label） | ✅ | `CaptureCoordinator.kt:358-394`；`CaptureHealth.kt:20-22`；`Labels.kt:74`；`values/strings.xml` + `values-b+zh+Hant/strings.xml` 皆有 `gap_reason_maintenance` |
| #3 | `SettingsViewModel` 以 snackbar 呈現 `resetFailedStep` | ✅ | `SettingsViewModel.kt:35-36`、`:145-161`；`SettingsScreen.kt:110-116` |
| #3 | 「維護中一切金庫寫入被停下」涵蓋 backup | ❌ | `platform/backup` 對 `VaultMaintenance` 的引用為 0（見 Important-2） |
| #4 | `JournalDao.setState` 於非 PENDING 時清空 payload | ✅ | `Daos.kt:49-58` |
| #4 | `ConversationDao.rebuildProjection(ids, now)` | ✅ | `Daos.kt:161-182` |
| #4 | `deleteMessages` / `deleteConversation` 在交易內刪 media 列、交易後刪檔 | ✅ | `InboxRepository.kt:66-107` |
| #4 | `SourceRepository.remove` 清整張刪除圖，用 `substr` 前綴比對而非 LIKE | ✅ | `SourceRepository.kt:65-82`；`Daos.kt:366-368` |
| #4 | `RetentionService` 在 `work {}` 內、重建 projection、拿掉電量限制 | ✅ | `RetentionWorker.kt:55-75`、`:100-104` |
| #4 | `MediaDao.orphans()` 也抓沒被指向的 blob | ✅ | `Daos.kt:324-325` |
| #5 | `KeystoreWrapper.getOrCreateKey()` 在 process 級鎖內 | ✅ | `KeystoreWrapper.kt:79-96`、`:115`（companion 的 `createLock`） |
| #7 | expiry 過濾加到 `observeForConversation`、counts、`statsBetween`、`earliestSortKey`、`search` | ⚠️ 部分 | `Daos.kt:196-206/261-264/275-289/291-292/389`；但 `allForExport()`（備份匯出）未過濾（見 Important-3） |
| #6（部分） | `MediaCopier.store()` 檔案 → 單一交易 → 失敗刪檔；縮圖失敗即 null；bitmap 計數延到 copier 結束 | ⚠️ 部分 | `MediaCopier.kt:137-185`、`CaptureCoordinator.kt:582-591`；但取消時序有一個窗口不成立（見 Important-4） |
| schema | v3（`MIGRATION_2_3`，三個 nullable ADD COLUMN，`3.json`） | ✅ | `QuietInboxDatabase.kt:52`、`:84-97`；`schemas/.../3.json` version=3 |
| tests | `CaptureCoordinatorTest` 16（+5）、`VaultMaintenanceTest` 4（新 test source set + CI） | ✅ 已實測 | JUnit XML：capture 16 tests / 0 failures，storage 4 tests / 0 failures（`--rerun-tasks` 重跑後仍綠）。`build.gradle.kts:40-48` 新增 test source set，`ci.yml:31` 加入 `:platform:storage:testDebugUnitTest` |
| tests | instrumented `DeletionGraphTest` 5、`MigrationTest` 2→3、`KeystoreWrapperTest` | 🔍 未驗證（唯讀限制） | 原始碼存在且合理：`DeletionGraphTest.kt` 5 個 `@Test`、`MigrationTest.kt` 3 個、`KeystoreWrapperTest.kt` 1 個。storage instrumented 合計 5+2+3+3 = 13，與 TEST_MATRIX 的「13 個」相符 |
| tests | 174 JVM 綠、storage 13 / crypto 2 instrumented 綠、negative control | 🔍 未驗證 | 我只跑了 20 個 JVM 測試（capture 16 + storage 4） |

---

## Strengths

寫得好的部分，先講清楚，這不是客套：

- **`VaultMaintenance` 的屏障順序是對的，而且對的理由被寫進 KDoc（`:30-32`）。** flag → snapshot → cancel → join → lock 是完整的：在 flag 翻轉前註冊的 worker 一定在 snapshot 裡會被取消；之後才註冊的 worker 因為 `work()` 在註冊後**再讀一次** `_active`（`:58`）而拒絕啟動。`synchronized(workers)` 提供的 memory barrier 加上 StateFlow 的 volatile 讀，讓這個 Dekker 式握手真的成立，沒有漏洞。`exclusive` 先 join 再拿 `pipelineMutex`（`:71-72`）也正是避免「worker 卡在鎖上等不到取消」的關鍵。
- **雙重 fence 的順序細節是對的。** `process()` 在鎖內先 `loadSourcePolicy()` 才做第二次 `admitted()`（`CaptureCoordinator.kt:486-490`），而不是反過來；`processJournaled` 在做完 parser + identity 查詢這些耗時工作之後、`ingest.commit` 之前**再**檢查一次 `commitFenced`（`:570`）。這兩個順序如果寫反，整個 #1 的修復就是假的。
- **「停用 vs 暫停」的語意分得很乾淨。** 停用 → `DISCARDED` 且 payload 清空（永不落地）；暫停 → 維持 PENDING 等 resume。這是產品規則層級的正確性，不只是程式碼。
- **`GapReason.MAINTENANCE` 插在 `UNKNOWN` 之前是安全的**，因為缺口是以 `reason.name` 字串持久化（`HealthRepository.kt:53`、`:62`；`Entities.kt:39` 是 `String`），不是 ordinal。這個地雷被避開了。
- **v3 migration 是三個 nullable ADD COLUMN，不重寫任何列**，且 `MigrationTest` 用 `runMigrationsAndValidate` 對照匯出的 schema 驗證，並額外驗了舊列讀回為 null。完全符合「不做破壞性遷移」的硬規則。
- **舊列（v2 升上來、`packageName IS NULL`）的防禦是雙層的**：`discardPending` 抓不到它們，但 replay 時 `commitFenced` 仍會依 `enabledPackages` 把它們標為 `DISCARDED`（`CaptureCoordinator.kt:520-524`）。這是正確的 defence-in-depth，不是漏洞。
- **`substr(scopeKey, 1, length(:prefix)) = :prefix` 的理由被寫在註解裡**（`Daos.kt:365`）：package name 可以含 `_`，而 `_` 是 LIKE 的萬用字元。呼叫端傳 `"$packageName|"`（`SourceRepository.kt:76`），分隔符讓 `com.foo` 不會誤中 `com.foobar`。正確。
- **`DeletionGraphTest` 不是打勾式測試。** 它建真的 SQLCipher vault、寫真的 blob 檔、驗 `blobFile.exists() shouldBe false`，而且 `deleteEverythingIsVerifiedAndNoCachedCipherOutlivesTheOldKey` 還把 reset 前的 ciphertext 寫回去、驗證新金鑰下解密失敗為 `KeyFailure.Tampered`。這是真的在測 epoch 語意，不是在測「函式有被呼叫」。
- **`VaultMaintenanceTest` 刻意用真 dispatcher 而非 virtual time**，並在 KDoc 說明理由（測的是「順序」，virtual time 只會讓它看起來決定論）。判斷正確。
- **文件同步得很紮實**：en/zh-Hant string parity 我實測是 309 / 309、無單邊字串；ARCHITECTURE、TEST_MATRIX 兩語系都更新了；TEST_MATRIX 的「16 個」「4 個」「13 個」與我實測的數字全部吻合。
- **`mediaCopyEnabled` 的 UI 迴路沒有壞掉。** 我一開始擔心 effective 值會讓開關「按了打不開」，實測 `SettingsScreen.kt:182-183` 的邏輯是「要開但沒同意 → 先跳 disclosure 對話框」，而對話框的確認鍵同時寫入兩個 pref（`:324`）。沒有死結。

---

## Issues

### Critical (Must Fix)

無。沒有 happy-path 的資料損毀、沒有安全退步、沒有 `INTERNET` / `PendingIntent` / 對來源通知動作的違規。

---

### Important (Should Fix)

#### Important-1 — `deleteEverything` 的失敗分支讓 vault 永遠停在 `Opening`，並連帶卡死 `maintenance.active` 的 collector

**位置**：`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/VaultRepository.kt:47-56`
（連帶 `platform/capture/src/main/kotlin/dev/quietinbox/platform/capture/CaptureCoordinator.kt:166-169`、`:388-391`）

**問題**：四條路徑裡只有 `Done` 與 `Failed("reopen")` 會呼叫 `holder.retry()`。`Failed("database")`、`Failed("media")`、`Failed("keys")` 三條都是直接 `return@exclusive`：

```kotlin
if (!holder.closeAndDeleteFiles()) return@exclusive ResetResult.Failed("database")   // 此時 _state 已是 Opening
if (!mediaDir.deleteAll()) return@exclusive ResetResult.Failed("media")               // DB 檔已刪、仍是 Opening
keyMaterial.destroyAll()
if (keyMaterial.anySecretExists() || keyMaterial.keystoreKeyExists()) return@exclusive ResetResult.Failed("keys")
```

`closeAndDeleteFiles()` 在 `DatabaseHolder.kt:89` 就把 `_state` 設成 `VaultState.Opening`。之後沒有任何人再呼叫 `open()`。

**具體失敗情境**（連鎖，三段）：

1. 使用者按「刪除全部」。某個 pooled WAL reader 還開著，`-wal` 檔刪不掉 → `allGone = false` → `Failed("database")`。
2. `DatabaseHolder.db()` 的實作是 `_state.first { it !is VaultState.Opening }`（`:63`）——**永遠不會回傳**。所有 repository 呼叫從此掛住；`flowWithDb` 不再 emit，每個畫面停在 loading。使用者只看到一則 snackbar，App 已經死了，只有殺 process 才能救。
3. 更糟的是：`exclusive` 的 `finally` 把 `_active` 設回 false（`VaultMaintenance.kt:74`）→ `maintenance.active.collect`（**plain `collect`，lambda body 直接跑在 launch 協程裡**）→ `onMaintenance(false)` → `guarded { health.startSession(...) / health.recordGap(...) }`（`CaptureCoordinator.kt:388-391`）→ `holder.db()` → **collector 本身也永久掛住**。
4. 使用者按「再試一次」→ 第二次 `exclusive` 把 `_active` true → false。因為 collector 卡在步驟 3，StateFlow 會 conflate，第二輪 **完全沒有 `onMaintenance(true)`**：不輪換 generation、不記 `MAINTENANCE` 缺口、不結束 capture session。

擷取本身還是安全的（`admitted()` 於 `:468` 直接讀 `maintenance.isActive`，不依賴 collector），但「缺口一定被記錄、絕不隱藏」這條產品硬規則在這個路徑上被破壞了——而這正是 #3 想保證的東西。

**為什麼重要**：這是「reset」這條路徑，是使用者最需要它可信的時候；而且 #3 的整個賣點就是「verified，不會假裝完成」。目前它誠實地回報失敗，然後把 App 弄壞。

**修法**：一個 root fix 蓋掉兩者。

```kotlin
suspend fun deleteEverything(): ResetResult = maintenance.exclusive {
    try {
        ... 原本的步驟 ...
    } finally {
        if (holder.state.value !is VaultState.Ready) holder.retry()   // 任何離開路徑都重開 vault
    }
}
```

同時把 `CaptureCoordinator.kt:166-169` 的 collector body 改成不阻塞：bookkeeping 與 `replayJournal()` 各自 `scope.launch { ... }`（`setSourcePaused` 在 `:342` 已經是這個寫法），這樣 collector 永遠能繼續接收下一個 `active` 值。

---

#### Important-2 — 備份／還原完全不在維護閘門內，但三份文件都說它在

**位置**：`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/VaultMaintenance.kt:24`、
`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/repo/VaultRepository.kt:42-44`、
`docs/ARCHITECTURE.md`（Maintenance gate 節）、`docs/zh-Hant/ARCHITECTURE.md`（維護閘門節）

宣稱：

> `VaultMaintenance.kt:24` — Background work that touches the vault (media copies, journal replay, retention, **backup**) runs inside `work`
> `VaultRepository.kt:42-44` — capture, media copies, journal replay, retention and **backups** are stopped first

事實：`grep -rn "VaultMaintenance" platform/backup/src/main` 回傳 **0 行**。`BackupService` 只注入 `holder / keyMaterial / blobCipher / mediaDir / settings`（`BackupService.kt:54-59`），沒有 `maintenance`。它既不走 `work {}`，也不拿 `pipelineMutex`。

**具體失敗情境**：使用者從「還原備份」畫面觸發還原（`BackupService.apply` 開始把 blob 加密寫進 `mediaDir`，`:230-310`），然後切到設定按「刪除全部」。`exclusive` 取消並 join 所有已註冊 worker——還原不在名單裡，繼續跑。`mediaDir.deleteAll()` 回傳 true（當下目錄是空的），reset 回報 `Done`；還原接著把加密的媒體檔寫回剛清空的目錄。**「刪除全部」宣稱驗證通過，磁碟上卻留下媒體檔案。** 反向也成立：還原的寫入交易會撞上被關閉的 DB。

**為什麼重要**：這是隱私承諾（「刪除全部」）與文件承諾（「backups are stopped first」）同時失效。本專案每一輪 review 都會抓「docs ahead of code」，這是最典型的一例。

**修法**（擇一）：
- 最小：把 `BackupService.export` / `restore` 包進 `maintenance.work { }`，讓 reset 能取消它們；或把 restore 改成 `maintenance.exclusive { }`（它本來就是全庫寫入，而且和 reset 互斥才對）。
- 或者：把 KDoc 與 ARCHITECTURE（en + zh）中的 "backup / 備份" 拿掉，明說備份尚未納入閘門，並開一張 issue。

**注意**：如果選 `exclusive`，務必確認 restore 不是從 `work {}` 內被呼叫的——`exclusive` 會 cancel + join 所有 worker，若呼叫者自己就在 worker 集合裡會自我死鎖。目前的呼叫鏈（`SettingsViewModel.viewModelScope`）不在集合裡，安全。

---

#### Important-3 — #7 的到期保證在備份匯出／還原路徑上破功，且還原會替已到期的副本續命

**位置**：`platform/storage/src/main/kotlin/dev/quietinbox/platform/storage/db/Daos.kt:289`（`allForExport()` 無 expiry 過濾）
配合 `platform/backup/src/main/kotlin/dev/quietinbox/platform/backup/BackupService.kt:117`、`:301`

#7 把「到期」從「retention 跑過才消失」升級成「讀取時就隱藏」。但 `MessageDao.allForExport()` 仍是 `SELECT * FROM message ORDER BY id`，而它正是備份匯出的來源（`BackupService.kt:117`）。

**具體失敗情境**：使用者的保存期是 30 天。某訊息在昨天到期，retention 的 12 小時週期還沒跑到。使用者今天匯出備份 → 那則已到期的訊息**被寫進備份檔**（UI 上他早就看不到它了）。之後還原時：

```kotlin
expiresAtEpochMs = m.expiresAtEpochMs?.let { maxOf(it, now + retentionMs) }   // BackupService.kt:301
```

`maxOf` 把過去的到期時間拉到 `now + 30 天`——**已到期的內容以全新的保存期復活**，而且在 UI 上重新可見。

**為什麼重要**：這正好是 #7 想關掉的那類「內容活得比使用者以為的久」的問題，而且備份是持久的、會離開裝置的產物。

**修法**：`allForExport()` 加上 `WHERE expiresAtEpochMs IS NULL OR expiresAtEpochMs > :now`（並讓 `BackupService.export` 傳 `now`）；還原端把 `maxOf(...)` 改成保留原值、或直接跳過 `expiresAtEpochMs <= now` 的記錄。至少要二選一。

---

#### Important-4 — `MediaCopier.store()`：交易已 commit 但檔案被刪掉的取消窗口（#6 的宣稱在此不成立）

**位置**：`platform/media/src/main/kotlin/dev/quietinbox/platform/media/MediaCopier.kt:163-184`

```kotlin
db.withTransaction {           // :163
    val id = db.mediaDao().insert(...)
    db.messageDao().setMedia(messageId, MediaState.LOCAL_COPY.name, id)
}
written.clear()                // :180  ← 交易之外
return MediaState.LOCAL_COPY
} finally {
    for (f in written) dir.delete(f)   // :183
}
```

`withTransaction` 內部是 `withContext(transactionContext)`。`withContext` 的契約是：**即使 block 正常完成，只要外層 job 在期間被取消，resume 時仍會丟 `CancellationException` 並丟棄結果**。所以存在這個交錯：交易 commit 成功（`setTransactionSuccessful` + `endTransaction` 都跑完）→ `withContext` 丟 `CancellationException` → `written.clear()` 被跳過 → `finally` 把 blob 與縮圖檔刪掉。

結果：`media_blob` 列存在、`message.mediaBlobId` 指向它、磁碟上檔案不見了。而且 **`orphans()` 抓不到**——`Daos.kt:324` 的條件是「message 不存在，或 `mediaBlobId` 為 null / 不等於 blob id」，這裡三者都不成立，連結是完整的。使用者點開圖片只會拿到解密失敗。

有趣的是，**同一 repo 裡 `BackupService.apply` 用的就是正確寫法**：`var committed = false`（`:216`）→ 在交易 block 的**最後一行**設 `committed = true`（`:340`）→ `finally` 依 `committed` 決定刪不刪（`:347`）。`MediaCopier` 沒沿用。

**目前的實際影響有限**：唯一會取消 `copyPending` 的是 `exclusive`（reset），而 reset 接著就會刪掉整個媒體目錄，所以損害被蓋掉。但 #6 的宣稱是「every file written for a copy that did not reach that commit is removed」，反過來的保證（到了 commit 的就不刪）並不成立，而且一旦未來多一個取消來源（例如把 backup 納入 `work {}`，見 Important-2），這就會變成真的孤兒資料。

**修法**：照 `BackupService` 的樣式——

```kotlin
var committed = false
db.withTransaction {
    val id = db.mediaDao().insert(...)
    db.messageDao().setMedia(messageId, MediaState.LOCAL_COPY.name, id)
    committed = true          // 交易 block 內的最後一行
}
...
} finally {
    if (!committed) for (f in written) dir.delete(f)
}
```

---

### Minor (Nice to Have)

#### Minor-1 — replay 迴圈可能被「暫停中來源」的 PENDING 列擋在第一頁

`CaptureCoordinator.kt:601-639`；`Daos.kt:47` 的 `pending(limit)` 是 `ORDER BY receivedAtEpochMs LIMIT 200`（預設 200，`IngestRepository.kt:85`）。

若前 200 列全屬於被暫停的來源，`commitFenced` 讓它們維持 PENDING → `progressed` 保持 false → while 迴圈中止，排在後面、本來可以 commit 的列這一輪不會被處理。

**但先決條件幾乎達不到**：來源一旦被暫停，`pausedPackages` 就會讓 `offer()` / `admitted()` 在入列時就擋掉，不會再有新的 journal 列。一列要變成「被 fence 住的 PENDING」，只能是暫停剛好落在 `ingest.journal` 與 `commitFenced` 之間、且正好是那一個 in-flight 事件。要累積 200 列需要 200 次獨立的暫停事件。

所以這是**潛在的排序弱點，不是實際的資料遺失路徑**。真的要收緊的話：`pendingJournal` 加上「排除目前被 fence 的 package」的參數，或在偵測到整頁都被 fence 時改用 offset 往後翻頁。

#### Minor-2 — 「暫停中來源」與 `MAX_QUEUE_DEPTH` 的量級關係值得留一行註解

`Limits.MAX_QUEUE_DEPTH = 512` 大於 replay 的 200 筆分頁。雖然 Minor-1 說明了實務上到不了，但兩個常數的關係目前沒有任何地方寫下來；未來有人調大 queue 或調小 replay 分頁時，不會注意到它們有耦合。

#### Minor-3 — `rebuildProjection` 與 commit 路徑的 preview 截斷單位不一致

`Daos.kt:171` 用 `substr(m.body, 1, 200)`（SQLite 以字元／code point 計），commit 路徑用 `lastStored?.body?.take(200)`（`IngestRepository.kt:359`，Kotlin 以 UTF-16 code unit 計）。含 emoji 的訊息在「刪除後重建」與「即時 commit」兩條路徑會得到長度不同的預覽，`take(200)` 還可能把 surrogate pair 切一半。純視覺問題，但兩邊應該一致。

#### Minor-4 — 對話列表的 `messageCount` 不會因到期而更新，與全域計數不一致

`observeCounts(now)`（`Daos.kt:262/264`）已排除到期列，但 `conversation.messageCount` 只在刪除或 retention 掃過時才由 `rebuildProjection` 重算。在到期與 retention 週期之間，對話列表每列顯示的筆數會高於首頁的總數。誠實標示的產品原則下，這種不一致值得至少在 SCOPE 註明。

#### Minor-5 — `delete_everything_failed` 把英文步驟名／例外類別名插進中文 snackbar

`SettingsViewModel.kt:151-154` 在例外時用 `failure::class.java.simpleName` 當 step，`VaultRepository` 用 `"database"` / `"media"` / `"keys"` / `"reopen"`。zh-Hant 字串 `未能刪除全部資料（失敗步驟：%1$s）` 因此會顯示 `database` 或 `SQLiteException`。功能上沒問題（可診斷），但和其他全中文的錯誤訊息不一致，可考慮做一層 step → 在地化字串的映射。

#### Minor-6 — ARCHITECTURE 說還原走 `rebuildProjection`，實際不是

`docs/ARCHITECTURE.md` 與 `docs/zh-Hant/ARCHITECTURE.md` 的「刪除圖」段落寫 `ConversationDao.rebuildProjection` 在「每次刪除、到期清理與**還原**後」重算。還原實際上是在 `BackupService.kt:325-330` 自己 inline 算 `rows.count { it.dedupState != "AMBIGUOUS_REPEAT" }`，**沒有** expiry 過濾、也沒有呼叫 `rebuildProjection`。結果大致等價，但文件點名了一個沒被呼叫的函式。

#### Minor-7 — `BlobCipher.primitive()` 在 epoch 變動時仍回傳該 primitive

`BlobCipher.kt:57` 只在 epoch 未變時寫入快取，這點正確；但函式本身仍 `KeyResult.Ok(p)` 回傳一個可能屬於已死 epoch 的 primitive。目前靠維護閘門保證呼叫端在那個窗口不會執行（媒體複製走 `work {}`），所以安全。若要自洽，epoch 不符時應回 `KeyFailure` 或重試一次。

#### Minor-8 — `deleteForScopePrefix` 的 `substr(...)` 無法走索引

`Daos.kt:366-368` 的條件對 `scopeKey` 做函式運算，SQLite 無法使用索引，會全表掃描 `deletion_suppression`。只在「移除來源」時執行一次，量級無虞；純粹留一筆記錄。

#### Minor-9 — 新測試裡的時序假設與一行無效果的呼叫

- `CaptureCoordinatorTest.kt:389-391`：註解說「the mutex is fair」。實際上 `evt-b` 當下並不在 mutex 的等待佇列裡（單一 consumer 迴圈還在處理 `evt-a`），所以真正在等鎖的只有 `change` 一個，測試比註解所描述的更決定論。註解可以修正，測試本身沒問題。
- 同一測試的 `delay(200)`（`:392`）在高負載 CI 上是常見的 flake 來源；建議改成等待一個可觀測的狀態（例如 `awaitUntil { ... }`）而不是固定時間。
- `DeletionGraphTest.kt:198` 的 `inbox.deleteMessages(emptyList(), 0L, 0L)` 是 no-op（`InboxRepository.kt:67` 對空清單直接 return），看起來是殘留行。

#### Minor-10 — `VaultRoundTripTest` 為了配合讀取時到期而把 `retentionMs` 從 30 天改為 `null`

`VaultRoundTripTest.kt:89/107/128`：fixture 的時間戳在 2023 年，加上 30 天保存期後在「現在」已經到期，於是新的讀取過濾會讓斷言失敗。改成 `null`（永久保存）是必要且合理的調整，但副作用是這個 round-trip 測試不再涵蓋「commit 時設定到期時間」這條路徑。`DeletionGraphTest` 有補上，所以整體覆蓋沒有退步——只是記錄一下這個 trade-off。

---

## Recommendations

1. **先修 Important-1 與 Important-2**，這兩個是我判 REQUEST CHANGES 的唯一理由，改動都很小：`deleteEverything` 加 `finally { holder.retry() }`、collector 內的 bookkeeping 改 launch、以及決定「把 backup 納入閘門」或「把文件裡的 backup 拿掉」。
2. **Important-3 與 Important-4 建議同一輪一起修**，兩者都是「宣稱的保證比實作強」，而且修法都只有幾行。
3. **補一個測試涵蓋 `deleteEverything` 的失敗分支**：注入一個 `closeAndDeleteFiles()` 回傳 false 的情境，斷言結束後 `holder.state.value is VaultState.Ready`（也就是失敗不會讓 App 變磚）。目前 `DeletionGraphTest` 只測了 happy path。
4. **補一個測試涵蓋「維護 collector 存活」**：連續兩次 `exclusive`，斷言第二次仍記錄了 `MAINTENANCE` 缺口。這會同時鎖住 Important-1 的第 3、4 步。
5. **`MediaCopier` 直接沿用 `BackupService` 已有的 `committed` 旗標樣式**，順手把這個 idiom 記進 CLAUDE.md 的「Working rules」，避免第三處再寫錯。
6. **negative control 我沒能驗證**（宣稱「拿掉鎖內 fence ⇒ 3 個測試失敗」）。這是很好的實務，建議把它寫進 `docs/reviews/` 的紀錄，附上實際的失敗測試名稱，讓下一輪 reviewer 可以直接檢查。

---

## 我未能驗證的部分（明列，避免被當成已驗證）

- instrumented 測試（storage 13 / crypto 2）——唯讀限制，未接裝置或模擬器。
- 宣稱的 174 個 JVM 測試全綠——我只實跑了 `:platform:capture`（16）與 `:platform:storage`（4），共 20 個，全綠。其餘模組我只做了編譯驗證（`:feature:onboarding` `:feature:health` `:feature:settings` `:app` `:platform:backup` 皆 `compileDebugKotlin` 成功）。
- 裝置 walkthrough、螢幕截圖、`tools/check-permissions.sh`。
- negative control（移除鎖內 fence 是否真的讓 3 個測試失敗）。

---

## Assessment

**Ready to merge? — With fixes.**

核心的並行設計（維護閘門的 flag/cancel/join/lock 順序、雙重 admission fence、commit fence 的位置）我逐條追過，是正確的；schema v3 是非破壞性的；測試不是打勾式的；文件與程式碼的數字對得上。擋下來的是兩件與 commit 自身宣稱牴觸的事：備份完全不在它被文件宣稱所在的閘門內，以及「verified reset」在四條失敗分支中的三條會讓 App 停在無法使用的狀態並拖垮維護 collector。兩者合計約 20 行的修改，修完我會給 APPROVE。
