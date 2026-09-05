# QuietInbox v0.1 vertical slice — Fable 5 審查報告(pre-push dual-review)

- 審查者:Claude Fable 5(READ-ONLY,依 `docs/reviews/2026-09-06-round1/brief.md`)
- 審查對象:branch `main`,HEAD = `4e86698`(fix: system bar icon contrast …)
- 範圍:11,408 行 Kotlin(不含 build)、spec `QuietInbox_開源專案完整計劃.md` §2/§5–§9/§11–§13、docs/SCOPE.md 對照
- 實際執行:全部 JVM 測試(88 個,全綠;SCOPE 宣稱 74 為保守值)、字串 parity diff、manifest / ProGuard / CI 靜態檢查。未跑 instrumented、未安裝裝置(依 brief)。

## Verdict:**REQUEST CHANGES**

三個 Critical 全部是「程式碼與 spec 硬規則直接矛盾」或「可靜默毀損使用者資料」等級,且各自有明確、局部的修法;修完即可轉 APPROVE WITH MINOR FIXES。整體架構品質、隱私紀律(無正文 log、無違禁 API、無網路權限)與測試文化明顯高於一般 v0.1,值得先說在前面。

---

## Critical(push 前必修)

### C1. 刪除整個會話後,活動通知重播會復活已刪內容 — 違反硬規則 5(spec §7.3)

- 位置:`platform/storage/.../repo/InboxRepository.kt:82-88`(`deleteConversation`)+ `platform/storage/.../repo/IngestRepository.kt:153-174, 187`(commit 重建會話與 suppression 檢查)
- 問題:suppression token 以 `(conversationId, fingerprint)` 為 key(`DeletionSuppressionEntity`,Entities.kt:190-195)。`deleteConversation` 寫入 token 後刪除 conversation row;下次 reconnect 的 ACTIVE_RESYNC / journal replay 進到 `IngestRepository.commit` 時 `convDao.find(...)` 找不到 → **插入新的 conversation(新的自增 id)**,接著 `isSuppressed(新id, fp)` 永遠查不到舊 token → 訊息重新入庫。單則、無來源時間戳的通知(BigText/Inbox 形態,即 LINE/WhatsApp 關預覽或一般 1:1 通知最常見的形態)會以 `AMBIGUOUS_REPEAT` 之姿把**正文原樣復活**;多則 MessagingStyle 窗口雖不復活正文,也會復活會話外殼(標題、identityKey)。spec §7.3 與 brief 硬規則 5 明文:「使用者刪除訊息後,活動通知重播不能把內容復活」。`deleteMessages`(保留會話的部分刪除)沒有這個問題,因為 conversationId 還在。
- 具體修法(擇一或並用):
  1. suppression 改以 scope 為 key:`(packageName, profileKey, accountKey, identityKey, fingerprint)`,commit 時用 identity 查,不用資料庫自增 id;
  2. `deleteConversation` 同時刪除該 stream 的 checkpoint 並保留 conversation row 為 tombstone(messageCount=0、hidden),讓 id 穩定;
  3. 最少限度:`deleteConversation` 時把 suppression 改寫成與 `IngestRepository.commit` 查得到的 key 一致的任何形式,並補一個「刪會話 → reconnect resync → 不得復活」的 instrumented/unit 測試。

### C2. 暫停(pause)沒有切換 generation,queue 中事件照樣落盤 — 違反硬規則 4(spec §5),且 README 宣稱與程式碼不符

