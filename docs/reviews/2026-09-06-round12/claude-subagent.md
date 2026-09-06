# Review round 12 — Claude subagent（round-11 修復確認 mini re-review）

- **審查範圍**：`825d708..a3fd01b`（單一 commit，分支 `wave2`，worktree `/Users/iml1s/Documents/mine/quietinbox-wave2`）
- **審查性質**：mini re-review。逐條確認 round-11 的發現是否修好，並專找修復本身引入的回歸。

### ⚠️ 審查基準與工作區狀態（請先讀這段）

審查期間這個 worktree 有**別的 agent 併行寫入的未 commit 變更**（`git status` 顯示 `CHANGELOG.md`、
`core/designsystem/.../Formatting.kt`、`platform/capture/src/main/AndroidManifest.xml`、
`CaptureCoordinator.kt`、`SyntheticNotifications.kt` 共 5 檔 +28/−3，另有未追蹤的
`docs/reviews/2026-09-06-round12/`）。CHANGELOG 顯示那是一輪標為「Round-12 review fixes」的 lint 修正。

**本報告所有 file:line 引用一律以 `git show a3fd01b:<path>` 為準**，我已把工作區讀到的行號全部回頭核對過一遍。
特別提醒兩件事：

1. `CaptureCoordinator.kt` 在工作區有一行 `coldStartGapId = null`（維護開始時）**不在 a3fd01b 裡**，是那批未
   commit 變更加的。我初次讀檔時誤把它當成本 commit 的內容；已更正，並把對它的評估移到「其他觀察 4」。
   a3fd01b 之後的行號因此比工作區少 2。
2. 下面的 `./gradlew test` 是在**帶著那些未 commit 變更**的工作區跑的，不是純粹的 a3fd01b。差異是註解與
   lint 修正（`Formatting.kt`、`SyntheticNotifications.kt`），不影響測試數與結果，但如實記錄。

### 本地實測

| 驗證項目 | 指令 | 結果 |
| --- | --- | --- |
| 全部 JVM 測試 | `./gradlew test --console=plain` | BUILD SUCCESSFUL，exit 0 |
| 測試數統計（JUnit XML 彙總） | `find **/build/test-results -name 'TEST-*.xml'` | **196 tests / 0 failures / 0 errors / 0 skipped** |
| 分模組 | 同上 | model 5、parser 10、identity 5、reconcile 22、analytics 34、parsers:apps 43、app 5、crypto 3、**storage 12**、backup 24、**capture 22**、feature:analytics 8、feature:search 2、feature:conversation 1 |
| `SuppressionRuleTest` | `grep -c "    test("` | **4** |
| `BackupRoundTripTest`（instrumented，未執行） | `grep -c "@Test"` | **2**（原始碼存在，唯讀限制未跑） |
| en / zh-Hant 字串對照 | 自寫 Python 解析兩份 `strings.xml`（含 plurals） | **318 / 318 strings、1 / 1 plurals，零單邊** |

字串總數由 round-11 的 317 變成 318 是正確的：移除 `reminder_body`（−1）、新增 `backup_failed_maintenance`
與 `delete_everything_step_unexpected`（+2）。193 → 196 也對得上：capture +2、`SuppressionRuleTest` +1。
**brief 宣稱的 196 JVM / capture 22 / SuppressionRule 4 / backup instrumented 2 / 字串雙語對齊全部屬實。**

---

## Verdict

### **APPROVE WITH MINOR FIXES**

round-11 的 1 Critical、4 Important（agy 1 + subagent 3）全部修好，而且每一條都有**真斷言的測試**撐著，
不是只改註解。12 個 Minor 有 10 個完全修好、2 個部分修好。我沒有發現任何 Critical 或 Important 等級的回歸。

merge 前要處理的是**兩行與程式碼矛盾的文件**（Minor-1、Minor-2）——這正是專案 `CLAUDE.md` 明令
「Docs must not run ahead of the code」、且每一輪 reviewer 都會抓的那一類缺陷：

- `CHANGELOG.md:21` 仍寫「bounded buffer (64)」，但 `MAX_HELD = 256`，而**同一個檔案**的 `:30` 寫的是 256。
- `docs/SCOPE.md:76` 仍描述 #13 之前的行為（「`offer()` snapshots notifications of every package」），與現行程式碼相反。

外加 `BackupService.kt:130` 那句不實的 KDoc（Minor-3）。其餘 Minor 可以排進下一輪，不擋這次 merge。

---

## Round-11 verification table

### Gemini 3.8 Flash (high, via agy) — round 11