- 位置:`platform/capture/.../CaptureCoordinator.kt:181-200`(`setPaused`)、`:245-251`(commit fence 只比對 generation)
- 問題:spec §5:「撤權/暫停/刪除來源時切換 generation、取消媒體工作、關閉接受新事件;已排隊工作提交前再檢查」。brief 硬規則 4 同義。現況:`setPaused(true)` 只設 `paused` 旗標擋**新** offer;`activeGeneration` 不變,所以已在 queue 裡的事件通過 `item.generation != activeGeneration` 檢查照常 journal + commit。「刪除來源」同理:`enabledPackages` 更新只影響新 offer,已排隊事件照常落盤;`mediaCopier.copyPending` 已啟動的工作在撤權後也不會被取消。README.md「管線」一節寫「撤權/暫停切換 generation token,排隊中的事件在提交前再檢查(commit fence)」——**文件宣稱了程式碼沒有做的事**,同時觸犯 docs-honesty(§9 維度)。
- 具體修法:`setPaused(true)` 與來源刪除路徑一併 `activeGeneration = UUID.randomUUID()`(或置 null 再於 resume 時重建),使 fence 生效;恢復時重新產生 generation。撤權/暫停時對 `scope` 內未完成的 media job 做 cancel(給 `copyPending` 傳入 generation 並在寫檔前複查,或持有 Job 引用統一取消)。補測試:「pause 之後 queue 內事件必須 dropped(droppedAfterRevoke++),不得出現在 DB」。

### C3. 備份還原後的媒體會在 12 小時內被 RetentionWorker 當孤兒刪光

- 位置:`platform/backup/.../BackupService.kt:265`(還原時 `MediaBlobEntity(messageId = null, …)`)+ `platform/storage/.../db/Daos.kt:254-255`(`orphans()`:`WHERE messageId NOT IN (SELECT id FROM message) OR messageId IS NULL`)+ `RetentionWorker.kt:58-63`(刪檔 + 刪 row)
- 問題:還原路徑先插 blob(此時還不知道新 message id)再插 message 並把 `mediaBlobId` 指向 blob,但 **blob 的 `messageId` 永遠留 null**。`orphans()` 把 `messageId IS NULL` 一律視為孤兒;`RetentionWorker` 是 12 小時週期 → 還原成功後最晚半天,所有還原媒體的**加密檔案與 blob row 被物理刪除**,而 message row 仍是 `LOCAL_COPY` + 指向已死的 `mediaBlobId`,UI `MediaCopier.load()` 回 null,呈現壞圖。這是靜默的使用者資料毀損,且會讓「備份/還原」功能在實機驗證時看起來時好時壞(12h 內看是好的)。SCOPE 已宣告 backup「not device-verified」,但這是邏輯層可證的缺陷,不屬於已宣告缺口的重複回報。
- 具體修法:插入 message 拿到 `newId` 後補 `UPDATE media_blob SET messageId = :newId WHERE id = :blobId`(加一個 DAO 方法),或先插 message(mediaState 暫置 PENDING)再插 blob 後 `setMedia`。同時建議 `orphans()` 拿掉 `messageId IS NULL` 分支或給 null-messageId blob 一個寬限期,避免同類 footgun 再現。

---

## Important(push 前應修)

### I1. `DatabaseHolder.db()` 在「Opening → Locked」時永久懸掛,pipeline 靜默卡死

- 位置:`platform/storage/.../db/DatabaseHolder.kt:57-66`(63 行只等 `Ready`)
- 問題:呼叫時若 state 是 `Opening`,之後 `open()` 落到 `Locked`,`filterIsInstance<VaultState.Ready>().first()` 永不返回:queue 消費者 coroutine 掛在 `ingest.journal` → 佇列塞滿 512 → overflow;而且每次 overflow 都 `scope.launch { recordGap }`,這些 coroutine 也全部掛在 `db()` 上堆積。開機即 Locked(Keystore 尚不可用/毀損)的裝置會呈現「不 crash、不記 gap、什麼都不動」。
- 修法:等待「第一個非 Opening 狀態」——`state.first { it !is VaultState.Opening }`,Locked 就丟 `VaultUnavailableException`(呼叫端已有 catch)。

### I2. Process 重啟後的靜默漏抓:`enabledPackages` 未載入前,live/resync 事件被直接丟棄

- 位置:`CaptureCoordinator.kt:100`(初值 `emptySet()`)、`:109-113`(靠 vault Ready 後的 flow 才填值)、`:217-223`(`isCapturable` 查空集合)、`:227-233`(offer 直接 return,無 gap、無診斷)
- 問題:listener rebind 觸發 process 冷啟時,`onConnected` 的 ACTIVE_RESYNC 與最初幾秒的 live 事件幾乎必然跑在 SQLCipher 開庫 + `observeSources` 首次發射之前 → 真實來源的通知全部被 `isCapturable == false` 靜默丟掉,連 gap 都沒記(違反 §2「不可觀測損失只能標未知」的精神——這裡是可觀測卻沒記)。作者的裝置驗證用合成通知(own package + marker,不走 enabledPackages),所以測不到。
- 修法:在 `offer` 階段不過濾(或只過濾自己 package 的無標記通知),把「來源是否啟用」延後到 `process()`(消費側本來就會等 db);或 `onConnected` 的 resync 先 `sources.sources()` await 完成再 offer。

### I3. reconnect resync 會把每個單則通知重複記成新的 `AMBIGUOUS_REPEAT` row,且與 `closeAllWindows` 存在競態

- 位置:`CaptureCoordinator.kt:135-147`(關窗與 resync offer 在兩個獨立 coroutine,順序不定)、`core/reconcile/Reconciler.kt:143-159`(`samePost` 要求 `!previous.closed`;closed + 同 key + 單則無來源時戳 → `AmbiguousRepeat`)
- 問題:每次 reconnect / 重開機(`captureActiveOnConnect=true` 為預設)先 `closeAllWindows` 再 resync,同一則仍掛在通知列的 BigText/Inbox 單則通知會被判 ambiguous → **每次重啟都新插一筆 AMBIGUOUS_REPEAT row**,`ambiguousCount` 隨開機次數無上限成長,統計與 UI 的「身分不明觀測」數被灌水。而若 resync offer 恰好先於 `closeAllWindows` 提交,同一情境又變成 REPOST(不插 row)——行為由競態決定。ReconcilerTest 只測了「不同 key + closed」的真歧義(64-71 行),沒測「同 key + closed(resync)」。
- 修法:把 `origin`(ACTIVE_RESYNC/REPLAY)傳進 reconcile 或在 `processJournaled` 判斷:同 `notificationKey` 且 `postedAtEpochMs` 與 checkpoint 記錄一致者視為 REPOST;並將 `onConnected` 內 closeAllWindows 與 resync 排進同一個 coroutine 保證順序。

### I4. commit 層的 checkpoint-loss guard 壓掉同一窗口內的同值 multiplicity — 與 spec §7.2 及自家 Reconciler 測試矛盾

- 位置:`IngestRepository.kt:191-201`
- 問題:guard 條件是 `Decision.New && !confirmedById && NO_PREVIOUS_WINDOW`,對**每一筆** New 都查 `findIdByFingerprint`。同一窗口第一則插入後,第二則同 fingerprint(同 sender、同 body、皆無來源時戳——Inbox/textLines 形態必然如此)在**同一交易內**查到剛插的 row → 轉成 link,不再插入。結果:全新會話首個窗口 `[好, 好]` 只存一則。spec §7.2:「有多訊息窗口……保留同值 multiplicity」;ReconcilerTest「multiplicity inside one window is preserved」在 reconciler 層通過,但被 commit 層默默抵銷——單元測試盲區。
- 修法:guard 只對「本交易開始前已存在」的訊息生效:交易開頭先為整個 batch 做一次 fingerprint 預查(集合),迴圈中只比對預查結果,不查即時表;或 guard 僅套用在 `snapshot.origin == REPLAY/ACTIVE_RESYNC`。

### I5. replayJournal 與 live queue 併發跑同一條 stream,checkpoint 讀取在交易外 → 解鎖瞬間可能重複入庫

- 位置:`CaptureCoordinator.kt:108`(queue 消費者)與 `:114-119, 310-321`(vault Ready 觸發 replay,獨立 coroutine)、`processJournaled` 中 `ingest.checkpoint(...)`(交易外讀)
- 問題:vault 轉 Ready 時,live 事件 journal 成 PENDING 後、`commit` 設 COMMITTED 前,replay 的 `pendingJournal()` 可撈到同一筆並再跑一次 `processJournaled`;兩條路徑都在**交易外**讀同一 checkpoint,再各自 commit → 同窗訊息重複插入或 checkpoint 互相覆蓋。機率低但真實,且正好發生在最難重現的解鎖瞬間。
- 修法:replay 與 live 共用同一序列化執行器(把 replay 的每筆丟進同一個 queue/actor);或把 checkpoint 讀取移進 `commit` 交易內。