| # | 發現 | 是否修復 | 證據（行號以 a3fd01b 為準） |
| --- | --- | :---: | --- |
| **I-1** | 匯出把媒體解密／base64／串流包在單一 Room 交易內，擋住擷取寫入 | ⚠️ **大部分修復** | 媒體迴圈已移出交易：`BackupService.kt:180-187` 交易內只把 metadata 累積到 `mediaRows`，`:189-206` 在交易**外**做 `decryptFile` + `Base64` + `line()`。主要成本（數十 MB 檔案 IO + AES-GCM）確實出去了。**但** manifest、sources、conversation、message、revision 的 `json.encodeToString` 與 AEAD 串流寫入**仍在交易內**（`:141-187`），所以 KDoc `:130` 的「Only the row reads happen inside the transaction (milliseconds)」是**不實的**。詳見 Minor-3 |
| **M-2** | `openSettings` 的 `resolveActivity` 預檢在 Android 11+ 套件可見性下可能誤判 | ✅ 已修 | `ListenerAccess.kt:49-59` 移除 `continue`，只靠 `try/catch (ActivityNotFoundException / SecurityException)`；`:41-47` KDoc 把理由寫清楚了 |
| **M-3** | 金庫鎖定時每則通知各記一筆零碎 `COLD_START` gap | ✅ 已修（**限金庫可寫的情境**） | `CaptureCoordinator.kt:151` `@Volatile coldStartGapId`；`:439` `if (coldStartGapId != null) return` 每次 lock-out 只開一個 gap；`:441` 改用 `openGap`；`:362-364` `loadSourcePolicy()` 載入後關閉。測試 `CaptureCoordinatorTest.kt:603-614` 斷言兩則通知只有 **1 次** `openGap`、policy 載入後 **1 次** `closeOpenGaps` — 是真的 negative control。**注意**：金庫真的 `Locked` 時 `openGap` 本身也會拋例外，見「其他觀察 1」 |
| **M-4** | 空對話還原後 `lastActivityEpochMs` 退回 `createdAtEpochMs` | — | agy 自己標為「已知一致性權衡」的記錄項，無需動作；本輪未動，我同意 |

### Claude subagent — round 11