### I6. UI 鎖冷啟動 bypass:`enabled` 異步載入,首個 `onForeground` 幾乎必然先跑

- 位置:`app/.../ui/LockController.kt:35-59`
- 問題:`enabled` 由 DataStore flow 異步填入;冷啟動 `MainActivity.onStart → lock.onForeground()` 時 `enabled` 仍為 false → 不上鎖,之後 collect 補上 `enabled=true` 也不會回頭鎖。已開啟 App 鎖的使用者,殺掉 process 再開 = 直接看到收件匣。SCOPE 只宣告「biometric flow not exercised」,未涵蓋此邏輯洞。
- 修法:`uiLockEnabled` 讀取完成前視為「未知」,先鎖(`_locked` 初值改 true,首次 settings 發射後若 disabled 再解);或 `onForeground` 掛起等第一次 settings 發射。

### I7. 搜尋:≥4 字母的拉丁子字串查詢與單一 CJK 字查詢必然 0 結果

- 位置:`core/model/.../Normalization.kt:62-63`(query 也走 `tokens()`,整字 token 一併加入)+ `platform/storage/.../SearchDao.search`(`HAVING COUNT(DISTINCT token) = :tokenCount` 要求全部命中)
- 問題:body「hello」索引 token 為 {hello, hel, ell, llo};查「hell」產生 {hell, hel, ell},其中 `hell` 不在索引 → 0 結果。同理查「meet」找不到「meeting」。CJK 單字查詢(「開」)也因長 run 只索引 bigram 而 0 結果。現行測試只覆蓋「開會」與「hel」(≤3 字),剛好繞過。README 宣稱「ASCII 詞/3-gram」字面上沒撒謊,但使用者觀感就是「搜尋壞掉」。
- 修法:query 端與 index 端分開 tokenize:query 的拉丁詞 ≥3 時**只**取 3-gram(不加整字 token);CJK 單字查詢 fallback 到 `LIKE '%字%'` 參數化查詢或對每個 CJK 字補單字 token(索引體積換功能)。

### I8. `WrappedSecretFile.writeAtomically` 無 fsync、fallback 路徑非原子 — 首次建 key 的窗口內斷電即永久鎖庫

- 位置:`platform/crypto/.../WrappedSecretFile.kt:58-66`
- 問題:`tmp.writeBytes + renameTo` 未對檔案與目錄 fsync;rename 失敗時 fallback 直接 `file.writeBytes`(半寫風險)。時序:`getOrCreate()` 回傳 secret → SQLCipher 立即用它建庫;若此後掉電而 key 檔內容未落盤,重啟後 vault 存在但 key 檔空/毀 → `Tampered` → 永久 Locked,而使用者從沒看過 recovery 流程(db key 本身不在備份內)。機率低、後果最高。
- 修法:用 `FileOutputStream` 寫入後 `fd.sync()` 再 rename,rename 後對父目錄 fsync(或改用 `AtomicFile`);移除非原子 fallback。

### I9. 備份 export 註解宣稱「one read transaction」但實際沒有交易 — 一致性與 docs-honesty

- 位置:`platform/backup/.../BackupService.kt:100-106`
- 問題:五個 `allForExport()` 是獨立查詢,期間 capture 照常寫入:之後新增的 conversation 的 messages 會被匯出成「孤兒訊息」(import 時 `convMap[m.conversationId] ?: continue` 靜默丟棄)。counts 內部自洽所以驗證不會抓到。註解與行為不符。
- 修法:包 `db.withTransaction { }`(Room 交易內做全部讀取)或至少改註解並在 export 前暫停 pipeline;建議前者,順帶把 media 解密移出交易。

### I10. 備份 import 對每則訊息重查整個會話 → O(N²),大庫還原會在單一交易內耗數分鐘

- 位置:`BackupService.kt:254`(迴圈內 `forConversation(cid)` 全撈 + 建 set)
- 修法:每個 cid 只查一次,維護 `HashMap<Long, MutableSet<String>>`,插入後把新 fingerprint 加進 set。

### I11. Vault Locked 期間的事件丟失無任何缺口紀錄