| # | 發現 | 是否修復 | 證據（行號以 a3fd01b 為準） |
| --- | --- | :---: | --- |
| **Critical-1** | 冷啟動緩衝溢位在成功路徑不記任何 gap（違反「gaps are shown, never hidden」） | ✅ 已修 | `CaptureCoordinator.kt:410` `releaseHeld()` 原子取出 `(items, dropped)`；`:411-415` `dropped > 0` 時 `recordGap(start, now, COLD_START, BOUNDED, now)`；`:798` `MAX_HELD` 64 → **256**。測試 `CaptureCoordinatorTest.kt:552-575`：300 則通知（半數為 `UNLISTED_PKG`）、`CompletableDeferred` 卡住金庫，斷言至少一次 `recordGap(COLD_START, BOUNDED)` 且**倖存 128 則全部是偶數 id（= 啟用來源）**。我核算過 256 上限下 45..300 的偶數正好 128，這個數字不是湊的 |
| **Important-1** | policy 已載入時 `coldStart()` 既不派送也不丟棄，項目永久卡在緩衝 | ✅ 已修（殘留窗口見「其他觀察 2、3」） | `CaptureCoordinator.kt:395` `if (!sourcesLoaded) guarded { loadSourcePolicy() } else releaseHeld()`（round-11 建議的單行修法）；競態 B 也修了：`:377-388` job 檢查與 `scope.launch` 移進 `synchronized(held)` 內。**`scope` 固定是 `Dispatchers.Default`（`:106`），`launch` 一定 dispatch 不會 inline 執行；`pipelineMutex` 是 suspending Mutex 不阻塞執行緒，所以在 `synchronized` 內 launch 沒有引入死結** |
| **Important-2** | 匯出交易擋住擷取，且擋住時走 `markJournalRetryable` 對不存在的列做 UPDATE → 靜默資料遺失 | ✅ 已修（兩半都修） | (a) 交易那半見 agy I-1。(b) 靜默遺失那半：`CaptureCoordinator.kt:602` `var journaled = false`、`:617` journal 成功後才設 true、`:630-640` 例外時分流——已 journal 走 `markJournalRetryable`，**未 journal 走 `diagnostic("JOURNAL_FAILED", …)` + `recordGap(EXACT)`**（`:637-638`）。測試 `CaptureCoordinatorTest.kt:578-591` 讓 `journal()` 拋 `IllegalStateException("database is locked")`，斷言 `recordGap(UNKNOWN, EXACT)` 恰 1 次、`diagnostic("JOURNAL_FAILED", …)` 恰 1 次、`markJournalRetryable` **恰 0 次**。舊測試 `:312-326` 也正確改成「接受之後才失敗」（改讓 `markJournal` 拋例外，`acceptedCount` 1 → 2），沒有把負面案例洗掉 |
| **Important-3** | 抑制 token 主鍵 `(scopeKey, fingerprint)` 只留最後一個 id，`SuppressionRule` 讓已刪內容復活 | ✅ 已修（採保守選項，並如實記錄） | `SuppressionRule.kt:24` `tokenSourceId != null && candidateSourceId != null && tokenSourceId == candidateSourceId -> true`——id 相同才直接抑制，**不同**時落到 post time。KDoc `:12-19` 與 `docs/SCOPE.md:48`、`CHANGELOG.md:22` 都補上了殘餘限制（同一 post 內同指紋的新訊息會被一起抑制）。`SuppressionRuleTest`「two different ids fall back to post time」兩個方向都斷言 |
| **Minor-1** | 被保留的通知用「釋放時間」而非「到達時間」 | ✅ 已修 | `CaptureCoordinator.kt:421` `snapshotFactory.create(h.sbn!!, h.origin, h.generation, h.heldAtEpochMs)`、`:427` `enqueue(captured, h.generation, h.heldAtEpochMs)` |
| **Minor-2** | `Held` 強引用 `StatusBarNotification` 繞過 bitmap 上限，要求一行說明 | ✅ 已修 | `CaptureCoordinator.kt:403-408` KDoc 說明 held 物件與 `onConnected` 的 resync list 是同一批，snapshot 之前不套 bitmap 上限。註記：上限同時從 64 提到 256（見「其他觀察 5」） |
| **Minor-3** | `openSettings()` 的 `resolveActivity` 門檻多餘；`settingsIntent()` 建兩次 Intent | ✅ 已修 | 同 agy M-2；`ListenerAccess.kt:40` `settingsIntent()` 現在只呼叫一次 `settingsIntents()`。副作用見 Minor-6 |
| **Minor-4** | reset 例外路徑把英文類別名塞進中文 snackbar | ✅ 已修 | `SettingsViewModel.kt:157` 改為 `"unexpected:" + simpleName`；`SettingsScreen.kt:116` `else -> stringResource(R.string.delete_everything_step_unexpected)`；兩語系字串齊 |
| **Minor-5** | `unviewedCount` 沒排除已無可見訊息的會話 | ✅ 已修 | `Daos.kt:173` 加上 `messageCount > 0` |
| **Minor-6** | `VaultRepositoryTest` 同義反覆的斷言 | ✅ 已修 | `VaultRepositoryTest.kt:46` 改為 `(h.state.value is VaultState.Ready) shouldBe true`，與 `:59` 的寫法統一 |
| **Minor-7** | `BackupRoundTripTest` 用全新 `VaultMaintenance()`，等於沒測到閘門 | ✅ 已修 | `BackupRoundTripTest.kt:63-64` 保留 `maintenance` 欄位共用同一個實例；`:142-147` 新測試在 `maintenance.exclusive {}` 內呼叫 `export`，斷言 `BackupResult.Failed(Reason.MAINTENANCE)` 且 `target.exists() shouldBe false`。這是真的閘門互動 |
| **Minor-8** | 匯出 manifest 的 media 計數明知不準 | ⚠️ **部分修復** | 新增 `BackupRecords.kt:102` `End(actual, skippedMedia = 0)` 與 `BackupService.kt:208` `line(BackupRecord.End(actual, skipped))`。但 round-11 提的兩個選項（把 `expected.media` 改成實際寫入數，**或**在 manifest 加 `skippedMedia` 欄位）都沒做——manifest 的 `expected.media` 仍是含跳過筆數的值（`BackupService.kt:148`），`BackupStager.kt:83` 仍要把 media 排除在 manifest 檢查外。詳見 Minor-7 |
| **Minor-9** | `VaultMaintenance` KDoc 把 backup 整體歸在 `work` | ✅ 已修 | `VaultMaintenance.kt:34-36` 改為「backup export 走 `work`；reset、backup import 本身是 `exclusive`」 |
| **Minor-10** | `SearchViewModel` 用 `va::class == vb::class` 比對金庫狀態 | ✅ 已修 | `SearchViewModel.kt:60` 改為 `va == vb`（`VaultState.Ready` 是 data class，會比較 `db` 參考） |
| **Minor-11** | repository 的搜尋游標無人使用 | ✅ 已修（文件化） | `SearchRepository.kt:17` `SearchPage` KDoc 明說「搜尋畫面目前只顯示第一頁（100 筆），不接續游標」 |
| **Minor-12** | `reminder_body` 成為死字串 | ✅ 已修 | 兩語系都移除；`grep -rn "reminder_body"`（排除 `reminder_body_count`）零命中 |

### 相容性與回歸專項（brief 點名的檢查）

| 檢查項 | 結果 |
| --- | --- |
| `synchronized(held)` 內 `scope.launch` 是否持鎖排程 | **安全**。`scope` 是 `CoroutineScope(SupervisorJob() + Dispatchers.Default + crashGuard)`（`:106`，無測試注入），`CoroutineStart.DEFAULT` 在非 Unconfined dispatcher 上必定 dispatch，不會 inline 執行協程體。反向也安全：`loadSourcePolicy` 在 `pipelineMutex` 下呼叫 `releaseHeld()` → `synchronized(held)`，而另一側在 `synchronized(held)` 內只做 `launch`；`Mutex` 是 suspend 不阻塞執行緒，兩者不構成 lock-order 死結 |
| `releaseHeld()` 在 pipeline lock 內 launch 記 gap | **安全**（fire-and-forget，被 launch 的協程不碰 `pipelineMutex`）。副作用只有 gap 與入列事件的先後順序不保證，屬觀感問題 |
| `coldStartGapId` 重設路徑 / 維護結束後是否有 stale id | **a3fd01b 沒有這個缺陷。** `onMaintenance`（`:471`）在結束時只設 `sourcesLoaded = false`（`:487`），不動 `coldStartGapId`——這是**對的**，因為 `:364` 關的是 `closeOpenGaps(now, GapReason.COLD_START)`，**依 reason 比對而非依存下來的 row id**，存的 id 實質上只當布林旗標用。所以：reset 之後（DB 被刪、gap 列消失）殘留的 id 會讓下一次 `loadSourcePolicy` 做一次空的 `closeOpenGaps`，無害；restore 之後（合併、gap 列存活）id 仍在，gap 會被正確關閉。唯一的殘留窗口是「reset 完成到下一次 `loadSourcePolicy` 之間，`dropHeld` 會因為死 id 而不開新 gap」，而 `sourcesLoaded = false` 保證下一個事件就會觸發 `loadSourcePolicy`，窗口極窄。**但工作區那批未 commit 變更改動了這一點，反而會引入問題，見「其他觀察 4」** |
| `journaled` 在 `ingest.journal` 回 false（重複 id）vs 拋例外 | **兩者都正確**。`:616` `if (!ingest.journal(...)) return` 提前返回、`journaled` 維持 false 但也不記 gap——重複 id 不是遺失，不該記。拋例外才走新的 gap 分支。`journal()` 的回傳只有 `insert(row) != -1L`（`IngestRepository.kt:86`），語意單純 |
| 新 gap 分支的例外安全 | `guarded { ingest.diagnostic(...); health.recordGap(...) }`：`IngestRepository.diagnostic`（`:143-146`）自己包了 `runCatching` 且只重拋 `CancellationException`，所以 diagnostic 失敗**不會**吃掉後面的 `recordGap`。順序正確 |
| `mediaRows` 記憶體用量 | metadata-only 沒錯（`MediaBlobEntity` 只有 id / messageId / 兩個檔名 / mimeType / byteCount / 寬高 / state / failureReason / 時間），但**筆數無上限**，見 Minor-4 |
| manifest media 計數 vs `End.skippedMedia` | 見 Minor-7 |
| `BackupStager` 對沒有 `skippedMedia` 的舊備份 | **相容**。`skippedMedia` 有預設值 `0`，且 `BackupService.kt:70` 是 `Json { ignoreUnknownKeys = true; encodeDefaults = true }`——舊檔缺欄位走預設、舊版讀新檔忽略未知欄位。`BackupStager.kt:82` 的計數檢查只看 `e.actual`（= 實際寫入的 media 數）與 staged 的 `media.size`，兩者一致，不受影響 |
| 抑制：ids 不同但 `tokenPostedAtEpochMs` 為 null | 回傳 `true`（抑制）。**這是行為變更且沒有測試覆蓋**，見 Minor-5 |
| 字串 en / zh-Hant parity | **318 / 318 + 1 plural，零單邊**（實測） |
| 文件 vs 程式碼計數 | 196 / 22 / 4 / 2 全部吻合；`docs/reviews/README.md` 與 `docs/zh-Hant/reviews/README.md` 都加了第 11 列且內容對稱；round-11 三份報告（agy、subagent、kimi-blocked）已逐字歸檔在 `docs/reviews/2026-09-06-round11/`。**但另有兩行文件與程式碼矛盾**，見 Minor-1、Minor-2 |