- 位置:`CaptureCoordinator.kt:258-259`(catch `VaultUnavailableException` 只改 status)
- 問題:journal 失敗即事件丟失,但 gap 沒記(DB 鎖著也寫不進去),解鎖後健康頁看不到這段損失,違反「可能中斷區間」可見性要求(spec §2/§8)。
- 修法:在記憶體累積 `lockedGapStart`,vault 恢復 Ready 時補寫一筆 `GapReason.VAULT_LOCKED`(bounded)。

---

## Minor / nitpicks

1. **Reconciler 混合 id/無 id 窗口的對齊不對稱**(`Reconciler.kt:124-130`):`prevFps` 含全部項目、`newFps` 只含無 id 項;prev 內夾雜 id 項會使 suffix 對齊失敗 → 無 id 項全判 New(重複入庫)。目前所有 adapter 都不產 sourceMessageId,故未觸發;brief 明點此項,建議 prev 也過濾成無 id 序列再對齊,並補測試。
2. **CI 沒跑 `:app:testDebugUnitTest`**(`.github/workflows/ci.yml` jvm-tests 清單):`ReminderSchedulerTest` 只在本機跑得到。另 `lint { abortOnError = false }`(app/build.gradle.kts)讓 CI 的 lintDebug 形同虛設。
3. **Export 失敗清理用 `contentResolver.delete(target)`**(`BackupService.kt:88`):SAF document URI 多半不支援,會留下半截密文檔;應改 `DocumentsContract.deleteDocument` 並容忍失敗。
4. **`MediaCopier.copyUri` 的 `catch (e: Exception)` 吞掉 `TimeoutCancellationException` 之外的一般取消**(`MediaCopier.kt:74-80`):scope 取消會被誤標 FAILED;`CancellationException` 應 rethrow(timeout 單獨接)。
5. **佇列可持有最多 512 個 `Bitmap`**(`Limits.MAX_QUEUE_DEPTH` + `CapturedNotification.bitmap`):BigPicture 通知風暴時有 OOM 風險;建議入隊前就把 bitmap 壓縮成 bytes 或降 queue 上限給帶圖事件。
6. **KEK 遺失被誤報為 `Tampered`**(`KeystoreWrapper.kt:72-85`):alias 不在時 `getOrCreateKey` 直接**造新 key** 再解密舊檔 → `AEADBadTagException`;UI 會顯示「檔案毀損」而非「Keystore 遺失」。unwrap 路徑不該隱式建 key。
7. **`closeOpenGap` 只關最新一個 open gap**(HealthRepository):pause gap + disconnect gap 同時開著時會留下永不關閉的 gap。
8. **Restore 以 fingerprint 去重會丟掉合法的同值訊息**(`BackupService.kt:255`):AMBIGUOUS_REPEAT 對與多次入庫的同 fingerprint row 還原後只剩一筆;可帶原 `dedupState` 一起判斷。
9. **`conversation.summaryOnlyCount` 從未遞增**(IngestRepository.commit 只寫 healthDao):欄位存在但恆 0;UI 若引用會誤導。
10. **`offer()` snapshot 建立失敗靜默丟棄**(`CaptureCoordinator.kt:232-233`):至少記一筆 diagnostic。
11. **`deleteConversation(onDone)` 失敗仍呼叫 `onDone`**(ConversationViewModel.kt):刪除失敗 UI 也會退出會話頁。
12. **`localeFilters` 含 `zh-rTW` 但無對應資源目錄**(app/build.gradle.kts):無害,可移除。
13. **`onRemoved` 為算 streamKey 而完整建一次 snapshot**(`CaptureCoordinator.kt:172`):浪費且在 binder 回呼後的 Default dispatcher 上做全量 extras 讀取;可只讀 tag/id/package。

---

## Other observations(讀者可能懷疑、但已驗證為正確的部分)