---

## Issues

### Critical

**無。**

### Important

**無。** round-11 的 Critical 與四個 Important 都有可驗證的程式碼改動與真斷言測試，沒有一項是靠改文件搪塞的。

### Minor

#### Minor-1 — `CHANGELOG.md` 自相矛盾：同一檔案一處寫緩衝 64、一處寫 256

**位置**：`CHANGELOG.md:21`（「held in a bounded buffer (64)」）vs `CHANGELOG.md:30`（本輪新增條目「buffer 256」）、`CaptureCoordinator.kt:798`（`MAX_HELD = 256`）

**故障情境**：下一位 reviewer 或維護者讀 #13 的條目，得到「緩衝 64」的錯誤心智模型，據此推算冷啟動 resync
的丟棄量會差 4 倍。

**為什麼重要**：專案 `CLAUDE.md` 把「Docs must not run ahead of the code」列為硬規則，並註明
「reviewers flagged 'docs ahead of code' in every round」。這是**同一個檔案內部**的矛盾，成本最低、最該在
merge 前修掉。

**修法**：`CHANGELOG.md:21` 的 `(64)` 改成 `(256)`。

---

#### Minor-2 — `docs/SCOPE.md:76` 仍描述 #13 之前的冷啟動行為

**位置**：`docs/SCOPE.md:76`（在「Known defects and rough edges found during device verification」段）

```
- During cold start, until the first source list has loaded, `offer()` snapshots notifications of
  every package and drops the non-sources in `process()`; content is never persisted, but the
  bounded bitmap budget can be consumed by non-source notifications.
```

**故障情境**：#13 的整個重點就是**不再**這樣做——`CaptureCoordinator.kt:524` 現在在 `!sourcesLoaded` 時
`hold(Held(sbn, null, origin, gen, now))` 並 `return`，一個 byte 都不讀；`releaseHeld()`（`:409`）才在 policy
已知後過濾並 snapshot。這行文字描述的是相反的行為，而且它坐在「已知缺陷」清單裡，等於對讀者宣告一個
已經不存在的缺陷。

**為什麼重要**：同 Minor-1。這行在 `825d708` 就已經過期（**不是本輪的回歸**），但本 commit 動了
`docs/SCOPE.md` 卻沒順手清掉，round-11 也漏了。

**修法**：刪除該行，或改寫為現行行為（「policy 未知前只保留框架物件、上限 256、溢位與逾時都記
`COLD_START` gap」）。註：`docs/zh-Hant/` 底下**沒有** `SCOPE.md`，所以這裡沒有雙語 parity 問題。

---

#### Minor-3 — `writeRecords` 的 KDoc 宣稱「只有 row reads 在交易內（milliseconds）」，與程式碼不符

**位置**：`BackupService.kt:129-132`（KDoc）vs `:141-187`（交易主體）

**故障情境**：交易內實際還有 manifest + sources + 每一頁 conversation / message / revision 的
`json.encodeToString(...)`，加上經 `BufferedWriter` → Tink streaming AEAD → `FileOutputStream` 的實體寫入
（`line()` 定義在 `:135-138`，在交易內被呼叫）。對一個累積數十萬則訊息的金庫，這仍然是秒級而非毫秒級，
而 Room 的 `withTransaction` 在 BEGIN 就取得寫入鎖。

**為什麼重要**：agy I-1 的主要成本（媒體檔案 IO + AES-GCM + base64）確實出去了，剩下的是 CPU／記憶體
密集而非磁碟密集，所以我評 Minor 而不是 Important；而且 round-11 Important-2 真正致命的那一半（等鎖逾時
→ `markJournalRetryable` 對不存在的列做 UPDATE → 靜默遺失）已經由 `journaled` 分流徹底修好，即使真被擋
到逾時，現在也會留下 `JOURNAL_FAILED` 診斷與一筆精確 gap。**但 KDoc 這句話會讓下一輪 reviewer 誤判這個
風險已經完全退場。**

**修法**：把 KDoc 改成誠實的敘述（「媒體解密與串流在交易外；其餘資料表仍在單一讀取交易內，以保證
manifest 與列的一致性」），並考慮把剩下的資料表也改成 Minor-4 的短交易分頁。

---

#### Minor-4 — `mediaRows` 是無上限的記憶體清單，媒體表的分頁在這裡被抵消了

**位置**：`BackupService.kt:140`（`val mediaRows = ArrayList<MediaBlobEntity>()`）、`:180-186`（分頁後全部累積）

**故障情境**：`exportPage` 仍以 500 筆為一頁讀，但每一頁都 `mediaRows += page`，最後整張表的 metadata 都在
heap 上。`MediaBlobEntity` 有兩個檔名字串加 `mimeType` 與 `failureReason`，一列粗估 200–300 bytes；十萬張圖
的金庫就是 20–30 MB，而這正是 KDoc `:127-128` 自己說「no table is ever held in memory as a whole」要避免的事。
`BackupLimits.MAX_MEDIA_BYTES` 只限制單一檔案大小，對筆數沒有上限。

**為什麼重要**：不是資料正確性問題，但它把「絕不整表進記憶體」這條既有紀律在媒體表上破了例，而媒體表
偏偏是筆數最容易失控的一張。

**修法**：媒體改成在交易**外**用短的讀取交易逐頁取得（keyset 已經天生適合），每取一頁就立刻解密串流再取
下一頁。媒體的計數一致性本來就不靠單一交易保證（`End.actual` 才是權威，`BackupStager.kt:83` 也刻意把 media
排除在 manifest 檢查外），所以這樣改不會弱化任何既有保證。

---

#### Minor-5 — 「ids 不同 + 某一側沒有 post time」的行為由 false 翻成 true，且沒有測試

**位置**：`SuppressionRule.kt:24-26`

**故障情境**：`applies("m2", null, "m1", 1_000L)`——修改前兩側都有 id 就直接比對 → `false`（存下來）；
修改後落到第二條 `tokenPostedAtEpochMs == null` → `true`（抑制）。`applies("m2", 1_000L, "m1", null)` 同理翻轉。
這是**保守方向**（寧可不寫回），與 KDoc「Without a post time on either side the token applies」一致，我認為是
刻意的；但 `SuppressionRuleTest` 的第四則只覆蓋 `(null, null, null, 5_000L)` 與 `(null, 5_000L, null, null)`，
兩側都沒有 id，剛好避開了這個新分支。

**為什麼重要**：這是本輪唯一一個「行為變了但測試沒跟上」的點。id 不同又缺 post time 的組合在
`timestampQuality != SOURCE_MESSAGE` 的來源上並不罕見。

**修法**：`SuppressionRuleTest` 第四則加兩行斷言：

```kotlin
SuppressionRule.applies("m2", null, "m1", 1_000L) shouldBe true
SuppressionRule.applies("m2", 1_000L, "m1", null) shouldBe true
```

---

#### Minor-6 — `settingsIntent()` 全鏈路是死程式碼，且現在比修改前更弱

**位置**：`ListenerAccess.kt:40`、`HealthViewModel.kt:99`、`InboxViewModel.kt:111`、`OnboardingViewModel.kt:89`

**故障情境**：三個畫面實際都走 `openListenerSettings(...)` → `ListenerAccess.openSettings()`；
`grep -rn "settingsIntent|listenerSettingsIntent"` 顯示三個 ViewModel 的包裝函式**沒有任何 UI 呼叫端**。
同時 `settingsIntent()` 從「第一個 resolve 得到的」改成「無條件回傳最具體的那個」——在 API 30+ 就是
`ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS`，OEM 沒有這個 activity 時直接 `startActivity` 會拋
`ActivityNotFoundException`。目前沒有呼叫端，所以**不會當掉**，但它是一個留給未來的陷阱。

（我確認過沒有 minSdk 問題：`settingsIntents()` 的 detail intent 有 `Build.VERSION.SDK_INT >= 30` 保護，
API 26–29 的 `first()` 是 `ACTION_NOTIFICATION_LISTENER_SETTINGS`。）

**為什麼重要**：與 round-11 Minor-12（移除死字串 `reminder_body`）同一個標準——既然那條被接受了，四層死
API 更值得一起清掉。

**修法**：刪掉 `ListenerAccess.settingsIntent()` 與三個 ViewModel 的包裝；真要保留就在 KDoc 明說呼叫端
**必須**自己 `try/catch`。

---

#### Minor-7 — manifest 的 media 計數仍是明知不符的數字（round-11 Minor-8 只做了一半）

**位置**：`BackupService.kt:148`（`media = db.mediaDao().exportCount(now)`）、`BackupStager.kt:83`（`m.expected.copy(media = actual.media) != actual`）

**故障情境**：`End.skippedMedia` 現在把跳過數帶出去了（好事，UI 也接上了），但 manifest 對外宣告的
「這份備份有幾筆 media」仍然是含跳過筆數的值，`BackupStager` 因此還是得把 media 從 manifest 檢查裡挖掉。
round-11 提的兩個選項都沒採用。