- **隱私紀律確實成立**:全 repo 生產程式碼 0 個 `Log.`/`println`/`Timber`;診斷摘要(`HealthViewModel.diagnosticsSummary`)逐行檢查為無正文;`DiagnosticEventEntity` 只有 code/detail(enum 名與 parser id)/package。未發現任何把 body/URI 寫進診斷的路徑。
- **違禁 API 缺席已驗證**:無 `cancelNotification`、無來源 `contentIntent`/`deleteIntent`/`RemoteInput` 使用;唯一 `setContentIntent` 是自家 reminder 通知(合法)。開來源 App 走 `getLaunchIntentForPackage` 且有事前確認 dialog(ConversationScreen:246)。
- **Reminder 迴圈防護正確**:reminder 不帶 `EXTRA_SYNTHETIC`,`isCapturable` 對自家 package 要求該 marker,故不會自我捕捉。
- **Checkpoint 索引算術正確**(brief 特別點名):`newWindow.items` 是 `finalDecisions` 的尾段(`takeLast(maxWindow)`),故 `storedIds[decisions.size - newWindow.items.size + i]` 的對應成立;suppressed 項 fallback 到 `item.messageId`(New 為 null)也是安全值。
- **Journal-first 順序正確**:`process()` 先 `ingest.journal` 成功才計 accepted、才進 parse/commit;replay 只撈 PENDING;`journal` 用 UUID eventId + IGNORE,無重複風險。**撤權**(disconnect)路徑的 generation fence 正確——問題只在 pause/來源刪除(見 C2)。
- **備份容器密碼學設計合格**:HKDF-SHA256(RFC vectors 有測)推導 → Tink AES-GCM-HKDF streaming AEAD,header(magic+version+salt)作 AAD 綁定;import 全量 staging + 行數/大小/筆數上限 + manifest/End 雙重 counts 比對 + 全部 tag 驗完才單一交易 apply;錯 key/截斷/竄改都在 staging 階段擋下,原 vault 不動;apply 失敗會清已寫媒體檔。C3 以外的部分我找不到能讓 vault 半套用的路徑。
- **Key 管理符合 spec §9**:`setUserAuthenticationRequired(false)` 有註解說明理由;`fallbackToDestructiveMigration` 不存在;`DatabaseHolder` 對「key array 不可清零(pooled connections 重用)」的 NOTE 是正確的(SupportOpenHelperFactory 行為);key 失敗一律進 Locked、僅使用者明確 `deleteEverything` 才毀 key。
- **雙語字串完整 parity**:designsystem 296/296、capture 皆逐 name diff 相同;資料品質狀態均為文字+圖示。
- **Manifest/建置閘門符合宣稱**:`INTERNET`/`ACCESS_NETWORK_STATE` 用 `tools:node="remove"` 主動移除;`check-permissions.sh` 另擋 QUERY_ALL_PACKAGES;`allowBackup=false` + data extraction rules 全排除;Room `exportSchema=true` 且 schema JSON 已入庫。
- **測試實況**:本機 `./gradlew … test` 全綠(88 tests / 0 fail,含 1,000-seed property test);§7.2 六個例子確為 ReconcilerTest 的字面測項。SCOPE.md 的「done/not done」宣稱與程式碼對照大致誠實——唯二例外已列為 C2(README generation 宣稱)與 I4(multiplicity 在 commit 層失守)。
- **ProGuard**:自訂規則僅 SQLCipher 兩條,其餘依賴(Room/Hilt/Tink/kotlinx-serialization)靠 consumer rules;作者宣稱 R8 release 在實機跑過,合理可信,但 journal payload 的 kotlinx-serialization decode 建議在 release smoke 清單中明確驗一次。
- **material3 1.5.0-alpha27 風險已被 ADR-0002 誠實記錄**,並已因 alpha 元件實際壞掉而退回 `NavigationBar`/`NavigationRail`,處理方式正確。

---

## 附:建議的修復優先序

1. C3(還原媒體被刪)→ 單行級修復,先堵資料毀損。
2. C1(刪會話復活)→ suppression key 改 scope 基準 + 補測試。
3. C2(pause fence)→ generation 輪換 + README 校正。
4. I1/I2/I6(懸掛、冷啟漏抓、鎖 bypass)→ 三個都是「重啟時序」類,建議一起修一起測。
5. I7(搜尋)→ 使用者最容易撞到的功能缺陷。
6. 其餘 Important → 視 push 時程,至少開 issue 追蹤。