**為什麼重要**：純粹的資料自洽問題，不影響還原正確性（`End.actual` 是權威，串流 AEAD 保證完整性）。記錄用。

**修法**：既然 `End` 已經有 `skippedMedia`，最省事的是在 `Manifest` 也加一個同名欄位（同樣給預設值以保持
舊檔相容），讓 manifest 說的話與檔案內容一致。

---

#### Minor-8 — 新記下的 `COLD_START` gap 起點取自「倖存」項目，而被丟棄的一定更早

**位置**：`CaptureCoordinator.kt:412`（`val start = items.minOfOrNull { it.heldAtEpochMs }`，本 commit 新增）、
`:379-382`（`removeFirst()` 丟最舊的）；`dropHeld()` 的 `:440` 有同樣的算法

**故障情境**：溢位時 `hold()` 丟掉的永遠是**最舊**的那一筆，所以 `items` 裡最早的時間戳一定**晚於**所有被
丟掉的項目。於是 `releaseHeld()` 記下的 `[items.min .. now]` 這個 BOUNDED 區間，反而**不包含**真正發生
遺失的時段——使用者在健康頁看到的缺口視窗，指向的是一段其實什麼都沒丟的時間。

**為什麼重要**：gap 有被記下來，Critical-1 的硬規則沒有再被違反，所以不是 Important。但區間被標成
`BOUNDED`（= 我們知道邊界）卻指向錯的視窗，對一個以「誠實標示」為賣點的 App 是不必要的失真。實務上
冷啟動 resync 是一個緊密迴圈，被丟的與倖存的往往只差毫秒，影響很小——這是我評 Minor 的主要理由。
註：這段記錄邏輯是本 commit 新增的（`:410-415`），屬於本輪範圍內的發現，不是既有問題。

**修法**：在 `hold()` 驅逐時記下 `heldDroppedFirstAtEpochMs`（只在第一次驅逐時寫入），`releaseHeld()` 與
`dropHeld()` 優先用它當 `start`，取不到才退回 `items.minOfOrNull { … }`；釋放後與 `heldDropped` 一起歸零。

---

## 其他觀察

1. **（既有缺陷，非本輪回歸）金庫真的 `Locked` 時，冷啟動的丟棄什麼都不會留下。**
   `HealthRepository.openGap` 與 `sources.sources()` 走的是同一個 `holder.db()`。金庫 `Locked` 時：
   `loadSourcePolicy()` 拋 → `guarded` 吞掉 → `withTimeoutOrNull` 立刻回 `false` → `dropHeld()` →
   `openGap`（`:441`）**也**拋 → `guarded` 吞掉 → `coldStartGapId` 維持 null。結果是這段期間的通知被丟棄，
   **沒有 gap、沒有診斷、`_status` 的 `vaultLocked` / `DEGRADED` 也不會被設**（`process()` 裡那套
   `vaultGapOpen` 機制根本走不到，因為這些事件從未進 queue）。
   `CaptureCoordinatorTest`「when the vault does not open…」的 mock `health` 是會成功的，所以測試編碼的是
   一個現實中不成立的模型。這在 `825d708`（#13 落地時）就存在，**不改變本輪判定**，但它與「gaps are shown,
   never hidden」直接衝突，建議開一張 issue。
   修法方向：在記憶體裡記住 `firstDropAtEpochMs`，等金庫可寫時（`:209` 附近那個 observer）補一筆
   `recordGap(first, now, COLD_START, BOUNDED)`；同時在這條路徑上把 `vaultLocked = true` 設起來。

2. **（既有分支，round-11 Important-1 點名過）`releaseHeld()` 的 `h.generation != activeGeneration || paused` 仍是靜默 `continue`。**
   `CaptureCoordinator.kt:417`。當一次 15 秒內完成的維護或使用者暫停剛好落在冷啟動視窗中：項目以 G1 被保留 →
   維護把 generation 轉成 G2 → `loadSourcePolicy()` → `releaseHeld()` → 全部 `continue`，而 `dropHeld()` 因為
   `loaded == true` 不會執行。機率很低，但這條分支確實還是「丟了不記」。列在這裡是為了避免上面的驗證表被
   讀成「Important-1 已 100% 關閉」。

3. **殘留競態（比 round-11 窄很多）**：`hold()` 在 `coldStartJob?.isActive == true` 時不另起 job（`:386`）。
   若某個 job 已經跑完 `releaseHeld()` 但協程尚未結束，此刻加入的項目就沒有任何 job 會處理它。
   由於 `offer()` 在 `sourcesLoaded == true` 之後不再呼叫 `hold()`，這個窗口只有微秒級，且後果通常是
   「延到下一次 `loadSourcePolicy()` 才派送」而非遺失。建議在 `coldStart()` 結尾補一次
   `synchronized(held) { held.isNotEmpty() }` 的檢查並重跑 `releaseHeld()`，把迴圈收乾淨。

4. **⚠️ 對工作區那批未 commit 變更的警告：`onMaintenance(true)` 裡新加的 `coldStartGapId = null` 會讓
   restore 之後留下一筆永遠關不掉的 `COLD_START` gap。**
   這行**不在 a3fd01b**，是併行寫入的「Round-12 review fixes」加的，註解理由是「金庫可能即將消失」。
   這個理由對 **reset** 成立（整個 DB 被刪，gap 列跟著消失），但對 **restore（import）不成立**——
   `BackupService.kt:294` 的 `applyStaged` 只有 `db.withTransaction`，**沒有 `clearAllTables`**，是合併不是清空，
   `gap_interval` 的既有列原封不動存活。流程：冷啟動開了一筆 gap（`coldStartGapId` 記著）→ 使用者還原備份 →
   新程式碼把 `coldStartGapId` 設 null → 維護結束後 `sourcesLoaded = false` → 下一次 `loadSourcePolicy()` 讀到
   `gap == null` → **跳過 `closeOpenGaps`** → 那筆 gap 從此永遠 open，`HealthScreen.kt:237` 會一直顯示
   `health_gap_open`，告訴使用者擷取仍有缺口。
   如前面驗證表所述，a3fd01b 原本沒有這個問題，因為 `closeOpenGaps` 是**依 reason 比對**、存下來的 id 只當
   布林旗標，reset 後的殘留 id 頂多造成一次無害的空查詢。
   **建議**：不要加這行；若要加，就把 `:364` 的條件拿掉，讓維護結束後的第一次 `loadSourcePolicy()` 無條件
   呼叫 `closeOpenGaps(now, GapReason.COLD_START)`（本身冪等且只掃 open 的列）。

5. **`MAX_HELD` 64 → 256 的記憶體面**：`Held` 持有的 `StatusBarNotification` 強引用上限跟著變成 4 倍，每個都
   可能帶 BigPicture / LargeIcon 的 `Bitmap`，最長 15 秒不放。`onConnected` 的 resync list 本來就同時持有這批
   物件，所以 resync 路徑上是零增量；但「金庫鎖住期間持續有新通知進來」的路徑是真的多了 4 倍上限。
   在 `docs/COMPATIBILITY.md` 剛加上低記憶體章節的背景下值得留一筆，風險我評估為可接受。

6. **值得肯定的地方**：
   - 每一個 round-11 發現都配了**會失敗的測試**。溢位測試的 128 這個數字要 `MAX_HELD = 256` 才成立，改壞
     就會紅；`JOURNAL_FAILED` 測試同時斷言 `markJournalRetryable` **恰 0 次**，是真正的 negative control；
     M-3 的測試斷言「兩則通知只開一次 gap」而非只檢查「有開 gap」。這是這輪最扎實的部分。
   - 舊測試「an ordinary pipeline failure…」被**改成**測 journal 之後的失敗（而不是刪掉），新測試補上
     journal 之前的失敗，兩條路徑都有守衛。
   - `BackupResult.Reason.MAINTENANCE` 是 exhaustive `when`（`SettingsScreen.kt:423`）由編譯器守住，兩語系
     字串齊全，`VAULT_UNAVAILABLE` 與「正在重設／還原」終於分得開。
   - `docs/reviews/README.md` 與 zh 版第 11 列的敘述與實際發現逐條對得上，沒有美化 verdict。

---

## Assessment

**Ready to merge? With fixes.**

round-11 的 1 Critical 與 4 Important 都以可驗證的程式碼改動加真斷言測試關閉，196 個 JVM 測試全綠、字串
雙語 318/318 對齊、文件計數與程式碼一致，我沒有發現任何 Critical 或 Important 等級的回歸。
merge 前只需修掉三處文字：`CHANGELOG.md:21` 的「(64)」、`docs/SCOPE.md:76` 過期的冷啟動描述、
`BackupService.kt:130` 不實的 KDoc。其餘 Minor、以及「其他觀察 1」的既有缺陷建議開 issue 排進下一輪。
另請留意「其他觀察 4」——工作區裡尚未 commit 的那行 `coldStartGapId = null` 會在 restore 路徑上引入一筆
永遠關不掉的 gap，**建議在合併那批變更前先處理**。
